package org.zlab.upfuzz.cassandra;

import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

import org.zlab.upfuzz.docker.DockerCluster;
import org.zlab.upfuzz.fuzzingengine.AgentServerSocket;
import org.zlab.upfuzz.fuzzingengine.Config;
import org.zlab.upfuzz.fuzzingengine.executor.Executor;
import org.zlab.upfuzz.fuzzingengine.server.FuzzingServer;
import org.zlab.upfuzz.utils.Pair;

public class CassandraExecutor extends Executor {
    static final String jacocoOptions = "=append=false";
    static final String classToIns = Config.getConf().instClassFilePath;
    static final String excludes = "org.apache.cassandra.metrics.*:org.apache.cassandra.net.*:org.apache.cassandra.io.sstable.format.SSTableReader.*:org.apache.cassandra.service.*";

    static final List<String> blackListReadInconsistencyKeyword = Arrays.asList(
            "SyntaxException", "InvalidRequest", "0 rows");

    public CassandraExecutor() {
        super("cassandra", Config.getConf().nodeNum);
        timestamp = System.currentTimeMillis();
        agentStore = new HashMap<>();
        agentHandler = new HashMap<>();
        sessionGroup = new ConcurrentHashMap<>();

        dockerCluster = new CassandraDockerCluster(
                this, Config.getConf().originalVersion,
                nodeNum, collectFormatCoverage, configPath,
                direction);
    }

    public CassandraExecutor(int nodeNum, boolean collectFormatCoverage,
            Path configPath, int direction) {
        super("cassandra", nodeNum);
        timestamp = System.currentTimeMillis();

        this.collectFormatCoverage = collectFormatCoverage;
        this.configPath = configPath;
        this.direction = direction;
        agentStore = new HashMap<>();
        agentHandler = new HashMap<>();
        sessionGroup = new ConcurrentHashMap<>();
    }

    @Override
    public boolean startup() {
        try {
            agentSocket = new AgentServerSocket(this);
            agentSocket.setDaemon(true);
            agentSocket.start();
            agentPort = agentSocket.getPort();
        } catch (Exception e) {
            logger.error(e);
            return false;
        }

        if (Config.getConf().debug) {
            logger.debug("[HKLOG] executor direction: " + direction);
        }
        if (direction == 0) {
            if (Config.getConf().debug) {
                logger.info("[HKLOG] Docker Cluster startup, original version: "
                        + Config.getConf().originalVersion);
            }
            dockerCluster = new CassandraDockerCluster(
                    this, Config.getConf().originalVersion,
                    nodeNum, collectFormatCoverage,
                    configPath, direction);
        } else {
            if (Config.getConf().debug) {
                logger.info("[HKLOG] Docker Cluster startup, upgraded version: "
                        + Config.getConf().upgradedVersion);
            }
            dockerCluster = new CassandraDockerCluster(
                    this, Config.getConf().upgradedVersion,
                    nodeNum, collectFormatCoverage,
                    configPath, direction);
        }

        try {
            dockerCluster.build();
        } catch (Exception e) {
            logger.error("docker cluster cannot build with exception: ", e);
            return false;
        }

        // May change classToIns according to the system...
        logger.info("Cassandra Start...");

        // What should we do if the docker cluster start up throws an exception?
        try {
            int ret = dockerCluster.start();
            if (ret != 0) {
                logger.error("cassandra " + executorID + " failed to started");
                return false;
            }
        } catch (Exception e) {
            logger.error("docker cluster start up failed", e);
            return false;
        }
        return true;
    }

    public static int findLiveNodeIndex(
            DockerCluster dockerCluster) {
        for (int i = 0; i < dockerCluster.nodeNum; i++) {
            if (dockerCluster.dockerStates[i].alive) {
                return i;
            }
        }
        return -1;
    }

    public Pair<Boolean, String> checkResultConsistency(List<String> oriResult,
            List<String> upResult, boolean compareOldAndNew) {
        // This could be override by each system to filter some false positive
        // Such as: the exception is the same, but the print format is different

        if (oriResult == null) {
            logger.error("original result are null!");
        }
        if (upResult == null) {
            logger.error("upgraded result are null!");
        }

        StringBuilder failureInfo = new StringBuilder("");
        assert oriResult != null;
        assert upResult != null;
        if (oriResult.size() != upResult.size()) {
            failureInfo.append("The result size is different\n");
            return new Pair<>(false, failureInfo.toString());
        } else {
            boolean ret = true;
            for (int i = 0; i < oriResult.size(); i++) {
                String ori = oriResult.get(i);
                String up = upResult.get(i);

                if (!compareOldAndNew
                        && FuzzingServer.EXAMPLE_ORACLE_SKIP_TOKEN
                                .equals(ori)) {
                    continue;
                }

                if (ori.compareTo(up) != 0) {
                    boolean isInBlackList = false;
                    for (String keyword : blackListReadInconsistencyKeyword) {
                        if (ori.contains(keyword) && up.contains(keyword)) {
                            isInBlackList = true;
                            break;
                        }
                    }
                    if (isInBlackList)
                        continue;

                    String errorMsg;
                    if (((ori.contains("InvalidRequest")) &&
                            !(up.contains("InvalidRequest")))
                            || ((up.contains("InvalidRequest")) &&
                                    !(ori
                                            .contains("InvalidRequest")))) {
                        errorMsg = "Insignificant Result inconsistency at read id: "
                                + i
                                + "\n";
                    } else {
                        errorMsg = "Result inconsistency at read id: " + i
                                + "\n";
                    }
                    if (compareOldAndNew) {
                        errorMsg += "Old Version Result: "
                                + ori.strip()
                                + "\n"
                                + "New Version Result: "
                                + up.strip()
                                + "\n";
                    } else {
                        errorMsg += "Baseline Result:\n"
                                + ori.strip()
                                + "\n"
                                + "Lane Result:\n"
                                + up.strip()
                                + "\n";
                    }
                    failureInfo.append(errorMsg);
                    ret = false;
                }
            }
            return new Pair<>(ret, failureInfo.toString());
        }
    }
}
