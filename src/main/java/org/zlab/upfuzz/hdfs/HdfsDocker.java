package org.zlab.upfuzz.hdfs;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

import org.apache.commons.text.StringSubstitutor;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.zlab.upfuzz.docker.Docker;
import org.zlab.upfuzz.docker.DockerCluster;
import org.zlab.upfuzz.fuzzingengine.Config;
import org.zlab.upfuzz.fuzzingengine.LogInfo;
import org.zlab.upfuzz.utils.Utilities;

public class HdfsDocker extends Docker {
    protected final Logger logger = LogManager.getLogger(getClass());

    public static final int SAFEMODE_WAIT_INTERVAL = 30; // s
    String composeYaml;
    String javaToolOpts;
    int hdfsDaemonPort = 11116;
    public int direction;

    public String namenodeIP;

    public HdfsDocker(HdfsDockerCluster dockerCluster, int index) {
        this.index = index;
        this.direction = dockerCluster.direction;
        type = "original";
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
        namenodeIP = dockerCluster.namenodeIP;
        agentPort = dockerCluster.agentPort;
        includes = dockerCluster.includes;
        excludes = dockerCluster.excludes;
        executorID = dockerCluster.executorID;
        serviceName = "DC3N" + index; // Remember update the service name

        collectFormatCoverage = dockerCluster.collectFormatCoverage;
        configPath = dockerCluster.configpath;
        if (Config.getConf().testSingleVersion)
            containerName = "hdfs-" + originalVersion + "_" + executorID + "_N"
                    + index;
        else
            containerName = "hdfs-" + originalVersion + "_" + upgradedVersion +
                    "_" + executorID + "_N" + index;
    }

    @Override
    public String getNetworkIP() {
        return networkIP;
    }

    public String formatComposeYaml() {
        Map<String, String> formatMap = new HashMap<>();
        formatMap.put("projectRoot", System.getProperty("user.dir"));
        formatMap.put("system", system);
        formatMap.put("originalVersion", originalVersion);
        formatMap.put("upgradedVersion", upgradedVersion);
        formatMap.put("index", Integer.toString(index));
        formatMap.put("networkName", networkName);
        formatMap.put("JAVA_TOOL_OPTIONS", javaToolOpts);
        formatMap.put("subnet", subnet);
        formatMap.put("namenodeIP", namenodeIP);
        formatMap.put("networkIP", networkIP);
        formatMap.put("agentPort", Integer.toString(agentPort));
        formatMap.put("formatCoveragePort",
                Integer.toString(Config.instance.formatCoveragePort));
        formatMap.put("executorID", executorID);
        StringSubstitutor sub = new StringSubstitutor(formatMap);
        this.composeYaml = sub.replace(template);

        return composeYaml;
    }

    @Override
    public int start() throws Exception {
        // Connect to the HDFS daemon
        shell = new HDFSShellDaemon(getNetworkIP(), hdfsDaemonPort,
                executorID, this);
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
        String hdfsHome = "/hdfs/" + originalVersion;
        String hdfsConf = "/etc/" + originalVersion + "/etc/hadoop";

        javaToolOpts = "JAVA_TOOL_OPTIONS=\"-javaagent:"
                + "/org.jacoco.agent.rt.jar"
                + "=append=false"
                + ",includes=" + includes + ",excludes=" + excludes +
                ",output=dfe,address=" + hostIP + ",port=" + agentPort +
                // ",weights=" + hdfsHome + "/diff_func.txt" +
                ",sessionid=" + system + "-" + executorID + "_"
                + type + "-" + index +
                "\"";

        env = new String[] {
                "HADOOP_HOME=" + hdfsHome,
                "HADOOP_CONF_DIR=" + hdfsConf, javaToolOpts,
                "HDFS_SHELL_DAEMON_PORT=\"" + hdfsDaemonPort + "\"",
                "PYTHON=python3",
                "ENABLE_FORMAT_COVERAGE=" + (Config.getConf().useFormatCoverage
                        && collectFormatCoverage),
                "ENABLE_NET_COVERAGE=" + Config.getConf().useTrace,
                "ENABLE_NETWORK_TRACE=" + Config.getConf().useTrace,
                "NET_TRACE_NODE_ID=" + executorID + "-N" + index
        };
        setEnvironment();

        // Copy the cassandra-ori.yaml and cassandra-up.yaml
        if (configPath != null) {
            copyConfig(configPath, direction);
        }
        return false;
    }

    @Override
    public void shutdown() {
        String nodeType;
        if (index == 0) {
            nodeType = "namenode";
        } else if (index == 1) {
            nodeType = "secondarynamenode";
        } else {
            nodeType = "datanode";
        }

        String curVersion = type.equals("upgraded") ? upgradedVersion
                : originalVersion;
        String orihadoopDaemonPath = "/" + system + "/" + curVersion + "/"
                + "sbin/hadoop-daemon.sh";

        if (!nodeType.equals("secondarynamenode")) {
            // Secondary is stopped in a specific op (HDFSStopSNN)
            String[] stopNode = new String[] { orihadoopDaemonPath, "stop",
                    nodeType };

            int ret = runProcessInContainer(stopNode, env);
            logger.info("daemon stopped ret = " + ret);
            logger.debug("shutdown " + nodeType);
        }
    }

    public void prepareUpgradeEnv() throws IOException {
        type = "upgraded";
        String hdfsHome = "/hdfs/" + upgradedVersion;
        String hdfsConf = "/etc/" + upgradedVersion + "/etc/hadoop";

        javaToolOpts = "JAVA_TOOL_OPTIONS=\"-javaagent:"
                + "/org.jacoco.agent.rt.jar"
                + "=append=false"
                + ",includes=" + includes + ",excludes=" + excludes +
                ",output=dfe,address=" + hostIP + ",port=" + agentPort +
                // ",weights=" + hdfsHome + "/diff_func.txt" +
                ",sessionid=" + system + "-" + executorID + "_"
                + type + "-" + index +
                "\"";

        // hdfsDaemonPort ^= 1;
        env = new String[] {
                "HADOOP_HOME=" + hdfsHome,
                "HADOOP_CONF_DIR=" + hdfsConf, javaToolOpts,
                "HDFS_SHELL_DAEMON_PORT=\"" + hdfsDaemonPort + "\"",
                "PYTHON=python3",
                "ENABLE_FORMAT_COVERAGE=false",
                "ENABLE_NET_COVERAGE=" + Config.getConf().useTrace,
                "ENABLE_NETWORK_TRACE=" + Config.getConf().useTrace,
                "NET_TRACE_NODE_ID=" + executorID + "-N" + index
        };
        setEnvironment();
    }

    public void waitSafeModeInterval() {
        // For NN, if it's a rolling upgrade, add a safemode waiting time for 30
        // seconds.
        // This might be changed if HA is enabled.
        if (index == 0) {
            logger.info("NN waiting for safemode interval for 30s");
            try {
                Thread.sleep(SAFEMODE_WAIT_INTERVAL * 1000);
            } catch (InterruptedException ignored) {
            }
        }
    }

    @Override
    public void upgrade() throws Exception {
        prepareUpgradeEnv();
        String restartCommand = "/usr/bin/supervisorctl restart upfuzz_hdfs:";
        // Seems the env doesn't really matter...
        Process restart = runInContainer(
                new String[] { "/bin/bash", "-c", restartCommand });
        int ret = restart.waitFor();
        String message = Utilities.readProcess(restart);
        logger.debug("upgrade version start: " + ret + "\n" + message);
        shell = new HDFSShellDaemon(getNetworkIP(), hdfsDaemonPort,
                executorID, this);
        waitSafeModeInterval();
    }

    @Override
    public void upgradeFromCrash() throws Exception {
        prepareUpgradeEnv();
        restart();
        waitSafeModeInterval();
    }

    @Override
    public void downgrade() throws Exception {
        type = "original";
        String hdfsHome = "/hdfs/" + originalVersion;
        String hdfsConf = "/etc/" + originalVersion + "/etc/hadoop";

        javaToolOpts = "JAVA_TOOL_OPTIONS=\"-javaagent:"
                + "/org.jacoco.agent.rt.jar"
                + "=append=false"
                + ",includes=" + includes + ",excludes=" + excludes +
                ",output=dfe,address=" + hostIP + ",port=" + agentPort +
                // ",weights=" + hdfsHome + "/diff_func.txt" +
                ",sessionid=" + system + "-" + executorID + "_"
                + type + "-" + index +
                "\"";

        // hdfsDaemonPort ^= 1;
        env = new String[] {
                "HADOOP_HOME=" + hdfsHome,
                "HADOOP_CONF_DIR=" + hdfsConf, javaToolOpts,
                "HDFS_SHELL_DAEMON_PORT=\"" + hdfsDaemonPort + "\"",
                "PYTHON=python3",
                "ENABLE_FORMAT_COVERAGE=false",
                "ENABLE_NET_COVERAGE=" + Config.getConf().useTrace,
                "ENABLE_NETWORK_TRACE=" + Config.getConf().useTrace,
                "NET_TRACE_NODE_ID=" + executorID + "-N" + index };
        setEnvironment();
        String restartCommand = "/usr/bin/supervisorctl restart upfuzz_hdfs:";
        // Seems the env doesn't really matter...
        Process restart = runInContainer(
                new String[] { "/bin/bash", "-c", restartCommand });
        int ret = restart.waitFor();
        String message = Utilities.readProcess(restart);
        logger.debug(
                "downgrade to original version start: " + ret + "\n" + message);
        shell = new HDFSShellDaemon(getNetworkIP(), hdfsDaemonPort,
                executorID, this);
        waitSafeModeInterval();
    }

    @Override
    public boolean clear() {
        try {
            runInContainer(new String[] {
                    "rm", "-rf", "/var/hadoop/data/*"
            });
        } catch (IOException e) {
            logger.error(e);
            // FIXME: remove this line after debugging
            System.exit(1);
        }
        return true;
    }

    @Override
    public LogInfo grepLogInfo(Set<String> blackListErrorLog) {
        LogInfo logInfo = new LogInfo();
        Path filePath = Paths.get("/var/log/hdfs/*.log");

        constructLogInfo(logInfo, filePath, blackListErrorLog);
        return logInfo;
    }

    public Path getWorkPath() {
        return workdir.toPath();
    }

    static String template = ""
            + "    DC3N${index}:\n"
            + "        container_name: hdfs-${originalVersion}_${upgradedVersion}_${executorID}_N${index}\n"
            + "        image: upfuzz_${system}:${originalVersion}_${upgradedVersion}\n"
            + "        command: bash -c 'sleep 0 && /usr/bin/supervisord'\n"
            + "        networks:\n"
            + "            ${networkName}:\n"
            + "                ipv4_address: ${networkIP}\n"
            + "        volumes:\n"
            // + " -
            // ./persistent/node_${index}/nndata:/var/hadoop/data/nameNode\n"
            // + " -
            // ./persistent/node_${index}/dndata:/var/hadoop/data/dataNode\n"
            + "            - ./persistent/node_${index}/log:/var/log/hdfs\n"
            + "            - ./persistent/node_${index}/env.sh:/usr/bin/set_env\n"
            + "            - ./persistent/node_${index}/consolelog:/var/log/supervisor\n"
            + "            - ./persistent/config:/test_config\n"
            + "            - /tmp/upfuzz/hdfs:/tmp/upfuzz/hdfs\n"
            + "            - ${projectRoot}/prebuild/${system}/${originalVersion}:/${system}/${originalVersion}\n"
            + "            - ${projectRoot}/prebuild/${system}/${upgradedVersion}:/${system}/${upgradedVersion}\n"
            + "        environment:\n"
            + "            - HDFS_CLUSTER_NAME=dev_cluster\n"
            + "            - namenodeIP=${namenodeIP},\n"
            + "            - HDFS_LOGGING_LEVEL=DEBUG\n"
            + "            - HDFS_SHELL_HOST=${networkIP}\n"
            + "            - HDFS_LOG_DIR=/var/log/hadoop\n"
            + "        expose:\n"
            + "            - ${agentPort}\n"
            + "            - 50020\n"
            + "            - 50010\n"
            + "            - 50075\n"
            + "            - 9000\n"
            + "            - 50070\n"
            + "            - 50090\n"
            + "        ulimits:\n"
            + "            memlock: -1\n"
            + "            nproc: 32768\n"
            + "            nofile: 100000\n";

    @Override
    public void chmodDir() throws IOException, InterruptedException {
        runInContainer(
                new String[] { "chmod", "-R", "777", "/var/hadoop/data" });
        runInContainer(
                new String[] { "chmod", "-R", "777", "/var/log/hdfs" });
        runInContainer(
                new String[] { "chmod", "-R", "777", "/var/log/supervisor" });
        runInContainer(
                new String[] { "chmod", "-R", "777", "/usr/bin/set_env" });
    }

    @Override
    public void flush() throws Exception {
    }

    @Override
    public void restart() throws Exception {
        String[] containerRecoverCMD = new String[] {
                "docker", "compose", "restart", serviceName
        };
        Process containerRecoverProcess = Utilities.exec(
                containerRecoverCMD,
                workdir);
        containerRecoverProcess.waitFor();

        // recreate connection
        start();

        waitSafeModeInterval();
        logger.info(
                String.format("Node%d restart successfully!", index));
    }
}
