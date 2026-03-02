package org.zlab.upfuzz.hbase;

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
import org.zlab.upfuzz.fuzzingengine.packet.ValidationResult;
import org.zlab.upfuzz.utils.Utilities;

public class HBaseDocker extends Docker {
    protected final Logger logger = LogManager.getLogger(getClass());

    public enum NodeType {
        ZOOKEEPER, MASTER, REGIONSERVER
    }

    String composeYaml;
    String javaToolOpts;
    int HBaseDaemonPort = 36000;
    public int direction;

    public String seedIP;

    public HBaseDocker(HBaseDockerCluster dockerCluster, int index) {
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

        collectFormatCoverage = dockerCluster.collectFormatCoverage;
        configPath = dockerCluster.configpath;

        if (Config.getConf().testSingleVersion)
            containerName = "hbase-" + originalVersion + "_" + executorID
                    + "_N" + index;
        else
            containerName = "hbase-" + originalVersion + "_"
                    + upgradedVersion + "_" + executorID + "_N" + index;
    }

    @Override
    public String getNetworkIP() {
        return networkIP;
    }

    @Override
    public String formatComposeYaml() {
        Map<String, String> formatMap = new HashMap<>();

        containerName = "hbase-" + originalVersion + "_" + upgradedVersion +
                "_" + executorID + "_N" + index;
        formatMap.put("projectRoot", System.getProperty("user.dir"));
        formatMap.put("system", system);
        formatMap.put("originalVersion", originalVersion);
        formatMap.put("upgradedVersion", upgradedVersion);
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
                    "\"";
        } else {
            javaToolOpts = "JAVA_TOOL_OPTIONS=\"\"";
        }

        String pythonVersion = "python2";

        String[] spStrings = originalVersion.split("-");
        try {
            int main_version = Integer
                    .parseInt(spStrings[spStrings.length - 1].substring(0, 1));
            if (Config.getConf().debug)
                logger.debug("[HKLOG] original main version = " + main_version);
            if (main_version > 3)
                pythonVersion = "python3";
        } catch (Exception e) {
            e.printStackTrace();
        }

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
                "NET_TRACE_NODE_ID=" + executorID + "-N" + index
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
        waitForControlPlaneReady();
    }

    private void waitForControlPlaneReady() throws Exception {
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
                    if (probe.isSuccess() && !transientZkIssue) {
                        logger.info(String.format(
                                "Node[%d] control plane ready after %d attempts via {%s}",
                                index, attempt, probeCommand));
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

    private boolean isZkTransient(String mergedLower) {
        return mergedLower.contains("zookeeper get/list could not be completed")
                || mergedLower.contains("recoverablezookeeper")
                || mergedLower.contains("connectionloss")
                || mergedLower.contains("keepererrorcode = connectionloss")
                || mergedLower.contains("annotatednoroutetohostexception")
                || mergedLower.contains("connection refused");
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
                    "\"";
        } else {
            javaToolOpts = "JAVA_TOOL_OPTIONS=\"\"";
        }
        HBaseDaemonPort ^= 1;

        String pythonVersion = "python2";
        String[] spStrings = upgradedVersion.split("-");
        try {
            int main_version = Integer
                    .parseInt(spStrings[spStrings.length - 1].substring(0, 1));
            if (main_version >= 3)
                pythonVersion = "python3";
        } catch (Exception e) {
            e.printStackTrace();
        }
        env = new String[] {
                "HBASE_HOME=\"" + HBaseHome + "\"",
                "HBASE_CONF=\"" + HBaseConf + "\"", javaToolOpts,
                "HBASE_SHELL_DAEMON_PORT=\"" + HBaseDaemonPort + "\"",
                "CUR_STATUS=UP",
                "PYTHON=" + pythonVersion,
                "ENABLE_FORMAT_COVERAGE=false",
                "ENABLE_NET_COVERAGE=" + Config.getConf().useTrace,
                "ENABLE_NETWORK_TRACE=" + Config.getConf().useTrace,
                "NET_TRACE_NODE_ID=" + executorID + "-N" + index };
        setEnvironment();
    }

    @Override
    public void downgrade() throws Exception {
    }

    public void prepareUpgradeTo2_2(NodeType nodeType, String version)
            throws Exception {
        if (nodeType == NodeType.MASTER) {
            if (index == 0) {
                String hbaseDaemonPath = "/" + system + "/" + version + "/"
                        + "bin/hbase-daemon.sh";
                // N0: hbase master, zookeeper
                String[] stopHMaster = new String[] {
                        hbaseDaemonPath, "--config", "/etc/" + version,
                        "stop", "master" };
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

                String[] startHMaster = new String[] { "/bin/bash", "-c",
                        "\"" + hbaseDaemonPath, "--config", "/etc/" + version,
                        "start", "master\"" };

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
        String hbaseDaemonPath = "/" + system + "/" + curVersion + "/"
                + "bin/hbase-daemon.sh";
        if (nodeType == NodeType.REGIONSERVER) {
            if (index == 1 || index == 2) {
                String[] stopNode = new String[] {
                        hbaseDaemonPath, "--config", "/etc/" + curVersion,
                        "stop", "regionserver" };
                int ret = runProcessInContainer(stopNode, env);
                logger.debug(String.format(
                        "shutdown regionserver (index = %d) ret = %d", index,
                        ret));
            }
        } else if (nodeType == NodeType.MASTER) {
            if (index == 0) {
                // N0: hbase master, zookeeper
                String[] stopHMaster = new String[] {
                        hbaseDaemonPath, "--config", "/etc/" + curVersion,
                        "stop", "master" };
                int ret = runProcessInContainer(stopHMaster, env);
                logger.debug("shutdown " + "hmaster" + " ret = " + ret);
            }
        } else if (nodeType == NodeType.ZOOKEEPER) {
            String[] stopZK = new String[] {
                    hbaseDaemonPath, "--config", "/etc/" + curVersion,
                    "stop", "zookeeper" };
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
        String hbaseDaemonPath = "/" + system + "/" + curVersion + "/"
                + "bin/hbase-daemon.sh";

        if (index == 0) {
            // N0: hbase master, zookeeper
            String[] stopHMaster = new String[] {
                    hbaseDaemonPath, "--config", "/etc/" + curVersion,
                    "stop", "master" };
            int ret = runProcessInContainer(stopHMaster, env);
            logger.debug("shutdown " + "hmaster" + " ret = " + ret);
        } else {
            // N1, N2: regionserver
            String[] stopNode = new String[] {
                    hbaseDaemonPath, "--config", "/etc/" + curVersion,
                    "stop", "regionserver" };
            int ret = runProcessInContainer(stopNode, env);
            logger.debug(String.format(
                    "shutdown regionserver (index = %d) ret = %d", index, ret));
        }
        String[] stopZK = new String[] {
                hbaseDaemonPath, "--config", "/etc/" + curVersion,
                "stop", "zookeeper" };
        int ret = runProcessInContainer(stopZK, env);
        logger.debug("shutdown " + "zookeeper" + index + " ret = " + ret);
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
            + "        container_name: hbase-${originalVersion}_${upgradedVersion}_${executorID}_N${index}\n"
            + "        image: upfuzz_${system}:${originalVersion}_${upgradedVersion}\n"
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
