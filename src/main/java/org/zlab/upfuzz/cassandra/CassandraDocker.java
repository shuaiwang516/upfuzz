package org.zlab.upfuzz.cassandra;

import java.io.*;
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

public class CassandraDocker extends Docker {
    protected final Logger logger = LogManager.getLogger(getClass());

    String composeYaml;
    String javaToolOpts;
    int cqlshDaemonPort = 18250;
    public int direction;

    public String seedIP;

    public CassandraDocker(CassandraDockerCluster dockerCluster, int index) {
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
        includes = CassandraDockerCluster.includes;
        excludes = CassandraDockerCluster.excludes;
        executorID = dockerCluster.executorID;
        serviceName = "DC3N" + index;

        collectFormatCoverage = dockerCluster.collectFormatCoverage;
        configPath = dockerCluster.configpath;
        if (Config.getConf().testSingleVersion)
            containerName = "cassandra-" + originalVersion + "_" + executorID
                    + "_N" + index;
        else
            containerName = "cassandra-" + originalVersion + "_"
                    + upgradedVersion + "_" + executorID + "_N" + index;
    }

    @Override
    public String getNetworkIP() {
        return networkIP;
    }

    @Override
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
        formatMap.put("seedIP", seedIP);
        formatMap.put("networkIP", networkIP);
        formatMap.put("agentPort", Integer.toString(agentPort));
        formatMap.put("formatCoveragePort",
                Integer.toString(Config.instance.formatCoveragePort));
        formatMap.put("executorID", executorID);
        formatMap.put("serviceName", serviceName);

        StringSubstitutor sub = new StringSubstitutor(formatMap);
        if (Config.getConf().testSingleVersion)
            this.composeYaml = sub.replace(singleVersionTemplate);
        else
            this.composeYaml = sub.replace(template);
        return composeYaml;
    }

    @Override
    public int start() throws Exception {
        shell = new CassandraCqlshDaemon(getNetworkIP(), cqlshDaemonPort, this);
        return 0;
    }

    private void setEnvironment() throws IOException {
        File envFile = new File(workdir,
                "./persistent/node_" + index + "/env.sh");

        FileWriter fw;
        envFile.getParentFile().mkdirs();
        fw = new FileWriter(envFile, false);
        for (String s : env) {
            if (Config.getConf().debug)
                logger.debug("env to be written: " + s);
            fw.write("export " + s + "\n");
        }
        fw.close();
    }

    private void handleEnv(String curVersion, String cassandraHome,
            String cassandraConf, boolean useFormatCoverage)
            throws IOException {
        // Set up /usr/bin/set_env
        String[] spStrings = curVersion.split("-");
        String pythonVersion = "python2";
        String jdkPath = "/opt/java/openjdk/";
        try {
            String version = spStrings[spStrings.length - 1];
            int main_version = Integer
                    .parseInt(version.substring(0, 1));
            if (Config.getConf().debug) {
                logger.debug("[HKLOG] original main version = " + main_version);
            }
            if (main_version > 3 && !version.equals("4.0.0"))
                pythonVersion = "python3";
            if (main_version >= 5)
                jdkPath = "/usr/lib/jvm/java-11-openjdk-amd64";
        } catch (Exception e) {
            e.printStackTrace();
        }

        env = new String[] {
                "CASSANDRA_HOME=\"" + cassandraHome + "\"",
                "CASSANDRA_CONF=\"" + cassandraConf + "\"", javaToolOpts,
                "CQLSH_DAEMON_PORT=\"" + cqlshDaemonPort + "\"",
                "PYTHON=" + pythonVersion,
                "JAVA_HOME=" + jdkPath,
                "PATH=$JAVA_HOME/bin:$PATH",
                "ENABLE_FORMAT_COVERAGE=" + (Config.getConf().useFormatCoverage
                        && collectFormatCoverage),
                "ENABLE_NET_COVERAGE=" + Config.getConf().useTrace
        };

        setEnvironment();
    }

    @Override
    public void teardown() {
    }

    @Override
    public boolean build() throws IOException {
        type = (direction == 0) ? "original" : "upgraded";
        if (Config.getConf().debug) {
            logger.debug("[HKLOG] Cassandra Docker, original Version: "
                    + originalVersion + ", modifiedVersion: "
                    + upgradedVersion);
        }
        String cassandraHome = "/cassandra/" + originalVersion;
        String cassandraConf = "/etc/" + originalVersion;
        javaToolOpts = "JAVA_TOOL_OPTIONS=\"-javaagent:"
                + "/org.jacoco.agent.rt.jar"
                + "=append=false"
                + ",includes=" + includes + ",excludes=" + excludes +
                ",output=dfe,address=" + hostIP + ",port=" + agentPort +
                ",sessionid=" + system + "-" + executorID + "_"
                + type + "-" + index +
                "\"";

        // Only enable format coverage before version change
        handleEnv(originalVersion, cassandraHome, cassandraConf,
                Config.getConf().useFormatCoverage && collectFormatCoverage);

        // Copy the cassandra-ori.yaml and cassandra-up.yaml
        if (configPath != null) {
            copyConfig(configPath, direction);
        }
        return true;
    }

    @Override
    public void flush() throws Exception {
        String mode = "flush";
        if (Config.getConf().originalVersion.contains("cassandra-2.")) {
            Process flushCass = this.runInContainer(new String[] {
                    "/" + system + "/" + originalVersion + "/"
                            + "bin/nodetool",
                    "-h", "::FFFF:127.0.0.1",
                    mode });
            flushCass.waitFor();
        } else {
            Process flushCass = this.runInContainer(new String[] {
                    "/" + system + "/" + originalVersion + "/"
                            + "bin/nodetool",
                    mode });
            flushCass.waitFor();
        }
    }

    public void drain() throws Exception {
        String mode = "drain";
        if (Config.getConf().originalVersion.contains("cassandra-2.")) {
            Process flushCass = this.runInContainer(new String[] {
                    "/" + system + "/" + originalVersion + "/"
                            + "bin/nodetool",
                    "-h", "::FFFF:127.0.0.1",
                    mode });
            flushCass.waitFor();
        } else {
            Process drainCass = this.runInContainer(new String[] {
                    "/" + system + "/" + originalVersion + "/"
                            + "bin/nodetool",
                    mode });
            drainCass.waitFor();
        }
    }

    @Override
    public void upgrade() throws Exception {
        prepareUpgradeEnv();
        String restartCommand = "/usr/bin/supervisorctl restart upfuzz_cassandra:";
        Process restart = runInContainer(
                new String[] { "/bin/bash", "-c", restartCommand }, env);
        int ret = restart.waitFor();
        String message = Utilities.readProcess(restart);
        logger.debug("upgrade version start: " + ret + "\n" + message);
        shell = new CassandraCqlshDaemon(getNetworkIP(), cqlshDaemonPort, this);
    }

    @Override
    public void upgradeFromCrash() throws Exception {
        prepareUpgradeEnv();
        restart();
    }

    public void prepareUpgradeEnv() throws IOException {
        type = "upgraded";
        String cassandraHome = "/cassandra/" + upgradedVersion;
        String cassandraConf = "/etc/" + upgradedVersion;
        javaToolOpts = "JAVA_TOOL_OPTIONS=\"-javaagent:"
                + "/org.jacoco.agent.rt.jar"
                + "=append=false"
                + ",includes=" + includes + ",excludes=" + excludes +
                ",output=dfe,address=" + hostIP + ",port=" + agentPort +
                ",sessionid=" + system + "-" + executorID + "_" + type +
                "-" + index +
                "\"";
        cqlshDaemonPort ^= 1;

        handleEnv(upgradedVersion, cassandraHome, cassandraConf, false);
    }

    public void prepareDowngradeEnv() throws IOException {
        type = "original";
        String cassandraHome;
        String cassandraConf;
        if (!Config.getConf().useVersionDelta) {
            cassandraHome = "/cassandra/" + originalVersion;
            cassandraConf = "/etc/" + originalVersion;
        } else {
            cassandraHome = "/cassandra/" + upgradedVersion;
            cassandraConf = "/etc/" + upgradedVersion;
        }
        javaToolOpts = "JAVA_TOOL_OPTIONS=\"-javaagent:"
                + "/org.jacoco.agent.rt.jar"
                + "=append=false"
                + ",includes=" + includes + ",excludes=" + excludes +
                ",output=dfe,address=" + hostIP + ",port=" + agentPort +
                ",sessionid=" + system + "-" + executorID + "_"
                + type + "-" + index +
                "\"";
        cqlshDaemonPort ^= 1;

        // Special for version delta
        String curVersion = (!Config.getConf().useVersionDelta)
                ? originalVersion
                : upgradedVersion;
        logger.info("Downgrading to: " + curVersion);
        handleEnv(curVersion, cassandraHome, cassandraConf, false);
    }

    @Override
    public void downgrade() throws Exception {
        prepareDowngradeEnv();

        // Why removing the data??? Now make sense
        // String removeCassandraLibCommand = "rm -R /var/lib/cassandra";
        // // TODO remove the env arguments, we already have /usr/bin/set_env
        // if (Integer.parseInt(
        // spStrings[spStrings.length - 1].substring(0, 1)) == 2) {
        // Process removeCassandraLib = runInContainer(
        // new String[] { "/bin/bash", "-c",
        // removeCassandraLibCommand });
        // removeCassandraLib.waitFor();
        // logger.info(
        // "[HKLOG] /var/lib/cassandra removed successfully, now the daemon
        // should start");
        // }

        String restartCommand = "/usr/bin/supervisorctl restart upfuzz_cassandra:";
        Process restart = runInContainer(
                new String[] { "/bin/bash", "-c", restartCommand }, env);
        int ret = restart.waitFor();
        String message = Utilities.readProcess(restart);
        logger.debug("downgrade version start: " + ret + "\n" + message);
        shell = new CassandraCqlshDaemon(getNetworkIP(), cqlshDaemonPort, this);
    }

    @Override
    public void shutdown() {
        // Use the target version's script to shutdown the node
        String curVersion = type.equals("upgraded") ? upgradedVersion
                : originalVersion;
        String[] stopNode;
        if (Config.getConf().originalVersion.contains("cassandra-2.")) {
            stopNode = new String[] {
                    "/" + system + "/" + curVersion + "/"
                            + "bin/nodetool",
                    "-h", "::FFFF:127.0.0.1",
                    "stopdaemon" };
            // in this case, the ret will be 1 or 2, this is normal
        } else {
            stopNode = new String[] {
                    "/" + system + "/" + curVersion + "/"
                            + "bin/nodetool",
                    "stopdaemon" };
        }
        int ret = runProcessInContainer(stopNode);
        logger.debug("cassandra shutdown ret = " + ret);
    }

    @Override
    public boolean clear() {
        int ret = runProcessInContainer(new String[] {
                "rm", "-rf", "/var/lib/cassandra/*"
        });
        logger.debug("cassandra clear data ret = " + ret);
        return true;
    }

    public Path getWorkPath() {
        return workdir.toPath();
    }

    public void chmodDir() throws IOException {
        runInContainer(
                new String[] { "chmod", "-R", "777", "/var/log/cassandra" });
        runInContainer(
                new String[] { "chmod", "-R", "777", "/var/lib/cassandra" });
        runInContainer(
                new String[] { "chmod", "-R", "777", "/var/log/supervisor" });
        runInContainer(
                new String[] { "chmod", "-R", "777", "/usr/bin/set_env" });
    }

    // add the configuration test files

    static String singleVersionTemplate = ""
            + "    ${serviceName}:\n"
            + "        container_name: cassandra-${originalVersion}_${executorID}_N${index}\n"
            + "        image: upfuzz_${system}:${originalVersion}\n"
            + "        command: bash -c 'sleep 0 && /usr/bin/supervisord'\n"
            + "        networks:\n"
            + "            ${networkName}:\n"
            + "                ipv4_address: ${networkIP}\n"
            + "        volumes:\n"
            // + " - ./persistent/node_${index}/data:/var/lib/cassandra\n"
            + "            - ./persistent/node_${index}/log:/var/log/cassandra\n"
            + "            - ./persistent/node_${index}/env.sh:/usr/bin/set_env\n"
            + "            - ./persistent/node_${index}/consolelog:/var/log/supervisor\n"
            + "            - ./persistent/config:/test_config\n"
            + "            - ${projectRoot}/prebuild/${system}/${originalVersion}:/${system}/${originalVersion}\n"
            + "        environment:\n"
            + "            - CASSANDRA_CLUSTER_NAME=dev_cluster\n"
            + "            - CASSANDRA_SEEDS=${seedIP},\n"
            + "            - CASSANDRA_LOGGING_LEVEL=DEBUG\n"
            + "            - CQLSH_HOST=${networkIP}\n"
            + "            - CASSANDRA_LOG_DIR=/var/log/cassandra\n"
            + "        expose:\n"
            + "            - ${agentPort}\n"
            + "            - 7000\n"
            + "            - 7001\n"
            + "            - 7199\n"
            + "            - 9042\n"
            + "            - 9160\n"
            + "            - 18251\n"
            + "        ulimits:\n"
            + "            memlock: -1\n"
            + "            nproc: 32768\n"
            + "            nofile: 100000\n";

    static String template = ""
            + "    ${serviceName}:\n"
            + "        container_name: cassandra-${originalVersion}_${upgradedVersion}_${executorID}_N${index}\n"
            + "        image: upfuzz_${system}:${originalVersion}_${upgradedVersion}\n"
            + "        command: bash -c 'sleep 0 && /usr/bin/supervisord'\n"
            + "        networks:\n"
            + "            ${networkName}:\n"
            + "                ipv4_address: ${networkIP}\n"
            + "        volumes:\n"
            // + " - ./persistent/node_${index}/data:/var/lib/cassandra\n"
            + "            - ./persistent/node_${index}/log:/var/log/cassandra\n"
            + "            - ./persistent/node_${index}/env.sh:/usr/bin/set_env\n"
            + "            - ./persistent/node_${index}/consolelog:/var/log/supervisor\n"
            + "            - ./persistent/config:/test_config\n"
            + "            - ${projectRoot}/prebuild/${system}/${originalVersion}:/${system}/${originalVersion}\n"
            + "            - ${projectRoot}/prebuild/${system}/${upgradedVersion}:/${system}/${upgradedVersion}\n"
            + "        environment:\n"
            + "            - CASSANDRA_CLUSTER_NAME=dev_cluster\n"
            + "            - CASSANDRA_SEEDS=${seedIP},\n"
            + "            - CASSANDRA_LOGGING_LEVEL=DEBUG\n"
            + "            - CQLSH_HOST=${networkIP}\n"
            + "            - CASSANDRA_LOG_DIR=/var/log/cassandra\n"
            + "        expose:\n"
            + "            - ${agentPort}\n"
            + "            - 7000\n"
            + "            - 7001\n"
            + "            - 7199\n"
            + "            - 9042\n"
            + "            - 9160\n"
            + "            - 18251\n"
            + "        ulimits:\n"
            + "            memlock: -1\n"
            + "            nproc: 32768\n"
            + "            nofile: 100000\n";

    @Override
    public LogInfo grepLogInfo(Set<String> blackListErrorLog) {
        LogInfo logInfo = new LogInfo();
        Path filePath = Paths.get("/var/log/cassandra/system.log");
        constructLogInfo(logInfo, filePath, blackListErrorLog);
        return logInfo;
    }
}
