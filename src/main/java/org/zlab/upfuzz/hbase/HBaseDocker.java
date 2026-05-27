package org.zlab.upfuzz.hbase;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.apache.commons.text.StringSubstitutor;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.zlab.upfuzz.docker.Docker;
import org.zlab.upfuzz.docker.DockerCluster;
import org.zlab.upfuzz.docker.DockerMeta;
import org.zlab.upfuzz.fuzzingengine.Config;
import org.zlab.upfuzz.fuzzingengine.LogInfo;
import org.zlab.upfuzz.fuzzingengine.packet.ValidationResult;
import org.zlab.upfuzz.utils.Utilities;

public class HBaseDocker extends Docker {
    protected final Logger logger = LogManager.getLogger(getClass());
    private static final Pattern LEADING_MAJOR_PATTERN = Pattern
            .compile("^(\\d+)");
    private static final Pattern ANY_MAJOR_PATTERN = Pattern.compile("(\\d+)");

    public enum NodeType {
        ZOOKEEPER, MASTER, REGIONSERVER
    }

    String composeYaml;
    String javaToolOpts;
    int HBaseDaemonPort = 36000;
    public int direction;
    private final HBaseDockerCluster hbaseDockerCluster;

    // Un-swapped versions for Docker image/container naming
    String configOriginalVersion;
    String configUpgradedVersion;

    public String seedIP;

    public HBaseDocker(HBaseDockerCluster dockerCluster, int index) {
        this.hbaseDockerCluster = dockerCluster;
        this.index = index;
        this.direction = dockerCluster.direction;
        workdir = dockerCluster.workdir;
        system = dockerCluster.system;
        originalVersion = (dockerCluster.direction == 0)
                ? dockerCluster.originalVersion
                : dockerCluster.upgradedVersion;
        upgradedVersion = (dockerCluster.direction == 0)
                ? dockerCluster.upgradedVersion
                : dockerCluster.originalVersion;
        networkName = dockerCluster.networkName;
        subnet = dockerCluster.subnet;
        hostIP = dockerCluster.hostIP;
        networkIP = DockerCluster.getKthIP(hostIP, index);
        seedIP = dockerCluster.seedIP;
        agentPort = dockerCluster.agentPort;
        includes = HBaseDockerCluster.includes;
        excludes = HBaseDockerCluster.excludes;
        executorID = dockerCluster.executorID;
        serviceName = "DC3N" + index;

        // Store un-swapped config versions for image/container naming
        configOriginalVersion = Config.getConf().originalVersion;
        configUpgradedVersion = Config.getConf().upgradedVersion;

        collectFormatCoverage = dockerCluster.collectFormatCoverage;
        configPath = dockerCluster.configpath;

        if (Config.getConf().testSingleVersion)
            containerName = "hbase-" + configOriginalVersion + "_"
                    + executorID + "_N" + index;
        else
            containerName = "hbase-" + configOriginalVersion + "_"
                    + configUpgradedVersion + "_" + executorID + "_N"
                    + index;
    }

    @Override
    public String getNetworkIP() {
        return networkIP;
    }

    public String getNodeRole() {
        if (index == 0)
            return "master";
        return "regionserver" + (index - 1);
    }

    @Override
    protected String checkpointHardStopCommand() {
        String hbaseProcessPattern = "org[.]apache[.]hadoop|"
                + "org[.]apache[.]hbase|"
                + "org[.]jruby|"
                + "J[p]s|"
                + "[s]leep 5|"
                + "H[M]aster|"
                + "H[R]egionServer|"
                + "H[Q]uorumPeer|"
                + "hbase_daemon[.]py|"
                + "hbase-daemon-init[.]sh|"
                + "hbase-daemon[.]sh|"
                + "hbase-init[.]sh|"
                + "hbase [s]hell|"
                + "HBaseShell[D]aemon|"
                + "hbase[_]shell";
        return "pkill -TERM -f '" + hbaseProcessPattern + "' || true; "
                + "sleep 2; "
                + "pkill -KILL -f '" + hbaseProcessPattern + "' || true; "
                + "for i in 1 2 3 4 5; do "
                + "leftovers=$(pgrep -fa '" + hbaseProcessPattern
                + "' || true); "
                + "[ -z \"$leftovers\" ] && break; "
                + "echo \"$leftovers\"; "
                + "pkill -KILL -f '" + hbaseProcessPattern + "' || true; "
                + "sleep 1; "
                + "done; "
                + "ps -eo pid,ppid,stat,comm,args";
    }

    @Override
    public void stopServicesForCheckpoint()
            throws IOException, InterruptedException {
        Process stopProcess = runInContainer(new String[] {
                "/bin/bash", "-c",
                "supervisorctl stop all || true"
        });
        String output = Utilities.readProcess(stopProcess);
        stopProcess.waitFor();
        logger.info("[CHECKPOINT] Stopped all HBase container services in {}: {}",
                containerName, output.trim());

        Process hardStopProcess = runInContainer(new String[] {
                "/bin/bash", "-c", checkpointHardStopCommand()
        });
        String hardStopOutput = Utilities.readProcess(hardStopProcess);
        hardStopProcess.waitFor();
        logger.info("[CHECKPOINT] HBase checkpoint process table in {}: {}",
                containerName, hardStopOutput.trim());
    }

    @Override
    public void prepareReusableCheckpointImage()
            throws IOException, InterruptedException {
        Process cleanProcess = runInContainer(new String[] {
                "/bin/bash", "-c",
                "rm -rf /usr/local/zookeeper/* "
                        + "/tmp/hbase-* /tmp/Jetty_* /tmp/hsperfdata_* "
                        + "/tmp/upfuzz-hbase-status "
                        + "/tmp/upfuzz-supervisor-status; "
                        + "mkdir -p /usr/local/zookeeper; "
                        + "ps -eo pid,ppid,stat,comm,args"
        });
        String output = Utilities.readProcess(cleanProcess);
        cleanProcess.waitFor();
        if (cleanProcess.exitValue() != 0) {
            throw new IOException(String.format(
                    "preparing HBase reusable checkpoint image failed for %s: %s",
                    containerName, output));
        }
        logger.info(
                "[CHECKPOINT_REUSE] Prepared HBase reusable image state in {}: {}",
                containerName, output.trim());
    }

    @Override
    public void restartContainerAfterCheckpointRestore()
            throws IOException, InterruptedException {
        super.restartContainerAfterCheckpointRestore();

        Process startProcess = runInContainer(new String[] {
                "/bin/bash", "-c",
                "ready=0; "
                        + "for i in $(seq 1 60); do "
                        + "supervisorctl status >/tmp/upfuzz-supervisor-status 2>&1 "
                        + "&& { ready=1; break; }; "
                        + "sleep 1; "
                        + "done; "
                        + "[ \"$ready\" = 1 ] || "
                        + "{ cat /tmp/upfuzz-supervisor-status; exit 1; }; "
                        + "ensure_started() { "
                        + "name=\"$1\"; "
                        + "status=$(supervisorctl status \"$name\" 2>&1 || true); "
                        + "echo \"$status\" | grep -Eq '[[:space:]]+(RUNNING|STARTING)' "
                        + "|| supervisorctl start \"$name\"; "
                        + "}; "
                        + "ensure_started sshd; "
                        + "ensure_started upfuzz_hbase:hbase; "
                        + "ensure_started upfuzz_hbase:hbase_daemon; "
                        + "for i in $(seq 1 60); do "
                        + "supervisorctl status > /tmp/upfuzz-hbase-status; "
                        + "grep -Eq '^upfuzz_hbase:hbase_daemon[[:space:]]+RUNNING' "
                        + "/tmp/upfuzz-hbase-status && break; "
                        + "ensure_started upfuzz_hbase:hbase; "
                        + "ensure_started upfuzz_hbase:hbase_daemon; "
                        + "sleep 1; "
                        + "done; "
                        + "grep -Eq '^upfuzz_hbase:hbase_daemon[[:space:]]+RUNNING' "
                        + "/tmp/upfuzz-hbase-status || "
                        + "{ supervisorctl status; exit 1; }; "
                        + "supervisorctl status"
        });
        String output = Utilities.readProcess(startProcess);
        startProcess.waitFor();
        if (startProcess.exitValue() != 0) {
            throw new IOException(String.format(
                    "starting HBase services after checkpoint restore failed for %s: %s",
                    containerName, output));
        }
        logger.info("[CHECKPOINT] Started HBase services in restored {}: {}",
                containerName, output.trim());
    }

    @Override
    public String formatComposeYaml() {
        Map<String, String> formatMap = new HashMap<>();

        containerName = "hbase-" + configOriginalVersion + "_"
                + configUpgradedVersion + "_" + executorID + "_N" + index;
        formatMap.put("projectRoot", System.getProperty("user.dir"));
        formatMap.put("system", system);
        formatMap.put("originalVersion", originalVersion);
        formatMap.put("upgradedVersion", upgradedVersion);
        formatMap.put("configOriginalVersion", configOriginalVersion);
        formatMap.put("configUpgradedVersion", configUpgradedVersion);
        formatMap.put("index", Integer.toString(index));
        formatMap.put("networkName", networkName);
        formatMap.put("JAVA_TOOL_OPTIONS", javaToolOpts);
        formatMap.put("subnet", subnet);
        formatMap.put("seedIP", seedIP);
        formatMap.put("networkIP", networkIP);
        formatMap.put("agentPort", Integer.toString(agentPort));
        formatMap.put("executorID", executorID);
        formatMap.put("serviceName", serviceName);
        formatMap.put("HadoopIP", DockerCluster.getKthIP(hostIP, 100));
        formatMap.put("daemonPort", Integer.toString(HBaseDaemonPort));
        String defaultImageName = "upfuzz_" + system + ":"
                + configOriginalVersion + "_" + configUpgradedVersion;
        formatMap.put("imageName", composeImageName(defaultImageName));
        if (index == 0) {
            formatMap.put("HBaseMaster", "true");
            formatMap.put("depDockerID", "DEPN100");
        } else {
            formatMap.put("HBaseMaster", "false");
            formatMap.put("depDockerID", "DC3N0");
        }

        StringSubstitutor sub = new StringSubstitutor(formatMap);
        this.composeYaml = sub.replace(template);
        return composeYaml;
    }

    @Override
    public int start() throws Exception {
        shell = new HBaseShellDaemon(getNetworkIP(), HBaseDaemonPort,
                this.executorID,
                this);
        return 0;
    }

    private void setEnvironment() throws IOException {
        File envFile = new File(workdir,
                "./persistent/node_" + index + "/env.sh");

        FileWriter fw;
        envFile.getParentFile().mkdirs();
        fw = new FileWriter(envFile, false);
        for (String s : env) {
            fw.write("export " + s + "\n");
        }
        fw.close();
    }

    @Override
    public void teardown() {
    }

    @Override
    public boolean build() throws IOException {
        type = (direction == 0) ? "original" : "upgraded";
        String HBaseHome = "/hbase/" + originalVersion;
        String HBaseConf = "/etc/" + originalVersion;
        if (Config.getConf().useBranchCoverage) {
            javaToolOpts = "JAVA_TOOL_OPTIONS=\"-javaagent:"
                    + "/org.jacoco.agent.rt.jar"
                    + "=append=false"
                    + ",includes=" + includes + ",excludes=" + excludes +
                    ",output=dfe,address=" + hostIP + ",port=" + agentPort +
                    ",sessionid=" + system + "-" + executorID + "_"
                    + type + "-" + index +
                    checkpointRestoreJavaOptionsSuffix() +
                    "\"";
        } else {
            javaToolOpts = "JAVA_TOOL_OPTIONS=\""
                    + checkpointRestoreJavaOptions() + "\"";
        }

        int originalMajorVersion = extractMajorVersion(originalVersion);
        if (Config.getConf().debug) {
            logger.debug(
                    "[HKLOG] original main version = " + originalMajorVersion);
        }
        String pythonVersion = pythonVersionForMajor(originalMajorVersion);

        env = new String[] {
                "HBASE_HOME=\"" + HBaseHome + "\"",
                "HBASE_CONF=\"" + HBaseConf + "\"", javaToolOpts,
                "HBASE_SHELL_DAEMON_PORT=\"" + HBaseDaemonPort + "\"",
                "CUR_STATUS=ORI",
                "PYTHON=" + pythonVersion,
                "ENABLE_FORMAT_COVERAGE=" + (Config.getConf().useFormatCoverage
                        && collectFormatCoverage),
                "ENABLE_NET_COVERAGE=" + Config.getConf().useTrace,
                "ENABLE_NETWORK_TRACE=" + Config.getConf().useTrace,
                "NET_TRACE_NODE_ID=" + executorID + "-N" + index,
                "NET_TRACE_NODE_ROLE=" + getNodeRole()
        };

        setEnvironment();

        if (configPath != null) {
            copyConfig(configPath, direction);
        }
        return true;
    }

    @Override
    public void flush() throws Exception {
    }

    public void rollingUpgrade() throws Exception {
        upgrade();
        // Reconnect to the upgraded shell daemon endpoint.
        start();
        waitForPostUpgradeHealth();
    }

    private void waitForPostUpgradeHealth() throws Exception {
        if (index == 0) {
            waitForMasterAndClusterReady(
                    String.format("post-upgrade node[%d] master", index));
            return;
        }

        waitForRegionServerLocalReady();
        waitForRegionServerRejoinedFromMaster();
    }

    public void waitForMasterAndClusterReady(String phase) throws Exception {
        waitForMasterControlPlaneReady();
        waitForMasterReportsClusterHealthy(
                hbaseDockerCluster.getExpectedRegionServerCount(), phase);
    }

    public void waitForCheckpointFunctionalReadiness(String phase)
            throws Exception {
        final int maxAttempts = Math.max(1,
                Config.getConf().hbaseDaemonRetryTimes);
        final int sleepMillis = 5000;
        Exception lastException = null;
        ValidationResult lastProbe = null;

        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            String probeTable = checkpointProbeTableName(attempt);
            try {
                ValidationResult status = execCommandStructured(
                        "status 'simple'");
                lastProbe = status;
                if (!isSuccessfulNonTransientProbe(status)) {
                    logger.info(String.format(
                            "Node[%d] checkpoint functional probe %d/%d not ready during %s at status: %s",
                            index, attempt, maxAttempts, phase,
                            summarizeProbe(status)));
                    Thread.sleep(sleepMillis);
                    continue;
                }

                ValidationResult namespaces = execCommandStructured(
                        "list_namespace");
                lastProbe = namespaces;
                if (!isSuccessfulNonTransientProbe(namespaces)) {
                    logger.info(String.format(
                            "Node[%d] checkpoint functional probe %d/%d not ready during %s at list_namespace: %s",
                            index, attempt, maxAttempts, phase,
                            summarizeProbe(namespaces)));
                    Thread.sleep(sleepMillis);
                    continue;
                }

                ValidationResult tables = execCommandStructured("list");
                lastProbe = tables;
                if (!isSuccessfulNonTransientProbe(tables)) {
                    logger.info(String.format(
                            "Node[%d] checkpoint functional probe %d/%d not ready during %s at list: %s",
                            index, attempt, maxAttempts, phase,
                            summarizeProbe(tables)));
                    Thread.sleep(sleepMillis);
                    continue;
                }

                runCheckpointTableRoundTrip(probeTable);
                logger.info(String.format(
                        "Node[%d] checkpoint functional readiness passed after %d attempts during %s",
                        index, attempt, phase));
                return;
            } catch (Exception e) {
                lastException = e;
                logger.info(String.format(
                        "Node[%d] checkpoint functional probe %d/%d failed during %s: %s",
                        index, attempt, maxAttempts, phase, e.toString()));
                bestEffortDropCheckpointProbeTable(probeTable);
                Thread.sleep(sleepMillis);
            }
        }

        String reason = lastException != null
                ? lastException.toString()
                : summarizeProbe(lastProbe);
        throw new IOException(
                "HBase checkpoint restore timed out waiting for functional readiness ("
                        + phase + "): " + reason);
    }

    private boolean isSuccessfulNonTransientProbe(ValidationResult probe) {
        if (probe == null || !probe.isSuccess()) {
            return false;
        }
        String mergedLower = safeLower(safe(probe.stdout) + "\n"
                + safe(probe.stderr));
        return !isMasterTransient(mergedLower)
                && !containsMetaUnavailable(mergedLower);
    }

    private boolean containsMetaUnavailable(String mergedLower) {
        return mergedLower.contains("notservingregionexception")
                || mergedLower.contains("hbase:meta")
                        && mergedLower.contains("not online");
    }

    private void runCheckpointTableRoundTrip(String tableName)
            throws Exception {
        bestEffortDropCheckpointProbeTable(tableName);
        requireCheckpointProbeSuccess(
                execCommandStructured("create '" + tableName + "', 'cf'"),
                "create", tableName);
        requireCheckpointProbeSuccess(
                execCommandStructured("disable '" + tableName + "'"),
                "disable", tableName);
        requireCheckpointProbeSuccess(
                execCommandStructured("drop '" + tableName + "'"),
                "drop", tableName);
    }

    private void requireCheckpointProbeSuccess(ValidationResult result,
            String op, String tableName) throws IOException {
        if (isSuccessfulNonTransientProbe(result)) {
            return;
        }
        throw new IOException(String.format(
                "checkpoint readiness table probe failed during %s %s: %s",
                op, tableName, summarizeProbe(result)));
    }

    private void bestEffortDropCheckpointProbeTable(String tableName) {
        try {
            execCommandStructured("disable '" + tableName + "'");
        } catch (Exception ignored) {
        }
        try {
            execCommandStructured("drop '" + tableName + "'");
        } catch (Exception ignored) {
        }
    }

    private String checkpointProbeTableName(int attempt) {
        String executor = executorID == null ? "unknown" : executorID;
        executor = executor.replaceAll("[^A-Za-z0-9_]", "_");
        return "upfuzz_restore_probe_" + executor + "_" + index + "_"
                + attempt;
    }

    private void waitForMasterControlPlaneReady() throws Exception {
        final int maxAttempts = Math.max(1,
                Config.getConf().hbaseDaemonRetryTimes);
        final int sleepMillis = 5000;
        final String[] probeCommands = {
                "status 'simple'",
                "list_namespace"
        };

        ValidationResult lastProbe = null;
        Exception lastException = null;

        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            for (String probeCommand : probeCommands) {
                try {
                    ValidationResult probe = execCommandStructured(
                            probeCommand);
                    lastProbe = probe;
                    String mergedLower = safeLower(probe.stdout)
                            + "\n"
                            + safeLower(probe.stderr);
                    boolean transientZkIssue = isZkTransient(mergedLower);
                    if (probe.isSuccess()) {
                        logger.info(String.format(
                                "Node[%d] control plane ready after %d attempts via {%s} (zkTransient=%s)",
                                index, attempt, probeCommand,
                                transientZkIssue));
                        return;
                    }

                    logger.info(String.format(
                            "Node[%d] readiness probe %d/%d not ready (cmd={%s}, exit=%d, class=%s, zkTransient=%s)",
                            index, attempt, maxAttempts, probeCommand,
                            probe.exitCode, probe.failureClass,
                            transientZkIssue));
                } catch (Exception e) {
                    lastException = e;
                    logger.info(String.format(
                            "Node[%d] readiness probe %d/%d failed on {%s}: %s",
                            index, attempt, maxAttempts, probeCommand,
                            e.toString()));
                }
            }

            Thread.sleep(sleepMillis);
        }

        String reason = lastException != null
                ? lastException.toString()
                : summarizeProbe(lastProbe);
        throw new IOException(
                "HBase rolling upgrade timed out waiting for control-plane readiness: "
                        + reason);
    }

    private void waitForRegionServerLocalReady() throws Exception {
        final int maxAttempts = Math.max(1,
                Config.getConf().hbaseDaemonRetryTimes);
        final int sleepMillis = 5000;
        final String rsJpsCheck = "jps -l | grep -q 'org.apache.hadoop.hbase.regionserver.HRegionServer'";
        final String rsPortCheck = String.format(
                "(ss -ltn 2>/dev/null || netstat -ltn 2>/dev/null) | grep -q ':%d\\b'",
                Config.getConf().REGIONSERVER_PORT);

        ProbeCommandResult lastJps = null;
        ProbeCommandResult lastPort = null;
        Exception lastException = null;

        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            try {
                lastJps = runContainerProbe(rsJpsCheck);
                lastPort = runContainerProbe(rsPortCheck);
                boolean jpsReady = lastJps.exitCode == 0;
                boolean portReady = lastPort.exitCode == 0;

                if (jpsReady && portReady) {
                    logger.info(String.format(
                            "Node[%d] regionserver local readiness passed after %d attempts",
                            index, attempt));
                    return;
                }

                logger.info(String.format(
                        "Node[%d] regionserver readiness %d/%d not ready (jpsExit=%d, portExit=%d)",
                        index, attempt, maxAttempts,
                        lastJps.exitCode, lastPort.exitCode));
            } catch (Exception e) {
                lastException = e;
                logger.info(String.format(
                        "Node[%d] regionserver readiness %d/%d failed: %s",
                        index, attempt, maxAttempts, e.toString()));
            }

            Thread.sleep(sleepMillis);
        }

        String reason;
        if (lastException != null) {
            reason = lastException.toString();
        } else {
            String jpsOut = lastJps == null ? "" : lastJps.output;
            String portOut = lastPort == null ? "" : lastPort.output;
            reason = String.format(
                    "jpsExit=%s,portExit=%s,jpsOut=%s,portOut=%s",
                    lastJps == null ? "NA" : Integer.toString(lastJps.exitCode),
                    lastPort == null ? "NA"
                            : Integer.toString(lastPort.exitCode),
                    compactText(jpsOut), compactText(portOut));
        }
        throw new IOException(
                "HBase rolling upgrade timed out waiting for regionserver local readiness: "
                        + reason);
    }

    private void waitForRegionServerRejoinedFromMaster() throws Exception {
        HBaseDocker masterDocker = hbaseDockerCluster.getMasterDocker();
        if (masterDocker == null) {
            throw new IOException(
                    "Master docker is unavailable while checking regionserver rejoin");
        }

        masterDocker.waitForMasterAndClusterReady(
                String.format("post-upgrade node[%d] regionserver", index));

        String regionServerHost = hbaseDockerCluster.getRegionServerHostForNode(
                index);
        if (regionServerHost != null) {
            masterDocker.bestEffortConfirmRegionServerListed(regionServerHost);
        }
    }

    private void waitForMasterReportsClusterHealthy(int expectedRegionServers,
            String phase) throws Exception {
        final int maxAttempts = Math.max(1,
                Config.getConf().hbaseDaemonRetryTimes);
        final int sleepMillis = 5000;
        final boolean checkpointReuseExecution = isCheckpointReuseExecutionPhase(
                phase);
        ValidationResult lastProbe = null;
        Exception lastException = null;

        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            try {
                ValidationResult status = execCommandStructured(
                        "status 'simple'");
                lastProbe = status;
                String merged = safe(status.stdout) + "\n"
                        + safe(status.stderr);
                String mergedLower = safeLower(merged);
                Integer liveServers = extractLiveServerCount(merged);
                Integer deadServers = extractDeadServerCount(merged);
                Integer onlineRegions = extractOnlineRegionCount(merged);
                boolean transientState = isMasterTransient(mergedLower);
                Boolean detailedHostsReady = null;
                if (liveServers == null && expectedRegionServers > 0) {
                    detailedHostsReady = areExpectedRegionServersListedInDetailedStatus();
                }
                boolean liveReady = expectedRegionServers == 0
                        || (liveServers != null
                                && liveServers >= expectedRegionServers)
                        || Boolean.TRUE.equals(detailedHostsReady);
                boolean deadReady = deadServers == null || deadServers == 0;
                if (checkpointReuseExecution && status.isSuccess()
                        && !transientState && liveReady && !deadReady) {
                    boolean clearSucceeded = bestEffortClearDeadServers(attempt,
                            phase, deadServers);
                    if (clearSucceeded) {
                        ValidationResult postClear = execCommandStructured(
                                "status 'simple'");
                        String postClearMerged = safe(postClear.stdout) + "\n"
                                + safe(postClear.stderr);
                        String postClearMergedLower = safeLower(
                                postClearMerged);
                        Integer postClearLiveServers = extractLiveServerCount(
                                postClearMerged);
                        Integer postClearDeadServers = extractDeadServerCount(
                                postClearMerged);
                        Integer postClearOnlineRegions = extractOnlineRegionCount(
                                postClearMerged);
                        boolean postClearTransient = isMasterTransient(
                                postClearMergedLower);
                        boolean postClearLiveReady = expectedRegionServers == 0
                                || (postClearLiveServers != null
                                        && postClearLiveServers >= expectedRegionServers);
                        boolean postClearDeadReady = postClearDeadServers == null
                                || postClearDeadServers == 0;
                        if (postClear.isSuccess() && !postClearTransient
                                && postClearLiveReady && postClearDeadReady) {
                            logger.info(String.format(
                                    "Node[%d] master-side cluster health ready after %d attempts (%s): liveServers=%s, deadServers=%s, onlineRegions=%s, expectedRegionServers=%d, detailedHostsReady=%s, staleDeadServersCleared=true",
                                    index, attempt, phase,
                                    postClearLiveServers == null ? "NA"
                                            : postClearLiveServers.toString(),
                                    postClearDeadServers == null ? "NA"
                                            : postClearDeadServers.toString(),
                                    postClearOnlineRegions == null ? "NA"
                                            : postClearOnlineRegions.toString(),
                                    expectedRegionServers,
                                    detailedHostsReady == null ? "NA"
                                            : detailedHostsReady.toString()));
                            return;
                        }
                    }
                }

                if (status.isSuccess() && !transientState && liveReady
                        && deadReady) {
                    logger.info(String.format(
                            "Node[%d] master-side cluster health ready after %d attempts (%s): liveServers=%s, deadServers=%s, onlineRegions=%s, expectedRegionServers=%d, detailedHostsReady=%s",
                            index, attempt, phase,
                            liveServers == null ? "NA" : liveServers.toString(),
                            deadServers == null ? "NA"
                                    : deadServers.toString(),
                            onlineRegions == null ? "NA"
                                    : onlineRegions.toString(),
                            expectedRegionServers,
                            detailedHostsReady == null ? "NA"
                                    : detailedHostsReady.toString()));
                    return;
                }

                logger.info(String.format(
                        "Node[%d] master-side health %d/%d not ready (%s): exit=%d,class=%s,transient=%s,liveServers=%s,deadServers=%s,onlineRegions=%s,expectedRegionServers=%d,detailedHostsReady=%s,sample=%s",
                        index, attempt, maxAttempts, phase, status.exitCode,
                        status.failureClass, transientState,
                        liveServers == null ? "NA" : liveServers.toString(),
                        deadServers == null ? "NA" : deadServers.toString(),
                        onlineRegions == null ? "NA"
                                : onlineRegions.toString(),
                        expectedRegionServers,
                        detailedHostsReady == null ? "NA"
                                : detailedHostsReady.toString(),
                        compactText(merged)));

                if (transientState) {
                    bestEffortRestartMasterIfDown(attempt, phase);
                } else {
                    bestEffortRecoverMissingRegionServers(attempt,
                            expectedRegionServers, liveServers, phase);
                }
            } catch (Exception e) {
                lastException = e;
                logger.info(String.format(
                        "Node[%d] master-side health %d/%d failed (%s): %s",
                        index, attempt, maxAttempts, phase, e.toString()));
            }

            Thread.sleep(sleepMillis);
        }

        String reason = lastException != null
                ? lastException.toString()
                : summarizeProbe(lastProbe);
        throw new IOException(
                "HBase rolling upgrade timed out waiting for cluster health from master side ("
                        + phase + "): " + reason);
    }

    private boolean isCheckpointReuseExecutionPhase(String phase) {
        if (phase != null && phase.contains("reusable checkpoint cache")) {
            return true;
        }
        return usesCheckpointReuseImage();
    }

    private boolean bestEffortClearDeadServers(int attempt, String phase,
            Integer deadServers) {
        if (index != 0 || deadServers == null || deadServers <= 0) {
            return false;
        }
        try {
            ValidationResult clear = execCommandStructured(
                    "clear_deadservers");
            logger.info(String.format(
                    "Node[%d] attempted clear_deadservers during %s at health attempt %d (previousDeadServers=%d): exit=%d,class=%s,sample=%s",
                    index, phase, attempt, deadServers, clear.exitCode,
                    clear.failureClass,
                    compactText(safe(clear.stdout) + "\n"
                            + safe(clear.stderr))));
            return clear.isSuccess();
        } catch (Exception e) {
            logger.info(String.format(
                    "Node[%d] failed to clear stale dead servers during %s at health attempt %d: %s",
                    index, phase, attempt, e.toString()));
            return false;
        }
    }

    private void bestEffortRestartMasterIfDown(int attempt, String phase) {
        if (index != 0 || attempt % 3 != 0) {
            return;
        }
        final String masterJpsCheck = "jps -l | grep -q 'org.apache.hadoop.hbase.master.HMaster'";
        try {
            ProbeCommandResult jps = runContainerProbe(masterJpsCheck);
            if (jps.exitCode == 0) {
                return;
            }
            logger.info(String.format(
                    "Node[%d] master JVM missing during %s; restarting supervisor hbase service",
                    index, phase));
            Process restart = runInContainer(
                    new String[] { "/bin/bash", "-lc",
                            "/usr/bin/supervisorctl restart upfuzz_hbase:hbase" },
                    env);
            String restartOutput = Utilities.readProcess(restart);
            logger.info(String.format(
                    "Node[%d] master restart command exit=%d output=%s",
                    index, restart.exitValue(), compactText(restartOutput)));

            for (int i = 0; i < 24; i++) {
                Thread.sleep(2500);
                ProbeCommandResult recheck = runContainerProbe(masterJpsCheck);
                if (recheck.exitCode == 0) {
                    logger.info(String.format(
                            "Node[%d] master JVM recovered after restart during %s",
                            index, phase));
                    return;
                }
            }
            logger.info(String.format(
                    "Node[%d] master JVM still missing after restart attempt during %s",
                    index, phase));
        } catch (Exception e) {
            logger.info(String.format(
                    "Node[%d] failed to restart master during %s: %s",
                    index, phase, e.toString()));
        }
    }

    private void bestEffortRecoverMissingRegionServers(int attempt,
            int expectedRegionServers, Integer liveServers, String phase) {
        if (index != 0 || expectedRegionServers <= 0) {
            return;
        }
        if (liveServers != null && liveServers >= expectedRegionServers) {
            return;
        }
        // Avoid aggressive restarts; try once every 3 health attempts.
        if (attempt % 3 != 0) {
            return;
        }

        for (int node = 1; node < hbaseDockerCluster.nodeNum; node++) {
            HBaseDocker rsDocker = (HBaseDocker) hbaseDockerCluster.getDocker(
                    node);
            if (rsDocker == null) {
                continue;
            }
            rsDocker.bestEffortRestartRegionServerIfDown(phase);
        }
    }

    private void bestEffortRestartRegionServerIfDown(String phase) {
        if (index == 0) {
            return;
        }
        final String rsJpsCheck = "jps -l | grep -q 'org.apache.hadoop.hbase.regionserver.HRegionServer'";
        try {
            ProbeCommandResult jps = runContainerProbe(rsJpsCheck);
            if (jps.exitCode == 0) {
                return;
            }

            logger.info(String.format(
                    "Node[%d] regionserver JVM missing during %s; restarting supervisor hbase service",
                    index, phase));
            Process restart = runInContainer(
                    new String[] { "/bin/bash", "-lc",
                            "/usr/bin/supervisorctl restart upfuzz_hbase:hbase" },
                    env);
            String restartOutput = Utilities.readProcess(restart);
            logger.info(String.format(
                    "Node[%d] regionserver restart command exit=%d output=%s",
                    index, restart.exitValue(), compactText(restartOutput)));

            for (int i = 0; i < 24; i++) {
                Thread.sleep(2500);
                ProbeCommandResult recheck = runContainerProbe(rsJpsCheck);
                if (recheck.exitCode == 0) {
                    logger.info(String.format(
                            "Node[%d] regionserver JVM recovered after restart during %s",
                            index, phase));
                    return;
                }
            }
            logger.info(String.format(
                    "Node[%d] regionserver JVM still missing after restart attempt during %s",
                    index, phase));
        } catch (Exception e) {
            logger.info(String.format(
                    "Node[%d] failed to restart regionserver during %s: %s",
                    index, phase, e.toString()));
        }
    }

    private Boolean areExpectedRegionServersListedInDetailedStatus() {
        try {
            ValidationResult detailed = execCommandStructured(
                    "status 'detailed'");
            String merged = safe(detailed.stdout) + "\n"
                    + safe(detailed.stderr);
            String mergedLower = safeLower(merged);
            if (!detailed.isSuccess() || isMasterTransient(mergedLower)) {
                return Boolean.FALSE;
            }

            String[] expectedHosts = hbaseDockerCluster
                    .getExpectedRegionServerHosts();
            for (String host : expectedHosts) {
                if (!mergedLower.contains(safeLower(host))) {
                    return Boolean.FALSE;
                }
            }
            return Boolean.TRUE;
        } catch (Exception e) {
            logger.info(String.format(
                    "Master-side detailed status host-list check failed: %s",
                    e.toString()));
            return null;
        }
    }

    private void bestEffortConfirmRegionServerListed(String regionServerHost) {
        try {
            ValidationResult status = execCommandStructured(
                    "status 'detailed'");
            String merged = safe(status.stdout) + "\n" + safe(status.stderr);
            if (status.isSuccess()
                    && safeLower(merged)
                            .contains(safeLower(regionServerHost))) {
                logger.info(String.format(
                        "Master-side detailed status confirms regionserver host [%s] is listed",
                        regionServerHost));
            } else {
                logger.info(String.format(
                        "Master-side detailed status did not explicitly list regionserver host [%s]; proceeding because simple-status health checks passed",
                        regionServerHost));
            }
        } catch (Exception e) {
            logger.info(String.format(
                    "Master-side detailed status check failed for regionserver host [%s]: %s",
                    regionServerHost, e.toString()));
        }
    }

    private ProbeCommandResult runContainerProbe(String command)
            throws IOException {
        Process process = runInContainer(
                new String[] { "/bin/bash", "-lc", command }, env);
        String output = Utilities.readProcess(process);
        return new ProbeCommandResult(process.exitValue(), output);
    }

    private boolean isZkTransient(String mergedLower) {
        return mergedLower.contains("zookeeper get/list could not be completed")
                || mergedLower.contains("recoverablezookeeper")
                || mergedLower.contains("connectionloss")
                || mergedLower.contains("keepererrorcode = connectionloss")
                || mergedLower.contains("annotatednoroutetohostexception")
                || mergedLower.contains("connection refused");
    }

    private boolean isMasterTransient(String mergedLower) {
        return isZkTransient(mergedLower)
                || mergedLower.contains("failed contacting masters")
                || mergedLower.contains("master is initializing")
                || mergedLower.contains("pleaseholdexception");
    }

    private Integer extractLiveServerCount(String text) {
        Integer value = extractCountByPattern(text,
                "(\\d+)\\s+region\\s+servers?\\b");
        if (value != null) {
            return value;
        }
        value = extractCountByPattern(text, "(\\d+)\\s+live\\s+servers?\\b");
        if (value != null) {
            return value;
        }
        value = extractCountByPattern(text, "(\\d+)\\s+regionservers?\\b");
        if (value != null) {
            return value;
        }
        return extractCountByPattern(text, "(\\d+)\\s+servers?\\b");
    }

    private Integer extractDeadServerCount(String text) {
        Integer value = extractCountByPattern(text,
                "(\\d+)\\s+dead\\s+servers?\\b");
        if (value != null) {
            return value;
        }
        return extractCountByPattern(text, "(\\d+)\\s+dead\\b");
    }

    private Integer extractOnlineRegionCount(String text) {
        Integer value = extractCountByPattern(text,
                "numberOfOnlineRegions\\s*=\\s*(\\d+)");
        if (value != null) {
            return value;
        }
        return extractCountByPattern(text,
                "(\\d+)\\s+online\\s+regions?\\b");
    }

    private Integer extractCountByPattern(String text, String regex) {
        Pattern pattern = Pattern.compile(regex, Pattern.CASE_INSENSITIVE);
        Matcher matcher = pattern.matcher(safe(text));
        if (!matcher.find()) {
            return null;
        }
        try {
            return Integer.parseInt(matcher.group(1));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private String summarizeProbe(ValidationResult probe) {
        if (probe == null) {
            return "no probe response";
        }
        String merged = ((probe.stdout == null) ? "" : probe.stdout)
                + " "
                + ((probe.stderr == null) ? "" : probe.stderr);
        String compact = merged.replaceAll("\\s+", " ").trim();
        if (compact.length() > 240) {
            compact = compact.substring(0, 240) + "...";
        }
        return String.format("exit=%d,class=%s,sample=%s", probe.exitCode,
                probe.failureClass, compact);
    }

    private String safeLower(String text) {
        return text == null ? "" : text.toLowerCase();
    }

    private String safe(String text) {
        return text == null ? "" : text;
    }

    private String compactText(String text) {
        String compact = safe(text).replaceAll("\\s+", " ").trim();
        if (compact.length() > 160) {
            return compact.substring(0, 160) + "...";
        }
        return compact;
    }

    private static class ProbeCommandResult {
        private final int exitCode;
        private final String output;

        private ProbeCommandResult(int exitCode, String output) {
            this.exitCode = exitCode;
            this.output = output;
        }
    }

    @Override
    public void upgrade() throws Exception {
        prepareUpgradeEnv();
        String restartCommand;
        String deleteLogCommand;

        String[] versionParts = originalVersion
                .substring(originalVersion.indexOf("-") + 1).split("\\.");

        restartCommand = "/usr/bin/supervisorctl restart upfuzz_hbase:";
        deleteLogCommand = "rm -rf /usr/local/zookeeper/version-2/log.*";
        if ((Integer.parseInt(versionParts[0]) == 2
                && Integer.parseInt(versionParts[1]) < 3)
                || (Integer.parseInt(versionParts[0]) == 1)) {
            logger.info(
                    "Hbase docker: going to add empty snapshot.0 file for zookeeper");
            Process copyToHbaseContainer = copyToContainer("snapshot.0",
                    "/usr/local/zookeeper/version-2/");
            int copyToContainerRet = copyToHbaseContainer.waitFor();

            logger.debug("Node " + index + " empty snapshot creation returned: "
                    + copyToContainerRet);
        }
        // Process deleteLog = runInContainer(
        // new String[] { "/bin/bash", "-c",
        // deleteLogCommand },
        // env);
        // int deleteLogRet = deleteLog.waitFor();
        Process restart = runInContainer(
                new String[] { "/bin/bash", "-c", restartCommand }, env);
        int ret = restart.waitFor();
        String message = Utilities.readProcess(restart);
        logger.debug("Node " + index + " upgrade version start: " + ret + "\n"
                + message);
    }

    @Override
    public void upgradeFromCrash() throws Exception {
        prepareUpgradeEnv();
        restart();
    }

    public void prepareUpgradeEnv() throws IOException {
        type = "upgraded";
        String HBaseHome = "/hbase/" + upgradedVersion;
        String HBaseConf = "/etc/" + upgradedVersion;
        if (Config.getConf().useBranchCoverage) {
            javaToolOpts = "JAVA_TOOL_OPTIONS=\"-javaagent:"
                    + "/org.jacoco.agent.rt.jar"
                    + "=append=false"
                    + ",includes=" + includes + ",excludes=" + excludes +
                    ",output=dfe,address=" + hostIP + ",port=" + agentPort +
                    ",sessionid=" + system + "-" + executorID + "_" + type +
                    "-" + index +
                    checkpointRestoreJavaOptionsSuffix() +
                    "\"";
        } else {
            javaToolOpts = "JAVA_TOOL_OPTIONS=\""
                    + checkpointRestoreJavaOptions() + "\"";
        }
        HBaseDaemonPort ^= 1;

        int upgradedMajorVersion = extractMajorVersion(upgradedVersion);
        String pythonVersion = pythonVersionForMajor(upgradedMajorVersion);
        env = new String[] {
                "HBASE_HOME=\"" + HBaseHome + "\"",
                "HBASE_CONF=\"" + HBaseConf + "\"", javaToolOpts,
                "HBASE_SHELL_DAEMON_PORT=\"" + HBaseDaemonPort + "\"",
                "CUR_STATUS=UP",
                "PYTHON=" + pythonVersion,
                "ENABLE_FORMAT_COVERAGE=false",
                "ENABLE_NET_COVERAGE=" + Config.getConf().useTrace,
                "ENABLE_NETWORK_TRACE=" + Config.getConf().useTrace,
                "NET_TRACE_NODE_ID=" + executorID + "-N" + index,
                "NET_TRACE_NODE_ROLE=" + getNodeRole() };
        setEnvironment();
    }

    @Override
    public void prepareCheckpointReuseVersion(
            DockerMeta.DockerVersion dockerVersion) throws Exception {
        if (dockerVersion == DockerMeta.DockerVersion.upgraded) {
            prepareUpgradeEnv();
        }
    }

    private int extractMajorVersion(String version) {
        String normalized = version == null ? "" : version.trim();
        if (normalized.startsWith("hbase-")) {
            normalized = normalized.substring("hbase-".length());
        }

        // Snapshot tags are suffix metadata (e.g. 4.0.0-alpha-1-SNAPSHOT).
        if (normalized.contains("SNAPSHOT")) {
            normalized = normalized.replace("-SNAPSHOT", "");
        }

        Matcher leading = LEADING_MAJOR_PATTERN.matcher(normalized);
        if (leading.find()) {
            return Integer.parseInt(leading.group(1));
        }

        // Fallback: first numeric token anywhere in the string.
        Matcher any = ANY_MAJOR_PATTERN.matcher(normalized);
        if (any.find()) {
            return Integer.parseInt(any.group(1));
        }

        logger.warn(
                "Cannot parse HBase major version from '{}', defaulting to 2",
                version);
        return 2;
    }

    private String pythonVersionForMajor(int majorVersion) {
        return majorVersion >= 3 ? "python3" : "python2";
    }

    @Override
    public void downgrade() throws Exception {
    }

    public void prepareUpgradeTo2_2(NodeType nodeType, String version)
            throws Exception {
        if (nodeType == NodeType.MASTER) {
            if (index == 0) {
                // N0: hbase master, zookeeper
                String[] stopHMaster = buildHbaseDaemonCommand(version, "stop",
                        "master");
                int ret = runProcessInContainer(stopHMaster, env);
                logger.debug("shutdown " + "hmaster" + " ret = " + ret);

                String newConfigurationEntry = "/<\\/configuration>/i\\\n" +
                        "    <property>\\\n" +
                        "        <name>hbase.procedure.upgrade-to-2-2</name>\\\n"
                        +
                        "        <value>true</value>\\\n" +
                        "    </property>";
                String hbaseConfigPath = "/etc/" + version + "/hbase-site.xml";
                String modifyHbaseSiteCommand = "sed -i '" +
                        newConfigurationEntry + "' ";

                // Process envForUpgradeTo2_2 =
                // runInContainer(modifyHbaseSiteCommand);
                // int ret = envForUpgradeTo2_2.waitFor();

                Process envForUpgradeTo2_2 = updateFileInContainer(
                        hbaseConfigPath,
                        modifyHbaseSiteCommand);
                ret = envForUpgradeTo2_2.waitFor();
                logger.debug(
                        "prepare upgrade environment " + " to 2.2 for hmaster"
                                + " ret = " + ret);

                String[] startHMaster = buildHbaseDaemonCommand(version,
                        "start",
                        "master");

                Process upgradeTo2_2_start = runInContainer(startHMaster);
                ret = upgradeTo2_2_start.waitFor();
                logger.debug("upgrade " + "hmaster to 2.2" + " ret = " + ret);
            }
        }
    }

    public void shutdownWithType(NodeType nodeType) {
        // The reason this code is like this is that
        // the zk and master/rs are in the same node
        String curVersion = type.equals("upgraded") ? upgradedVersion
                : originalVersion;
        logger.info("[HBaseDocker] Current version: " + curVersion);
        if (nodeType == NodeType.REGIONSERVER) {
            if (index == 1 || index == 2) {
                String[] stopNode = buildHbaseDaemonCommand(curVersion, "stop",
                        "regionserver");
                int ret = runProcessInContainer(stopNode, env);
                logger.debug(String.format(
                        "shutdown regionserver (index = %d) ret = %d", index,
                        ret));
            }
        } else if (nodeType == NodeType.MASTER) {
            if (index == 0) {
                // N0: hbase master, zookeeper
                String[] stopHMaster = buildHbaseDaemonCommand(curVersion,
                        "stop",
                        "master");
                int ret = runProcessInContainer(stopHMaster, env);
                logger.debug("shutdown " + "hmaster" + " ret = " + ret);
            }
        } else if (nodeType == NodeType.ZOOKEEPER) {
            String[] stopZK = buildHbaseDaemonCommand(curVersion, "stop",
                    "zookeeper");
            int ret = runProcessInContainer(stopZK, env);
            logger.debug("shutdown " + "zookeeper" + index + " ret = " + ret);
        } else {
            throw new RuntimeException("Unknown node type");
        }
    }

    @Override
    public void shutdown() {
        // ${HBASE_HOME}/bin/hbase-daemon.sh --config ${HBASE_CONF}
        // foreground_start regionserver
        String curVersion = type.equals("upgraded") ? upgradedVersion
                : originalVersion;

        if (index == 0) {
            // N0: hbase master, zookeeper
            String[] stopHMaster = buildHbaseDaemonCommand(curVersion, "stop",
                    "master");
            int ret = runProcessInContainer(stopHMaster, env);
            logger.debug("shutdown " + "hmaster" + " ret = " + ret);
        } else {
            // N1, N2: regionserver
            String[] stopNode = buildHbaseDaemonCommand(curVersion, "stop",
                    "regionserver");
            int ret = runProcessInContainer(stopNode, env);
            logger.debug(String.format(
                    "shutdown regionserver (index = %d) ret = %d", index, ret));
        }
        String[] stopZK = buildHbaseDaemonCommand(curVersion, "stop",
                "zookeeper");
        int ret = runProcessInContainer(stopZK, env);
        logger.debug("shutdown " + "zookeeper" + index + " ret = " + ret);
    }

    private String[] buildHbaseDaemonCommand(String version, String action,
            String component) {
        String daemonSh = "/" + system + "/" + version + "/bin/hbase-daemon.sh";
        String daemonPy = "/" + system + "/" + version + "/bin/hbase-daemon.py";
        String hbaseConf = "/etc/" + version;
        String command = "if [ -x '" + daemonSh + "' ]; then "
                + "'" + daemonSh + "' --config '" + hbaseConf + "' " + action
                + " " + component + "; "
                + "elif [ -f '" + daemonPy + "' ]; then "
                + "python3 '" + daemonPy + "' --config '" + hbaseConf + "' "
                + action + " " + component + "; "
                + "else exit 127; fi";
        return new String[] { "/bin/bash", "-lc", command };
    }

    @Override
    public boolean clear() {
        int ret = runProcessInContainer(new String[] {
                "rm", "-rf", "/var/lib/hbase/*"
        });
        logger.debug("hbase clear data ret = " + ret);
        return true;
    }

    public Path getWorkPath() {
        return workdir.toPath();
    }

    // TODO
    public void chmodDir() throws IOException {
        runInContainer(
                new String[] { "chmod", "-R", "777", "/var/log/hbase" });
        runInContainer(
                new String[] { "chmod", "-R", "777", "/var/lib/hbase" });
        runInContainer(
                new String[] { "chmod", "-R", "777", "/var/log/supervisor" });
        runInContainer(
                new String[] { "chmod", "-R", "777", "/usr/bin/set_env" });
    }

    // add the configuration test files

    static String template = "" // TODO
            + "    ${serviceName}:\n"
            + "        container_name: hbase-${configOriginalVersion}_${configUpgradedVersion}_${executorID}_N${index}\n"
            + "        image: ${imageName}\n"
            + "        command: bash -c 'sleep 0 && source /usr/bin/set_env && /usr/bin/supervisord'\n"
            + "        networks:\n"
            + "            ${networkName}:\n"
            + "                ipv4_address: ${networkIP}\n"
            + "        volumes:\n"
            // + " - ./persistent/node_${index}/data:/var/lib/cassandra\n"
            + "            - ./persistent/node_${index}/log:/var/log/hbase\n"
            + "            - ./persistent/node_${index}/env.sh:/usr/bin/set_env\n"
            // + " -
            // ./persistent/node_${index}/zookeeper:/usr/local/zookeeper\n"
            + "            - ./persistent/node_${index}/consolelog:/var/log/supervisor\n"
            + "            - ${projectRoot}/src/main/resources/hbase/compile-src/hbase-init.sh:/usr/local/bin/hbase-init.sh\n"
            + "            - ./persistent/config:/test_config\n"
            + "            - ${projectRoot}/prebuild/${system}/${originalVersion}:/${system}/${originalVersion}\n"
            + "            - ${projectRoot}/prebuild/${system}/${upgradedVersion}:/${system}/${upgradedVersion}\n"
            + "            - ${projectRoot}/prebuild/hadoop/hadoop-2.10.2:/hadoop/hadoop-2.10.2\n"
            // TODO: depend system & version in configuration
            + "        environment:\n"
            + "            - HADOOP_IP=${HadoopIP}\n"
            + "            - IS_HMASTER=${HBaseMaster}\n"
            + "            - HBASE_CLUSTER_NAME=dev_cluster\n"
            + "            - HBASE_SEEDS=${seedIP},\n"
            + "            - HBASE_LOGGING_LEVEL=DEBUG\n"
            + "            - HBASE_SHELL_HOST=${networkIP}\n"
            + "            - HBASE_LOG_DIR=/var/log/hbase\n"
            + "            - JAVA_HOME=/usr/lib/jvm/java-8-openjdk-amd64/\n"
            + "        expose:\n"
            + "            - ${agentPort}\n"
            + "            - ${daemonPort}\n"
            + "            - 22\n"
            + "            - 2181\n"
            + "            - 2888\n"
            + "            - 3888\n"
            + "            - 7000\n"
            + "            - 7001\n"
            + "            - 7199\n"
            + "            - 8020\n"
            + "            - 9042\n"
            + "            - 9160\n"
            + "            - 18251\n"
            + "            - 16000\n"
            + "            - 16010\n"
            + "        ulimits:\n"
            + "            memlock: -1\n"
            + "            nproc: 32768\n"
            + "            nofile: 100000\n"
            + "        depends_on:\n"
            + "             - ${depDockerID}\n";

    @Override
    public LogInfo grepLogInfo(Set<String> blackListErrorLog) {
        LogInfo logInfo = new LogInfo();
        Path filePath = Paths.get("/var/log/hbase/*.log");
        constructLogInfo(logInfo, filePath, blackListErrorLog);
        return logInfo;
    }
}
