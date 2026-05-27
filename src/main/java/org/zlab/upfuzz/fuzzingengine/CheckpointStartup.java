package org.zlab.upfuzz.fuzzingengine;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.zlab.upfuzz.docker.DockerCluster;
import org.zlab.upfuzz.fuzzingengine.executor.Executor;
import org.zlab.upfuzz.fuzzingengine.packet.TestPlanPacket;
import org.zlab.upfuzz.hbase.HBaseDockerCluster;
import org.zlab.upfuzz.hdfs.HdfsDockerCluster;
import org.zlab.upfuzz.utils.Utilities;

final class CheckpointStartup {
    private static final Logger logger = LogManager
            .getLogger(CheckpointStartup.class);

    private CheckpointStartup() {
    }

    static boolean startup(Executor executor, TestPlanPacket testPlanPacket,
            String laneName) throws Exception {
        validateCheckpointMode(executor, laneName);

        if (!Config.getConf().checkpointAllLanes
                && !FuzzingClient.LANE_ROLLING.equals(laneName)) {
            return executor.startup();
        }

        logDockerCheckpointAvailability();
        boolean reusableCheckpoint = Config.getConf().checkpointReuse;
        CacheSpec cacheSpec = buildCacheSpec(executor, testPlanPacket, laneName,
                reusableCheckpoint);
        if (reusableCheckpoint) {
            if (cacheImagesExist(cacheSpec)) {
                if (startFromImageCheckpoint(executor, cacheSpec, laneName,
                        "reusable checkpoint cache")) {
                    return true;
                }
                logger.warn(
                        "[CHECKPOINT_REUSE] Cached startup failed for {}; invalidating cache and rebuilding",
                        cacheSpec.cacheKey);
                executor.teardown();
                removeCacheImages(cacheSpec);
            }
        }
        executor.configureCheckpointReuseStartup(Collections.emptyMap(),
                cacheSpec.subnetID, Collections.emptySet());

        if (!executor.startup()) {
            return false;
        }

        prepareCheckpointPrefix(executor, laneName);
        if (Config.getConf().useTrace) {
            executor.clearTraceAllNodes();
        }

        boolean cacheImagesCreated = false;
        try {
            executor.dockerCluster.createReusableCheckpointImages(
                    cacheSpec.imageNames);
            cacheImagesCreated = true;
            waitForCheckpointReady(executor,
                    laneName + " checkpoint image source");
            writeCacheManifest(cacheSpec);
            logger.info("[CHECKPOINT_REUSE] Built checkpoint image cache {}",
                    cacheSpec.cacheKey);

            executor.teardown();
            return startFromImageCheckpoint(executor, cacheSpec, laneName,
                    reusableCheckpoint
                            ? "new reusable checkpoint cache"
                            : "ephemeral checkpoint image");
        } finally {
            if (shouldRemoveCheckpointImagesAfterStartup(cacheImagesCreated,
                    reusableCheckpoint)) {
                removeCacheImagesBestEffort(cacheSpec);
            }
        }
    }

    @SuppressWarnings("unused")
    private static void configureDockerCheckpointScope(Executor executor) {
        if ("hbase".equals(executor.systemID)) {
            executor.dockerCluster.configureCheckpointRestoreNodes(null);
            executor.dockerCluster.configureCheckpointRestoreExtraNodes(false);
            return;
        }
        executor.dockerCluster.configureCheckpointRestoreNodes(null);
        executor.dockerCluster.configureCheckpointRestoreExtraNodes(true);
    }

    private static void validateCheckpointMode(Executor executor,
            String laneName) {
        if (Config.getConf().testingMode != 5) {
            throw new IllegalStateException(
                    "enableCheckpointRestore is only supported for testingMode=5");
        }
        if (!Config.getConf().differentialExecution) {
            throw new IllegalStateException(
                    "enableCheckpointRestore requires differentialExecution=true");
        }
        if (!FuzzingClient.LANE_ONLY_OLD.equals(laneName)
                && !FuzzingClient.LANE_ROLLING.equals(laneName)
                && !FuzzingClient.LANE_ONLY_NEW.equals(laneName)) {
            throw new IllegalArgumentException(
                    "Unknown checkpoint lane: " + laneName);
        }
        if (!Config.getConf().checkpointAllowNonCassandra
                && !"cassandra".equals(executor.systemID)) {
            throw new IllegalStateException(String.format(
                    "enableCheckpointRestore is Cassandra-first; %s needs system-specific validation before enabling checkpointAllowNonCassandra",
                    executor.systemID));
        }
        FuzzingClient.getCheckpointSelectedNodeSet();
    }

    private static boolean isDockerCheckpointAvailable()
            throws IOException, InterruptedException {
        Process versionProcess = Utilities.exec(new String[] {
                "docker", "version", "--format",
                "{{.Server.Experimental}}"
        }, ".");
        String experimental = Utilities.readProcess(versionProcess).trim();
        if (versionProcess.exitValue() != 0
                || !"true".equalsIgnoreCase(experimental)) {
            return false;
        }
        Process helpProcess = Utilities.exec(new String[] {
                "docker", "checkpoint", "--help"
        }, ".");
        Utilities.readProcess(helpProcess);
        return helpProcess.exitValue() == 0;
    }

    private static void logDockerCheckpointAvailability()
            throws IOException, InterruptedException {
        if (isDockerCheckpointAvailable()) {
            logger.info(
                    "[CHECKPOINT] Docker CRIU checkpoint CLI is available; using image checkpoint backend for CloudLab-safe startup");
            return;
        }
        logger.warn(
                "[CHECKPOINT] Docker CRIU checkpoint restore is unavailable; using image checkpoint backend");
    }

    private static void waitForCheckpointReady(Executor executor, String phase)
            throws Exception {
        if (executor.dockerCluster instanceof HBaseDockerCluster) {
            ((HBaseDockerCluster) executor.dockerCluster)
                    .waitForCheckpointRestoreReady(phase);
        }
    }

    private static boolean startFromImageCheckpoint(Executor executor,
            CacheSpec cacheSpec, String laneName, String source)
            throws Exception {
        executor.configureCheckpointReuseStartup(cacheSpec.imageNames,
                cacheSpec.subnetID, checkpointReuseUpgradedNodes(laneName));
        if (!executor.startup()) {
            return false;
        }
        waitForCheckpointReady(executor, laneName + " " + source);
        requireTraceEndpointReady(executor, laneName + " " + source);
        if (Config.getConf().useTrace) {
            executor.clearTraceAllNodes();
        }
        logger.info(
                "[CHECKPOINT_REUSE] Lane {} started from {} {}",
                laneName, source, cacheSpec.cacheKey);
        return true;
    }

    private static void requireTraceEndpointReady(Executor executor,
            String phase) throws Exception {
        if (!Config.getConf().useTrace) {
            return;
        }
        int attempts = 12;
        long sleepMillis = 5000L;
        Exception lastException = null;
        for (int attempt = 1; attempt <= attempts; attempt++) {
            int readyNodes = 0;
            for (int nodeIndex = 0; nodeIndex < executor.nodeNum; nodeIndex++) {
                try {
                    if (executor.dockerCluster.getDocker(nodeIndex)
                            .collectTrace() != null) {
                        readyNodes++;
                    }
                } catch (Exception e) {
                    lastException = e;
                    logger.warn(
                            "[CHECKPOINT_TRACE] Trace endpoint not ready for lane phase {} node {} attempt {}/{}: {}",
                            phase, nodeIndex, attempt, attempts, e.toString());
                    break;
                }
            }
            if (readyNodes == executor.nodeNum) {
                logger.info(
                        "[CHECKPOINT_TRACE] Trace endpoints ready for {} on {} nodes",
                        phase, readyNodes);
                return;
            }
            Thread.sleep(sleepMillis);
        }
        throw new IllegalStateException(String.format(
                "Trace endpoints did not become ready after checkpoint startup phase %s",
                phase), lastException);
    }

    private static void prepareCheckpointPrefix(Executor executor,
            String laneName) throws Exception {
        Set<Integer> selectedNodes = FuzzingClient
                .getCheckpointSelectedNodeSet();
        if (FuzzingClient.LANE_ROLLING.equals(laneName)) {
            stopHdfsSNNForCheckpointPrefix(executor, laneName);
            executor.dockerCluster.prepareUpgrade();
            for (int nodeIndex : selectedNodes) {
                executor.dockerCluster.upgrade(nodeIndex);
            }
            return;
        }

        for (int nodeIndex : selectedNodes) {
            if (!executor.dockerCluster.restartContainer(nodeIndex)) {
                throw new IllegalStateException(String.format(
                        "Cannot restart node %d for checkpoint baseline lane %s",
                        nodeIndex, laneName));
            }
        }
    }

    private static void stopHdfsSNNForCheckpointPrefix(Executor executor,
            String laneName) {
        if (!shouldStopHdfsSNNBeforeCheckpointUpgrade(executor, laneName)) {
            return;
        }
        if (!(executor.dockerCluster instanceof HdfsDockerCluster)) {
            throw new IllegalStateException(String.format(
                    "HDFS checkpoint prefix expected HdfsDockerCluster but found %s",
                    executor.dockerCluster == null ? "null"
                            : executor.dockerCluster.getClass().getName()));
        }
        ((HdfsDockerCluster) executor.dockerCluster).stopSNN();
    }

    static boolean shouldStopHdfsSNNBeforeCheckpointUpgrade(Executor executor,
            String laneName) {
        return FuzzingClient.LANE_ROLLING.equals(laneName)
                && executor != null
                && "hdfs".equals(executor.systemID);
    }

    @SuppressWarnings("unused")
    private static String checkpointNameFor(TestPlanPacket testPlanPacket,
            String laneName, String executorID) {
        String testPlanKey = sanitize(testPlanPacket.configFileName)
                + "-test" + testPlanPacket.testPacketID;
        return sanitize("mode5-" + testPlanKey + "-" + laneName + "-"
                + executorID);
    }

    private static String sanitize(String raw) {
        return raw == null ? "unknown"
                : raw.replaceAll("[^a-zA-Z0-9._-]", "_");
    }

    private static CacheSpec buildCacheSpec(Executor executor,
            TestPlanPacket testPlanPacket, String laneName, boolean reusable)
            throws IOException {
        String manifest = String.join("\n",
                "version=mode5-checkpoint-image-v6",
                "system=" + executor.systemID,
                "original=" + Config.getConf().originalVersion,
                "upgraded=" + Config.getConf().upgradedVersion,
                "nodeNum=" + Config.getConf().nodeNum,
                "direction=" + executor.direction,
                "lane=" + laneName,
                "selectedNodes="
                        + FuzzingClient.getCheckpointSelectedNodeSet(),
                "checkpointAllLanes=" + Config.getConf().checkpointAllLanes,
                "reusable=" + reusable,
                reusable ? "executor=stable"
                        : "executor=" + executor.executorID,
                "configFingerprint="
                        + configFingerprint(executor.configPath));
        String cacheKey = sha256Hex(manifest).substring(0, 16);
        int subnetID = checkpointSubnetID(cacheKey, laneName);
        Map<String, String> imageNames = checkpointCacheImageNames(
                executor.systemID, cacheKey, Config.getConf().nodeNum);
        return new CacheSpec(cacheKey, manifest, subnetID, imageNames);
    }

    static Map<String, String> checkpointCacheImageNames(String systemID,
            String cacheKey, int nodeNum) {
        Map<String, String> imageNames = new LinkedHashMap<>();
        for (int i = 0; i < nodeNum; i++) {
            imageNames.put(DockerCluster.checkpointImageKeyForMainNode(i),
                    cacheImageName(cacheKey, "node" + i));
        }
        // HBase's HDFS container is a component dependency, not an upgrade
        // target, so reusable checkpoint caches only include HBase main nodes.
        return imageNames;
    }

    private static int checkpointSubnetID(String cacheKey, String laneName) {
        int laneBase;
        if (FuzzingClient.LANE_ONLY_OLD.equals(laneName)) {
            laneBase = 30;
        } else if (FuzzingClient.LANE_ROLLING.equals(laneName)) {
            laneBase = 110;
        } else if (FuzzingClient.LANE_ONLY_NEW.equals(laneName)) {
            laneBase = 190;
        } else {
            laneBase = 20;
        }
        return laneBase + Math.floorMod(cacheKey.hashCode(), 50);
    }

    private static Set<Integer> checkpointReuseUpgradedNodes(String laneName) {
        if (FuzzingClient.LANE_ROLLING.equals(laneName)) {
            return FuzzingClient.getCheckpointSelectedNodeSet();
        }
        return Collections.emptySet();
    }

    private static String cacheImageName(String cacheKey, String suffix) {
        return "upfuzz_checkpoint_cache:" + cacheKey + "-" + suffix;
    }

    private static String configFingerprint(Path configPath)
            throws IOException {
        if (configPath == null) {
            return "none";
        }
        if (!Files.exists(configPath)) {
            return "missing";
        }
        MessageDigest digest = sha256Digest();
        List<Path> files = new ArrayList<>();
        try (Stream<Path> stream = Files.walk(configPath)) {
            stream.filter(Files::isRegularFile).forEach(files::add);
        }
        files.sort(Comparator.comparing(path -> configPath.relativize(path)
                .toString()));
        for (Path file : files) {
            Path relative = configPath.relativize(file);
            digest.update(relative.toString().getBytes(StandardCharsets.UTF_8));
            digest.update(Long.toString(Files.size(file))
                    .getBytes(StandardCharsets.UTF_8));
            digest.update(Files.readAllBytes(file));
        }
        return hex(digest.digest());
    }

    private static boolean cacheImagesExist(CacheSpec cacheSpec)
            throws IOException, InterruptedException {
        for (String imageName : cacheSpec.imageNames.values()) {
            Process inspectProcess = Utilities.exec(new String[] {
                    "docker", "image", "inspect", imageName
            }, ".");
            Utilities.readProcess(inspectProcess);
            if (inspectProcess.exitValue() != 0) {
                return false;
            }
        }
        return true;
    }

    private static void removeCacheImages(CacheSpec cacheSpec)
            throws IOException, InterruptedException {
        for (String imageName : cacheSpec.imageNames.values()) {
            Process rmProcess = Utilities.exec(new String[] {
                    "docker", "image", "rm", "-f", imageName
            }, ".");
            Utilities.readProcess(rmProcess);
        }
    }

    private static void removeCacheImagesBestEffort(CacheSpec cacheSpec) {
        try {
            removeCacheImages(cacheSpec);
            logger.info(
                    "[CHECKPOINT_REUSE] Removed ephemeral checkpoint image cache {}",
                    cacheSpec.cacheKey);
        } catch (IOException | InterruptedException e) {
            logger.warn(
                    "[CHECKPOINT_REUSE] Failed to remove ephemeral checkpoint image cache {}",
                    cacheSpec.cacheKey, e);
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
        }
    }

    static boolean shouldRemoveCheckpointImagesAfterStartup(
            boolean cacheImagesCreated, boolean reusableCheckpoint) {
        return cacheImagesCreated && !reusableCheckpoint;
    }

    private static void writeCacheManifest(CacheSpec cacheSpec)
            throws IOException {
        File cacheDir = new File(Config.getConf().checkpointCacheDir,
                cacheSpec.cacheKey);
        Files.createDirectories(cacheDir.toPath());
        List<String> lines = new ArrayList<>();
        lines.add(cacheSpec.manifest);
        lines.add("subnetID=" + cacheSpec.subnetID);
        for (Map.Entry<String, String> entry : cacheSpec.imageNames
                .entrySet()) {
            lines.add("image." + entry.getKey() + "=" + entry.getValue());
        }
        Files.write(new File(cacheDir, "manifest.txt").toPath(), lines,
                StandardCharsets.UTF_8);
    }

    private static String sha256Hex(String value) {
        MessageDigest digest = sha256Digest();
        return hex(digest.digest(value.getBytes(StandardCharsets.UTF_8)));
    }

    private static MessageDigest sha256Digest() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String hex(byte[] bytes) {
        StringBuilder builder = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            builder.append(String.format("%02x", b));
        }
        return builder.toString();
    }

    private static final class CacheSpec {
        final String cacheKey;
        final String manifest;
        final int subnetID;
        final Map<String, String> imageNames;

        CacheSpec(String cacheKey, String manifest, int subnetID,
                Map<String, String> imageNames) {
            this.cacheKey = cacheKey;
            this.manifest = manifest;
            this.subnetID = subnetID;
            this.imageNames = imageNames;
        }
    }
}
