package org.zlab.upfuzz.hdfs;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

import org.zlab.upfuzz.fuzzingengine.AgentServerSocket;
import org.zlab.upfuzz.fuzzingengine.Config;
import org.zlab.upfuzz.fuzzingengine.executor.Executor;
import org.zlab.upfuzz.fuzzingengine.testplan.event.command.ShellCommand;
import org.zlab.upfuzz.hdfs.HDFSShellDaemon.HdfsPacket;
import org.zlab.upfuzz.utils.Pair;
import org.zlab.upfuzz.utils.Utilities;

public class HdfsExecutor extends Executor {

    // static final String jacocoOptions =
    // "=append=false,includes=org.apache.hadoop.*,output=dfe,address=localhost,port=6300,sessionid=";

    HDFSShellDaemon hdfsShell = null;

    public HdfsExecutor() {
        super("hdfs", Config.getConf().nodeNum);

        timestamp = System.currentTimeMillis();

        agentStore = new HashMap<>();
        agentHandler = new HashMap<>();
        sessionGroup = new ConcurrentHashMap<>();

        // TODO: FIXME multiple init here for HBase
        dockerCluster = new HdfsDockerCluster(this,
                Config.getConf().originalVersion,
                nodeNum, collectFormatCoverage, configPath, direction);
    }

    public HdfsExecutor(int nodeNum, boolean collectFormatCoverage,
            Path configPath, int direction) {
        super("hdfs", nodeNum);

        timestamp = System.currentTimeMillis();

        this.collectFormatCoverage = collectFormatCoverage;
        this.configPath = configPath;
        this.direction = direction;

        agentStore = new HashMap<>();
        agentHandler = new HashMap<>();
        sessionGroup = new ConcurrentHashMap<>();
    }

    public boolean isHdfsReady(String hdfsPath) {
        ProcessBuilder isReadyBuilder = new ProcessBuilder();
        Process isReady;
        int ret = 0;
        try {
            isReady = Utilities.exec(
                    new String[] { "bin/hdfs", "dfsadmin", "-report" },
                    hdfsPath);
            BufferedReader in = new BufferedReader(
                    new InputStreamReader(isReady.getInputStream()));
            String line;
            while ((line = in.readLine()) != null) {
            }
            isReady.waitFor();
            in.close();
            ret = isReady.exitValue();
        } catch (IOException | InterruptedException e) {
            e.printStackTrace();
        }
        return ret == 0;
    }

    @Override
    public boolean startup() {
        try {
            if (agentSocket != null) {
                agentSocket.stopServer();
                agentSocket = null;
            }
            agentSocket = new AgentServerSocket(this);
            agentSocket.setDaemon(true);
            agentSocket.start();
            agentPort = agentSocket.getPort();
        } catch (Exception e) {
            logger.error(e);
            return false;
        }

        if (direction == 0) {
            dockerCluster = new HdfsDockerCluster(this,
                    Config.getConf().originalVersion,
                    nodeNum, collectFormatCoverage, configPath,
                    direction);
        } else {
            dockerCluster = new HdfsDockerCluster(this,
                    Config.getConf().upgradedVersion,
                    nodeNum, collectFormatCoverage, configPath,
                    direction);
        }

        try {
            dockerCluster.build();
        } catch (Exception e) {
            logger.error("docker cluster cannot build with exception: ", e);
            return false;
        }

        logger.info("[Old Version] HDFS Start...");

        try {
            int ret = dockerCluster.start();
            if (ret != 0) {
                logger.error("hdfs " + executorID + " failed to started");
                return false;
            }
        } catch (Exception e) {
            logger.error("docker cluster start up failed", e);
            return false;
        }

        logger.info("hdfs " + executorID + " started");
        return true;
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
        if (oriResult.size() != upResult.size()) {
            failureInfo.append("The result size is different\n");
            return new Pair<>(false, failureInfo.toString());
        } else {
            boolean ret = true;
            for (int i = 0; i < oriResult.size(); i++) {
                String str1 = oriResult.get(i);
                String str2 = upResult.get(i);
                // Mask timestamp
                if (Config.getConf().maskTimestamp) {
                    str1 = Utilities.maskTimeStampYYYYMMDD(
                            Utilities.maskTimeStampHHSS(str1));
                    str2 = Utilities.maskTimeStampYYYYMMDD(
                            Utilities.maskTimeStampHHSS(str2));
                }
                // Mask all spaces
                str1 = str1.replaceAll("\\s", "");
                str2 = str2.replaceAll("\\s", "");

                if (str1.compareTo(str2) != 0) {
                    String errorMsg = "Result inconsistency at read id: " + i
                            + "\n";
                    if (compareOldAndNew) {
                        errorMsg += "Old Version Result: "
                                + oriResult.get(i).strip()
                                + "\n"
                                + "New Version Result: "
                                + upResult.get(i).strip()
                                + "\n";
                    } else {
                        errorMsg += "Full Stop Result:\n"
                                + oriResult.get(i).strip()
                                + "\n"
                                + "Rolling Upgrade Result:\n"
                                + upResult.get(i).strip()
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
