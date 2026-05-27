package org.zlab.upfuzz.cassandra;

import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;

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

public class CassandraDockerCluster extends DockerCluster {
    static Logger logger = LogManager.getLogger(CassandraDockerCluster.class);

    String seedIP;

    static final String includes = "org.apache.cassandra.*";
    static final String excludes = "org.apache.cassandra.metrics.*:org.apache.cassandra.net.*:org.apache.cassandra.io.sstable.format.SSTableReader.*:org.apache.cassandra.service.*";

    CassandraDockerCluster(CassandraExecutor executor, String version,
            int nodeNum, boolean collectFormatCoverage,
            Path configPath,
            int direction) {
        super(executor, version, nodeNum, collectFormatCoverage,
                direction);

        this.dockers = new CassandraDocker[nodeNum];
        this.seedIP = DockerCluster.getKthIP(hostIP, 0);
        this.configpath = configPath;
        initBlackListErrorLog();
    }

    public void initBlackListErrorLog() {
        blackListErrorLog.add("Error response from daemon: Container");
        blackListErrorLog.add("Unable to gossip with any peers");
        blackListErrorLog.add(
                "java.io.IOError: java.nio.channels.AsynchronousCloseException");
        blackListErrorLog.add("LEAK DETECTED");
        blackListErrorLog.add(
                "org.apache.cassandra.db.composites.CompoundSparseCellNameType.create"
                        +
                        "(CompoundSparseCellNameType.java:126)" +
                        " ~[apache-cassandra-2.2.19-SNAPSHOT.jar:2.2.19-SNAPSHOT]");
        // "QueryProcessor.java:559 - The statement:": reported bug
        blackListErrorLog.add("QueryProcessor.java:559 - The statement:");
    }

    public boolean build() throws Exception {
        for (int i = 0; i < dockers.length; ++i) {
            if (Config.getConf().testSingleVersion) {
                logger.info(this.originalVersion);
            } else {
                logger.info(
                        this.originalVersion + " -> " + this.upgradedVersion);
            }
            dockers[i] = new CassandraDocker(this, i);
            dockers[i].build();
        }
        applyCheckpointReuseNodeVersions();
        configureCheckpointImageOverrides(
                executor.getCheckpointReuseImageOverrides());
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

        try {
            Process buildProcess = Utilities.exec(
                    new String[] { "docker", "compose", "down" }, workdir);
            buildProcess.waitFor();
            if (Config.getConf().debug) {
                logger.debug("teardown docker compose in " + workdir);
            }
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
        if (!Config.getConf().drain)
            return;
        for (Docker docker : dockers) {
            ((CassandraDocker) docker).drain();
        }
    }

    @Override
    public void finalizeUpgrade() {
        logger.debug("cassandra upgrade finalized");
    }
}
