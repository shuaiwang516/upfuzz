package org.zlab.upfuzz.hdfs;

import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;

import org.apache.commons.lang3.RandomUtils;
import org.apache.commons.text.StringSubstitutor;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.zlab.upfuzz.docker.DockerCluster;
import org.zlab.upfuzz.docker.IDocker;
import org.zlab.upfuzz.fuzzingengine.Config;
import org.zlab.upfuzz.utils.Utilities;

public class HdfsDockerCluster extends DockerCluster {
    static Logger logger = LogManager.getLogger(HdfsDockerCluster.class);

    String namenodeIP;

    static final String includes = "org.apache.hadoop.hdfs.*";
    static final String excludes = "org.apache.cassandra.*";

    public static String[] includeJacocoHandlers = {
            "org.apache.hadoop.hdfs.server.namenode.NameNode",
            "org.apache.hadoop.hdfs.server.namenode.SecondaryNameNode",
            "org.apache.hadoop.hdfs.server.datanode.DataNode"
    };

    HdfsDockerCluster(HdfsExecutor executor, String version,
            int nodeNum, boolean collectFormatCoverage, Path configPath,
            int direction) {
        super(executor, version, nodeNum, collectFormatCoverage,
                direction);

        this.dockers = new HdfsDocker[nodeNum];
        this.namenodeIP = DockerCluster.getKthIP(hostIP, 0); // 2 means the
        this.configpath = configPath;

        initBlackListErrorLog();
    }

    public void initBlackListErrorLog() {
        blackListErrorLog.add("Error response from daemon: Container");
        blackListErrorLog.add("RECEIVED SIGNAL");
        // During rolling upgrade, SNN may transiently fail checkpoint while NN
        // is restarting/upgrading. Treat these as expected transient logs.
        blackListErrorLog.add(
                "SecondaryNameNode: Exception in doCheckpoint");
        blackListErrorLog.add(
                "java.net.ConnectException: Connection refused (Connection refused)");
    }

    public boolean build() throws Exception {
        for (int i = 0; i < dockers.length; ++i) {
            dockers[i] = new HdfsDocker(this, i);
            dockers[i].build();
        }
        return true;
    }

    @Override
    public void refreshNetwork() {
        this.subnetID = RandomUtils.nextInt(1, 256);
        this.subnet = "192.168." + Integer.toString(subnetID) + ".0/24";
        this.hostIP = "192.168." + Integer.toString(subnetID) + ".1";
        this.namenodeIP = DockerCluster.getKthIP(hostIP, 0);
        try {
            this.build();
        } catch (Exception e) {
            logger.error("Cannot build cluster " + e);
        }
    }

    @Override
    public void prepareUpgrade() {
        logger.info("hdfs prepared upgrade");
        int idx = getFirstLiveNodeIdx();
        if (idx == -1) {
            logger.error("cannot upgrade, all nodes are down");
            throw new RuntimeException(
                    "all nodes are down, cannot prepare upgrade");
        }
        String oriHDFS = "/" + system + "/" + originalVersion + "/"
                + "bin/hdfs";
        String[] enterSafemode = new String[] { oriHDFS, "dfsadmin",
                "-safemode", "enter" };
        String[] prepareFSImage = new String[] { oriHDFS, "dfsadmin",
                "-rollingUpgrade", "prepare" };
        String[] leaveSafemode = new String[] { oriHDFS, "dfsadmin",
                "-safemode", "leave" };
        int ret;
        ret = dockers[idx].runProcessInContainer(enterSafemode,
                dockers[idx].env);
        logger.debug("enter safe mode ret = " + ret);
        ret = dockers[idx].runProcessInContainer(prepareFSImage,
                dockers[idx].env);
        logger.debug("prepare image ret = " + ret);
        ret = dockers[idx].runProcessInContainer(leaveSafemode,
                dockers[idx].env);
        logger.debug("leave safemode ret = " + ret);
    }

    @Override
    public void finalizeUpgrade() {
        String upHadoopHDFSPath = "/" + system + "/" + upgradedVersion + "/"
                + "bin/hdfs";
        String[] finalizeUpgradeCmd = new String[] {
                upHadoopHDFSPath, "dfsadmin", "-rollingUpgrade",
                "finalize"
        };
        dockers[0].runProcessInContainer(finalizeUpgradeCmd);
        logger.debug("hdfs upgrade finalized");
    }

    @Override
    public boolean fullStopUpgrade() throws Exception {
        logger.info("[HDFS] Cluster full-stop upgrading...");
        // TODO: invoke prepareUpgrade() if it's not invoked before
        stopSNN(); // HDFS is special since it needs to stop SNN first
        for (int i = 0; i < dockers.length; i++) {
            dockers[i].flush();
            dockers[i].shutdown();
        }
        for (int i = 0; i < dockers.length; i++) {
            dockers[i].upgrade();
        }
        logger.info("Cluster upgraded");
        return true;
    }

    public void stopSNN() {
        String orihadoopDaemonPath = "/" + system + "/" + originalVersion + "/"
                + "sbin/hadoop-daemon.sh";
        String[] stopNode = new String[] { orihadoopDaemonPath, "stop",
                "secondarynamenode" };
        int ret = dockers[1].runProcessInContainer(stopNode, dockers[1].env);
        logger.debug("secondarynamenode stop: " + ret);
    }

    public void teardown() {
        // Chmod so that we can read/write them on the host machine
        try {
            for (int i = 0; i < dockers.length; ++i) {
                dockers[i].chmodDir();
            }
        } catch (Exception e) {
            logger.error("fail to chmod dir");
        }

        try {
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
        for (int i = 0; i < dockers.length; ++i) {
            sb.append(dockers[i].formatComposeYaml());
        }
        String dockersFormat = sb.toString();
        formatMap.put("dockers", dockersFormat);
        formatMap.put("subnet", subnet);
        formatMap.put("networkName", networkName);
        StringSubstitutor sub = new StringSubstitutor(formatMap);
        this.composeYaml = sub.replace(template);
    }
}
