package org.zlab.upfuzz.fuzzingengine.server;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;

import org.apache.commons.io.FileUtils;
import org.apache.commons.lang3.SerializationUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.jacoco.core.data.ExecutionDataStore;
import org.zlab.net.tracker.Trace;
import org.zlab.net.tracker.TraceEntry;
import org.zlab.net.tracker.diff.DiffComputeEditDistance;
import org.zlab.net.tracker.diff.DiffComputeJaccardSimilarity;
import org.zlab.net.tracker.diff.DiffComputeMessageTriDiff;
import org.zlab.ocov.Utils;
import org.zlab.ocov.tracker.FormatCoverageStatus;
import org.zlab.ocov.tracker.ObjectGraphCoverage;
import org.zlab.ocov.tracker.Runtime;
import org.zlab.upfuzz.CommandPool;
import org.zlab.upfuzz.State;
import org.zlab.upfuzz.cassandra.CassandraCommandPool;
import org.zlab.upfuzz.cassandra.CassandraConfigGen;
import org.zlab.upfuzz.cassandra.CassandraExecutor;
import org.zlab.upfuzz.cassandra.CassandraState;
import org.zlab.upfuzz.fuzzingengine.Config;
import org.zlab.upfuzz.fuzzingengine.FeedBack;
import org.zlab.upfuzz.fuzzingengine.configgen.ConfigGen;
import org.zlab.upfuzz.fuzzingengine.packet.*;
import org.zlab.upfuzz.fuzzingengine.executor.Executor;
import org.zlab.upfuzz.fuzzingengine.server.testtracker.TestTrackerGraph;
import org.zlab.upfuzz.fuzzingengine.testplan.TestPlan;
import org.zlab.upfuzz.fuzzingengine.testplan.event.Event;
import org.zlab.upfuzz.fuzzingengine.testplan.event.command.ShellCommand;
import org.zlab.upfuzz.fuzzingengine.testplan.event.downgradeop.DowngradeOp;
import org.zlab.upfuzz.fuzzingengine.testplan.event.fault.*;
import org.zlab.upfuzz.fuzzingengine.testplan.event.upgradeop.FinalizeUpgrade;
import org.zlab.upfuzz.fuzzingengine.testplan.event.upgradeop.HDFSStopSNN;
import org.zlab.upfuzz.fuzzingengine.testplan.event.upgradeop.PrepareUpgrade;
import org.zlab.upfuzz.fuzzingengine.testplan.event.upgradeop.UpgradeOp;
import org.zlab.upfuzz.hbase.HBaseConfigGen;
import org.zlab.upfuzz.hdfs.HdfsCommandPool;
import org.zlab.upfuzz.hdfs.HdfsConfigGen;
import org.zlab.upfuzz.hdfs.HdfsExecutor;
import org.zlab.upfuzz.hdfs.HdfsState;
import org.zlab.upfuzz.ozone.OzoneCommandPool;
import org.zlab.upfuzz.ozone.OzoneState;
import org.zlab.upfuzz.ozone.OzoneConfigGen;
import org.zlab.upfuzz.ozone.OzoneExecutor;
import org.zlab.upfuzz.hbase.HBaseCommandPool;
import org.zlab.upfuzz.hbase.HBaseExecutor;
import org.zlab.upfuzz.hbase.HBaseState;
import org.zlab.upfuzz.utils.Pair;
import org.zlab.upfuzz.utils.Utilities;

import static org.zlab.upfuzz.utils.Utilities.rand;

public class FuzzingServer {
    static Logger logger = LogManager.getLogger(FuzzingServer.class);

    int fixedTestIndex = 0;

    // Target system
    public CommandPool commandPool;
    public Executor executor;
    public Class<? extends State> stateClass;

    // Corpus
    public Corpus corpus;

    public TestPlanCorpus testPlanCorpus = new TestPlanCorpus();
    public FullStopCorpus fullStopCorpus = new FullStopCorpus();

    /**
     * FIXME
     * These structure might keep the tests that are not finished yet.
     * However, if the server start up fails, we still want to figure out
     * a way to update their information
     * * Remove from memory
     * * Update the information in the disk (graph)
     */
    TestTrackerGraph graph = new TestTrackerGraph();
    private final Map<Integer, Seed> testID2Seed;
    private final Map<Integer, TestPlan> testID2TestPlan;

    // Next packet for execution
    public final Queue<StackedTestPacket> stackedTestPackets;
    private final Queue<TestPlanPacket> testPlanPackets;

    public int firstMutationSeedNum = 0;

    private int testID = 0;
    private int finishedTestID = 0;
    private int skippedUpgradeNum = 0;
    public static int round = 0;
    public static int failureId = 0;

    public static int fullStopCrashNum = 0;
    public static int eventCrashNum = 0;
    public static int inconsistencyNum = 0;
    public static int errorLogNum = 0;

    public static int testExecutionTimeoutNum = 0;
    public static int testExecutionFailedWithOtherExceptionNum = 0;

    private boolean isFullStopUpgrade = true;
    private int finishedTestIdAgentGroup1 = 0;
    private int finishedTestIdAgentGroup2 = 0;

    // Config mutation
    public ConfigGen configGen;
    public Path configDirPath;

    // ------------------- Format Coverage -------------------
    // Execute a test in old version
    private ObjectGraphCoverage oriObjCoverage;
    // Execute a test in new version
    private ObjectGraphCoverage upObjCoverage;

    private static final Path formatCoverageLogPath = Paths
            .get("format_coverage.log");

    private int newFormatCount = 0;
    private int nonMatchableNewFormatCount = 0;
    private int nonMatchableMultiInvCount = 0;

    // ------------------- Version Delta -------------------
    // Matchable Format (formats that exist in both versions)
    Map<String, Set<String>> modifiedFields;
    Map<String, Set<String>> modifiedSerializedFields;
    Map<String, Map<String, String>> matchableClassInfo;

    // Ablation: <IsSerialized>
    Set<String> changedClasses;

    // 2-group version delta (deprecated)
    public BlockingQueue<StackedTestPacket> stackedTestPacketsQueueVersionDelta;
    public InterestingTestsCorpus testBatchCorpus;

    // ------------------- Branch Coverage -------------------
    // before upgrade
    public static int oriCoveredBranches = 0;
    public static int oriProbeNum = 0;
    // after upgrade
    public static int upCoveredBranchesAfterUpgrade = 0;
    public static int upProbeNumAfterUpgrade = 0;
    // before downgrade
    public static int upCoveredBranches = 0;
    public static int upProbeNum = 0;
    // after downgrade
    public static int oriCoveredBranchesAfterDowngrade = 0;
    public static int oriProbeNumAfterDowngrade = 0;

    public static List<Pair<Integer, Integer>> oriBCAlongTime = new ArrayList<>();
    public static List<Pair<Integer, Integer>> upBCAlongTimeAfterUpgrade = new ArrayList<>();
    public static List<Pair<Integer, Integer>> upBCCoverageAlongTime = new ArrayList<>();
    public static List<Pair<Integer, Integer>> oriBCAlongTimeAfterDowngrade = new ArrayList<>();

    public static long lastTimePoint = 0;
    public long startTime;
    // Execute a test in old version
    ExecutionDataStore curOriCoverage;
    // Coverage after upgrade to new version
    ExecutionDataStore curUpCoverageAfterUpgrade;

    // Execute a test in new version
    ExecutionDataStore curUpCoverage;
    // Coverage after downgrade to old version
    ExecutionDataStore curOriCoverageAfterDowngrade;

    // Calculate cumulative probabilities
    double[] cumulativeTestChoiceProbabilities = new double[4];

    Set<Integer> mutatedSeedIds = new HashSet<>();
    Set<Integer> insignificantInconsistenciesIn = new HashSet<>();
    Map<Integer, Double> testChoiceProbabilities;

    List<Integer> branchVersionDeltaInducedTpIds = new ArrayList<>();
    List<Integer> formatVersionDeltaInducedTpIds = new ArrayList<>();
    List<Integer> onlyNewBranchCoverageInducedTpIds = new ArrayList<>();
    List<Integer> onlyNewFormatCoverageInducedTpIds = new ArrayList<>();

    List<Integer> nonInterestingTpIds = new ArrayList<>();

    public FuzzingServer() {
        if (Config.getConf().testSingleVersion) {
            configDirPath = Paths.get(
                    Config.getConf().configDir,
                    Config.getConf().originalVersion);
        } else {
            configDirPath = Paths.get(
                    Config.getConf().configDir, Config.getConf().originalVersion
                            + "_" + Config.getConf().upgradedVersion);
        }

        startTime = TimeUnit.SECONDS.convert(System.nanoTime(),
                TimeUnit.NANOSECONDS);

        if (Config.getConf().useVersionDelta) {
            corpus = new CorpusVersionDeltaFiveQueueWithBoundary();
        } else {
            if (Config.getConf().useFormatCoverage)
                corpus = new CorpusNonVersionDelta();
            else
                corpus = new CorpusDefault();
        }
        testID2Seed = new HashMap<>();
        testID2TestPlan = new HashMap<>();
        stackedTestPackets = new LinkedList<>();
        testPlanPackets = new LinkedList<>();
        curOriCoverage = new ExecutionDataStore();
        curUpCoverage = new ExecutionDataStore();
        curOriCoverageAfterDowngrade = new ExecutionDataStore();
        curUpCoverageAfterUpgrade = new ExecutionDataStore();

        // skip upgrade check

        assert !(Config.getConf().BC_skipUpgrade
                && Config.getConf().useFormatCoverage)
                : "BC_skipUpgrade should only be enabled when no format coverage";

        if (Config.getConf().useFormatCoverage) {
            // FIXME: add isSerialized path
            Path oriFormatInfoFolder = Paths.get("configInfo")
                    .resolve(Config.getConf().originalVersion);
            if (!oriFormatInfoFolder.toFile().exists()) {
                throw new RuntimeException(
                        "oriFormatInfoFolder is not specified in the configuration file "
                                +
                                "while format coverage is enabled");
            }

            oriObjCoverage = new ObjectGraphCoverage(
                    oriFormatInfoFolder.resolve(
                            Config.getConf().baseClassInfoFileName),
                    oriFormatInfoFolder.resolve(
                            Config.getConf().topObjectsFileName),
                    oriFormatInfoFolder.resolve(
                            Config.getConf().comparableClassesFileName),
                    null,
                    null,
                    null,
                    null,
                    null);
            if (Config.getConf().staticVD
                    || Config.getConf().prioritizeIsSerialized
                    || Config.getConf().useVersionDelta) {
                Path upFormatInfoFolder = Paths.get("configInfo")
                        .resolve(Config.getConf().upgradedVersion);

                assert Config.getConf().staticVD
                        ^ Config.getConf().prioritizeIsSerialized
                        : "Only one of staticVD and prioritizeIsSerialized can be true";

                if (Config.getConf().staticVD)
                    setStaticVD(oriFormatInfoFolder, upFormatInfoFolder);

                if (Config.getConf().useVersionDelta) {
                    if (!upFormatInfoFolder.toFile().exists()) {
                        throw new RuntimeException(
                                "upFormatInfoFolder is not specified in config");
                    }
                    upObjCoverage = new ObjectGraphCoverage(
                            upFormatInfoFolder.resolve(
                                    Config.getConf().baseClassInfoFileName),
                            upFormatInfoFolder.resolve(
                                    Config.getConf().topObjectsFileName),
                            upFormatInfoFolder.resolve(
                                    Config.getConf().comparableClassesFileName),
                            null,
                            null,
                            null,
                            null);
                    upObjCoverage.setMatchableClassInfo(matchableClassInfo);
                }
            }
            Runtime.initWriter(formatCoverageLogPath);
        }
    }

    private void setStaticVD(Path oriFormatInfoFolder,
            Path upFormatInfoFolder) {
        Path upgradeFormatInfoFolder = Paths.get("configInfo")
                .resolve(Config.getConf().originalVersion + "_"
                        + Config.getConf().upgradedVersion);
        assert upgradeFormatInfoFolder.toFile().exists();
        if (Config.getConf().srcVD) {
            Path modifiedFieldsPath = getModifiedFieldsPath(
                    upgradeFormatInfoFolder);
            this.modifiedFields = Utils
                    .loadModifiedFields(modifiedFieldsPath);
            Map<String, Map<String, String>> oriClassInfo = Utilities
                    .loadMapFromFile(
                            oriFormatInfoFolder.resolve(
                                    Config.getConf().baseClassInfoFileName));

            Map<String, Set<String>> oriClassInfoWithoutType = new HashMap<>();
            for (Map.Entry<String, Map<String, String>> entry : oriClassInfo
                    .entrySet()) {
                String className = entry.getKey();
                Map<String, String> fields = entry.getValue();
                Set<String> fieldNames = new HashSet<>(fields.keySet());
                oriClassInfoWithoutType.put(className, fieldNames);
            }
            this.modifiedSerializedFields = Utils.intersect(
                    modifiedFields, oriClassInfoWithoutType);

            matchableClassInfo = Utilities.computeMFUsingModifiedFields(
                    Objects.requireNonNull(oriClassInfo),
                    modifiedFields);
            logger.debug("[srcVD] Matchable class info count: "
                    + Utilities.count(matchableClassInfo));
            logger.debug("[srcVD] ori class info count: "
                    + Utilities.count(oriClassInfo));
            oriObjCoverage.setMatchableClassInfo(matchableClassInfo);
            changedClasses = Utilities.computeChangedClassesUsingModifiedFields(
                    Objects.requireNonNull(Utilities
                            .loadMapFromFile(
                                    oriFormatInfoFolder.resolve(
                                            Config.getConf().baseClassInfoFileName))),
                    modifiedFields);
            // logger.debug("<isSerialized> Changed classes: " +
            // changedClasses);
            oriObjCoverage.setChangedClasses(changedClasses);
        } else {
            if (!upFormatInfoFolder.toFile().exists()) {
                throw new RuntimeException(
                        "upFormatInfoFolder is not specified in config");
            }
            matchableClassInfo = Utilities.computeMF(
                    Objects.requireNonNull(Utilities
                            .loadMapFromFile(
                                    oriFormatInfoFolder.resolve(
                                            Config.getConf().baseClassInfoFileName))),
                    Objects.requireNonNull(Utilities
                            .loadMapFromFile(upFormatInfoFolder.resolve(
                                    Config.getConf().baseClassInfoFileName))));
            oriObjCoverage.setMatchableClassInfo(matchableClassInfo);
            changedClasses = Utilities.computeChangedClasses(
                    Objects.requireNonNull(Utilities
                            .loadMapFromFile(
                                    oriFormatInfoFolder.resolve(
                                            Config.getConf().baseClassInfoFileName))),
                    Objects.requireNonNull(Utilities
                            .loadMapFromFile(upFormatInfoFolder.resolve(
                                    Config.getConf().baseClassInfoFileName))));
            // logger.debug("<isSerialized> Changed classes: " +
            // changedClasses);
            oriObjCoverage.setChangedClasses(changedClasses);
        }
    }

    private static Path getModifiedFieldsPath(Path upgradeFormatInfoFolder) {
        String modFileName;
        switch (Config.getConf().vdType) {
        case all:
            modFileName = Config.getConf().modifiedFieldsFileName;
            break;
        case classNameMatch:
            modFileName = Config
                    .getConf().modifiedFieldsClassnameMustMatchFileName;
            break;
        case typeChange:
            modFileName = Config
                    .getConf().modifiedFieldsClassnameOnlyTypeChangeFileName;
            break;
        default:
            throw new RuntimeException("Unsupported vdType");
        }
        Path modifiedFieldsPath = upgradeFormatInfoFolder
                .resolve(modFileName);
        return modifiedFieldsPath;
    }

    private void init() {
        // Force GC every 10 minutes
        Executors.newSingleThreadScheduledExecutor().scheduleAtFixedRate(() -> {
            System.gc();
            if (Config.getConf().debug) {
                logger.debug("[GC] Server Garbage Collection invoked");
            }
        }, Config.getConf().gcInterval, Config.getConf().gcInterval,
                TimeUnit.MINUTES);

        if (Config.getConf().loadInitCorpus) {
            testID = corpus.initCorpus();
        }

        stackedTestPacketsQueueVersionDelta = new LinkedBlockingQueue<>();
        testBatchCorpus = new InterestingTestsCorpus();

        // maintain the num of configuration files
        // read all configurations file name in a list
        switch (Config.getConf().system) {
        case "cassandra":
            executor = new CassandraExecutor();
            commandPool = new CassandraCommandPool();
            stateClass = CassandraState.class;
            configGen = new CassandraConfigGen();
            break;
        case "hdfs":
            executor = new HdfsExecutor();
            commandPool = new HdfsCommandPool();
            stateClass = HdfsState.class;
            configGen = new HdfsConfigGen();
            break;
        case "hbase":
            executor = new HBaseExecutor();
            commandPool = new HBaseCommandPool();
            stateClass = HBaseState.class;
            configGen = new HBaseConfigGen();
            break;
        case "ozone":
            executor = new OzoneExecutor();
            commandPool = new OzoneCommandPool();
            stateClass = OzoneState.class;
            configGen = new OzoneConfigGen();
            break;
        default:
            throw new RuntimeException(
                    "System " + Config.getConf().system + " is not supported");
        }
        TestChoiceProbabilitiesVersionDeltaTwoGroups testChoiceProbabilitiesVersionDeltaTwoGroups = new TestChoiceProbabilitiesVersionDeltaTwoGroups();

        testChoiceProbabilities = testChoiceProbabilitiesVersionDeltaTwoGroups.probabilitiesHashMap;
        cumulativeTestChoiceProbabilities = testChoiceProbabilitiesVersionDeltaTwoGroups
                .getCumulativeProbabilities();
    }

    public void start() {
        init();
        new Thread(new FuzzingServerSocket(this)).start();
        // new Thread(new FuzzingServerDispatcher(this)).start();
    }

    public synchronized StackedTestPacket getOneBatch() {
        int randomIndex = rand.nextInt(testBatchCorpus.configFiles.size());
        String configFileName = testBatchCorpus
                .getConfigFileByIndex(randomIndex);
        while (testBatchCorpus.areAllQueuesEmptyForThisConfig(configFileName)) {
            logger.info("[HKLOG] no test with config file: " + configFileName);
            testBatchCorpus.configFiles.remove(configFileName);
            randomIndex = rand.nextInt(testBatchCorpus.configFiles.size());
            configFileName = testBatchCorpus.getConfigFileByIndex(randomIndex);
        }
        StackedTestPacket stackedTestPacket = new StackedTestPacket(
                Config.getConf().nodeNum, configFileName);
        logger.info("[HKLOG] non empty config file name: " + configFileName);
        for (int i = 0; i < Config.getConf().STACKED_TESTS_NUM_G2; i++) {
            if (!testBatchCorpus
                    .noInterestingTestsForThisConfig(configFileName)) {
                int testTypeInt = getSeedOrTestType(
                        cumulativeTestChoiceProbabilities);
                if (!testBatchCorpus.intermediateBuffer[testTypeInt]
                        .containsKey(configFileName)) {
                    testTypeInt = getNextBestTestType(testChoiceProbabilities,
                            configFileName);
                }
                TestPacket testPacket = null;
                if (testTypeInt != -1) {
                    testPacket = testBatchCorpus.getPacket(
                            InterestingTestsCorpus.TestType
                                    .values()[testTypeInt],
                            configFileName);
                }
                if (testPacket != null) {
                    stackedTestPacket.addTestPacket(testPacket);
                }
            } else {
                TestPacket testPacket = testBatchCorpus.getPacket(
                        InterestingTestsCorpus.TestType.LOW_PRIORITY,
                        configFileName);
                try {
                    if (testPacket != null) {
                        stackedTestPacket
                                .addTestPacket(testPacket);
                    }
                } catch (Exception e) {
                    logger.debug(
                            "Not enough test packets in the buffer yet for this config, trying with a smaller batch in this execution");
                }
            }
        }
        if (testBatchCorpus.areAllQueuesEmptyForThisConfig(configFileName)) {
            testBatchCorpus.configFiles.remove(configFileName);
        }
        stackedTestPacket.clientGroupForVersionDelta = 2;

        logger.info("[HKLOG] sending batch size to agent group 2: "
                + stackedTestPacket.getTestPacketList().size());
        return stackedTestPacket;
    }

    public synchronized Packet getOneTest() {
        if (Config.getConf().testingMode == 0) {
            if (stackedTestPackets.isEmpty()) {
                fuzzOne();
            }
            assert !stackedTestPackets.isEmpty();
            StackedTestPacket stackedTestPacket = stackedTestPackets.poll();
            if (Config.getConf().useVersionDelta
                    && (Config.getConf().versionDeltaApproach == 2)) {
                stackedTestPacket.clientGroupForVersionDelta = 1;
            }
            if (Config.getConf().skipUpgrade) {
                assert Config.getConf().STACKED_TESTS_NUM == 1;
                if (Config.getConf().useFormatCoverage) {
                    stackedTestPacket.formatCoverage = SerializationUtils.clone(
                            oriObjCoverage);
                    stackedTestPacket.branchCoverage = new ExecutionDataStore();
                    stackedTestPacket.branchCoverage.merge(curOriCoverage);
                }
                if (Config.getConf().BC_skipUpgrade) {
                    stackedTestPacket.branchCoverage = new ExecutionDataStore();
                    stackedTestPacket.branchCoverage.merge(curOriCoverage);
                }
            }

            // Debug: use the fixed command
            if (Config.getConf().useFixedCommand) {
                int suffixIdx = fixedTestIndex
                        % Config.getConf().fixedTestNum;

                logger.info(
                        "[Debug Usage] use fixed command index: " + suffixIdx);

                String suffix = String.format("%d", suffixIdx);
                if (suffixIdx == 0) {
                    suffix = "";
                }

                // Must be provided
                Path commandPath = Paths.get(System.getProperty("user.dir"),
                        "examplecase");
                assert commandPath.resolve("commands" + suffix + ".txt")
                        .toFile().exists()
                        && commandPath
                                .resolve("validcommands" + suffix + ".txt")
                                .toFile().exists()
                        : "Fixed command file does not exist";
                List<String> fixedWriteCommands = readCommands(
                        commandPath.resolve("commands" + suffix + ".txt"));
                List<String> fixedValidationCommands = readCommands(
                        commandPath.resolve("validcommands" + suffix + ".txt"));

                assert fixedWriteCommands != null
                        && fixedValidationCommands != null;

                if (stackedTestPacket.getTestPacketList().size() != 1) {
                    throw new RuntimeException(
                            "Fixed command should be used for only one test packet for debug usage");
                }

                for (TestPacket tp : stackedTestPacket.getTestPacketList()) {
                    logger.info(
                            "[Debug Usage] use fixed commands from examplecase/commands"
                                    + suffix + ".txt");
                    tp.originalCommandSequenceList = fixedWriteCommands;
                    tp.validationCommandSequenceList = fixedValidationCommands;
                }
                fixedTestIndex++;
            }
            return stackedTestPacket;
        } else if (Config.getConf().testingMode == 2) {
            return generateMixedTestPacket();
        } else if (Config.getConf().testingMode == 3) {
            logger.info("execute example test plan");
            return generateExampleTestplanPacket();
        } else if (Config.getConf().testingMode == 4) {
            // test full-stop and rolling upgrade iteratively
            Packet packet;
            if (isFullStopUpgrade
                    || (testPlanPackets.isEmpty() && !fuzzTestPlan())) {
                if (stackedTestPackets.isEmpty())
                    fuzzOne();
                assert !stackedTestPackets.isEmpty();
                packet = stackedTestPackets.poll();
                logger.debug("[getOneTest] for full-stop. isFullStopUpgrade = "
                        + isFullStopUpgrade);
            } else {
                assert !testPlanPackets.isEmpty();
                packet = testPlanPackets.poll();
                logger.debug("[getOneTest] for test plan. isFullStopUpgrade = "
                        + isFullStopUpgrade);
            }
            isFullStopUpgrade = !isFullStopUpgrade;
            return packet;
        } else if (Config.getConf().testingMode == 5) {
            // Only test rolling upgrade using test plans
            if (testPlanPackets.isEmpty()) {
                if (!fuzzTestPlan()) {
                    // No seeds in fullStopCorpus yet, generate example
                    return generateExampleTestplanPacket();
                }
            }
            assert !testPlanPackets.isEmpty();
            return testPlanPackets.poll();
        }
        throw new RuntimeException(
                String.format("testing Mode [%d] is not in correct scope",
                        Config.getConf().testingMode));
    }

    public MixedTestPacket generateMixedTestPacket() {
        StackedTestPacket stackedTestPacket;
        TestPlanPacket testPlanPacket;

        if (stackedTestPackets.isEmpty())
            fuzzOne();
        stackedTestPacket = stackedTestPackets.poll();

        while (testPlanPackets.isEmpty())
            fuzzTestPlan();
        testPlanPacket = testPlanPackets.poll();

        return new MixedTestPacket(stackedTestPacket, testPlanPacket);
    }

    public void generateRandomSeed() {
        if (Config.getConf().debug) {
            logger.debug("[fuzzOne] generate a random seed");
        }
        StackedTestPacket stackedTestPacket;

        int configIdx = configGen.generateConfig();
        String configFileName = "test" + configIdx;
        // corpus is empty, random generate one test packet and wait
        stackedTestPacket = new StackedTestPacket(
                Config.getConf().nodeNum,
                configFileName);
        if (Config.getConf().useVersionDelta
                && Config.getConf().versionDeltaApproach == 2) {
            stackedTestPacket.clientGroupForVersionDelta = 1;
        }

        if (Config.getConf().paddingStackedTestPackets) {
            for (int i = 0; i < Config.getConf().STACKED_TESTS_NUM; i++) {
                Seed seed = Seed.generateSeed(commandPool, stateClass,
                        configIdx, testID);
                if (seed != null) {
                    mutatedSeedIds.add(testID);
                    graph.addNode(-1, seed); // random generate seed
                    testID2Seed.put(testID, seed);
                    stackedTestPacket.addTestPacket(seed, testID++);
                }
            }
        } else {
            Seed seed = null;
            int maxGenLimit = 100;
            int genCount = 0;
            while (seed == null) {
                if (genCount >= maxGenLimit) {
                    throw new RuntimeException(
                            "Random seed generation out of limit: should generate "
                                    + maxGenLimit + ", not finished after "
                                    + genCount + " times");
                }
                seed = Seed.generateSeed(commandPool, stateClass,
                        configIdx, testID);
                genCount++;
            }
            mutatedSeedIds.add(testID);
            graph.addNode(-1, seed); // random generate seed
            testID2Seed.put(testID, seed);
            stackedTestPacket.addTestPacket(seed, testID++);
        }
        if (stackedTestPacket.size() == 0) {
            throw new RuntimeException(
                    "Fuzzing Server failed to generate and tests");
        }
        assert stackedTestPacket.size() != 0;
        stackedTestPackets.add(stackedTestPacket);
    }

    /**
     *  Get a seed from corpus, now fuzz it for an epoch
     *  The seed contains a specific configuration to trigger new coverage
     *  1. Fix the config, mutate command sequences
     *      a. Mutate command sequences
     *      b. Random generate new command sequences
     *  2. Fix the command sequence
     *      a. Mutate the configs (not supported yet)
     *      b. Random generate new configs
     *  3. Mutate both config and command sequence (dramatic mutation, disabled currently)
     */
    public void fuzzOne() {
        // Pick one test case from the corpus, fuzz it for mutationEpoch
        // Add the new tests into the stackedTestPackets
        // All packets have been dispatched, now fuzz next seed

        Seed seed = null;
        if (rand.nextDouble() < Config.getConf().getSeedFromCorpusRatio)
            seed = corpus.getSeed();

        if (seed == null) {
            generateRandomSeed();
            return;
        }

        round++;
        StackedTestPacket stackedTestPacket;

        // Compute mutation depth
        int mutationDepth = seed.mutationDepth;
        int configIdx;

        mutatedSeedIds.add(seed.testID);
        if (Config.getConf().debug) {
            logger.debug(
                    "[fuzzOne] fuzz a seed from corpus, stackedTestPackets size = "
                            + stackedTestPackets.size());
        }

        // 1.a Fix config, mutate command sequences
        if (seed.configIdx == -1)
            configIdx = configGen.generateConfig();
        else
            configIdx = seed.configIdx;

        int mutationEpoch;
        int randGenEpoch;
        if (firstMutationSeedNum < Config
                .getConf().firstMutationSeedLimit) {
            mutationEpoch = Config.getConf().firstSequenceMutationEpoch;
            randGenEpoch = Config.getConf().firstSequenceRandGenEpoch;
        } else {
            mutationEpoch = Config.getConf().sequenceMutationEpoch;
            randGenEpoch = Config.getConf().sequenceRandGenEpoch;
        }

        if (Config.getConf().debug) {
            logger.debug(String.format(
                    "mutationEpoch = %s, firstMutationSeedNum = %s",
                    mutationEpoch, firstMutationSeedNum));
        }
        String configFileName = "test" + configIdx;
        stackedTestPacket = new StackedTestPacket(Config.getConf().nodeNum,
                configFileName);
        if (Config.getConf().useVersionDelta
                && Config.getConf().versionDeltaApproach == 2) {
            stackedTestPacket.clientGroupForVersionDelta = 1;
        }

        // Avoid infinite mutation problem: if the mutation keeps failing,
        // need to jump out of this loop
        int maxMutationLimit = 3 * mutationEpoch;
        int mutationCount = 0;
        int mutationFailCount = 0;
        for (int i = 0; i < mutationEpoch; i++) {
            if (mutationCount >= maxMutationLimit) {
                logger.debug(
                        "Mutation out of limit for seed " + seed.testID
                                + ": should mutate " + mutationEpoch
                                + ", not finished after "
                                + mutationCount + " times");
                break;
            }
            if (mutationFailCount >= Config.getConf().mutationFailLimit) {
                logger.debug(
                        "Mutation fail out of limit for seed " + seed.testID
                                + ": should mutate " + mutationEpoch
                                + ", mutation failed "
                                + mutationFailCount + " times");
                break;
            }
            if (i != 0 && i % Config.getConf().STACKED_TESTS_NUM == 0) {
                stackedTestPackets.add(stackedTestPacket);
                stackedTestPacket = new StackedTestPacket(
                        Config.getConf().nodeNum,
                        configFileName);
                if (Config.getConf().useVersionDelta
                        && Config.getConf().versionDeltaApproach == 2) {
                    stackedTestPacket.clientGroupForVersionDelta = 1;
                }
            }
            Seed mutateSeed = SerializationUtils.clone(seed);
            if (mutateSeed.mutate(commandPool, stateClass)) {
                mutateSeed.testID = testID; // update testID after mutation
                mutateSeed.mutationDepth = mutationDepth;
                graph.addNode(seed.testID, mutateSeed);
                testID2Seed.put(testID, mutateSeed);
                stackedTestPacket.addTestPacket(mutateSeed, testID++);
            } else {
                logger.debug("Mutation failed");
                i--;
                mutationFailCount++;
            }
            mutationCount++;
        }
        // last test packet
        if (stackedTestPacket.size() != 0) {
            stackedTestPackets.add(stackedTestPacket);
        }

        // 1.b Fix config, random generate new command sequences
        if (Config.getConf().enableRandomGenUsingSameConfig
                && configGen.enable) {
            stackedTestPacket = new StackedTestPacket(Config.getConf().nodeNum,
                    configFileName);
            for (int i = 0; i < randGenEpoch; i++) {
                if (i != 0 && i % Config.getConf().STACKED_TESTS_NUM == 0) {
                    stackedTestPackets.add(stackedTestPacket);
                    stackedTestPacket = new StackedTestPacket(
                            Config.getConf().nodeNum, configFileName);
                }
                Seed randGenSeed = Seed.generateSeed(commandPool,
                        stateClass,
                        configIdx, testID);
                if (randGenSeed != null) {
                    // This should be 0 since it's randomly generated
                    // randGenSeed.mutationDepth = mutationDepth;
                    graph.addNode(seed.testID, randGenSeed);
                    testID2Seed.put(testID, randGenSeed);
                    stackedTestPacket.addTestPacket(randGenSeed, testID++);
                } else {
                    logger.debug("Random seed generation failed");
                    i--;
                }
            }
            // last test packet
            if (stackedTestPacket.size() != 0) {
                stackedTestPackets.add(stackedTestPacket);
            }
        }

        if (configGen.enable) {
            int configMutationEpoch;
            if (firstMutationSeedNum < Config
                    .getConf().firstMutationSeedLimit)
                configMutationEpoch = Config
                        .getConf().firstConfigMutationEpoch;
            else
                configMutationEpoch = Config.getConf().configMutationEpoch;

            for (int configMutationIdx = 0; configMutationIdx < configMutationEpoch; configMutationIdx++) {
                configIdx = configGen.generateConfig();
                configFileName = "test" + configIdx;
                stackedTestPacket = new StackedTestPacket(
                        Config.getConf().nodeNum,
                        configFileName);
                if (Config.getConf().useVersionDelta
                        && Config.getConf().versionDeltaApproach == 2) {
                    stackedTestPacket.clientGroupForVersionDelta = 1;
                }
                // put the seed into it
                Seed mutateSeed = SerializationUtils.clone(seed);
                mutateSeed.configIdx = configIdx;
                mutateSeed.testID = testID; // update testID after mutation
                mutateSeed.mutationDepth = mutationDepth;
                graph.addNode(seed.testID, mutateSeed);
                testID2Seed.put(testID, mutateSeed);

                // We shouldn't add more tests for this batch, since it's only
                // testing the configuration mutation, this batch would be 1.
                // If we add more tests, actually we already think that this
                // config is interesting, however, we shouldn't do that.
                stackedTestPacket.addTestPacket(mutateSeed, testID++);
                // add mutated seeds (Mutate sequence&config)
                if (Config.getConf().paddingStackedTestPackets) {
                    for (int i = 1; i < Config
                            .getConf().STACKED_TESTS_NUM; i++) {
                        mutateSeed = SerializationUtils.clone(seed);
                        mutateSeed.configIdx = configIdx;
                        if (mutateSeed.mutate(commandPool, stateClass)) {
                            mutateSeed.testID = testID;
                            mutateSeed.mutationDepth = mutationDepth;
                            graph.addNode(seed.testID, mutateSeed);
                            testID2Seed.put(testID, mutateSeed);
                            stackedTestPacket.addTestPacket(mutateSeed,
                                    testID++);
                        } else {
                            logger.debug("Mutation failed");
                            i--;
                        }
                    }
                }
                stackedTestPackets.add(stackedTestPacket);
            }
        }
        firstMutationSeedNum++;

        if (Config.getConf().debug) {
            logger.debug("[fuzzOne] mutate done, stackedTestPackets size = "
                    + stackedTestPackets.size());
        }

        if (stackedTestPackets.isEmpty()) {
            logger.error(
                    "No test packets generated, the mutation likely fails, now random generate one");
            generateRandomSeed();
        }
    }

    private boolean fuzzTestPlan() {
        int MAX_MUTATION_RETRY = 50;
        TestPlan testPlan = testPlanCorpus.getTestPlan();

        if (testPlan == null) {
            // Randomly generate a new test plan
            FullStopSeed fullStopSeed = fullStopCorpus.getSeed();
            if (fullStopSeed == null) {
                // return false, cannot fuzz test plan
                // TODO: Completely generate a new test plan?
                return false;
            } else {
                // Generate a test plan from the full-stop seed
                for (int i = 0; i < Config
                        .getConf().testPlanGenerationNum; i++) {
                    for (int j = 0; j < MAX_MUTATION_RETRY; j++) {
                        testPlan = generateTestPlan(fullStopSeed);
                        if (testPlan != null) {
                            break;
                        }
                    }
                    if (testPlan == null)
                        return false;

                    testID2TestPlan.put(testID, testPlan);
                    int configIdx = configGen.generateConfig();
                    String configFileName = "test" + configIdx;

                    testPlanPackets.add(new TestPlanPacket(
                            Config.getConf().system,
                            testID++, configFileName, testPlan));
                }
                return true;
            }
        }

        // Mutate an existing test plan
        for (int i = 0; i < Config.getConf().testPlanMutationEpoch; i++) {
            TestPlan mutateTestPlan = null;
            int j = 0;
            for (; j < Config.getConf().testPlanMutationRetry; j++) {
                mutateTestPlan = SerializationUtils.clone(testPlan);
                mutateTestPlan.mutate(commandPool, stateClass);
                if (testPlanVerifier(mutateTestPlan.getEvents(),
                        testPlan.nodeNum)) {
                    break;
                }
            }
            // Always failed mutating this test plan
            if (j == Config.getConf().testPlanMutationRetry)
                return false;
            testID2TestPlan.put(testID, mutateTestPlan);

            int configIdx = configGen.generateConfig();
            String configFileName = "test" + configIdx;

            testPlanPackets.add(new TestPlanPacket(
                    Config.getConf().system,
                    testID++, configFileName, mutateTestPlan));
        }
        return true;
    }

    public FeedBack mergeCoverage(FeedBack[] feedBacks) {
        FeedBack fb = new FeedBack();
        if (feedBacks == null) {
            return fb;
        }
        for (FeedBack feedBack : feedBacks) {
            if (feedBack.originalCodeCoverage != null)
                fb.originalCodeCoverage.merge(feedBack.originalCodeCoverage);
            if (feedBack.upgradedCodeCoverage != null)
                fb.upgradedCodeCoverage.merge(feedBack.upgradedCodeCoverage);
        }
        return fb;
    }

    public Packet generateExampleTestplanPacket() {
        // Modify configID for debugging
        int configIdx = configGen.generateConfig();
        String configFileName = "test" + configIdx;

        TestPlan testPlan = generateExampleTestPlan();
        testID2TestPlan.put(testID, testPlan);
        return new TestPlanPacket(
                Config.getConf().system, testID++, configFileName,
                testPlan);
    }

    public TestPlan generateExampleTestPlan() {
        int nodeNum = Config.getConf().nodeNum;

        List<Event> events = EventParser.construct();

        logger.debug("example test plan size = " + events.size());

        Map<Integer, Map<String, String>> oracle = new HashMap<>();
        Path commandPath = Paths.get(System.getProperty("user.dir"),
                "examplecase");
        String validCommandsFile = "validcommands.txt";
        if ("hdfs".equals(Config.getConf().system)) {
            validCommandsFile = "validcommands_hdfs_example.txt";
        }
        List<String> validcommands = readCommands(
                commandPath.resolve(validCommandsFile));
        List<String> validationReadResultsOracle = new LinkedList<>();

        return new TestPlan(nodeNum, events, validcommands,
                validationReadResultsOracle);
    }

    public static TestPlan generateTestPlan(FullStopSeed fullStopSeed) {
        // Some systems might have special requirements for
        // upgrade, like HDFS needs to upgrade NN.
        int nodeNum = Config.getConf().nodeNum;

        if (Config.getConf().useExampleTestPlan)
            return constructExampleTestPlan(fullStopSeed, nodeNum);

        // -----------fault----------
        int faultNum = rand.nextInt(Config.getConf().faultMaxNum + 1);
        List<Pair<Fault, FaultRecover>> faultPairs = Fault
                .randomGenerateFaults(nodeNum, faultNum);

        List<Event> upgradeOps = new LinkedList<>();
        if (!Config.getConf().testSingleVersion
                && !Config.getConf().fullStopUpgradeWithFaults) {
            for (int i = 0; i < nodeNum; i++) {
                upgradeOps.add(new UpgradeOp(i));
            }
            if (Config.getConf().shuffleUpgradeOrder) {
                Collections.shuffle(upgradeOps);
            }
            // -----------downgrade----------
            if (Config.getConf().testDowngrade) {
                upgradeOps = addDowngrade(upgradeOps);
            }

            // -----------prepare----------
            if (Config.getConf().system.equals("hdfs")) {
                upgradeOps.add(0, new HDFSStopSNN());
            } else {
                // FIXME: Move prepare to the start up stage
                upgradeOps.add(0, new PrepareUpgrade());
            }
        }

        List<Event> upgradeOpAndFaults = interleaveFaultAndUpgradeOp(faultPairs,
                upgradeOps);

        if (!testPlanVerifier(upgradeOpAndFaults, nodeNum)) {
            return null;
        }

        // Randomly interleave the commands with the upgradeOp&faults
        List<Event> shellCommands = new LinkedList<>();
        if (fullStopSeed.seed != null)
            shellCommands = ShellCommand.seedWriteCmd2Events(fullStopSeed.seed,
                    nodeNum);
        else
            logger.error("empty full stop seed");

        List<Event> events = interleaveWithOrder(upgradeOpAndFaults,
                shellCommands);

        if (!Config.getConf().testSingleVersion
                && !Config.getConf().fullStopUpgradeWithFaults)
            events.add(events.size(), new FinalizeUpgrade());

        return new TestPlan(nodeNum, events, fullStopSeed.seed,
                fullStopSeed.seed.validationCommandSequence
                        .getCommandStringList(),
                fullStopSeed.validationReadResults);
    }

    public static TestPlan constructExampleTestPlan(FullStopSeed fullStopSeed,
            int nodeNum) {
        // DEBUG USE
        logger.info("use example test plan");

        List<Event> exampleEvents = new LinkedList<>();
        // nodeNum should be 3
        assert nodeNum == 3;
        // for (int i = 0; i < Config.getConf().nodeNum - 1; i++) {
        // exampleEvents.add(new UpgradeOp(i));
        // }
        exampleEvents.add(new PrepareUpgrade());
        if (Config.getConf().system.equals("hdfs")) {
            exampleEvents.add(new HDFSStopSNN());
        }
        exampleEvents.add(new UpgradeOp(0));

        // exampleEvents.add(new ShellCommand("dfs -touchz /tmp"));
        // exampleEvents.add(new RestartFailure(0));

        exampleEvents.add(new UpgradeOp(1));
        exampleEvents.add(new UpgradeOp(2));
        // exampleEvents.add(new LinkFailure(0, 1));

        // exampleEvents.add(new LinkFailureRecover(0, 1));

        // exampleEvents.add(new UpgradeOp(2));
        // exampleEvents.add(new UpgradeOp(3));
        // exampleEvents.add(0, new LinkFailure(1, 2));
        return new TestPlan(nodeNum, exampleEvents, new LinkedList<>(),
                new LinkedList<>());
    }

    public static boolean testPlanVerifier(List<Event> events, int nodeNum) {
        // check connection status to the seed node
        boolean[][] connection = new boolean[nodeNum][nodeNum];
        for (int i = 0; i < nodeNum; i++) {
            for (int j = 0; j < nodeNum; j++) {
                connection[i][j] = true;
            }
        }
        // Check the connection with the seed node
        for (Event event : events) {
            if (event instanceof IsolateFailure) {
                int nodeIdx = ((IsolateFailure) event).nodeIndex;
                for (int i = 0; i < nodeNum; i++) {
                    if (i != nodeIdx)
                        connection[i][nodeIdx] = false;
                }
                for (int i = 0; i < nodeNum; i++) {
                    if (i != nodeIdx)
                        connection[nodeIdx][i] = false;
                }
            } else if (event instanceof IsolateFailureRecover) {
                int nodeIdx = ((IsolateFailureRecover) event).nodeIndex;
                for (int i = 0; i < nodeNum; i++) {
                    if (i != nodeIdx)
                        connection[i][nodeIdx] = true;
                }
                for (int i = 0; i < nodeNum; i++) {
                    if (i != nodeIdx)
                        connection[nodeIdx][i] = true;
                }
            } else if (event instanceof LinkFailure) {
                int nodeIdx1 = ((LinkFailure) event).nodeIndex1;
                int nodeIdx2 = ((LinkFailure) event).nodeIndex2;
                connection[nodeIdx1][nodeIdx2] = false;
                connection[nodeIdx2][nodeIdx1] = false;
            } else if (event instanceof LinkFailureRecover) {
                int nodeIdx1 = ((LinkFailureRecover) event).nodeIndex1;
                int nodeIdx2 = ((LinkFailureRecover) event).nodeIndex2;
                connection[nodeIdx1][nodeIdx2] = true;
                connection[nodeIdx2][nodeIdx1] = true;
            } else if (event instanceof UpgradeOp
                    || event instanceof DowngradeOp
                    || event instanceof RestartFailure
                    || event instanceof NodeFailureRecover) {
                int nodeIdx;
                if (event instanceof UpgradeOp)
                    nodeIdx = ((UpgradeOp) event).nodeIndex;
                else if (event instanceof DowngradeOp)
                    nodeIdx = ((DowngradeOp) event).nodeIndex;
                else if (event instanceof RestartFailure)
                    nodeIdx = ((RestartFailure) event).nodeIndex;
                else
                    nodeIdx = ((NodeFailureRecover) event).nodeIndex;
                if (nodeIdx == 0)
                    continue;

                if (Config.getConf().system.equals("hdfs")
                        || Config.getConf().system.equals("cassandra")) {
                    // This could be removed if failover is implemented
                    if (!connection[nodeIdx][0])
                        return false;
                } else {
                    int connectedPeerNum = 0;
                    for (int i = 0; i < nodeNum; i++) {
                        if (i != nodeIdx) {
                            if (connection[nodeIdx][i]) {
                                connectedPeerNum++;
                            }
                        }
                    }
                    if (connectedPeerNum == 0) {
                        return false;
                    }
                }
            }
        }

        boolean isSeedAlive = true;
        // Cannot upgrade if seed node is down
        // Cannot execute commands if seed node is down
        // TODO: If we have failure mechanism, we can remove this check
        for (Event event : events) {
            if (event instanceof NodeFailure) {
                int nodeIdx = ((NodeFailure) event).nodeIndex;
                if (nodeIdx == 0)
                    isSeedAlive = false;
            } else if (event instanceof NodeFailureRecover) {
                int nodeIdx = ((NodeFailureRecover) event).nodeIndex;
                if (nodeIdx == 0) {
                    isSeedAlive = true;
                }
            } else if (event instanceof RestartFailure) {
                int nodeIdx = ((RestartFailure) event).nodeIndex;
                if (nodeIdx == 0) {
                    isSeedAlive = true;
                }
            } else if (event instanceof UpgradeOp) {
                int nodeIdx = ((UpgradeOp) event).nodeIndex;
                if (nodeIdx == 0) {
                    isSeedAlive = true;
                } else if (!isSeedAlive) {
                    return false;
                }
            } else if (event instanceof ShellCommand) {
                if (!Config.getConf().failureOver) {
                    if (!isSeedAlive)
                        return false;
                }
            }
        }

        // Check double failure injection (NodeFailure[0] -x-> LinkFailure[0])
        boolean[] nodeState = new boolean[nodeNum];
        for (int i = 0; i < nodeNum; i++)
            nodeState[i] = true;
        for (Event event : events) {
            if (event instanceof NodeFailure) {
                int nodeIdx = ((NodeFailure) event).nodeIndex;
                nodeState[nodeIdx] = false;
            } else if (event instanceof NodeFailureRecover) {
                int nodeIdx = ((NodeFailureRecover) event).nodeIndex;
                nodeState[nodeIdx] = true;
            } else if (event instanceof RestartFailure) {
                int nodeIdx = ((RestartFailure) event).nodeIndex;
                nodeState[nodeIdx] = true;
            } else if (event instanceof UpgradeOp) {
                int nodeIdx = ((UpgradeOp) event).nodeIndex;
                nodeState[nodeIdx] = true;
            } else if (event instanceof LinkFailure) {
                int nodeIdx1 = ((LinkFailure) event).nodeIndex1;
                int nodeIdx2 = ((LinkFailure) event).nodeIndex2;
                if (!nodeState[nodeIdx1] || !nodeState[nodeIdx2])
                    return false;
            } else if (event instanceof LinkFailureRecover) {
                int nodeIdx1 = ((LinkFailureRecover) event).nodeIndex1;
                int nodeIdx2 = ((LinkFailureRecover) event).nodeIndex2;
                if (!nodeState[nodeIdx1] || !nodeState[nodeIdx2])
                    return false;
            } else if (event instanceof IsolateFailure) {
                int nodeIdx = ((IsolateFailure) event).nodeIndex;
                if (!nodeState[nodeIdx]) {
                    return false;
                }
            } else if (event instanceof IsolateFailureRecover) {
                int nodeIdx = ((IsolateFailureRecover) event).nodeIndex;
                if (!nodeState[nodeIdx]) {
                    return false;
                }
            }
        }

        // hdfs specific, no restart failure between STOPSNN and UpgradeSNN
        if (Config.getConf().system.equals("hdfs")) {
            boolean metStopSNN = false;
            for (Event event : events) {
                if (event instanceof HDFSStopSNN) {
                    metStopSNN = true;
                } else if (event instanceof RestartFailure) {
                    int nodeIdx = ((RestartFailure) event).nodeIndex;
                    if (metStopSNN && nodeIdx == 1) {
                        return false;
                    }
                } else if (event instanceof UpgradeOp) {
                    int nodeIdx = ((UpgradeOp) event).nodeIndex;
                    if (nodeIdx == 1) {
                        // checked the process between STOPSNN and UpgradeSNN
                        break;
                    }
                }
            }
        }
        return true;
    }

    public static List<String> readCommands(Path path) {
        List<String> strings = new LinkedList<>();
        try {
            BufferedReader br = new BufferedReader(
                    new FileReader(path.toFile()));
            String line;
            while ((line = br.readLine()) != null) {
                if (!line.isEmpty())
                    strings.add(line);
            }
        } catch (IOException e) {
            e.printStackTrace();
            System.exit(1);
        }
        return strings;
    }

    public static String readConfigFileName(Path path) {
        String configFileName = readFirstLine(path);

        if (configFileName != null) {
            return configFileName;
        } else {
            return null;
        }
    }

    private static String readFirstLine(Path path) {
        try (BufferedReader br = new BufferedReader(
                new FileReader(path.toFile()))) {
            return br.readLine(); // Only read the first line
        } catch (IOException e) {
            System.out.println("Error reading file: " + e.getMessage());
            return null;
        }
    }

    private void updateBCStatus() {
        updateBCStatusOri();
        updateBCStatusUpAfterUpgrade();
        updateBCStatusUp();
        updateBCStatusOriAfterDowngrade();
        // updateBCStatusAlongTime();
    }

    private void updateBCStatusOri() {
        Pair<Integer, Integer> coverageStatus = Utilities
                .getCoverageStatus(
                        curOriCoverage);
        oriCoveredBranches = coverageStatus.left;
        oriProbeNum = coverageStatus.right;
    }

    private void updateBCStatusUp() {
        Pair<Integer, Integer> coverageStatus = Utilities
                .getCoverageStatus(
                        curUpCoverage);
        upCoveredBranches = coverageStatus.left;
        upProbeNum = coverageStatus.right;
    }

    private void updateBCStatusOriAfterDowngrade() {
        Pair<Integer, Integer> coverageStatus = Utilities
                .getCoverageStatus(
                        curOriCoverageAfterDowngrade);
        oriCoveredBranchesAfterDowngrade = coverageStatus.left;
        oriProbeNumAfterDowngrade = coverageStatus.right;
    }

    private void updateBCStatusUpAfterUpgrade() {
        Pair<Integer, Integer> coverageStatus = Utilities
                .getCoverageStatus(
                        curUpCoverageAfterUpgrade);
        upCoveredBranchesAfterUpgrade = coverageStatus.left;
        upProbeNumAfterUpgrade = coverageStatus.right;
    }

    private void updateBCStatusAlongTime() {
        Long timeElapsed = TimeUnit.SECONDS.convert(
                System.nanoTime(), TimeUnit.NANOSECONDS) - startTime;
        if (timeElapsed - lastTimePoint > Config.getConf().timeInterval ||
                lastTimePoint == 0) {
            // Insert a record (time: coverage)
            oriBCAlongTime.add(
                    new Pair(timeElapsed, oriCoveredBranches));
            upBCAlongTimeAfterUpgrade.add(
                    new Pair(timeElapsed, upCoveredBranchesAfterUpgrade));
            upBCCoverageAlongTime.add(
                    new Pair(timeElapsed, upCoveredBranches));
            oriBCAlongTimeAfterDowngrade.add(
                    new Pair(timeElapsed, oriCoveredBranchesAfterDowngrade));
            lastTimePoint = timeElapsed;
        }
    }

    public synchronized void updateStatus(
            TestPlanFeedbackPacket testPlanFeedbackPacket) {

        FeedBack fb = mergeCoverage(testPlanFeedbackPacket.feedBacks);
        boolean addToCorpus = false;
        if (Config.getConf().useBranchCoverage) {
            if (Utilities.hasNewBits(curOriCoverage,
                    fb.originalCodeCoverage)) {
                addToCorpus = true;
                curOriCoverage.merge(fb.originalCodeCoverage);
            }
            if (Utilities.hasNewBits(curUpCoverageAfterUpgrade,
                    fb.upgradedCodeCoverage)) {
                addToCorpus = true;
                curUpCoverageAfterUpgrade.merge(fb.upgradedCodeCoverage);
            }
            if (addToCorpus) {
                testPlanCorpus.addTestPlan(
                        testID2TestPlan
                                .get(testPlanFeedbackPacket.testPacketID));
            }
        }

        if (Config.getConf().useTrace) {
            if (testPlanFeedbackPacket.trace != null) {
                // Debug
                logger.info(
                        "trace len: " + testPlanFeedbackPacket.trace.length);
                for (int i = 0; i < testPlanFeedbackPacket.trace.length; i++)
                    logger.info("trace[" + i + "] len = "
                            + testPlanFeedbackPacket.trace[i].size());
            } else {
                logger.error("trace is null");
            }
        }

        Path failureDir;
        if (testPlanFeedbackPacket.isEventFailed
                || testPlanFeedbackPacket.isInconsistent
                || testPlanFeedbackPacket.hasERRORLog) {
            failureDir = createFailureDir(
                    testPlanFeedbackPacket.configFileName);
            saveFullSequence(failureDir, testPlanFeedbackPacket.fullSequence);
            if (testPlanFeedbackPacket.isEventFailed) {
                saveEventCrashReport(failureDir,
                        testPlanFeedbackPacket.testPacketID,
                        testPlanFeedbackPacket.eventFailedReport);
            }
            if (testPlanFeedbackPacket.isInconsistent) {
                saveInconsistencyReport(failureDir,
                        testPlanFeedbackPacket.testPacketID,
                        testPlanFeedbackPacket.inconsistencyReport);
            }
            if (testPlanFeedbackPacket.hasERRORLog) {
                saveErrorReport(failureDir,
                        testPlanFeedbackPacket.errorLogReport,
                        testPlanFeedbackPacket.testPacketID);
            }
        }
        testID2TestPlan.remove(testPlanFeedbackPacket.testPacketID);

        finishedTestID++;
        printInfo();
        System.out.println();
    }

    static Map<Integer, String> testPlanID2Setup = new HashMap<>();
    static {
        testPlanID2Setup.put(0, "Only Old");
        testPlanID2Setup.put(1, "Rolling");
        testPlanID2Setup.put(2, "Only New");
    }

    public synchronized void updateStatus(
            TestPlanDiffFeedbackPacket testPlanDiffFeedbackPacket) {
        logger.info("TestPlanDiffFeedbackPacket received");

        TestPlanFeedbackPacket[] testPlanFeedbackPackets = testPlanDiffFeedbackPacket.testPlanFeedbackPackets;

        if (testPlanFeedbackPackets.length != 3) {
            throw new RuntimeException(
                    "TestPlanDiffFeedbackPacket length is not 3: there should be (1) Old (2) RU and (3) New");
        }

        // --- Trace processing ---
        Trace[] serializedTraces = new Trace[testPlanFeedbackPackets.length];

        if (Config.getConf().useTrace) {
            for (int i = 0; i < testPlanFeedbackPackets.length; i++) {
                TestPlanFeedbackPacket testPlanFeedbackPacket = testPlanFeedbackPackets[i];

                if (testPlanFeedbackPacket.trace != null) {
                    serializedTraces[i] = Trace
                            .mergeBasedOnTimestamp(
                                    testPlanFeedbackPacket.trace);

                    logger.info("TestPlanFeedbackPacket " + i + ", type = "
                            + testPlanID2Setup.get(i) + ": trace:");
                    for (int j = 0; j < testPlanFeedbackPacket.trace.length; j++) {
                        logger.info("trace[" + j + "] len = "
                                + testPlanFeedbackPacket.trace[j].size());

                        List<TraceEntry> entries = testPlanFeedbackPacket.trace[j]
                                .getTraceEntries();
                        boolean hasChangedMessage = false;
                        for (TraceEntry traceEntry : entries) {
                            if (traceEntry.changedMessage) {
                                hasChangedMessage = true;
                                break;
                            }
                        }
                        if (hasChangedMessage) {
                            logger.info("Trace contains changed message");
                        } else {
                            logger.info(
                                    "Trace does not contain changed message");
                        }
                    }
                } else {
                    logger.error("trace is null for cluster " + i);
                    serializedTraces[i] = new Trace();
                }
            }

            if (Config.getConf().printTrace) {
                for (int i = 0; i < serializedTraces.length; i++) {
                    String clusterType = testPlanID2Setup.get(i);
                    logger.info("=== Merged Trace " + i + " ("
                            + clusterType + "), size="
                            + serializedTraces[i].size() + " ===");
                    int entryIdx = 0;
                    for (TraceEntry entry : serializedTraces[i]
                            .getTraceEntries()) {
                        logger.info("[" + clusterType + "] entry["
                                + entryIdx + "] " + entry);
                        entryIdx++;
                    }
                    // Print hashcode sequence for Jaccard analysis
                    logger.info("[" + clusterType
                            + "] hashcode sequence: "
                            + serializedTraces[i].getHashCodes());
                }
            }
        }

        // --- Branch coverage processing (from all 3 clusters) ---
        // Old-Old (index 0, direction=0): originalCodeCoverage = old version
        FeedBack fbOld = mergeCoverage(testPlanFeedbackPackets[0].feedBacks);
        // Rolling (index 1, direction=0): original = old, upgraded = new
        FeedBack fbRolling = mergeCoverage(
                testPlanFeedbackPackets[1].feedBacks);
        // New-New (index 2, direction=1): originalCodeCoverage = new version
        // (swapped!)
        FeedBack fbNew = mergeCoverage(testPlanFeedbackPackets[2].feedBacks);

        boolean newOriBC = false;
        boolean newUpgradeBC = false;

        if (Config.getConf().useBranchCoverage) {
            // Merge old version coverage into curOriCoverage
            if (Utilities.hasNewBits(curOriCoverage,
                    fbOld.originalCodeCoverage)) {
                curOriCoverage.merge(fbOld.originalCodeCoverage);
                newOriBC = true;
            }
            if (Utilities.hasNewBits(curOriCoverage,
                    fbRolling.originalCodeCoverage)) {
                curOriCoverage.merge(fbRolling.originalCodeCoverage);
                newOriBC = true;
            }
            // Merge new version coverage into curUpCoverageAfterUpgrade
            if (Utilities.hasNewBits(curUpCoverageAfterUpgrade,
                    fbRolling.upgradedCodeCoverage)) {
                curUpCoverageAfterUpgrade
                        .merge(fbRolling.upgradedCodeCoverage);
                newUpgradeBC = true;
            }
            if (Utilities.hasNewBits(curUpCoverageAfterUpgrade,
                    fbNew.originalCodeCoverage)) {
                curUpCoverageAfterUpgrade.merge(fbNew.originalCodeCoverage);
                newUpgradeBC = true;
            }
        }

        // --- Jaccard similarity "interesting" check ---
        boolean lowSimilarity = false;
        if (Config.getConf().useJaccardSimilarity
                && Config.getConf().useTrace) {
            double[] jaccardSim = DiffComputeJaccardSimilarity.compute(
                    serializedTraces[0], serializedTraces[1],
                    serializedTraces[2]);
            logger.info("Jaccard Similarity[0] = " + jaccardSim[0]
                    + ", Jaccard Similarity[1] = " + jaccardSim[1]);

            if (jaccardSim[0] < Config.getConf().jaccardSimilarityThreshold
                    || jaccardSim[1] < Config
                            .getConf().jaccardSimilarityThreshold) {
                lowSimilarity = true;
                logger.info(
                        "Low Jaccard similarity detected — test is interesting");
            }
        }

        boolean messageIdentityInteresting = false;
        if (Config.getConf().useMessageIdentityDiff
                && Config.getConf().useTrace) {
            DiffComputeMessageTriDiff.MessageTriDiffResult triDiff = DiffComputeMessageTriDiff
                    .compute(serializedTraces[0], serializedTraces[1],
                            serializedTraces[2]);
            logger.info(
                    "Message identity tri-diff: " + triDiff.toSummaryString());
            if (Config.getConf().printTrace) {
                logger.info(triDiff.toDetailedString(20));
            }
            if (triDiff.isInteresting(
                    Config.getConf().messageIdentityMinExclusiveCount,
                    Config.getConf().messageIdentityMinOrderedCommonRatio)) {
                messageIdentityInteresting = true;
                logger.info(
                        "Message identity tri-diff detected interesting divergence");
            }
        }

        if (Config.getConf().useEditDistance && Config.getConf().useTrace) {
            int[] diff = DiffComputeEditDistance.compute(serializedTraces[0],
                    serializedTraces[1], serializedTraces[2]);
            logger.info("Diff[0] = " + diff[0] + ", Diff[1] = " + diff[1]);
        }

        // --- Corpus update ---
        boolean addToCorpus = newOriBC || newUpgradeBC || lowSimilarity
                || messageIdentityInteresting;
        if (addToCorpus) {
            TestPlan testPlan = testID2TestPlan
                    .get(testPlanDiffFeedbackPacket.testPacketID);
            if (testPlan != null) {
                testPlanCorpus.addTestPlan(testPlan);
                logger.info(
                        "Added test plan to corpus (newOriBC=" + newOriBC
                                + ", newUpgradeBC=" + newUpgradeBC
                                + ", lowSimilarity=" + lowSimilarity
                                + ", messageIdentityInteresting="
                                + messageIdentityInteresting + ")");
            }
        }

        // --- Bug detection ---
        Path failureDir = null;
        TestPlanFeedbackPacket rollingFb = testPlanFeedbackPackets[1];

        // 1. Check if all 3 clusters failed with the same event failure
        boolean allFailed = testPlanFeedbackPackets[0].isEventFailed
                && testPlanFeedbackPackets[1].isEventFailed
                && testPlanFeedbackPackets[2].isEventFailed;
        if (allFailed) {
            logger.info(
                    "All 3 clusters failed — discarding (likely test issue, not upgrade bug)");
        } else {
            // 2. Rolling upgrade process crash (only rolling failed)
            if (rollingFb.isEventFailed) {
                failureDir = ensureFailureDir(failureDir,
                        rollingFb.configFileName, rollingFb.fullSequence);
                saveEventCrashReport(failureDir,
                        testPlanDiffFeedbackPacket.testPacketID,
                        "[Rolling] " + rollingFb.eventFailedReport);
            }

            // 3. Cross-cluster inconsistency
            if (hasValidationResults(testPlanFeedbackPackets)) {
                String crossClusterReport = checkCrossClusterInconsistency(
                        testPlanFeedbackPackets);
                if (crossClusterReport != null) {
                    failureDir = ensureFailureDir(failureDir,
                            rollingFb.configFileName, rollingFb.fullSequence);
                    saveInconsistencyReport(failureDir,
                            testPlanDiffFeedbackPacket.testPacketID,
                            crossClusterReport);
                }
            }

            // 4. Per-cluster inconsistency (existing oracle comparison)
            for (int i = 0; i < 3; i++) {
                if (testPlanFeedbackPackets[i].isInconsistent) {
                    failureDir = ensureFailureDir(failureDir,
                            rollingFb.configFileName, rollingFb.fullSequence);
                    saveInconsistencyReport(failureDir,
                            testPlanDiffFeedbackPacket.testPacketID,
                            "[" + testPlanID2Setup.get(i) + "] "
                                    + testPlanFeedbackPackets[i].inconsistencyReport);
                }
            }

            // 5. ERROR logs from any cluster
            for (int i = 0; i < 3; i++) {
                if (testPlanFeedbackPackets[i].hasERRORLog) {
                    failureDir = ensureFailureDir(failureDir,
                            rollingFb.configFileName, rollingFb.fullSequence);
                    saveErrorReport(failureDir,
                            "[" + testPlanID2Setup.get(i) + "] "
                                    + testPlanFeedbackPackets[i].errorLogReport,
                            testPlanDiffFeedbackPacket.testPacketID);
                }
            }
        }

        // --- Cleanup and status update ---
        testID2TestPlan.remove(testPlanDiffFeedbackPacket.testPacketID);
        finishedTestID++;
        printInfo();
        System.out.println();
    }

    // Check cross-cluster inconsistency (compare validation results)
    private String checkCrossClusterInconsistency(
            TestPlanFeedbackPacket[] packets) {
        List<String> oldResults = packets[0].validationReadResults;
        List<String> rollingResults = packets[1].validationReadResults;
        List<String> newResults = packets[2].validationReadResults;

        if (oldResults == null || rollingResults == null
                || newResults == null)
            return null;
        if (oldResults.isEmpty() && rollingResults.isEmpty()
                && newResults.isEmpty())
            return null;

        // Check if rolling differs from both old-old and new-new
        boolean rollingDiffersFromOld = !oldResults.equals(rollingResults);
        boolean rollingDiffersFromNew = !newResults.equals(rollingResults);
        boolean oldNewAgree = oldResults.equals(newResults);

        if (rollingDiffersFromOld && rollingDiffersFromNew && oldNewAgree) {
            // Rolling upgrade introduced behavioral divergence
            StringBuilder sb = new StringBuilder();
            sb.append("Cross-cluster inconsistency detected!\n");
            sb.append(
                    "Old-Old and New-New agree but Rolling differs.\n");
            sb.append("Old-Old results: ").append(oldResults).append("\n");
            sb.append("Rolling results: ").append(rollingResults)
                    .append("\n");
            sb.append("New-New results: ").append(newResults).append("\n");
            return sb.toString();
        }
        return null;
    }

    // Helper to lazily create failure dir (reuses existing pattern)
    private Path ensureFailureDir(Path failureDir, String configFileName,
            String fullSequence) {
        if (failureDir == null) {
            return createFailureDirAndSaveFullSequence(configFileName,
                    fullSequence);
        }
        return failureDir;
    }

    // Check if packets have validation results
    private boolean hasValidationResults(
            TestPlanFeedbackPacket[] packets) {
        for (TestPlanFeedbackPacket p : packets) {
            if (p.validationReadResults != null
                    && !p.validationReadResults.isEmpty())
                return true;
        }
        return false;
    }

    public Path createFailureDirAndSaveFullSequence(
            String configFileName, String fullSequence) {
        Path failureDir = createFailureDir(configFileName);
        saveFullSequence(failureDir, fullSequence);
        return failureDir;
    }

    public synchronized void updateStatus(
            StackedFeedbackPacket stackedFeedbackPacket) {

        if (stackedFeedbackPacket.upgradeSkipped) {
            // upgrade process is skipped
            logger.info("upgrade process is skipped");
            skippedUpgradeNum++;
        }

        Path failureDir = null;

        // Handle test execution timeout or failed with other exception
        if (stackedFeedbackPacket.testExecutionTimeout
                || stackedFeedbackPacket.testExecutionFailedWithOtherException) {
            logger.info(
                    "test execution timed out or failed with other exception");

            if (failureDir == null)
                failureDir = createFailureDirAndSaveFullSequence(
                        stackedFeedbackPacket.configFileName,
                        stackedFeedbackPacket.fullSequence);

            assert stackedFeedbackPacket.testIDs.size() > 0;

            int startTestID = stackedFeedbackPacket.testIDs.get(0);
            int endTestID = stackedFeedbackPacket.testIDs
                    .get(stackedFeedbackPacket.testIDs.size() - 1);

            if (stackedFeedbackPacket.testExecutionTimeout) {
                saveTestExecutionTimeoutReport(failureDir,
                        "exec_timeout",
                        startTestID,
                        endTestID);
            } else {
                saveTestExecutionFailedWithOtherExceptionReport(failureDir,
                        stackedFeedbackPacket.testExecutionFailedWithOtherExceptionReport,
                        startTestID,
                        endTestID);
            }
            finishedTestID++;
            return;
        }

        int startTestID = 0;
        int endTestID = 0;
        if (stackedFeedbackPacket.getFpList().size() > 0) {
            startTestID = stackedFeedbackPacket.getFpList().get(0).testPacketID;
            endTestID = stackedFeedbackPacket.getFpList()
                    .get(stackedFeedbackPacket.getFpList().size()
                            - 1).testPacketID;
        }

        if (stackedFeedbackPacket.isUpgradeProcessFailed) {
            failureDir = createFailureDir(stackedFeedbackPacket.configFileName);
            saveFullSequence(failureDir, stackedFeedbackPacket.fullSequence);
            saveFullStopCrashReport(failureDir,
                    stackedFeedbackPacket.upgradeFailureReport, startTestID,
                    endTestID);
            finishedTestID++;
        }

        if (Config.getConf().testDowngrade) {
            logger.debug(
                    "[hklog] check downgrade failure: isDowngradeProcessFailed = "
                            + stackedFeedbackPacket.isDowngradeProcessFailed);
            if (stackedFeedbackPacket.isDowngradeProcessFailed) {
                if (failureDir == null) {
                    failureDir = createFailureDirAndSaveFullSequence(
                            stackedFeedbackPacket.configFileName,
                            stackedFeedbackPacket.fullSequence);
                }
                saveFullStopCrashReport(failureDir,
                        stackedFeedbackPacket.downgradeFailureReport,
                        startTestID,
                        endTestID, false);
            }
        }

        FuzzingServerHandler.printClientNum();
        for (FeedbackPacket feedbackPacket : stackedFeedbackPacket
                .getFpList()) {
            finishedTestID++;

            boolean newOriBC = false;
            boolean newUpgradeBC = false;

            boolean newOriFC = false;
            boolean newModFC = false;
            boolean newBoundaryChange = false;

            // Merge all the feedbacks
            FeedBack fb = mergeCoverage(feedbackPacket.feedBacks);
            if (Utilities.hasNewBits(
                    curOriCoverage,
                    fb.originalCodeCoverage)) {
                // Write Seed to Disk + Add to Corpus
                curOriCoverage.merge(
                        fb.originalCodeCoverage);
                newOriBC = true;
            }
            if (Utilities.hasNewBits(curUpCoverageAfterUpgrade,
                    fb.upgradedCodeCoverage)) {
                curUpCoverageAfterUpgrade.merge(
                        fb.upgradedCodeCoverage);
                newUpgradeBC = true;
            }

            // format coverage
            if (Config.getConf().useFormatCoverage) {
                if (feedbackPacket.formatCoverage != null) {
                    FormatCoverageStatus oriFormatCoverageStatus = oriObjCoverage
                            .merge(feedbackPacket.formatCoverage,
                                    "ori",
                                    feedbackPacket.testPacketID, true,
                                    Config.getConf().updateInvariantBrokenFrequency,
                                    Config.getConf().checkSpecialDumpIds);
                    if (oriFormatCoverageStatus.isNewFormat()) {
                        logger.info("New format coverage for test "
                                + feedbackPacket.testPacketID);
                        newOriFC = true;
                        newFormatCount += oriFormatCoverageStatus
                                .getNewFormatCount();
                    }
                    // New format relevant to modification
                    assert !(Config.getConf().staticVD
                            && Config.getConf().prioritizeIsSerialized);
                    if (Config.getConf().staticVD) {
                        if (oriFormatCoverageStatus.isNonMatchableNewFormat()) {
                            logger.info(
                                    "New modification related format coverage for test "
                                            + feedbackPacket.testPacketID);
                            newModFC = true;
                            nonMatchableNewFormatCount += oriFormatCoverageStatus
                                    .getNonMatchableNewFormatCount();
                        }
                        if (oriFormatCoverageStatus.isNonMatchableMultiInv()) {
                            nonMatchableMultiInvCount += oriFormatCoverageStatus
                                    .getNonMatchableMultiInvCount();
                        }
                    }
                    if (Config.getConf().staticVD
                            && Config.getConf().prioritizeMultiInv
                            && oriFormatCoverageStatus
                                    .isMultiInvBroken()) {
                        logger.info(
                                "Multi-inv Broken for test "
                                        + feedbackPacket.testPacketID);
                        newModFC = true;
                    }
                    if (Config.getConf().prioritizeIsSerialized
                            && oriFormatCoverageStatus.isNewIsSerialize()) {
                        logger.info("New isSerialized coverage for test "
                                + feedbackPacket.testPacketID);
                        newModFC = true;
                    }
                    if (oriFormatCoverageStatus.isBoundaryChange()) {
                        logger.info("Boundary change for test "
                                + feedbackPacket.testPacketID);
                        newBoundaryChange = true;
                    }
                } else {
                    logger.info("Null format coverage");
                }
            }

            corpus.addSeed(testID2Seed.get(feedbackPacket.testPacketID),
                    newOriBC,
                    newOriFC, newUpgradeBC,
                    newBoundaryChange, newModFC);

            // Update full-stop corpus
            fullStopCorpus.addSeed(new FullStopSeed(
                    testID2Seed.get(feedbackPacket.testPacketID),
                    feedbackPacket.validationReadResults));

            // TODO: record boundary in graph
            graph.updateNodeCoverage(feedbackPacket.testPacketID,
                    newOriBC, newUpgradeBC,
                    newOriFC, newModFC);

            if (feedbackPacket.isInconsistent) {
                if (failureDir == null) {
                    failureDir = createFailureDirAndSaveFullSequence(
                            stackedFeedbackPacket.configFileName,
                            stackedFeedbackPacket.fullSequence);
                }
                saveInconsistencyReport(failureDir,
                        feedbackPacket.testPacketID,
                        feedbackPacket.inconsistencyReport);
            }
        }
        // update testId2Seed
        for (int testID : stackedFeedbackPacket.testIDs) {
            testID2Seed.remove(testID);
        }
        if (stackedFeedbackPacket.hasERRORLog) {
            if (failureDir == null) {
                failureDir = createFailureDirAndSaveFullSequence(
                        stackedFeedbackPacket.configFileName,
                        stackedFeedbackPacket.fullSequence);
            }
            saveErrorReport(failureDir,
                    stackedFeedbackPacket.errorLogReport, startTestID,
                    endTestID);
        }
        printInfo();
        System.out.println();
    }

    // One Group VD
    public synchronized void analyzeFeedbackFromVersionDelta(
            VersionDeltaFeedbackPacketApproach1 versionDeltaFeedbackPacket) {
        Path failureDir = null;

        int startTestID = 0;
        int endTestID = 0;

        List<FeedbackPacket> versionDeltaFeedbackPacketsUp = versionDeltaFeedbackPacket.stackedFeedbackPacketUpgrade.fpList;
        List<FeedbackPacket> versionDeltaFeedbackPacketsDown = versionDeltaFeedbackPacket.stackedFeedbackPacketDowngrade.fpList;
        String configFileName = versionDeltaFeedbackPacket.stackedFeedbackPacketUpgrade.configFileName;
        String fullSequence = versionDeltaFeedbackPacket.stackedFeedbackPacketUpgrade.fullSequence;

        if (versionDeltaFeedbackPacketsUp.size() > 0) {
            startTestID = versionDeltaFeedbackPacketsUp
                    .get(0).testPacketID;
            endTestID = versionDeltaFeedbackPacketsUp
                    .get(versionDeltaFeedbackPacketsUp.size()
                            - 1).testPacketID;
        }

        if (versionDeltaFeedbackPacket.stackedFeedbackPacketUpgrade.isUpgradeProcessFailed) {
            failureDir = createFailureDir(
                    configFileName);
            saveFullSequence(failureDir, fullSequence);
            saveFullStopCrashReport(failureDir,
                    versionDeltaFeedbackPacket.stackedFeedbackPacketUpgrade.upgradeFailureReport,
                    startTestID,
                    endTestID);
            finishedTestID++;
            finishedTestIdAgentGroup2++;
        }

        if (versionDeltaFeedbackPacket.stackedFeedbackPacketDowngrade.isDowngradeProcessFailed) {
            failureDir = createFailureDir(configFileName);
            saveFullSequence(failureDir, fullSequence);
            saveFullStopCrashReport(failureDir,
                    versionDeltaFeedbackPacket.stackedFeedbackPacketDowngrade.downgradeFailureReport,
                    startTestID,
                    endTestID);
            finishedTestID++;
            finishedTestIdAgentGroup2++;
        }
        FuzzingServerHandler.printClientNum();

        for (FeedbackPacket fp : versionDeltaFeedbackPacketsUp) {
            if (fp.isInconsistencyInsignificant) {
                insignificantInconsistenciesIn.add(fp.testPacketID);
            }
        }
        for (FeedbackPacket fp : versionDeltaFeedbackPacketsDown) {
            if (fp.isInconsistencyInsignificant) {
                insignificantInconsistenciesIn.add(fp.testPacketID);
            }
        }

        int feedbackLength = versionDeltaFeedbackPacketsUp.size();
        for (int i = 0; i < feedbackLength; i++) {
            FeedbackPacket versionDeltaFeedbackPacketUp = versionDeltaFeedbackPacketsUp
                    .get(i);
            FeedbackPacket versionDeltaFeedbackPacketDown = versionDeltaFeedbackPacketsDown
                    .get(i);
            assert versionDeltaFeedbackPacketUp.testPacketID == versionDeltaFeedbackPacketDown.testPacketID;
            int testPacketID = versionDeltaFeedbackPacketUp.testPacketID;

            finishedTestID++;
            finishedTestIdAgentGroup2++;

            // Branch coverage
            boolean newBCVD = false;
            boolean newOriBC = false;
            boolean newUpgradeBC = false;
            boolean newUpBC = false;
            boolean newDowngradeBC = false;

            // Format coverage
            boolean newFCVD = false;
            boolean newOriFC = false;
            boolean newOriMatchableFC = false;
            boolean newUpFC = false;
            boolean newUpMatchableFC = false;
            boolean newUpBoundaryChange = false;
            boolean newOriBoundaryChange = false;

            // Merge all feedbacks
            FeedBack fbUpgrade = mergeCoverage(
                    versionDeltaFeedbackPacketUp.feedBacks);
            FeedBack fbDowngrade = mergeCoverage(
                    versionDeltaFeedbackPacketDown.feedBacks);

            if (Utilities.hasNewBits(
                    curOriCoverage,
                    fbUpgrade.originalCodeCoverage)) {
                curOriCoverage.merge(fbUpgrade.originalCodeCoverage);
                newOriBC = true;
            }
            if (Utilities.hasNewBits(
                    curUpCoverage,
                    fbDowngrade.originalCodeCoverage)) {
                curUpCoverage.merge(fbDowngrade.originalCodeCoverage);
                newUpBC = true;
            }
            if (Utilities.hasNewBits(
                    curUpCoverageAfterUpgrade,
                    fbUpgrade.upgradedCodeCoverage)) {
                curUpCoverageAfterUpgrade.merge(
                        fbUpgrade.upgradedCodeCoverage);
                newUpgradeBC = true;
            }
            if (Utilities.hasNewBits(curOriCoverageAfterDowngrade,
                    fbDowngrade.downgradedCodeCoverage)) {
                curOriCoverageAfterDowngrade.merge(
                        fbDowngrade.downgradedCodeCoverage);
                newDowngradeBC = true;
            }
            // Compute BC version delta
            newBCVD = newOriBC ^ newUpBC;

            // Compute format coverage
            if (Config.getConf().useFormatCoverage) {
                if (versionDeltaFeedbackPacketUp.formatCoverage != null) {
                    FormatCoverageStatus oriFormatCoverageStatus = oriObjCoverage
                            .merge(
                                    versionDeltaFeedbackPacketUp.formatCoverage,
                                    "ori",
                                    testPacketID, true,
                                    Config.getConf().updateInvariantBrokenFrequency,
                                    Config.getConf().checkSpecialDumpIds);
                    if (oriFormatCoverageStatus.isNewFormat())
                        newOriFC = true;
                    if (oriFormatCoverageStatus.isBoundaryChange())
                        newOriBoundaryChange = true;
                    if (oriFormatCoverageStatus.isMatchableNewFormat())
                        newOriMatchableFC = true;
                } else {
                    logger.info("Null format coverage");
                }
                if (versionDeltaFeedbackPacketDown.formatCoverage != null) {
                    FormatCoverageStatus upFormatCoverageStatus = upObjCoverage
                            .merge(
                                    versionDeltaFeedbackPacketDown.formatCoverage,
                                    "up",
                                    testPacketID, true,
                                    Config.getConf().updateInvariantBrokenFrequency,
                                    Config.getConf().checkSpecialDumpIds);
                    if (upFormatCoverageStatus.isNewFormat())
                        newUpFC = true;
                    if (upFormatCoverageStatus.isBoundaryChange())
                        newUpBoundaryChange = true;
                    if (upFormatCoverageStatus.isMatchableNewFormat())
                        newUpMatchableFC = true;
                } else {
                    logger.info("Null format coverage");
                }
                logger.debug("newOriFC: " + newOriFC + " newUpFC: " + newUpFC
                        + " newOriMatchableFC: " + newOriMatchableFC
                        + " newUpMatchableFC: " + newUpMatchableFC);
                newFCVD = newOriMatchableFC ^ newUpMatchableFC;
            }

            graph.updateNodeCoverage(testPacketID,
                    newOriBC, newUpgradeBC, newUpBC,
                    newDowngradeBC, newOriFC, newUpFC,
                    newOriMatchableFC, newUpMatchableFC);

            if (versionDeltaFeedbackPacketUp.isInconsistent) {
                if (failureDir == null) {
                    failureDir = createFailureDirAndSaveFullSequence(
                            configFileName, fullSequence);
                }
                saveInconsistencyReport(failureDir,
                        testPacketID,
                        versionDeltaFeedbackPacketUp.inconsistencyReport);
            }

            assert corpus instanceof CorpusVersionDeltaFiveQueueWithBoundary;
            corpus.addSeed(testID2Seed.get(testPacketID),
                    newOriBC, newUpBC, newOriFC, newUpFC,
                    newUpgradeBC, newDowngradeBC,
                    newOriBoundaryChange, newUpBoundaryChange, false, newBCVD,
                    newFCVD);
        }
        // update testId2Seed
        for (int testID : versionDeltaFeedbackPacket.stackedFeedbackPacketUpgrade.testIDs) {
            testID2Seed.remove(testID);
        }

        // process upgrade failure report
        if (versionDeltaFeedbackPacket.stackedFeedbackPacketUpgrade.hasERRORLog) {
            if (failureDir == null)
                failureDir = createFailureDirAndSaveFullSequence(
                        configFileName, fullSequence);
            saveErrorReport(failureDir,
                    versionDeltaFeedbackPacket.stackedFeedbackPacketUpgrade.errorLogReport,
                    startTestID,
                    endTestID, true);
        }
        if (versionDeltaFeedbackPacket.stackedFeedbackPacketDowngrade.hasERRORLog) {
            if (failureDir == null) {
                failureDir = createFailureDirAndSaveFullSequence(
                        configFileName, fullSequence);
            }
            saveErrorReport(failureDir,
                    versionDeltaFeedbackPacket.stackedFeedbackPacketDowngrade.errorLogReport,
                    startTestID,
                    endTestID, false);
        }
        printInfo();
        System.out.println();
    }

    // Two Group VD: G1
    public synchronized void analyzeFeedbackFromVersionDeltaGroup1(
            VersionDeltaFeedbackPacketApproach2 versionDeltaFeedbackPacket) {
        int startTestID = 0;
        int endTestID = 0;
        if (versionDeltaFeedbackPacket.stackedFeedbackPacketUpgrade.getFpList()
                .size() > 0) {
            startTestID = versionDeltaFeedbackPacket.stackedFeedbackPacketUpgrade
                    .getFpList()
                    .get(0).testPacketID;
            endTestID = versionDeltaFeedbackPacket.stackedFeedbackPacketUpgrade
                    .getFpList()
                    .get(versionDeltaFeedbackPacket.stackedFeedbackPacketUpgrade
                            .getFpList().size()
                            - 1).testPacketID;
        }

        FuzzingServerHandler.printClientNum();

        List<FeedbackPacket> versionDeltaFeedbackPacketsUp = versionDeltaFeedbackPacket.stackedFeedbackPacketUpgrade
                .getFpList();
        List<FeedbackPacket> versionDeltaFeedbackPacketsDown = versionDeltaFeedbackPacket.stackedFeedbackPacketDowngrade
                .getFpList();

        int feedbackLength = versionDeltaFeedbackPacketsUp.size();
        logger.debug("feedback packet num: " + feedbackLength);

        Path failureDir = null;

        for (int i = 0; i < feedbackLength; i++) {
            TestPacket testPacket = versionDeltaFeedbackPacket.tpList.get(i);
            FeedbackPacket versionDeltaFeedbackPacketUp = versionDeltaFeedbackPacketsUp
                    .get(i);
            FeedbackPacket versionDeltaFeedbackPacketDown = versionDeltaFeedbackPacketsDown
                    .get(i);
            finishedTestID++;
            finishedTestIdAgentGroup1++;

            boolean newOriBC = false;
            boolean newUpBC = false;
            boolean newOriFC = false;
            boolean oriBoundaryChange = false;
            boolean newUpFC = false;
            boolean upBoundaryChange = false;

            // Merge all the feedbacks
            FeedBack fbUpgrade = mergeCoverage(
                    versionDeltaFeedbackPacketUp.feedBacks);
            FeedBack fbDowngrade = mergeCoverage(
                    versionDeltaFeedbackPacketDown.feedBacks);

            // priority feature is disabled
            // logger.info("Checking new bits for upgrade feedback");
            if (Utilities.hasNewBits(
                    curOriCoverage,
                    fbUpgrade.originalCodeCoverage)) {
                // Write Seed to Disk + Add to Corpus
                curOriCoverage.merge(
                        fbUpgrade.originalCodeCoverage);
                newOriBC = true;
            }
            // logger.info("Checking new bits for downgrade feedback");
            if (Utilities.hasNewBits(curUpCoverage,
                    fbDowngrade.originalCodeCoverage)) {
                curUpCoverage.merge(
                        fbDowngrade.originalCodeCoverage);
                newUpBC = true;
            }

            // format coverage
            if (Config.getConf().useFormatCoverage) {
                logger.debug("Check ori format coverage");
                FormatCoverageStatus oriFormatCoverageStatus = oriObjCoverage
                        .merge(
                                versionDeltaFeedbackPacketUp.formatCoverage,
                                "ori",
                                versionDeltaFeedbackPacketUp.testPacketID,
                                true,
                                Config.getConf().updateInvariantBrokenFrequency,
                                Config.getConf().checkSpecialDumpIds);

                if (oriFormatCoverageStatus.isNewFormat())
                    newOriFC = true;
                if (oriFormatCoverageStatus.isBoundaryChange())
                    oriBoundaryChange = true;

                logger.debug("Check ori format coverage done");

                logger.debug("Check up format coverage");
                FormatCoverageStatus upFormatCoverageStatus = upObjCoverage
                        .merge(
                                versionDeltaFeedbackPacketDown.formatCoverage,
                                "up",
                                versionDeltaFeedbackPacketDown.testPacketID,
                                true,
                                Config.getConf().updateInvariantBrokenFrequency,
                                Config.getConf().checkSpecialDumpIds);
                if (upFormatCoverageStatus.isNewFormat())
                    newUpFC = true;
                if (upFormatCoverageStatus.isBoundaryChange())
                    upBoundaryChange = true;
                logger.debug("Check up format coverage done");
            }

            boolean hasFeedbackInducedBranchVersionDelta = newOriBC
                    ^ newUpBC;
            boolean hasFeedbackInducedFormatVersionDelta = newOriFC
                    ^ newUpFC;
            boolean hasFeedbackInducedNewBranchCoverage = newOriBC
                    || newUpBC;
            boolean hasFeedbackInducedNewFormatCoverage = newOriFC
                    || newUpFC;
            boolean hasFeedbackInducedNewBrokenBoundary = upBoundaryChange
                    || oriBoundaryChange;

            if (hasFeedbackInducedFormatVersionDelta) {
                testBatchCorpus.addPacket(testPacket,
                        InterestingTestsCorpus.TestType.FORMAT_COVERAGE_VERSION_DELTA,
                        versionDeltaFeedbackPacket.stackedFeedbackPacketUpgrade.configFileName);
                formatVersionDeltaInducedTpIds
                        .add(versionDeltaFeedbackPacket.tpList
                                .get(i).testPacketID);
            } else if (hasFeedbackInducedBranchVersionDelta) {
                testBatchCorpus.addPacket(testPacket,
                        InterestingTestsCorpus.TestType.BRANCH_COVERAGE_VERSION_DELTA,
                        versionDeltaFeedbackPacket.stackedFeedbackPacketUpgrade.configFileName);
                branchVersionDeltaInducedTpIds
                        .add(versionDeltaFeedbackPacket.tpList
                                .get(i).testPacketID);
            } else if (hasFeedbackInducedNewFormatCoverage) {
                testBatchCorpus.addPacket(testPacket,
                        InterestingTestsCorpus.TestType.FORMAT_COVERAGE,
                        versionDeltaFeedbackPacket.stackedFeedbackPacketUpgrade.configFileName);
                onlyNewFormatCoverageInducedTpIds.add(
                        versionDeltaFeedbackPacket.tpList
                                .get(i).testPacketID);
            } else if (hasFeedbackInducedNewBrokenBoundary) {
                testBatchCorpus.addPacket(testPacket,
                        InterestingTestsCorpus.TestType.BOUNDARY_BROKEN,
                        versionDeltaFeedbackPacket.stackedFeedbackPacketDowngrade.configFileName);
            } else if (hasFeedbackInducedNewBranchCoverage) {
                testBatchCorpus.addPacket(testPacket,
                        InterestingTestsCorpus.TestType.BRANCH_COVERAGE_BEFORE_VERSION_CHANGE,
                        versionDeltaFeedbackPacket.stackedFeedbackPacketDowngrade.configFileName);
                onlyNewBranchCoverageInducedTpIds.add(
                        versionDeltaFeedbackPacket.tpList
                                .get(i).testPacketID);
            } else {
                if (addNonInterestingTestsToBuffer(rand.nextDouble(),
                        Config.getConf().DROP_TEST_PROB_G2)) {
                    if (Config.getConf().debug) {
                        logger.info("non interesting test packet "
                                + testPacket.testPacketID
                                + " chosen to be upgraded");
                    }
                    testBatchCorpus.addPacket(testPacket,
                            InterestingTestsCorpus.TestType.LOW_PRIORITY,
                            versionDeltaFeedbackPacket.stackedFeedbackPacketDowngrade.configFileName);
                } else {
                    if (Config.getConf().debug) {
                        logger.info("non interesting test packet "
                                + testPacket.testPacketID
                                + " will not be upgraded");
                    }
                }
                nonInterestingTpIds.add(
                        versionDeltaFeedbackPacket.tpList
                                .get(i).testPacketID);
            }

            corpus.addSeed(
                    testID2Seed.get(versionDeltaFeedbackPacketUp.testPacketID),
                    newOriBC, newUpBC, newOriFC, newUpFC, false, false,
                    oriBoundaryChange, upBoundaryChange, false,
                    newOriBC ^ newUpBC, newOriFC ^ newUpFC);

            graph.updateNodeCoverageGroup1(
                    versionDeltaFeedbackPacketUp.testPacketID,
                    newOriBC, newUpBC, newOriFC, newUpFC);
        }

        if (versionDeltaFeedbackPacket.stackedFeedbackPacketUpgrade.hasERRORLog) {
            if (failureDir == null) {
                failureDir = createFailureDirAndSaveFullSequence(
                        versionDeltaFeedbackPacket.stackedFeedbackPacketUpgrade.configFileName,
                        versionDeltaFeedbackPacket.stackedFeedbackPacketUpgrade.fullSequence);
            }
            saveErrorReport(failureDir,
                    versionDeltaFeedbackPacket.stackedFeedbackPacketUpgrade.errorLogReport,
                    startTestID,
                    endTestID, true);
        }

        if (versionDeltaFeedbackPacket.stackedFeedbackPacketDowngrade.hasERRORLog) {
            if (failureDir == null)
                failureDir = createFailureDirAndSaveFullSequence(
                        versionDeltaFeedbackPacket.stackedFeedbackPacketDowngrade.configFileName,
                        versionDeltaFeedbackPacket.stackedFeedbackPacketDowngrade.fullSequence);
            saveErrorReport(failureDir,
                    versionDeltaFeedbackPacket.stackedFeedbackPacketDowngrade.errorLogReport,
                    startTestID,
                    endTestID, false);
        }

        Integer[] branchVersionDeltaInducedArray = branchVersionDeltaInducedTpIds
                .toArray(new Integer[0]);
        Integer[] formatVersionDeltaInducedArray = formatVersionDeltaInducedTpIds
                .toArray(new Integer[0]);
        Integer[] branchCoverageInducedArray = onlyNewBranchCoverageInducedTpIds
                .toArray(new Integer[0]);

        // Print array using toString() method
        System.out.println();

        if (Config.getConf().debug) {
            logger.info("[HKLOG] branch coverage induced in "
                    + java.util.Arrays.toString(branchCoverageInducedArray));
            logger.info("[HKLOG] branch version delta induced in "
                    + java.util.Arrays
                            .toString(branchVersionDeltaInducedArray));
            logger.info("[HKLOG] format version delta induced in "
                    + java.util.Arrays
                            .toString(formatVersionDeltaInducedArray));
        }

        System.out.println();

        if (!testBatchCorpus.configFiles
                .contains(
                        versionDeltaFeedbackPacket.stackedFeedbackPacketUpgrade.configFileName)) {
            testBatchCorpus
                    .addConfigFile(
                            versionDeltaFeedbackPacket.stackedFeedbackPacketDowngrade.configFileName);
        }
        printInfo();
        System.out.println();

        if (Config.getConf().debug) {
            String reportDir = "fullSequences/lessPriority";
            if (formatVersionDeltaInducedTpIds.size() > 0
                    || branchVersionDeltaInducedTpIds.size() > 0) {
                reportDir = "fullSequences/versionDelta";
            }
            if (onlyNewFormatCoverageInducedTpIds.size() > 0) {
                reportDir = "fullSequences/formatCoverage";
            }
            if (onlyNewBranchCoverageInducedTpIds.size() > 0) {
                reportDir = "fullSequences/branchCoverage";
            }
            String reportName = "fullSequence_" + endTestID + ".txt";

            saveFullSequenceBasedOnType(reportDir, reportName,
                    versionDeltaFeedbackPacket.stackedFeedbackPacketUpgrade.fullSequence);
        }
    }

    // Two Group VD: G2
    public synchronized void analyzeFeedbackFromVersionDeltaGroup2(
            VersionDeltaFeedbackPacketApproach2 versionDeltaFeedbackPacket) {
        assert versionDeltaFeedbackPacket.stackedFeedbackPacketDowngrade == null;
        analyzeFeedbackFromVersionDeltaGroup2WithoutDowngrade(
                versionDeltaFeedbackPacket);
    }

    // Two Group VD: G2 without downgrade
    private synchronized void analyzeFeedbackFromVersionDeltaGroup2WithoutDowngrade(
            VersionDeltaFeedbackPacketApproach2 versionDeltaFeedbackPacket) {
        if (versionDeltaFeedbackPacket.stackedFeedbackPacketUpgrade.upgradeSkipped) {
            // upgrade process is skipped
            logger.info("upgrade process is skipped");
        }

        Path failureDir = null;

        int startTestID = 0;
        int endTestID = 0;
        if (versionDeltaFeedbackPacket.stackedFeedbackPacketUpgrade.getFpList()
                .size() > 0) {
            startTestID = versionDeltaFeedbackPacket.stackedFeedbackPacketUpgrade
                    .getFpList()
                    .get(0).testPacketID;
            endTestID = versionDeltaFeedbackPacket.stackedFeedbackPacketUpgrade
                    .getFpList()
                    .get(versionDeltaFeedbackPacket.stackedFeedbackPacketUpgrade
                            .getFpList().size()
                            - 1).testPacketID;
        }

        if (versionDeltaFeedbackPacket.stackedFeedbackPacketUpgrade.isUpgradeProcessFailed) {
            failureDir = createFailureDir(
                    versionDeltaFeedbackPacket.stackedFeedbackPacketUpgrade.configFileName);
            saveFullSequence(failureDir,
                    versionDeltaFeedbackPacket.stackedFeedbackPacketUpgrade.fullSequence);
            saveFullStopCrashReport(failureDir,
                    versionDeltaFeedbackPacket.stackedFeedbackPacketUpgrade.upgradeFailureReport,
                    startTestID,
                    endTestID, true);

            finishedTestID++;
            finishedTestIdAgentGroup2++;
        }

        FuzzingServerHandler.printClientNum();

        List<FeedbackPacket> versionDeltaFeedbackPacketsUp = versionDeltaFeedbackPacket.stackedFeedbackPacketUpgrade
                .getFpList();

        for (FeedbackPacket fp : versionDeltaFeedbackPacketsUp) {
            if (fp.isInconsistencyInsignificant) {
                insignificantInconsistenciesIn.add(fp.testPacketID);
            }
        }

        int feedbackLength = versionDeltaFeedbackPacketsUp.size();
        System.out.println("feedback length: " + feedbackLength);
        for (FeedbackPacket versionDeltaFeedbackPacketUp : versionDeltaFeedbackPacketsUp) {
            finishedTestID++;
            finishedTestIdAgentGroup2++;

            boolean newUpgradeBC = false;

            // Merge all the feedbacks
            FeedBack fbUpgrade = mergeCoverage(
                    versionDeltaFeedbackPacketUp.feedBacks);

            // priority feature is disabled
            if (Utilities.hasNewBits(
                    curUpCoverageAfterUpgrade,
                    fbUpgrade.upgradedCodeCoverage)) {
                // Write Seed to Disk + Add to Corpus
                newUpgradeBC = true;
            }

            curUpCoverageAfterUpgrade.merge(fbUpgrade.upgradedCodeCoverage);

            if (versionDeltaFeedbackPacketUp.isInconsistent) {
                if (failureDir == null) {
                    failureDir = createFailureDirAndSaveFullSequence(
                            versionDeltaFeedbackPacket.stackedFeedbackPacketUpgrade.configFileName,
                            versionDeltaFeedbackPacket.stackedFeedbackPacketUpgrade.fullSequence);
                }
                saveInconsistencyReport(failureDir,
                        versionDeltaFeedbackPacketUp.testPacketID,
                        versionDeltaFeedbackPacketUp.inconsistencyReport);
            }

            corpus.addSeed(
                    testID2Seed.get(versionDeltaFeedbackPacketUp.testPacketID),
                    false, false, newUpgradeBC, false, false);
            // FIXME: it's already updated in Group1, do we update it again in
            // group2?
            graph.updateNodeCoverageGroup2(
                    versionDeltaFeedbackPacketUp.testPacketID,
                    newUpgradeBC, false);

        }
        // update testId2Seed
        for (TestPacket tp : versionDeltaFeedbackPacket.tpList) {
            testID2Seed.remove(tp.testPacketID);
        }
        if (versionDeltaFeedbackPacket.stackedFeedbackPacketUpgrade.hasERRORLog) {
            if (failureDir == null)
                failureDir = createFailureDirAndSaveFullSequence(
                        versionDeltaFeedbackPacket.stackedFeedbackPacketUpgrade.configFileName,
                        versionDeltaFeedbackPacket.stackedFeedbackPacketUpgrade.fullSequence);

            saveErrorReport(failureDir,
                    versionDeltaFeedbackPacket.stackedFeedbackPacketUpgrade.errorLogReport,
                    startTestID,
                    endTestID, true);
        }

        printInfo();
        System.out.println();
    }

    /**
     * FailureIdx
     * - crash
     * - inconsistency
     * - error
     * @return path
     */
    private Path createFailureDir(String configFileName) {
        while (Paths
                .get(Config.getConf().failureDir,
                        "failure_" + failureId)
                .toFile().exists()) {
            failureId++;
        }
        Path failureSubDir = Paths.get(Config.getConf().failureDir,
                "failure_" + failureId++);
        failureSubDir.toFile().mkdir();
        copyConfig(failureSubDir, configFileName);
        return failureSubDir;
    }

    private void copyConfig(Path failureSubDir, String configFileName) {
        if (Config.getConf().debug)
            logger.info("[HKLOG] debug copy config, failureSubDir = "
                    + failureSubDir + " configFile = " + configFileName
                    + " configPath = " + configDirPath);
        if (configFileName == null || configFileName.isEmpty())
            return;
        Path configPath = Paths.get(configDirPath.toString(), configFileName);
        try {
            FileUtils.copyDirectory(configPath.toFile(),
                    failureSubDir.toFile());
        } catch (IOException e) {
            logger.error("config file not exist with exception: " + e);
        }
    }

    private Path createFailureSubDir(Path failureSubDir, String subDirName) {
        Path dir = failureSubDir.resolve(subDirName);
        dir.toFile().mkdir();
        return dir;
    }

    private void saveFullSequence(Path failureDir,
            String fullSequence) {
        long timeElapsed = TimeUnit.SECONDS.convert(
                System.nanoTime(), TimeUnit.NANOSECONDS) - startTime;
        Path crashReportPath = Paths.get(
                failureDir.toString(),
                String.format("fullSequence_%d.report", timeElapsed));
        Utilities.write2TXT(crashReportPath.toFile(), fullSequence, false);
    }

    private void saveFullSequenceBasedOnType(String storageDir,
            String reportName,
            String fullSequence) {

        File storage = new File(storageDir);
        if (!storage.exists()) {
            storage.mkdirs();
        }

        Path fullSequenceReportPath = Paths.get(
                storageDir.toString(),
                reportName);
        Utilities.write2TXT(fullSequenceReportPath.toFile(), fullSequence,
                false);
    }

    private void saveFullStopCrashReport(Path failureDir,
            String report, int startTestID) {
        Path subDir = createFailureSubDir(failureDir, "fullstop_crash");
        Path crashReportPath = Paths.get(
                subDir.toString(),
                String.format("fullstop_%d_crash.report", startTestID));
        Utilities.write2TXT(crashReportPath.toFile(), report, false);
        fullStopCrashNum++;
    }

    private void saveFullStopCrashReport(Path failureDir,
            String report, int startTestID, int endTestID) {
        Path subDir = createFailureSubDir(failureDir, "fullstop_crash");
        Path crashReportPath = Paths.get(
                subDir.toString(),
                String.format("fullstop_%d_%d_crash.report", startTestID,
                        endTestID));
        Utilities.write2TXT(crashReportPath.toFile(), report, false);
        fullStopCrashNum++;
    }

    private void saveFullStopCrashReport(Path failureDir,
            String report, int startTestID, int endTestID, boolean isUpgrade) {
        Path subDir = createFailureSubDir(failureDir, "fullstop_crash");
        Path crashReportPath = Paths.get(
                subDir.toString(),
                String.format("fullstop_%d_%d_%s_crash.report", startTestID,
                        endTestID, isUpgrade ? "upgrade" : "downgrade"));
        Utilities.write2TXT(crashReportPath.toFile(), report, false);
        fullStopCrashNum++;
    }

    private void saveEventCrashReport(Path failureDir, int testID,
            String report) {
        Path subDir = createFailureSubDir(failureDir, "event_crash");
        Path crashReportPath = Paths.get(
                subDir.toString(),
                "event_crash_" + testID + ".report");
        Utilities.write2TXT(crashReportPath.toFile(), report, false);
        eventCrashNum++;
    }

    private void saveTestExecutionTimeoutReport(Path failureDir,
            String report, int startTestID, int endTestID) {
        Path testExecutionTimeoutSubDir = createFailureSubDir(failureDir,
                "exec_timeout");
        Path crashReportPath = Paths.get(
                testExecutionTimeoutSubDir.toString(),
                "exec_timeout_" + startTestID + "_" + endTestID + ".report");
        Utilities.write2TXT(crashReportPath.toFile(), report, false);
        testExecutionTimeoutNum++;
    }

    private void saveTestExecutionFailedWithOtherExceptionReport(
            Path failureDir,
            String report, int startTestID, int endTestID) {
        Path testExecutionFailedWithOtherExceptionSubDir = createFailureSubDir(
                failureDir, "exec_failed_with_other_exception");
        Path crashReportPath = Paths.get(
                testExecutionFailedWithOtherExceptionSubDir.toString(),
                "exec_failed_with_other_exception_" + testID + ".report");
        Utilities.write2TXT(crashReportPath.toFile(), report, false);
        testExecutionFailedWithOtherExceptionNum++;
    }

    private void saveInconsistencyReport(Path failureDir, int testID,
            String report) {
        Path inconsistencySubDir = createFailureSubDir(failureDir,
                "inconsistency");
        Path crashReportPath = Paths.get(
                inconsistencySubDir.toString(),
                "inconsistency_" + testID + ".report");
        Utilities.write2TXT(crashReportPath.toFile(), report, false);
        inconsistencyNum++;
    }

    private void saveErrorReport(Path failureDir, String report, int testID) {
        Path errorSubDir = createFailureSubDir(failureDir, "errorLog");
        Path reportPath = Paths.get(
                errorSubDir.toString(),
                String.format("error_%d.report", testID));
        Utilities.write2TXT(reportPath.toFile(), report, false);
        errorLogNum++;
    }

    // For version delta, since might need two error log files
    private void saveErrorReport(Path failureDir, String report,
            int startTestID, int endTestID, boolean isUpgrade) {
        Path errorSubDir = createFailureSubDir(failureDir, "errorLog");
        Path reportPath = Paths.get(
                errorSubDir.toString(),
                String.format("error_%d_%d_%s.report", startTestID, endTestID,
                        isUpgrade ? "upgrade" : "downgrade"));
        Utilities.write2TXT(reportPath.toFile(), report, false);
        errorLogNum++;
    }

    private void saveErrorReport(Path failureDir, String report,
            int startTestID, int endTestID) {
        Path errorSubDir = createFailureSubDir(failureDir, "errorLog");
        Path reportPath = Paths.get(
                errorSubDir.toString(),
                String.format("error_%d_%d.report", startTestID, endTestID));
        Utilities.write2TXT(reportPath.toFile(), report, false);
        errorLogNum++;
    }

    public void printInfo() {
        updateBCStatus();

        long timeElapsed = TimeUnit.SECONDS.convert(
                System.nanoTime(), TimeUnit.NANOSECONDS) - startTime;

        System.out.println("--------------------------------------------------"
                +
                " TestStatus ---------------------------------------------------------------");
        System.out.println("System: " + Config.getConf().system);
        if (Config.getConf().testSingleVersion) {
            System.out.println(
                    "Test single version: " + Config.getConf().originalVersion);
        } else {
            System.out.println("Upgrade Testing: "
                    + Config.getConf().originalVersion + "=>"
                    + Config.getConf().upgradedVersion);
        }
        System.out.println(
                "============================================================"
                        + "=================================================================");
        System.out.format("|%30s|%30s|%30s|%30s|\n",
                "cur testID : " + testID,
                "total exec : " + finishedTestID,
                "skipped upgrade : " + skippedUpgradeNum,
                "");

        if (Config.getConf().testSingleVersion) {
            System.out.format("|%30s|%30s|\n",
                    "run time : " + timeElapsed + "s",
                    "BC : " + oriCoveredBranches + "/"
                            + oriProbeNum);
        } else {
            System.out.format("|%30s|%30s|%30s|%30s|\n",
                    "run time : " + timeElapsed + "s",
                    "round : " + round,
                    "ori BC : " + oriCoveredBranches + "/"
                            + oriProbeNum,
                    "up BC upgrade : " + upCoveredBranchesAfterUpgrade
                            + "/"
                            + upProbeNumAfterUpgrade);
        }
        // Print queue info...
        corpus.printInfo();

        if (Config.getConf().useFormatCoverage) {
            if (Config.getConf().staticVD) {
                System.out.format("|%30s|%30s|%30s|%30s|\n",
                        "format num : " + newFormatCount,
                        "vd-format num : " + nonMatchableNewFormatCount,
                        "vd-multi-inv num : " + nonMatchableMultiInvCount,
                        "");
            } else {
                System.out.format("|%30s|%30s|%30s|%30s|\n",
                        "format num : " + newFormatCount,
                        "",
                        "",
                        "");
            }
        }
        if (Config.getConf().useVersionDelta
                && Config.getConf().versionDeltaApproach == 2) {
            testBatchCorpus.printInfo();
        }

        // Version Delta Info
        if (Config.getConf().useVersionDelta) {
            System.out.format("|%30s|%30s|%30s|%30s|\n",
                    "exec group 1 : " + finishedTestIdAgentGroup1,
                    "exec group 2 : " + finishedTestIdAgentGroup2,
                    "up BC : " + upCoveredBranches + "/"
                            + upProbeNum,
                    "ori BC downgrade : "
                            + oriCoveredBranchesAfterDowngrade + "/"
                            + oriProbeNumAfterDowngrade);
        }

        // Differential Execution Info
        if (Config.getConf().differentialExecution) {
            System.out.format("|%30s|%30s|\n",
                    "Jaccard threshold : "
                            + Config.getConf().jaccardSimilarityThreshold,
                    "testPlanCorpus : " + testPlanCorpus.queue.size());
        }

        System.out.println(
                "------------------------------------------------------------"
                        + "-----------------------------------------------------------------");
        // Failures
        System.out.format("|%30s|%30s|%30s|%30s|\n",
                "fullstop crash : " + fullStopCrashNum,
                "event crash : " + eventCrashNum,
                "inconsistency : " + inconsistencyNum,
                "error log : " + errorLogNum);
        System.out.println(
                "------------------------------------------------------------"
                        + "-----------------------------------------------------------------");
        if (Config.getConf().staticVD && finishedTestID
                % Config.getConf().staticVDMeasureInterval == 0) {
            oriObjCoverage.measureCoverageOfModifiedReferences(
                    modifiedSerializedFields, true);
        }
        System.out.println();
    }

    public static List<Event> interleaveFaultAndUpgradeOp(
            List<Pair<Fault, FaultRecover>> faultPairs,
            List<Event> upgradeOps) {
        // Upgrade op can happen with fault
        // E.g. isolate node1 -> upgrade node1 -> recover node1
        List<Event> upgradeOpAndFaults = new LinkedList<>(upgradeOps);
        for (Pair<Fault, FaultRecover> faultPair : faultPairs) {
            int pos1 = rand.nextInt(upgradeOpAndFaults.size() + 1);
            upgradeOpAndFaults.add(pos1, faultPair.left);
            int pos2 = Utilities.randWithRange(pos1 + 1,
                    upgradeOpAndFaults.size() + 1);
            if (faultPair.left instanceof NodeFailure) {
                // the recover must be in the front of node upgrade
                int nodeIndex = ((NodeFailure) faultPair.left).nodeIndex;
                int nodeUpgradePos = 0;
                for (; nodeUpgradePos < upgradeOpAndFaults
                        .size(); nodeUpgradePos++) {
                    if (upgradeOpAndFaults
                            .get(nodeUpgradePos) instanceof UpgradeOp
                            && ((UpgradeOp) upgradeOpAndFaults.get(
                                    nodeUpgradePos)).nodeIndex == nodeIndex) {
                        break;
                    }
                }
                assert nodeUpgradePos != pos1;
                if (nodeUpgradePos > pos1
                        && nodeUpgradePos < upgradeOpAndFaults.size()) {
                    if (faultPair.right == null) {
                        upgradeOpAndFaults.remove(nodeUpgradePos);
                        continue;
                    }
                    pos2 = Utilities.randWithRange(pos1 + 1,
                            nodeUpgradePos + 1);
                }
            }
            if (faultPair.right != null)
                upgradeOpAndFaults.add(pos2, faultPair.right);
        }
        return upgradeOpAndFaults;
    }

    public static int getSeedOrTestType(double[] cumulativeProbabilities) {
        // Generate a random number between 0 and 1
        double randomValue = rand.nextDouble();

        // Find the queue whose cumulative probability is greater than or equal
        // to the random value
        for (int i = 0; i < cumulativeProbabilities.length; i++) {
            if (randomValue <= cumulativeProbabilities[i]) {
                return i;
            }
        }

        // Should not reach here if probabilities are valid
        throw new IllegalStateException("Invalid probabilities");
    }

    public int getNextBestTestType(Map<Integer, Double> probabilities,
            String configFileName) {
        List<Map.Entry<Integer, Double>> sortedProbabilities = new ArrayList<>(
                probabilities.entrySet());
        sortedProbabilities.sort((entry1, entry2) -> Double
                .compare(entry2.getValue(), entry1.getValue()));

        // Iterate through sorted probabilities
        for (Map.Entry<Integer, Double> entry : sortedProbabilities) {
            int elementIndex = entry.getKey();
            if (!testBatchCorpus.isEmpty(InterestingTestsCorpus.TestType
                    .values()[elementIndex])
                    && testBatchCorpus.intermediateBuffer[elementIndex]
                            .containsKey(configFileName)) {
                return elementIndex; // Return the index of the non-empty
                                     // list which has a key for the
                                     // configFileName
            }
        }
        return -1;
    }

    public static List<Event> interleaveWithOrder(List<Event> events1,
            List<Event> events2) {
        // Merge two lists but still maintain the inner order
        // Prefer to execute events2 first. Not uniform distribution
        List<Event> events = new LinkedList<>();

        int size1 = events1.size();
        int size2 = events2.size();
        int totalEventSize = size1 + size2;
        int upgradeOpAndFaultsIdx = 0;
        int commandIdx = 0;
        for (int i = 0; i < totalEventSize; i++) {
            // Magic Number: Prefer to execute commands first
            // Also make the commands more separate
            if (Utilities.oneOf(rand, 3)) {
                if (upgradeOpAndFaultsIdx < events1.size())
                    events.add(events1.get(upgradeOpAndFaultsIdx++));
                else
                    break;
            } else {
                if (commandIdx < size2)
                    events.add(events2
                            .get(commandIdx++));
                else
                    break;
            }
        }
        if (upgradeOpAndFaultsIdx < size1) {
            for (int i = upgradeOpAndFaultsIdx; i < size1; i++) {
                events.add(events1.get(i));
            }
        } else if (commandIdx < size2) {
            for (int i = commandIdx; i < size2; i++) {
                events.add(events2.get(i));
            }
        }
        return events;
    }

    public synchronized boolean addNonInterestingTestsToBuffer(
            double randomNumber, double probabilityThreshold) {
        return randomNumber >= probabilityThreshold;
    }

    /**
     * 1. find a position after the first upgrade operation
     * 2. collect all upgrade op node idx between [first_upgrade, pos]
     * 3. remove all the upgrade op after it
     * 4. downgrade all nodeidx collected
     */
    public static List<Event> addDowngrade(List<Event> events) {
        // Add downgrade during the upgrade/when all nodes have been upgraded.
        List<Event> newEvents;
        // find first upgrade op
        int pos1 = 0;
        for (; pos1 < events.size(); pos1++) {
            if (events.get(pos1) instanceof UpgradeOp) {
                break;
            }
        }
        if (pos1 == events.size()) {
            throw new RuntimeException(
                    "no nodes are upgraded, cannot downgrade");
        }
        int pos2 = Utilities.randWithRange(pos1 + 1, events.size() + 1);

        newEvents = events.subList(0, pos2);
        assert newEvents.size() == pos2;

        List<Integer> upgradeNodeIdxes = new LinkedList<>();
        for (int i = pos1; i < pos2; i++) {
            if (newEvents.get(i) instanceof UpgradeOp)
                upgradeNodeIdxes.add(((UpgradeOp) newEvents.get(i)).nodeIndex);
        }

        // downgrade in a reverse way
        upgradeNodeIdxes.sort(Collections.reverseOrder());
        // logger.info("upgrade = " + upgradeNodeIdxes);
        for (int nodeIdx : upgradeNodeIdxes) {
            newEvents.add(new DowngradeOp(nodeIdx));
        }
        return newEvents;
    }
}
