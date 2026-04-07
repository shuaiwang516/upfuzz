package org.zlab.upfuzz.docker;

import java.io.*;
import java.nio.file.Path;
import java.text.DateFormat;
import java.text.MessageFormat;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.stream.Collectors;

import org.apache.commons.lang3.RandomUtils;
import org.zlab.net.tracker.Trace;
import org.zlab.ocov.tracker.ObjectGraphCoverage;
import org.zlab.upfuzz.fuzzingengine.Config;
import org.zlab.upfuzz.fuzzingengine.LogInfo;
import org.zlab.upfuzz.fuzzingengine.executor.Executor;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.zlab.upfuzz.hdfs.HdfsDockerCluster;
import org.zlab.upfuzz.utils.Utilities;

public abstract class DockerCluster implements IDockerCluster {
    static Logger logger = LogManager.getLogger(DockerCluster.class);

    protected Docker[] dockers;
    protected Docker[] extranodes;
    public DockerMeta.DockerState[] dockerStates;

    public Network network;

    public String version;
    public String originalVersion;
    public String upgradedVersion;

    public int nodeNum;
    public String networkID;
    public Executor executor;
    public String executorID;
    public String system;
    public String subnet;
    public int agentPort;
    public int subnetID;
    public String networkName;
    public String composeYaml;
    public String hostIP;
    public File workdir;
    public Path configpath;
    public int direction;

    public boolean collectFormatCoverage;
    public Set<String> blackListErrorLog = new HashSet<>();

    // This function do the shifting
    // .1 runs the client
    // .2 runs the first node
    public static String getKthIP(String ip, int index) {
        String[] segments = ip.split("\\.");
        segments[3] = Integer.toString(index + 2);
        return String.join(".", segments);
    }

    public DockerCluster(Executor executor, String version,
            int nodeNum, boolean collectFormatCoverage, int direction) {
        // replace subnet
        // rename services

        // 192.168.24.[(0001~1111)|0000] / 28

        this.subnetID = RandomUtils.nextInt(1, 256);
        this.subnet = "192.168." + subnetID + ".0/24";
        this.hostIP = "192.168." + subnetID + ".1";
        this.agentPort = executor.agentPort;
        this.executor = executor;
        this.executorID = executor.executorID;
        this.version = version;
        this.originalVersion = Config.getConf().originalVersion;
        this.upgradedVersion = Config.getConf().upgradedVersion;
        this.system = executor.systemID;
        this.nodeNum = nodeNum;
        this.direction = direction;
        DateFormat formatter = new SimpleDateFormat("yyyy-MM-dd-HH-mm-ss");
        String executorTimestamp = formatter.format(System.currentTimeMillis());

        if (Config.getConf().testSingleVersion) {
            this.networkName = MessageFormat.format(
                    "network_{0}_{1}_to_{2}_{3}", executor.systemID,
                    Config.getConf().originalVersion,
                    Config.getConf().upgradedVersion,
                    UUID.randomUUID());
            this.workdir = new File(
                    "fuzzing_storage/" + executor.systemID + "/" +
                            originalVersion + "/" + executorTimestamp + "-"
                            + executor.executorID);
        } else {
            if (!Config.getConf().useVersionDelta) {
                this.networkName = MessageFormat.format(
                        "network_{0}_{1}_to_{2}_{3}", executor.systemID,
                        Config.getConf().originalVersion,
                        Config.getConf().upgradedVersion,
                        UUID.randomUUID());
                this.workdir = new File(
                        "fuzzing_storage/" + executor.systemID + "/" +
                                originalVersion + "/" + upgradedVersion + "/" +
                                executorTimestamp + "-" + executor.executorID);
            } else {
                if (direction == 0) {
                    this.networkName = MessageFormat.format(
                            "network_{0}_{1}_to_{2}_{3}", executor.systemID,
                            Config.getConf().originalVersion,
                            Config.getConf().upgradedVersion,
                            UUID.randomUUID());
                    this.workdir = new File(
                            "fuzzing_storage/" + executor.systemID + "/" +
                                    originalVersion + "/" + upgradedVersion
                                    + "/" +
                                    executorTimestamp + "-"
                                    + executor.executorID);
                } else {
                    this.networkName = MessageFormat.format(
                            "network_{0}_{2}_to_{1}_{3}", executor.systemID,
                            Config.getConf().originalVersion,
                            Config.getConf().upgradedVersion,
                            UUID.randomUUID());
                    this.workdir = new File(
                            "fuzzing_storage/" + executor.systemID + "/" +
                                    upgradedVersion + "/" + originalVersion
                                    + "/" +
                                    executorTimestamp + "-"
                                    + executor.executorID);
                }
            }
        }

        this.network = new Network();
        this.collectFormatCoverage = collectFormatCoverage;

        // Init docker states
        dockerStates = new DockerMeta.DockerState[nodeNum];
        for (int i = 0; i < nodeNum; i++) {
            this.dockerStates[i] = new DockerMeta.DockerState(
                    DockerMeta.DockerVersion.original, true);
        }
    }

    public int getFirstLiveNodeIdx() {
        int idx = -1;
        for (int i = 0; i < nodeNum; i++) {
            if (dockerStates[i].alive) {
                idx = i;
                break;
            }
        }
        return idx;
    }

    public abstract void formatComposeYaml();

    public abstract void refreshNetwork();

    @Override
    public int start() {
        File composeFile = new File(workdir, "docker-compose.yaml");
        if (!workdir.exists()) {
            workdir.mkdirs();
        }

        Process buildProcess = null;
        int retry = 3, ret = -1;

        for (int i = 0; i < retry; ++i) {
            try {
                BufferedWriter writer = new BufferedWriter(
                        new FileWriter(composeFile));

                formatComposeYaml();
                composeFile.createNewFile();
                // logger.info(composeYaml);
                writer.write(composeYaml);
                writer.close();

                buildProcess = Utilities.exec(
                        new String[] { "docker", "compose", "up", "-d" },
                        workdir);
                ret = buildProcess.waitFor();
                if (ret == 0) {
                    if (Config.getConf().debug) {
                        logger.debug("docker compose up " + workdir);
                    }
                    break;
                } else {
                    Utilities.exec(
                            new String[] { "docker", "network", "prune" },
                            workdir);
                    refreshNetwork();
                    String errorMessage = Utilities.readProcess(buildProcess);
                    logger.warn("docker compose up\n" + errorMessage);
                }
            } catch (IOException | InterruptedException e) {
                e.printStackTrace();
                throw new RuntimeException(e.toString());
            }
        }
        if (ret != 0) {
            String errorMessage = Utilities.readProcess(buildProcess);
            logger.error("docker compose up\n" + errorMessage);
            return ret;
        }

        try {
            // Get network full name here, so that later we can disconnect and
            // reconnect
            Process getNameProcess = Utilities.exec(
                    new String[] { "/bin/sh", "-c",
                            "docker network ls | grep " + networkName },
                    workdir);
            getNameProcess.waitFor();

            BufferedReader stdInput = new BufferedReader(
                    new InputStreamReader(getNameProcess.getInputStream()));

            BufferedReader stdError = new BufferedReader(
                    new InputStreamReader(getNameProcess.getErrorStream()));

            List<String> results = new ArrayList<>();
            String s;
            while ((s = stdInput.readLine()) != null) {
                results.add(s);
            }
            if (results.size() != 1) {
                logger.error(
                        "There should be one matching network, but there is "
                                + results.size() + " matching");
                this.networkID = null;
            } else {
                this.networkID = results.get(0).split(" ")[0];
            }

            // System.out.println("network ID = " + this.networkID);
        } catch (IOException | InterruptedException e) {
            e.printStackTrace();
        }

        for (int i = 0; i < dockers.length; ++i) {
            try {
                dockers[i].start();
            } catch (Exception e) {
                logger.error(String.format(
                        "docker[%d] cannot start up with exception %s", i, e));
                ret = -1;
                break;
            }
        }
        // FIXME: special for hdfs, we move "create image" behind
        if (Config.getConf().prepareImageFirst
                && Config.getConf().enable_fsimage) {
            try {
                if (this instanceof HdfsDockerCluster) {
                    this.prepareUpgrade();
                }
            } catch (Exception e) {
                logger.error("problem with hdfs preparation!");
            }
        }
        return ret;
    }

    public ObjectGraphCoverage getFormatCoverage(Path formatInfoFolder) {
        assert dockers.length > 0;
        ObjectGraphCoverage coverageMap = new ObjectGraphCoverage(
                formatInfoFolder
                        .resolve(Config.getConf().baseClassInfoFileName),
                formatInfoFolder.resolve(Config.getConf().topObjectsFileName));
        for (int i = 0; i < dockers.length; i++) {
            // merge
            try {
                ObjectGraphCoverage formatCoverageMap = dockers[i]
                        .getFormatCoverage();
                coverageMap.merge(formatCoverageMap);
            } catch (Exception e) {
                logger.error("Exception occur when collecting" +
                        " format coverage of docker  " + i
                        + ", executorID = " + executorID
                        + ", exception = "
                        + e);
                for (StackTraceElement ste : e.getStackTrace()) {
                    logger.error(ste.toString());
                }
                // throw new RuntimeException(e);
            }
        }
        return coverageMap;
    }

    public void clearTraceAllNodes() {
        for (int i = 0; i < dockers.length; i++) {
            try {
                dockers[i].clearTrace();
            } catch (Exception e) {
                logger.warn("clearTrace failed on node {}: {}", i,
                        e.toString());
            }
        }
    }

    public Trace[] collectTraceAllNodes() {
        Trace[] traces = new Trace[dockers.length];
        for (int i = 0; i < dockers.length; i++) {
            traces[i] = collectTrace(i);
        }
        return traces;
    }

    public void clearFormatCoverage() {
        assert dockers.length > 0;
        for (int i = 0; i < dockers.length; i++) {
            try {
                dockers[i].clearFormatCoverage();
            } catch (Exception e) {
                logger.error("Exception occur when clear" +
                        " format coverage of docker  " + i
                        + ", executorID = " + executorID
                        + ", exception = "
                        + e);
            }
        }
    }

    public Trace[] collectTrace() {
        assert dockers.length > 0;
        Trace[] traces = new Trace[dockers.length];
        logger.info("[HKLOG] collecting network traces");
        for (int i = 0; i < dockers.length; i++) {
            try {
                traces[i] = dockers[i].collectTrace();
            } catch (Exception e) {
                logger.error("Exception occur when collecting" +
                        " trace of docker  " + i
                        + ", executorID = " + executorID
                        + ", exception = "
                        + e);
                for (StackTraceElement ste : e.getStackTrace()) {
                    logger.error(ste.toString());
                }
                // throw new RuntimeException(e);
            }
        }
        return traces;
    }

    public Trace collectTrace(int nodeIndex) {
        assert dockers.length > 0;
        Trace trace = null;
        logger.info("[HKLOG] collecting network traces for node " + nodeIndex);
        try {
            trace = dockers[nodeIndex].collectTrace();
        } catch (Exception e) {
            logger.error("Exception occur when collecting" +
                    " trace of docker  " + nodeIndex
                    + ", executorID = " + executorID
                    + ", exception = "
                    + e);
            for (StackTraceElement ste : e.getStackTrace()) {
                logger.error(ste.toString());
            }
            // throw new RuntimeException(e);
        }
        return trace;
    }

    @Override
    public boolean fullStopUpgrade() throws Exception {
        // FIXME: prepareUpgrade() might be put after shutdown?
        logger.info("Cluster full-stop upgrading...");
        prepareUpgrade();
        for (int i = 0; i < dockers.length; i++) {
            dockers[i].shutdown();
        }
        for (int i = 0; i < dockers.length; i++) {
            if (dockerStates[i].alive)
                dockers[i].upgrade();
            else
                dockers[i].upgradeFromCrash();
        }
        logger.info("Cluster upgraded");
        return true;
    }

    @Override
    public boolean rollingUpgrade() throws Exception {
        logger.info("Cluster upgrading...");
        prepareUpgrade();
        for (int i = 0; i < dockers.length; ++i) {
            dockers[i].shutdown();
            dockers[i].upgrade();
        }
        logger.info("Cluster upgraded");
        return true;
    }

    @Override
    public boolean downgrade() throws Exception {
        logger.info("Cluster downgrading...");
        // downgrade in reverse order
        for (int i = dockers.length - 1; i >= 0; i--) {
            dockers[i].shutdown();
            dockers[i].downgrade();
        }
        logger.info("Cluster downgraded");
        return true;
    }

    @Override
    public void flush() throws Exception {
        for (int i = 0; i < dockers.length; i++) {
            dockers[i].flush();
        }
    }

    @Override
    public boolean freshStartNewVersion() throws Exception {
        logger.info("Fresh start new version ...");
        for (int i = 0; i < dockers.length; i++) {
            dockers[i].shutdown();
        }
        for (int i = 0; i < dockers.length; i++) {
            dockers[i].clear();
        }
        // new version will start up from a clear state
        for (int i = 0; i < dockers.length; i++) {
            dockers[i].upgrade();
        }
        logger.info("Cluster upgraded");
        return true;
    }

    /**
     * collecting log Info from each node
     */
    public Map<Integer, LogInfo> grepLogInfo() {
        // nodeId -> {class.state -> value}
        Map<Integer, LogInfo> states = new HashMap<>();
        for (int i = 0; i < nodeNum; i++) {
            states.put(i, dockers[i].grepLogInfo(blackListErrorLog));
        }
        return states;
    }

    /**
     * Some preparation before upgrading nodes
     * - prepare FSImage in HDFS
     * - Drain in Cassandra to remove commit logs
     */
    public abstract void prepareUpgrade() throws Exception;

    /**
     * Upgrade a node
     */
    @Override
    public void upgrade(int nodeIndex) throws Exception {
        if (dockerStates[nodeIndex].alive) {
            logger.info(String.format("Upgrade Node[%d]", nodeIndex));
            dockers[nodeIndex].shutdown();

            // Only for debug usage
            if (Config.getConf().eval_CASSANDRA15727) {
                logger.info(
                        "[eval CASSANDRA15727] is enabled: " +
                                "special fault inject during upgrade");
                // uni-partition current node for N seconds during upgrade
                new Thread(() -> {
                    this.uniPartition(nodeIndex);
                    logger.info("[eval CASSANDRA15727] uni-partition node "
                            + nodeIndex);
                    // sleep ?s
                    try {
                        Thread.sleep(5000);
                    } catch (InterruptedException e) {
                        e.printStackTrace();
                    }
                    this.uniPartitionRecover(nodeIndex);
                    logger.info(
                            "[eval CASSANDRA15727] uni-partition recover node "
                                    + nodeIndex);
                }).start();
            }

            dockers[nodeIndex].upgrade();
            dockerStates[nodeIndex].dockerVersion = DockerMeta.DockerVersion.upgraded;
            logger.info(String.format("Node[%d] is upgraded", nodeIndex));
        } else {
            // Upgrade from a crashed container
            logger.info(
                    String.format("Upgrade Node[%d] from crash", nodeIndex));
            dockers[nodeIndex].upgradeFromCrash();
            dockerStates[nodeIndex].alive = true;
        }
    }

    @Override
    public void downgrade(int nodeIndex) throws Exception {
        // upgrade a specific node
        logger.info(String.format("Downgrade Node[%d]", nodeIndex));
        dockers[nodeIndex].shutdown();
        dockers[nodeIndex].downgrade();
        dockerStates[nodeIndex].dockerVersion = DockerMeta.DockerVersion.original;
        logger.info(String.format("Node[%d] is downgraded", nodeIndex));
    }

    // Uni Partition a node
    // It cannot receive any packets from other nodes, but can send packets
    public boolean uniPartition(int nodeIndex) {
        if (!checkIndex(nodeIndex))
            return false;
        if (!dockerStates[nodeIndex].alive)
            return false;
        boolean ret = true;
        for (int i = 0; i < nodeNum; i++) {
            if (i != nodeIndex) {
                if (network.uniPartition(dockers[nodeIndex], dockers[i]))
                    ret = false;
            }
        }
        return ret;
    }

    public boolean uniPartitionRecover(int nodeIndex) {
        if (!checkIndex(nodeIndex))
            return false;
        if (!dockerStates[nodeIndex].alive)
            return false;
        boolean ret = true;
        for (int i = 0; i < nodeNum; i++) {
            if (i != nodeIndex) {
                if (network.uniPartitionRecover(dockers[nodeIndex], dockers[i]))
                    ret = false;
            }
        }
        return ret;
    }

    public boolean linkFailure(int nodeIndex1, int nodeIndex2) {
        if (!checkIndex(nodeIndex1) || !checkIndex(nodeIndex2))
            return false;
        if (nodeIndex1 == nodeIndex2)
            return false;
        // If one of the node is down, what should we do?
        // - We shouldn't inject faults here right? The
        // - container is completely down
        if (!dockerStates[nodeIndex1].alive
                || !dockerStates[nodeIndex2].alive) {
            return false;
        }
        logger.info("[LinkFailure] node" + nodeIndex1 + ", node" + nodeIndex2);
        return network.biPartition(dockers[nodeIndex1], dockers[nodeIndex2]);
    }

    public boolean linkFailureRecover(int nodeIndex1, int nodeIndex2) {
        if (!checkIndex(nodeIndex1) || !checkIndex(nodeIndex2))
            return false;
        if (nodeIndex1 == nodeIndex2)
            return false;
        return network.biPartitionRecover(dockers[nodeIndex1],
                dockers[nodeIndex2]);
    }

    public boolean isolateNode(int nodeIndex) {
        if (!checkIndex(nodeIndex))
            return false;
        if (!dockerStates[nodeIndex].alive)
            return false;
        Set<Docker> peers = new HashSet<>();
        for (int i = 0; i < nodeNum; i++) {
            if (i != nodeIndex)
                peers.add(dockers[i]);
        }
        return network.isolateNode(dockers[nodeIndex], peers);
    }

    public boolean isolateNodeRecover(int nodeIndex) {
        if (!checkIndex(nodeIndex))
            return false;
        Set<Docker> peers = new HashSet<>();
        for (int i = 0; i < nodeNum; i++) {
            if (i != nodeIndex)
                peers.add(dockers[i]);
        }
        return network.isolateNodeRecover(dockers[nodeIndex], peers);
    }

    public boolean partition(Set<Integer> nodeSet1, Set<Integer> nodeSet2) {
        if (Collections.disjoint(nodeSet1, nodeSet2)) {
            // There shouldn't be common nodes
            return false;
        }
        for (int nodeIndex : nodeSet1) {
            if (!checkIndex(nodeIndex) || !dockerStates[nodeIndex].alive)
                return false;
        }
        for (int nodeIndex : nodeSet2) {
            if (!checkIndex(nodeIndex) || !dockerStates[nodeIndex].alive)
                return false;
        }

        Set<Docker> dockerSet1 = nodeSet1.stream()
                .map(nodeIndex -> dockers[nodeIndex])
                .collect(Collectors.toSet());
        Set<Docker> dockerSet2 = nodeSet2.stream()
                .map(nodeIndex -> dockers[nodeIndex])
                .collect(Collectors.toSet());

        return network.partitionTwoSets(dockerSet1, dockerSet2);
    }

    public boolean partitionRecover(Set<Integer> nodeSet1,
            Set<Integer> nodeSet2) {
        if (Collections.disjoint(nodeSet1, nodeSet2)) {
            // There shouldn't be common nodes
            return false;
        }
        for (int nodeIndex : nodeSet1) {
            if (!checkIndex(nodeIndex))
                return false;
        }
        for (int nodeIndex : nodeSet2) {
            if (!checkIndex((nodeIndex)))
                return false;
        }

        Set<Docker> dockerSet1 = nodeSet1.stream()
                .map(nodeIndex -> dockers[nodeIndex])
                .collect(Collectors.toSet());
        Set<Docker> dockerSet2 = nodeSet2.stream()
                .map(nodeIndex -> dockers[nodeIndex])
                .collect(Collectors.toSet());

        return network.partitionTwoSetsRecover(dockerSet1, dockerSet2);
    }

    public boolean forceKillContainer(String containerName) {
        // docker kill container
        try {
            String[] killContainerCMD = new String[] {
                    "docker", "kill", containerName
            };
            logger.debug("workdir = " + workdir);
            Process killContainerProcess = Utilities.exec(killContainerCMD,
                    workdir);

            // Capture stdout of the process
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(
                            killContainerProcess.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    logger.debug("Container Force Kill Output: " + line);
                }
            }

            // Capture stderr of the process
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(
                            killContainerProcess.getErrorStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    logger.debug("Container Force Kill ERROR: " + line);
                }
            }
            killContainerProcess.waitFor();
        } catch (IOException | InterruptedException e) {
            logger.error("Cannot kill container "
                    + containerName, e);
            return false;
        }
        return true;
    }

    public boolean stopContainer(int nodeIndex) {
        // docker-compose stop container
        if (!checkIndex(nodeIndex))
            return false;

        try {
            String[] killContainerCMD = new String[] {
                    "docker", "compose", "stop", dockers[nodeIndex].serviceName
            };
            logger.debug("workdir = " + workdir);
            Process killContainerProcess = Utilities.exec(killContainerCMD,
                    workdir);
            killContainerProcess.waitFor();
        } catch (IOException | InterruptedException e) {
            logger.error("Cannot delete container "
                    + dockers[nodeIndex].containerName, e);
            return false;
        }

        dockerStates[nodeIndex].alive = false;
        return true;
    }

    public boolean restartContainer(int nodeIndex) {
        if (!checkIndex(nodeIndex))
            return false;

        try {
            dockers[nodeIndex].restart();
            dockerStates[nodeIndex].alive = true;
        } catch (Exception e) {
            return false;
        }
        return true;
    }

    public boolean killContainerRecover(int nodeIndex) {
        if (!checkIndex(nodeIndex))
            return false;

        try {
            dockers[nodeIndex].restart();
            dockerStates[nodeIndex].alive = true;
        } catch (Exception e) {
            return false;
        }
        return true;
    }

    public boolean checkIndex(int nodeIndex) {
        return nodeIndex < nodeNum && nodeIndex >= 0;
    }

    public abstract void finalizeUpgrade();

}
