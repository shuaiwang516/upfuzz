package org.zlab.upfuzz.fuzzingengine;

import com.google.gson.GsonBuilder;
import java.lang.reflect.Field;
import org.zlab.net.tracker.CanonicalKeyMode;

/**
 * Configuration for the fuzzing engine
 * - Do not modify the default configurations!
 * - Modify it in the config.json file to override them
 */
public class Config {

    public static Configuration instance;

    public static Configuration getConf() {
        return instance;
    }

    public Config() {
        instance = new Configuration();
    }

    public static class Configuration {
        // ------ debug coverage ------
        public boolean debugCoverage = false;

        // ----------- general ------------
        public String serverHost = "localhost";
        public Integer serverPort = 6299;
        public String clientHost = "localhost";
        public Integer clientPort = 6300;
        public String instClassFilePath = null;

        public String originalVersion = null;
        public String upgradedVersion = null;
        public String depVersion = null;

        public String jacocoAgentPath = null;
        public String system = null;
        public String depSystem = null;

        public String failureDir = null;

        public boolean nyxMode = false;
        public String nyxFuzzSH = "nyx_mode/fuzz_no_pt.sh";

        // -------------- Test Execution Timeout --------------
        // A single test must be finished within this time limit
        public int testExecutionTimeout = 5; // minutes

        // Testing Purpose
        public boolean forceTestExecutionTimeout = false;

        // -------------- Reproducibility --------------
        // Seed
        public long seed = 20250101L;
        // Evaluation
        public boolean controlRandomness = false;

        // Skip Upgrade
        public boolean BC_skipUpgrade = false;

        // Skip Upgrade
        public boolean skipUpgrade = false;
        // Parameters for the exponential distribution
        public double skipProbForNewBranchCoverage = 0.2;
        public double expProbModel_C = 0.9;
        public double skipUpgradeTargetProb = 0.2;
        public int skipUpgradeTargetProbN = 10;

        // -------------- GC --------------
        public int gcInterval = 2; // minutes

        // ------------ Corpus ------------
        public String corpus = "corpus";
        public boolean saveCorpusToDisk = true;
        public boolean loadInitCorpus = false;
        public boolean reuseInitSeedConfig = false;

        // ------------ Input Generation ------------
        // Debug use the same command sequence
        public boolean useFixedCommand = false;
        // Replay/debug: force a specific config file index (test<idx>)
        // for example-testplan mode. -1 means random.
        public int fixedConfigIdx = -1;
        // Provide multiple fixed tests and execute them in sequence
        public int fixedTestNum = 1;

        // Sequence Generation for write commands
        public int MIN_CMD_SEQ_LEN = 15;
        public int MAX_CMD_SEQ_LEN = 100;

        // Sequence Generation for read commands
        public int MIN_READ_CMD_SEQ_LEN = 30;
        public int MAX_READ_CMD_SEQ_LEN = 100;

        // Sequence Generation for read commands (HDFS)
        public int MIN_HDFS_READ_CMD_SEQ_LEN = 15;
        public int MAX_HDFS_READ_CMD_SEQ_LEN = 100;

        // Expected len = ~20
        // Base for the exponential function
        // Skew model of command sequence length
        public double CMD_SEQ_LEN_LAMBDA = 0.2;

        public int SET_TYPE_MAX_SIZE = 10;

        // 95% get seed from corpus, 5% generate new seed
        public double getSeedFromCorpusRatio = 0.95;

        // ---------------- Mutation ---------------
        // For the first firstMutationSeedLimit seeds added
        // to the corpus, mutate them for relative few times
        public int firstMutationSeedLimit = 5;
        public int firstSequenceMutationEpoch = 10;
        public int sequenceMutationEpoch = 80;
        public int firstConfigMutationEpoch = 3;
        public int configMutationEpoch = 20;
        public int mutationFailLimit = 15;

        // Fix config and random generate new command sequences
        // Focus on fuzzing
        public int firstSequenceRandGenEpoch = 10;
        public int sequenceRandGenEpoch = 20;

        // Whether to enable random generation using the same config
        public boolean enableRandomGenUsingSameConfig = false;

        /**
         * When we only mutate config, we cannot stack them
         * together. For throughput, we can stack
         * other tests here. But this is a hack, it looks like
         * that we already think that this config is interesting,
         * which is not reasonable.
         * Also, with NYX, there's no need for doing this.
         * This can be enabled when using stacked tests and aiming
         * only for largest throughput.
         */
        public boolean paddingStackedTestPackets = false;

        /* Special Mutation */
        public boolean enableAddMultiCommandMutation = true;
        // If choose to add command, 30% add multiple commands
        public double addCommandWithSameTypeProb = 0.3;
        public int addCommandWithSameTypeNum = 3;

        public int testPlanMutationEpoch = 20;
        public int testPlanMutationRetry = 50;
        // Given a full-stop seed, we generate 20
        // test plan from it.
        public int testPlanGenerationNum = 20;

        public int intervalMin = 10; // ms
        public int intervalMax = 200; // ms

        public int STACKED_TESTS_NUM = 1;
        public int STACKED_TESTS_NUM_G2 = 30;
        public long timeInterval = 600; // seconds, record time
        public boolean keepDir = true; // set to false if start a long-running
                                       // test
        public int nodeNum = 3;

        // ------------Branch Coverage------------
        public boolean useBranchCoverage = true;
        public boolean enableHitCount = false;
        public boolean debugHitCount = false;
        public boolean collUpFeedBack = true;
        public boolean collDownFeedBack = true;

        // ------------Fault Injection-------------
        public boolean shuffleUpgradeOrder = false; // Whether shuffle the
                                                    // upgrade order
        public int faultMaxNum = 2; // disable faults for now
        public boolean alwaysRecoverFault = false;
        public float noRecoverProb = 0.5f;

        public int rebuildConnectionSecs = 5;

        // Inject a unidirectional link failure
        public boolean eval_CASSANDRA15727 = false;

        // ------------Configuration Testing-------------
        public boolean verifyConfig = false;
        public String configDir = "configtests";

        // == single version ==
        public boolean testSingleVersionConfig = false;
        public double testSingleVersionConfigRatio = 0.1;

        // == upgrade ==
        public boolean testBoundaryConfig = false;
        // Mutate all boundary related configs
        public double testBoundaryUpgradeConfigRatio = 1;

        public boolean testAddedConfig = false;
        public boolean testDeletedConfig = false;
        // marked "deprecated"
        public boolean testCommonConfig = false;
        public boolean testRemainConfig = false;

        // Every config has 0.4 probability to be tested
        public double testUpgradeConfigRatio = 0.4;
        public double testRemainUpgradeConfigRatio = 0.4;

        // ------------Test Mode-------------
        public boolean testDowngrade = false;
        // failureOver = true: if the seed node in the distributed is dead
        // another node can keep executing commands
        public boolean failureOver = false;

        // 0: only full-stop test using StackedTestPacket
        // 1: N/A
        // 2: mixed test using MixedTestPlan
        // 3: Bug Reproduction: Rolling upgrade (given a test plan)
        // 4: full-stop upgrade + rolling upgrade iteratively (Final Version)
        // 5: Only test rolling upgrade using test plans
        // (differential or regular)
        // 6: Rolling-only differential fuzzing with branch-only guidance
        // (same as mode 5 but forces useTrace=false)
        public int testingMode = 0;
        public boolean testSingleVersion = false;
        // This make the test plan interleave with
        // full-stop upgrade
        public boolean fullStopUpgradeWithFaults = false;

        // Debug option
        public boolean startUpClusterForDebugging = false;

        public boolean keepClusterBeforeExecutingTestplan = false;
        public boolean keepClusterAfterExecutingTestplan = false;

        public boolean useExampleTestPlan = false;
        public boolean debug = false;

        // ---------------Log Check------------------
        // check ERROR/WARN in log
        public boolean enableLogCheck = true;
        public int grepLineNum = 4;
        public boolean filterLogBeforeUpgrade = false;

        // ---------------Test Graph-----------------
        public String testGraphDirPath = "graph";

        // ---------------Format Coverage-----------------
        // whether to use format coverage to guide the test (add to corpus)
        // If disabled, we also won't collect format coverage
        public boolean useFormatCoverage = false;

        // Coverage evaluation
        public boolean addTestToBothFCandVD = false;

        // Only one of the following can be true
        public boolean staticVD = false; // A superSet of isSerialized

        // true: use source code comparison to extract modified formats
        // false: binary analysis for both versions and then do comparison
        public boolean srcVD = true;
        public VDType vdType = VDType.all;

        public enum VDType {
            all, classNameMatch, typeChange
        }

        // Add multi-inv also to VD corpus
        public boolean prioritizeMultiInv = false;
        public boolean prioritizeIsSerialized = false; // For ablation
                                                       // experiments

        // For <Multiple likely invariants broken at the same time>: optimized
        // with frequency
        public boolean updateInvariantBrokenFrequency = true;
        public boolean checkSpecialDumpIds = false; // Deprecated

        // NonVersionDeltaMode
        public double BC_CorpusNonVersionDelta = 0.2;
        public double FC_CorpusNonVersionDelta = 0.6;
        public double FC_MOD_CorpusNonVersionDelta = 0;
        public double BoundaryChange_CorpusNonVersionDelta = 0.2;

        public int formatCoveragePort = 62000;

        public String baseClassInfoFileName = "serializedFields_alg1.json";
        public String topObjectsFileName = "topObjects.json";
        public String comparableClassesFileName = "comparableClasses.json";
        public String branch2CollectionFileName = "branch2Collection.json";
        public String specialDumpIdsFileName = "modifiedDumpIds.json";

        public String modifiedFieldsFileName = "modifiedFields.json";
        public String modifiedFieldsClassnameMustMatchFileName = "modifiedFields_classname_must_match.json";
        // modifiedFields_only_type_change.json
        public String modifiedFieldsClassnameOnlyTypeChangeFileName = "modifiedFields_only_type_change.json";

        // ---------------Version Delta-----------------
        public boolean useVersionDelta = false; // Dynamic VD

        public int versionDeltaApproach = 2;

        // Approach 1: Five Queue Implementation with boundary: no boundary
        // delta
        public double FC_VD_PROB_CorpusVersionDeltaFiveQueueWithBoundary = 0.3;
        public double FC_PROB_CorpusVersionDeltaFiveQueueWithBoundary = 0.2;
        public double BC_VD_PROB_CorpusVersionDeltaFiveQueueWithBoundary = 0.1;
        public double BC_PROB_CorpusVersionDeltaFiveQueueWithBoundary = 0.1;
        public double BoundaryChange_PROB_CorpusVersionDeltaFiveQueueWithBoundary = 0.3;

        // Approach 2: Six Queue Implementation
        // Group1
        public double branchVersionDeltaChoiceProb = 0.2;
        public double formatVersionDeltaChoiceProb = 0.4;
        public double branchCoverageChoiceProb = 0.1;
        public double formatCoverageChoiceProb = 0.2;
        public double boundaryRelatedSeedsChoiceProb = 0.1;

        // Approach 2
        public boolean enableNyxInGroup2 = false;

        public double DROP_TEST_PROB_G2 = 0.1;

        // Measure coverage of occurred references...
        public int staticVDMeasureInterval = 100;

        // -----------Network Trace Coverage-------------

        // If true: collect the trace
        public boolean useTrace = false;

        public boolean differentialExecution = false;
        // Timeout budget (seconds) for collecting each differential lane
        // feedback (old-old / rolling / new-new). Applied to all systems.
        public int differentialLaneTimeoutSec = 1200;

        public boolean printTrace = false;

        // --- Canonical trace similarity (Phase 4) ---
        public boolean useCanonicalTraceSimilarity = true;

        // Window-level thresholds
        public double canonicalRollingMinWindowSimilarityThreshold = 0.75;
        public double canonicalWindowDivergenceMarginThreshold = 0.08;
        public int canonicalMinWindowEventCount = 5;

        // Aggregate thresholds
        public double canonicalRollingMinAggregateSimilarityThreshold = 0.85;
        public double canonicalAggregateDivergenceMarginThreshold = 0.05;

        // Tri-diff thresholds (canonical keys)
        public boolean useCanonicalMessageIdentityDiff = true;
        public int rollingExclusiveMinCount = 3;
        public int rollingMissingMinCount = 3;
        public double rollingExclusiveFractionThreshold = 0.05;
        public double rollingMissingFractionThreshold = 0.05;

        // --- Phase 2 trace evidence strength gates ---
        // Phase 2 makes trace evidence support-aware, stage-aware, and
        // change-aware. Windows that fire the tri-diff exclusive rule (or
        // the window-sim rule) are still visible for observability, but a
        // window must additionally pass the knobs below before the
        // round-level trace strength can be promoted to STRONG. Windows
        // that fire without passing these gates are classified as WEAK
        // (or UNSUPPORTED when no three-way shared support exists).
        //
        // All gates are inclusive (">= threshold") so a conservative
        // default of 0 disables the knob and preserves Phase 0 behavior.
        // The defaults below match the Apr15 offline-replay target
        // (`strongTraceMinAllThreeCount=3`) and leave the stricter
        // baseline/similarity/changed-message knobs open for offline
        // tuning.
        public int strongTraceMinAllThreeCount = 3;
        public int strongTraceMinBaselineSharedCount = 3;
        public double strongTraceMinBaselineSimilarity = 0.70;
        public int strongTraceMinChangedMessageCount = 0;
        public int strongTraceMinUpgradedBoundaryCount = 0;
        // When false (Phase 2 default), PRE_UPGRADE windows can never
        // upgrade a branch admission into STRONG trace-backed status.
        // The flag exists so offline replay can A/B the policy without
        // rebuilding.
        public boolean preUpgradeTraceCanStrengthenBranch = false;
        // Apr15 cutoff for "strong support alone": when changed-message
        // and upgraded-boundary evidence is absent, the window can still
        // reach STRONG if both (a) totalAllThreeCount is at or above
        // this threshold and (b) rollingMinSimilarity drops far below
        // the baseline. The defaults are intentionally strict so only
        // large, highly divergent windows qualify.
        public int strongTraceFallbackMinAllThreeCount = 20;
        public double strongTraceFallbackMaxRollingMinSimilarity = 0.40;

        // --- Canonical key tier (Phase 3) ---
        // Controls how strictly two messages are considered the same by
        // window similarity and tri-diff. SEMANTIC is the original Phase 2
        // key; SEMANTIC_SHAPE_SUMMARY is the Apr 12 recommended default
        // because it separates within-semantic drift while still bucketing
        // benign value noise so it can survive cross-version refactors.
        // SEMANTIC_SHAPE_VALUE is reserved for offline analysis; it
        // over-fragments in cross-version campaigns.
        public CanonicalKeyMode canonicalKeyMode = CanonicalKeyMode.SEMANTIC_SHAPE_SUMMARY;

        // --- Phase 4 trace-signature dedup ---
        // Suppress trace-only admissions whose interesting-window
        // signatures are already saturated in a bounded recent-signature
        // index. Branch-backed admissions are exempt.
        public boolean useTraceSignatureDedup = true;

        // Number of prior trace-only admissions with the same compact
        // trace signature required before later trace-only admissions are
        // suppressed.
        public int traceSignatureSaturationThreshold = 2;

        // Sliding round window for the recent-signature index. Entries
        // older than this many completed rounds are forgotten.
        public int traceSignatureLookbackRounds = 100;

        // Hard cap on the total recorded signature entries kept in the
        // recent-signature index. Oldest entries are evicted first once
        // the cap is reached.
        public int traceSignatureIndexCapacity = 256;

        // Maximum number of rolling-exclusive / rolling-missing canonical
        // buckets retained in each compact per-window trace signature.
        public int traceSignatureTopBucketLimit = 3;

        // Debug
        public boolean useCompressedOrderDebug = false;

        // --- Phase 0 observability ---
        // If true, the server writes reason-coded admission counters,
        // seed lifecycle metadata, and per-window trigger rows to
        // <failureDir>/observability/. Can be turned off if the CSV
        // writes become expensive on long campaigns.
        public boolean enableObservabilityArtifacts = true;

        // --- Phase 2 corpus admission and retention ---
        // Master switch for Phase 2 tiered corpus. When true, the rolling
        // seed corpus routes trace-only admissions into a probation pool,
        // reserves capacity for branch-backed seeds, and biases parent
        // selection. When false, the corpus falls back to the legacy
        // "admit everything" cycle-queue behavior so Phase 2 can be
        // disabled without rebuilding.
        public boolean useTraceProbation = true;

        // Soft total cap on the rolling corpus. The trace-only pool
        // share is computed as floor(rollingCorpusMaxSize *
        // traceOnlyCorpusMaxShare). Branch-backed seeds are never
        // rejected because of this cap — it only bounds trace-only
        // growth.
        public int rollingCorpusMaxSize = 500;

        // Upper bound on the trace-only pool (probation + promoted) as a
        // fraction of rollingCorpusMaxSize. Defaults to 0.33 per the
        // Apr 12 plan.
        public double traceOnlyCorpusMaxShare = 0.33;

        // Maximum trace-only admissions per single round.
        public int traceOnlyAdmissionCapPerRound = 1;

        // Maximum trace-only admissions over any sliding 100-round
        // window.
        public int traceOnlyAdmissionCapPer100Rounds = 15;

        // Number of rounds a trace-only seed may stay in probation
        // without any downstream payoff before being evicted.
        public int traceOnlyProbationRounds = 50;

        // Number of times a trace-probation seed may be selected as a
        // mutation parent without any downstream payoff before being
        // evicted.
        public int traceProbationMaxSelectionsWithoutPayoff = 10;

        // Number of independent rediscoveries (same command-sequence
        // content admitted again while still in probation) required to
        // promote a trace-probation seed to the long-lived trace pool.
        public int traceProbationRediscoveryThreshold = 3;

        // Probability that a call to RollingSeedCorpus.getSeed() first
        // tries the branch-backed pool. Defaults to 0.5 per the Apr 12
        // plan recommendation of a 50/50 split between branch-backed
        // and promoted-trace pools.
        public double branchBackedSelectionWeight = 0.5;

        /**
         * ---------------Version Specific-----------------
         * To avoid FPs
         * If a command is supported only in the new/old version,
         * this can cause FP when comparing the read results.
         * Do not modify these default configurations!
         */
        // == cassandra ==

        // Live check1: process should appear in ps -ef after WAIT_INTERVAL
        public boolean cassandraEnableTimeoutCheck = true;
        public int WAIT_INTERVAL = 15; // seconds

        // Live check2: the connection should be established within
        // CASSANDRA_RETRY_TIMEOUT
        public int CASSANDRA_RETRY_TIMEOUT = 180; // seconds

        public boolean eval_CASSANDRA13939 = false;
        public boolean enable_ORDERBY_IN_SELECT = true;

        // Make sure not affected by forward read, we filter out those read
        // commands
        public boolean eval_14803_filter_forward_read = false;

        public boolean eval_CASSANDRA14912 = false;
        public boolean eval_CASSANDRA15970 = false;

        public int CASSANDRA_COLUMN_NAME_MAX_SIZE = 20;
        public int CASSANDRA_LIST_TYPE_MAX_SIZE = 10;
        public boolean CASSANDRA_ENABLE_SPECULATIVE_RETRY = true;

        // Three choices: disable, flush or drain
        public boolean flushAfterTest = true;
        // Drain: remove all commit logs
        public boolean drain = true;

        // == hdfs ==
        // If true: first create fsimage, then execute some commands
        // to test the edits log replay. If false, no edits log will
        // be replayed in the new version.
        public boolean prepareImageFirst = true;
        // If false: it won't create FSImage before upgrade
        public boolean enable_fsimage = true;
        public double new_fs_state_prob = 0.005;

        public boolean support_EC = false; // > 2
        public boolean support_StorageType_PROVIDED = false; // > 2
        public boolean support_count_e_opt = false; // > 2
        public boolean support_du_v_opt = false; // > 2

        public boolean eval_HDFS16984 = false;
        // Disable this to avoid triggering HDFS-17174
        public boolean enable_checksum = true; // du can be tested for version >
                                               // 2
        // Disable this when evaluating HDFS-16984
        public boolean enable_count = true; // du can be tested for version > 2
        public boolean enable_ls_u_option = true; // access time

        public boolean enable_du = false; // du can be tested for version > 2
        public boolean support_StorageType_NVDIMM = false; // >= 3.4.0
        public boolean support_checksum_v_opt = false; // > 3.3.x

        public boolean maskTimestamp = true;

        public boolean enable_HDFS_READ_CMD_SEQ_LEN = false;

        // == hbase ==
        // Wait for process to start up for hbaseDaemonRetryTimes * 5 seconds
        public int hbaseDaemonRetryTimes = 40;
        public boolean enableHBaseReadResultComparison = true;
        public boolean enable_IS_DISABLED = true;
        public boolean enable_LIST_SNAPSHOTS = true;
        public boolean enable_LIST_QUOTA_TABLE_SIZES = true;
        public boolean enable_DESCRIBE_NAMESPACE = true;

        public boolean enableQuota = true;
        public int MAX_CF_NUM = 7;
        public int REGIONSERVER_PORT = 16020;

        public String[] getHBaseRegionServers() {
            int regionServerCount = Math.max(1, nodeNum - 1);
            String[] regionServers = new String[regionServerCount];
            for (int i = 0; i < regionServerCount; i++) {
                regionServers[i] = "hregion" + (i + 1);
            }
            return regionServers;
        }

        public String getHBaseZookeeperQuorum() {
            if (nodeNum <= 1) {
                return "hmaster";
            }
            StringBuilder quorum = new StringBuilder("hmaster");
            for (String regionServer : getHBaseRegionServers()) {
                quorum.append(",").append(regionServer);
            }
            return quorum.toString();
        }

        public boolean eval_HBASE22503 = false;
        public boolean reproduce_HBASE22503 = false;

        // == ozone ==
        // Add a special first command... (will be deprecated)
        public boolean ozoneAppendSpecialCommand = false;

        public boolean testFSCommands = true;
        public boolean testSHCommands = true;

        public boolean enable_KeyLs = false;

        public boolean enable_VolumeInfo = false;
        public boolean enable_BucketInfo = false;
        public boolean support_createSnapshot = false;

        // == unit test ==
        public boolean eval_UnitTest = false;

        @Override
        public String toString() {
            return new GsonBuilder()
                    .setPrettyPrinting()
                    .disableHtmlEscaping()
                    .create()
                    .toJson(this, Configuration.class);
        }

        public void normalizeModeFlags() {
            if (testingMode == 6) {
                differentialExecution = true;
                useBranchCoverage = true;
                useTrace = false;
                useCanonicalTraceSimilarity = false;
                useCanonicalMessageIdentityDiff = false;
                useCompressedOrderDebug = false;
                printTrace = false;
                useFormatCoverage = false;
                useVersionDelta = false;
                // Preserve mode-5 oracle semantics:
                // do not force enableLogCheck on or off here.
            }
        }

        public Boolean checkNull() {
            Field[] fields = this.getClass().getDeclaredFields();
            for (Field field : fields) {
                try {
                    Object fieldObject = field.get(this);
                    if (fieldObject == null) {
                        // logger.error("Configuration failed to find: " +
                        // field);
                    }
                } catch (IllegalArgumentException | IllegalAccessException e) {
                    e.printStackTrace();
                    System.exit(1);
                }
            }

            // assertTrue(Arrays.stream(fields).anyMatch(
            // field -> field.getName().equals(LAST_NAME_FIELD) &&
            // field.getType().equals(String.class)));
            return true;
        }
    }

    public static void setInstance(Configuration config) {
        config.normalizeModeFlags();
        instance = config;
    }
}
