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
import org.zlab.upfuzz.docker.DockerMeta;
import org.zlab.upfuzz.fuzzingengine.Config;
import org.zlab.upfuzz.fuzzingengine.LogInfo;
import org.zlab.upfuzz.utils.Utilities;

public class CassandraDocker extends Docker {
    protected final Logger logger = LogManager.getLogger(getClass());

    String composeYaml;
    String javaToolOpts;
    int cqlshDaemonPort = 18250;
    public int direction;

    // Un-swapped versions for Docker image/container naming
    String configOriginalVersion;
    String configUpgradedVersion;

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

        // Store un-swapped config versions for image/container naming
        configOriginalVersion = Config.getConf().originalVersion;
        configUpgradedVersion = Config.getConf().upgradedVersion;

        collectFormatCoverage = dockerCluster.collectFormatCoverage;
        configPath = dockerCluster.configpath;
        if (Config.getConf().testSingleVersion)
            containerName = "cassandra-" + configOriginalVersion + "_"
                    + executorID + "_N" + index;
        else
            containerName = "cassandra-" + configOriginalVersion + "_"
                    + configUpgradedVersion + "_" + executorID + "_N"
                    + index;
    }

    @Override
    public String getNetworkIP() {
        return networkIP;
    }

    public String getNodeRole() {
        return "node" + index;
    }

    @Override
    public String formatComposeYaml() {
        Map<String, String> formatMap = new HashMap<>();

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
        formatMap.put("formatCoveragePort",
                Integer.toString(Config.instance.formatCoveragePort));
        formatMap.put("executorID", executorID);
        formatMap.put("serviceName", serviceName);
        String defaultImageName = Config.getConf().testSingleVersion
                ? "upfuzz_" + system + ":" + configOriginalVersion
                : "upfuzz_" + system + ":" + configOriginalVersion + "_"
                        + configUpgradedVersion;
        formatMap.put("imageName", composeImageName(defaultImageName));

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
            if (main_version >= 5) {
                // Cassandra 5.x classes are compiled with Java 17.
                jdkPath = "/usr/lib/jvm/java-17-openjdk-amd64";
            } else if (main_version >= 4) {
                // Cassandra 4.x classes are compiled with Java 11.
                jdkPath = "/usr/lib/jvm/java-11-openjdk-amd64";
            }
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
                "ENABLE_NET_COVERAGE=" + Config.getConf().useTrace,
                "ENABLE_NETWORK_TRACE=" + Config.getConf().useTrace,
                "NET_TRACE_NODE_ID=" + executorID + "-N" + index,
                "NET_TRACE_NODE_ROLE=" + getNodeRole()
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
                checkpointRestoreJavaOptionsSuffix() +
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

    // Phase 0/1 full-loop: benchmark application-native snapshot/rollback on a
    // throwaway keyspace, IN the running fuzzer (the only place the
    // instrumented
    // cluster is stable). Logs [NATIVE_SNAPSHOT] snapshot_ms / rollback_ms /
    // count_after (expect 100). Validates that R (rollback, no restart) <<
    // boot.
    // Cassandra 4.x nodetool needs Java 11, 5.x needs Java 11/17, but the
    // multi-version upfuzz image defaults JAVA_HOME to Java 8 (for 2.x/3.x).
    // nodetool therefore fails with UnsupportedClassVersionError unless we point
    // it at the right JVM. cqlsh is Python and is unaffected.
    private String javaHomeForCassandra(String v) {
        if (v.contains("cassandra-5.")) // 5.x NodeTool needs Java 17 (Java 11
                                        // gives LinkageError)
            return "/usr/lib/jvm/java-17-openjdk-amd64";
        if (v.contains("cassandra-4."))
            return "/usr/lib/jvm/java-11-openjdk-amd64";
        return ""; // 2.x/3.x: container default (Java 8) is correct
    }

    private Process nodetool(String javaHome, String ntPath, String argline)
            throws Exception {
        String cmd = (javaHome.isEmpty() ? "" : "JAVA_HOME=" + javaHome + " ")
                + ntPath + " " + argline;
        return runInContainer(new String[] { "/bin/sh", "-c", cmd });
    }

    // Extract the integer value from a `SELECT count(*)` cqlsh result (ignores
    // the trailing "(N rows)" line).
    private int parseCount(String out) {
        if (out == null)
            return -1;
        String s = out.replaceAll("\\(\\d+ rows?\\)", " ");
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("\\d+")
                .matcher(s);
        int last = -1;
        while (m.find()) {
            try {
                last = Integer.parseInt(m.group());
            } catch (NumberFormatException ignore) {
            }
        }
        return last;
    }

    // Phase 1 + Phase 2 validation of the warm-cluster native-snapshot loop:
    // boot once, snapshot a base ("parent") state, then for several children
    // diverge differently and roll back to the base WITHOUT a process restart
    // (TRUNCATE + `nodetool import --copy-data`, which restores correctly AND
    // leaves the snapshot intact for the next rollback). Measures rollback
    // latency per cycle and verifies each rollback restores exactly the base.
    public void nativeSnapshotRollbackBenchmark(DockerCluster cluster) {
        try {
            String nt = "/" + system + "/" + originalVersion + "/bin/nodetool";
            String jh = javaHomeForCassandra(originalVersion);
            String ks = "snapbench";
            int nodes = cluster.nodeNum;
            boolean is3x = originalVersion.contains("cassandra-2.")
                    || originalVersion.contains("cassandra-3.");
            shell.executeCommand("DROP KEYSPACE IF EXISTS " + ks + ";");
            shell.executeCommand("CREATE KEYSPACE " + ks
                    + " WITH replication={'class':'SimpleStrategy','replication_factor':1};");
            shell.executeCommand(
                    "CREATE TABLE " + ks + ".t (id int PRIMARY KEY, v text);");
            // Per-statement inserts: the cqlsh daemon executes one statement per
            // call (a batched multi-statement string is not reliably run).
            for (int i = 0; i < 100; i++)
                shell.executeCommand("INSERT INTO " + ks + ".t (id,v) VALUES ("
                        + i + ",'p" + i + "');");
            int baseCount = parseCount(
                    shell.executeCommand("SELECT count(*) FROM " + ks + ".t;"));

            // Snapshot on EVERY node: with RF<nodeNum the rows are token-split
            // across nodes, so the whole-cluster state lives on all nodes and
            // each must be snapshotted/rolled-back for a correct restore.
            long s0 = System.currentTimeMillis();
            String snap = "base" + System.currentTimeMillis();
            for (int n = 0; n < nodes; n++) {
                CassandraDocker d = (CassandraDocker) cluster.getDocker(n);
                d.nodetool(jh, nt, "flush " + ks).waitFor();
                d.nodetool(jh, nt, "snapshot -t " + snap + " " + ks).waitFor();
            }
            long s1 = System.currentTimeMillis();

            int cycles = 3;
            long[] rb = new long[cycles];
            int[] cnts = new int[cycles];
            boolean allCorrect = true;
            for (int c = 0; c < cycles; c++) {
                // each child diverges from the base differently
                int extra = 10 * (c + 1);
                for (int i = 100; i < 100 + extra; i++)
                    shell.executeCommand("INSERT INTO " + ks
                            + ".t (id,v) VALUES (" + i + ",'c" + i + "');");

                long r0 = System.currentTimeMillis();
                shell.executeCommand("TRUNCATE " + ks + ".t;");
                // Roll back on every node from its own snapshot (no restart).
                for (int n = 0; n < nodes; n++) {
                    CassandraDocker d = (CassandraDocker) cluster.getDocker(n);
                    if (is3x) {
                        d.runInContainer(new String[] { "/bin/sh", "-c",
                                "D=$(ls -d /var/lib/cassandra/data/" + ks
                                        + "/t-* 2>/dev/null | head -1); cp $D/snapshots/"
                                        + snap + "/*.db $D/ 2>/dev/null" })
                                .waitFor();
                        d.nodetool(jh, nt, "refresh " + ks + " t").waitFor();
                    } else {
                        d.runInContainer(new String[] { "/bin/sh", "-c",
                                "SD=$(ls -d /var/lib/cassandra/data/" + ks
                                        + "/t-*/snapshots/" + snap
                                        + " 2>/dev/null | head -1); "
                                        + (jh.isEmpty() ? "" : "JAVA_HOME=" + jh
                                                + " ")
                                        + nt + " import --copy-data " + ks
                                        + " t $SD" }).waitFor();
                    }
                }
                long r1 = System.currentTimeMillis();
                rb[c] = r1 - r0;
                cnts[c] = parseCount(shell
                        .executeCommand("SELECT count(*) FROM " + ks + ".t;"));
                if (cnts[c] != 100)
                    allCorrect = false;
            }
            shell.executeCommand("DROP KEYSPACE " + ks + ";");

            StringBuilder rbs = new StringBuilder();
            StringBuilder cs = new StringBuilder();
            long sum = 0;
            for (int c = 0; c < cycles; c++) {
                rbs.append(rb[c]);
                cs.append(cnts[c]);
                if (c < cycles - 1) {
                    rbs.append(",");
                    cs.append(",");
                }
                sum += rb[c];
            }
            logger.info(
                    "[NATIVE_SNAPSHOT] version={} base_count={} snapshot_ms={} cycles={} rollback_ms=[{}] avg_rollback_ms={} counts=[{}] all_correct={} (expect base=100, each rollback=100)",
                    originalVersion, baseCount, (s1 - s0), cycles, rbs.toString(),
                    (sum / cycles), cs.toString(), allCorrect);
        } catch (Exception e) {
            logger.warn("[NATIVE_SNAPSHOT] benchmark failed: {}", e.toString());
        }
    }

    // ---- Reusable native-snapshot warm-loop API (Phase 2/3 integration) ----
    // Extracted from the validated nativeSnapshotRollbackBenchmark. The warm
    // lifecycle calls snapshotKeyspaceAllNodes() once on a parent state, then
    // rollbackTableAllNodes() (R~5s, no restart, correct+repeatable) between
    // children. Snapshot/rollback run on EVERY node (RF<nodeNum token split) and
    // use the version-correct JAVA_HOME for nodetool (4.x=11, 5.x=17, 2/3.x=8).

    /** Flush + snapshot keyspace `ks` on every node; returns the snapshot tag. */
    public String snapshotKeyspaceAllNodes(DockerCluster cluster, String ks,
            String tag) throws Exception {
        String nt = "/" + system + "/" + originalVersion + "/bin/nodetool";
        String jh = javaHomeForCassandra(originalVersion);
        for (int n = 0; n < cluster.nodeNum; n++) {
            CassandraDocker d = (CassandraDocker) cluster.getDocker(n);
            d.nodetool(jh, nt, "flush " + ks).waitFor();
            d.nodetool(jh, nt, "snapshot -t " + tag + " " + ks).waitFor();
        }
        return tag;
    }

    /**
     * Roll back `ks.table` to snapshot `tag` on every node with no process
     * restart: TRUNCATE (cluster-wide via cqlsh) + `nodetool import --copy-data`
     * per node (copy-data leaves the snapshot intact for the next rollback).
     */
    public void rollbackTableAllNodes(DockerCluster cluster, String ks,
            String table, String tag) throws Exception {
        String nt = "/" + system + "/" + originalVersion + "/bin/nodetool";
        String jh = javaHomeForCassandra(originalVersion);
        boolean is3x = originalVersion.contains("cassandra-2.")
                || originalVersion.contains("cassandra-3.");
        shell.executeCommand("TRUNCATE " + ks + "." + table + ";");
        for (int n = 0; n < cluster.nodeNum; n++) {
            CassandraDocker d = (CassandraDocker) cluster.getDocker(n);
            if (is3x) {
                d.runInContainer(new String[] { "/bin/sh", "-c",
                        "D=$(ls -d /var/lib/cassandra/data/" + ks + "/" + table
                                + "-* 2>/dev/null | head -1); cp $D/snapshots/"
                                + tag + "/*.db $D/ 2>/dev/null" }).waitFor();
                d.nodetool(jh, nt, "refresh " + ks + " " + table).waitFor();
            } else {
                d.runInContainer(new String[] { "/bin/sh", "-c",
                        "SD=$(ls -d /var/lib/cassandra/data/" + ks + "/" + table
                                + "-*/snapshots/" + tag
                                + " 2>/dev/null | head -1); "
                                + (jh.isEmpty() ? "" : "JAVA_HOME=" + jh + " ")
                                + nt + " import --copy-data " + ks + " " + table
                                + " $SD" }).waitFor();
            }
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
                checkpointRestoreJavaOptionsSuffix() +
                "\"";
        cqlshDaemonPort ^= 1;

        handleEnv(upgradedVersion, cassandraHome, cassandraConf, false);
    }

    @Override
    public void prepareCheckpointReuseVersion(
            DockerMeta.DockerVersion dockerVersion) throws Exception {
        if (dockerVersion == DockerMeta.DockerVersion.upgraded) {
            prepareUpgradeEnv();
        }
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
                checkpointRestoreJavaOptionsSuffix() +
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
            + "        container_name: cassandra-${configOriginalVersion}_${executorID}_N${index}\n"
            + "        image: ${imageName}\n"
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
            + "        container_name: cassandra-${configOriginalVersion}_${configUpgradedVersion}_${executorID}_N${index}\n"
            + "        image: ${imageName}\n"
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
