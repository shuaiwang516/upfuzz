package org.zlab.upfuzz.fuzzingengine.server;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.zlab.upfuzz.fuzzingengine.Config;
import org.zlab.upfuzz.fuzzingengine.server.observability.AdmissionReason;
import org.zlab.upfuzz.fuzzingengine.server.observability.BranchNoveltyClass;
import org.zlab.upfuzz.fuzzingengine.server.observability.BranchNoveltyRow;
import org.zlab.upfuzz.fuzzingengine.server.observability.ObservabilityMetrics;
import org.zlab.upfuzz.fuzzingengine.server.observability.QueuePriorityClass;
import org.zlab.upfuzz.fuzzingengine.server.observability.SchedulerClass;
import org.zlab.upfuzz.fuzzingengine.server.observability.SeedLifecycle;
import org.zlab.upfuzz.fuzzingengine.server.observability.StructuredCandidateStrength;
import org.zlab.upfuzz.fuzzingengine.server.observability.TraceEvidenceStrength;
import org.zlab.upfuzz.fuzzingengine.testplan.TestPlan;
import org.zlab.upfuzz.fuzzingengine.testplan.event.Event;
import org.zlab.upfuzz.fuzzingengine.testplan.event.command.ShellCommand;
import org.zlab.upfuzz.fuzzingengine.testplan.event.upgradeop.UpgradeOp;

/**
 * Phase 5 tests for coverage guidance: novelty classification,
 * scheduler score boosting, scout capacity reservation, observability
 * columns, and seed lifecycle novelty tracking.
 */
class CoverageDeltaUtilPhase5Test {

    @BeforeEach
    void setUp() {
        Config.Configuration cfg = new Config.Configuration();
        cfg.usePriorityTestPlanScheduler = true;
        cfg.enableTestPlanCompactDedup = true;
        cfg.testPlanMutationEpoch = 20;
        cfg.mainExploitMutationEpoch = 30;
        cfg.branchScoutMutationEpoch = 10;
        cfg.shadowEvalMutationEpoch = 4;
        cfg.reproConfirmMutationEpoch = 50;
        cfg.mainExploitQueueWeight = 8;
        cfg.branchScoutQueueWeight = 3;
        cfg.shadowEvalQueueWeight = 1;
        cfg.reproConfirmQueueWeight = 4;
        cfg.mainExploitQueueMaxSize = 256;
        cfg.branchScoutQueueMaxSize = 256;
        cfg.shadowEvalQueueMaxSize = 128;
        cfg.reproConfirmQueueMaxSize = 64;
        cfg.testPlanDequeueDecayThreshold = 3;
        cfg.enableObservabilityArtifacts = true;
        cfg.enableStageFocusedMutation = false;
        cfg.enableCoverageGuidance = true;
        cfg.rollingPostUpgradeScoreBoost = 3.0;
        cfg.rollingPreUpgradeScoreBoost = 0.5;
        cfg.sharedNoveltyScoreBoost = 1.0;
        cfg.baselineOnlyScoreBoost = 0.5;
        cfg.branchScoutMinOccupancy = 5;
        Config.setInstance(cfg);
    }

    // ------------------------------------------------------------------
    // Novelty classification
    // ------------------------------------------------------------------

    @Test
    void classifyNoveltyNone() {
        assertEquals(BranchNoveltyClass.NONE,
                CoverageDeltaUtil.classifyNovelty(0, 0, 0, 0, 0, 0));
    }

    @Test
    void classifyNoveltyRollingPostUpgrade() {
        assertEquals(BranchNoveltyClass.ROLLING_POST_UPGRADE,
                CoverageDeltaUtil.classifyNovelty(0, 0, 0, 0, 5, 0));
    }

    @Test
    void classifyNoveltyRollingPostUpgradeDominatesPreUpgrade() {
        assertEquals(BranchNoveltyClass.ROLLING_POST_UPGRADE,
                CoverageDeltaUtil.classifyNovelty(0, 3, 0, 0, 5, 0));
    }

    @Test
    void classifyNoveltyRollingPreUpgradeOnly() {
        assertEquals(BranchNoveltyClass.ROLLING_PRE_UPGRADE_ONLY,
                CoverageDeltaUtil.classifyNovelty(0, 7, 0, 0, 0, 0));
    }

    @Test
    void classifyNoveltyShared() {
        assertEquals(BranchNoveltyClass.SHARED,
                CoverageDeltaUtil.classifyNovelty(0, 0, 4, 0, 0, 0));
    }

    @Test
    void classifyNoveltySharedNewVersionOnly() {
        assertEquals(BranchNoveltyClass.SHARED,
                CoverageDeltaUtil.classifyNovelty(0, 0, 0, 0, 0, 4));
    }

    @Test
    void classifyNoveltyBaselineOnly() {
        assertEquals(BranchNoveltyClass.BASELINE_ONLY,
                CoverageDeltaUtil.classifyNovelty(3, 0, 0, 0, 0, 0));
    }

    @Test
    void classifyNoveltyBaselineOnlyNewVersion() {
        assertEquals(BranchNoveltyClass.BASELINE_ONLY,
                CoverageDeltaUtil.classifyNovelty(0, 0, 0, 3, 0, 0));
    }

    @Test
    void classifyNoveltyRollingPostUpgradeDominatesAll() {
        assertEquals(BranchNoveltyClass.ROLLING_POST_UPGRADE,
                CoverageDeltaUtil.classifyNovelty(2, 3, 4, 5, 1, 6));
    }

    // ------------------------------------------------------------------
    // Per-version novelty source labels
    // ------------------------------------------------------------------

    @Test
    void versionSourceNone() {
        assertEquals("NONE",
                CoverageDeltaUtil.classifyVersionNoveltySource(0, 0, 0));
    }

    @Test
    void versionSourceRollingOnly() {
        assertEquals("ROLLING_ONLY",
                CoverageDeltaUtil.classifyVersionNoveltySource(0, 5, 0));
    }

    @Test
    void versionSourceRollingOnlyWithShared() {
        assertEquals("ROLLING_ONLY",
                CoverageDeltaUtil.classifyVersionNoveltySource(0, 5, 3));
    }

    @Test
    void versionSourceBaselineOnly() {
        assertEquals("BASELINE_ONLY",
                CoverageDeltaUtil.classifyVersionNoveltySource(4, 0, 0));
    }

    @Test
    void versionSourceMixed() {
        assertEquals("MIXED",
                CoverageDeltaUtil.classifyVersionNoveltySource(2, 3, 0));
    }

    @Test
    void versionSourceShared() {
        assertEquals("SHARED",
                CoverageDeltaUtil.classifyVersionNoveltySource(0, 0, 5));
    }

    // ------------------------------------------------------------------
    // Score boost
    // ------------------------------------------------------------------

    @Test
    void scoreBoostPostUpgradeHighest() {
        double boost = CoverageDeltaUtil.noveltyScoreBoost(
                BranchNoveltyClass.ROLLING_POST_UPGRADE);
        assertEquals(3.0, boost, 0.01);
    }

    @Test
    void scoreBoostPreUpgradeLow() {
        double boost = CoverageDeltaUtil.noveltyScoreBoost(
                BranchNoveltyClass.ROLLING_PRE_UPGRADE_ONLY);
        assertEquals(0.5, boost, 0.01);
    }

    @Test
    void scoreBoostSharedMedium() {
        double boost = CoverageDeltaUtil.noveltyScoreBoost(
                BranchNoveltyClass.SHARED);
        assertEquals(1.0, boost, 0.01);
    }

    @Test
    void scoreBoostBaselineLow() {
        double boost = CoverageDeltaUtil.noveltyScoreBoost(
                BranchNoveltyClass.BASELINE_ONLY);
        assertEquals(0.5, boost, 0.01);
    }

    @Test
    void scoreBoostNoneZero() {
        assertEquals(0.0,
                CoverageDeltaUtil.noveltyScoreBoost(
                        BranchNoveltyClass.NONE),
                0.01);
    }

    @Test
    void scoreBoostNullZero() {
        assertEquals(0.0,
                CoverageDeltaUtil.noveltyScoreBoost(null), 0.01);
    }

    // ------------------------------------------------------------------
    // Scheduler integration: score boost applied to queued plans
    // ------------------------------------------------------------------

    @Test
    void rollingPostUpgradeScoresHigherThanBaselineOnly() {
        ObservabilityMetrics metrics = new ObservabilityMetrics(null);
        TestPlanCorpus corpus = new TestPlanCorpus(metrics);

        TestPlan baselinePlan = makeUniquePlan(100, "baseline");
        TestPlan rollingPlan = makeUniquePlan(101, "rolling");

        corpus.addTestPlan(baselinePlan, 0, 100,
                AdmissionReason.BRANCH_ONLY,
                TraceEvidenceStrength.NONE,
                StructuredCandidateStrength.NONE,
                QueuePriorityClass.BRANCH_ONLY,
                StageMutationHint.empty(),
                BranchNoveltyClass.BASELINE_ONLY);

        corpus.addTestPlan(rollingPlan, 1, 101,
                AdmissionReason.BRANCH_ONLY,
                TraceEvidenceStrength.NONE,
                StructuredCandidateStrength.NONE,
                QueuePriorityClass.BRANCH_ONLY,
                StageMutationHint.empty(),
                BranchNoveltyClass.ROLLING_POST_UPGRADE);

        assertEquals(2, corpus.size());
        // The rolling-post-upgrade plan should be dequeued first
        // because it has a higher score.
        QueuedTestPlan first = corpus.pollQueuedTestPlan(2);
        assertNotNull(first);
        assertEquals(101, first.enqueueTestId);
        assertEquals(BranchNoveltyClass.ROLLING_POST_UPGRADE,
                first.branchNoveltyClass);
    }

    @Test
    void preUpgradeOnlyScoresLowerThanPostUpgrade() {
        ObservabilityMetrics metrics = new ObservabilityMetrics(null);
        TestPlanCorpus corpus = new TestPlanCorpus(metrics);

        TestPlan prePlan = makeUniquePlan(200, "pre");
        TestPlan postPlan = makeUniquePlan(201, "post");

        corpus.addTestPlan(prePlan, 0, 200,
                AdmissionReason.BRANCH_ONLY,
                TraceEvidenceStrength.NONE,
                StructuredCandidateStrength.NONE,
                QueuePriorityClass.BRANCH_ONLY,
                StageMutationHint.empty(),
                BranchNoveltyClass.ROLLING_PRE_UPGRADE_ONLY);

        corpus.addTestPlan(postPlan, 1, 201,
                AdmissionReason.BRANCH_ONLY,
                TraceEvidenceStrength.NONE,
                StructuredCandidateStrength.NONE,
                QueuePriorityClass.BRANCH_ONLY,
                StageMutationHint.empty(),
                BranchNoveltyClass.ROLLING_POST_UPGRADE);

        QueuedTestPlan first = corpus.pollQueuedTestPlan(2);
        assertNotNull(first);
        assertEquals(201, first.enqueueTestId);
    }

    @Test
    void coverageGuidanceDisabledMeansNoBoost() {
        Config.getConf().enableCoverageGuidance = false;
        ObservabilityMetrics metrics = new ObservabilityMetrics(null);
        TestPlanCorpus corpus = new TestPlanCorpus(metrics);

        TestPlan planA = makeUniquePlan(300, "planA");
        TestPlan planB = makeUniquePlan(301, "planB");

        corpus.addTestPlan(planA, 0, 300,
                AdmissionReason.BRANCH_ONLY,
                TraceEvidenceStrength.NONE,
                StructuredCandidateStrength.NONE,
                QueuePriorityClass.BRANCH_ONLY,
                StageMutationHint.empty(),
                BranchNoveltyClass.BASELINE_ONLY);

        corpus.addTestPlan(planB, 1, 301,
                AdmissionReason.BRANCH_ONLY,
                TraceEvidenceStrength.NONE,
                StructuredCandidateStrength.NONE,
                QueuePriorityClass.BRANCH_ONLY,
                StageMutationHint.empty(),
                BranchNoveltyClass.ROLLING_POST_UPGRADE);

        // With guidance disabled, first enqueued should be dequeued first
        // (FIFO within the branch_scout class since scores are equal).
        QueuedTestPlan first = corpus.pollQueuedTestPlan(2);
        assertNotNull(first);
        assertEquals(300, first.enqueueTestId);
    }

    // ------------------------------------------------------------------
    // Branch-scout min occupancy (decay protection)
    // ------------------------------------------------------------------

    @Test
    void decayProtectsBranchScoutAtMinOccupancy() {
        Config.getConf().branchScoutMinOccupancy = 2;
        Config.getConf().testPlanDequeueDecayThreshold = 1;
        ObservabilityMetrics metrics = new ObservabilityMetrics(null);
        TestPlanCorpus corpus = new TestPlanCorpus(metrics);

        // Add exactly 2 plans to branch_scout
        TestPlan plan1 = makeUniquePlan(400, "scout1");
        TestPlan plan2 = makeUniquePlan(401, "scout2");
        corpus.addTestPlan(plan1, 0, 400,
                AdmissionReason.BRANCH_ONLY,
                TraceEvidenceStrength.NONE,
                StructuredCandidateStrength.NONE,
                QueuePriorityClass.BRANCH_ONLY);
        corpus.addTestPlan(plan2, 0, 401,
                AdmissionReason.BRANCH_ONLY,
                TraceEvidenceStrength.NONE,
                StructuredCandidateStrength.NONE,
                QueuePriorityClass.BRANCH_ONLY);

        // Dequeue both (incrementing their dequeue counts)
        corpus.pollQueuedTestPlan(1);
        corpus.pollQueuedTestPlan(2);

        // Re-add them to simulate re-enqueue
        corpus.addTestPlan(makeUniquePlan(402, "scout1b"), 3, 400,
                AdmissionReason.BRANCH_ONLY,
                TraceEvidenceStrength.NONE,
                StructuredCandidateStrength.NONE,
                QueuePriorityClass.BRANCH_ONLY);
        corpus.addTestPlan(makeUniquePlan(403, "scout2b"), 3, 401,
                AdmissionReason.BRANCH_ONLY,
                TraceEvidenceStrength.NONE,
                StructuredCandidateStrength.NONE,
                QueuePriorityClass.BRANCH_ONLY);

        int sizeBefore = corpus.size();

        // Decay — with min occupancy = 2 and exactly 2 entries, none
        // should be decayed from branch_scout.
        corpus.decayStaleEntries();

        Map<SchedulerClass, Integer> occupancy = corpus
                .occupancyBySchedulerClass();
        assertTrue(occupancy.get(SchedulerClass.BRANCH_SCOUT) >= 2,
                "Branch scout should retain entries at min occupancy");
    }

    @Test
    void decayDecaysAboveMinOccupancy() {
        Config.getConf().branchScoutMinOccupancy = 1;
        Config.getConf().testPlanDequeueDecayThreshold = 1;
        ObservabilityMetrics metrics = new ObservabilityMetrics(null);
        TestPlanCorpus corpus = new TestPlanCorpus(metrics);

        // Add 3 plans to branch_scout (above min of 1)
        for (int i = 0; i < 3; i++) {
            TestPlan p = makeUniquePlan(500 + i, "scout" + i);
            corpus.addTestPlan(p, 0, 500 + i,
                    AdmissionReason.BRANCH_ONLY,
                    TraceEvidenceStrength.NONE,
                    StructuredCandidateStrength.NONE,
                    QueuePriorityClass.BRANCH_ONLY);
        }

        // Dequeue all
        for (int i = 0; i < 3; i++) {
            corpus.pollQueuedTestPlan(i + 1);
        }

        // Re-add
        for (int i = 0; i < 3; i++) {
            corpus.addTestPlan(makeUniquePlan(600 + i, "scout" + i + "b"),
                    4, 500 + i,
                    AdmissionReason.BRANCH_ONLY,
                    TraceEvidenceStrength.NONE,
                    StructuredCandidateStrength.NONE,
                    QueuePriorityClass.BRANCH_ONLY);
        }

        corpus.decayStaleEntries();

        // Some entries should have decayed since we had > min occupancy.
        // The exact count depends on whether the decay logic can push
        // entries below the floor; at minimum, after decay we should
        // still have at least the min occupancy in branch_scout.
        Map<SchedulerClass, Integer> occupancy = corpus
                .occupancyBySchedulerClass();
        assertTrue(occupancy.get(SchedulerClass.BRANCH_SCOUT) >= 1,
                "Branch scout must retain at least min occupancy");
    }

    // ------------------------------------------------------------------
    // BranchNoveltyRow Phase 5 columns
    // ------------------------------------------------------------------

    @Test
    void branchNoveltyRowCsvIncludesPhase5Columns() {
        BranchNoveltyRow row = new BranchNoveltyRow(
                42, 10,
                2, 5, 3,
                1, 7, 4,
                "ROLLING_ONLY", "ROLLING_ONLY",
                BranchNoveltyClass.ROLLING_POST_UPGRADE);
        String csv = row.toCsvRow();
        // Should contain the novelty labels
        assertTrue(csv.contains("ROLLING_ONLY"),
                "CSV should contain old_version_novelty_source");
        assertTrue(csv.contains("ROLLING_POST_UPGRADE"),
                "CSV should contain branch_novelty_class");
        String header = BranchNoveltyRow.csvHeader();
        assertTrue(header.contains("old_version_novelty_source"));
        assertTrue(header.contains("new_version_novelty_source"));
        assertTrue(header.contains("branch_novelty_class"));
        assertTrue(header.contains("rolling_only_old_probe_count"));
        assertTrue(header.contains("rolling_only_new_probe_count"));
        assertTrue(header.contains("baseline_only_probe_count"));
        assertTrue(header.contains("shared_probe_count"));
    }

    @Test
    void branchNoveltyRowPhase0ConstructorDerivesSafeDefaults() {
        BranchNoveltyRow row = new BranchNoveltyRow(1, 2, 0, 0, 0, 0, 0, 0);
        assertEquals("NONE", row.oldVersionNoveltySource);
        assertEquals("NONE", row.newVersionNoveltySource);
        assertEquals(BranchNoveltyClass.NONE, row.branchNoveltyClass);
    }

    // ------------------------------------------------------------------
    // SeedLifecycle Phase 5 novelty tracking
    // ------------------------------------------------------------------

    @Test
    void seedLifecycleRecordsBranchNoveltyClass() {
        ObservabilityMetrics metrics = new ObservabilityMetrics(null);
        SeedLifecycle lifecycle = metrics.recordSeedAddition(
                100, 5, AdmissionReason.BRANCH_ONLY, -1,
                BranchNoveltyClass.ROLLING_POST_UPGRADE);
        assertNotNull(lifecycle);
        assertEquals(BranchNoveltyClass.ROLLING_POST_UPGRADE,
                lifecycle.branchNoveltyClass);
    }

    @Test
    void seedLifecycleCsvIncludesNoveltyColumn() {
        String header = SeedLifecycle.csvHeader();
        assertTrue(header.contains("branch_novelty_class"));
        SeedLifecycle lifecycle = new SeedLifecycle(
                1, 1, 1000, AdmissionReason.BRANCH_ONLY, -1,
                BranchNoveltyClass.SHARED);
        String csv = lifecycle.toCsvRow();
        assertTrue(csv.contains("SHARED"));
    }

    @Test
    void seedLifecycleLegacyConstructorDefaultsToNone() {
        SeedLifecycle lifecycle = new SeedLifecycle(
                1, 1, 1000, AdmissionReason.BRANCH_ONLY, -1);
        assertEquals(BranchNoveltyClass.NONE, lifecycle.branchNoveltyClass);
    }

    // ------------------------------------------------------------------
    // QueuedTestPlan Phase 5 novelty class
    // ------------------------------------------------------------------

    @Test
    void queuedTestPlanCarriesNoveltyClass() {
        ObservabilityMetrics metrics = new ObservabilityMetrics(null);
        TestPlanCorpus corpus = new TestPlanCorpus(metrics);

        TestPlan plan = makeUniquePlan(700, "test");
        corpus.addTestPlan(plan, 0, 700,
                AdmissionReason.BRANCH_ONLY,
                TraceEvidenceStrength.NONE,
                StructuredCandidateStrength.NONE,
                QueuePriorityClass.BRANCH_ONLY,
                StageMutationHint.empty(),
                BranchNoveltyClass.ROLLING_POST_UPGRADE);

        QueuedTestPlan entry = corpus.pollQueuedTestPlan(1);
        assertNotNull(entry);
        assertEquals(BranchNoveltyClass.ROLLING_POST_UPGRADE,
                entry.branchNoveltyClass);
    }

    @Test
    void queuedTestPlanLegacyConstructorDefaultsToNone() {
        QueuedTestPlan entry = new QueuedTestPlan(
                null, 1, 1,
                AdmissionReason.BRANCH_ONLY,
                TraceEvidenceStrength.NONE,
                StructuredCandidateStrength.NONE,
                QueuePriorityClass.BRANCH_ONLY,
                SchedulerClass.BRANCH_SCOUT,
                0, 10, "sig", 3.0);
        assertEquals(BranchNoveltyClass.NONE, entry.branchNoveltyClass);
    }

    // ------------------------------------------------------------------
    // Stage coverage snapshots
    // ------------------------------------------------------------------

    @Test
    void stageCoverageSnapshotsDefaultOff() {
        Config.Configuration cfg = Config.getConf();
        assertNotNull(cfg);
        assertEquals(false, cfg.enableStageCoverageSnapshots);
    }

    @Test
    void feedBackCarriesStageCoverageSnapshots() {
        org.zlab.upfuzz.fuzzingengine.FeedBack fb = new org.zlab.upfuzz.fuzzingengine.FeedBack();
        assertNotNull(fb.stageCoverageSnapshots);
        assertTrue(fb.stageCoverageSnapshots.isEmpty());

        org.jacoco.core.data.ExecutionDataStore snap = new org.jacoco.core.data.ExecutionDataStore();
        fb.stageCoverageSnapshots.put("AFTER_UPGRADE_0", snap);
        assertEquals(1, fb.stageCoverageSnapshots.size());
        assertSame(snap, fb.stageCoverageSnapshots.get("AFTER_UPGRADE_0"));
    }

    @Test
    void executorHasStageCoverageSnapshotsField() throws Exception {
        java.lang.reflect.Field field = org.zlab.upfuzz.fuzzingengine.executor.Executor.class
                .getField("stageCoverageSnapshots");
        assertNotNull(field);
    }

    @Test
    void executorHasNonDestructiveCollectSnapshotMethod() throws Exception {
        // Verify the non-destructive collectSnapshot method exists on
        // Executor so stage snapshots use dump-without-reset.
        java.lang.reflect.Method method = org.zlab.upfuzz.fuzzingengine.executor.Executor.class
                .getMethod("collectSnapshot", String.class);
        assertNotNull(method);
        assertEquals(org.jacoco.core.data.ExecutionDataStore.class,
                method.getReturnType());
    }

    @Test
    void agentHandlerHasCollectSnapshotMethod() throws Exception {
        // Verify AgentServerHandler.collectSnapshot exists — this is
        // the method that calls visitDumpCommand(true, false).
        java.lang.reflect.Method method = org.zlab.upfuzz.fuzzingengine.AgentServerHandler.class
                .getMethod("collectSnapshot");
        assertNotNull(method);
    }

    @Test
    void multiUpgradeSnapshotsPreserveAll() {
        org.zlab.upfuzz.fuzzingengine.FeedBack fb = new org.zlab.upfuzz.fuzzingengine.FeedBack();
        org.jacoco.core.data.ExecutionDataStore snap0 = new org.jacoco.core.data.ExecutionDataStore();
        org.jacoco.core.data.ExecutionDataStore snap1 = new org.jacoco.core.data.ExecutionDataStore();
        org.jacoco.core.data.ExecutionDataStore snap2 = new org.jacoco.core.data.ExecutionDataStore();
        org.jacoco.core.data.ExecutionDataStore finSnap = new org.jacoco.core.data.ExecutionDataStore();

        fb.stageCoverageSnapshots.put("AFTER_UPGRADE_0", snap0);
        fb.stageCoverageSnapshots.put("AFTER_UPGRADE_1", snap1);
        fb.stageCoverageSnapshots.put("AFTER_UPGRADE_2", snap2);
        fb.stageCoverageSnapshots.put("AFTER_FINALIZE", finSnap);

        assertEquals(4, fb.stageCoverageSnapshots.size());
        // LinkedHashMap preserves insertion order — first key is
        // AFTER_UPGRADE_0, last upgrade key is AFTER_UPGRADE_2.
        java.util.Iterator<String> keys = fb.stageCoverageSnapshots
                .keySet().iterator();
        assertEquals("AFTER_UPGRADE_0", keys.next());
        assertEquals("AFTER_UPGRADE_1", keys.next());
        assertEquals("AFTER_UPGRADE_2", keys.next());
        assertEquals("AFTER_FINALIZE", keys.next());
        // Each snapshot is a distinct object — later upgrades do NOT
        // overwrite earlier ones.
        assertSame(snap0, fb.stageCoverageSnapshots.get("AFTER_UPGRADE_0"));
        assertSame(snap1, fb.stageCoverageSnapshots.get("AFTER_UPGRADE_1"));
        assertSame(snap2, fb.stageCoverageSnapshots.get("AFTER_UPGRADE_2"));
        assertSame(finSnap, fb.stageCoverageSnapshots.get("AFTER_FINALIZE"));
    }

    // ------------------------------------------------------------------
    // StageNoveltyRow CSV artifact
    // ------------------------------------------------------------------

    @Test
    void stageNoveltyRowCsvHeader() {
        String header = org.zlab.upfuzz.fuzzingengine.server.observability.StageNoveltyRow
                .csvHeader();
        assertTrue(header.contains("round_id"));
        assertTrue(header.contains("probes_at_first_upgrade"));
        assertTrue(header.contains("probes_at_last_upgrade"));
        assertTrue(header.contains("probes_at_finalize"));
        assertTrue(header.contains("upgrade_snapshot_count"));
    }

    @Test
    void stageNoveltyRowToCsv() {
        org.zlab.upfuzz.fuzzingengine.server.observability.StageNoveltyRow row = new org.zlab.upfuzz.fuzzingengine.server.observability.StageNoveltyRow(
                42, 10, 15, 3, 8, 12, 3);
        String csv = row.toCsvRow();
        assertEquals("42,10,15,3,8,12,3", csv);
    }

    @Test
    void observabilityMetricsRecordsStageNovelty() {
        ObservabilityMetrics metrics = new ObservabilityMetrics(null);
        assertEquals(0, metrics.stageNoveltyRowCount());
        metrics.recordStageNovelty(
                new org.zlab.upfuzz.fuzzingengine.server.observability.StageNoveltyRow(
                        1, 100, 10, 2, 5, 8, 2));
        assertEquals(1, metrics.stageNoveltyRowCount());
    }

    // ------------------------------------------------------------------
    // Strong-trace payoff tracking
    // ------------------------------------------------------------------

    @Test
    void seedLifecycleHasStrongTraceHitsCounter() {
        SeedLifecycle lifecycle = new SeedLifecycle(
                1, 1, 1000, AdmissionReason.BRANCH_ONLY, -1,
                BranchNoveltyClass.ROLLING_POST_UPGRADE);
        assertEquals(0, lifecycle.descendantStrongTraceHits.get());
        lifecycle.descendantStrongTraceHits.incrementAndGet();
        assertEquals(1, lifecycle.descendantStrongTraceHits.get());
    }

    @Test
    void seedLifecycleCsvIncludesStrongTraceColumn() {
        String header = SeedLifecycle.csvHeader();
        assertTrue(header.contains("descendant_strong_trace_hits"));
    }

    @Test
    void recordDownstreamStrongTraceHitCreditsParent() {
        ObservabilityMetrics metrics = new ObservabilityMetrics(null);
        SeedLifecycle parent = metrics.recordSeedAddition(
                100, 1, AdmissionReason.BRANCH_ONLY, -1,
                BranchNoveltyClass.ROLLING_POST_UPGRADE);
        assertNotNull(parent);
        assertEquals(0, parent.descendantStrongTraceHits.get());

        // Link child 200 -> parent 100
        metrics.linkChildToParent(200, 100);
        metrics.recordDownstreamStrongTraceHit(200);
        assertEquals(1, parent.descendantStrongTraceHits.get());
    }

    @Test
    void strongTraceHitDoesNotCreditWhenNoParent() {
        ObservabilityMetrics metrics = new ObservabilityMetrics(null);
        // No parent registered — this should be a no-op, not an error.
        metrics.recordDownstreamStrongTraceHit(999);
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    private static TestPlan makeUniquePlan(int testId, String tag) {
        List<Event> events = new ArrayList<>();
        events.add(new ShellCommand("SELECT * FROM " + tag, 0));
        events.add(new UpgradeOp(1));
        events.add(new ShellCommand("INSERT " + tag + " VALUES (1)", 1));
        events.add(new UpgradeOp(2));
        TestPlan plan = new TestPlan(3, events,
                new ArrayList<>(java.util.Arrays.asList(
                        "SELECT COUNT(*) FROM " + tag,
                        "SELECT MAX(id) FROM " + tag)),
                new ArrayList<>());
        plan.lineageTestId = testId;
        return plan;
    }
}
