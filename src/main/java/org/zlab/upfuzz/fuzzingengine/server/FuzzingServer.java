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
import org.zlab.net.tracker.CanonicalKeyMode;
import org.zlab.net.tracker.Trace;
import org.zlab.net.tracker.TraceEntry;
import org.zlab.net.tracker.diff.DiffComputeCompressedOrder;
import org.zlab.net.tracker.diff.DiffComputeMessageTriDiff;
import org.zlab.net.tracker.diff.DiffComputeSemanticSimilarity;
import org.zlab.upfuzz.fuzzingengine.trace.TraceWindow;
import org.zlab.upfuzz.fuzzingengine.trace.WindowedTrace;
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
import org.zlab.upfuzz.fuzzingengine.server.observability.AdmissionReason;
import org.zlab.upfuzz.fuzzingengine.server.observability.AdmissionSummaryRow;
import org.zlab.upfuzz.fuzzingengine.server.observability.ObservabilityMetrics;
import org.zlab.upfuzz.fuzzingengine.server.observability.WindowTriggerRow;
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
    public static final String EXAMPLE_ORACLE_SKIP_TOKEN = "__UPFUZZ_ORACLE_SKIP__";

    int fixedTestIndex = 0;

    // Target system
    public CommandPool commandPool;
    public Executor executor;
    public Class<? extends State> stateClass;

    // Corpus
    public Corpus corpus;

    public TestPlanCorpus testPlanCorpus = new TestPlanCorpus();
    public FullStopCorpus fullStopCorpus = new FullStopCorpus();
    public RollingSeedCorpus rollingSeedCorpus;
    public final RecentTraceSignatureIndex traceSignatureIndex;

    // Phase 0 observability
    public final ObservabilityMetrics observabilityMetrics;

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

    // Differential lane collection/wait outcomes
    public static int oldOldLaneTimeoutNum = 0;
    public static int rollingLaneTimeoutNum = 0;
    public static int newNewLaneTimeoutNum = 0;
    public static int oldOldLaneCollectionFailureNum = 0;
    public static int rollingLaneCollectionFailureNum = 0;
    public static int newNewLaneCollectionFailureNum = 0;

    // Verdict-aware counters (WS0)
    public static int candidateNum = 0;
    public static int sameVersionBugNum = 0;
    public static int noiseNum = 0;

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

        // Phase 0: measurement artifacts land under
        // <failureDir>/observability/.
        // Immediately truncate-to-headers so a fresh run does not leak stale
        // rows from a previous run sharing the same failureDir.
        Path observabilityDir = Paths
                .get(Config.getConf().failureDir == null ? "failure"
                        : Config.getConf().failureDir)
                .resolve("observability");
        this.observabilityMetrics = new ObservabilityMetrics(observabilityDir);
        this.observabilityMetrics
                .setEnabled(Config.getConf().enableObservabilityArtifacts);
        this.observabilityMetrics.writeAllArtifacts();

        // Phase 2: construct the tiered rolling corpus from the current
        // Config snapshot. The long-term pools and admission caps depend on
        // these knobs, so the corpus cannot be safely initialized at field
        // declaration time (before Config is loaded by Main).
        this.rollingSeedCorpus = new RollingSeedCorpus(
                Config.getConf().rollingCorpusMaxSize,
                Config.getConf().traceOnlyCorpusMaxShare,
                Config.getConf().traceOnlyAdmissionCapPerRound,
                Config.getConf().traceOnlyAdmissionCapPer100Rounds,
                Config.getConf().traceOnlyProbationRounds,
                Config.getConf().traceProbationMaxSelectionsWithoutPayoff,
                Config.getConf().traceProbationRediscoveryThreshold,
                Config.getConf().branchBackedSelectionWeight,
                new java.util.Random(Config.getConf().seed ^ 0xC0FFEEL));
        this.traceSignatureIndex = new RecentTraceSignatureIndex(
                Config.getConf().traceSignatureSaturationThreshold,
                Config.getConf().traceSignatureLookbackRounds,
                Config.getConf().traceSignatureIndexCapacity);

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
        } else if (Config.getConf().testingMode == 5
                || Config.getConf().testingMode == 6) {
            // Only test rolling upgrade using test plans
            if (testPlanPackets.isEmpty()) {
                if (!fuzzRollingTestPlan()) {
                    if (!bootstrapRollingSeedCorpusForMode5()) {
                        throw new RuntimeException(
                                "Mode 5 bootstrap failed: unable to generate rolling seed");
                    }

                    if (!fuzzRollingTestPlan() || testPlanPackets.isEmpty()) {
                        throw new RuntimeException(
                                "Mode 5 bootstrap succeeded but failed to generate any rolling test plan");
                    }
                }
            }
            assert !testPlanPackets.isEmpty();
            return testPlanPackets.poll();
        }
        throw new RuntimeException(
                String.format("testing Mode [%d] is not in correct scope",
                        Config.getConf().testingMode));
    }

    private boolean bootstrapRollingSeedCorpusForMode5() {
        logger.info(
                "Mode 5 bootstrap: generating rolling seed for differential rolling-upgrade execution");

        Seed seed = corpus.getSeed();
        if (seed == null) {
            int configIdx = configGen.generateConfig();
            int maxGenLimit = 100;
            int genCount = 0;
            while (seed == null && genCount < maxGenLimit) {
                seed = Seed.generateSeed(commandPool, stateClass,
                        configIdx, testID);
                genCount++;
            }

            if (seed == null) {
                logger.warn(
                        "Mode 5 bootstrap failed: seed generation exhausted {} attempts",
                        maxGenLimit);
                return false;
            }
        }

        if (seed.originalCommandSequence == null
                || seed.validationCommandSequence == null) {
            logger.warn(
                    "Mode 5 bootstrap failed: generated seed has null command sequence");
            return false;
        }

        rollingSeedCorpus.addSeed(new RollingSeed(seed, new LinkedList<>()));
        logger.info(
                "Mode 5 bootstrap imported 1 generated seed into rollingSeedCorpus");
        return true;
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
            FullStopSeed fullStopSeed = fullStopCorpus.getSeed();
            if (fullStopSeed == null)
                return false;
            return generateAndEnqueueTestPlansFromFullStopSeed(fullStopSeed,
                    MAX_MUTATION_RETRY);
        }

        return mutateAndEnqueueExistingTestPlan(testPlan);
    }

    private boolean fuzzRollingTestPlan() {
        int MAX_MUTATION_RETRY = 50;
        TestPlan testPlan = testPlanCorpus.getTestPlan();

        if (testPlan == null) {
            RollingSeed rollingSeed = rollingSeedCorpus.getSeed();
            if (rollingSeed == null)
                return false;
            return generateAndEnqueueTestPlansFromRollingSeed(rollingSeed,
                    MAX_MUTATION_RETRY);
        }

        return mutateAndEnqueueExistingTestPlan(testPlan);
    }

    private boolean mutateAndEnqueueExistingTestPlan(TestPlan testPlan) {
        boolean anyEnqueued = false;
        int parentLineageId = testPlan.lineageTestId;
        if (parentLineageId >= 0) {
            observabilityMetrics.recordParentSelection(parentLineageId);
        }
        for (int i = 0; i < Config.getConf().testPlanMutationEpoch; i++) {
            TestPlan mutateTestPlan = null;
            int j = 0;
            for (; j < Config.getConf().testPlanMutationRetry; j++) {
                mutateTestPlan = SerializationUtils.clone(testPlan);
                boolean mutationSuccess;
                try {
                    mutationSuccess = mutateTestPlan.mutate(commandPool,
                            stateClass);
                } catch (Exception | AssertionError e) {
                    logger.error(
                            "Unexpected error during test plan mutation, skipping attempt",
                            e);
                    continue;
                }
                if (!mutationSuccess) {
                    continue;
                }
                if (testPlanVerifier(mutateTestPlan.getEvents(),
                        testPlan.nodeNum)) {
                    break;
                }
            }
            // This epoch exhausted its retry budget; skip to next epoch
            if (j == Config.getConf().testPlanMutationRetry)
                continue;
            // Phase 0: the mutated child inherits the parent's lineage but
            // will only get its own lineageTestId when it is admitted.
            mutateTestPlan.lineageTestId = -1;
            testID2TestPlan.put(testID, mutateTestPlan);

            if (parentLineageId >= 0) {
                observabilityMetrics.linkChildToParent(testID, parentLineageId);
            }

            int configIdx = configGen.generateConfig();
            String configFileName = "test" + configIdx;

            testPlanPackets.add(new TestPlanPacket(
                    Config.getConf().system,
                    testID++, configFileName, mutateTestPlan));
            anyEnqueued = true;
        }
        return anyEnqueued;
    }

    private boolean generateAndEnqueueTestPlansFromFullStopSeed(
            FullStopSeed fullStopSeed,
            int maxMutationRetry) {
        TestPlan testPlan = null;
        for (int i = 0; i < Config.getConf().testPlanGenerationNum; i++) {
            for (int j = 0; j < maxMutationRetry; j++) {
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

    private boolean generateAndEnqueueTestPlansFromRollingSeed(
            RollingSeed rollingSeed,
            int maxMutationRetry) {
        int parentLineageId = rollingSeed != null ? rollingSeed.lineageTestId
                : -1;
        if (parentLineageId >= 0) {
            observabilityMetrics.recordParentSelection(parentLineageId);
        }
        TestPlan testPlan = null;
        for (int i = 0; i < Config.getConf().testPlanGenerationNum; i++) {
            for (int j = 0; j < maxMutationRetry; j++) {
                testPlan = generateTestPlan(rollingSeed);
                if (testPlan != null) {
                    break;
                }
            }
            if (testPlan == null)
                return false;

            testPlan.lineageTestId = -1;
            testID2TestPlan.put(testID, testPlan);

            if (parentLineageId >= 0) {
                observabilityMetrics.linkChildToParent(testID, parentLineageId);
            }

            int configIdx = configGen.generateConfig();
            String configFileName = "test" + configIdx;

            testPlanPackets.add(new TestPlanPacket(
                    Config.getConf().system,
                    testID++, configFileName, testPlan));
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
        int configIdx = Config.getConf().fixedConfigIdx >= 0
                ? Config.getConf().fixedConfigIdx
                : configGen.generateConfig();
        if (Config.getConf().fixedConfigIdx >= 0) {
            logger.info(
                    "use fixed config index for example test plan replay: test{}",
                    configIdx);
        }
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
        List<String> validationReadResultsOracle = loadExampleOracle(
                commandPath, validcommands);

        return new TestPlan(nodeNum, events, validcommands,
                validationReadResultsOracle);
    }

    private List<String> loadExampleOracle(Path commandPath,
            List<String> validationCommands) {
        String oracleFileName = "validation_oracle.txt";
        if ("hdfs".equals(Config.getConf().system)) {
            oracleFileName = "validation_oracle_hdfs_example.txt";
        }
        Path oraclePath = commandPath.resolve(oracleFileName);
        if (!oraclePath.toFile().exists()) {
            return new LinkedList<>();
        }

        int commandNum = validationCommands == null ? 0
                : validationCommands.size();
        List<String> oracle = new ArrayList<>(
                Collections.nCopies(commandNum, EXAMPLE_ORACLE_SKIP_TOKEN));
        int loadedEntries = 0;

        try (BufferedReader br = new BufferedReader(
                new FileReader(oraclePath.toFile()))) {
            String line;
            while ((line = br.readLine()) != null) {
                if (line.trim().isEmpty()) {
                    continue;
                }
                String[] parts = line.split("\t", 2);
                if (parts.length != 2) {
                    logger.warn(
                            "Skip malformed oracle entry in {}: {}",
                            oraclePath, line);
                    continue;
                }
                int idx;
                try {
                    idx = Integer.parseInt(parts[0].trim());
                } catch (NumberFormatException e) {
                    logger.warn(
                            "Skip malformed oracle index in {}: {}",
                            oraclePath, line);
                    continue;
                }
                if (idx < 0 || idx >= oracle.size()) {
                    logger.warn(
                            "Skip out-of-range oracle index {} in {} (size={})",
                            idx, oraclePath, oracle.size());
                    continue;
                }
                String decoded;
                try {
                    decoded = new String(Base64.getDecoder()
                            .decode(parts[1].trim()));
                } catch (IllegalArgumentException e) {
                    logger.warn(
                            "Skip malformed oracle payload in {}: {}",
                            oraclePath, line);
                    continue;
                }
                oracle.set(idx, decoded);
                loadedEntries++;
            }
        } catch (IOException e) {
            logger.warn("Failed reading example oracle file {}", oraclePath, e);
            return new LinkedList<>();
        }
        if (loadedEntries == 0) {
            return new LinkedList<>();
        }
        logger.info("Loaded example oracle entries from {} (commands={})",
                oraclePath, oracle.size());
        return oracle;
    }

    public static TestPlan generateTestPlan(FullStopSeed fullStopSeed) {
        if (fullStopSeed == null) {
            return null;
        }
        return generateTestPlanFromSeed(fullStopSeed.seed,
                fullStopSeed.validationReadResults);
    }

    public static TestPlan generateTestPlan(RollingSeed rollingSeed) {
        if (rollingSeed == null) {
            return null;
        }
        // Mode-5 rolling differential paths no longer depend on persistent
        // per-seed oracle. Pass empty oracle; Checker D (cross-cluster
        // structured comparison) is the canonical oracle at verdict time.
        return generateTestPlanFromSeed(rollingSeed.seed,
                new LinkedList<>());
    }

    private static TestPlan generateTestPlanFromSeed(
            Seed sourceSeed,
            List<String> validationReadResultsOracle) {
        // Some systems might have special requirements for
        // upgrade, like HDFS needs to upgrade NN.
        int nodeNum = Config.getConf().nodeNum;

        if (sourceSeed == null || sourceSeed.validationCommandSequence == null
                || sourceSeed.originalCommandSequence == null) {
            logger.error("empty or invalid source seed for generateTestPlan");
            return null;
        }

        if (Config.getConf().useExampleTestPlan)
            return constructExampleTestPlan(new FullStopSeed(sourceSeed,
                    validationReadResultsOracle == null ? new LinkedList<>()
                            : validationReadResultsOracle),
                    nodeNum);

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
        shellCommands = ShellCommand.seedWriteCmd2Events(sourceSeed, nodeNum);

        List<Event> events = interleaveWithOrder(upgradeOpAndFaults,
                shellCommands);

        if (!Config.getConf().testSingleVersion
                && !Config.getConf().fullStopUpgradeWithFaults)
            events.add(events.size(), new FinalizeUpgrade());

        List<String> oracle = validationReadResultsOracle == null
                ? new LinkedList<>()
                : new LinkedList<>(validationReadResultsOracle);
        return new TestPlan(nodeNum, events, sourceSeed,
                sourceSeed.validationCommandSequence
                        .getCommandStringList(),
                oracle);
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

    private static TestPlanFeedbackPacket.LaneStatus normalizeLaneStatus(
            TestPlanFeedbackPacket packet) {
        if (packet == null || packet.laneStatus == null) {
            return TestPlanFeedbackPacket.LaneStatus.OK;
        }
        return packet.laneStatus;
    }

    private void updateDifferentialLaneOutcomeCounters(
            TestPlanFeedbackPacket[] testPlanFeedbackPackets) {
        for (int i = 0; i < testPlanFeedbackPackets.length; i++) {
            TestPlanFeedbackPacket packet = testPlanFeedbackPackets[i];
            TestPlanFeedbackPacket.LaneStatus laneStatus = normalizeLaneStatus(
                    packet);
            String laneName = testPlanID2Setup.getOrDefault(i, "UNKNOWN");
            String laneReason = packet == null ? ""
                    : (packet.laneFailureReason == null ? ""
                            : packet.laneFailureReason);
            logger.info(
                    "Differential lane outcome [{}]: status={}, reason={}",
                    laneName, laneStatus, laneReason);

            if (laneStatus != TestPlanFeedbackPacket.LaneStatus.OK) {
                if (i == 0)
                    oldOldLaneCollectionFailureNum++;
                else if (i == 1)
                    rollingLaneCollectionFailureNum++;
                else if (i == 2)
                    newNewLaneCollectionFailureNum++;
            }
            if (laneStatus == TestPlanFeedbackPacket.LaneStatus.TIMEOUT) {
                if (i == 0)
                    oldOldLaneTimeoutNum++;
                else if (i == 1)
                    rollingLaneTimeoutNum++;
                else if (i == 2)
                    newNewLaneTimeoutNum++;
            }
        }

        logger.info(
                "Differential lane counters: timeout(old-old={}, old-new={}, new-new={}), collectionFailure(old-old={}, old-new={}, new-new={})",
                oldOldLaneTimeoutNum, rollingLaneTimeoutNum,
                newNewLaneTimeoutNum,
                oldOldLaneCollectionFailureNum,
                rollingLaneCollectionFailureNum,
                newNewLaneCollectionFailureNum);
    }

    public synchronized void updateStatus(
            TestPlanDiffFeedbackPacket testPlanDiffFeedbackPacket) {
        logger.info("TestPlanDiffFeedbackPacket received");

        TestPlanFeedbackPacket[] testPlanFeedbackPackets = testPlanDiffFeedbackPacket.testPlanFeedbackPackets;

        if (testPlanFeedbackPackets.length != 3) {
            throw new RuntimeException(
                    "TestPlanDiffFeedbackPacket length is not 3: there should be (1) Old (2) RU and (3) New");
        }

        updateDifferentialLaneOutcomeCounters(testPlanFeedbackPackets);

        int oldOldValidationSize = testPlanFeedbackPackets[0].validationReadResults == null
                ? 0
                : testPlanFeedbackPackets[0].validationReadResults.size();
        int rollingValidationSize = testPlanFeedbackPackets[1].validationReadResults == null
                ? 0
                : testPlanFeedbackPackets[1].validationReadResults.size();
        int newNewValidationSize = testPlanFeedbackPackets[2].validationReadResults == null
                ? 0
                : testPlanFeedbackPackets[2].validationReadResults.size();
        logger.info(
                "Validation results collected (old-old={}, rolling={}, new-new={})",
                oldOldValidationSize, rollingValidationSize,
                newNewValidationSize);

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
                CanonicalKeyMode debugKeyMode = Config
                        .getConf().canonicalKeyMode;
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
                    // Print canonical key sequence at the configured tier
                    logger.info("[" + clusterType + "] canonical keys ("
                            + debugKeyMode + "): "
                            + serializedTraces[i]
                                    .getCanonicalKeysForDiff(debugKeyMode));
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

        // --- Compressed order debug signal (Phase 6) ---
        if (Config.getConf().useCompressedOrderDebug
                && Config.getConf().useTrace
                && serializedTraces[0] != null
                && serializedTraces[1] != null
                && serializedTraces[2] != null) {
            double[] orderSim = DiffComputeCompressedOrder.compute(
                    serializedTraces[0], serializedTraces[1],
                    serializedTraces[2]);
            logger.info(
                    "[TRACE-DEBUG] Compressed order: OO-RO={}, RO-NN={}, OO-NN={}",
                    String.format("%.2f", orderSim[0]),
                    String.format("%.2f", orderSim[1]),
                    String.format("%.2f", orderSim[2]));
        }

        // === Canonical trace scoring (Phase 4) ===
        boolean traceInteresting = false;
        // Phase 0 observability: accumulate which rules fired so we can
        // pick a primary admission reason later.
        boolean anyTriDiffExclusiveFired = false;
        boolean anyTriDiffMissingFired = false;
        boolean anyWindowSimFired = false;
        boolean aggregateSimFired = false;
        int windowsEvaluatedThisRound = 0;
        List<TraceSignature> interestingTraceSignatures = new ArrayList<>();

        boolean allLanesOk = normalizeLaneStatus(
                testPlanFeedbackPackets[0]) == TestPlanFeedbackPacket.LaneStatus.OK
                && normalizeLaneStatus(
                        testPlanFeedbackPackets[1]) == TestPlanFeedbackPacket.LaneStatus.OK
                && normalizeLaneStatus(
                        testPlanFeedbackPackets[2]) == TestPlanFeedbackPacket.LaneStatus.OK;

        boolean windowedTracesAvailable = Config.getConf().useTrace
                && allLanesOk
                && testPlanFeedbackPackets[0].windowedTrace != null
                && testPlanFeedbackPackets[1].windowedTrace != null
                && testPlanFeedbackPackets[2].windowedTrace != null;

        if (Config.getConf().useCanonicalTraceSimilarity
                && windowedTracesAvailable) {

            CanonicalKeyMode keyMode = Config.getConf().canonicalKeyMode;

            List<AlignedWindow> aligned = alignWindows(
                    testPlanFeedbackPackets[0].windowedTrace,
                    testPlanFeedbackPackets[1].windowedTrace,
                    testPlanFeedbackPackets[2].windowedTrace);

            if (aligned.isEmpty()) {
                logger.info(
                        "[TRACE] Window alignment failed or abstained — trace scoring skipped");
            } else {
                logger.info(
                        "[TRACE] Aligned {} comparable windows (keyMode={})",
                        aligned.size(), keyMode);

                // Accumulators for aggregate similarity
                long aggIntersectionOO_RO = 0, aggUnionOO_RO = 0;
                long aggIntersectionRO_NN = 0, aggUnionRO_NN = 0;
                long aggIntersectionOO_NN = 0, aggUnionOO_NN = 0;

                for (AlignedWindow aw : aligned) {
                    Trace mergedOO = aw.oldOld.mergedTrace();
                    Trace mergedRO = aw.rolling.mergedTrace();
                    Trace mergedNN = aw.newNew.mergedTrace();

                    // --- Per-window similarity ---
                    double[] sims = DiffComputeSemanticSimilarity.compute(
                            mergedOO, mergedRO, mergedNN, keyMode);
                    double rollingMinSimilarity = Math.min(sims[0], sims[1]);
                    double baselineSimilarity = sims[2];
                    double rollingDivergenceMargin = Math.min(
                            baselineSimilarity - sims[0],
                            baselineSimilarity - sims[1]);

                    logger.info(
                            "[TRACE] Window {} ({}): simOO_RO={}, simRO_NN={}, baseline={}, margin={}",
                            aw.rolling.ordinal,
                            aw.rolling.comparisonStageId,
                            String.format("%.4f", sims[0]),
                            String.format("%.4f", sims[1]),
                            String.format("%.4f", baselineSimilarity),
                            String.format("%.4f", rollingDivergenceMargin));

                    // --- Min-event gate (applies to similarity, tri-diff,
                    // AND aggregate accumulation) ---
                    boolean windowHasEnoughEvents = aw.rolling
                            .totalEventCount() >= Config
                                    .getConf().canonicalMinWindowEventCount;

                    // --- Per-window interestingness ---
                    boolean windowInteresting = windowHasEnoughEvents
                            && rollingMinSimilarity < Config
                                    .getConf().canonicalRollingMinWindowSimilarityThreshold
                            && rollingDivergenceMargin > Config
                                    .getConf().canonicalWindowDivergenceMarginThreshold;

                    // --- Per-window tri-diff ---
                    boolean triDiffInteresting = false;
                    boolean triDiffExclusiveFired = false;
                    boolean triDiffMissingFired = false;
                    int rollingExclusive = 0;
                    int rollingMissing = 0;
                    int totalMessages = 0;
                    int totalAllThreeCount = 0;
                    double rollingExclusiveFraction = 0.0;
                    double rollingMissingFraction = 0.0;
                    Map<String, Integer> rollingExclusiveBuckets = Collections
                            .emptyMap();
                    Map<String, Integer> rollingMissingBuckets = Collections
                            .emptyMap();
                    if (windowHasEnoughEvents
                            && Config
                                    .getConf().useCanonicalMessageIdentityDiff) {
                        DiffComputeMessageTriDiff.MessageTriDiffResult triDiff = DiffComputeMessageTriDiff
                                .computeSemantic(mergedOO, mergedRO, mergedNN,
                                        keyMode);

                        rollingExclusive = triDiff.rollingExclusiveCount();
                        rollingMissing = triDiff.rollingMissingCount();
                        totalMessages = triDiff.rollingLaneSize();
                        totalAllThreeCount = triDiff.totalAllThreeCount();
                        rollingExclusiveFraction = triDiff
                                .rollingExclusiveFraction();
                        rollingMissingFraction = triDiff
                                .rollingMissingFraction();
                        rollingExclusiveBuckets = triDiff.only1;
                        rollingMissingBuckets = triDiff.in02Only;

                        // Phase 1 invariant: both fractions must stay in
                        // [0, 1]. The accessors guarantee this by
                        // construction — a WARN here indicates a
                        // regression in the tri-diff math, which would
                        // silently contaminate offline re-scoring, so
                        // surface it during fuzzing.
                        if (rollingExclusiveFraction < 0.0
                                || rollingExclusiveFraction > 1.0
                                || rollingMissingFraction < 0.0
                                || rollingMissingFraction > 1.0) {
                            logger.warn(
                                    "[TRACE] Tri-diff fraction out of [0,1]: exclusive={}, missing={}, laneSize={}, baselineShared={}",
                                    rollingExclusiveFraction,
                                    rollingMissingFraction,
                                    totalMessages,
                                    triDiff.baselineSharedCount());
                        }

                        logger.info(
                                "[TRACE] TriDiff Window {} ({}): rollingExclusive={} ({}), "
                                        + "rollingMissing={} ({}), baselineShared={}, all3={}, total={}",
                                aw.rolling.ordinal,
                                aw.rolling.comparisonStageId,
                                rollingExclusive,
                                String.format("%.4f",
                                        rollingExclusiveFraction),
                                rollingMissing,
                                String.format("%.4f",
                                        rollingMissingFraction),
                                triDiff.baselineSharedCount(),
                                totalAllThreeCount,
                                totalMessages);

                        TriDiffWindowDecision decision = evaluateTriDiffWindow(
                                triDiff,
                                aw.rolling.stageKind,
                                Config.getConf().rollingExclusiveMinCount,
                                Config
                                        .getConf().rollingExclusiveFractionThreshold,
                                Config.getConf().rollingMissingMinCount,
                                Config
                                        .getConf().rollingMissingFractionThreshold);
                        triDiffExclusiveFired = decision.exclusiveInteresting;
                        triDiffMissingFired = decision.missingInteresting;
                        triDiffInteresting = decision.triDiffInteresting;
                    }

                    // Phase 0: emit a window row for every evaluated window
                    // (regardless of whether it fires) so offline re-scoring
                    // can reproduce admission decisions. Use finishedTestID
                    // because the static {@code round} counter is only bumped
                    // by the stacked-tests path and stays at 0 in rolling
                    // modes.
                    observabilityMetrics.recordWindowTrigger(
                            new WindowTriggerRow(
                                    finishedTestID,
                                    testPlanDiffFeedbackPacket.testPacketID,
                                    aw.rolling.ordinal,
                                    aw.rolling.comparisonStageId,
                                    totalMessages,
                                    totalAllThreeCount,
                                    rollingExclusive,
                                    rollingMissing,
                                    rollingExclusiveFraction,
                                    rollingMissingFraction,
                                    sims[0],
                                    sims[1],
                                    baselineSimilarity,
                                    rollingMinSimilarity,
                                    rollingDivergenceMargin,
                                    windowHasEnoughEvents,
                                    windowInteresting,
                                    triDiffExclusiveFired,
                                    triDiffMissingFired));
                    windowsEvaluatedThisRound++;
                    if (windowInteresting) {
                        anyWindowSimFired = true;
                    }
                    if (triDiffExclusiveFired) {
                        anyTriDiffExclusiveFired = true;
                    }
                    if (triDiffMissingFired) {
                        anyTriDiffMissingFired = true;
                    }

                    // Accumulate for aggregate (only windows above
                    // min-event threshold)
                    if (windowHasEnoughEvents) {
                        Map<String, Integer> msOO = mergedOO != null
                                ? mergedOO.getCanonicalMultiset(keyMode)
                                : Collections.emptyMap();
                        Map<String, Integer> msRO = mergedRO != null
                                ? mergedRO.getCanonicalMultiset(keyMode)
                                : Collections.emptyMap();
                        Map<String, Integer> msNN = mergedNN != null
                                ? mergedNN.getCanonicalMultiset(keyMode)
                                : Collections.emptyMap();

                        if (Config.getConf().printTrace) {
                            logger.info(
                                    "[TRACE] Window {} multisets: OO={}, RO={}, NN={}",
                                    aw.rolling.ordinal, msOO, msRO,
                                    msNN);
                        }

                        Set<String> allKeysOO_RO = new HashSet<>(
                                msOO.keySet());
                        allKeysOO_RO.addAll(msRO.keySet());
                        for (String key : allKeysOO_RO) {
                            int cOO = msOO.getOrDefault(key, 0);
                            int cRO = msRO.getOrDefault(key, 0);
                            aggIntersectionOO_RO += Math.min(cOO, cRO);
                            aggUnionOO_RO += Math.max(cOO, cRO);
                        }
                        Set<String> allKeysRO_NN = new HashSet<>(
                                msRO.keySet());
                        allKeysRO_NN.addAll(msNN.keySet());
                        for (String key : allKeysRO_NN) {
                            int cRO = msRO.getOrDefault(key, 0);
                            int cNN = msNN.getOrDefault(key, 0);
                            aggIntersectionRO_NN += Math.min(cRO, cNN);
                            aggUnionRO_NN += Math.max(cRO, cNN);
                        }
                        Set<String> allKeysOO_NN = new HashSet<>(
                                msOO.keySet());
                        allKeysOO_NN.addAll(msNN.keySet());
                        for (String key : allKeysOO_NN) {
                            int cOO = msOO.getOrDefault(key, 0);
                            int cNN = msNN.getOrDefault(key, 0);
                            aggIntersectionOO_NN += Math.min(cOO, cNN);
                            aggUnionOO_NN += Math.max(cOO, cNN);
                        }
                    }

                    if (windowInteresting || triDiffInteresting) {
                        interestingTraceSignatures.add(TraceSignature
                                .fromWindow(
                                        aw.rolling,
                                        keyMode,
                                        rollingExclusiveBuckets,
                                        rollingMissingBuckets,
                                        windowInteresting,
                                        rollingMinSimilarity,
                                        rollingDivergenceMargin,
                                        Config.getConf().traceSignatureTopBucketLimit));
                        traceInteresting = true;
                        logger.info(
                                "[TRACE] Window {} interesting: windowSim={}, triDiff={}",
                                aw.rolling.ordinal, windowInteresting,
                                triDiffInteresting);
                    }
                }

                // --- Aggregate similarity (always computed and logged) ---
                double aggSimOO_RO = aggUnionOO_RO == 0 ? 1.0
                        : (double) aggIntersectionOO_RO / aggUnionOO_RO;
                double aggSimRO_NN = aggUnionRO_NN == 0 ? 1.0
                        : (double) aggIntersectionRO_NN / aggUnionRO_NN;
                double aggSimOO_NN = aggUnionOO_NN == 0 ? 1.0
                        : (double) aggIntersectionOO_NN / aggUnionOO_NN;

                double aggRollingMinSim = Math.min(aggSimOO_RO,
                        aggSimRO_NN);
                double aggBaselineSim = aggSimOO_NN;
                double aggDivergenceMargin = Math.min(
                        aggBaselineSim - aggSimOO_RO,
                        aggBaselineSim - aggSimRO_NN);

                logger.info(
                        "[TRACE] Aggregate: simOO_RO={}, simRO_NN={}, baseline={}, margin={}",
                        String.format("%.4f", aggSimOO_RO),
                        String.format("%.4f", aggSimRO_NN),
                        String.format("%.4f", aggBaselineSim),
                        String.format("%.4f", aggDivergenceMargin));

                // Aggregate admission check (only if no per-window hit)
                if (!traceInteresting) {
                    boolean aggregateInteresting = aggRollingMinSim < Config
                            .getConf().canonicalRollingMinAggregateSimilarityThreshold
                            && aggDivergenceMargin > Config
                                    .getConf().canonicalAggregateDivergenceMarginThreshold;

                    if (aggregateInteresting) {
                        traceInteresting = true;
                        aggregateSimFired = true;
                        logger.info(
                                "[TRACE] Aggregate interesting: sim={}, margin={}",
                                String.format("%.4f", aggRollingMinSim),
                                String.format("%.4f",
                                        aggDivergenceMargin));
                    }
                }
            }
        } else if (Config.getConf().useCanonicalTraceSimilarity
                && Config.getConf().useTrace) {
            logger.info(
                    "[TRACE] Windowed traces not available — canonical scoring skipped");
        }

        // === Verdict classification (WS0) — runs BEFORE corpus update ===
        DiffVerdict overallVerdict = DiffVerdict.NONE;
        TestPlanFeedbackPacket rollingFb = testPlanFeedbackPackets[1];

        // 0. Lane collection failures → always INFRA_NOISE
        for (int i = 0; i < testPlanFeedbackPackets.length; i++) {
            TestPlanFeedbackPacket.LaneStatus laneStatus = normalizeLaneStatus(
                    testPlanFeedbackPackets[i]);
            if (laneStatus != TestPlanFeedbackPacket.LaneStatus.OK) {
                if (DiffVerdict.INFRA_NOISE
                        .moreSignificantThan(overallVerdict)) {
                    overallVerdict = DiffVerdict.INFRA_NOISE;
                }
            }
        }

        // 1-2. Event failure classification
        boolean anyEventFailed = testPlanFeedbackPackets[0].isEventFailed
                || testPlanFeedbackPackets[1].isEventFailed
                || testPlanFeedbackPackets[2].isEventFailed;
        DiffVerdict eventVerdict = DiffVerdict.NONE;
        if (anyEventFailed) {
            eventVerdict = classifyEventFailure(testPlanFeedbackPackets);
            if (eventVerdict.moreSignificantThan(overallVerdict)) {
                overallVerdict = eventVerdict;
            }
        }

        // 3. Cross-cluster structured inconsistency (Checker D) — primary
        // rolling-upgrade bug oracle. Gated strictly: all 3 lanes must be OK
        // and all 3 must have non-null structured validationResults.
        String crossClusterReport = null;
        boolean checkerDGateOpen = allLanesOkWithStructuredResults(
                testPlanFeedbackPackets);
        logger.info(
                "[CheckerD] gate={}, validationResults sizes: [{}={}, {}={}, {}={}]",
                checkerDGateOpen,
                "old-old",
                testPlanFeedbackPackets[0].validationResults == null
                        ? "null"
                        : testPlanFeedbackPackets[0].validationResults.size(),
                "rolling",
                testPlanFeedbackPackets[1].validationResults == null
                        ? "null"
                        : testPlanFeedbackPackets[1].validationResults.size(),
                "new-new",
                testPlanFeedbackPackets[2].validationResults == null
                        ? "null"
                        : testPlanFeedbackPackets[2].validationResults
                                .size());
        if (checkerDGateOpen) {
            crossClusterReport = checkCrossClusterInconsistencyStructured(
                    testPlanFeedbackPackets);
            logger.info("[CheckerD] crossClusterReport={}",
                    crossClusterReport == null ? "null (no divergence)"
                            : "DIVERGENCE DETECTED");
            if (crossClusterReport != null) {
                logger.info("[CheckerD] report:\n{}", crossClusterReport);
                if (DiffVerdict.ROLLING_UPGRADE_BUG_CANDIDATE
                        .moreSignificantThan(overallVerdict)) {
                    overallVerdict = DiffVerdict.ROLLING_UPGRADE_BUG_CANDIDATE;
                }
            }
        }

        // 4. Per-cluster inconsistency (Checker E) — REMOVED for mode-3/mode-5
        // rolling differential paths. Checker E was driven by isInconsistent
        // flags from the removed lane-local oracle mismatch (Checker C).

        // 5. ERROR log classification
        DiffVerdict errorLogVerdict = DiffVerdict.NONE;
        boolean anyErrorLog = testPlanFeedbackPackets[0].hasERRORLog
                || testPlanFeedbackPackets[1].hasERRORLog
                || testPlanFeedbackPackets[2].hasERRORLog;
        if (anyErrorLog) {
            errorLogVerdict = classifyErrorLogSignal(testPlanFeedbackPackets);
            if (errorLogVerdict.moreSignificantThan(overallVerdict)) {
                overallVerdict = errorLogVerdict;
            }
        }

        logger.info("Verdict classification: overall=" + overallVerdict
                + " event=" + eventVerdict + " crossCluster="
                + (crossClusterReport != null ? "CANDIDATE" : "NONE")
                + " errorLog=" + errorLogVerdict);

        // === Corpus update (gated by verdict) ===
        boolean addToCorpus = newOriBC || newUpgradeBC || traceInteresting;
        boolean newBranchCoverage = newOriBC || newUpgradeBC;
        // WS0: exclude same-version bugs from corpus to avoid FP-prone
        // descendants
        if (overallVerdict == DiffVerdict.SAME_VERSION_BUG) {
            logger.info(
                    "Suppressing corpus add: test triggers SAME_VERSION_BUG");
            addToCorpus = false;
        }

        // Phase 0: downstream payoff credit for the parent of this child,
        // irrespective of whether we admit the child ourselves. Structured
        // (Checker D cross-cluster) candidates are tracked separately from
        // weak event/error-log candidates so Phase 2 retention decisions can
        // ignore the latter until Phase 5 cleans candidate routing up.
        boolean structuredCandidate = crossClusterReport != null;
        boolean weakCandidate = !structuredCandidate
                && (eventVerdict == DiffVerdict.ROLLING_UPGRADE_BUG_CANDIDATE
                        || errorLogVerdict == DiffVerdict.ROLLING_UPGRADE_BUG_CANDIDATE);
        // Phase 2: the corpus needs the same root-lineage lookup that the
        // observability layer already performs so promotion credits land on
        // the rolling-corpus seed that fathered this round's mutation.
        int parentLineageRoot = observabilityMetrics
                .resolveRootLifecycleId(
                        testPlanDiffFeedbackPacket.testPacketID);
        if (newBranchCoverage) {
            observabilityMetrics.recordDownstreamBranchHit(
                    testPlanDiffFeedbackPacket.testPacketID);
            if (parentLineageRoot >= 0) {
                rollingSeedCorpus.notifyBranchPayoff(parentLineageRoot);
            }
        }
        if (structuredCandidate) {
            observabilityMetrics.recordDownstreamStructuredCandidateHit(
                    testPlanDiffFeedbackPacket.testPacketID);
            if (parentLineageRoot >= 0) {
                rollingSeedCorpus
                        .notifyStructuredCandidatePayoff(parentLineageRoot);
            }
        }
        if (weakCandidate) {
            observabilityMetrics.recordDownstreamWeakCandidateHit(
                    testPlanDiffFeedbackPacket.testPacketID);
            // Weak candidates intentionally do not feed Phase 2 promotion
            // until Phase 5 cleans up candidate routing — they are too
            // noisy to drive retention.
        }

        boolean traceSignatureSuppressed = false;
        boolean isRollingMode = Config.getConf().testingMode == 5
                || Config.getConf().testingMode == 6;
        if (addToCorpus
                && isRollingMode
                && traceInteresting
                && Config.getConf().useTraceSignatureDedup
                && shouldSuppressTraceOnlyBySignature(
                        newBranchCoverage,
                        interestingTraceSignatures,
                        finishedTestID,
                        traceSignatureIndex)) {
            traceSignatureSuppressed = true;
            addToCorpus = false;
            logger.info(
                    "Phase 4 signature dedup suppressed trace-only admission "
                            + "(signatures={}, totalSuppressed={}, live={}, recentEntries={})",
                    interestingTraceSignatures,
                    traceSignatureIndex.getTotalSuppressedDuplicates(),
                    traceSignatureIndex.liveSignatureCount(),
                    traceSignatureIndex.recentEntryCount());
        }

        // Mode-3/mode-5 rolling differential paths no longer persist the
        // old-old lane results as validationReadResultsOracle. Checker D
        // (cross-cluster structured comparison) runs at verdict time and
        // does not depend on a stored per-seed oracle.
        AdmissionReason admissionReasonForRow = AdmissionReason.UNKNOWN;
        boolean admittedThisRound = false;
        RollingSeedCorpus.AdmissionOutcome rollingCorpusOutcome = null;
        if (addToCorpus) {
            TestPlan testPlan = testID2TestPlan
                    .get(testPlanDiffFeedbackPacket.testPacketID);
            if (testPlan != null) {
                AdmissionReason admissionReason = classifyAdmissionReason(
                        newBranchCoverage,
                        traceInteresting,
                        anyTriDiffExclusiveFired,
                        anyWindowSimFired,
                        aggregateSimFired);

                // Phase 2: for mode-5/mode-6, route the admission through
                // the tiered corpus first. Trace-only admissions may be
                // rejected by the per-round, 100-round, or share caps; in
                // that case we drop the admission entirely — both the
                // long-lived corpus AND the short-term testPlanCorpus —
                // so trace-only flooding is suppressed end-to-end.
                boolean isModeFive = Config.getConf().testingMode == 5
                        || Config.getConf().testingMode == 6;
                boolean rollingCorpusAccepted = true;
                if (isModeFive && Config.getConf().useTraceProbation
                        && testPlan.seed != null) {
                    RollingSeed storedRollingSeed = new RollingSeed(
                            SerializationUtils.clone(testPlan.seed),
                            new LinkedList<>());
                    storedRollingSeed.lineageTestId = testPlanDiffFeedbackPacket.testPacketID;
                    // Pass the already-resolved parent lineage root so the
                    // corpus can enforce the Phase 2 "independent parents"
                    // rule for rediscovery promotion: repeated admissions
                    // from the same ancestor count as a single event.
                    rollingCorpusOutcome = rollingSeedCorpus.tryAdmit(
                            storedRollingSeed,
                            admissionReason,
                            finishedTestID,
                            parentLineageRoot);
                    rollingCorpusAccepted = rollingCorpusOutcome.isAdmitted();
                    if (!rollingCorpusAccepted) {
                        logger.info(
                                "Phase 2 corpus rejected trace-only admission (reason={}, outcome={}, "
                                        + "branchBacked={}, probation={}, promoted={})",
                                admissionReason,
                                rollingCorpusOutcome,
                                rollingSeedCorpus.branchBackedSize(),
                                rollingSeedCorpus.traceProbationSize(),
                                rollingSeedCorpus.tracePromotedSize());
                    }
                } else if (isModeFive && testPlan.seed != null) {
                    // useTraceProbation disabled → fall back to legacy
                    // cycle-queue admission semantics. Preserves old
                    // mode-5 behavior when Phase 2 is turned off.
                    RollingSeed storedRollingSeed = new RollingSeed(
                            SerializationUtils.clone(testPlan.seed),
                            new LinkedList<>());
                    storedRollingSeed.lineageTestId = testPlanDiffFeedbackPacket.testPacketID;
                    rollingSeedCorpus.addSeed(storedRollingSeed);
                }

                if (!rollingCorpusAccepted) {
                    addToCorpus = false;
                } else {
                    admissionReasonForRow = admissionReason;
                    admittedThisRound = true;
                    observabilityMetrics.recordAdmission(admissionReason);
                    // Tag this plan with its admission testID so future
                    // mutations know who to credit as parent.
                    testPlan.lineageTestId = testPlanDiffFeedbackPacket.testPacketID;
                    observabilityMetrics.recordSeedAddition(
                            testPlanDiffFeedbackPacket.testPacketID,
                            finishedTestID,
                            admissionReason,
                            observabilityMetrics.resolveRootLifecycleId(
                                    testPlanDiffFeedbackPacket.testPacketID));

                    testPlanCorpus.addTestPlan(testPlan);
                    if (isModeFive && !newBranchCoverage
                            && Config.getConf().useTraceSignatureDedup) {
                        traceSignatureIndex.recordAdmitted(
                                interestingTraceSignatures,
                                finishedTestID);
                    }
                    if (isModeFive) {
                        logger.info(
                                "Mode 5 rollingSeedCorpus updated from interesting test plan, "
                                        + "total={} (branchBacked={}, probation={}, promoted={}), outcome={}",
                                rollingSeedCorpus.size(),
                                rollingSeedCorpus.branchBackedSize(),
                                rollingSeedCorpus.traceProbationSize(),
                                rollingSeedCorpus.tracePromotedSize(),
                                rollingCorpusOutcome);
                    }
                    logger.info(
                            "Added test plan to corpus (newOriBC=" + newOriBC
                                    + ", newUpgradeBC=" + newUpgradeBC
                                    + ", traceInteresting="
                                    + traceInteresting + ", reason="
                                    + admissionReason + ")");
                }
            }
        }

        // Phase 2: evict trace-probation seeds that have timed out or
        // exhausted their selection budget without any payoff. Runs once
        // per completed differential round so the pool stays responsive
        // to the current campaign without needing a separate sweeper
        // thread.
        if ((Config.getConf().testingMode == 5
                || Config.getConf().testingMode == 6)
                && Config.getConf().useTraceProbation) {
            int evicted = rollingSeedCorpus
                    .evictExpiredProbation(finishedTestID);
            if (evicted > 0) {
                logger.info(
                        "Phase 2 corpus evicted {} expired trace-probation seeds "
                                + "(branchBacked={}, probation={}, promoted={})",
                        evicted,
                        rollingSeedCorpus.branchBackedSize(),
                        rollingSeedCorpus.traceProbationSize(),
                        rollingSeedCorpus.tracePromotedSize());
            }
        }

        // === Bug detection — verdict-routed directory saves ===
        // Each verdict bucket gets its own lazy directory
        Path candidateDir = null;
        Path sameVersionDir = null;
        Path noiseDir = null;

        // 0. Lane collection failures → noise
        for (int i = 0; i < testPlanFeedbackPackets.length; i++) {
            TestPlanFeedbackPacket lanePacket = testPlanFeedbackPackets[i];
            TestPlanFeedbackPacket.LaneStatus laneStatus = normalizeLaneStatus(
                    lanePacket);
            if (laneStatus == TestPlanFeedbackPacket.LaneStatus.OK) {
                continue;
            }
            noiseDir = ensureVerdictDir(noiseDir,
                    rollingFb.configFileName, rollingFb.fullSequence,
                    DiffVerdict.INFRA_NOISE);
            String laneTag = testPlanID2Setup.getOrDefault(i, "Unknown")
                    .replace(" ", "");
            String reason = lanePacket == null ? ""
                    : (lanePacket.laneFailureReason == null ? ""
                            : lanePacket.laneFailureReason);
            if (reason.isEmpty()) {
                reason = "[" + laneTag + "][" + laneStatus
                        + "] lane feedback collection failed";
            }
            saveLaneCollectionFailureReport(noiseDir,
                    testPlanDiffFeedbackPacket.testPacketID,
                    laneTag, reason);
            noiseNum++;
        }

        // 1-2. Event failures — routed by eventVerdict
        if (anyEventFailed) {
            boolean allFailed = testPlanFeedbackPackets[0].isEventFailed
                    && testPlanFeedbackPackets[1].isEventFailed
                    && testPlanFeedbackPackets[2].isEventFailed;
            if (allFailed) {
                logger.info(
                        "All 3 clusters failed — saving as ORACLE_NOISE");
                noiseDir = ensureVerdictDir(noiseDir,
                        rollingFb.configFileName, rollingFb.fullSequence,
                        DiffVerdict.ORACLE_NOISE);
                for (int i = 0; i < 3; i++) {
                    if (testPlanFeedbackPackets[i].isEventFailed) {
                        String laneTag = testPlanID2Setup.getOrDefault(i,
                                "Unknown").replace(" ", "");
                        saveEventCrashReport(noiseDir,
                                testPlanDiffFeedbackPacket.testPacketID,
                                DiffReportHelper.eventCrashHeader(laneTag)
                                        + testPlanFeedbackPackets[i].eventFailedReport,
                                laneTag, DiffVerdict.ORACLE_NOISE,
                                rollingFb.configFileName);
                    }
                }
                noiseNum++;
            } else if (eventVerdict == DiffVerdict.ROLLING_UPGRADE_BUG_CANDIDATE) {
                candidateDir = ensureVerdictDir(candidateDir,
                        rollingFb.configFileName, rollingFb.fullSequence,
                        DiffVerdict.ROLLING_UPGRADE_BUG_CANDIDATE);
                saveEventCrashReport(candidateDir,
                        testPlanDiffFeedbackPacket.testPacketID,
                        DiffReportHelper.eventCrashHeader("Rolling")
                                + rollingFb.eventFailedReport,
                        "Rolling",
                        DiffVerdict.ROLLING_UPGRADE_BUG_CANDIDATE,
                        rollingFb.configFileName);
                candidateNum++;
            } else if (eventVerdict == DiffVerdict.SAME_VERSION_BUG) {
                sameVersionDir = ensureVerdictDir(sameVersionDir,
                        rollingFb.configFileName, rollingFb.fullSequence,
                        DiffVerdict.SAME_VERSION_BUG);
                for (int i = 0; i < 3; i++) {
                    if (testPlanFeedbackPackets[i].isEventFailed) {
                        String laneTag = testPlanID2Setup.getOrDefault(i,
                                "Unknown").replace(" ", "");
                        saveEventCrashReport(sameVersionDir,
                                testPlanDiffFeedbackPacket.testPacketID,
                                DiffReportHelper.eventCrashHeader(laneTag)
                                        + testPlanFeedbackPackets[i].eventFailedReport,
                                laneTag, DiffVerdict.SAME_VERSION_BUG,
                                rollingFb.configFileName);
                    }
                }
                sameVersionBugNum++;
            } else {
                // ORACLE_NOISE or other mixed
                noiseDir = ensureVerdictDir(noiseDir,
                        rollingFb.configFileName, rollingFb.fullSequence,
                        DiffVerdict.ORACLE_NOISE);
                for (int i = 0; i < 3; i++) {
                    if (testPlanFeedbackPackets[i].isEventFailed) {
                        String laneTag = testPlanID2Setup.getOrDefault(i,
                                "Unknown").replace(" ", "");
                        saveEventCrashReport(noiseDir,
                                testPlanDiffFeedbackPacket.testPacketID,
                                DiffReportHelper.eventCrashHeader(laneTag)
                                        + testPlanFeedbackPackets[i].eventFailedReport,
                                laneTag, DiffVerdict.ORACLE_NOISE,
                                rollingFb.configFileName);
                    }
                }
                noiseNum++;
            }
        }

        // 3. Cross-cluster inconsistency → always CANDIDATE (tri-diff is
        // correct)
        if (crossClusterReport != null) {
            candidateDir = ensureVerdictDir(candidateDir,
                    rollingFb.configFileName, rollingFb.fullSequence,
                    DiffVerdict.ROLLING_UPGRADE_BUG_CANDIDATE);
            saveInconsistencyReport(candidateDir,
                    testPlanDiffFeedbackPacket.testPacketID,
                    crossClusterReport,
                    DiffVerdict.ROLLING_UPGRADE_BUG_CANDIDATE,
                    rollingFb.configFileName);
            candidateNum++;
        }

        // 4. Per-cluster inconsistency (Checker E) — REMOVED.
        // Lane-local isInconsistent flags are no longer set by Checker C.

        // 5. ERROR logs — routed by errorLogVerdict
        if (anyErrorLog) {
            if (errorLogVerdict == DiffVerdict.ROLLING_UPGRADE_BUG_CANDIDATE) {
                candidateDir = ensureVerdictDir(candidateDir,
                        rollingFb.configFileName, rollingFb.fullSequence,
                        DiffVerdict.ROLLING_UPGRADE_BUG_CANDIDATE);
                for (int i = 0; i < 3; i++) {
                    if (testPlanFeedbackPackets[i].hasERRORLog) {
                        String laneTag = testPlanID2Setup.getOrDefault(i,
                                "Unknown").replace(" ", "");
                        saveErrorReport(candidateDir,
                                DiffReportHelper.errorLogHeader(laneTag)
                                        + testPlanFeedbackPackets[i].errorLogReport,
                                testPlanDiffFeedbackPacket.testPacketID,
                                laneTag,
                                DiffVerdict.ROLLING_UPGRADE_BUG_CANDIDATE,
                                rollingFb.configFileName);
                    }
                }
                candidateNum++;
            } else if (errorLogVerdict == DiffVerdict.SAME_VERSION_BUG) {
                sameVersionDir = ensureVerdictDir(sameVersionDir,
                        rollingFb.configFileName, rollingFb.fullSequence,
                        DiffVerdict.SAME_VERSION_BUG);
                for (int i = 0; i < 3; i++) {
                    if (testPlanFeedbackPackets[i].hasERRORLog) {
                        String laneTag = testPlanID2Setup.getOrDefault(i,
                                "Unknown").replace(" ", "");
                        saveErrorReport(sameVersionDir,
                                DiffReportHelper.errorLogHeader(laneTag)
                                        + testPlanFeedbackPackets[i].errorLogReport,
                                testPlanDiffFeedbackPacket.testPacketID,
                                laneTag, DiffVerdict.SAME_VERSION_BUG,
                                rollingFb.configFileName);
                    }
                }
                sameVersionBugNum++;
            } else {
                noiseDir = ensureVerdictDir(noiseDir,
                        rollingFb.configFileName, rollingFb.fullSequence,
                        DiffVerdict.ORACLE_NOISE);
                for (int i = 0; i < 3; i++) {
                    if (testPlanFeedbackPackets[i].hasERRORLog) {
                        String laneTag = testPlanID2Setup.getOrDefault(i,
                                "Unknown").replace(" ", "");
                        saveErrorReport(noiseDir,
                                DiffReportHelper.errorLogHeader(laneTag)
                                        + testPlanFeedbackPackets[i].errorLogReport,
                                testPlanDiffFeedbackPacket.testPacketID,
                                laneTag, DiffVerdict.ORACLE_NOISE,
                                rollingFb.configFileName);
                    }
                }
                noiseNum++;
            }
        }

        // Phase 0: emit a per-round admission summary row for this
        // completed differential execution. Cumulative counts embedded in
        // the row reflect the state *after* any admission recorded above.
        observabilityMetrics.recordAdmissionSummary(new AdmissionSummaryRow(
                finishedTestID,
                testPlanDiffFeedbackPacket.testPacketID,
                admittedThisRound,
                admissionReasonForRow,
                newBranchCoverage,
                traceInteresting,
                anyTriDiffExclusiveFired,
                anyTriDiffMissingFired,
                anyWindowSimFired,
                aggregateSimFired,
                traceSignatureSuppressed,
                structuredCandidate,
                weakCandidate,
                windowsEvaluatedThisRound,
                overallVerdict == null ? "NONE" : overallVerdict.name(),
                observabilityMetrics
                        .getAdmissionCount(AdmissionReason.BRANCH_ONLY),
                observabilityMetrics
                        .getAdmissionCount(AdmissionReason.BRANCH_AND_TRACE),
                observabilityMetrics.getAdmissionCount(
                        AdmissionReason.TRACE_ONLY_WINDOW_SIM),
                observabilityMetrics.getAdmissionCount(
                        AdmissionReason.TRACE_ONLY_TRIDIFF_EXCLUSIVE),
                observabilityMetrics.getAdmissionCount(
                        AdmissionReason.TRACE_ONLY_TRIDIFF_MISSING),
                traceSignatureIndex.getTotalSuppressedDuplicates()));

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

    /**
     * Structured cross-cluster comparison (Checker D).
     * Requires all 3 packets to have non-null structured validationResults.
     * No legacy string-based fallback for mode-3/mode-5 paths.
     */
    private String checkCrossClusterInconsistencyStructured(
            TestPlanFeedbackPacket[] packets) {
        if (packets[0].validationResults == null
                || packets[1].validationResults == null
                || packets[2].validationResults == null) {
            return null;
        }
        String oldVsRolling = ValidationResultComparator.compare(
                packets[0].validationResults,
                packets[1].validationResults,
                "Old-Old", "Rolling");
        String newVsRolling = ValidationResultComparator.compare(
                packets[2].validationResults,
                packets[1].validationResults,
                "New-New", "Rolling");
        String oldVsNew = ValidationResultComparator.compare(
                packets[0].validationResults,
                packets[2].validationResults,
                "Old-Old", "New-New");

        // Rolling diverges from both baselines, and baselines agree
        if (oldVsRolling != null && newVsRolling != null
                && oldVsNew == null) {
            StringBuilder sb = new StringBuilder();
            sb.append(
                    DiffReportHelper.crossClusterHeader());
            sb.append(
                    "Old-Old and New-New agree but Rolling differs.\n");
            sb.append(oldVsRolling).append("\n");
            sb.append(newVsRolling).append("\n");
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

    // --- Tri-lane classification helpers (WS0) ---

    /**
     * Classify event failure across 3 lanes.
     * rolling-only → CANDIDATE; all-three → ORACLE_NOISE;
     * baseline-only → SAME_VERSION_BUG; mixed → ORACLE_NOISE
     */
    private DiffVerdict classifyEventFailure(
            TestPlanFeedbackPacket[] packets) {
        boolean oldFailed = packets[0].isEventFailed;
        boolean rollingFailed = packets[1].isEventFailed;
        boolean newFailed = packets[2].isEventFailed;

        if (rollingFailed && !oldFailed && !newFailed) {
            return DiffVerdict.ROLLING_UPGRADE_BUG_CANDIDATE;
        }
        if (oldFailed && rollingFailed && newFailed) {
            return DiffVerdict.ORACLE_NOISE;
        }
        if (!rollingFailed && (oldFailed || newFailed)) {
            return DiffVerdict.SAME_VERSION_BUG;
        }
        // mixed: e.g. rolling+old but not new, or rolling+new but not old
        return DiffVerdict.ORACLE_NOISE;
    }

    /**
     * Classify per-cluster inconsistency across 3 lanes.
     */
    private DiffVerdict classifyPerClusterInconsistency(
            TestPlanFeedbackPacket[] packets) {
        boolean oldInc = packets[0].isInconsistent;
        boolean rollingInc = packets[1].isInconsistent;
        boolean newInc = packets[2].isInconsistent;

        if (rollingInc && !oldInc && !newInc) {
            return DiffVerdict.ROLLING_UPGRADE_BUG_CANDIDATE;
        }
        if (oldInc && rollingInc && newInc) {
            return DiffVerdict.ORACLE_NOISE;
        }
        if (!rollingInc && (oldInc || newInc)) {
            return DiffVerdict.SAME_VERSION_BUG;
        }
        return DiffVerdict.ORACLE_NOISE;
    }

    /**
     * Classify ERROR log signal across 3 lanes.
     */
    private DiffVerdict classifyErrorLogSignal(
            TestPlanFeedbackPacket[] packets) {
        boolean oldErr = packets[0].hasERRORLog;
        boolean rollingErr = packets[1].hasERRORLog;
        boolean newErr = packets[2].hasERRORLog;

        if (rollingErr && !oldErr && !newErr) {
            return DiffVerdict.ROLLING_UPGRADE_BUG_CANDIDATE;
        }
        if (oldErr && rollingErr && newErr) {
            return DiffVerdict.ORACLE_NOISE;
        }
        if (!rollingErr && (oldErr || newErr)) {
            return DiffVerdict.SAME_VERSION_BUG;
        }
        return DiffVerdict.ORACLE_NOISE;
    }

    /**
     * Strict gate for cross-cluster structured checker (Checker D).
     * All 3 lanes must be OK and all 3 must have non-null, non-empty
     * structured validationResults.
     */
    private boolean allLanesOkWithStructuredResults(
            TestPlanFeedbackPacket[] packets) {
        if (packets.length < 3)
            return false;
        for (int i = 0; i < 3; i++) {
            if (packets[i] == null)
                return false;
            if (normalizeLaneStatus(
                    packets[i]) != TestPlanFeedbackPacket.LaneStatus.OK)
                return false;
            if (packets[i].validationResults == null
                    || packets[i].validationResults.isEmpty())
                return false;
        }
        return true;
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

    /**
     * Create a failure directory routed by verdict.
     * candidate  → failure/candidate/failure_N/
     * same_version → failure/same_version/failure_N/
     * noise / infra / oracle → failure/noise/failure_N/
     */
    private Path createVerdictDir(String configFileName,
            DiffVerdict verdict) {
        String bucket;
        switch (verdict) {
        case ROLLING_UPGRADE_BUG_CANDIDATE:
            bucket = "candidate";
            break;
        case SAME_VERSION_BUG:
            bucket = "same_version";
            break;
        default:
            bucket = "noise";
            break;
        }
        Path bucketDir = Paths.get(Config.getConf().failureDir, bucket);
        bucketDir.toFile().mkdirs();
        while (Paths.get(bucketDir.toString(),
                "failure_" + failureId).toFile().exists()) {
            failureId++;
        }
        Path failureSubDir = Paths.get(bucketDir.toString(),
                "failure_" + failureId++);
        failureSubDir.toFile().mkdir();
        copyConfig(failureSubDir, configFileName);
        return failureSubDir;
    }

    private Path ensureVerdictDir(Path verdictDir, String configFileName,
            String fullSequence, DiffVerdict verdict) {
        if (verdictDir == null) {
            Path dir = createVerdictDir(configFileName, verdict);
            saveFullSequence(dir, fullSequence);
            return dir;
        }
        return verdictDir;
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
        saveEventCrashReport(failureDir, testID, report, null, null, null);
    }

    private void saveEventCrashReport(Path failureDir, int testID,
            String report, String laneTag) {
        saveEventCrashReport(failureDir, testID, report, laneTag, null,
                null);
    }

    private void saveEventCrashReport(Path failureDir, int testID,
            String report, String laneTag, DiffVerdict verdict,
            String configIdx) {
        Path subDir = createFailureSubDir(failureDir, "event_crash");
        String fileName = DiffReportHelper.reportFileName(
                DiffReportHelper.CheckerType.EVENT_CRASH, testID, laneTag);
        String fullReport = verdict != null
                ? DiffReportHelper.buildMetadataBlock(
                        DiffReportHelper.CheckerType.EVENT_CRASH,
                        laneTag, verdict, testID, configIdx, null)
                        + report
                : report;
        Path crashReportPath = Paths.get(subDir.toString(), fileName);
        Utilities.write2TXT(crashReportPath.toFile(), fullReport, false);
        eventCrashNum++;
    }

    private void saveLaneCollectionFailureReport(Path failureDir, int testID,
            String laneTag, String report) {
        Path subDir = createFailureSubDir(failureDir,
                "lane_collection_failure");
        String normalizedLaneTag = laneTag == null ? "unknown"
                : laneTag.toLowerCase();
        Path reportPath = Paths.get(
                subDir.toString(),
                String.format("lane_collection_failure_%s_%d.report",
                        normalizedLaneTag, testID));
        Utilities.write2TXT(reportPath.toFile(), report, false);
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
        saveInconsistencyReport(failureDir, testID, report, null, null);
    }

    private void saveInconsistencyReport(Path failureDir, int testID,
            String report, DiffVerdict verdict, String configIdx) {
        Path inconsistencySubDir = createFailureSubDir(failureDir,
                "inconsistency");
        String fileName = DiffReportHelper.reportFileName(
                DiffReportHelper.CheckerType.CROSS_CLUSTER_INCONSISTENCY,
                testID, null);
        String fullReport = verdict != null
                ? DiffReportHelper.buildMetadataBlock(
                        DiffReportHelper.CheckerType.CROSS_CLUSTER_INCONSISTENCY,
                        null, verdict, testID, configIdx, null)
                        + report
                : report;
        Path crashReportPath = Paths.get(
                inconsistencySubDir.toString(), fileName);
        Utilities.write2TXT(crashReportPath.toFile(), fullReport, false);
        inconsistencyNum++;
    }

    private void saveErrorReport(Path failureDir, String report, int testID) {
        saveErrorReport(failureDir, report, testID, (String) null);
    }

    private void saveErrorReport(Path failureDir, String report, int testID,
            String laneTag) {
        saveErrorReport(failureDir, report, testID, laneTag, null, null);
    }

    private void saveErrorReport(Path failureDir, String report, int testID,
            String laneTag, DiffVerdict verdict, String configIdx) {
        Path errorSubDir = createFailureSubDir(failureDir, "errorLog");
        String fileName = DiffReportHelper.reportFileName(
                DiffReportHelper.CheckerType.ERROR_LOG, testID, laneTag);
        String fullReport = verdict != null
                ? DiffReportHelper.buildMetadataBlock(
                        DiffReportHelper.CheckerType.ERROR_LOG,
                        laneTag, verdict, testID, configIdx, null)
                        + report
                : report;
        Path reportPath = Paths.get(errorSubDir.toString(), fileName);
        Utilities.write2TXT(reportPath.toFile(), fullReport, false);
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
            System.out.format("|%30s|%30s|%30s|\n",
                    "trace : "
                            + Config.getConf().useTrace,
                    "testPlanCorpus : " + testPlanCorpus.queue.size(),
                    "rollingSeedCorpus : " + rollingSeedCorpus.size());
            // Phase 2: tiered pool sizes and cap-rejection counters
            System.out.format("|%30s|%30s|%30s|\n",
                    "rolling BB/probation/promoted : "
                            + rollingSeedCorpus.branchBackedSize() + "/"
                            + rollingSeedCorpus.traceProbationSize() + "/"
                            + rollingSeedCorpus.tracePromotedSize(),
                    "trace promoted/evicted : "
                            + rollingSeedCorpus.getTotalTracePromoted() + "/"
                            + rollingSeedCorpus
                                    .getTotalTraceProbationEvicted(),
                    "trace rej round/100/share : "
                            + rollingSeedCorpus
                                    .getTotalTraceRejectedPerRound()
                            + "/"
                            + rollingSeedCorpus.getTotalTraceRejectedWindow()
                            + "/"
                            + rollingSeedCorpus
                                    .getTotalTraceRejectedShare());
            System.out.format("|%30s|%30s|%30s|\n",
                    "lane timeout old/roll/new : " + oldOldLaneTimeoutNum + "/"
                            + rollingLaneTimeoutNum + "/"
                            + newNewLaneTimeoutNum,
                    "lane collect fail old/roll/new : "
                            + oldOldLaneCollectionFailureNum + "/"
                            + rollingLaneCollectionFailureNum + "/"
                            + newNewLaneCollectionFailureNum,
                    "");
            System.out.format("|%30s|%30s|%30s|\n",
                    "trace sig live/recent : "
                            + traceSignatureIndex.liveSignatureCount() + "/"
                            + traceSignatureIndex.recentEntryCount(),
                    "trace sig suppress : "
                            + traceSignatureIndex
                                    .getTotalSuppressedDuplicates(),
                    "trace sig evict look/cap : "
                            + traceSignatureIndex.getTotalLookbackEvictions()
                            + "/"
                            + traceSignatureIndex
                                    .getTotalCapacityEvictions());
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
        // Verdict breakdown (WS0)
        System.out.format("|%30s|%30s|%30s|%30s|\n",
                "candidates : " + candidateNum,
                "same-version bugs : " + sameVersionBugNum,
                "noise : " + noiseNum,
                "");
        System.out.println(
                "------------------------------------------------------------"
                        + "-----------------------------------------------------------------");
        if (Config.getConf().staticVD && finishedTestID
                % Config.getConf().staticVDMeasureInterval == 0) {
            oriObjCoverage.measureCoverageOfModifiedReferences(
                    modifiedSerializedFields, true);
        }

        // Phase 0: compact admission counters line, then flush CSV artifacts
        if (observabilityMetrics.isEnabled()) {
            System.out.format("|%30s|%30s|%30s|%30s|\n",
                    "adm branch : "
                            + observabilityMetrics.getAdmissionCount(
                                    AdmissionReason.BRANCH_ONLY),
                    "adm br+tr : "
                            + observabilityMetrics.getAdmissionCount(
                                    AdmissionReason.BRANCH_AND_TRACE),
                    "adm tr-excl : "
                            + observabilityMetrics.getAdmissionCount(
                                    AdmissionReason.TRACE_ONLY_TRIDIFF_EXCLUSIVE),
                    "adm tr-miss : "
                            + observabilityMetrics.getAdmissionCount(
                                    AdmissionReason.TRACE_ONLY_TRIDIFF_MISSING));
            System.out.format("|%30s|%30s|%30s|%30s|\n",
                    "adm tr-sim : "
                            + observabilityMetrics.getAdmissionCount(
                                    AdmissionReason.TRACE_ONLY_WINDOW_SIM),
                    "seeds tracked : "
                            + observabilityMetrics.lifecycleCount(),
                    "round rows : "
                            + observabilityMetrics.admissionRowCount(),
                    "window rows : "
                            + observabilityMetrics.windowRowCount());
            observabilityMetrics.writeAllArtifacts();
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

    /**
     * Map the raw trigger flags to a single admission reason. Branch coverage
     * always wins; within the trace-only fallback, tri-diff exclusive beats
     * window/aggregate similarity.
     *
     * <p>Phase 1 hotfix: missing-message churn is no longer a direct admission
     * path, so it is not accepted as a primary reason here either. A round
     * where missing co-fires alongside an actual admission signal must be
     * attributed to the signal that genuinely admitted it; otherwise the
     * {@link AdmissionReason#TRACE_ONLY_TRIDIFF_MISSING} counter would
     * overcount and make the Phase 1 hotfix impossible to verify from the CSV
     * summary. The fact that missing co-fired is still recorded on
     * {@link AdmissionSummaryRow#triDiffMissingFired} (boolean column) for
     * observability.
     */
    static AdmissionReason classifyAdmissionReason(
            boolean newBranchCoverage,
            boolean traceInteresting,
            boolean triDiffExclusiveFired,
            boolean windowSimFired,
            boolean aggregateSimFired) {
        if (newBranchCoverage) {
            return traceInteresting ? AdmissionReason.BRANCH_AND_TRACE
                    : AdmissionReason.BRANCH_ONLY;
        }
        if (triDiffExclusiveFired) {
            return AdmissionReason.TRACE_ONLY_TRIDIFF_EXCLUSIVE;
        }
        if (windowSimFired || aggregateSimFired) {
            return AdmissionReason.TRACE_ONLY_WINDOW_SIM;
        }
        return AdmissionReason.UNKNOWN;
    }

    /**
     * Phase 4 dedup gate. Branch-backed admissions are always exempt; trace-
     * only admissions are suppressed only when every interesting-window
     * signature for the round is already saturated in the recent-signature
     * index.
     */
    static boolean shouldSuppressTraceOnlyBySignature(
            boolean newBranchCoverage,
            List<TraceSignature> interestingTraceSignatures,
            long round,
            RecentTraceSignatureIndex recentTraceSignatureIndex) {
        if (newBranchCoverage || recentTraceSignatureIndex == null) {
            return false;
        }
        return recentTraceSignatureIndex.shouldSuppress(
                interestingTraceSignatures, round);
    }

    // === Phase 4: Canonical trace scoring helpers ===

    /**
     * Aligned comparable windows across three lanes for one stage.
     */
    static class AlignedWindow {
        final TraceWindow oldOld;
        final TraceWindow rolling;
        final TraceWindow newNew;

        AlignedWindow(TraceWindow oo, TraceWindow ro, TraceWindow nn) {
            this.oldOld = oo;
            this.rolling = ro;
            this.newNew = nn;
        }
    }

    /**
     * Align comparable windows across three lanes by
     * (comparisonStageId, normalizedTransitionNodeSet).
     * Returns list of aligned window triples.
     *
     * <p>Fast path: when all three lanes have equal window counts, aligns
     * positionally and validates stage-key consistency.
     *
     * <p>Partial alignment: when the rolling lane has exactly 1 fewer
     * comparable window than both baselines (OO=N, RO=N-1, NN=N), attempts
     * order-preserving key-based matching. For each rolling window, searches
     * forward from the last matched position in OO and NN to find matching
     * stage keys. If a unique monotonic match cannot be found, abstains.
     *
     * <p>All other mismatches (counts differ by 2+, rolling has more than
     * baselines, baselines disagree, ambiguous keys) produce full abstain.
     */
    static List<AlignedWindow> alignWindows(
            WindowedTrace oldOldTrace,
            WindowedTrace rollingTrace,
            WindowedTrace newNewTrace) {

        List<TraceWindow> oo = oldOldTrace.getComparableWindows();
        List<TraceWindow> ro = rollingTrace.getComparableWindows();
        List<TraceWindow> nn = newNewTrace.getComparableWindows();

        // Fast path: all same size → positional alignment
        if (oo.size() == ro.size() && ro.size() == nn.size()) {
            List<AlignedWindow> aligned = new ArrayList<>();
            for (int i = 0; i < oo.size(); i++) {
                TraceWindow wOO = oo.get(i);
                TraceWindow wRO = ro.get(i);
                TraceWindow wNN = nn.get(i);

                if (stageMatches(wOO, wRO) && stageMatches(wRO, wNN)) {
                    aligned.add(new AlignedWindow(wOO, wRO, wNN));
                } else {
                    logger.warn(
                            "[TRACE] Window alignment mismatch at [{}]: OO={}/{}, RO={}/{}, NN={}/{} — abstaining",
                            i, wOO.comparisonStageId,
                            wOO.normalizedTransitionNodeSet,
                            wRO.comparisonStageId,
                            wRO.normalizedTransitionNodeSet,
                            wNN.comparisonStageId,
                            wNN.normalizedTransitionNodeSet);
                    logStageKeysOnFailure(oo, ro, nn);
                    return Collections.emptyList();
                }
            }
            return aligned;
        }

        // Partial alignment: rolling has exactly 1 fewer window than both
        // baselines (e.g. OO=3, RO=2, NN=3). Match by stage key with
        // monotonic forward search to preserve lane order.
        if (oo.size() == nn.size() && ro.size() == oo.size() - 1) {
            // Guard: if any lane has duplicate stage keys, the forward
            // search could silently bind to the wrong window. Abstain
            // instead. (Executor can emit repeated POST_FINAL_STAGE when
            // the normalized node set is already complete.)
            if (hasDuplicateStageKeys(oo) || hasDuplicateStageKeys(ro)
                    || hasDuplicateStageKeys(nn)) {
                logger.warn(
                        "[TRACE] Partial alignment: duplicate stage keys detected — abstaining (OO={}, RO={}, NN={})",
                        oo.size(), ro.size(), nn.size());
                logStageKeysOnFailure(oo, ro, nn);
                return Collections.emptyList();
            }

            logger.info(
                    "[TRACE] Window count mismatch (partial alignment): OO={}, RO={}, NN={}",
                    oo.size(), ro.size(), nn.size());
            List<AlignedWindow> aligned = new ArrayList<>();
            int ooCursor = 0;
            int nnCursor = 0;

            for (int ri = 0; ri < ro.size(); ri++) {
                TraceWindow roWin = ro.get(ri);

                // Search forward in OO from ooCursor for a stage-key match
                int ooIdx = findForwardMatch(oo, ooCursor, roWin);
                if (ooIdx < 0) {
                    logger.warn(
                            "[TRACE] Partial alignment: no OO match for RO window {} (stage={}/{})",
                            ri, roWin.comparisonStageId,
                            roWin.normalizedTransitionNodeSet);
                    logStageKeysOnFailure(oo, ro, nn);
                    return Collections.emptyList();
                }

                // Search forward in NN from nnCursor for a stage-key match
                int nnIdx = findForwardMatch(nn, nnCursor, roWin);
                if (nnIdx < 0) {
                    logger.warn(
                            "[TRACE] Partial alignment: no NN match for RO window {} (stage={}/{})",
                            ri, roWin.comparisonStageId,
                            roWin.normalizedTransitionNodeSet);
                    logStageKeysOnFailure(oo, ro, nn);
                    return Collections.emptyList();
                }

                aligned.add(
                        new AlignedWindow(oo.get(ooIdx), roWin,
                                nn.get(nnIdx)));
                ooCursor = ooIdx + 1;
                nnCursor = nnIdx + 1;
            }
            logger.info(
                    "[TRACE] Partial alignment matched {} of {} rolling windows",
                    aligned.size(), ro.size());
            return aligned;
        }

        // Other mismatches: full abstain
        logger.warn(
                "[TRACE] Window count mismatch: OO={}, RO={}, NN={} — abstaining",
                oo.size(), ro.size(), nn.size());
        logStageKeysOnFailure(oo, ro, nn);
        return Collections.emptyList();
    }

    /**
     * Search forward from {@code startIdx} in {@code windows} for the first
     * window whose stage key matches {@code target}. Returns the index, or
     * -1 if no match is found.
     */
    private static int findForwardMatch(List<TraceWindow> windows,
            int startIdx, TraceWindow target) {
        for (int i = startIdx; i < windows.size(); i++) {
            if (stageMatches(windows.get(i), target)) {
                return i;
            }
        }
        return -1;
    }

    /**
     * Returns true if any two windows in the list share the same
     * (comparisonStageId, normalizedTransitionNodeSet) key. Used to
     * detect ambiguity before attempting key-based partial alignment.
     */
    private static boolean hasDuplicateStageKeys(List<TraceWindow> windows) {
        for (int i = 0; i < windows.size(); i++) {
            for (int j = i + 1; j < windows.size(); j++) {
                if (stageMatches(windows.get(i), windows.get(j))) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * Emit DEBUG-level diagnostics showing stage keys for each lane when
     * alignment fails. Avoids log spam on successful alignment.
     */
    private static void logStageKeysOnFailure(List<TraceWindow> oo,
            List<TraceWindow> ro, List<TraceWindow> nn) {
        if (!logger.isDebugEnabled())
            return;
        logger.debug("[TRACE] OO stages: {}", formatStageKeys(oo));
        logger.debug("[TRACE] RO stages: {}", formatStageKeys(ro));
        logger.debug("[TRACE] NN stages: {}", formatStageKeys(nn));
    }

    private static String formatStageKeys(List<TraceWindow> windows) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < windows.size(); i++) {
            if (i > 0)
                sb.append(", ");
            TraceWindow w = windows.get(i);
            sb.append(w.comparisonStageId).append("/")
                    .append(w.normalizedTransitionNodeSet);
        }
        sb.append("]");
        return sb.toString();
    }

    private static boolean stageMatches(TraceWindow a, TraceWindow b) {
        return a.comparisonStageId.equals(b.comparisonStageId)
                && a.normalizedTransitionNodeSet
                        .equals(b.normalizedTransitionNodeSet);
    }

    private static int totalMapCount(Map<String, Integer> counts) {
        int total = 0;
        for (Integer value : counts.values()) {
            total += value;
        }
        return total;
    }

    // === Phase 1: tri-diff window decision ===

    /**
     * Explicit sub-decisions for a single aligned tri-diff window. Separating
     * {@code exclusiveInteresting} from {@code missingInteresting} is what lets
     * Phase 1 disable missing-only admission while still tracking missing-signal
     * counters for observability and later analysis. The {@code triDiffInteresting}
     * field is the final answer fed into corpus admission — after Phase 1 it is
     * equal to {@code exclusiveInteresting}.
     */
    static final class TriDiffWindowDecision {
        final boolean exclusiveInteresting;
        final boolean missingInteresting;
        final boolean triDiffInteresting;

        TriDiffWindowDecision(boolean exclusiveInteresting,
                boolean missingInteresting, boolean triDiffInteresting) {
            this.exclusiveInteresting = exclusiveInteresting;
            this.missingInteresting = missingInteresting;
            this.triDiffInteresting = triDiffInteresting;
        }
    }

    /**
     * Evaluate a single aligned tri-diff window.
     *
     * <p>Rolling-exclusive churn (messages the rolling lane produced that neither
     * baseline has) is the strong signal and is the only direct seed-admission
     * path after the Phase 1 hotfix. Missing-message churn (messages both
     * baselines share that rolling dropped) is still thresholded and reported,
     * but no longer contributes to {@code triDiffInteresting}. The missing path
     * is additionally stage-gated: {@code PRE_UPGRADE} windows never produce
     * {@code missingInteresting=true} because Apr 12 evidence showed benign
     * pre-upgrade missing drift was driving the "always-on" missing trigger.
     */
    static TriDiffWindowDecision evaluateTriDiffWindow(
            DiffComputeMessageTriDiff.MessageTriDiffResult triDiff,
            TraceWindow.StageKind stageKind,
            int rollingExclusiveMinCount,
            double rollingExclusiveFractionThreshold,
            int rollingMissingMinCount,
            double rollingMissingFractionThreshold) {
        int rollingExclusive = triDiff.rollingExclusiveCount();
        int rollingMissing = triDiff.rollingMissingCount();
        double exclusiveFraction = triDiff.rollingExclusiveFraction();
        double missingFraction = triDiff.rollingMissingFraction();

        boolean exclusiveInteresting = rollingExclusive >= rollingExclusiveMinCount
                && exclusiveFraction >= rollingExclusiveFractionThreshold;

        boolean preUpgradeStage = stageKind == TraceWindow.StageKind.PRE_UPGRADE;
        boolean missingInteresting = !preUpgradeStage
                && rollingMissing >= rollingMissingMinCount
                && missingFraction >= rollingMissingFractionThreshold;

        // Phase 1 hotfix: missing-only windows no longer admit seeds.
        // triDiffInteresting reflects the admission verdict; missingInteresting
        // is kept for observability so offline re-scoring and Phase 7 reruns
        // can reproduce the decision.
        boolean triDiffInteresting = exclusiveInteresting;

        return new TriDiffWindowDecision(exclusiveInteresting,
                missingInteresting, triDiffInteresting);
    }
}
