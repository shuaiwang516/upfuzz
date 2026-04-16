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
import org.zlab.upfuzz.fuzzingengine.server.observability.BranchNoveltyClass;
import org.zlab.upfuzz.fuzzingengine.server.observability.BranchNoveltyRow;
import org.zlab.upfuzz.fuzzingengine.server.observability.ObservabilityMetrics;
import org.zlab.upfuzz.fuzzingengine.server.observability.QueuePriorityClass;
import org.zlab.upfuzz.fuzzingengine.server.observability.StageNoveltyRow;
import org.zlab.upfuzz.fuzzingengine.server.observability.StructuredCandidateStrength;
import org.zlab.upfuzz.fuzzingengine.server.observability.TraceEvidenceStrength;
import org.zlab.upfuzz.fuzzingengine.server.observability.WeakCandidateKind;
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

    public TestPlanCorpus testPlanCorpus;
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

        // Phase 0: the short-term queue emits enqueue/dequeue rows through
        // the observability metrics instance, so it cannot be initialized
        // at field declaration time — ObservabilityMetrics is only
        // available after Config is loaded.
        this.testPlanCorpus = new TestPlanCorpus(this.observabilityMetrics);

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
        QueuedTestPlan queuedTestPlan = testPlanCorpus
                .pollQueuedTestPlan(finishedTestID);

        if (queuedTestPlan == null) {
            FullStopSeed fullStopSeed = fullStopCorpus.getSeed();
            if (fullStopSeed == null)
                return false;
            return generateAndEnqueueTestPlansFromFullStopSeed(fullStopSeed,
                    MAX_MUTATION_RETRY);
        }

        return mutateAndEnqueueExistingTestPlan(queuedTestPlan);
    }

    private boolean fuzzRollingTestPlan() {
        int MAX_MUTATION_RETRY = 50;
        QueuedTestPlan queuedTestPlan = testPlanCorpus
                .pollQueuedTestPlan(finishedTestID);

        if (queuedTestPlan == null) {
            RollingSeed rollingSeed = rollingSeedCorpus.getSeed();
            if (rollingSeed == null)
                return false;
            return generateAndEnqueueTestPlansFromRollingSeed(rollingSeed,
                    MAX_MUTATION_RETRY);
        }

        return mutateAndEnqueueExistingTestPlan(queuedTestPlan);
    }

    private boolean mutateAndEnqueueExistingTestPlan(
            QueuedTestPlan queuedTestPlan) {
        TestPlan testPlan = queuedTestPlan.plan;
        boolean anyEnqueued = false;
        int parentLineageId = testPlan.lineageTestId;
        if (parentLineageId >= 0) {
            observabilityMetrics.recordParentSelection(parentLineageId);
        }
        int mutationEpoch = queuedTestPlan.plannedMutationBudget > 0
                ? queuedTestPlan.plannedMutationBudget
                : Config.getConf().testPlanMutationEpoch;
        StageMutationHint stageHint = queuedTestPlan.stageMutationHint != null
                ? queuedTestPlan.stageMutationHint
                : StageMutationHint.empty();
        logger.info(
                "Phase 3 dequeue: schedulerClass={}, priorityClass={}, "
                        + "budget={}, lineageRoot={}, dequeueCount={}, "
                        + "stageHint={}",
                queuedTestPlan.schedulerClass,
                queuedTestPlan.priorityClass, mutationEpoch,
                queuedTestPlan.lineageRoot, queuedTestPlan.dequeueCount,
                stageHint);

        // Phase 4: confirmation-oriented children are emitted first
        // when the queued parent carries a confirmation budget. They
        // are deliberately generated outside the normal mutation
        // epoch loop so the strong-candidate path does not dilute the
        // exploration budget.
        int confirmationEmitted = emitConfirmationChildren(testPlan,
                queuedTestPlan, stageHint, parentLineageId);
        if (confirmationEmitted > 0) {
            anyEnqueued = true;
        }

        for (int i = 0; i < mutationEpoch; i++) {
            TestPlan mutateTestPlan = null;
            int j = 0;
            for (; j < Config.getConf().testPlanMutationRetry; j++) {
                mutateTestPlan = SerializationUtils.clone(testPlan);
                boolean mutationSuccess;
                try {
                    mutationSuccess = StageAwareTestPlanMutator.mutate(
                            mutateTestPlan, stageHint, commandPool,
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

    /**
     * Phase 4 confirmation path. Emits a small number of
     * low-edit-distance / replay / minimization children for queued
     * parents that carry {@code needsConfirmation=true}. Confirmation
     * children are deliberately cheaper variants — they exist to
     * reproduce strong or weak structured divergence without spending
     * exploration budget on mainline mutation families.
     *
     * <ol>
     *   <li>replay: clone the parent unchanged — the rolling-lane
     *       execution is non-deterministic enough that a pure replay
     *       can confirm or reject a candidate.</li>
     *   <li>low-edit-distance: tighten intervals near the hotspot
     *       <em>only</em>, so timing churn near the boundary is the
     *       only difference vs the parent.</li>
     *   <li>minimization: drop a single non-lifecycle event (shell
     *       command / fault / validation appendix) so the reduced
     *       plan still reproduces the candidate if the minimized
     *       event was irrelevant.</li>
     * </ol>
     *
     * <p>Returns the number of confirmation children successfully
     * enqueued so the caller can factor them into its epoch budget.
     */
    private int emitConfirmationChildren(TestPlan parent,
            QueuedTestPlan queuedTestPlan, StageMutationHint hint,
            int parentLineageId) {
        if (queuedTestPlan.confirmationBudgetRemaining <= 0) {
            return 0;
        }
        int emitted = 0;
        int budget = queuedTestPlan.confirmationBudgetRemaining;
        for (int c = 0; c < budget; c++) {
            TestPlan child = SerializationUtils.clone(parent);
            boolean ok;
            switch (c % 3) {
            case 0: // replay
                ok = true;
                break;
            case 1: // low-edit-distance: tighten intervals
                ok = StageAwareTestPlanMutator
                        .adjustIntervalAroundHotspot(child, hint);
                break;
            case 2: // minimization: drop a non-lifecycle event
                ok = minimizeOneNonLifecycleEvent(child, hint);
                break;
            default:
                ok = true;
                break;
            }
            if (!ok) {
                continue;
            }
            if (!testPlanVerifier(child.getEvents(), parent.nodeNum)) {
                continue;
            }
            child.lineageTestId = -1;
            testID2TestPlan.put(testID, child);
            if (parentLineageId >= 0) {
                observabilityMetrics.linkChildToParent(testID,
                        parentLineageId);
            }
            int configIdx = configGen.generateConfig();
            String configFileName = "test" + configIdx;
            testPlanPackets.add(new TestPlanPacket(
                    Config.getConf().system, testID++, configFileName,
                    child));
            emitted++;
        }
        queuedTestPlan.confirmationBudgetRemaining = Math.max(0,
                budget - emitted);
        if (emitted > 0) {
            logger.info(
                    "Phase 4 emitted {} confirmation children (budgetLeft={}, "
                            + "signal={}, lineageRoot={})",
                    emitted, queuedTestPlan.confirmationBudgetRemaining,
                    hint.signalType, queuedTestPlan.lineageRoot);
        }
        return emitted;
    }

    /**
     * Minimize the cloned test plan in place by removing a single
     * non-lifecycle event near the hotspot. Never removes
     * {@link UpgradeOp} / {@link FinalizeUpgrade} / {@link PrepareUpgrade}
     * or a fault without its recover counterpart, so the upgrade
     * skeleton stays intact.
     */
    private static boolean minimizeOneNonLifecycleEvent(TestPlan plan,
            StageMutationHint hint) {
        List<Event> events = plan.events;
        if (events == null || events.size() <= 1) {
            return false;
        }
        int hotspot = StageAwareTestPlanMutator.locateHotspotAnchor(events,
                hint);
        if (hotspot < 0) {
            hotspot = events.size() / 2;
        }
        // Search outward from the hotspot for the first deletable event.
        for (int radius = 0; radius < events.size(); radius++) {
            for (int sign : new int[] { 1, -1 }) {
                int idx = hotspot + sign * radius;
                if (idx < 0 || idx >= events.size()) {
                    continue;
                }
                Event e = events.get(idx);
                if (e instanceof UpgradeOp
                        || e instanceof FinalizeUpgrade
                        || e instanceof PrepareUpgrade) {
                    continue;
                }
                if (e instanceof Fault || e instanceof FaultRecover) {
                    // Skip: dropping one half of a fault pair would
                    // leave the plan in an inconsistent fault state.
                    continue;
                }
                events.remove(idx);
                return true;
            }
        }
        return false;
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
            // Phase 5: carry stage snapshots from the first non-empty slot
            if (feedBack.stageCoverageSnapshots != null
                    && !feedBack.stageCoverageSnapshots.isEmpty()
                    && fb.stageCoverageSnapshots.isEmpty()) {
                fb.stageCoverageSnapshots
                        .putAll(feedBack.stageCoverageSnapshots);
            }
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

        // Phase 0 branch novelty source attribution. The new probe sets
        // must be collected before any merge because the merges commit
        // this round's coverage into the running aggregate.
        int oldVersionBaselineOnlyProbes = 0;
        int oldVersionRollingOnlyProbes = 0;
        int oldVersionSharedProbes = 0;
        int newVersionBaselineOnlyProbes = 0;
        int newVersionRollingOnlyProbes = 0;
        int newVersionSharedProbes = 0;

        if (Config.getConf().useBranchCoverage) {
            java.util.Map<Long, java.util.BitSet> oldVersionBaselineNew = Utilities
                    .collectNewProbeIds(curOriCoverage,
                            fbOld.originalCodeCoverage);
            java.util.Map<Long, java.util.BitSet> oldVersionRollingNew = Utilities
                    .collectNewProbeIds(curOriCoverage,
                            fbRolling.originalCodeCoverage);
            java.util.Map<Long, java.util.BitSet> newVersionRollingNew = Utilities
                    .collectNewProbeIds(curUpCoverageAfterUpgrade,
                            fbRolling.upgradedCodeCoverage);
            java.util.Map<Long, java.util.BitSet> newVersionBaselineNew = Utilities
                    .collectNewProbeIds(curUpCoverageAfterUpgrade,
                            fbNew.originalCodeCoverage);

            oldVersionSharedProbes = Utilities.intersectProbeCount(
                    oldVersionBaselineNew, oldVersionRollingNew);
            oldVersionBaselineOnlyProbes = Utilities
                    .countProbes(oldVersionBaselineNew)
                    - oldVersionSharedProbes;
            oldVersionRollingOnlyProbes = Utilities
                    .countProbes(oldVersionRollingNew)
                    - oldVersionSharedProbes;
            newVersionSharedProbes = Utilities.intersectProbeCount(
                    newVersionBaselineNew, newVersionRollingNew);
            newVersionBaselineOnlyProbes = Utilities
                    .countProbes(newVersionBaselineNew)
                    - newVersionSharedProbes;
            newVersionRollingOnlyProbes = Utilities
                    .countProbes(newVersionRollingNew)
                    - newVersionSharedProbes;

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

        // Phase 5: classify the round's branch novelty from the probe
        // counts computed above. This label feeds the scheduler score
        // boost and is recorded on BranchNoveltyRow and SeedLifecycle.
        BranchNoveltyClass branchNoveltyClass = CoverageDeltaUtil
                .classifyNovelty(
                        oldVersionBaselineOnlyProbes,
                        oldVersionRollingOnlyProbes,
                        oldVersionSharedProbes,
                        newVersionBaselineOnlyProbes,
                        newVersionRollingOnlyProbes,
                        newVersionSharedProbes);
        // Phase 5: per-version novelty source labels for observability
        String oldVersionNoveltySource = CoverageDeltaUtil
                .classifyVersionNoveltySource(
                        oldVersionBaselineOnlyProbes,
                        oldVersionRollingOnlyProbes,
                        oldVersionSharedProbes);
        String newVersionNoveltySource = CoverageDeltaUtil
                .classifyVersionNoveltySource(
                        newVersionBaselineOnlyProbes,
                        newVersionRollingOnlyProbes,
                        newVersionSharedProbes);

        // Phase 5: stage-level novelty attribution from optional
        // ordered snapshots. Keys are AFTER_UPGRADE_0, AFTER_UPGRADE_1,
        // ..., AFTER_FINALIZE. We extract the first-upgrade snapshot,
        // the last-upgrade snapshot, and the finalize snapshot to
        // determine when novelty first appeared.
        if (Config.getConf().enableStageCoverageSnapshots
                && fbRolling.stageCoverageSnapshots != null
                && !fbRolling.stageCoverageSnapshots.isEmpty()) {
            org.jacoco.core.data.ExecutionDataStore firstUpgrade = null;
            org.jacoco.core.data.ExecutionDataStore lastUpgrade = null;
            org.jacoco.core.data.ExecutionDataStore afterFinalize = fbRolling.stageCoverageSnapshots
                    .get("AFTER_FINALIZE");
            int upgradeCount = 0;
            for (java.util.Map.Entry<String, org.jacoco.core.data.ExecutionDataStore> e : fbRolling.stageCoverageSnapshots
                    .entrySet()) {
                if (e.getKey().startsWith("AFTER_UPGRADE_")) {
                    if (firstUpgrade == null) {
                        firstUpgrade = e.getValue();
                    }
                    lastUpgrade = e.getValue();
                    upgradeCount++;
                }
            }
            int probesTotal = Utilities
                    .countProbes(Utilities.collectNewProbeIds(
                            curUpCoverageAfterUpgrade,
                            fbRolling.upgradedCodeCoverage));
            int probesAtFirst = firstUpgrade != null
                    ? Utilities.countProbes(Utilities.collectNewProbeIds(
                            curUpCoverageAfterUpgrade, firstUpgrade))
                    : -1;
            int probesAtLast = lastUpgrade != null
                    ? Utilities.countProbes(Utilities.collectNewProbeIds(
                            curUpCoverageAfterUpgrade, lastUpgrade))
                    : -1;
            int probesAtFin = afterFinalize != null
                    ? Utilities.countProbes(Utilities.collectNewProbeIds(
                            curUpCoverageAfterUpgrade, afterFinalize))
                    : -1;
            logger.info(
                    "[Phase5-STAGE] new-version novelty: firstUpgrade={}, "
                            + "lastUpgrade={}, finalize={}, total={}, "
                            + "upgradeSnapshots={}",
                    probesAtFirst, probesAtLast, probesAtFin,
                    probesTotal, upgradeCount);
            observabilityMetrics
                    .recordStageNovelty(new StageNoveltyRow(
                            finishedTestID,
                            testPlanDiffFeedbackPacket.testPacketID,
                            probesTotal,
                            probesAtFirst,
                            probesAtLast,
                            probesAtFin,
                            upgradeCount));
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
        // Phase 0 observability: per-round trace support accounting so the
        // admission summary row can distinguish support-backed trace
        // evidence from unsupported {@code all3=0} windows.
        int unsupportedTraceWindowCount = 0;
        int supportBackedTraceWindowCount = 0;
        int firingStrongWindowCount = 0;
        int firingWeakWindowCount = 0;
        int firingUnsupportedWindowCount = 0;
        List<TraceSignature> interestingTraceSignatures = new ArrayList<>();

        // Phase 4: capture the first firing window's stage so the
        // stage-aware mutator knows where to concentrate effort, and
        // union every firing window's rolling upgraded node set so
        // the mutator can target the boundary hotspots. "Firing"
        // here means either the per-window similarity check or the
        // tri-diff exclusive check fired on that window.
        String phase4HotStageId = "";
        TraceWindow.StageKind phase4HotStageKind = null;
        int phase4HotWindowOrdinal = -1;
        Set<Integer> phase4HotNodeSet = new LinkedHashSet<>();
        boolean phase4AnyPreUpgradeFired = false;
        boolean phase4AnyNonPreUpgradeFired = false;
        boolean phase4AnyPostUpgradeFired = false;
        boolean phase4AnyFaultRecoveryFired = false;

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

                    // Phase 2: compute the per-window corroboration inputs
                    // up front so {@link #evaluateTriDiffWindow} can
                    // consume them. {@code changedMessageCount} counts
                    // payloads whose class changed between versions on
                    // the rolling lane; {@code upgradedBoundaryEventCount}
                    // counts actual per-event boundary crossings — a
                    // message whose sender and receiver split between
                    // upgraded and non-upgraded nodes. The previous
                    // implementation used {@code rawUpgradedNodeSet.size()}
                    // which overstated corroboration (a stage could have
                    // several upgraded nodes but zero cross-version
                    // traffic).
                    int changedMessageCount = countChangedMessages(mergedRO);
                    int upgradedBoundaryEventCount = countUpgradedBoundaryCrossings(
                            mergedRO, aw.rolling.rawUpgradedNodeSet);

                    // --- Per-window tri-diff ---
                    boolean triDiffInteresting = false;
                    boolean triDiffExclusiveFired = false;
                    boolean triDiffMissingFired = false;
                    int rollingExclusive = 0;
                    int rollingMissing = 0;
                    int totalMessages = 0;
                    int totalAllThreeCount = 0;
                    int baselineSharedCount = 0;
                    double rollingExclusiveFraction = 0.0;
                    double rollingMissingFraction = 0.0;
                    Map<String, Integer> rollingExclusiveBuckets = Collections
                            .emptyMap();
                    Map<String, Integer> rollingMissingBuckets = Collections
                            .emptyMap();
                    // Phase 2 window-level evaluation results. Defaults
                    // mirror the "tri-diff skipped" case: no support, no
                    // stage credit, no change evidence. When the triDiff
                    // block runs, these are overwritten with the richer
                    // decision result; otherwise the fall-through path
                    // below re-derives them from the legacy classifier.
                    boolean supportGatePassed = false;
                    boolean stageGatePassed = false;
                    boolean changedMessageGatePassed = false;
                    TraceEvidenceStrength windowStrength = TraceEvidenceStrength.NONE;
                    boolean windowStrengthFromDecision = false;
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
                        baselineSharedCount = triDiff.baselineSharedCount();
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
                                        .getConf().rollingMissingFractionThreshold,
                                /* windowSimInteresting */ windowInteresting,
                                changedMessageCount,
                                upgradedBoundaryEventCount,
                                rollingMinSimilarity,
                                baselineSimilarity,
                                TraceStrengthGates
                                        .fromConfig(Config.getConf()));
                        triDiffExclusiveFired = decision.exclusiveInteresting;
                        triDiffMissingFired = decision.missingInteresting;
                        triDiffInteresting = decision.triDiffInteresting;
                        // Phase 2: the decision is now the single source of
                        // truth for per-window support / stage / change
                        // gating and the final strength label.
                        supportGatePassed = decision.supportGatePassed;
                        stageGatePassed = decision.stageGatePassed;
                        changedMessageGatePassed = decision.changedMessageGatePassed;
                        windowStrength = decision.traceEvidenceStrength;
                        windowStrengthFromDecision = true;
                    }

                    boolean windowFired = windowInteresting
                            || triDiffExclusiveFired;
                    if (!windowStrengthFromDecision) {
                        // Tri-diff was skipped (either
                        // useCanonicalMessageIdentityDiff=false or the
                        // window was below the min-event gate). Fall back
                        // to the legacy Phase 0 classifier so window-sim
                        // admissions still get a strength label. Support
                        // is locked to false here because the triDiff
                        // counters were never computed.
                        supportGatePassed = false;
                        stageGatePassed = false;
                        changedMessageGatePassed = false;
                        windowStrength = classifyWindowTraceEvidenceStrength(
                                windowFired,
                                supportGatePassed,
                                aw.rolling.stageKind,
                                changedMessageCount,
                                upgradedBoundaryEventCount);
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
                                    triDiffMissingFired,
                                    baselineSharedCount,
                                    changedMessageCount,
                                    upgradedBoundaryEventCount,
                                    windowStrength,
                                    supportGatePassed));
                    windowsEvaluatedThisRound++;
                    if (windowHasEnoughEvents) {
                        if (supportGatePassed) {
                            supportBackedTraceWindowCount++;
                        } else {
                            unsupportedTraceWindowCount++;
                        }
                    }
                    if (windowFired) {
                        switch (windowStrength) {
                        case STRONG:
                            firingStrongWindowCount++;
                            break;
                        case WEAK:
                            firingWeakWindowCount++;
                            break;
                        case UNSUPPORTED:
                            firingUnsupportedWindowCount++;
                            break;
                        default:
                            break;
                        }
                    }
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
                    // Phase 4: every firing window (trace OR triDiff)
                    // contributes to the stage hint. The first firing
                    // window becomes the hot stage; the node set is
                    // the union across all firings so the stage-aware
                    // mutator sees every upgraded boundary that
                    // corroborated this round.
                    if (windowInteresting || triDiffExclusiveFired) {
                        if (phase4HotWindowOrdinal < 0) {
                            phase4HotWindowOrdinal = aw.rolling.ordinal;
                            phase4HotStageId = aw.rolling.comparisonStageId;
                            phase4HotStageKind = aw.rolling.stageKind;
                        }
                        if (aw.rolling.rawUpgradedNodeSet != null) {
                            phase4HotNodeSet
                                    .addAll(aw.rolling.rawUpgradedNodeSet);
                        }
                        TraceWindow.StageKind firedKind = aw.rolling.stageKind;
                        if (firedKind == TraceWindow.StageKind.PRE_UPGRADE) {
                            phase4AnyPreUpgradeFired = true;
                        } else {
                            phase4AnyNonPreUpgradeFired = true;
                        }
                        if (firedKind == TraceWindow.StageKind.POST_STAGE
                                || firedKind == TraceWindow.StageKind.POST_FINAL_STAGE) {
                            phase4AnyPostUpgradeFired = true;
                        }
                        if (firedKind == TraceWindow.StageKind.FAULT_RECOVERY) {
                            phase4AnyFaultRecoveryFired = true;
                        }
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

        // Phase 2: round-level trace evidence strength. Computed right
        // after the trace scoring loop so the corpus / admission code
        // below can gate on it. Weak and unsupported labels are the
        // Apr15 noise pattern Phase 2 is designed to filter: PRE_UPGRADE
        // churn, all3=0 windows, and aggregate-sim-only rounds never
        // produce a STRONG label, so the enforcement below drops them
        // from trace-only admission and from BRANCH_AND_TRACE promotion
        // while keeping them visible on the observability rows.
        TraceEvidenceStrength traceEvidenceStrength = classifyRoundTraceEvidenceStrength(
                traceInteresting,
                firingStrongWindowCount,
                firingWeakWindowCount,
                firingUnsupportedWindowCount,
                aggregateSimFired);

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
        CrossClusterComparisonOutcome crossClusterOutcome = CrossClusterComparisonOutcome
                .none();
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
            crossClusterOutcome = checkCrossClusterInconsistencyStructured(
                    testPlanFeedbackPackets);
            logger.info(
                    "[CheckerD] diverged={}, strength={}, containsUnknown={}, containsDaemonError={}",
                    crossClusterOutcome.diverged,
                    crossClusterOutcome.strength,
                    crossClusterOutcome.containsUnknown,
                    crossClusterOutcome.containsDaemonError);
            if (crossClusterOutcome.diverged) {
                logger.info("[CheckerD] report:\n{}",
                        crossClusterOutcome.report);
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
                + (crossClusterOutcome.diverged ? "CANDIDATE" : "NONE")
                + " errorLog=" + errorLogVerdict);

        // === Corpus update (gated by verdict) ===
        // Phase 2 enforcement: trace evidence only drives admission when
        // the round-level label is STRONG. Weak and unsupported rounds
        // never produce trace-only admissions and never upgrade a
        // branch-only admission into BRANCH_AND_TRACE — this is the core
        // filter Phase 2 adds to the Phase 1 exclusive-only admission
        // contract. {@code traceInteresting} stays as the raw signal for
        // observability so offline replay can see which rounds fired at
        // the Phase 0/1 level; {@code effectiveTraceInteresting} is the
        // Phase 2 decision that actually feeds the admission path.
        boolean effectiveTraceInteresting = isPhase2TraceAdmissible(
                traceInteresting, traceEvidenceStrength);
        boolean addToCorpus = newOriBC || newUpgradeBC
                || effectiveTraceInteresting;
        boolean newBranchCoverage = newOriBC || newUpgradeBC;
        if (traceInteresting && !effectiveTraceInteresting) {
            logger.info(
                    "[TRACE] Phase 2 demoted trace evidence: strength={}, "
                            + "strongWindows={}, weakWindows={}, unsupportedWindows={}, "
                            + "aggregateSim={}",
                    traceEvidenceStrength,
                    firingStrongWindowCount,
                    firingWeakWindowCount,
                    firingUnsupportedWindowCount,
                    aggregateSimFired);
        }
        // WS0: exclude same-version bugs from corpus to avoid FP-prone
        // descendants
        if (overallVerdict == DiffVerdict.SAME_VERSION_BUG) {
            logger.info(
                    "Suppressing corpus add: test triggers SAME_VERSION_BUG");
            addToCorpus = false;
        }

        // Phase 1: confidence labels now come directly from the checker-D
        // outcome, so the comparator's row-level strength policy is the
        // single source of truth for how strong the structured divergence
        // is. The packet-level UNKNOWN/DAEMON_ERROR scan is still available
        // as a fallback, but Phase 1 prefers the comparator result because
        // it understands payload/failure-class/asymmetric divergence.
        boolean structuredCandidate = crossClusterOutcome.diverged;
        StructuredCandidateStrength structuredCandidateStrength = crossClusterOutcome.strength;
        if (structuredCandidate
                && structuredCandidateStrength == StructuredCandidateStrength.NONE) {
            // Defensive: if the outcome is diverged but the strength slot
            // somehow ended up NONE, fall back to the packet scan so we
            // never accidentally call a divergent round NONE.
            structuredCandidateStrength = classifyStructuredCandidateStrength(
                    structuredCandidate, testPlanFeedbackPackets);
        }
        boolean strongStructuredCandidate = structuredCandidate
                && structuredCandidateStrength == StructuredCandidateStrength.STRONG;
        boolean weakStructuredCandidate = structuredCandidate
                && !strongStructuredCandidate;

        // Rolling-only event / error-log candidates are always weak
        // unless a strong structured divergence coexists (which then
        // promotes the whole round into the strong bucket). They appear
        // in the candidate/weak directory and never call
        // notifyStructuredCandidatePayoff.
        boolean rollingOnlyEventCandidate = !structuredCandidate
                && eventVerdict == DiffVerdict.ROLLING_UPGRADE_BUG_CANDIDATE;
        boolean rollingOnlyErrorLogCandidate = !structuredCandidate
                && errorLogVerdict == DiffVerdict.ROLLING_UPGRADE_BUG_CANDIDATE;

        // CSV-backward-compat weakCandidate flag: keeps the Phase 0
        // semantics ("not structured AND rolling-only event/error") so
        // existing offline parsers of trace_admission_summary.csv still
        // report the same column meaning. Phase 1 tracks unstable
        // structured divergence separately.
        boolean weakCandidate = rollingOnlyEventCandidate
                || rollingOnlyErrorLogCandidate;

        // Test-level routing strength: STRONG if any strong structured
        // divergence, else WEAK if any weak candidate fires, else NONE.
        // All candidate reports for this run land in a single
        // failure/candidate/{strong,weak}/failure_N directory so
        // event / inconsistency / errorLog reports stay co-located.
        StructuredCandidateStrength testLevelCandidateStrength;
        if (strongStructuredCandidate) {
            testLevelCandidateStrength = StructuredCandidateStrength.STRONG;
        } else if (weakStructuredCandidate || rollingOnlyEventCandidate
                || rollingOnlyErrorLogCandidate) {
            testLevelCandidateStrength = StructuredCandidateStrength.WEAK;
        } else {
            testLevelCandidateStrength = StructuredCandidateStrength.NONE;
        }

        // Phase 0 weak-candidate sub-classifier uses the new
        // comparator-backed strength value. The round-level trace
        // evidence strength was already computed before the verdict
        // block so the Phase 2 enforcement on {@code addToCorpus} can
        // see it; keep that single computation as the source of truth
        // rather than recomputing here.
        WeakCandidateKind weakCandidateKind = classifyWeakCandidateKind(
                structuredCandidate,
                structuredCandidateStrength,
                eventVerdict,
                errorLogVerdict);
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
                // Phase 3: also credit any queued plans descended from
                // this lineage so the scheduler prefers parents that
                // already produced novelty.
                testPlanCorpus.notifyBranchPayoff(parentLineageRoot);
            }
        }
        // Phase 5: credit the parent when this round produced STRONG
        // trace evidence. This is independent of candidate payoff — a
        // seed can produce strong trace patterns without triggering a
        // structured divergence.
        if (traceEvidenceStrength == TraceEvidenceStrength.STRONG) {
            observabilityMetrics.recordDownstreamStrongTraceHit(
                    testPlanDiffFeedbackPacket.testPacketID);
        }
        // Phase 1: only strong structured candidates are allowed to
        // promote a probationary seed. Weak structured candidates are
        // still tracked for review via
        // recordDownstreamWeakStructuredCandidateHit
        // but never call notifyStructuredCandidatePayoff.
        if (strongStructuredCandidate) {
            observabilityMetrics.recordDownstreamStructuredCandidateHit(
                    testPlanDiffFeedbackPacket.testPacketID);
            if (parentLineageRoot >= 0) {
                rollingSeedCorpus
                        .notifyStructuredCandidatePayoff(parentLineageRoot);
                testPlanCorpus.notifyStrongCandidatePayoff(parentLineageRoot);
            }
        }
        if (weakStructuredCandidate) {
            observabilityMetrics
                    .recordDownstreamWeakStructuredCandidateHit(
                            testPlanDiffFeedbackPacket.testPacketID);
        }
        if (rollingOnlyEventCandidate) {
            observabilityMetrics.recordDownstreamWeakEventCandidateHit(
                    testPlanDiffFeedbackPacket.testPacketID);
        }
        if (rollingOnlyErrorLogCandidate) {
            observabilityMetrics.recordDownstreamWeakErrorLogCandidateHit(
                    testPlanDiffFeedbackPacket.testPacketID);
        }
        if (weakCandidate) {
            observabilityMetrics.recordDownstreamWeakCandidateHit(
                    testPlanDiffFeedbackPacket.testPacketID);
            // Weak candidates intentionally do not feed Phase 2 promotion
            // until Phase 5 cleans up candidate routing — they are too
            // noisy to drive retention.
            if (parentLineageRoot >= 0) {
                testPlanCorpus.notifyWeakCandidatePayoff(parentLineageRoot);
            }
        }

        boolean traceSignatureSuppressed = false;
        boolean isRollingMode = Config.getConf().testingMode == 5
                || Config.getConf().testingMode == 6;
        if (addToCorpus
                && isRollingMode
                && effectiveTraceInteresting
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
        QueuePriorityClass queuePriorityClassForRow = QueuePriorityClass.UNKNOWN;
        boolean admittedThisRound = false;
        RollingSeedCorpus.AdmissionOutcome rollingCorpusOutcome = null;
        if (addToCorpus) {
            TestPlan testPlan = testID2TestPlan
                    .get(testPlanDiffFeedbackPacket.testPacketID);
            if (testPlan != null) {
                // Phase 2 enforcement: use the effective trace signal
                // (already gated on round-level STRONG) when selecting
                // the admission reason so BRANCH_AND_TRACE / TRACE_ONLY_*
                // cannot be produced by weak or unsupported trace. The
                // CSV still receives the raw {@code traceInteresting}
                // flag separately for observability.
                AdmissionReason admissionReason = classifyAdmissionReason(
                        newBranchCoverage,
                        effectiveTraceInteresting,
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
                                    testPlanDiffFeedbackPacket.testPacketID),
                            branchNoveltyClass);

                    queuePriorityClassForRow = classifyQueuePriorityClass(
                            admissionReason, traceEvidenceStrength);
                    // Phase 4: build the stage-focused mutation hint
                    // from the per-window accumulators we captured
                    // during trace scoring. A missing hot window
                    // (e.g. a pure-branch admission with no firing
                    // trace window) yields {@link StageMutationHint#empty()}
                    // and the mutator falls back to generic mutation.
                    StageMutationHint stageMutationHint = buildStageMutationHint(
                            phase4HotStageId,
                            phase4HotStageKind,
                            phase4HotWindowOrdinal,
                            phase4HotNodeSet,
                            phase4AnyPreUpgradeFired,
                            phase4AnyNonPreUpgradeFired,
                            phase4AnyPostUpgradeFired,
                            phase4AnyFaultRecoveryFired,
                            newBranchCoverage,
                            traceEvidenceStrength,
                            testLevelCandidateStrength,
                            rollingOnlyEventCandidate
                                    || rollingOnlyErrorLogCandidate,
                            strongStructuredCandidate
                                    || weakStructuredCandidate);
                    // Phase 3: when this admission also produced a
                    // strong structured candidate, promote the plan
                    // into the repro_confirm lane so the scheduler
                    // allocates extra mutation budget specifically to
                    // re-observe and stabilize the candidate. Otherwise
                    // fall through to the normal queue-class mapping.
                    if (strongStructuredCandidate) {
                        testPlanCorpus.addCandidateParent(
                                testPlan,
                                finishedTestID,
                                parentLineageRoot,
                                admissionReason,
                                traceEvidenceStrength,
                                structuredCandidateStrength,
                                queuePriorityClassForRow,
                                stageMutationHint,
                                branchNoveltyClass);
                    } else {
                        testPlanCorpus.addTestPlan(
                                testPlan,
                                finishedTestID,
                                parentLineageRoot,
                                admissionReason,
                                traceEvidenceStrength,
                                structuredCandidateStrength,
                                queuePriorityClassForRow,
                                stageMutationHint,
                                branchNoveltyClass);
                    }
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
                candidateDir = ensureCandidateVerdictDir(candidateDir,
                        rollingFb.configFileName, rollingFb.fullSequence,
                        testLevelCandidateStrength);
                saveEventCrashReport(candidateDir,
                        testPlanDiffFeedbackPacket.testPacketID,
                        DiffReportHelper.eventCrashHeader("Rolling")
                                + rollingFb.eventFailedReport,
                        "Rolling",
                        DiffVerdict.ROLLING_UPGRADE_BUG_CANDIDATE,
                        rollingFb.configFileName,
                        testLevelCandidateStrength,
                        weakCandidateKind);
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

        // 3. Cross-cluster inconsistency → tri-diff is always a rolling
        // candidate. Phase 1 routes strong vs weak (unstable) structured
        // divergence into separate subdirectories, and the report body
        // already carries the confidence metadata line inserted by
        // checkCrossClusterInconsistencyStructured.
        if (crossClusterOutcome.diverged) {
            candidateDir = ensureCandidateVerdictDir(candidateDir,
                    rollingFb.configFileName, rollingFb.fullSequence,
                    testLevelCandidateStrength);
            saveInconsistencyReport(candidateDir,
                    testPlanDiffFeedbackPacket.testPacketID,
                    crossClusterOutcome.report,
                    DiffVerdict.ROLLING_UPGRADE_BUG_CANDIDATE,
                    rollingFb.configFileName,
                    structuredCandidateStrength,
                    weakCandidateKind,
                    crossClusterOutcome.containsUnknown,
                    crossClusterOutcome.containsDaemonError);
            candidateNum++;
        }

        // 4. Per-cluster inconsistency (Checker E) — REMOVED.
        // Lane-local isInconsistent flags are no longer set by Checker C.

        // 5. ERROR logs — routed by errorLogVerdict
        if (anyErrorLog) {
            if (errorLogVerdict == DiffVerdict.ROLLING_UPGRADE_BUG_CANDIDATE) {
                candidateDir = ensureCandidateVerdictDir(candidateDir,
                        rollingFb.configFileName, rollingFb.fullSequence,
                        testLevelCandidateStrength);
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
                                rollingFb.configFileName,
                                testLevelCandidateStrength,
                                weakCandidateKind);
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
                traceSignatureIndex.getTotalSuppressedDuplicates(),
                structuredCandidateStrength,
                weakCandidateKind,
                traceEvidenceStrength,
                unsupportedTraceWindowCount,
                supportBackedTraceWindowCount,
                queuePriorityClassForRow));

        // Phase 0/5: round-level branch novelty source attribution. Phase 5
        // adds the per-version novelty source labels and the round-level
        // classification so offline parsers can directly see which rounds
        // produced rolling-post-upgrade novelty without re-deriving it.
        observabilityMetrics.recordBranchNovelty(new BranchNoveltyRow(
                finishedTestID,
                testPlanDiffFeedbackPacket.testPacketID,
                oldVersionBaselineOnlyProbes,
                oldVersionRollingOnlyProbes,
                oldVersionSharedProbes,
                newVersionBaselineOnlyProbes,
                newVersionRollingOnlyProbes,
                newVersionSharedProbes,
                oldVersionNoveltySource,
                newVersionNoveltySource,
                branchNoveltyClass));

        // Phase 3: run the decay sweep once per round so plans that
        // have been dequeued repeatedly without any payoff decay to a
        // cheaper class. Then emit a snapshot of scheduler counters
        // keyed by SchedulerClass (the internal lane, not the
        // admission-facing priority label) so offline replay can
        // reason about the scheduler's behavior without
        // reconstructing it from the per-enqueue/dequeue CSV stream.
        testPlanCorpus.decayStaleEntries();
        observabilityMetrics.recordSchedulerSnapshot(
                observabilityMetrics.buildSchedulerSnapshot(
                        finishedTestID,
                        testPlanDiffFeedbackPacket.testPacketID,
                        testPlanCorpus.occupancyBySchedulerClass()));

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
     *
     * <p>Phase 1: returns a {@link CrossClusterComparisonOutcome} that
     * carries confidence ({@link StructuredCandidateStrength}) alongside
     * the report text, so callers can route strong vs weak structured
     * candidates into separate directories without re-inspecting packet
     * failure classes.
     */
    private CrossClusterComparisonOutcome checkCrossClusterInconsistencyStructured(
            TestPlanFeedbackPacket[] packets) {
        if (packets[0].validationResults == null
                || packets[1].validationResults == null
                || packets[2].validationResults == null) {
            return CrossClusterComparisonOutcome.none();
        }
        ValidationComparison oldVsRolling = ValidationResultComparator.compare(
                packets[0].validationResults,
                packets[1].validationResults,
                "Old-Old", "Rolling");
        ValidationComparison newVsRolling = ValidationResultComparator.compare(
                packets[2].validationResults,
                packets[1].validationResults,
                "New-New", "Rolling");
        ValidationComparison oldVsNew = ValidationResultComparator.compare(
                packets[0].validationResults,
                packets[2].validationResults,
                "Old-Old", "New-New");

        // Rolling diverges from both baselines, and baselines agree
        if (oldVsRolling.isDivergent() && newVsRolling.isDivergent()
                && oldVsNew.equivalent) {
            // Pair-level strength is STRONG only when both rolling-vs-baseline
            // comparisons are themselves STRONG. A single weak pair taints
            // the candidate because the router can no longer trust the
            // rolling-only signal.
            StructuredCandidateStrength pairStrength;
            if (oldVsRolling.strength == StructuredCandidateStrength.STRONG
                    && newVsRolling.strength == StructuredCandidateStrength.STRONG) {
                pairStrength = StructuredCandidateStrength.STRONG;
            } else {
                pairStrength = StructuredCandidateStrength.WEAK;
            }
            boolean containsUnknown = oldVsRolling.involvesUnknown
                    || newVsRolling.involvesUnknown
                    || oldVsNew.involvesUnknown;
            boolean containsDaemonError = oldVsRolling.involvesDaemonError
                    || newVsRolling.involvesDaemonError
                    || oldVsNew.involvesDaemonError;
            StringBuilder sb = new StringBuilder();
            sb.append(DiffReportHelper.crossClusterHeader());
            sb.append(
                    "Old-Old and New-New agree but Rolling differs.\n");
            sb.append(DiffReportHelper.confidenceMetadataLine(
                    pairStrength, containsUnknown, containsDaemonError));
            sb.append(oldVsRolling.reportLine).append("\n");
            sb.append(newVsRolling.reportLine).append("\n");
            return new CrossClusterComparisonOutcome(
                    /*diverged*/ true,
                    pairStrength,
                    sb.toString(),
                    containsUnknown,
                    containsDaemonError);
        }
        return CrossClusterComparisonOutcome.none();
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
     * candidate  → failure/candidate/failure_N/ (Phase 0 layout; Phase 1
     *              uses {@link #createCandidateVerdictDir} instead so
     *              candidates land under strong/weak subdirectories)
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

    /**
     * Phase 1 candidate directory creator. Splits the
     * {@code failure/candidate/} bucket into {@code strong/} and
     * {@code weak/} subdirectories so downstream triage can separate
     * checker-D strong structured divergences from the weak pile
     * without re-reading report bodies.
     *
     * <p>{@link StructuredCandidateStrength#NONE} is treated as WEAK
     * defensively — no candidate routing should ever pass NONE, but if
     * it does we want a single home for the artefact rather than a
     * crash.
     */
    private Path createCandidateVerdictDir(String configFileName,
            StructuredCandidateStrength strength) {
        String subBucket = strength == StructuredCandidateStrength.STRONG
                ? DiffReportHelper.STRONG_CANDIDATE_SUBDIR
                : DiffReportHelper.WEAK_CANDIDATE_SUBDIR;
        Path bucketDir = Paths.get(Config.getConf().failureDir, subBucket);
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

    private Path ensureCandidateVerdictDir(Path candidateDir,
            String configFileName, String fullSequence,
            StructuredCandidateStrength strength) {
        if (candidateDir == null) {
            Path dir = createCandidateVerdictDir(configFileName, strength);
            saveFullSequence(dir, fullSequence);
            return dir;
        }
        return candidateDir;
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
        saveEventCrashReport(failureDir, testID, report, laneTag, verdict,
                configIdx, null, null);
    }

    /**
     * Phase 1 variant with the Phase 1 confidence labels embedded in the
     * report metadata block. {@code strength} and {@code weakKind} are
     * optional: pass {@code null} for callers that do not know them
     * (e.g. SAME_VERSION_BUG routing) and the metadata block falls
     * back to the Phase 0 layout.
     */
    private void saveEventCrashReport(Path failureDir, int testID,
            String report, String laneTag, DiffVerdict verdict,
            String configIdx,
            StructuredCandidateStrength strength,
            WeakCandidateKind weakKind) {
        Path subDir = createFailureSubDir(failureDir, "event_crash");
        String fileName = DiffReportHelper.reportFileName(
                DiffReportHelper.CheckerType.EVENT_CRASH, testID, laneTag);
        String fullReport = verdict != null
                ? DiffReportHelper.buildMetadataBlock(
                        DiffReportHelper.CheckerType.EVENT_CRASH,
                        laneTag, verdict, testID, configIdx, null,
                        strength, weakKind,
                        /*containsUnknown*/ false,
                        /*containsDaemonError*/ false)
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
        saveInconsistencyReport(failureDir, testID, report, verdict,
                configIdx, null, null, false, false);
    }

    /**
     * Phase 1 variant: records the checker-D strength and the
     * UNKNOWN/DAEMON_ERROR touches in the report metadata block so
     * offline parsers can re-bucket strong vs weak structured
     * divergences without re-parsing the comparator output.
     */
    private void saveInconsistencyReport(Path failureDir, int testID,
            String report, DiffVerdict verdict, String configIdx,
            StructuredCandidateStrength strength,
            WeakCandidateKind weakKind,
            boolean containsUnknown,
            boolean containsDaemonError) {
        Path inconsistencySubDir = createFailureSubDir(failureDir,
                "inconsistency");
        String fileName = DiffReportHelper.reportFileName(
                DiffReportHelper.CheckerType.CROSS_CLUSTER_INCONSISTENCY,
                testID, null);
        String fullReport = verdict != null
                ? DiffReportHelper.buildMetadataBlock(
                        DiffReportHelper.CheckerType.CROSS_CLUSTER_INCONSISTENCY,
                        null, verdict, testID, configIdx, null,
                        strength, weakKind,
                        containsUnknown, containsDaemonError)
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
        saveErrorReport(failureDir, report, testID, laneTag, verdict,
                configIdx, null, null);
    }

    /**
     * Phase 1 variant: records the error log's strength + weak-candidate
     * kind in the metadata block so downstream review can split weak
     * event/error-log signals from strong structured divergences.
     */
    private void saveErrorReport(Path failureDir, String report, int testID,
            String laneTag, DiffVerdict verdict, String configIdx,
            StructuredCandidateStrength strength,
            WeakCandidateKind weakKind) {
        Path errorSubDir = createFailureSubDir(failureDir, "errorLog");
        String fileName = DiffReportHelper.reportFileName(
                DiffReportHelper.CheckerType.ERROR_LOG, testID, laneTag);
        String fullReport = verdict != null
                ? DiffReportHelper.buildMetadataBlock(
                        DiffReportHelper.CheckerType.ERROR_LOG,
                        laneTag, verdict, testID, configIdx, null,
                        strength, weakKind,
                        /*containsUnknown*/ false,
                        /*containsDaemonError*/ false)
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
                    "testPlanCorpus : " + testPlanCorpus.size(),
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
     * Phase 2 trace-admission gate. Trace evidence only drives an
     * admission (or upgrades a branch-only admission into
     * {@link AdmissionReason#BRANCH_AND_TRACE}) when the round-level
     * trace evidence strength is {@link TraceEvidenceStrength#STRONG}.
     *
     * <p>Weak and unsupported rounds keep producing trace observability
     * rows but never enter the corpus via the trace path. This is what
     * turns the Phase 0/1 label into actual enforcement — the Apr15
     * {@code all3=0}, {@code PRE_UPGRADE}, baseline-disagreement, and
     * aggregate-sim-only rounds all land here.
     */
    static boolean isPhase2TraceAdmissible(boolean traceInteresting,
            TraceEvidenceStrength strength) {
        return traceInteresting && strength == TraceEvidenceStrength.STRONG;
    }

    // === Phase 0: confidence label classifiers ===

    /**
     * Count {@code changedMessage=true} entries in a merged rolling-lane
     * trace. Returns 0 for a null trace. A non-zero result means the
     * rolling lane carried at least one payload whose class changed
     * between versions — a secondary signal of mixed-version relevance.
     */
    private static int countChangedMessages(Trace mergedTrace) {
        if (mergedTrace == null) {
            return 0;
        }
        int count = 0;
        for (TraceEntry entry : mergedTrace.getTraceEntries()) {
            if (entry != null && entry.changedMessage) {
                count++;
            }
        }
        return count;
    }

    /**
     * Count per-event upgraded-boundary crossings in a merged rolling-lane
     * trace. A crossing is a message where exactly one endpoint — sender
     * or receiver — belongs to {@code upgradedNodeSet}. Returns 0 when
     * either the trace or the upgraded set is null/empty.
     *
     * <p>This is the Phase 2 corroboration signal. The Apr15 code used
     * {@code rawUpgradedNodeSet.size()} which only tells us how many
     * nodes have already flipped to new bits, not how many messages
     * actually crossed a version boundary. That overstated corroboration
     * in stages where the rolling lane had several upgraded nodes but
     * all observed traffic stayed within a single version (e.g., a
     * post-upgrade stage where writes happen between two new-version
     * replicas). The per-event count below fixes that.
     *
     * <p>Node indices are extracted from raw IDs of the form
     * {@code <executorID>-N<index>} (see
     * {@code CassandraDocker.NET_TRACE_NODE_ID}). Entries with
     * unparseable endpoints are skipped rather than counted, so a
     * malformed row cannot accidentally inflate the corroboration
     * counter.
     */
    static int countUpgradedBoundaryCrossings(Trace mergedTrace,
            Set<Integer> upgradedNodeSet) {
        if (mergedTrace == null || upgradedNodeSet == null
                || upgradedNodeSet.isEmpty()) {
            return 0;
        }
        int count = 0;
        for (TraceEntry entry : mergedTrace.getTraceEntries()) {
            if (entry == null) {
                continue;
            }
            int srcIdx = extractNodeIndex(entry.nodeId);
            int dstIdx = extractNodeIndex(entry.peerId);
            if (srcIdx < 0 || dstIdx < 0) {
                continue;
            }
            boolean srcUp = upgradedNodeSet.contains(srcIdx);
            boolean dstUp = upgradedNodeSet.contains(dstIdx);
            if (srcUp ^ dstUp) {
                count++;
            }
        }
        return count;
    }

    /**
     * Parse a node index out of a raw node ID string. Handles three
     * forms (see {@code CassandraDocker.NET_TRACE_NODE_ID}):
     * <ul>
     *   <li>{@code <executorID>-N<digits>} — preferred, e.g.
     *       {@code SrnNTLLS-N0} -> {@code 0}.</li>
     *   <li>{@code N<digits>} — bare normalized form.</li>
     *   <li>{@code <digits>} — plain decimal.</li>
     * </ul>
     * Returns -1 for null, empty, or unparseable inputs so the caller
     * can safely skip the entry without counting it.
     */
    static int extractNodeIndex(String rawId) {
        if (rawId == null || rawId.isEmpty() || "null".equals(rawId)) {
            return -1;
        }
        String candidate;
        int dashN = rawId.lastIndexOf("-N");
        if (dashN >= 0 && dashN + 2 < rawId.length()) {
            candidate = rawId.substring(dashN + 2);
        } else if (rawId.length() >= 2 && rawId.charAt(0) == 'N') {
            candidate = rawId.substring(1);
        } else {
            candidate = rawId;
        }
        try {
            return Integer.parseInt(candidate);
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    /**
     * Phase 0 per-window trace evidence strength classifier. A window is
     * STRONG only when (a) it fired at least one trace rule, (b) the
     * support gate passed (three-way baseline shared support exists),
     * (c) the stage is a mixed-version-relevant stage (not
     * {@code PRE_UPGRADE} or lifecycle-only), and (d) at least one
     * corroborating upgrade-boundary event or changed-message payload
     * is visible. Windows that fire with support but without
     * corroboration are WEAK; windows that fire without support at all
     * are UNSUPPORTED. Non-firing windows are NONE.
     */
    static TraceEvidenceStrength classifyWindowTraceEvidenceStrength(
            boolean windowFired,
            boolean supportGatePassed,
            TraceWindow.StageKind stageKind,
            int changedMessageCount,
            int upgradedBoundaryEventCount) {
        if (!windowFired) {
            return TraceEvidenceStrength.NONE;
        }
        if (!supportGatePassed) {
            return TraceEvidenceStrength.UNSUPPORTED;
        }
        boolean mixedVersionStage = stageKind == TraceWindow.StageKind.POST_STAGE
                || stageKind == TraceWindow.StageKind.POST_FINAL_STAGE
                || stageKind == TraceWindow.StageKind.FAULT_RECOVERY;
        boolean hasCorroboration = changedMessageCount > 0
                || upgradedBoundaryEventCount > 0;
        if (mixedVersionStage && hasCorroboration) {
            return TraceEvidenceStrength.STRONG;
        }
        return TraceEvidenceStrength.WEAK;
    }

    /**
     * Aggregate per-window labels into a single round-level label. The
     * strongest window wins; aggregate-sim-only rounds without any
     * window firing are classified as WEAK because there is no per-window
     * support evidence to upgrade them.
     */
    static TraceEvidenceStrength classifyRoundTraceEvidenceStrength(
            boolean traceInteresting,
            int strongFiringWindows,
            int weakFiringWindows,
            int unsupportedFiringWindows,
            boolean aggregateSimFired) {
        if (!traceInteresting) {
            return TraceEvidenceStrength.NONE;
        }
        if (strongFiringWindows > 0) {
            return TraceEvidenceStrength.STRONG;
        }
        if (weakFiringWindows > 0) {
            return TraceEvidenceStrength.WEAK;
        }
        if (unsupportedFiringWindows > 0) {
            return TraceEvidenceStrength.UNSUPPORTED;
        }
        if (aggregateSimFired) {
            return TraceEvidenceStrength.WEAK;
        }
        return TraceEvidenceStrength.NONE;
    }

    /**
     * Structured candidate strength classifier. STRONG requires Checker
     * D to have fired AND every lane to have a stable structured
     * outcome (no UNKNOWN / DAEMON_ERROR {@code failureClass} across
     * the three packets). Otherwise the structured divergence is WEAK
     * because at least one lane produced an unstable outcome, which is
     * the Apr15 Cassandra checker-D noise pattern.
     */
    static StructuredCandidateStrength classifyStructuredCandidateStrength(
            boolean structuredCandidate,
            TestPlanFeedbackPacket[] packets) {
        if (!structuredCandidate) {
            return StructuredCandidateStrength.NONE;
        }
        if (packets == null || packets.length < 3) {
            return StructuredCandidateStrength.WEAK;
        }
        for (int i = 0; i < 3; i++) {
            TestPlanFeedbackPacket packet = packets[i];
            if (packet == null) {
                return StructuredCandidateStrength.WEAK;
            }
            if (packet.validationResults == null) {
                return StructuredCandidateStrength.WEAK;
            }
            for (org.zlab.upfuzz.fuzzingengine.packet.ValidationResult vr : packet.validationResults) {
                if (vr == null) {
                    continue;
                }
                String fc = vr.failureClass;
                if (fc == null) {
                    continue;
                }
                if ("UNKNOWN".equals(fc) || "DAEMON_ERROR".equals(fc)) {
                    return StructuredCandidateStrength.WEAK;
                }
            }
        }
        return StructuredCandidateStrength.STRONG;
    }

    /**
     * Weak-candidate subclassifier. STRUCTURED rounds that did not
     * make the STRONG bar are labeled
     * {@link WeakCandidateKind#UNSTABLE_STRUCTURED_DIVERGENCE}; the
     * other weak subclasses come from rolling-only event failures and
     * rolling-only error logs. Plain NONE is returned when the round
     * is not a weak candidate at all.
     */
    static WeakCandidateKind classifyWeakCandidateKind(
            boolean structuredCandidate,
            StructuredCandidateStrength structuredCandidateStrength,
            DiffVerdict eventVerdict,
            DiffVerdict errorLogVerdict) {
        if (structuredCandidate
                && structuredCandidateStrength != StructuredCandidateStrength.STRONG) {
            return WeakCandidateKind.UNSTABLE_STRUCTURED_DIVERGENCE;
        }
        if (!structuredCandidate
                && eventVerdict == DiffVerdict.ROLLING_UPGRADE_BUG_CANDIDATE) {
            return WeakCandidateKind.ROLLING_ONLY_EVENT_FAILURE;
        }
        if (!structuredCandidate
                && errorLogVerdict == DiffVerdict.ROLLING_UPGRADE_BUG_CANDIDATE) {
            return WeakCandidateKind.ROLLING_ONLY_ERROR_LOG;
        }
        return WeakCandidateKind.NONE;
    }

    /**
     * Map an admission reason and round-level trace evidence strength
     * onto a queue priority class. Trace-only admissions inherit the
     * round-level trace strength; branch-backed admissions pick up
     * {@link QueuePriorityClass#BRANCH_AND_STRONG_TRACE} when the
     * trace evidence is STRONG, otherwise
     * {@link QueuePriorityClass#BRANCH_AND_WEAK_TRACE}.
     */
    static QueuePriorityClass classifyQueuePriorityClass(
            AdmissionReason admissionReason,
            TraceEvidenceStrength traceEvidenceStrength) {
        if (admissionReason == null) {
            return QueuePriorityClass.UNKNOWN;
        }
        switch (admissionReason) {
        case BRANCH_ONLY:
            return QueuePriorityClass.BRANCH_ONLY;
        case BRANCH_AND_TRACE:
            return traceEvidenceStrength == TraceEvidenceStrength.STRONG
                    ? QueuePriorityClass.BRANCH_AND_STRONG_TRACE
                    : QueuePriorityClass.BRANCH_AND_WEAK_TRACE;
        case TRACE_ONLY_TRIDIFF_EXCLUSIVE:
        case TRACE_ONLY_WINDOW_SIM:
        case TRACE_ONLY_TRIDIFF_MISSING:
            return traceEvidenceStrength == TraceEvidenceStrength.STRONG
                    ? QueuePriorityClass.TRACE_ONLY_STRONG
                    : QueuePriorityClass.TRACE_ONLY_WEAK;
        case UNKNOWN:
        default:
            return QueuePriorityClass.UNKNOWN;
        }
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

    // === Phase 4 (Apr15): stage-focused mutation hint builder ===

    /**
     * Phase 4 stage-focused mutation hint builder. Aggregates the
     * per-window accumulators captured during canonical trace
     * scoring into a single immutable hint that the stage-aware
     * mutator and confirmation-oriented child generator consume.
     *
     * <p>The hint is deliberately compact so the serialized
     * {@link QueuedTestPlan} stays small — only the first firing
     * window's stage id / ordinal, the union of every firing window's
     * rolling upgraded node set, and a closed set of booleans.
     */
    static StageMutationHint buildStageMutationHint(
            String hotStageId,
            TraceWindow.StageKind hotStageKind,
            int hotWindowOrdinal,
            Set<Integer> hotNodeSet,
            boolean anyPreUpgradeFired,
            boolean anyNonPreUpgradeFired,
            boolean anyPostUpgradeFired,
            boolean anyFaultRecoveryFired,
            boolean newBranchCoverage,
            TraceEvidenceStrength traceEvidenceStrength,
            StructuredCandidateStrength testLevelCandidateStrength,
            boolean rollingOnlyEventOrErrorLog,
            boolean anyStructuredDivergence) {
        StageMutationHint.SignalType signalType = StageMutationHint
                .classifySignal(newBranchCoverage, traceEvidenceStrength,
                        testLevelCandidateStrength,
                        rollingOnlyEventOrErrorLog);
        boolean preUpgradeOnly = anyPreUpgradeFired
                && !anyNonPreUpgradeFired
                && signalType != StageMutationHint.SignalType.STRONG_STRUCTURED;
        boolean faultInfluenced = anyFaultRecoveryFired;
        // Confirmation is reserved for actual structured candidates
        // (checker-D divergence). Rolling-only event/error log
        // candidates are too noisy to warrant confirmation children.
        boolean needsConfirmation = anyStructuredDivergence
                && (signalType == StageMutationHint.SignalType.STRONG_STRUCTURED
                        || signalType == StageMutationHint.SignalType.WEAK_STRUCTURED);
        // Upgrade order matters when the firing windows span a
        // POST_STAGE boundary with multiple upgraded nodes — the
        // specific node upgrade sequence may have contributed to the
        // divergence. This gates the optional SHUFFLE_UPGRADE_ORDER
        // mutator.
        boolean upgradeOrderMattered = anyPostUpgradeFired
                && hotNodeSet != null && hotNodeSet.size() >= 2;
        StageMutationHint.StageKindHint hotKindHint = StageMutationHint.StageKindHint
                .from(hotStageKind);
        return new StageMutationHint(
                hotStageId,
                hotKindHint,
                hotWindowOrdinal,
                hotNodeSet,
                signalType,
                anyPostUpgradeFired,
                preUpgradeOnly,
                faultInfluenced,
                needsConfirmation,
                upgradeOrderMattered);
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

    // === Phase 1/2: tri-diff window decision ===

    /**
     * Explicit sub-decisions for a single aligned tri-diff window.
     *
     * <p>Phase 1 added the
     * {@code exclusiveInteresting} / {@code missingInteresting} split so
     * that missing-only admissions could be disabled while still keeping
     * the missing counter visible for observability.
     *
     * <p>Phase 2 extends the decision object with support/stage/change
     * gating results and the per-window
     * {@link TraceEvidenceStrength} label. These fields are the source of
     * truth for the round-level strength roll-up — the caller no longer
     * has to re-derive them from the raw triDiff numbers. The original
     * boolean fields stay populated exactly as before so the legacy
     * admission path and Phase 1 regression tests remain unchanged.
     *
     * <p>Invariants:
     * <ul>
     *   <li>{@code traceEvidenceStrength == NONE} iff {@code !windowFired},
     *       where "fired" means either {@code exclusiveInteresting} or
     *       {@code windowSimInteresting}.</li>
     *   <li>{@code traceEvidenceStrength == STRONG} implies
     *       {@code supportGatePassed && stageGatePassed} and at least
     *       one of (a) {@code changedMessageGatePassed} — which is
     *       itself true when <em>either</em> the changed-message
     *       path or the upgraded-boundary path clears its minimum
     *       with non-zero evidence — or (b) the strong-support
     *       fallback ({@code totalAllThreeCount >=
     *       strongTraceFallbackMinAllThreeCount} with
     *       {@code rollingMinSimilarity <=
     *       strongTraceFallbackMaxRollingMinSimilarity}).</li>
     *   <li>{@code changedMessageGatePassed} is a logical OR of the
     *       changed-message and upgraded-boundary corroboration paths;
     *       the two minima control the threshold for each path
     *       independently and are never AND-combined.</li>
     *   <li>{@code traceEvidenceStrength == UNSUPPORTED} iff the window
     *       fired but {@code !supportGatePassed}.</li>
     * </ul>
     */
    static final class TriDiffWindowDecision {
        final boolean exclusiveInteresting;
        final boolean missingInteresting;
        final boolean triDiffInteresting;
        // --- Phase 2 gating result fields ---
        final boolean supportGatePassed;
        final boolean stageGatePassed;
        final boolean changedMessageGatePassed;
        final TraceEvidenceStrength traceEvidenceStrength;

        TriDiffWindowDecision(boolean exclusiveInteresting,
                boolean missingInteresting, boolean triDiffInteresting) {
            this(exclusiveInteresting, missingInteresting, triDiffInteresting,
                    /* supportGatePassed */ false,
                    /* stageGatePassed */ false,
                    /* changedMessageGatePassed */ false,
                    TraceEvidenceStrength.NONE);
        }

        TriDiffWindowDecision(boolean exclusiveInteresting,
                boolean missingInteresting, boolean triDiffInteresting,
                boolean supportGatePassed, boolean stageGatePassed,
                boolean changedMessageGatePassed,
                TraceEvidenceStrength traceEvidenceStrength) {
            this.exclusiveInteresting = exclusiveInteresting;
            this.missingInteresting = missingInteresting;
            this.triDiffInteresting = triDiffInteresting;
            this.supportGatePassed = supportGatePassed;
            this.stageGatePassed = stageGatePassed;
            this.changedMessageGatePassed = changedMessageGatePassed;
            this.traceEvidenceStrength = traceEvidenceStrength == null
                    ? TraceEvidenceStrength.NONE
                    : traceEvidenceStrength;
        }
    }

    /**
     * Phase 2 gate knobs captured as an immutable bundle. Grouping them
     * keeps {@link #evaluateTriDiffWindow(
     * DiffComputeMessageTriDiff.MessageTriDiffResult, TraceWindow.StageKind,
     * int, double, int, double, TraceStrengthGates)} readable and lets
     * unit tests build "all-zero" / "all-strict" gate fixtures without
     * worrying about positional arguments.
     *
     * <p>All thresholds are inclusive (">="). Setting everything to 0 (or
     * -1 for ratios) reproduces the pre-Phase-2 behavior, which is what
     * the legacy overload does.
     */
    static final class TraceStrengthGates {
        final int minAllThreeCount;
        final int minBaselineSharedCount;
        final double minBaselineSimilarity;
        final int minChangedMessageCount;
        final int minUpgradedBoundaryCount;
        final boolean preUpgradeCanStrengthenBranch;
        final int fallbackMinAllThreeCount;
        final double fallbackMaxRollingMinSimilarity;

        TraceStrengthGates(int minAllThreeCount,
                int minBaselineSharedCount,
                double minBaselineSimilarity,
                int minChangedMessageCount,
                int minUpgradedBoundaryCount,
                boolean preUpgradeCanStrengthenBranch,
                int fallbackMinAllThreeCount,
                double fallbackMaxRollingMinSimilarity) {
            this.minAllThreeCount = minAllThreeCount;
            this.minBaselineSharedCount = minBaselineSharedCount;
            this.minBaselineSimilarity = minBaselineSimilarity;
            this.minChangedMessageCount = minChangedMessageCount;
            this.minUpgradedBoundaryCount = minUpgradedBoundaryCount;
            this.preUpgradeCanStrengthenBranch = preUpgradeCanStrengthenBranch;
            this.fallbackMinAllThreeCount = fallbackMinAllThreeCount;
            this.fallbackMaxRollingMinSimilarity = fallbackMaxRollingMinSimilarity;
        }

        /** Default gates that disable all Phase 2 promotions (legacy behavior). */
        static TraceStrengthGates permissive() {
            return new TraceStrengthGates(
                    /* minAllThreeCount */ 0,
                    /* minBaselineSharedCount */ 0,
                    /* minBaselineSimilarity */ 0.0,
                    /* minChangedMessageCount */ 0,
                    /* minUpgradedBoundaryCount */ 0,
                    /* preUpgradeCanStrengthenBranch */ true,
                    /* fallbackMinAllThreeCount */ 0,
                    /* fallbackMaxRollingMinSimilarity */ 1.0);
        }

        /** Snapshot of the current server config. */
        static TraceStrengthGates fromConfig(Config.Configuration conf) {
            return new TraceStrengthGates(
                    conf.strongTraceMinAllThreeCount,
                    conf.strongTraceMinBaselineSharedCount,
                    conf.strongTraceMinBaselineSimilarity,
                    conf.strongTraceMinChangedMessageCount,
                    conf.strongTraceMinUpgradedBoundaryCount,
                    conf.preUpgradeTraceCanStrengthenBranch,
                    conf.strongTraceFallbackMinAllThreeCount,
                    conf.strongTraceFallbackMaxRollingMinSimilarity);
        }
    }

    /**
     * Legacy six-argument entry point. Preserved for
     * {@link org.zlab.upfuzz.fuzzingengine.server.FuzzingServerTriDiffDecisionTest}
     * and any offline replay harness that only cares about the Phase 1
     * boolean triad. Phase 2 fields are populated with "permissive" values
     * so the returned object is still usable in a support-aware context.
     */
    static TriDiffWindowDecision evaluateTriDiffWindow(
            DiffComputeMessageTriDiff.MessageTriDiffResult triDiff,
            TraceWindow.StageKind stageKind,
            int rollingExclusiveMinCount,
            double rollingExclusiveFractionThreshold,
            int rollingMissingMinCount,
            double rollingMissingFractionThreshold) {
        return evaluateTriDiffWindow(
                triDiff,
                stageKind,
                rollingExclusiveMinCount,
                rollingExclusiveFractionThreshold,
                rollingMissingMinCount,
                rollingMissingFractionThreshold,
                /* windowSimInteresting */ false,
                /* changedMessageCount */ 0,
                /* upgradedBoundaryEventCount */ 0,
                /* rollingMinSimilarity */ Double.NaN,
                /* baselineSimilarity */ Double.NaN,
                TraceStrengthGates.permissive());
    }

    /**
     * Evaluate a single aligned tri-diff window with full Phase 2 gating.
     *
     * <p>Rolling-exclusive churn (messages the rolling lane produced that
     * neither baseline has) is the only direct seed-admission path after
     * Phase 1. This method preserves that exclusive/missing admission
     * contract and additionally computes Phase 2 support/stage/change
     * gating:
     *
     * <ol>
     *   <li><b>Support gate:</b> at least one canonical message must
     *       appear in all three lanes
     *       ({@code totalAllThreeCount >= minAllThreeCount}) and the
     *       baseline-shared count must clear its own floor. Windows that
     *       fail the support gate are labelled
     *       {@link TraceEvidenceStrength#UNSUPPORTED}.</li>
     *   <li><b>Stage gate:</b> only
     *       {@link TraceWindow.StageKind#POST_STAGE} and
     *       {@link TraceWindow.StageKind#POST_FINAL_STAGE} (plus
     *       {@link TraceWindow.StageKind#FAULT_RECOVERY} for completeness)
     *       are mixed-version-relevant. {@code PRE_UPGRADE} is gated off
     *       by default — the config knob
     *       {@code preUpgradeTraceCanStrengthenBranch} flips this for
     *       offline replay.</li>
     *   <li><b>Baseline-agreement gate:</b> if the two baselines
     *       disagree too much with each other
     *       ({@code baselineSimilarity < minBaselineSimilarity}), the
     *       round itself is already unstable and promoting a rolling
     *       divergence to {@link TraceEvidenceStrength#STRONG} would
     *       amplify noise. The window drops to WEAK instead.</li>
     *   <li><b>Change gate:</b> a STRONG window usually needs
     *       corroborating upgrade evidence — changed-message traffic or
     *       upgraded-boundary events. Windows that otherwise pass the
     *       support/stage/baseline gates but lack this corroboration
     *       stay WEAK unless the strong-support fallback fires
     *       (very high {@code totalAllThreeCount} with a very low
     *       {@code rollingMinSimilarity}).</li>
     * </ol>
     */
    static TriDiffWindowDecision evaluateTriDiffWindow(
            DiffComputeMessageTriDiff.MessageTriDiffResult triDiff,
            TraceWindow.StageKind stageKind,
            int rollingExclusiveMinCount,
            double rollingExclusiveFractionThreshold,
            int rollingMissingMinCount,
            double rollingMissingFractionThreshold,
            boolean windowSimInteresting,
            int changedMessageCount,
            int upgradedBoundaryEventCount,
            double rollingMinSimilarity,
            double baselineSimilarity,
            TraceStrengthGates gates) {
        int rollingExclusive = triDiff.rollingExclusiveCount();
        int rollingMissing = triDiff.rollingMissingCount();
        double exclusiveFraction = triDiff.rollingExclusiveFraction();
        double missingFraction = triDiff.rollingMissingFraction();
        int totalAllThreeCount = triDiff.totalAllThreeCount();
        int baselineSharedCount = triDiff.baselineSharedCount();

        boolean exclusiveInteresting = rollingExclusive >= rollingExclusiveMinCount
                && exclusiveFraction >= rollingExclusiveFractionThreshold;

        boolean preUpgradeStage = stageKind == TraceWindow.StageKind.PRE_UPGRADE;
        boolean missingInteresting = !preUpgradeStage
                && rollingMissing >= rollingMissingMinCount
                && missingFraction >= rollingMissingFractionThreshold;

        // Phase 1 hotfix: missing-only windows no longer admit seeds.
        // triDiffInteresting reflects the admission verdict; missingInteresting
        // is kept for observability so offline re-scoring and later reruns
        // can reproduce the decision.
        boolean triDiffInteresting = exclusiveInteresting;

        // Phase 2: compute support/stage/change gating for the strength
        // label. These gates never affect the admission booleans — they
        // only decide whether a firing window counts as STRONG, WEAK, or
        // UNSUPPORTED for the round-level roll-up.
        TraceStrengthGates effectiveGates = gates == null
                ? TraceStrengthGates.permissive()
                : gates;

        boolean windowFired = exclusiveInteresting || windowSimInteresting;
        boolean supportGatePassed = totalAllThreeCount > 0
                && totalAllThreeCount >= effectiveGates.minAllThreeCount
                && baselineSharedCount >= effectiveGates.minBaselineSharedCount;

        boolean mixedVersionStage = stageKind == TraceWindow.StageKind.POST_STAGE
                || stageKind == TraceWindow.StageKind.POST_FINAL_STAGE
                || stageKind == TraceWindow.StageKind.FAULT_RECOVERY;
        boolean stageGatePassed = mixedVersionStage
                || (preUpgradeStage
                        && effectiveGates.preUpgradeCanStrengthenBranch);

        boolean baselineAgreementOk = Double.isNaN(baselineSimilarity)
                || baselineSimilarity >= effectiveGates.minBaselineSimilarity;

        // Phase 2 corroboration gate: changed-message traffic and
        // upgraded-boundary traffic are <em>alternative</em> paths per
        // the plan ("strong trace should usually require at least one
        // of: changed-message traffic, upgraded-boundary traffic,
        // strong support plus very high divergence"). Each path must
        // (a) be non-zero, and (b) clear its own minimum threshold to
        // count; a window passes the gate when <em>either</em> path
        // qualifies. Setting either minimum to 0 only relaxes that
        // path — it does not make the other path mandatory. The
        // strong-support fallback below remains a third independent
        // path to STRONG.
        boolean changedMessageCorroborates = changedMessageCount > 0
                && changedMessageCount >= effectiveGates.minChangedMessageCount;
        boolean upgradedBoundaryCorroborates = upgradedBoundaryEventCount > 0
                && upgradedBoundaryEventCount >= effectiveGates.minUpgradedBoundaryCount;
        boolean changedMessageGatePassed = changedMessageCorroborates
                || upgradedBoundaryCorroborates;

        // Strong-support fallback: very large all3 + very low rolling
        // similarity should still count as STRONG even without a
        // changed-message or upgraded-boundary co-signal, because the
        // rolling lane is genuinely drifting from a well-agreed
        // three-way shared baseline.
        boolean fallbackStrongSupport = totalAllThreeCount >= effectiveGates.fallbackMinAllThreeCount
                && effectiveGates.fallbackMinAllThreeCount > 0
                && !Double.isNaN(rollingMinSimilarity)
                && rollingMinSimilarity <= effectiveGates.fallbackMaxRollingMinSimilarity;

        TraceEvidenceStrength strength;
        if (!windowFired) {
            strength = TraceEvidenceStrength.NONE;
        } else if (!supportGatePassed) {
            strength = TraceEvidenceStrength.UNSUPPORTED;
        } else if (!stageGatePassed || !baselineAgreementOk) {
            strength = TraceEvidenceStrength.WEAK;
        } else if (changedMessageGatePassed || fallbackStrongSupport) {
            strength = TraceEvidenceStrength.STRONG;
        } else {
            strength = TraceEvidenceStrength.WEAK;
        }

        return new TriDiffWindowDecision(exclusiveInteresting,
                missingInteresting, triDiffInteresting,
                supportGatePassed, stageGatePassed, changedMessageGatePassed,
                strength);
    }
}
