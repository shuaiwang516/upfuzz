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

    public static final String ROLLING_GENERATION_POLICY_GUIDED = "guided";
    public static final String ROLLING_GENERATION_POLICY_PURE_RANDOM =
            "pure_random";

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

        // Rolling-only fuzzing input generation policy. "guided" is the
        // existing mode-5 corpus/mutation scheduler; "pure_random" generates
        // a fresh random seed and test plan for every queued rolling round.
        public String rollingGenerationPolicy =
                ROLLING_GENERATION_POLICY_GUIDED;

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

        // --- Phase 3 value-weighted scheduling ---
        // Master switch for the stratified test-plan scheduler. When true,
        // the short-term TestPlanCorpus uses priority-class queues
        // (main_exploit / branch_scout / shadow_eval / repro_confirm)
        // with weighted round-robin selection and class-aware mutation
        // budgets; when false, the corpus falls back to legacy FIFO
        // behavior so Phase 3 can be A/B tested or rolled back without
        // a rebuild.
        public boolean usePriorityTestPlanScheduler = true;

        // Class-aware mutation budget knobs. Each admitted plan consumes
        // the mutation epoch tied to its queue priority class at dequeue
        // time instead of the flat {@link #testPlanMutationEpoch}. A
        // value of 0 means the corresponding class is parked (queued but
        // never mutated) — useful for offline replay experiments.
        public int mainExploitMutationEpoch = 30;
        public int branchScoutMutationEpoch = 10;
        public int shadowEvalMutationEpoch = 4;
        public int reproConfirmMutationEpoch = 50;

        // Weighted round-robin weights across queue classes. The Phase 3
        // scheduler picks the next queue by highest weight/deficit; a
        // weight of 0 disables the queue entirely. Defaults bias
        // strongly toward main_exploit while keeping branch_scout alive.
        public int reproConfirmQueueWeight = 4;
        public int mainExploitQueueWeight = 8;
        public int branchScoutQueueWeight = 3;
        public int shadowEvalQueueWeight = 1;

        // Per-queue soft capacity. When a queue is full, the least
        // valuable entry (lowest score) is evicted on enqueue so weak
        // plans cannot monopolize queue memory. 0 means unbounded.
        public int mainExploitQueueMaxSize = 256;
        public int branchScoutQueueMaxSize = 256;
        public int shadowEvalQueueMaxSize = 128;
        public int reproConfirmQueueMaxSize = 64;

        // Short-term dedup: a newly admitted plan whose compact
        // signature matches a plan already in any queue is collapsed
        // into the existing entry (its score is bumped, not a second
        // copy enqueued). Set to false to disable dedup entirely.
        public boolean enableTestPlanCompactDedup = true;

        // Decay: after this many dequeues of the same lineage root
        // without any downstream payoff credit, the plan is demoted one
        // priority class (or dropped from shadow_eval). 0 disables
        // decay entirely.
        public int testPlanDequeueDecayThreshold = 3;

        // --- Phase 4 stage-focused mutation ---
        // Master switch for the Phase 4 stage-aware mutator and
        // confirmation-oriented child generation. When false, the
        // test plan scheduler and mutator fall back to the Phase 3
        // generic path (no stage hints, no confirmation reservation,
        // no pre-upgrade-only caps). Kept true by default so Phase 4
        // is the live mode-5 configuration but can be rolled back
        // without a rebuild.
        public boolean enableStageFocusedMutation = true;

        // Phase 4 stage-aware mutator controls. {@code
        // stageAwareMutationProbability} is the chance that a single
        // mutation epoch uses the stage-focused mutator instead of the
        // generic {@code TestPlan.mutate(...)} fallback. Set to 0 to
        // rely only on generic mutation while still populating hints
        // for offline analysis.
        public double stageAwareMutationProbability = 0.65;
        // Maximum number of stage-aware mutator families tried per
        // parent dequeue before falling back to the generic mutator.
        public int stageAwareMutationMaxAttempts = 3;

        // Phase 4 reusable plan templates. Disabled by default so
        // first-wave rollout exercises the stage-aware mutator only;
        // templates can be flipped on in a second wave.
        public boolean enableStageTemplates = true;
        // Probability that a template is applied instead of a
        // stage-aware mutator on any given mutation epoch where a
        // usable hint is present.
        public double stageTemplateProbability = 0.25;

        // Phase 4 pre-upgrade-only parent cap. When true, a parent
        // whose stage hint reports firing only in PRE_UPGRADE windows
        // is down-ranked one scheduler lane and its mutation epoch is
        // capped at {@code preUpgradeOnlyMutationEpochCap}.
        public boolean preUpgradeOnlyDownrank = true;
        public int preUpgradeOnlyMutationEpochCap = 6;

        // Phase 4 confirmation budgets. A strong-structured candidate
        // parent gets {@code strongCandidateConfirmationBudget}
        // low-edit-distance / replay / minimization children emitted
        // outside the normal exploration queue; weak structured
        // divergences get the smaller {@code weakCandidateConfirmationBudget}.
        // 0 disables confirmation generation for that tier.
        public int strongCandidateConfirmationBudget = 6;
        public int weakCandidateConfirmationBudget = 2;

        // --- Phase 4 branch-backbone efficiency controls ---
        // These are shared scheduler policy knobs (trace-agnostic) that
        // keep branch-only exploration healthy and bound weak-candidate
        // cost. Phase 6 A/B validation holds these constant across
        // trace-on and trace-off arms so trace benefit is isolated.
        //
        // Master switch for the branch-backbone controls. When false
        // the scheduler behaves like Phase 3 — global decay threshold
        // only, no per-lane decay, no quarantine, no reweight bonus.
        public boolean enableBranchBackboneControls = true;

        // Extra score boost applied to a BRANCH_SCOUT / MAIN_EXPLOIT
        // entry on every downstream branch payoff credit. Layers on
        // top of the base payoff score bump so productive branch
        // parents stay favored over low-value branch-only churn.
        public double branchBackbonePayoffBonus = 1.0;

        // Per-lane decay thresholds. When set to a positive value, the
        // scheduler decays entries in that lane after this many
        // dequeues without any downstream payoff credit, overriding
        // the global {@code testPlanDequeueDecayThreshold}. 0 means
        // "use the global threshold". Defaults accelerate decay for
        // low-value lanes (branch-scout without payoff = 4, shadow-eval
        // without payoff = 2) so unhelpful plans clear out faster.
        public int branchScoutDecayThreshold = 4;
        public int shadowEvalDecayThreshold = 2;
        public int mainExploitDecayThreshold = 0;
        public int reproConfirmDecayThreshold = 0;

        // Weak-candidate quarantine. When a lineage root accumulates
        // {@code weakCandidateQuarantineDecayEvents} SHADOW_EVAL
        // decays without any downstream payoff, new admissions from
        // that lineage are rejected for {@code
        // weakCandidateQuarantineRounds} subsequent rounds. Prevents
        // a repeatedly-useless weak-trace pattern from cycling through
        // the shadow lane forever. 0 disables quarantine entirely.
        public int weakCandidateQuarantineDecayEvents = 2;
        public int weakCandidateQuarantineRounds = 50;

        // Phase 4 lower-confidence trace admission. When true, the
        // round-level trace gate admits non-STRONG trace rounds that
        // still carry structural support — WEAK with at least
        // flow-backed 3-way overlap, or UNSUPPORTED_BUT_REPEATABLE
        // (repeated rolling-only upgrade-critical traffic). These
        // lower-confidence admissions route to SHADOW_EVAL via the
        // existing BRANCH_AND_WEAK_TRACE / TRACE_ONLY_WEAK
        // priority classes so the Phase 4 routing plan (strong trace
        // → MAIN_EXPLOIT, repeatable/supported weak → SHADOW_EVAL)
        // is actually reachable from real server execution.
        //
        // Kept as a knob so Phase 6 A/B validation can hold this
        // constant across trace-on and trace-off arms and so the
        // Phase 3 STRONG-only admission policy can be restored for
        // rollback.
        public boolean enableLowerConfidenceTraceAdmission = true;

        // --- Phase 5 useful coverage guidance ---
        // Master switch for Phase 5 coverage-quality guidance. When
        // true, branch novelty is classified by source (rolling-only vs
        // baseline vs shared) and the scheduler boosts seeds with
        // rolling-post-upgrade novelty. When false, all branch novelty
        // is treated equally (Phase 3/4 behavior).
        public boolean enableCoverageGuidance = true;

        // Score boosts applied to the QueuedTestPlan initial score based
        // on the round's BranchNoveltyClass. Higher boost = more
        // mutation energy for that novelty type.
        public double rollingPostUpgradeScoreBoost = 3.0;
        public double rollingPreUpgradeScoreBoost = 0.5;
        public double sharedNoveltyScoreBoost = 1.0;
        public double baselineOnlyScoreBoost = 0.5;

        // When the branch-scout queue occupancy drops below this floor,
        // the scheduler avoids evicting branch-scout entries and
        // treats incoming branch-only admissions as high-priority so
        // branch-only exploration never collapses to near-zero.
        public int branchScoutMinOccupancy = 5;

        // Phase 5 optional stage coverage snapshots. When true, the
        // rolling-lane executor captures per-boundary coverage deltas
        // (after upgrade, after finalize) so the server can attribute
        // novelty to specific rolling-upgrade stages. Off by default
        // because round-level attribution is sufficient for the first
        // wave; enable for deeper temporal analysis.
        public boolean enableStageCoverageSnapshots = false;

        public int intervalMin = 10; // ms
        public int intervalMax = 200; // ms

        public int STACKED_TESTS_NUM = 1;
        public int STACKED_TESTS_NUM_G2 = 30;
        public long timeInterval = 600; // seconds, record time
        public boolean keepDir = true; // set to false if start a long-running
                                       // test
        public boolean preserveCandidateArtifacts = true;
        public int candidateArtifactMaxFiles = 64;
        public long candidateArtifactMaxBytesPerFile = 512 * 1024;
        public long candidateArtifactMaxTotalBytes = 4 * 1024 * 1024;
        public int candidateTraceSnippetMaxEntries = 500;
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

        // --- Mode 5 checkpoint/restore startup acceleration ---
        // Default false preserves the historical mode-5 behavior exactly:
        // every lane starts from a fresh cluster and executes the full test
        // plan. When true, only mode-5 differential rolling executions are
        // allowed to use the checkpoint-aware startup path.
        public boolean enableCheckpointRestore = false;
        // Nodes included in the deterministic checkpoint prefix. The rolling
        // lane upgrades these nodes before checkpointing; baseline lanes
        // restart the same node set so trace stages remain aligned.
        public int[] checkpointSelectedNodes = new int[] { 0 };
        // Keep all three differential lanes on the checkpoint path. Turning
        // this off is mainly for debugging the startup path and is not the
        // recommended fuzzing configuration.
        public boolean checkpointAllLanes = true;
        // Directory used for checkpoint metadata/artifacts.
        public String checkpointCacheDir = "fuzzing_storage/checkpoints";
        // Persistent checkpoint cache reuse. Checkpoint mode starts lanes from
        // committed image snapshots taken after the deterministic checkpoint
        // prefix. This avoids depending on Docker/CRIU restore support while
        // preserving online branch and network-trace guidance.
        public boolean checkpointReuse = false;
        // Initial support is intentionally Cassandra-first. HDFS/HBase have
        // extra preparation and sidecar process constraints and should be
        // enabled only after system-specific validation.
        public boolean checkpointAllowNonCassandra = false;
        // Deprecated compatibility knob. Checkpoint restore now always uses
        // the fast post-checkpoint suffix for checkpointed lanes: execute
        // workload events only, with no restart/upgrade/finalize lifecycle
        // events after the checkpointed cluster is launched.
        public boolean checkpointWorkloadOnlyBenchmark = false;

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

        // --- Phase 3 trace-strength scoring knobs ---
        // Phase 3 retires the Phase 2 hard-gate knobs (strongTraceMin*,
        // strongTraceFallback*, preUpgradeTraceCanStrengthenBranch) and
        // replaces them with a small composite scorer grounded in the
        // Phase 1 family taxonomy and the Phase 2 logical-flow
        // extraction. See
        // {@link
        // org.zlab.upfuzz.fuzzingengine.server.TraceWindowGuidanceScorer}
        // for the formula. Defaults are the first-cut replay calibration
        // (see agent/result/2026-04-19-result-phase-3-replay-calibration.md)
        // — support-backed upgrade-critical windows become reachable
        // without letting background-only windows dominate.
        //
        // Composite score = baselineAgreement * rollingDivergence
        // + boundaryBonus (if boundary traffic present)
        // + orderBonus (if support >= FLOW_BACKED)
        // — backgroundCap (if support < FAMILY_BACKED).

        /** Weight applied to UPGRADE_CRITICAL families inside the scorer. */
        public double traceUpgradeCriticalFamilyWeight = 1.0;

        /** Weight applied to BACKGROUND families. Keep low so gossip/heartbeat
         *  overlap cannot dominate baseline agreement. */
        public double traceBackgroundFamilyWeight = 0.2;

        /** Weight applied to UNKNOWN families. */
        public double traceUnknownFamilyWeight = 0.4;

        /** Composite score at or above this value produces STRONG. */
        public double traceStrongScoreThreshold = 0.35;

        /** Composite score at or above this value produces WEAK (when the
         *  window fired). Below this, the window still fires but the strength
         *  is WEAK because the composite signal is low. */
        public double traceWeakScoreThreshold = 0.10;

        /** Additive bonus applied when the window carries at least one
         *  upgraded-boundary crossing or boundary-involved flow. */
        public double traceBoundaryBonus = 0.15;

        /** Cap on the order-divergence bonus. Order is a bounded secondary
         *  signal per the Phase 3 plan; it can never dominate the composite. */
        public double traceOrderBonusCap = 0.10;

        /** Cap on the composite score when support is UNSUPPORTED or
         *  BACKGROUND_ONLY. A background-only window can never exceed this
         *  cap, so it cannot reach {@code traceStrongScoreThreshold}. */
        public double traceBackgroundCap = 0.10;

        /** Minimum baseline-baseline agreement required to promote a window
         *  to STRONG. Low baseline agreement means the two same-version lanes
         *  already disagree; rolling divergence from that unstable baseline
         *  is noise. */
        public double traceMinBaselineAgreementForStrong = 0.55;

        /** Minimum rolling divergence required to promote a window to
         *  STRONG. Guards against high-agreement rounds where the rolling
         *  lane barely drifted. */
        public double traceMinRollingDivergenceForStrong = 0.20;

        // --- Canonical key tier (Phase 1 online identity split) ---
        // Controls how strictly two messages are considered the same by
        // window similarity and tri-diff. Phase 1 introduced
        // {@link CanonicalKeyMode#GUIDANCE}: a role-first, protocol-family
        // identity produced by the {@code ProtocolFamilyClassifier}. This
        // is the new live-fuzzing default — it is stable under wrapper and
        // summary drift and replaces the pre-Phase-1 reliance on
        // {@link CanonicalKeyMode#SEMANTIC_SHAPE_SUMMARY}, which is now
        // kept only for offline diagnostic fixtures.
        public CanonicalKeyMode canonicalKeyMode = CanonicalKeyMode.GUIDANCE;

        // --- Phase 5 system-level scoring/routing preset ---
        // Picks the per-system trace policy bundle (scoring thresholds,
        // family weights, background cap, trace-only admission budget,
        // queue-routing weights). {@link TraceSystemPreset#AUTO} resolves
        // to the system-specific preset based on {@link #system};
        // {@link TraceSystemPreset#GENERIC} keeps the Java defaults as-is
        // (use this for ablation runs or per-knob experiments).
        // {@link Config#setInstance(Configuration)} applies the resolved
        // preset after {@link #normalizeModeFlags()}, so JSON-level
        // overrides of preset-controlled knobs are lost when this field
        // is non-{@code GENERIC}.
        public TraceSystemPreset traceSystemPreset = TraceSystemPreset.AUTO;

        // --- Phase 5 version-aware family-map profile ---
        // Path to a YAML file that supplies per-(rpcService, rpcMethod)
        // ProtocolFamily mappings beyond the Phase 1 hardcoded taxonomy.
        // The loader applies these only as long-tail extensions: the
        // profile fills entries the live classifier currently returns as
        // UNKNOWN, and never overrides an already-classified family. When
        // {@code null} or unreadable, no override is registered. Profiles
        // are produced by
        // {@code
        // nettrace-shuai/rupfuzz-nettrace/scripts/generate_family_inventories.sh}.
        public String familyMapProfilePath = null;

        // --- Phase 5 raw classifier-input dump (opt-in observability) ---
        // When {@code true}, the server emits a per-window CSV
        // {@code trace_window_classifier_inputs.csv} containing the raw
        // {@code (rpcService, rpcMethod, messageType, payloadType,
        // messageKind, protocol, currentFamily)} tuples plus per-tuple
        // counts for each lane (oo / ro / nn). This is the only artifact
        // that lets the Phase 5 replay tool exercise version-aware family
        // map changes — the standard {@code trace_window_summary.csv}
        // already carries aggregate family counts but not the raw
        // classifier inputs needed to re-classify under a different
        // profile. Off by default; turning it on is cheap but adds one
        // file per run and is mostly useful when staging a profile
        // change. {@code traceFlowTupleDumpTopK} bounds the per-(round,
        // window, lane) tuple count emitted; 0 means unbounded.
        public boolean enableTraceFlowTupleDump = false;
        public int traceFlowTupleDumpTopK = 50;

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

        // --- Phase 2 flow-summary observability ---
        // Maximum number of divergent families surfaced on each
        // WindowTriggerRow. Kept small so the CSV stays wide-enough to
        // scan but still tall enough to fit the top handful of
        // upgrade-critical families for Cassandra / HDFS / HBase.
        public int traceFlowTopDivergentFamiliesLimit = 3;

        // Maximum number of rolling-lane detail labels emitted per
        // divergent family. 0 disables the per-family cap (emit every
        // label); negative values behave identically. A tight cap keeps
        // HBase scan-heavy / HDFS heartbeat-heavy workloads from
        // flooding the CSV.
        public int traceFlowTopDivergentDetailsPerFamily = 5;

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
            normalizeRollingGenerationPolicy();
            if (testingMode == 6) {
                differentialExecution = true;
                useBranchCoverage = true;
                useTrace = false;
                useCanonicalTraceSimilarity = false;
                useCanonicalMessageIdentityDiff = false;
                printTrace = false;
                useFormatCoverage = false;
                useVersionDelta = false;
                // Preserve mode-5 oracle semantics:
                // do not force enableLogCheck on or off here.
            }
        }

        public boolean usePureRandomRollingGeneration() {
            return ROLLING_GENERATION_POLICY_PURE_RANDOM.equals(
                    normalizedRollingGenerationPolicy());
        }

        public boolean useGuidedRollingGeneration() {
            return ROLLING_GENERATION_POLICY_GUIDED.equals(
                    normalizedRollingGenerationPolicy());
        }

        private String normalizedRollingGenerationPolicy() {
            if (rollingGenerationPolicy == null
                    || rollingGenerationPolicy.trim().isEmpty()) {
                return ROLLING_GENERATION_POLICY_GUIDED;
            }
            return rollingGenerationPolicy.trim()
                    .toLowerCase(java.util.Locale.ROOT);
        }

        private void normalizeRollingGenerationPolicy() {
            String normalized = normalizedRollingGenerationPolicy();
            if (!ROLLING_GENERATION_POLICY_GUIDED.equals(normalized)
                    && !ROLLING_GENERATION_POLICY_PURE_RANDOM
                            .equals(normalized)) {
                throw new IllegalArgumentException(
                        "rollingGenerationPolicy must be '"
                                + ROLLING_GENERATION_POLICY_GUIDED
                                + "' or '"
                                + ROLLING_GENERATION_POLICY_PURE_RANDOM
                                + "' (got: "
                                + rollingGenerationPolicy + ")");
            }
            rollingGenerationPolicy = normalized;
        }

        /**
         * Phase 0 mode-scope helper. Encapsulates the decision "should the
         * rolling trace path treat {@code changedMessage} / {@code
         * modifiedFields.json} as an active corroborator?" so the Phase 0
         * mode-5 cleanup lives in one place instead of being scattered as
         * raw {@code testingMode == 5} checks across the scoring code.
         *
         * <p>Rules:
         * <ul>
         *   <li>{@code testingMode=5}: returns {@code false}. Mode 5 is the
         *       rolling-upgrade fuzzing path; {@code changedMessage} is a
         *       legacy signal from the older version-delta pipeline and
         *       the Apr16 campaign showed it never fires for rolling
         *       runs. Retiring it here stops the rolling scorer from
         *       depending on {@code modifiedFields.json} deployment.</li>
         *   <li>{@code testingMode=6}: returns {@code false}. Mode 6 is
         *       the explicit branch-only / trace-off baseline; the
         *       rolling trace path does not execute in this mode, but
         *       the helper reports {@code false} for consistency so
         *       future callers do not have to special-case it.</li>
         *   <li>All other modes: returns {@code true}. Legacy modes
         *       continue to treat {@code changedMessage} as an active
         *       corroborator — Phase 0 intentionally does not widen the
         *       cleanup into those modes.</li>
         * </ul>
         */
        public boolean useChangedMessageRollingTraceCorroboration() {
            return testingMode != 5 && testingMode != 6;
        }

        /**
         * Phase 0 mode-scope helper. {@code testingMode=6} is defined as
         * the explicit branch-only / trace-off validation baseline;
         * returning {@code true} lets observability code report "this is
         * the branch-only baseline" rather than re-deriving it from
         * combinations of raw flags.
         */
        public boolean isBranchOnlyBaselineMode() {
            return testingMode == 6;
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
        applyTraceSystemPreset(config);
        loadFamilyMapProfileIfPresent(config);
        instance = config;
    }

    /**
     * Phase 5 helper. Resolves {@link Configuration#traceSystemPreset} (handling
     * {@link TraceSystemPreset#AUTO}) and applies the resolved preset bundle
     * onto {@code config}. {@link TraceSystemPreset#GENERIC} is a no-op and
     * preserves the JSON-supplied values exactly. The resolved preset is
     * written back onto {@code traceSystemPreset} so the run artifact
     * records the concrete preset that was applied (never {@code AUTO}).
     */
    static void applyTraceSystemPreset(Configuration config) {
        if (config == null) {
            return;
        }
        TraceSystemPreset preset = config.traceSystemPreset;
        if (preset == null) {
            preset = TraceSystemPreset.AUTO;
        }
        TraceSystemPreset resolved = preset.resolve(config.system);
        resolved.applyTo(config);
        config.traceSystemPreset = resolved;
    }

    /**
     * Phase 5 helper. Loads the version-aware family-map profile from
     * {@link Configuration#familyMapProfilePath} (if set) and registers it
     * as the active long-tail override on
     * {@link org.zlab.net.tracker.classifier.ProtocolFamilyClassifier}.
     *
     * <p>The classifier override is JVM-global static state. {@code setInstance}
     * may be called multiple times in the same JVM (re-loading config in tests
     * or between replay runs), so this helper is responsible for clearing the
     * previously-registered override whenever the new {@code config} does NOT
     * supply a profile path, or when the path is unreadable / malformed. Tests
     * therefore see deterministic classifier behavior regardless of which other
     * tests ran first.
     *
     * <p>I/O or parse failures are logged but do not fail server startup —
     * the live classifier still works without an override.
     */
    static void loadFamilyMapProfileIfPresent(Configuration config) {
        if (config == null) {
            org.zlab.net.tracker.classifier.ProtocolFamilyClassifier
                    .setLongTailOverride(null);
            return;
        }
        String path = config.familyMapProfilePath;
        if (path == null || path.trim().isEmpty()) {
            org.zlab.net.tracker.classifier.ProtocolFamilyClassifier
                    .setLongTailOverride(null);
            return;
        }
        try {
            org.zlab.upfuzz.fuzzingengine.trace.VersionAwareFamilyProfile
                    .loadAndRegister(java.nio.file.Paths.get(path));
        } catch (java.io.IOException | RuntimeException e) {
            // Loader failed — clear any previously-registered override so a
            // subsequent run with no profile path does not silently inherit
            // the prior profile from JVM-global state.
            org.zlab.net.tracker.classifier.ProtocolFamilyClassifier
                    .setLongTailOverride(null);
            org.apache.logging.log4j.LogManager.getLogger(Config.class).warn(
                    "[Phase5] Failed to load family-map profile from {}: {}",
                    path, e.toString());
        }
    }
}
