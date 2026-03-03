package org.zlab.upfuzz.hbase;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileWriter;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.Scanner;

import org.apache.commons.lang3.RandomUtils;
import org.apache.commons.text.StringSubstitutor;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.zlab.upfuzz.docker.Docker;
import org.zlab.upfuzz.docker.DockerCluster;
import org.zlab.upfuzz.docker.DockerMeta;
import org.zlab.upfuzz.docker.IDocker;
import org.zlab.upfuzz.fuzzingengine.Config;
import org.zlab.upfuzz.utils.Utilities;

public class HBaseDockerCluster extends DockerCluster {
    static Logger logger = LogManager.getLogger(HBaseDockerCluster.class);

    String seedIP;

    static final String includes = "org.apache.hadoop.hbase.*";
    static final String excludes = "org.jruby.*";

    public static String[] includeJacocoHandlers = {
            "org.apache.hadoop.hbase.master.HMaster",
            "org.apache.hadoop.hbase.regionserver.HRegionServer"
    };

    HBaseDockerCluster(HBaseExecutor executor, String version,
            int nodeNum, boolean collectFormatCoverage,
            Path configPath,
            int direction) {
        super(executor, version, nodeNum, collectFormatCoverage, direction);

        this.dockers = new HBaseDocker[nodeNum];
        this.extranodes = new HBaseHDFSDocker[1];
        this.configpath = configPath;
        this.seedIP = DockerCluster.getKthIP(hostIP, 0);

        initBlackListErrorLog();
    }

    public void initBlackListErrorLog() {
        // zk related
        blackListErrorLog
                .add("zookeeper.ClientCnxn: Error while calling watcher \n" +
                        "java.util.concurrent.RejectedExecutionException: Task java.util.concurrent.FutureTask");
        blackListErrorLog.add(
                "quorum.LearnerHandler: Unexpected exception causing shutdown while sock still open");
        blackListErrorLog.add(
                "jmx.ManagedUtil: Problems while registering log4j jmx beans!");
        blackListErrorLog.add(
                "zookeeper.RecoverableZooKeeper: ZooKeeper delete failed");
        // Normal shutdown
        blackListErrorLog.add(
                "regionserver.HRegionServer: ***** ABORTING region server");
        blackListErrorLog.add(
                "regionserver.HRegionServerCommandLine: Region server exiting");
        blackListErrorLog.add(
                "regionserver.HRegionServer: RegionServer abort: loaded coprocessors");
        blackListErrorLog.add(
                "snapshot.TakeSnapshotHandler: Couldn't delete snapshot working");
        blackListErrorLog.add(
                "regionserver.HRegion: Memstore data size is");
        blackListErrorLog.add(
                "quorum.LearnerHandler: Unexpected exception in LearnerHandler");
        // zookeeper.ZKWatcher
        blackListErrorLog.add(
                "zookeeper.ZKWatcher");
        blackListErrorLog.add(
                "procedure2.ProcedureExecutor: ThreadGroup java.lang.ThreadGroup");
        blackListErrorLog.add(
                "zookeeper.ClientCnxn");
    }

    public boolean build() throws Exception {
        for (int i = 0; i < dockers.length; ++i) {
            dockers[i] = new HBaseDocker(this, i);
            dockers[i].build();
        }
        extranodes[0] = new HBaseHDFSDocker(this, 100);
        extranodes[0].build();
        return true;
    }

    @Override
    public void refreshNetwork() {
        this.subnetID = RandomUtils.nextInt(1, 256);
        this.subnet = "192.168." + subnetID + ".0/24";
        this.hostIP = "192.168." + subnetID + ".1";
        this.seedIP = DockerCluster.getKthIP(hostIP, 0);
        try {
            this.build();
        } catch (Exception e) {
            logger.error("Cannot build cluster " + e);
        }
    }

    public void teardown() {
        // Chmod so that we can read/write them on the host machine
        try {
            for (Docker docker : dockers) {
                docker.chmodDir();
            }
        } catch (Exception e) {
            logger.error("fail to chmod dir");
        }

        logger.debug("kill all containers");
        for (Docker docker : dockers) {
            logger.debug("killing container " + docker.containerName);
            forceKillContainer(docker.containerName);
        }
        for (Docker docker : extranodes) {
            logger.debug("killing container " + docker.containerName);
            forceKillContainer(docker.containerName);
        }
        logger.debug("finished killing containers");

        try {
            // Sometimes, the container is not fully stopped.
            // Shutdown it forcefully.
            Process buildProcess = Utilities.exec(
                    new String[] { "docker", "compose", "down" }, workdir);
            buildProcess.waitFor();
            logger.info("teardown docker compose in " + workdir);
        } catch (IOException | InterruptedException e) {
            logger.error("failed to teardown docker", e);
        }

        if (!Config.getConf().keepDir) {
            try {
                Utilities.exec(new String[] { "rm", "-rf",
                        this.workdir
                                .getAbsolutePath() },
                        ".");
            } catch (IOException e) {
                e.printStackTrace();
            }
            logger.info("[teardown] deleting dir");
        }
    }

    @Override
    public String getNetworkIP() {
        return hostIP;
    }

    @Override
    public IDocker getDocker(int i) {
        return dockers[i];
    }

    static String template = ""
            + "version: '3'\n"
            + "services:\n"
            + "\n"
            + "${dockers}"
            + "\n"
            + "networks:\n"
            + "    ${networkName}:\n"
            + "        driver: bridge\n"
            + "        ipam:\n"
            + "            driver: default\n"
            + "            config:\n"
            + "                - subnet: ${subnet}\n";

    @Override
    public void formatComposeYaml() {
        Map<String, String> formatMap = new HashMap<>();

        StringBuilder sb = new StringBuilder();
        sb.append(extranodes[0].formatComposeYaml());
        for (Docker docker : dockers) {
            sb.append(docker.formatComposeYaml());
        }
        String dockersFormat = sb.toString();
        formatMap.put("dockers", dockersFormat);
        formatMap.put("subnet", subnet);
        formatMap.put("networkName", networkName);
        StringSubstitutor sub = new StringSubstitutor(formatMap);
        this.composeYaml = sub.replace(template);
    }

    // Fault Injection
    // If we crash a docker container, the cqlsh might also be killed.
    // So next time if we want to issue a command, we might need to send
    // the command to a different node.

    // Disconnect the network of a set of containers
    // Inside the nodes, they also cannot communicate with each other (Not
    // Partition)
    public boolean disconnectNetwork(Set<Integer> nodeIndexes) {
        // First check whether all indexes is valid
        int maxNodeIndex = Collections.max(nodeIndexes);
        int minNodeIndex = Collections.min(nodeIndexes);

        if (maxNodeIndex >= nodeNum || minNodeIndex < 0) {
            throw new RuntimeException(
                    "The nodeIndex is out of range. maxNodeIndex = "
                            + maxNodeIndex
                            + ", minNodeIndex = " + minNodeIndex
                            + ", nodeNum = " + nodeNum);
        }
        for (int nodeIndex : nodeIndexes) {
            try {
                if (!disconnectNetwork(dockers[nodeIndex]))
                    return false;
            } catch (IOException | InterruptedException e) {
                logger.error("Cannot disconnect network of container "
                        + dockers[nodeIndex].containerName + " exception: "
                        + e);
            }
        }
        return true;
    }

    // Disconnect one container from network
    private boolean disconnectNetwork(DockerMeta docker)
            throws IOException, InterruptedException {
        String[] disconnectNetworkCMD = new String[] {
                "docker", "network", "disconnect", "-f", networkID,
                docker.containerName
        };
        Process disconnProcess = Utilities.exec(disconnectNetworkCMD, workdir);
        int ret = disconnProcess.waitFor();

        return ret == 0;
    }

    @Override
    public void prepareUpgrade() throws Exception {
    }

    @Override
    public void upgrade(int nodeIndex) throws Exception {
        if (dockerStates[nodeIndex].alive) {
            logger.info(String.format("Upgrade Node[%d]", nodeIndex));
            dockers[nodeIndex].flush();
            dockers[nodeIndex].shutdown();
            ((HBaseDocker) dockers[nodeIndex]).rollingUpgrade();
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
    public boolean fullStopUpgrade() throws Exception {
        logger.info("Cluster full-stop upgrading...");
        String[] versionPartsOriginal = Config.getConf().originalVersion
                .substring(originalVersion.indexOf("-") + 1)
                .split("\\.");
        String[] versionPartsUpgraded = Config.getConf().upgradedVersion
                .substring(upgradedVersion.indexOf("-") + 1)
                .split("\\.");
        boolean upgradeTo2_2 = (!Config.getConf().testSingleVersion) &&
                (Integer.parseInt(versionPartsOriginal[0]) == 2
                        && Integer.parseInt(versionPartsOriginal[1]) < 2)
                &&
                (Integer.parseInt(versionPartsUpgraded[0]) >= 2
                        && Integer.parseInt(versionPartsUpgraded[1]) >= 2);

        // Shutdown all rs
        if (upgradeTo2_2) {
            for (int i = 0; i < dockers.length; i++) {
                ((HBaseDocker) dockers[i])
                        .prepareUpgradeTo2_2(HBaseDocker.NodeType.MASTER,
                                originalVersion);
            }
            for (int i = 0; i < dockers.length; i++) {
                ((HBaseDocker) dockers[i])
                        .shutdownWithType(
                                HBaseDocker.NodeType.REGIONSERVER);
            }
            // Shutdown all zk
            for (int i = 0; i < dockers.length; i++) {
                ((HBaseDocker) dockers[i])
                        .shutdownWithType(HBaseDocker.NodeType.ZOOKEEPER);
            }
        } else {
            for (int i = 0; i < dockers.length; i++) {
                ((HBaseDocker) dockers[i])
                        .shutdownWithType(HBaseDocker.NodeType.REGIONSERVER);
            }
            // Shutdown all masters
            for (int i = 0; i < dockers.length; i++) {
                ((HBaseDocker) dockers[i])
                        .shutdownWithType(HBaseDocker.NodeType.MASTER);
            }
            // Shutdown all zk
            for (int i = 0; i < dockers.length; i++) {
                ((HBaseDocker) dockers[i])
                        .shutdownWithType(HBaseDocker.NodeType.ZOOKEEPER);
            }
        }
        prepareUpgrade();
        for (int i = 0; i < dockers.length; i++) {
            dockers[i].upgrade();
        }
        for (int i = 0; i < dockers.length; i++) {
            dockers[i].start();
        }
        logger.info("Cluster upgraded");
        return true;
    }

    @Override
    public boolean rollingUpgrade() throws Exception {
        logger.info("Cluster upgrading...");
        prepareUpgrade();
        for (int i = 0; i < dockers.length; ++i) {
            dockers[i].flush();
            dockers[i].shutdown();
            ((HBaseDocker) dockers[i]).rollingUpgrade();
            dockerStates[i].dockerVersion = DockerMeta.DockerVersion.upgraded;
        }
        logger.info("Cluster upgraded");
        return true;
    }

    public HBaseDocker getMasterDocker() {
        if (dockers == null || dockers.length == 0) {
            return null;
        }
        return (HBaseDocker) dockers[0];
    }

    public int getExpectedRegionServerCount() {
        return Math.max(0, nodeNum - 1);
    }

    public String getRegionServerHostForNode(int nodeIndex) {
        if (nodeIndex <= 0 || nodeIndex >= nodeNum) {
            return null;
        }
        // RegionServer container hostnames follow hregion<nodeIndex>.
        // Keep this independent from optional config helpers so remote
        // cloudlab repos with slightly older Config APIs still compile.
        return "hregion" + nodeIndex;
    }

    public String[] getExpectedRegionServerHosts() {
        int expected = getExpectedRegionServerCount();
        String[] hosts = new String[expected];
        for (int i = 1; i <= expected; i++) {
            hosts[i - 1] = "hregion" + i;
        }
        return hosts;
    }

    @Override
    public void finalizeUpgrade() {
        logger.debug("HBase upgrade finalized");
    }
}
