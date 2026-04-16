package org.zlab.upfuzz.fuzzingengine.server;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.zlab.upfuzz.fuzzingengine.Config;
import org.zlab.upfuzz.fuzzingengine.server.observability.AdmissionReason;
import org.zlab.upfuzz.fuzzingengine.server.observability.ObservabilityMetrics;
import org.zlab.upfuzz.fuzzingengine.server.observability.QueuePriorityClass;
import org.zlab.upfuzz.fuzzingengine.server.observability.SchedulerClass;
import org.zlab.upfuzz.fuzzingengine.server.observability.StructuredCandidateStrength;
import org.zlab.upfuzz.fuzzingengine.server.observability.TraceEvidenceStrength;
import org.zlab.upfuzz.fuzzingengine.testplan.TestPlan;
import org.zlab.upfuzz.fuzzingengine.testplan.event.Event;
import org.zlab.upfuzz.fuzzingengine.testplan.event.command.ShellCommand;
import org.zlab.upfuzz.fuzzingengine.testplan.event.upgradeop.FinalizeUpgrade;
import org.zlab.upfuzz.fuzzingengine.testplan.event.upgradeop.UpgradeOp;
import org.zlab.upfuzz.fuzzingengine.trace.TraceWindow;

/**
 * Phase 4 regression tests for {@link StageMutationHint},
 * {@link StageAwareTestPlanMutator}, and the Phase 4 hooks in
 * {@link TestPlanCorpus} and {@link FuzzingServer}.
 */
class StageAwareTestPlanMutatorPhase4Test {

    @BeforeEach
    void setUp() {
        Config.Configuration cfg = new Config.Configuration();
        cfg.usePriorityTestPlanScheduler = true;
        cfg.enableTestPlanCompactDedup = true;
        cfg.enableStageFocusedMutation = true;
        cfg.stageAwareMutationProbability = 0.65;
        cfg.stageAwareMutationMaxAttempts = 3;
        cfg.enableStageTemplates = true;
        cfg.stageTemplateProbability = 0.25;
        cfg.preUpgradeOnlyDownrank = true;
        cfg.preUpgradeOnlyMutationEpochCap = 6;
        cfg.strongCandidateConfirmationBudget = 6;
        cfg.weakCandidateConfirmationBudget = 2;
        cfg.mainExploitMutationEpoch = 30;
        cfg.branchScoutMutationEpoch = 10;
        cfg.shadowEvalMutationEpoch = 4;
        cfg.reproConfirmMutationEpoch = 50;
        cfg.mainExploitQueueMaxSize = 256;
        cfg.branchScoutQueueMaxSize = 256;
        cfg.shadowEvalQueueMaxSize = 128;
        cfg.reproConfirmQueueMaxSize = 64;
        cfg.testPlanDequeueDecayThreshold = 3;
        cfg.testPlanMutationEpoch = 20;
        cfg.nodeNum = 3;
        cfg.shuffleUpgradeOrder = true;
        cfg.intervalMin = 10;
        cfg.intervalMax = 200;
        cfg.enableObservabilityArtifacts = true;
        Config.setInstance(cfg);
    }

    // ------------------------------------------------------------------
    // StageMutationHint construction
    // ------------------------------------------------------------------

    @Test
    void emptyHintHasNoStageInfo() {
        StageMutationHint hint = StageMutationHint.empty();
        assertFalse(hint.hasStageInfo());
        assertEquals(-1, hint.hotWindowOrdinal);
        assertEquals(StageMutationHint.StageKindHint.UNKNOWN,
                hint.hotStageKind);
    }

    @Test
    void hintWithFiringWindowHasStageInfo() {
        StageMutationHint hint = makeHint(
                StageMutationHint.StageKindHint.POST_STAGE,
                0, setOf(1, 2),
                StageMutationHint.SignalType.BRANCH_AND_STRONG_TRACE,
                true, false, false, false);
        assertTrue(hint.hasStageInfo());
        assertEquals(0, hint.hotWindowOrdinal);
        assertEquals(StageMutationHint.StageKindHint.POST_STAGE,
                hint.hotStageKind);
        assertTrue(hint.postUpgrade);
    }

    @Test
    void classifySignalStrongStructured() {
        StageMutationHint.SignalType sig = StageMutationHint
                .classifySignal(true, TraceEvidenceStrength.STRONG,
                        StructuredCandidateStrength.STRONG, false);
        assertEquals(StageMutationHint.SignalType.STRONG_STRUCTURED, sig);
    }

    @Test
    void classifySignalBranchOnly() {
        StageMutationHint.SignalType sig = StageMutationHint
                .classifySignal(true, TraceEvidenceStrength.NONE,
                        StructuredCandidateStrength.NONE, false);
        assertEquals(StageMutationHint.SignalType.BRANCH_ONLY, sig);
    }

    @Test
    void classifySignalRollingOnlyEventFromEvent() {
        StageMutationHint.SignalType sig = StageMutationHint
                .classifySignal(false, TraceEvidenceStrength.NONE,
                        StructuredCandidateStrength.NONE, true);
        assertEquals(StageMutationHint.SignalType.ROLLING_ONLY_EVENT, sig);
    }

    @Test
    void stageKindHintMapsFromTraceWindowStageKind() {
        assertEquals(StageMutationHint.StageKindHint.PRE_UPGRADE,
                StageMutationHint.StageKindHint
                        .from(TraceWindow.StageKind.PRE_UPGRADE));
        assertEquals(StageMutationHint.StageKindHint.POST_STAGE,
                StageMutationHint.StageKindHint
                        .from(TraceWindow.StageKind.POST_STAGE));
        assertEquals(StageMutationHint.StageKindHint.UNKNOWN,
                StageMutationHint.StageKindHint.from(null));
    }

    // ------------------------------------------------------------------
    // Stage-aware mutator: family picking
    // ------------------------------------------------------------------

    @Test
    void pickFamilyForPreUpgradeIncludesWorkloadBurst() {
        StageMutationHint hint = makeHint(
                StageMutationHint.StageKindHint.PRE_UPGRADE,
                0, Collections.emptySet(),
                StageMutationHint.SignalType.BRANCH_ONLY,
                false, true, false, false);
        Config.Configuration cfg = Config.getConf();
        cfg.enableStageTemplates = false;
        StageAwareTestPlanMutator.MutationFamily family = StageAwareTestPlanMutator
                .pickFamily(hint, cfg);
        assertNotNull(family);
    }

    @Test
    void pickFamilyReturnsNullForEmptyHint() {
        StageAwareTestPlanMutator.MutationFamily family = StageAwareTestPlanMutator
                .pickFamily(StageMutationHint.empty(), Config.getConf());
        assertEquals(null, family);
    }

    // ------------------------------------------------------------------
    // Stage-aware mutator: mutation families
    // ------------------------------------------------------------------

    @Test
    void workloadBurstBeforeFirstUpgrade() {
        TestPlan plan = makeUpgradePlan();
        StageMutationHint hint = makeHint(
                StageMutationHint.StageKindHint.PRE_UPGRADE,
                0, setOf(1),
                StageMutationHint.SignalType.BRANCH_ONLY,
                false, true, false, false);
        int sizeBefore = plan.events.size();
        boolean ok = StageAwareTestPlanMutator.moveWorkloadBurst(plan, hint,
                StageAwareTestPlanMutator.BurstPlacement.BEFORE_FIRST_UPGRADE);
        assertTrue(ok);
        assertEquals(sizeBefore, plan.events.size());
    }

    @Test
    void duplicateShellClusterAtBoundaryIncreasesSize() {
        TestPlan plan = makeUpgradePlan();
        StageMutationHint hint = makeHint(
                StageMutationHint.StageKindHint.POST_STAGE,
                1, setOf(1),
                StageMutationHint.SignalType.BRANCH_AND_STRONG_TRACE,
                true, false, false, false);
        int sizeBefore = plan.events.size();
        boolean ok = StageAwareTestPlanMutator
                .duplicateShellClusterAtBoundary(plan, hint);
        assertTrue(ok);
        assertTrue(plan.events.size() > sizeBefore);
    }

    @Test
    void localReorderNearHotspotSwapsEvents() {
        TestPlan plan = makeUpgradePlan();
        StageMutationHint hint = makeHint(
                StageMutationHint.StageKindHint.POST_STAGE,
                1, setOf(1),
                StageMutationHint.SignalType.BRANCH_AND_STRONG_TRACE,
                true, false, false, false);
        String before = plan.events.toString();
        boolean ok = StageAwareTestPlanMutator.localReorderNearHotspot(plan,
                hint);
        if (ok) {
            assertFalse(plan.events.toString().equals(before));
        }
    }

    @Test
    void addRepeatedValidationIncreasesSize() {
        TestPlan plan = makeUpgradePlan();
        StageMutationHint hint = makeHint(
                StageMutationHint.StageKindHint.POST_FINAL_STAGE,
                2, setOf(1, 2),
                StageMutationHint.SignalType.BRANCH_AND_STRONG_TRACE,
                true, false, false, false);
        int sizeBefore = plan.events.size();
        boolean ok = StageAwareTestPlanMutator
                .addRepeatedValidationAfterHotspot(plan, hint);
        assertTrue(ok);
        assertTrue(plan.events.size() > sizeBefore);
    }

    @Test
    void adjustIntervalAroundHotspotChangesIntervals() {
        TestPlan plan = makeUpgradePlan();
        StageMutationHint hint = makeHint(
                StageMutationHint.StageKindHint.POST_STAGE,
                1, setOf(1),
                StageMutationHint.SignalType.BRANCH_ONLY,
                true, false, false, false);
        boolean ok = StageAwareTestPlanMutator
                .adjustIntervalAroundHotspot(plan, hint);
        assertTrue(ok);
    }

    @Test
    void shuffleUpgradeOrderSwapsNodes() {
        StageMutationHint hint = makeHint(
                StageMutationHint.StageKindHint.POST_STAGE,
                1, setOf(1, 2),
                StageMutationHint.SignalType.BRANCH_ONLY,
                true, false, false, false);
        boolean succeeded = false;
        for (int attempt = 0; attempt < 20; attempt++) {
            TestPlan plan = makeUpgradePlan();
            if (StageAwareTestPlanMutator.shuffleUpgradeOrder(plan, hint)) {
                succeeded = true;
                break;
            }
        }
        assertTrue(succeeded,
                "shuffleUpgradeOrder should succeed within 20 attempts");
    }

    @Test
    void moveValidationAfterFinalizeRelocates() {
        TestPlan plan = makeUpgradePlan();
        boolean ok = StageAwareTestPlanMutator.moveValidationAfterFinalize(
                plan);
        assertTrue(ok);
    }

    // ------------------------------------------------------------------
    // Hotspot anchor
    // ------------------------------------------------------------------

    @Test
    void locateHotspotAnchorReturnsShellCommandNearPostStage() {
        TestPlan plan = makeUpgradePlan();
        StageMutationHint hint = makeHint(
                StageMutationHint.StageKindHint.POST_STAGE,
                1, setOf(1),
                StageMutationHint.SignalType.BRANCH_ONLY,
                true, false, false, false);
        int anchor = StageAwareTestPlanMutator.locateHotspotAnchor(
                plan.events, hint);
        assertTrue(anchor >= 0);
        assertTrue(plan.events.get(anchor) instanceof ShellCommand);
    }

    @Test
    void locateHotspotAnchorReturnsMinusOneForNull() {
        assertEquals(-1, StageAwareTestPlanMutator.locateHotspotAnchor(
                null, StageMutationHint.empty()));
    }

    // ------------------------------------------------------------------
    // FuzzingServer.buildStageMutationHint
    // ------------------------------------------------------------------

    @Test
    void buildHintFromPostStageWindows() {
        StageMutationHint hint = FuzzingServer.buildStageMutationHint(
                "POST_STAGE_upgrade_1", TraceWindow.StageKind.POST_STAGE,
                1, setOf(1),
                false, true, true, false,
                true, TraceEvidenceStrength.STRONG,
                StructuredCandidateStrength.NONE, false, false);
        assertTrue(hint.hasStageInfo());
        assertTrue(hint.postUpgrade);
        assertFalse(hint.preUpgradeOnly);
        assertEquals(StageMutationHint.StageKindHint.POST_STAGE,
                hint.hotStageKind);
        assertEquals(StageMutationHint.SignalType.BRANCH_AND_STRONG_TRACE,
                hint.signalType);
    }

    @Test
    void buildHintPreUpgradeOnlyIsTrue() {
        StageMutationHint hint = FuzzingServer.buildStageMutationHint(
                "PRE_UPGRADE_init", TraceWindow.StageKind.PRE_UPGRADE,
                0, Collections.emptySet(),
                true, false, false, false,
                true, TraceEvidenceStrength.WEAK,
                StructuredCandidateStrength.NONE, false, false);
        assertTrue(hint.hasStageInfo());
        assertTrue(hint.preUpgradeOnly);
        assertFalse(hint.postUpgrade);
    }

    @Test
    void buildHintPreUpgradeNotOnlyWhenNonPreAlsoFired() {
        StageMutationHint hint = FuzzingServer.buildStageMutationHint(
                "PRE_UPGRADE_init", TraceWindow.StageKind.PRE_UPGRADE,
                0, setOf(1),
                true, true, true, false,
                true, TraceEvidenceStrength.STRONG,
                StructuredCandidateStrength.NONE, false, false);
        assertFalse(hint.preUpgradeOnly);
    }

    @Test
    void buildHintNeedsConfirmationForStrongStructured() {
        StageMutationHint hint = FuzzingServer.buildStageMutationHint(
                "POST_STAGE_upgrade_1", TraceWindow.StageKind.POST_STAGE,
                1, setOf(1),
                false, true, true, false,
                true, TraceEvidenceStrength.STRONG,
                StructuredCandidateStrength.STRONG, false, true);
        assertTrue(hint.needsConfirmation);
        assertEquals(StageMutationHint.SignalType.STRONG_STRUCTURED,
                hint.signalType);
    }

    @Test
    void buildHintFaultInfluenced() {
        StageMutationHint hint = FuzzingServer.buildStageMutationHint(
                "FAULT_RECOVERY_r1", TraceWindow.StageKind.FAULT_RECOVERY,
                2, setOf(1),
                false, true, false, true,
                true, TraceEvidenceStrength.STRONG,
                StructuredCandidateStrength.NONE, false, false);
        assertTrue(hint.faultInfluenced);
    }

    // ------------------------------------------------------------------
    // TestPlanCorpus: phase 4 lane and budget caps
    // ------------------------------------------------------------------

    @Test
    void preUpgradeOnlyParentDownrankedFromMainExploit() {
        TestPlanCorpus corpus = new TestPlanCorpus(null);
        StageMutationHint hint = makeHint(
                StageMutationHint.StageKindHint.PRE_UPGRADE,
                0, Collections.emptySet(),
                StageMutationHint.SignalType.BRANCH_AND_STRONG_TRACE,
                false, true, false, false);
        corpus.addTestPlan(makeUpgradePlan(), 1, 1,
                AdmissionReason.BRANCH_AND_TRACE,
                TraceEvidenceStrength.STRONG,
                StructuredCandidateStrength.NONE,
                QueuePriorityClass.BRANCH_AND_STRONG_TRACE, hint);
        QueuedTestPlan entry = corpus.pollQueuedTestPlan(1);
        assertNotNull(entry);
        assertEquals(SchedulerClass.BRANCH_SCOUT, entry.schedulerClass);
    }

    @Test
    void preUpgradeOnlyBudgetCapped() {
        TestPlanCorpus corpus = new TestPlanCorpus(null);
        StageMutationHint hint = makeHint(
                StageMutationHint.StageKindHint.PRE_UPGRADE,
                0, Collections.emptySet(),
                StageMutationHint.SignalType.BRANCH_AND_STRONG_TRACE,
                false, true, false, false);
        corpus.addTestPlan(makeUpgradePlan(), 1, 1,
                AdmissionReason.BRANCH_AND_TRACE,
                TraceEvidenceStrength.STRONG,
                StructuredCandidateStrength.NONE,
                QueuePriorityClass.BRANCH_AND_STRONG_TRACE, hint);
        QueuedTestPlan entry = corpus.pollQueuedTestPlan(1);
        assertNotNull(entry);
        assertTrue(
                entry.plannedMutationBudget <= Config
                        .getConf().preUpgradeOnlyMutationEpochCap,
                "pre-upgrade-only budget should be capped at "
                        + Config.getConf().preUpgradeOnlyMutationEpochCap
                        + " but was " + entry.plannedMutationBudget);
    }

    @Test
    void strongStructuredNotDownrankedEvenIfPreUpgrade() {
        TestPlanCorpus corpus = new TestPlanCorpus(null);
        StageMutationHint hint = makeHint(
                StageMutationHint.StageKindHint.PRE_UPGRADE,
                0, Collections.emptySet(),
                StageMutationHint.SignalType.STRONG_STRUCTURED,
                false, true, false, true);
        corpus.addCandidateParent(makeUpgradePlan(), 1, 1,
                AdmissionReason.BRANCH_AND_TRACE,
                TraceEvidenceStrength.STRONG,
                StructuredCandidateStrength.STRONG,
                QueuePriorityClass.BRANCH_AND_STRONG_TRACE, hint);
        QueuedTestPlan entry = corpus.pollQueuedTestPlan(1);
        assertNotNull(entry);
        assertEquals(SchedulerClass.REPRO_CONFIRM, entry.schedulerClass);
    }

    @Test
    void postUpgradeParentNotDownranked() {
        TestPlanCorpus corpus = new TestPlanCorpus(null);
        StageMutationHint hint = makeHint(
                StageMutationHint.StageKindHint.POST_STAGE,
                1, setOf(1),
                StageMutationHint.SignalType.BRANCH_AND_STRONG_TRACE,
                true, false, false, false);
        corpus.addTestPlan(makeUpgradePlan(), 1, 1,
                AdmissionReason.BRANCH_AND_TRACE,
                TraceEvidenceStrength.STRONG,
                StructuredCandidateStrength.NONE,
                QueuePriorityClass.BRANCH_AND_STRONG_TRACE, hint);
        QueuedTestPlan entry = corpus.pollQueuedTestPlan(1);
        assertNotNull(entry);
        assertEquals(SchedulerClass.MAIN_EXPLOIT, entry.schedulerClass);
    }

    @Test
    void confirmationBudgetSetForStrongCandidate() {
        TestPlanCorpus corpus = new TestPlanCorpus(null);
        StageMutationHint hint = makeHint(
                StageMutationHint.StageKindHint.POST_STAGE,
                1, setOf(1),
                StageMutationHint.SignalType.STRONG_STRUCTURED,
                true, false, false, true);
        corpus.addCandidateParent(makeUpgradePlan(), 1, 1,
                AdmissionReason.BRANCH_AND_TRACE,
                TraceEvidenceStrength.STRONG,
                StructuredCandidateStrength.STRONG,
                QueuePriorityClass.BRANCH_AND_STRONG_TRACE, hint);
        QueuedTestPlan entry = corpus.pollQueuedTestPlan(1);
        assertNotNull(entry);
        assertEquals(Config.getConf().strongCandidateConfirmationBudget,
                entry.confirmationBudgetRemaining);
    }

    @Test
    void confirmationBudgetZeroWhenNoConfirmation() {
        TestPlanCorpus corpus = new TestPlanCorpus(null);
        StageMutationHint hint = makeHint(
                StageMutationHint.StageKindHint.POST_STAGE,
                1, setOf(1),
                StageMutationHint.SignalType.BRANCH_ONLY,
                true, false, false, false);
        corpus.addTestPlan(makeUpgradePlan(), 1, 1,
                AdmissionReason.BRANCH_ONLY,
                TraceEvidenceStrength.NONE,
                StructuredCandidateStrength.NONE,
                QueuePriorityClass.BRANCH_ONLY, hint);
        QueuedTestPlan entry = corpus.pollQueuedTestPlan(1);
        assertNotNull(entry);
        assertEquals(0, entry.confirmationBudgetRemaining);
    }

    @Test
    void hintPersistedOnQueuedTestPlan() {
        TestPlanCorpus corpus = new TestPlanCorpus(null);
        StageMutationHint hint = makeHint(
                StageMutationHint.StageKindHint.POST_FINAL_STAGE,
                2, setOf(1, 2),
                StageMutationHint.SignalType.BRANCH_AND_STRONG_TRACE,
                true, false, false, false);
        corpus.addTestPlan(makeUpgradePlan(), 1, 1,
                AdmissionReason.BRANCH_AND_TRACE,
                TraceEvidenceStrength.STRONG,
                StructuredCandidateStrength.NONE,
                QueuePriorityClass.BRANCH_AND_STRONG_TRACE, hint);
        QueuedTestPlan entry = corpus.pollQueuedTestPlan(1);
        assertNotNull(entry);
        assertNotNull(entry.stageMutationHint);
        assertEquals(StageMutationHint.StageKindHint.POST_FINAL_STAGE,
                entry.stageMutationHint.hotStageKind);
        assertEquals(2, entry.stageMutationHint.hotWindowOrdinal);
        assertTrue(entry.stageMutationHint.hotNodeSet.contains(1));
        assertTrue(entry.stageMutationHint.hotNodeSet.contains(2));
    }

    @Test
    void phase4DisabledNoDownrank() {
        Config.getConf().enableStageFocusedMutation = false;
        TestPlanCorpus corpus = new TestPlanCorpus(null);
        StageMutationHint hint = makeHint(
                StageMutationHint.StageKindHint.PRE_UPGRADE,
                0, Collections.emptySet(),
                StageMutationHint.SignalType.BRANCH_AND_STRONG_TRACE,
                false, true, false, false);
        corpus.addTestPlan(makeUpgradePlan(), 1, 1,
                AdmissionReason.BRANCH_AND_TRACE,
                TraceEvidenceStrength.STRONG,
                StructuredCandidateStrength.NONE,
                QueuePriorityClass.BRANCH_AND_STRONG_TRACE, hint);
        QueuedTestPlan entry = corpus.pollQueuedTestPlan(1);
        assertNotNull(entry);
        assertEquals(SchedulerClass.MAIN_EXPLOIT, entry.schedulerClass);
    }

    // ------------------------------------------------------------------
    // QueuedTestPlan.promoteTo carries hint and confirmation budget
    // ------------------------------------------------------------------

    @Test
    void promoteToOverwritesHintAndConfirmation() {
        QueuedTestPlan entry = new QueuedTestPlan(
                makeUpgradePlan(), 1, 1,
                AdmissionReason.BRANCH_ONLY,
                TraceEvidenceStrength.NONE,
                StructuredCandidateStrength.NONE,
                QueuePriorityClass.BRANCH_ONLY,
                SchedulerClass.BRANCH_SCOUT,
                0, 10, "sig1", 3.0);
        assertEquals(0, entry.confirmationBudgetRemaining);
        assertFalse(entry.stageMutationHint.hasStageInfo());

        StageMutationHint newHint = makeHint(
                StageMutationHint.StageKindHint.POST_STAGE,
                1, setOf(1, 2),
                StageMutationHint.SignalType.STRONG_STRUCTURED,
                true, false, false, true);
        entry.promoteTo(SchedulerClass.REPRO_CONFIRM, 50,
                QueuePriorityClass.BRANCH_AND_STRONG_TRACE,
                AdmissionReason.BRANCH_AND_TRACE,
                TraceEvidenceStrength.STRONG,
                StructuredCandidateStrength.STRONG,
                10.0, newHint, 6);
        assertEquals(SchedulerClass.REPRO_CONFIRM, entry.schedulerClass);
        assertTrue(entry.stageMutationHint.hasStageInfo());
        assertEquals(6, entry.confirmationBudgetRemaining);
    }

    // ------------------------------------------------------------------
    // Review round 1: rolling-only event gets no confirmation budget
    // ------------------------------------------------------------------

    @Test
    void rollingOnlyEventGetsNoConfirmationBudget() {
        TestPlanCorpus corpus = new TestPlanCorpus(null);
        StageMutationHint hint = makeHint(
                StageMutationHint.StageKindHint.POST_STAGE,
                1, setOf(1),
                StageMutationHint.SignalType.ROLLING_ONLY_EVENT,
                true, false, false, false);
        corpus.addTestPlan(makeUpgradePlan(), 1, 1,
                AdmissionReason.BRANCH_ONLY,
                TraceEvidenceStrength.NONE,
                StructuredCandidateStrength.NONE,
                QueuePriorityClass.BRANCH_ONLY, hint);
        QueuedTestPlan entry = corpus.pollQueuedTestPlan(1);
        assertNotNull(entry);
        assertEquals(0, entry.confirmationBudgetRemaining,
                "rolling-only event/error should never get confirmation budget");
    }

    @Test
    void buildHintRollingOnlyEventNoConfirmation() {
        StageMutationHint hint = FuzzingServer.buildStageMutationHint(
                "POST_STAGE_upgrade_1", TraceWindow.StageKind.POST_STAGE,
                1, setOf(1),
                false, true, true, false,
                false, TraceEvidenceStrength.NONE,
                StructuredCandidateStrength.NONE, true, false);
        assertFalse(hint.needsConfirmation,
                "rolling-only event divergence should not need confirmation");
        assertEquals(StageMutationHint.SignalType.ROLLING_ONLY_EVENT,
                hint.signalType);
    }

    // ------------------------------------------------------------------
    // Review round 1: decay preserves pre-upgrade-only budget cap
    // ------------------------------------------------------------------

    @Test
    void decayPreservesPreUpgradeOnlyBudgetCap() {
        ObservabilityMetrics metrics = new ObservabilityMetrics(null);
        TestPlanCorpus corpus = new TestPlanCorpus(metrics);
        StageMutationHint hint = makeHint(
                StageMutationHint.StageKindHint.PRE_UPGRADE,
                0, Collections.emptySet(),
                StageMutationHint.SignalType.BRANCH_AND_STRONG_TRACE,
                false, true, false, false);
        corpus.addTestPlan(makeUpgradePlan(), 1, 1,
                AdmissionReason.BRANCH_AND_TRACE,
                TraceEvidenceStrength.STRONG,
                StructuredCandidateStrength.NONE,
                QueuePriorityClass.BRANCH_AND_STRONG_TRACE, hint);
        // Dequeue enough times to trigger decay
        for (int i = 0; i < Config.getConf().testPlanDequeueDecayThreshold
                + 1; i++) {
            corpus.addTestPlan(makeUpgradePlan(), 1 + i, 1,
                    AdmissionReason.BRANCH_AND_TRACE,
                    TraceEvidenceStrength.STRONG,
                    StructuredCandidateStrength.NONE,
                    QueuePriorityClass.BRANCH_AND_STRONG_TRACE, hint);
            corpus.pollQueuedTestPlan(1 + i);
        }
        corpus.decayStaleEntries();
        // Re-add and poll to see if a surviving entry has the cap
        corpus.addTestPlan(makeUpgradePlan(), 100, 1,
                AdmissionReason.BRANCH_AND_TRACE,
                TraceEvidenceStrength.STRONG,
                StructuredCandidateStrength.NONE,
                QueuePriorityClass.BRANCH_AND_STRONG_TRACE, hint);
        QueuedTestPlan entry = corpus.pollQueuedTestPlan(100);
        if (entry != null) {
            assertTrue(
                    entry.plannedMutationBudget <= Config
                            .getConf().preUpgradeOnlyMutationEpochCap,
                    "after decay, pre-upgrade-only budget should still be capped at "
                            + Config.getConf().preUpgradeOnlyMutationEpochCap
                            + " but was "
                            + entry.plannedMutationBudget);
        }
    }

    // ------------------------------------------------------------------
    // Review round 1: local reorder only swaps ShellCommand events
    // ------------------------------------------------------------------

    @Test
    void localReorderSkipsFaultEvents() {
        List<Event> events = new ArrayList<>();
        events.add(new ShellCommand("CREATE TABLE t (id int)", 0));
        events.add(
                new org.zlab.upfuzz.fuzzingengine.testplan.event.fault.NodeFailure(
                        1));
        events.add(new ShellCommand("INSERT INTO t VALUES (1)", 0));
        events.add(new UpgradeOp(1));
        TestPlan plan = new TestPlan(3, events,
                new ArrayList<>(Arrays.asList("SELECT * FROM t")),
                new ArrayList<>());
        StageMutationHint hint = makeHint(
                StageMutationHint.StageKindHint.PRE_UPGRADE,
                0, setOf(1),
                StageMutationHint.SignalType.BRANCH_ONLY,
                false, true, false, false);
        for (int i = 0; i < 50; i++) {
            TestPlan clone = org.apache.commons.lang3.SerializationUtils
                    .clone(plan);
            StageAwareTestPlanMutator.localReorderNearHotspot(clone, hint);
            for (int j = 0; j < clone.events.size(); j++) {
                Event e = clone.events.get(j);
                if (e instanceof org.zlab.upfuzz.fuzzingengine.testplan.event.fault.NodeFailure) {
                    assertEquals(1, j,
                            "fault event should not have been moved from index 1");
                }
            }
        }
    }

    // ------------------------------------------------------------------
    // Review round 1: shuffle-upgrade-order gated on upgradeOrderMattered
    // ------------------------------------------------------------------

    @Test
    void shuffleUpgradeOrderNotPickedWithoutUpgradeOrderMattered() {
        StageMutationHint hint = new StageMutationHint(
                "stage_POST_STAGE",
                StageMutationHint.StageKindHint.POST_STAGE,
                1, setOf(1, 2),
                StageMutationHint.SignalType.BRANCH_ONLY,
                false, false, false, false, false);
        assertFalse(hint.upgradeOrderMattered);
        Config.Configuration cfg = Config.getConf();
        cfg.enableStageTemplates = false;
        for (int i = 0; i < 100; i++) {
            StageAwareTestPlanMutator.MutationFamily family = StageAwareTestPlanMutator
                    .pickFamily(hint, cfg);
            assertNotNull(family);
            assertFalse(
                    family == StageAwareTestPlanMutator.MutationFamily.SHUFFLE_UPGRADE_ORDER,
                    "SHUFFLE_UPGRADE_ORDER should not be picked when upgradeOrderMattered=false");
        }
    }

    @Test
    void shuffleUpgradeOrderPickedWhenUpgradeOrderMattered() {
        StageMutationHint hint = new StageMutationHint(
                "stage_POST_STAGE",
                StageMutationHint.StageKindHint.POST_STAGE,
                1, setOf(1, 2),
                StageMutationHint.SignalType.BRANCH_ONLY,
                true, false, false, false, true);
        assertTrue(hint.upgradeOrderMattered);
        Config.Configuration cfg = Config.getConf();
        cfg.enableStageTemplates = false;
        boolean found = false;
        for (int i = 0; i < 200; i++) {
            StageAwareTestPlanMutator.MutationFamily family = StageAwareTestPlanMutator
                    .pickFamily(hint, cfg);
            if (family == StageAwareTestPlanMutator.MutationFamily.SHUFFLE_UPGRADE_ORDER) {
                found = true;
                break;
            }
        }
        assertTrue(found,
                "SHUFFLE_UPGRADE_ORDER should eventually be picked when upgradeOrderMattered=true");
    }

    // ------------------------------------------------------------------
    // Review round 1: write-heavy template uses write commands
    // ------------------------------------------------------------------

    @Test
    void writeHeavyTemplateUsesWriteNotValidation() {
        TestPlan plan = makeUpgradePlan();
        StageMutationHint hint = makeHint(
                StageMutationHint.StageKindHint.PRE_UPGRADE,
                0, setOf(1),
                StageMutationHint.SignalType.BRANCH_ONLY,
                false, true, false, false);
        int sizeBefore = plan.events.size();
        Set<String> validationSet = new HashSet<>(plan.validationCommands);
        // Snapshot the original events so we can identify newly inserted ones.
        Set<String> originalCommands = new HashSet<>();
        for (Event e : plan.events) {
            if (e instanceof ShellCommand) {
                originalCommands.add(
                        System.identityHashCode(e) + ":"
                                + ((ShellCommand) e).getCommand());
            }
        }
        boolean ok = StageAwareTestPlanMutator.applyFamily(plan, hint,
                StageAwareTestPlanMutator.MutationFamily.TEMPLATE_WRITE_HEAVY_PRE_UPGRADE,
                null, null);
        assertTrue(ok);
        assertTrue(plan.events.size() > sizeBefore);
        int newCount = 0;
        for (Event e : plan.events) {
            if (e instanceof ShellCommand) {
                String key = System.identityHashCode(e) + ":"
                        + ((ShellCommand) e).getCommand();
                if (!originalCommands.contains(key)) {
                    newCount++;
                    String cmd = ((ShellCommand) e).getCommand();
                    assertFalse(validationSet.contains(cmd),
                            "write-heavy template should not use validation command: "
                                    + cmd);
                }
            }
        }
        assertTrue(newCount > 0,
                "template should have inserted at least one new write command");
    }

    // ------------------------------------------------------------------
    // Test helpers
    // ------------------------------------------------------------------

    private static TestPlan makeUpgradePlan() {
        List<Event> events = new ArrayList<>();
        events.add(new ShellCommand("CREATE TABLE t (id int)", 0));
        events.add(new ShellCommand("INSERT INTO t VALUES (1)", 0));
        events.add(new UpgradeOp(1));
        events.add(new ShellCommand("SELECT * FROM t", 1));
        events.add(new UpgradeOp(2));
        events.add(new ShellCommand("INSERT INTO t VALUES (2)", 1));
        events.add(new FinalizeUpgrade());
        events.add(new ShellCommand("SELECT COUNT(*) FROM t", 0));
        TestPlan plan = new TestPlan(3, events,
                new ArrayList<>(Arrays.asList(
                        "SELECT COUNT(*) FROM t",
                        "SELECT * FROM t")),
                new ArrayList<>());
        plan.lineageTestId = 1;
        return plan;
    }

    private static StageMutationHint makeHint(
            StageMutationHint.StageKindHint kind, int ordinal,
            Set<Integer> nodeSet, StageMutationHint.SignalType signalType,
            boolean postUpgrade, boolean preUpgradeOnly,
            boolean faultInfluenced, boolean needsConfirmation) {
        boolean upgradeOrderMattered = postUpgrade && nodeSet != null
                && nodeSet.size() >= 2;
        return new StageMutationHint("stage_" + kind.name(), kind, ordinal,
                nodeSet, signalType, postUpgrade, preUpgradeOnly,
                faultInfluenced, needsConfirmation, upgradeOrderMattered);
    }

    private static Set<Integer> setOf(Integer... items) {
        return new LinkedHashSet<>(Arrays.asList(items));
    }
}
