package org.zlab.upfuzz.ozone;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import org.zlab.upfuzz.fuzzingengine.AgentServerSocket;
import org.zlab.upfuzz.fuzzingengine.Config;
import org.zlab.upfuzz.fuzzingengine.executor.Executor;
import org.zlab.upfuzz.fuzzingengine.server.FuzzingServer;
import org.zlab.upfuzz.fuzzingengine.testplan.event.command.ShellCommand;
import org.zlab.upfuzz.ozone.OzoneShellDaemon.OzonePacket;
import org.zlab.upfuzz.utils.Pair;
import org.zlab.upfuzz.utils.Utilities;

public class OzoneExecutor extends Executor {
    OzoneShellDaemon ozoneShell = null;
    Logger logger = LogManager.getLogger(OzoneExecutor.class);

    public OzoneExecutor() {
        super("ozone", Config.getConf().nodeNum);

        timestamp = System.currentTimeMillis();

        agentStore = new HashMap<>();
        agentHandler = new HashMap<>();
        sessionGroup = new ConcurrentHashMap<>();

        // TODO: FIXME multiple init here for HBase
        dockerCluster = new OzoneDockerCluster(this,
                Config.getConf().originalVersion,
                nodeNum, collectFormatCoverage, configPath, direction);
    }

    public OzoneExecutor(int nodeNum, boolean collectFormatCoverage,
            Path configPath, int direction) {
        super("ozone", nodeNum);

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

        if (direction == 0) {
            dockerCluster = new OzoneDockerCluster(this,
                    Config.getConf().originalVersion,
                    nodeNum, collectFormatCoverage, configPath,
                    direction);
        } else {
            dockerCluster = new OzoneDockerCluster(this,
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

        logger.info("[Old Version] Ozone Start...");

        try {
            int ret = dockerCluster.start();
            if (ret != 0) {
                logger.error("ozone " + executorID + " failed to started");
                return false;
            }
        } catch (Exception e) {
            logger.error("docker cluster start up failed", e);
        }

        logger.info("ozone " + executorID + " started");
        return true;
    }

    public void setDefaultFs(String fsPath, int choice) throws Exception {
        // String modificationCommand = "sed -i
        // '/^FS_DEFAULTFS=/c\\FS_DEFAULTFS="
        // + fsPath + "'";
        // String modificationCommand = "sed -i
        // '/<name>fs.defaultFS<\\/name>/,/<\\/value>/{"
        // +
        // "s|<value>[^<]*</value>|<value>" + fsPath + "</value>|" +
        // "}'";

        String modificationCommand = "sed -i '/<\\/configuration>/i\\"
                + "<property>\\n"
                + "<name>fs.defaultFS<\\/name>\\n"
                + "<value>" + fsPath + "<\\/value>\\n"
                + "<\\/property>\\n'";

        for (int i = 0; i < Config.getConf().nodeNum; i++) {
            OzoneDocker docker = (OzoneDocker) dockerCluster.getDocker(i);
            String fileNameOri = "/etc/" + docker.originalVersion
                    + "/etc/hadoop/ozone-site.xml";
            String fileNameUpg = "/etc/" + docker.upgradedVersion
                    + "/etc/hadoop/ozone-site.xml";

            Process updateEnv;
            if (choice == 0) {
                updateEnv = docker.updateFileInContainer(fileNameOri,
                        modificationCommand);
            } else {
                updateEnv = docker.updateFileInContainer(fileNameUpg,
                        modificationCommand);
            }
            int ret = updateEnv.waitFor();
            Process setEnv = docker.runInContainer(new String[] {
                    "/bin/sh", "-c",
                    "source", "/usr/bin/set_env" });
            ret = setEnv.waitFor();
        }
    }

    static Set<String> exceptionSet = new HashSet<>();
    static {
        exceptionSet.add("BUCKET_NOT_FOUND");
        exceptionSet.add("OMException");
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
                if (!compareOldAndNew
                        && FuzzingServer.EXAMPLE_ORACLE_SKIP_TOKEN
                                .equals(oriResult.get(i))) {
                    continue;
                }
                // Mask timestamp
                if ((oriResult.equals("Old Version Result: [ ]"))
                        || (upResult.equals("Old Version Result: [ ]"))) {
                    if (Config.getConf().debug) {
                        logger.info(
                                "[HKLOG] obtained empty list in one version");
                    }
                }
                String str1 = Utilities.maskTimeStampYYYYMMDD(
                        Utilities.maskTimeStampHHSS(oriResult.get(i)));
                String str2 = Utilities.maskTimeStampYYYYMMDD(
                        Utilities.maskTimeStampHHSS(upResult.get(i)));
                // Mask all spaces
                str1 = str1.replaceAll("\\s", "");
                str2 = str2.replaceAll("\\s", "");

                if (str1.compareTo(str2) != 0) {
                    boolean isSameExceptionInDifferentFormat = false;
                    for (String exception : exceptionSet) {
                        if (str1.contains(exception) && str2
                                .contains(exception)) {
                            isSameExceptionInDifferentFormat = true;
                            break;
                        }
                    }
                    if (isSameExceptionInDifferentFormat) {
                        continue;
                    }
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
