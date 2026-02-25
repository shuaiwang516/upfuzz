package org.zlab.upfuzz.fuzzingengine;

import java.io.*;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.concurrent.*;

import org.apache.commons.io.FileUtils;
import org.apache.commons.io.IOUtils;
import org.apache.commons.lang3.RandomStringUtils;
import org.apache.commons.lang3.SerializationUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.jacoco.core.data.ExecutionDataStore;
import org.zlab.ocov.tracker.ObjectGraphCoverage;
import org.zlab.net.tracker.Trace;
import org.zlab.upfuzz.cassandra.CassandraExecutor;
import org.zlab.upfuzz.fuzzingengine.packet.*;
import org.zlab.upfuzz.fuzzingengine.packet.Packet.PacketType;
import org.zlab.upfuzz.fuzzingengine.executor.Executor;
import org.zlab.upfuzz.fuzzingengine.testplan.event.Event;
import org.zlab.upfuzz.fuzzingengine.testplan.event.fault.RestartFailure;
import org.zlab.upfuzz.fuzzingengine.testplan.event.upgradeop.UpgradeOp;
import org.zlab.upfuzz.hdfs.HdfsExecutor;
import org.zlab.upfuzz.hbase.HBaseExecutor;
import org.zlab.upfuzz.ozone.OzoneExecutor;
import org.zlab.upfuzz.utils.Pair;
import org.zlab.upfuzz.utils.Utilities;
import org.zlab.upfuzz.nyx.LibnyxInterface;

import static org.zlab.upfuzz.nyx.MiniClientMain.runTheTests;
import static org.zlab.upfuzz.nyx.MiniClientMain.setTestType;

public class FuzzingClient {
    static Logger logger = LogManager.getLogger(FuzzingClient.class);

    public Executor executor;
    public Path configDirPath;
    public Path configDirPathUp;
    public Path configDirPathDown;
    public int group;
    private LibnyxInterface libnyx = null;
    private LibnyxInterface libnyxSibling = null;
    public boolean isDowngradeSupported;
    int CLUSTER_START_RETRY = 3;

    // For skipping upgrade
    public static ObjectGraphCoverage oriObjCoverage;

    FuzzingClient() {
        if (Config.getConf().testSingleVersion) {
            configDirPath = Paths.get(
                    Config.getConf().configDir,
                    Config.getConf().originalVersion);
        } else {
            configDirPath = Paths.get(
                    Config.getConf().configDir, Config.getConf().originalVersion
                            + "_" + Config.getConf().upgradedVersion);
            if (Config.getConf().useVersionDelta) {
                configDirPathUp = Paths.get(
                        Config.getConf().configDir,
                        Config.getConf().originalVersion
                                + "_" + Config.getConf().upgradedVersion);
                configDirPathDown = Paths.get(
                        Config.getConf().configDir,
                        Config.getConf().upgradedVersion
                                + "_" + Config.getConf().originalVersion);
            }

            if (Config.getConf().useFormatCoverage
                    && Config.getConf().skipUpgrade) {
                // only init format coverage at this stage
                Path oriFormatInfoFolder = Paths.get("configInfo")
                        .resolve(Config.getConf().originalVersion);
                oriObjCoverage = new ObjectGraphCoverage(
                        oriFormatInfoFolder.resolve(
                                Config.getConf().baseClassInfoFileName),
                        oriFormatInfoFolder.resolve(
                                Config.getConf().topObjectsFileName),
                        oriFormatInfoFolder.resolve(
                                Config.getConf().comparableClassesFileName),
                        null, null, null, null, null);
            }
        }
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            executor.teardown();
        }));
        if (Config.getConf().nyxMode) {
            this.libnyx = createLibnyxInterface();
            if (Config.getConf().useVersionDelta) {
                this.libnyxSibling = createLibnyxInterface();
            }
        }
    }

    public LibnyxInterface createLibnyxInterface() {
        LibnyxInterface libnyx = new LibnyxInterface(
                Paths.get("/tmp", RandomStringUtils.randomAlphanumeric(8))
                        .toAbsolutePath().toString(),
                Paths.get("/tmp", RandomStringUtils.randomAlphanumeric(8))
                        .toAbsolutePath().toString(),
                0);
        try {
            FileUtils.copyFile(
                    Main.upfuzzConfigFilePath.toFile(),
                    Paths.get(libnyx.getSharedir(), "config.json")
                            .toFile(),
                    false);
        } catch (IOException e) {
            // e.printStackTrace();
            logger.info(
                    "[NyxMode] config.json unable to copy into sharedir");
            logger.info("[NyxMode] Disabling Nyx Mode");
            Config.getConf().nyxMode = false; // disable nyx
        }
        try {
            FileUtils.copyFile(
                    Paths.get("./", Config.getConf().nyxFuzzSH).toFile(), // fuzz_no_pt.sh
                                                                          // script
                                                                          // location
                    Paths.get(libnyx.getSharedir(), "fuzz_no_pt.sh")
                            .toFile(),
                    false);
        } catch (IOException e) {
            // e.printStackTrace();
            logger.info(
                    "[NyxMode] fuzz_no_pt.sh unable to copy into sharedir");
            logger.info("[NyxMode] Disabling Nyx Mode");
            Config.getConf().nyxMode = false; // disable nyx
        }
        try {
            FileUtils.copyFile(
                    Paths.get("./", "nyx_mode", "config.ron").toFile(),
                    Paths.get(libnyx.getSharedir(), "config.ron")
                            .toFile(),
                    false);
        } catch (IOException e) {
            // e.printStackTrace();
            logger.info(
                    "[NyxMode] config.ron unable to copy into sharedir");
            logger.info("[NyxMode] Disabling Nyx Mode");
            Config.getConf().nyxMode = false; // disable nyx
        }
        try {
            FileUtils.copyFile(
                    Paths.get("./", "nyx_mode", "packer", "packer",
                            "linux_x86_64-userspace", "bin64", "hget_no_pt")
                            .toFile(),
                    Paths.get(libnyx.getSharedir(), "hget_no_pt")
                            .toFile(),
                    false);
        } catch (IOException e) {
            // e.printStackTrace();
            logger.info(
                    "[NyxMode] hget_no_pt unable to copy into sharedir");
            logger.info("[NyxMode] Disabling Nyx Mode");
            Config.getConf().nyxMode = false; // disable nyx
        }
        try {
            FileUtils.copyFile(
                    Paths.get("./", "nyx_mode", "packer", "packer",
                            "linux_x86_64-userspace", "bin64", "hcat_no_pt")
                            .toFile(),
                    Paths.get(libnyx.getSharedir(), "hcat_no_pt")
                            .toFile(),
                    false);
        } catch (IOException e) {
            // e.printStackTrace();
            logger.info(
                    "[NyxMode] hget_no_pt unable to copy into sharedir");
            logger.info("[NyxMode] Disabling Nyx Mode");
            Config.getConf().nyxMode = false; // disable nyx
        }
        // Copy over C Agent and MiniClient.jar
        try {
            FileUtils.copyFile(
                    new File("build/libs/c_agent"),
                    Paths.get(libnyx.getSharedir(), "c_agent")
                            .toFile(),
                    false);
            FileUtils.copyFile(
                    new File(
                            "build/libs/MiniClient.jar"),
                    Paths.get(libnyx.getSharedir(), "MiniClient.jar")
                            .toFile(),
                    false);
        } catch (IOException e) {
            logger.info(
                    "[NyxMode] unable to copy agent or MiniClient.jar into sharedir");
            logger.info("[NyxMode] Disabling Nyx Mode");
            Config.getConf().nyxMode = false; // disable nyx
        }
        return libnyx;
    }

    public void start() throws InterruptedException {
        if (Config.getConf().debug) {
            logger.debug("Starting fuzzing client of group: " + group);
        }
        // Schedule GC here
        Executors.newSingleThreadScheduledExecutor().scheduleAtFixedRate(() -> {
            System.gc();
            if (Config.getConf().debug) {
                logger.debug("[GC] Client Garbage Collection invoked");
            }
        }, Config.getConf().gcInterval, Config.getConf().gcInterval,
                TimeUnit.MINUTES);
        Thread clientThread = new Thread(new FuzzingClientSocket(this, group));
        clientThread.start();
        clientThread.join();
    }

    public static Executor[] initExecutors(int nodeNum,
            boolean collectFormatCoverage,
            Path configPath, int executorNum, int[] directions) {
        assert directions.length == executorNum;

        String system = Config.getConf().system;
        Executor[] executors = new Executor[executorNum];
        for (int i = 0; i < executors.length; i++) {
            switch (system) {
            case "cassandra":
                executors[i] = new CassandraExecutor(nodeNum,
                        collectFormatCoverage,
                        configPath, directions[i]);
                break;
            case "hdfs":
                executors[i] = new HdfsExecutor(nodeNum,
                        collectFormatCoverage,
                        configPath, directions[i]);
                break;
            case "hbase":
                executors[i] = new HBaseExecutor(nodeNum,
                        collectFormatCoverage,
                        configPath, directions[i]);
                break;
            case "ozone":
                executors[i] = new OzoneExecutor(nodeNum,
                        collectFormatCoverage,
                        configPath, directions[i]);
                break;
            default:
                throw new RuntimeException(String.format(
                        "System %s is not supported yet, supported system: cassandra, hdfs, hbase",
                        Config.getConf().system));
            }
        }
        return executors;
    }

    public static Executor initExecutor(int nodeNum,
            boolean collectFormatCoverage,
            Path configPath) {
        String system = Config.getConf().system;
        switch (system) {
        case "cassandra":
            return new CassandraExecutor(nodeNum, collectFormatCoverage,
                    configPath, 0);
        case "hdfs":
            return new HdfsExecutor(nodeNum, collectFormatCoverage,
                    configPath, 0);
        case "hbase":
            return new HBaseExecutor(nodeNum, collectFormatCoverage,
                    configPath, 0);
        case "ozone":
            return new OzoneExecutor(nodeNum, collectFormatCoverage,
                    configPath, 0);
        }
        throw new RuntimeException(String.format(
                "System %s is not supported yet, supported system: cassandra, hdfs, hbase",
                Config.getConf().system));
    }

    public static Executor initExecutor(int nodeNum,
            boolean collectFormatCoverage,
            Path configPath, int testDirection) {
        String system = Config.getConf().system;
        switch (system) {
        case "cassandra":
            return new CassandraExecutor(nodeNum, collectFormatCoverage,
                    configPath, testDirection);
        case "hdfs":
            return new HdfsExecutor(nodeNum, collectFormatCoverage,
                    configPath, testDirection);
        case "hbase":
            return new HBaseExecutor(nodeNum, collectFormatCoverage,
                    configPath, testDirection);
        case "ozone":
            return new OzoneExecutor(nodeNum, collectFormatCoverage,
                    configPath, testDirection);
        }
        throw new RuntimeException(String.format(
                "System %s is not supported yet, supported system: cassandra, hdfs, hbase",
                Config.getConf().system));
    }

    public boolean startUpExecutor() {
        if (Config.getConf().debug) {
            logger.debug("[HKLOG] Fuzzing client: starting up executor");
        }
        for (int i = 0; i < CLUSTER_START_RETRY; i++) {
            try {
                if (executor.startup()) {
                    if (Config.getConf().debug) {
                        logger.info(
                                "[Fuzzing Client] started up executor after trial "
                                        + i);
                    }
                    return true;
                }
            } catch (Exception e) {
                logger.error("An error occurred", e);
            }
            executor.teardown();
        }
        logger.error("original version cluster cannot start up");
        return false;
    }

    public void tearDownExecutor() {
        executor.clearState();
        executor.teardown();
    }

    public StackedFeedbackPacket executeStackedTestPacket(
            StackedTestPacket stackedTestPacket) {
        if (Config.getConf().nyxMode) {
            return executeStackedTestPacketNyx(stackedTestPacket);
        } else {
            return executeStackedTestPacketRegular(stackedTestPacket);
        }
    }

    public Packet executeTestPlanPacket(
            TestPlanPacket testPlanPacket) {
        if (Config.getConf().nyxMode)
            return executeTestPlanPacketNyx(testPlanPacket);

        if (Config.getConf().differentialExecution)
            return executeTestPlanPacketDifferential(testPlanPacket);
        else
            return executeTestPlanPacketRegular(testPlanPacket);
    }

    private Path previousConfigPath = null;
    private boolean previousVerificationResult = false;

    // Helper move it into utils later
    private static boolean isSameConfig(Path configPath1, Path configPath2) {
        Path oriConfigPath1 = configPath1.resolve("oriconfig");
        assert oriConfigPath1.toFile().isDirectory();
        Path oriConfigPath2 = configPath2.resolve("oriconfig");
        assert oriConfigPath2.toFile().isDirectory();
        for (File file : Objects
                .requireNonNull(oriConfigPath1.toFile().listFiles())) {
            File file2 = oriConfigPath2.resolve(file.getName()).toFile();
            if (file2.exists()) {
                try (
                        InputStream s1 = new FileInputStream(file);
                        InputStream s2 = new FileInputStream(file2)) {
                    if (!IOUtils.contentEquals(s1, s2)) {
                        return false;
                    }
                } catch (IOException e) {
                    return false;
                }
            } else {
                return false; // mismatch in names, config is different
            }
        }

        if (!Config.getConf().testSingleVersion) {
            Path upConfigPath1 = configPath1.resolve("upconfig");
            assert upConfigPath1.toFile().isDirectory();
            Path upConfigPath2 = configPath2.resolve("upconfig");
            assert upConfigPath2.toFile().isDirectory();
            for (File file : Objects
                    .requireNonNull(upConfigPath1.toFile().listFiles())) {
                File file2 = upConfigPath2.resolve(file.getName()).toFile();
                if (file2.exists()) {
                    try (
                            InputStream s1 = new FileInputStream(file);
                            InputStream s2 = new FileInputStream(file2)) {
                        if (!IOUtils.contentEquals(s1, s2)) {
                            return false;
                        }
                    } catch (IOException e) {
                        return false;
                    }
                } else {
                    return false; // mismatch in names, config is different
                }
            }
        }

        return true;
    }

    public StackedFeedbackPacket executeStackedTestPacketNyx(
            StackedTestPacket stackedTestPacket) {
        Path configPath = Paths.get(configDirPath.toString(),
                stackedTestPacket.configFileName);
        logger.info("[HKLOG] configPath = " + configPath);

        // TODO write a compare method
        boolean sameConfigAsLastTime = false;
        if (this.previousConfigPath != null) {
            sameConfigAsLastTime = isSameConfig(this.previousConfigPath,
                    configPath);
        }
        if (this.previousConfigPath == null || !sameConfigAsLastTime) {
            // the miniClient will setup the distributed system according to the
            // defaultStackedTestPacket and the config
            Path defaultStackedTestPath = Paths.get(this.libnyx.getSharedir(),
                    "stackedTestPackets",
                    "defaultStackedPacket.ser");
            Path sharedConfigPath = Paths.get(this.libnyx.getSharedir(),
                    "archive.tar.gz");
            try {
                // Created sharedir/stackedTestPackets directory
                Paths.get(this.libnyx.getSharedir(), "stackedTestPackets")
                        .toFile().mkdir();
                Paths.get(this.libnyx.getSharedir(), "testPlanPackets")
                        .toFile().mkdir();
                // Copy the default stacked packet
                Utilities.writeObjectToFile(defaultStackedTestPath.toFile(),
                        stackedTestPacket);

                // Copy the config file to the sharedir
                // Zip the config into a zip file
                Process tar = Utilities.exec(
                        new String[] { "tar",
                                "-czf", "archive.tar.gz",
                                "./", },
                        configPath.toFile());
                tar.waitFor();

                System.out.println(configPath
                        .resolve("archive.tar.gz").toAbsolutePath());
                FileUtils.copyFile(
                        configPath.resolve("archive.tar.gz")
                                .toFile(),
                        sharedConfigPath.toFile(), true);

            } catch (IOException e) {
                e.printStackTrace();
                return null;
            } catch (InterruptedException e) {
                // zip failed
                e.printStackTrace();
                return null;
            }
        }
        if (this.previousConfigPath == null) {
            long startTime = System.currentTimeMillis();
            this.libnyx.nyxNew();
            if (Config.getConf().debug) {
                logger.info(
                        "[Fuzzing Client] First execution: Time needed to start up a new nyx vm "
                                + (System.currentTimeMillis() - startTime)
                                + " milliseconds");
            }
        } else if (!sameConfigAsLastTime) {
            long startTime = System.currentTimeMillis();
            this.libnyx.nyxShutdown();
            this.libnyx.nyxNew();
            if (Config.getConf().debug) {
                logger.info(
                        "[Fuzzing Client] New config: Time needed to shutdown old nyx vm and start a new nyx vm "
                                + (System.currentTimeMillis() - startTime)
                                + " seconds");
            }
        }
        this.previousConfigPath = configPath;

        // Now write the stackedTestPacket to be used for actual tests
        logger.info("[Fuzzing Client] Starting New Execution");
        long startTime3 = System.currentTimeMillis();
        String stackedTestFileLocation = "stackedTestPackets/"
                + RandomStringUtils.randomAlphanumeric(8) + ".ser";
        Path stackedTestPath = Paths.get(this.libnyx.getSharedir(),
                stackedTestFileLocation);
        if (Config.getConf().debug) {
            logger.info("[Fuzzing Client] time for getting stacked test path "
                    + (System.currentTimeMillis() - startTime3)
                    + " milliseconds");
        }

        long startTime4 = System.currentTimeMillis();
        try {

            Utilities.writeObjectToFile(stackedTestPath.toFile(),
                    stackedTestPacket);
        } catch (IOException e) {
            e.printStackTrace();
            return null;
        }
        if (Config.getConf().debug) {
            logger.info("[Fuzzing Client] time for writing test packet to file "
                    + (System.currentTimeMillis() - startTime4)
                    + " milliseconds");
        }

        // tell the nyx agent where to find the stackedTestPacket
        long startTime5 = System.currentTimeMillis();
        this.libnyx.setInput(stackedTestFileLocation); // set the test file
                                                       // location as input
        if (Config.getConf().debug) {
            logger.info("[Fuzzing Client] time for libnyx setInput function "
                    + (System.currentTimeMillis() - startTime5)
                    + " milliseconds");
        }

        setTestType(0);
        long startTime6 = System.currentTimeMillis();
        logger.info("[HKLOG] Now starting nyx execution");
        this.libnyx.nyxExec();

        if (Config.getConf().debug) {
            logger.info("[Fuzzing Client] time for NyxExec() function "
                    + (System.currentTimeMillis() - startTime6)
                    + " milliseconds");
            logger.info("[Fuzzing Client] Total time for Nyx-UpFuzz execution "
                    + (System.currentTimeMillis() - startTime3)
                    + " milliseconds");
        }

        // String storagePath = executor.dockerCluster.workdir.getAbsolutePath()
        // .toString();
        String archive_name = "";
        String directoryPath = Paths.get(this.libnyx.getWorkdir(),
                "dump").toAbsolutePath().toString();
        File directory = new File(directoryPath);

        // Check if the provided path is a directory
        if (directory.isDirectory()) {
            File[] files = directory.listFiles();

            if (files != null) {
                for (File file : files) {
                    if (file.isFile() && file.getName().endsWith(".tar.gz")) {
                        archive_name = file.getName();
                        break;
                    }
                }
            } else {
                logger.info(
                        "[HKLOG] Fuzzing Client: No files found in the directory.");
            }
        } else {
            logger.info(
                    "[HKLOG] Fuzzing Client: Provided path is not a directory.");
        }

        if (!archive_name.equals("")) {
            String storagePath = directoryPath + "/" + archive_name;
            String unzip_archive_command = "cd " + directoryPath + "/ ; "
                    + "tar -xzf " + archive_name + " ; "
                    + "cp persistent/stackedFeedbackPacket.ser " + directoryPath
                    + " ; "
                    + "cd - ;"
                    + "mv " + storagePath + " "
                    + Paths.get(this.libnyx.getSharedir())
                    + "/$(date +'%Y-%m-%d-%H-%M-%S')-" + archive_name + " ; ";

            try {
                long startTime2 = System.currentTimeMillis();
                ProcessBuilder builder = new ProcessBuilder();
                builder.command("/bin/bash", "-c", unzip_archive_command);
                // builder.directory(new File(System.getProperty("user.home")));
                builder.redirectErrorStream(true);

                Process process = builder.start();
                int exitCode = process.waitFor();
                if (Config.getConf().debug) {
                    logger.info(
                            "[Fuzzing Client] Time needed to unzip the fuzzing storage archive and moving it to the workdir: "
                                    + (System.currentTimeMillis() - startTime2)
                                    + " milliseconds");
                }
            } catch (Exception e) {
                e.printStackTrace();
            }
        }

        // get feedback file from hpush dir (in workdir)
        long startTimeFdbk = System.currentTimeMillis();
        Path stackedFeedbackPath = Paths.get(this.libnyx.getWorkdir(),
                "dump",
                "stackedFeedbackPacket.ser");

        // convert it to StackedFeedbackPacket
        StackedFeedbackPacket stackedFeedbackPacket;
        try (DataInputStream in = new DataInputStream(new FileInputStream(
                stackedFeedbackPath.toAbsolutePath().toString()))) {
            int intType = in.readInt();
            if (intType == -1) {
                logger.info("Executor startup error!");
                return null;
            }
            if (intType != PacketType.StackedFeedbackPacket.value) {
                logger.info("Incorrect packet type hit");
                return null;
            }
            stackedFeedbackPacket = StackedFeedbackPacket.read(in);
        } catch (IOException e) {
            e.printStackTrace();
            return null;
        }
        if (Config.getConf().debug) {
            logger.info(
                    "[Fuzzing Client] Time needed to read the feedback packet: "
                            + (System.currentTimeMillis() - startTimeFdbk)
                            + " milliseconds");
        }
        return stackedFeedbackPacket;
    }

    public VersionDeltaFeedbackPacketApproach2 executeStackedTestPacketVersionDelta(
            StackedTestPacket stackedTestPacket) {
        if (Config.getConf().nyxMode) {
            return executeStackedTestPacketNyxVersionDelta(
                    stackedTestPacket);
        }
        try {
            return executeStackedTestPacketRegularVersionDeltaApproach2(
                    stackedTestPacket);
        } catch (Exception e) {
            logger.error("An error occurred", e);
            for (StackTraceElement ste : e.getStackTrace()) {
                logger.error(ste.toString());
            }
            return null;
        }
    }

    public VersionDeltaFeedbackPacketApproach2 executeStackedTestPacketNyxVersionDelta(
            StackedTestPacket stackedTestPacket) {
        if (isDowngradeSupported) {
            return executeStackedTestPacketNyxVersionDeltaWithDowngrade(
                    stackedTestPacket);
        }
        if (group != 2) {
            // Group 1: 2 clusters
            return executeStackedTestPacketNyxVersionDeltaWithDowngrade(
                    stackedTestPacket);
        } else {
            // Group 2: 1 cluster if downgrade is not supported
            if (Config.getConf().enableNyxInGroup2) {
                return executeStackedTestPacketNyxVersionDeltaWithoutDowngrade(
                        stackedTestPacket);
            } else {
                return executeStackedTestPacketRegularVersionDeltaApproach2WithoutDowngrade(
                        stackedTestPacket);
            }
        }
    }

    public VersionDeltaFeedbackPacketApproach2 executeStackedTestPacketNyxVersionDeltaWithoutDowngrade(
            StackedTestPacket stackedTestPacket) {
        if (Config.getConf().debug) {
            logger.info("Version delta testing for group: "
                    + stackedTestPacket.clientGroupForVersionDelta);
            logger.info("This client is in group: " + group);
        }
        assert stackedTestPacket.clientGroupForVersionDelta == group;

        ExecutorService executorService = Executors.newFixedThreadPool(1);
        Path configPath = Paths.get(configDirPath.toString(),
                stackedTestPacket.configFileName);
        logger.info("[HKLOG] configPath = " + configPath);

        boolean sameConfigAsLastTime = false;
        if (this.previousConfigPath != null) {
            sameConfigAsLastTime = isSameConfig(this.previousConfigPath,
                    configPath);
        }
        String threadIdGroup = "group" + group + "_"
                + String.valueOf(Thread.currentThread().getId());
        long startTimeVersionDeltaExecution = System.currentTimeMillis();
        if (Config.getConf().debug) {
            logger.info("[HKLOG: profiler] " + threadIdGroup + ": group "
                    + group
                    + ": (nyx mode) started version delta execution");
        }

        StackedTestPacket stackedTestPacket1 = new StackedTestPacket(
                stackedTestPacket.nodeNum,
                stackedTestPacket.configFileName);
        for (TestPacket tp : stackedTestPacket.getTestPacketList()) {
            stackedTestPacket1.addTestPacket(tp);
        }

        stackedTestPacket1.clientGroupForVersionDelta = this.group;
        stackedTestPacket1.testDirection = 0;
        stackedTestPacket1.isDowngradeSupported = isDowngradeSupported;

        Future<StackedFeedbackPacket> futureStackedFeedbackPacketUp = executorService
                .submit(new NyxStackedTestThread(0,
                        stackedTestPacket1, this.libnyx, configPath,
                        previousConfigPath, sameConfigAsLastTime,
                        isDowngradeSupported));

        this.previousConfigPath = configPath;
        // Retrieve results for operation 1
        try {
            StackedFeedbackPacket stackedFeedbackPacketUp = futureStackedFeedbackPacketUp
                    .get();
            if (stackedFeedbackPacketUp == null) {
                executorService.shutdown();
                return null;
            }
            List<TestPacket> tpList = stackedTestPacket.getTestPacketList();
            VersionDeltaFeedbackPacketApproach2 versionDeltaFeedbackPacketApproach2 = new VersionDeltaFeedbackPacketApproach2(
                    stackedFeedbackPacketUp, null,
                    tpList);

            versionDeltaFeedbackPacketApproach2.clientGroup = group;
            executorService.shutdown();
            return versionDeltaFeedbackPacketApproach2;

        } catch (Exception e) {
            e.printStackTrace();
            return null;
        }
    }

    public VersionDeltaFeedbackPacketApproach2 executeStackedTestPacketNyxVersionDeltaWithDowngrade(
            StackedTestPacket stackedTestPacket) {
        if (Config.getConf().debug) {
            logger.info("Version delta testing for group: "
                    + stackedTestPacket.clientGroupForVersionDelta);
            logger.info("This client is in group: " + group);
        }
        assert stackedTestPacket.clientGroupForVersionDelta == group;

        ExecutorService executorService = Executors.newFixedThreadPool(2);
        Path configPath = Paths.get(configDirPath.toString(),
                stackedTestPacket.configFileName);
        logger.info("[HKLOG] configPath = " + configPath);

        boolean sameConfigAsLastTime = false;
        if (this.previousConfigPath != null) {
            sameConfigAsLastTime = isSameConfig(this.previousConfigPath,
                    configPath);
        }
        String threadIdGroup = "group" + group + "_"
                + String.valueOf(Thread.currentThread().getId());
        if (Config.getConf().debug) {
            logger.info("[HKLOG: profiler] " + threadIdGroup + ": group "
                    + group
                    + ": (nyx mode) started version delta execution");
        }

        StackedTestPacket stackedTestPacket1 = new StackedTestPacket(
                stackedTestPacket.nodeNum,
                stackedTestPacket.configFileName);
        StackedTestPacket stackedTestPacket2 = new StackedTestPacket(
                stackedTestPacket.nodeNum,
                stackedTestPacket.configFileName);
        for (TestPacket tp : stackedTestPacket.getTestPacketList()) {
            stackedTestPacket1.addTestPacket(tp);
            stackedTestPacket2.addTestPacket(tp);
        }

        stackedTestPacket1.clientGroupForVersionDelta = this.group;
        stackedTestPacket1.testDirection = 0;
        stackedTestPacket1.isDowngradeSupported = isDowngradeSupported;

        stackedTestPacket2.clientGroupForVersionDelta = this.group;
        stackedTestPacket2.testDirection = 1;
        stackedTestPacket2.isDowngradeSupported = isDowngradeSupported;

        Future<StackedFeedbackPacket> futureStackedFeedbackPacketUp = executorService
                .submit(new NyxStackedTestThread(0,
                        stackedTestPacket1, this.libnyx, configPath,
                        previousConfigPath, sameConfigAsLastTime,
                        isDowngradeSupported));
        Future<StackedFeedbackPacket> futureStackedFeedbackPacketDown = executorService
                .submit(new NyxStackedTestThread(1,
                        stackedTestPacket2, this.libnyxSibling,
                        configPath,
                        previousConfigPath, sameConfigAsLastTime,
                        isDowngradeSupported));

        this.previousConfigPath = configPath;
        // Retrieve results for operation 1
        try {
            StackedFeedbackPacket stackedFeedbackPacketUp = futureStackedFeedbackPacketUp
                    .get();
            StackedFeedbackPacket stackedFeedbackPacketDown = futureStackedFeedbackPacketDown
                    .get();
            if (stackedFeedbackPacketUp == null
                    || stackedFeedbackPacketDown == null) {
                executorService.shutdown();
                return null;
            }
            List<TestPacket> tpList = stackedTestPacket.getTestPacketList();
            VersionDeltaFeedbackPacketApproach2 versionDeltaFeedbackPacketApproach2 = new VersionDeltaFeedbackPacketApproach2(
                    stackedFeedbackPacketUp, stackedFeedbackPacketDown,
                    tpList);
            if (group == 1) {
                versionDeltaFeedbackPacketApproach2.stackedFeedbackPacketUpgrade.upgradeSkipped = true;
                versionDeltaFeedbackPacketApproach2.stackedFeedbackPacketDowngrade.upgradeSkipped = true;
            }
            versionDeltaFeedbackPacketApproach2.clientGroup = group;
            executorService.shutdown();
            return versionDeltaFeedbackPacketApproach2;
        } catch (Exception e) {
            e.printStackTrace();
            return null;
        }
    }

    public StackedFeedbackPacket executeStackedTestPacketRegular(
            StackedTestPacket stackedTestPacket) {
        Path configPath = Paths.get(configDirPath.toString(),
                stackedTestPacket.configFileName);
        logger.debug("[HKLOG] configPath = " + configPath);
        if (Config.getConf().verifyConfig && !verifyConfig(configPath)) {
            logger.error("Configuration problem: System cannot start up");
            return null;
        }
        executor = initExecutor(stackedTestPacket.nodeNum,
                Config.getConf().useFormatCoverage, configPath);

        if (!startUpExecutor())
            return null;

        if (Config.getConf().startUpClusterForDebugging) {
            logger.info("[Debugging Mode] Start up the cluster only");
            Utilities.sleepAndExit(36000);
        }

        StackedFeedbackPacket stackedFeedbackPacket = null;

        Callable<StackedFeedbackPacket> task = () -> runTheTests(executor,
                stackedTestPacket, oriObjCoverage);
        Future<StackedFeedbackPacket> future = Executors
                .newSingleThreadExecutor().submit(task);
        try {
            stackedFeedbackPacket = future.get(
                    Config.getConf().testExecutionTimeout, TimeUnit.MINUTES);
        } catch (TimeoutException e) {
            stackedFeedbackPacket = new StackedFeedbackPacket(
                    stackedTestPacket.configFileName,
                    Utilities.extractTestIDs(stackedTestPacket));
            stackedFeedbackPacket.testExecutionTimeout = true;
            logger.error("Test execution timed out after "
                    + Config.getConf().testExecutionTimeout + " minutes");
        } catch (Exception e) {
            logger.error("Test execution failed", e);
            stackedFeedbackPacket = new StackedFeedbackPacket(
                    stackedTestPacket.configFileName,
                    Utilities.extractTestIDs(stackedTestPacket));
            stackedFeedbackPacket.testExecutionFailedWithOtherException = true;
            stackedFeedbackPacket.testExecutionFailedWithOtherExceptionReport = e
                    .getMessage();
        }
        tearDownExecutor();
        return stackedFeedbackPacket;
    }

    /**
     * Given one test, start up 2 clusters, execute the test in both verisons, then
     * perform upgrade and downgrade.
     * After receiving the feedback, calculate the version delta information
     * and send this information back.
     */
    public VersionDeltaFeedbackPacketApproach1 executeStackedTestPacketVersionDeltaApproach1(
            StackedTestPacket stackedTestPacket) {
        ExecutorService executorService = Executors.newFixedThreadPool(2);
        Path configPath = Paths.get(configDirPath.toString(),
                stackedTestPacket.configFileName);
        logger.info("[HKLOG] configPath = " + configPath);

        // config verification
        if (Config.getConf().verifyConfig) {
            boolean validConfig = verifyConfig(configPath);
            if (!validConfig) {
                logger.error(
                        "problem with configuration! system cannot start up");
                return null;
            }
        }

        int[] directions = { 0, 1 };
        Executor[] executors = initExecutors(
                stackedTestPacket.nodeNum, Config.getConf().useFormatCoverage,
                configPath, 2, directions);

        // Submitting two Callable tasks
        // direction 0 means original --> upgraded
        // direction 1 means upgraded --> original
        Future<StackedFeedbackPacket> futureStackedFeedbackPacketUp = executorService
                .submit(new RegularStackedTestThread(executors[0], 0,
                        stackedTestPacket, isDowngradeSupported));
        Future<StackedFeedbackPacket> futureStackedFeedbackPacketDown = executorService
                .submit(new RegularStackedTestThread(executors[1], 1,
                        stackedTestPacket, isDowngradeSupported));

        // Retrieve and check the result of thread 1
        try {
            StackedFeedbackPacket stackedFeedbackPacketUp = futureStackedFeedbackPacketUp
                    .get();
            StackedFeedbackPacket stackedFeedbackPacketDown = futureStackedFeedbackPacketDown
                    .get();
            if (stackedFeedbackPacketUp == null
                    || stackedFeedbackPacketDown == null) {
                executorService.shutdown();
                return null;
            }
            VersionDeltaFeedbackPacketApproach1 versionDeltaFeedbackPacketApproach1 = new VersionDeltaFeedbackPacketApproach1(
                    stackedFeedbackPacketUp, stackedFeedbackPacketDown);
            executorService.shutdown();
            return versionDeltaFeedbackPacketApproach1;
        } catch (Exception e) {
            logger.info(
                    "[HKLOG] Exception in executeStackedTestPacketVersionDeltaApproach1");
            e.printStackTrace();
            executorService.shutdown();
            return null;
        }
    }

    public VersionDeltaFeedbackPacketApproach2 executeStackedTestPacketRegularVersionDeltaApproach2(
            StackedTestPacket stackedTestPacket) {
        if (isDowngradeSupported) {
            // if downgrade is supported, always start up 2 clusters
            return executeStackedTestPacketRegularVersionDeltaApproach2WithDowngrade(
                    stackedTestPacket);
        }
        if (group != 2) {
            // Group 1: 2 clusters
            return executeStackedTestPacketRegularVersionDeltaApproach2WithDowngrade(
                    stackedTestPacket);
        } else {
            // Group 2: 1 cluster if downgrade is not supported
            return executeStackedTestPacketRegularVersionDeltaApproach2WithoutDowngrade(
                    stackedTestPacket);
        }
    }

    /**
     * Only start up 1 thread (1 cluster). No downgrade direction.
     */
    public VersionDeltaFeedbackPacketApproach2 executeStackedTestPacketRegularVersionDeltaApproach2WithoutDowngrade(
            StackedTestPacket stackedTestPacket) {
        if (Config.getConf().debug) {
            logger.info("Version delta testing for group: "
                    + stackedTestPacket.clientGroupForVersionDelta);
            logger.info("This client is in group: " + group);
        }

        ExecutorService executorService = Executors.newFixedThreadPool(1);
        Path configPath = Paths.get(configDirPath.toString(),
                stackedTestPacket.configFileName);
        logger.info("[HKLOG] executing without downgrade configPath = "
                + configPath);

        // config verification
        if (Config.getConf().verifyConfig) {
            boolean validConfig = verifyConfig(configPath);
            if (!validConfig) {
                logger.error(
                        "problem with configuration! system cannot start up");
                return null;
            }
        }

        if (Config.getConf().debug) {
            logger.info("[Fuzzing Client] Call to initialize executor");
        }

        boolean collectFormatCoverage = false;
        // only collect format coverage in group1
        if (group == 1 && Config.getConf().useFormatCoverage) {
            collectFormatCoverage = true;
        }
        int[] directions = { 0, 1 };
        Executor[] executors = initExecutors(
                stackedTestPacket.nodeNum, collectFormatCoverage,
                configPath, 2, directions);

        String threadIdGroup = "group" + group + "_"
                + String.valueOf(Thread.currentThread().getId());
        long startTimeVersionDeltaExecution = System.currentTimeMillis();
        if (Config.getConf().debug) {
            logger.info("[HKLOG: profiler] " + threadIdGroup + ": group "
                    + group + ": started version delta execution");
        }

        logger.info("[HKLOG] Downgrade supported: " + isDowngradeSupported);
        // Submitting two Callable tasks
        StackedTestPacket stackedTestPacketUp = stackedTestPacket;
        Future<StackedFeedbackPacket> futureStackedFeedbackPacketUp = executorService
                .submit(new VersionDeltaStackedTestThread(executors[0], 0,
                        stackedTestPacketUp, isDowngradeSupported, group));

        try {
            StackedFeedbackPacket stackedFeedbackPacketUp = futureStackedFeedbackPacketUp
                    .get();

            // Process results for operations before version change
            if (stackedFeedbackPacketUp == null) {
                executorService.shutdown();
                return null;
            }
            List<TestPacket> tpList = stackedTestPacket.getTestPacketList();
            VersionDeltaFeedbackPacketApproach2 versionDeltaFeedbackPacketApproach2 = new VersionDeltaFeedbackPacketApproach2(
                    stackedFeedbackPacketUp, null, tpList);

            versionDeltaFeedbackPacketApproach2.clientGroup = group;
            executorService.shutdown();
            assert versionDeltaFeedbackPacketApproach2 != null;

            return versionDeltaFeedbackPacketApproach2;
        } catch (Exception e) {
            logger.info("[HKLOG] Caught Exception!!! " + e);
            e.printStackTrace();
            return null;
        }
    }

    /**
     * 2 threads (2 clusters) are started up
     */
    public VersionDeltaFeedbackPacketApproach2 executeStackedTestPacketRegularVersionDeltaApproach2WithDowngrade(
            StackedTestPacket stackedTestPacket) {

        if (Config.getConf().debug) {
            logger.info("Version delta testing for group: "
                    + stackedTestPacket.clientGroupForVersionDelta);
            logger.info("This client is in group: " + group);
        }

        ExecutorService executorService = Executors.newFixedThreadPool(2);
        Path configPath = Paths.get(configDirPath.toString(),
                stackedTestPacket.configFileName);
        logger.info("[HKLOG] configPath = " + configPath);

        // config verification
        if (Config.getConf().verifyConfig) {
            boolean validConfig = verifyConfig(configPath);
            if (!validConfig) {
                logger.error(
                        "problem with configuration! system cannot start up");
                return null;
            }
        }

        if (Config.getConf().debug) {
            logger.info("[Fuzzing Client] Call to initialize executor");
        }

        int[] directions = { 0, 1 };
        Executor[] executors = initExecutors(
                stackedTestPacket.nodeNum,
                group == 1 && Config.getConf().useFormatCoverage,
                configPath, 2, directions);

        String threadIdGroup = "group" + group + "_"
                + String.valueOf(Thread.currentThread().getId());
        if (Config.getConf().debug) {
            logger.info("[HKLOG: profiler] " + threadIdGroup + ": group "
                    + group + ": started version delta execution");
        }

        logger.info("[HKLOG] Downgrade supported: " + isDowngradeSupported);
        // Submitting two Callable tasks
        StackedTestPacket stackedTestPacketUp = stackedTestPacket;
        StackedTestPacket stackedTestPacketDown = stackedTestPacket;
        Future<StackedFeedbackPacket> futureStackedFeedbackPacketUp = executorService
                .submit(new VersionDeltaStackedTestThread(executors[0], 0,
                        stackedTestPacketUp, isDowngradeSupported, group));
        Future<StackedFeedbackPacket> futureStackedFeedbackPacketDown = executorService
                .submit(new VersionDeltaStackedTestThread(executors[1], 1,
                        stackedTestPacketDown, isDowngradeSupported, group));

        // Retrieve results for operation 1
        try {
            StackedFeedbackPacket stackedFeedbackPacketUp = futureStackedFeedbackPacketUp
                    .get();
            StackedFeedbackPacket stackedFeedbackPacketDown = futureStackedFeedbackPacketDown
                    .get();

            // Process results for operations before version change
            if (stackedFeedbackPacketUp == null
                    || stackedFeedbackPacketDown == null) {
                executorService.shutdown();
                return null;
            }
            List<TestPacket> tpList = stackedTestPacket.getTestPacketList();
            VersionDeltaFeedbackPacketApproach2 versionDeltaFeedbackPacketApproach2 = new VersionDeltaFeedbackPacketApproach2(
                    stackedFeedbackPacketUp, stackedFeedbackPacketDown, tpList);

            versionDeltaFeedbackPacketApproach2.clientGroup = group;
            executorService.shutdown();
            if (group == 1) {
                versionDeltaFeedbackPacketApproach2.stackedFeedbackPacketUpgrade.upgradeSkipped = true;
                versionDeltaFeedbackPacketApproach2.stackedFeedbackPacketDowngrade.upgradeSkipped = true;
            }
            return versionDeltaFeedbackPacketApproach2;
        } catch (Exception e) {
            logger.error("[HKLOG] " + e);
            for (StackTraceElement ste : e.getStackTrace()) {
                logger.error(ste.toString());
            }
            executorService.shutdown();
            return null;
        }
    }

    public TestPlanFeedbackPacket executeTestPlanPacketNyx(
            TestPlanPacket testPlanPacket) {

        logger.info("[Fuzzing Client] Invoked executeTestPlanPacket");
        if (Config.getConf().debug) {
            logger.debug("test plan: \n");
            logger.debug(testPlanPacket.getTestPlan());
        }

        Path configPath = Paths.get(configDirPath.toString(),
                testPlanPacket.configFileName);
        logger.info("[HKLOG] configPath = " + configPath);

        // TODO write a compare method
        boolean sameConfigAsLastTime = false;
        if (this.previousConfigPath != null) {
            sameConfigAsLastTime = isSameConfig(this.previousConfigPath,
                    configPath);
        }
        if (this.previousConfigPath == null || !sameConfigAsLastTime) {
            // the miniClient will setup the distributed system according to the
            // defaultStackedTestPacket and the config
            Path defaultStackedTestPath = Paths.get(this.libnyx.getSharedir(),
                    "stackedTestPackets",
                    "defaultStackedPacket.ser");
            Path defaultTestPlanPath = Paths.get(this.libnyx.getSharedir(),
                    "testPlanPackets",
                    "defaultTestPlanPacket.ser");
            Path sharedConfigPath = Paths.get(this.libnyx.getSharedir(),
                    "archive.tar.gz");
            try {
                // Created sharedir/stackedTestPackets directory
                Paths.get(this.libnyx.getSharedir(), "testPlanPackets")
                        .toFile().mkdir();
                Paths.get(this.libnyx.getSharedir(), "stackedTestPackets")
                        .toFile().mkdir();
                // Copy the default stacked packet
                Utilities.writeObjectToFile(defaultTestPlanPath.toFile(),
                        testPlanPacket);

                // Copy the config file to the sharedir
                // Zip the config into a zip file
                Process tar = Utilities.exec(
                        new String[] { "tar",
                                "-czf", "archive.tar.gz",
                                "./", },
                        configPath.toFile());
                tar.waitFor();

                System.out.println(configPath
                        .resolve("archive.tar.gz").toAbsolutePath().toString());
                FileUtils.copyFile(
                        configPath.resolve("archive.tar.gz")
                                .toFile(),
                        sharedConfigPath.toFile(), true);

            } catch (IOException e) {
                e.printStackTrace();
                return null;
            } catch (InterruptedException e) {
                // zip failed
                e.printStackTrace();
                return null;
            }
        }
        if (this.previousConfigPath == null) {
            long startTime = System.currentTimeMillis();
            this.libnyx.nyxNew();
            if (Config.getConf().debug) {
                logger.info(
                        "[Fuzzing Client] First execution: Time needed to start up a new nyx vm "
                                + (System.currentTimeMillis() - startTime)
                                + " milliseconds");
            }
        } else if (!sameConfigAsLastTime) {
            long startTime = System.currentTimeMillis();
            this.libnyx.nyxShutdown();
            this.libnyx.nyxNew();
            if (Config.getConf().debug) {
                logger.info(
                        "[Fuzzing Client] New config: Time needed to shutdown old nyx vm and start a new nyx vm "
                                + (System.currentTimeMillis() - startTime)
                                + " seconds");
            }
        }
        this.previousConfigPath = configPath;

        // Now write the stackedTestPacket to be used for actual tests
        if (Config.getConf().debug) {
            logger.info("[Fuzzing Client] Starting New Execution");
        }
        long startTime3 = System.currentTimeMillis();
        String testPlanFileLocation = "testPlanPackets/"
                + RandomStringUtils.randomAlphanumeric(8) + ".ser";
        Path testPlanPath = Paths.get(this.libnyx.getSharedir(),
                testPlanFileLocation);
        logger.info("[Fuzzing Client] time for getting stacked test path "
                + testPlanPath +
                +(System.currentTimeMillis() - startTime3)
                + " milliseconds");

        long startTime4 = System.currentTimeMillis();
        try {
            Utilities.writeObjectToFile(testPlanPath.toFile(),
                    testPlanPacket);
        } catch (IOException e) {
            e.printStackTrace();
            return null;
        }
        if (Config.getConf().debug) {
            logger.info("[Fuzzing Client] time for writing test packet to file "
                    + (System.currentTimeMillis() - startTime4)
                    + " milliseconds");
        }

        // tell the nyx agent where to find the stackedTestPacket
        long startTime5 = System.currentTimeMillis();
        this.libnyx.setInput(testPlanFileLocation); // set the test file
                                                    // location as input
        if (Config.getConf().debug) {
            logger.info("[Fuzzing Client] time for libnyx setInput function "
                    + (System.currentTimeMillis() - startTime5)
                    + " milliseconds");
        }

        setTestType(4);
        long startTime6 = System.currentTimeMillis();
        this.libnyx.nyxExec();

        /////////////////////////////////////////////////////////

        String archive_name = "";
        String directoryPath = Paths.get(this.libnyx.getWorkdir(),
                "dump").toAbsolutePath().toString();
        File directory = new File(directoryPath);

        // Check if the provided path is a directory
        if (directory.isDirectory()) {
            File[] files = directory.listFiles();

            if (files != null) {
                for (File file : files) {
                    if (file.isFile() && file.getName().endsWith(".tar.gz")) {
                        archive_name = file.getName();
                        break;
                    }
                }
            } else {
                logger.info(
                        "[HKLOG] Fuzzing Client: No files found in the directory.");
            }
        } else {
            logger.info(
                    "[HKLOG] Fuzzing Client: Provided path is not a directory.");
        }

        if (!archive_name.equals("")) {
            String storagePath = directoryPath + "/" + archive_name;
            String unzip_archive_command = "cd " + directoryPath + "/ ; "
                    + "tar -xzf " + archive_name + " ; "
                    + "cp persistent/testPlanFeedbackPacket.ser "
                    + directoryPath
                    + " ; "
                    + "cd - ;"
                    + "mv " + storagePath + " "
                    + Paths.get(this.libnyx.getSharedir())
                    + "/$(date +'%Y-%m-%d-%H-%M-%S')-" + archive_name + " ; ";

            try {
                long startTime2 = System.currentTimeMillis();
                ProcessBuilder builder = new ProcessBuilder();
                builder.command("/bin/bash", "-c", unzip_archive_command);
                // builder.directory(new File(System.getProperty("user.home")));
                builder.redirectErrorStream(true);

                Process process = builder.start();
                int exitCode = process.waitFor();
                if (Config.getConf().debug) {
                    logger.info(
                            "[Fuzzing Client] Time needed to unzip the fuzzing storage archive and moving it to the workdir: "
                                    + (System.currentTimeMillis() - startTime2)
                                    + " milliseconds");
                }
            } catch (Exception e) {
                e.printStackTrace();
            }
        }

        // get feedback file from hpush dir (in workdir)
        long startTimeFdbk = System.currentTimeMillis();
        Path testPlanFeedbackPath = Paths.get(this.libnyx.getWorkdir(),
                "dump",
                "testPlanFeedbackPacket.ser");

        // convert it to StackedFeedbackPacket
        TestPlanFeedbackPacket testPlanFeedbackPacket;
        try (DataInputStream in = new DataInputStream(new FileInputStream(
                testPlanFeedbackPath.toAbsolutePath().toString()))) {
            int intType = in.readInt();
            if (intType == -1) {
                logger.info("Executor startup error!");
                return null;
            }
            if (intType != PacketType.TestPlanFeedbackPacket.value) {
                logger.info("Incorrect packet type hit");
                return null;
            }
            testPlanFeedbackPacket = TestPlanFeedbackPacket.read(in);
        } catch (IOException e) {
            e.printStackTrace();
            return null;
        }
        if (Config.getConf().debug) {
            logger.info(
                    "[Fuzzing Client] Time needed to read the feedback packet: "
                            + (System.currentTimeMillis() - startTimeFdbk)
                            + " milliseconds");
        }
        return testPlanFeedbackPacket;
    }

    public TestPlanDiffFeedbackPacket executeTestPlanPacketDifferential(
            TestPlanPacket testPlanPacket) {
        logger.info("[Fuzzing Client] executeTestPlanPacketDifferential");
        if (Config.getConf().debug) {
            logger.debug("test plan: \n");
            logger.debug(testPlanPacket.getTestPlan());
        }

        Path configPath = Paths.get(configDirPath.toString(),
                testPlanPacket.configFileName);
        logger.info("[HKLOG] configPath = " + configPath);

        // config verification
        if (Config.getConf().verifyConfig) {
            boolean validConfig = verifyConfig(configPath);
            if (!validConfig) {
                logger.error(
                        "problem with configuration! system cannot start up");
                return null;
            }
        }

        ExecutorService executorService = Executors.newFixedThreadPool(3);
        int[] directions = { 0, 0, 1 };
        Executor[] executors = initExecutors(
                Config.getConf().nodeNum,
                Config.getConf().useFormatCoverage,
                configPath, 3, directions);

        // For only old exec or only new exec
        TestPlanPacket testPlanPacketWithoutUpgrade = replaceUpgradeEventWithRestart(
                testPlanPacket);

        Future<TestPlanFeedbackPacket> futureOnlyOld = executorService
                .submit(new RegularTestPlanThread(executors[0],
                        testPlanPacketWithoutUpgrade));
        Future<TestPlanFeedbackPacket> futureRolling = executorService
                .submit(new RegularTestPlanThread(executors[1],
                        testPlanPacket));
        Future<TestPlanFeedbackPacket> futureNew = executorService
                .submit(new RegularTestPlanThread(executors[2],
                        testPlanPacketWithoutUpgrade));

        long diffPacketTimeoutSec = Math.max(180L,
                (long) Config.getConf().CASSANDRA_RETRY_TIMEOUT * 2L);
        long deadlineMillis = System.currentTimeMillis()
                + TimeUnit.SECONDS.toMillis(diffPacketTimeoutSec);

        try {
            TestPlanFeedbackPacket[] testPlanFeedbackPackets = new TestPlanFeedbackPacket[3];
            testPlanFeedbackPackets[0] = waitDifferentialFeedback(
                    futureOnlyOld,
                    "OnlyOld",
                    testPlanPacket,
                    deadlineMillis);
            testPlanFeedbackPackets[1] = waitDifferentialFeedback(
                    futureRolling,
                    "Rolling",
                    testPlanPacket,
                    deadlineMillis);
            testPlanFeedbackPackets[2] = waitDifferentialFeedback(
                    futureNew,
                    "OnlyNew",
                    testPlanPacket,
                    deadlineMillis);

            logger.debug("[HKLOG] trace diff: all three packets are collected");
            return new TestPlanDiffFeedbackPacket(
                    testPlanPacket.systemID,
                    testPlanPacket.testPacketID,
                    testPlanFeedbackPackets);
        } finally {
            executorService.shutdownNow();
        }
    }

    private TestPlanFeedbackPacket waitDifferentialFeedback(
            Future<TestPlanFeedbackPacket> future,
            String lane,
            TestPlanPacket testPlanPacket,
            long deadlineMillis) {
        long remainingMillis = deadlineMillis - System.currentTimeMillis();
        if (remainingMillis <= 0L) {
            logger.error("[HKLOG] differential lane " + lane
                    + " exceeded packet timeout");
            future.cancel(true);
            return buildFallbackDiffFeedback(testPlanPacket, lane,
                    "packet timeout reached before feedback arrived");
        }

        try {
            TestPlanFeedbackPacket packet = future.get(remainingMillis,
                    TimeUnit.MILLISECONDS);
            if (packet == null) {
                logger.error("[HKLOG] differential lane " + lane
                        + " returned null feedback packet");
                return buildFallbackDiffFeedback(testPlanPacket, lane,
                        "feedback packet is null");
            }
            return packet;
        } catch (TimeoutException e) {
            logger.error("[HKLOG] differential lane " + lane
                    + " timed out while waiting for feedback");
            future.cancel(true);
            return buildFallbackDiffFeedback(testPlanPacket, lane,
                    "timeout while waiting for feedback");
        } catch (ExecutionException e) {
            Throwable realException = e.getCause();
            logger.error("[HKLOG] differential lane " + lane
                    + " failed with ExecutionException: " + realException);
            if (realException != null) {
                for (StackTraceElement ste : realException.getStackTrace()) {
                    logger.error(ste.toString());
                }
            }
            return buildFallbackDiffFeedback(testPlanPacket, lane,
                    "execution exception: " + realException);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            logger.error("[HKLOG] differential lane " + lane
                    + " interrupted while waiting for feedback");
            future.cancel(true);
            return buildFallbackDiffFeedback(testPlanPacket, lane,
                    "interrupted while waiting for feedback");
        } catch (Exception e) {
            logger.error("[HKLOG] differential lane " + lane
                    + " failed with exception: " + e);
            for (StackTraceElement ste : e.getStackTrace()) {
                logger.error(ste.toString());
            }
            return buildFallbackDiffFeedback(testPlanPacket, lane,
                    "exception while waiting for feedback: " + e);
        }
    }

    private TestPlanFeedbackPacket buildFallbackDiffFeedback(
            TestPlanPacket testPlanPacket,
            String lane,
            String reason) {
        int nodeNum = Math.max(1, Config.getConf().nodeNum);
        FeedBack[] feedBacks = new FeedBack[nodeNum];
        for (int i = 0; i < nodeNum; i++) {
            feedBacks[i] = new FeedBack();
        }

        TestPlanFeedbackPacket packet = new TestPlanFeedbackPacket(
                testPlanPacket.systemID,
                testPlanPacket.configFileName,
                testPlanPacket.testPacketID,
                feedBacks);
        packet.isEventFailed = true;
        packet.eventFailedReport = "[DIFF-FALLBACK][" + lane + "] " + reason;
        packet.fullSequence = recordTestPlanPacket(testPlanPacket);

        if (Config.getConf().useTrace) {
            packet.trace = new Trace[nodeNum];
            for (int i = 0; i < nodeNum; i++) {
                packet.trace[i] = new Trace();
            }
        }
        return packet;
    }

    public TestPlanFeedbackPacket executeTestPlanPacketRegular(
            TestPlanPacket testPlanPacket) {

        logger.info("[Fuzzing Client] executeTestPlanPacketRegular");
        if (Config.getConf().debug) {
            logger.debug("test plan: \n");
            logger.debug(testPlanPacket.getTestPlan());
        }

        Path configPath = Paths.get(configDirPath.toString(),
                testPlanPacket.configFileName);
        logger.info("[HKLOG] configPath = " + configPath);

        // config verification
        if (Config.getConf().verifyConfig) {
            boolean validConfig = verifyConfig(configPath);
            if (!validConfig) {
                logger.error(
                        "problem with configuration! system cannot start up");
                return null;
            }
        }

        ExecutorService executorService = Executors.newFixedThreadPool(1);
        int[] directions = { 0 };
        Executor[] executors = initExecutors(
                Config.getConf().nodeNum,
                Config.getConf().useFormatCoverage,
                configPath, 1, directions);

        Future<TestPlanFeedbackPacket> futureRolling = executorService
                .submit(new RegularTestPlanThread(executors[0],
                        testPlanPacket));
        try {
            return futureRolling
                    .get();
        } catch (ExecutionException e) {
            Throwable realException = e.getCause(); // get the original cause
            logger.error("[HKLOG] ExecutionException: "
                    + realException);
            for (StackTraceElement ste : realException.getStackTrace())
                logger.error(ste.toString());
            executorService.shutdown();
            return null;
        } catch (Exception e) {
            logger.error("[HKLOG] Exception when: " + e);
            for (StackTraceElement ste : e.getStackTrace())
                logger.error(ste.toString());
            executorService.shutdown();
            return null;
        }
    }

    public MixedFeedbackPacket executeMixedTestPacket(
            MixedTestPacket mixedTestPacket) {

        StackedTestPacket stackedTestPacket = mixedTestPacket.stackedTestPacket;
        TestPlanPacket testPlanPacket = mixedTestPacket.testPlanPacket;

        String testPlanPacketStr = recordTestPlanPacket(testPlanPacket);
        String mixedTestPacketStr = recordMixedTestPacket(mixedTestPacket);

        assert stackedTestPacket.nodeNum == testPlanPacket.getNodeNum();
        int nodeNum = stackedTestPacket.nodeNum;

        Path configPath = Paths.get(configDirPath.toString(),
                stackedTestPacket.configFileName);
        logger.info("[HKLOG] configPath = " + configPath);

        // config verification
        if (Config.getConf().verifyConfig) {
            boolean validConfig = verifyConfig(configPath);
            if (!validConfig) {
                logger.error(
                        "problem with configuration! system cannot start up");
                return null;
            }
        }

        // start up cluster
        executor = initExecutor(nodeNum, Config.getConf().useFormatCoverage,
                configPath);

        boolean startUpStatus = startUpExecutor();
        if (!startUpStatus) {
            return null;
        }

        // execute stacked packets
        Map<Integer, FeedbackPacket> testID2FeedbackPacket = new HashMap<>();
        Map<Integer, List<String>> testID2oriResults = new HashMap<>();
        Map<Integer, List<String>> testID2upResults = new HashMap<>();

        for (TestPacket tp : stackedTestPacket.getTestPacketList()) {
            logger.trace("Execute testpacket " + tp.systemID + " " +
                    tp.testPacketID);
            executor.executeCommands(tp.originalCommandSequenceList);

            FeedBack[] feedBacks = new FeedBack[stackedTestPacket.nodeNum];
            for (int i = 0; i < stackedTestPacket.nodeNum; i++) {
                feedBacks[i] = new FeedBack();
            }
            ExecutionDataStore[] oriCoverages = executor
                    .collectCoverageSeparate("original");
            if (oriCoverages != null) {
                for (int nodeIdx = 0; nodeIdx < stackedTestPacket.nodeNum; nodeIdx++) {
                    feedBacks[nodeIdx].originalCodeCoverage = oriCoverages[nodeIdx];
                }
            }
            testID2FeedbackPacket.put(
                    tp.testPacketID,
                    new FeedbackPacket(tp.systemID, stackedTestPacket.nodeNum,
                            tp.testPacketID, feedBacks, null));

            List<String> oriResult = executor
                    .executeCommands(tp.validationCommandSequenceList);
            testID2oriResults.put(tp.testPacketID, oriResult);
        }

        StackedFeedbackPacket stackedFeedbackPacket = new StackedFeedbackPacket(
                stackedTestPacket.configFileName,
                Utilities.extractTestIDs(stackedTestPacket));
        stackedFeedbackPacket.fullSequence = mixedTestPacketStr;

        // LOG checking1
        Map<Integer, LogInfo> logInfoBeforeUpgrade = null;
        if (Config.getConf().enableLogCheck) {
            logger.info("[HKLOG] error log checking");
            logInfoBeforeUpgrade = executor.grepLogInfo();
        }

        // execute test plan (rolling upgrade + fault)
        boolean status = executor.execute(testPlanPacket.getTestPlan());

        // collect test plan coverage
        FeedBack[] testPlanFeedBacks = new FeedBack[nodeNum];
        for (int i = 0; i < nodeNum; i++) {
            testPlanFeedBacks[i] = new FeedBack();
            if (executor.oriCoverage[i] != null)
                testPlanFeedBacks[i].originalCodeCoverage = executor.oriCoverage[i];
        }

        TestPlanFeedbackPacket testPlanFeedbackPacket = new TestPlanFeedbackPacket(
                testPlanPacket.systemID, stackedTestPacket.configFileName,
                testPlanPacket.testPacketID, testPlanFeedBacks);
        testPlanFeedbackPacket.fullSequence = mixedTestPacketStr;

        if (!status) {
            // one event in the test plan failed
            testPlanFeedbackPacket.isEventFailed = true;

            testPlanFeedbackPacket.eventFailedReport = genTestPlanFailureReport(
                    executor.eventIdx, executor.executorID,
                    stackedTestPacket.configFileName, testPlanPacketStr);
            testPlanFeedbackPacket.isInconsistent = false;
            testPlanFeedbackPacket.inconsistencyReport = "";
        } else {
            Pair<Boolean, String> compareRes;
            // read comparison between full-stop and rolling
            if (!testPlanPacket.getTestPlan().validationReadResultsOracle
                    .isEmpty()) {
                List<String> testPlanReadResults = executor
                        .executeCommands(
                                testPlanPacket
                                        .getTestPlan().validationCommands);
                compareRes = executor
                        .checkResultConsistency(
                                testPlanPacket
                                        .getTestPlan().validationReadResultsOracle,
                                testPlanReadResults, false);

                if (!compareRes.left) {
                    testPlanFeedbackPacket.isInconsistent = true;
                    testPlanFeedbackPacket.inconsistencyReport = genTestPlanInconsistencyReport(
                            executor.executorID,
                            stackedTestPacket.configFileName,
                            compareRes.right, testPlanPacketStr);
                }
            }

            // ----test plan upgrade coverage----
            ExecutionDataStore[] upCoverages = executor
                    .collectCoverageSeparate("upgraded");
            if (upCoverages != null) {
                for (int nodeIdx = 0; nodeIdx < nodeNum; nodeIdx++) {
                    testPlanFeedbackPacket.feedBacks[nodeIdx].upgradedCodeCoverage = upCoverages[nodeIdx];
                }
            }

            // ----stacked read upgrade coverage----
            for (TestPacket tp : stackedTestPacket.getTestPacketList()) {
                List<String> upResult = executor
                        .executeCommands(tp.validationCommandSequenceList);
                testID2upResults.put(tp.testPacketID, upResult);
                if (Config.getConf().collUpFeedBack) {
                    upCoverages = executor
                            .collectCoverageSeparate("upgraded");
                    if (upCoverages != null) {
                        for (int nodeIdx = 0; nodeIdx < stackedTestPacket.nodeNum; nodeIdx++) {
                            testID2FeedbackPacket.get(
                                    tp.testPacketID).feedBacks[nodeIdx].upgradedCodeCoverage = upCoverages[nodeIdx];
                        }
                    }
                }
            }
            // Check read results consistency
            for (TestPacket tp : stackedTestPacket.getTestPacketList()) {
                compareRes = executor
                        .checkResultConsistency(
                                testID2oriResults.get(tp.testPacketID),
                                testID2upResults.get(tp.testPacketID), true);

                FeedbackPacket feedbackPacket = testID2FeedbackPacket
                        .get(tp.testPacketID);

                if (!compareRes.left) {
                    String failureReport = genInconsistencyReport(
                            executor.executorID,
                            stackedTestPacket.configFileName,
                            compareRes.right, recordSingleTestPacket(tp));

                    // Create the feedback packet
                    feedbackPacket.isInconsistent = true;
                    feedbackPacket.inconsistencyReport = failureReport;
                } else {
                    feedbackPacket.isInconsistent = false;
                }
                feedbackPacket.validationReadResults = testID2upResults
                        .get(tp.testPacketID);
                stackedFeedbackPacket.addFeedbackPacket(feedbackPacket);
            }
        }

        // LOG checking2
        if (Config.getConf().enableLogCheck) {
            logger.info("[HKLOG] error log checking");
            assert logInfoBeforeUpgrade != null;
            Map<Integer, LogInfo> logInfo = extractErrorLog(executor,
                    logInfoBeforeUpgrade);
            if (hasERRORLOG(logInfo)) {
                stackedFeedbackPacket.hasERRORLog = true;
                stackedFeedbackPacket.errorLogReport = genErrorLogReport(
                        executor.executorID, stackedTestPacket.configFileName,
                        logInfo);
                testPlanFeedbackPacket.hasERRORLog = true;
                testPlanFeedbackPacket.errorLogReport = genErrorLogReport(
                        executor.executorID, stackedTestPacket.configFileName,
                        logInfo);
            }
        }

        tearDownExecutor();
        return new MixedFeedbackPacket(stackedFeedbackPacket,
                testPlanFeedbackPacket);
    }

    private boolean verifyConfig(Path configPath) {
        // start up one node in old version, verify old version config file
        // start up one node in new version, verify new version config file
        logger.info("verifying configuration");

        boolean sameConfigAsLastTime = false;
        if (this.previousConfigPath != null) {
            sameConfigAsLastTime = isSameConfig(this.previousConfigPath,
                    configPath);
        } else {
            sameConfigAsLastTime = false;
        }
        this.previousConfigPath = configPath;

        if (sameConfigAsLastTime) {
            return previousVerificationResult;
        } else {
            String system = Config.getConf().system;
            Executor executor;
            if (system.equals("hdfs")) {
                executor = initExecutor(4, false, configPath);
            } else if (system.equals("ozone")) {
                executor = initExecutor(4, false, configPath);
            } else if (system.equals("hbase")) {
                executor = initExecutor(3, false, configPath);
            } else {
                executor = initExecutor(1, false, configPath);
            }
            boolean startUpStatus = executor.startup();

            if (!startUpStatus) {
                logger.error("config cannot start up old version");
                executor.teardown();
                previousVerificationResult = false;
                return false;
            }
            startUpStatus = executor.freshStartNewVersion();
            executor.teardown();
            if (!startUpStatus) {
                previousVerificationResult = false;
                logger.error("config cannot start up new version");
            } else {
                previousVerificationResult = true;
            }
            return startUpStatus;
        }
    }

    public static String genTestPlanFailureReport(int failEventIdx,
            String executorID,
            String configFileName, String testPlanPacket) {
        return "[Test plan execution failed at event" + failEventIdx + "]\n" +
                "executionId = " + executorID + "\n" +
                "ConfigIdx = " + configFileName + "\n" +
                testPlanPacket + "\n";
    }

    public static String genInconsistencyReport(String executorID,
            String configFileName, String inconsistencyRecord,
            String singleTestPacket) {
        return "[Results inconsistency between two versions]\n" +
                "executionId = " + executorID + "\n" +
                "ConfigIdx = " + configFileName + "\n" +
                inconsistencyRecord + "\n" +
                singleTestPacket + "\n";
    }

    public static String genTestPlanInconsistencyReport(String executorID,
            String configFileName, String inconsistencyRecord,
            String singleTestPacket) {
        return "[Results inconsistency between full-stop and rolling upgrade]\n"
                +
                "executionId = " + executorID + "\n" +
                "ConfigIdx = " + configFileName + "\n" +
                inconsistencyRecord + "\n" +
                singleTestPacket + "\n";
    }

    public static String genUpgradeFailureReport(String executorID,
            String configFileName) {
        return "[Upgrade Failed]\n" +
                "executionId = " + executorID + "\n" +
                "ConfigIdx = " + configFileName + "\n";
    }

    public static String genDowngradeFailureReport(String executorID,
            String configFileName) {
        return "[Downgrade Failed]\n" +
                "executionId = " + executorID + "\n" +
                "ConfigIdx = " + configFileName + "\n";
    }

    public static String genOriCoverageCollFailureReport(String executorID,
            String configFileName, String singleTestPacket) {
        return "[Original Coverage Collect Failed]\n" +
                "executionId = " + executorID + "\n" +
                "ConfigIdx = " + configFileName + "\n" +
                singleTestPacket + "\n";
    }

    public static String genUpCoverageCollFailureReport(String executorID,
            String configFileName, String singleTestPacket) {
        return "[Upgrade Coverage Collect Failed]\n" +
                "executionId = " + executorID + "\n" +
                "ConfigIdx = " + configFileName + "\n" +
                singleTestPacket + "\n";
    }

    public static String genErrorLogReport(String executorID,
            String configFileName,
            Map<Integer, LogInfo> logInfo) {
        StringBuilder ret = new StringBuilder("[ERROR LOG]\n");
        ret.append("executionId = ").append(executorID).append("\n");
        ret.append("ConfigIdx = ").append(configFileName).append("\n");
        for (int i : logInfo.keySet()) {
            if (logInfo.get(i).ERRORMsg.size() > 0) {
                ret.append("Node").append(i).append("\n");
                for (String msg : logInfo.get(i).ERRORMsg) {
                    ret.append(msg).append("\n");
                }
                ret.append("\n");
            }
        }
        return ret.toString();
    }

    public static String recordStackedTestPacket(
            StackedTestPacket stackedTestPacket) {
        StringBuilder sb = new StringBuilder();
        for (TestPacket tp : stackedTestPacket.getTestPacketList()) {
            for (String cmdStr : tp.originalCommandSequenceList) {
                sb.append(cmdStr).append("\n");
            }
            sb.append("\n");
            for (String cmdStr : tp.validationCommandSequenceList) {
                sb.append(cmdStr).append("\n");
            }
            sb.append("\n\n");
        }
        return sb.toString();
    }

    public static String recordTestPlanPacket(TestPlanPacket testPlanPacket) {
        return String.format("nodeNum = %d\n", testPlanPacket.getNodeNum()) +
                testPlanPacket.getTestPlan().toString();
    }

    public static String recordMixedTestPacket(
            MixedTestPacket mixedTestPacket) {
        return recordTestPlanPacket(mixedTestPacket.testPlanPacket) +
                "\n" +
                recordStackedTestPacket(mixedTestPacket.stackedTestPacket);
    }

    public static String recordSingleTestPacket(TestPacket tp) {
        StringBuilder sb = new StringBuilder();
        sb.append("[Original Command Sequence]\n");
        for (String commandStr : tp.originalCommandSequenceList) {
            sb.append(commandStr).append("\n");
        }
        sb.append("\n\n");
        sb.append("[Read Command Sequence]\n");
        for (String commandStr : tp.validationCommandSequenceList) {
            sb.append(commandStr).append("\n");
        }
        return sb.toString();
    }

    public static Map<Integer, LogInfo> filterErrorLog(
            Map<Integer, LogInfo> logInfoBeforeUpgrade,
            Map<Integer, LogInfo> logInfoAfterUpgrade) {
        Map<Integer, LogInfo> filteredLogInfo = new HashMap<>();
        for (int nodeIdx : logInfoBeforeUpgrade.keySet()) {
            LogInfo beforeUpgradeLogInfo = logInfoBeforeUpgrade.get(nodeIdx);
            LogInfo afterUpgradeLogInfo = logInfoAfterUpgrade.get(nodeIdx);

            LogInfo logInfo = new LogInfo();
            for (String errorMsg : afterUpgradeLogInfo.ERRORMsg) {
                if (!beforeUpgradeLogInfo.ERRORMsg.contains(errorMsg)) {
                    logInfo.addErrorMsg(errorMsg);
                }
            }
            for (String warnMsg : afterUpgradeLogInfo.WARNMsg) {
                if (!beforeUpgradeLogInfo.WARNMsg.contains(warnMsg)) {
                    logInfo.addWARNMsg(warnMsg);
                }
            }
            filteredLogInfo.put(nodeIdx, logInfo);
        }
        return filteredLogInfo;
    }

    public static boolean hasERRORLOG(Map<Integer, LogInfo> logInfo) {
        boolean hasErrorLog = false;
        for (int i : logInfo.keySet()) {
            if (logInfo.get(i).ERRORMsg.size() > 0) {
                hasErrorLog = true;
                break;
            }
        }
        return hasErrorLog;
    }

    private Map<Integer, Map<String, Pair<String, String>>> stateCompare(
            Map<Integer, Map<String, String>> fullStopStates,
            Map<Integer, Map<String, String>> rollingStates) {
        // state value is encoded via Base64, decode is needed
        // how to compare?
        // - simple string comparison
        Map<Integer, Map<String, Pair<String, String>>> inconsistentStates = new HashMap<>();
        if (fullStopStates.keySet().size() != rollingStates.keySet().size()) {
            throw new RuntimeException(
                    "node num is different between full-stop upgrade" +
                            "and rolling upgrade");
        }
        for (int nodeId : fullStopStates.keySet()) {
            Map<String, String> fStates = fullStopStates.get(nodeId);
            Map<String, String> rStates = rollingStates.get(nodeId);
            for (String stateName : fStates.keySet()) {
                String fstateValue = Utilities
                        .decodeString(fStates.get(stateName));
                String rstateValue = Utilities
                        .decodeString(rStates.get(stateName));
                if (!fstateValue.equals(rstateValue)) {
                    if (!inconsistentStates.containsKey(nodeId))
                        inconsistentStates.put(nodeId, new HashMap<>());
                    inconsistentStates.get(nodeId).put(stateName,
                            new Pair<>(fstateValue, rstateValue));
                }
            }
        }
        return inconsistentStates;
    }

    public static Map<Integer, LogInfo> extractErrorLog(Executor executor,
            Map<Integer, LogInfo> logInfoBeforeUpgrade) {
        if (Config.getConf().filterLogBeforeUpgrade) {
            return FuzzingClient.filterErrorLog(
                    logInfoBeforeUpgrade,
                    executor.grepLogInfo());
        } else {
            return executor.grepLogInfo();
        }
    }

    public static TestPlanPacket replaceUpgradeEventWithRestart(
            TestPlanPacket testPlanPacket) {
        // Copy to create a new TestPlanPacket (not modifying the original one)
        TestPlanPacket updatedTestPlanPacket = SerializationUtils
                .clone(testPlanPacket);

        // Update events are enough

        List<Event> updatedEvents = new LinkedList<>();
        for (Event event : updatedTestPlanPacket.getTestPlan().events) {
            if (event instanceof UpgradeOp) {
                // Replace UpgradeOp with RestartOp
                RestartFailure restartOp = new RestartFailure(
                        ((UpgradeOp) event).nodeIndex);
                updatedEvents.add(restartOp);
            } else {
                updatedEvents.add(event);
            }
        }
        updatedTestPlanPacket.getTestPlan().events = updatedEvents;
        return updatedTestPlanPacket;
    }
}
