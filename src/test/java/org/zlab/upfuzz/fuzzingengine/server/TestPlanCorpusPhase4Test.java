package org.zlab.upfuzz.fuzzingengine.server;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.zlab.upfuzz.fuzzingengine.Config;
import org.zlab.upfuzz.fuzzingengine.server.observability.AdmissionReason;
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
 * Phase 4 regression tests for the scheduler-level branch-backbone
 * controls, weak-candidate quarantine, and trace-backed routing
 * guarantees added on top of Phase 3.
 *
 * <p>The Phase 3 test file covers the stratified queue, weighted
 * round-robin, dedup, and decay. This file locks in the Phase 4
 * additions so later tuning cannot silently regress them:
 *
 * <ul>
 *   <li>trace-backed routing: strong branch+trace admissions reach
 *       MAIN_EXPLOIT, repeatable weak-trace admissions reach
 *       SHADOW_EVAL (via BRANCH_AND_WEAK_TRACE /
 *       TRACE_ONLY_WEAK priority classes).</li>
 *   <li>branch-backbone payoff reweight: a queued plan gets an
 *       additional score bump when a downstream branch payoff is
 *       credited to its lineage, visible on the scheduler metrics.</li>
 *   <li>per-lane accelerated decay: the SHADOW_EVAL / BRANCH_SCOUT
 *       lanes decay faster than the global threshold when the
 *       branch-backbone knobs are set.</li>
 *   <li>weak-candidate quarantine: repeated SHADOW_EVAL decays of the
 *       same lineage trigger a quarantine cooldown that rejects
 *       further non-candidate admissions for the configured window;
 *       strong structured candidate parents bypass the cooldown.</li>
 *   <li>trace-disabled coherence: the same branch-backbone knob set
 *       still works when trace admission never fires, so Phase 6 can
 *       hold branch routing constant across A (trace-on) and B
 *       (trace-off) arms.</li>
 * </ul>
 */
class TestPlanCorpusPhase4Test {

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
        cfg.testPlanDequeueDecayThreshold = 5;
        cfg.enableBranchBackboneControls = true;
        cfg.branchBackbonePayoffBonus = 1.0;
        cfg.branchScoutDecayThreshold = 3;
        cfg.shadowEvalDecayThreshold = 2;
        cfg.mainExploitDecayThreshold = 0;
        cfg.reproConfirmDecayThreshold = 0;
        cfg.weakCandidateQuarantineDecayEvents = 2;
        cfg.weakCandidateQuarantineRounds = 10;
        cfg.enableObservabilityArtifacts = true;
        Config.setInstance(cfg);
    }

    // ------------------------------------------------------------------
    // Trace-backed routing
    // ------------------------------------------------------------------

    @Test
    void branchAndStrongTraceReachesMainExploit() {
        TestPlanCorpus corpus = new TestPlanCorpus(
                new ObservabilityMetrics(null));
        TestPlan plan = makeUniquePlan(100, "strong-mix");
        corpus.addTestPlan(plan, 0, 100,
                AdmissionReason.BRANCH_AND_TRACE,
                TraceEvidenceStrength.STRONG,
                StructuredCandidateStrength.NONE,
                QueuePriorityClass.BRANCH_AND_STRONG_TRACE);
        QueuedTestPlan entry = corpus.pollQueuedTestPlan(1);
        assertNotNull(entry);
        assertEquals(SchedulerClass.MAIN_EXPLOIT, entry.schedulerClass,
                "BRANCH_AND_STRONG_TRACE routes to MAIN_EXPLOIT");
        assertEquals(30, entry.plannedMutationBudget);
    }

    @Test
    void branchAndWeakTraceRoutesToShadowEval() {
        TestPlanCorpus corpus = new TestPlanCorpus(
                new ObservabilityMetrics(null));
        TestPlan plan = makeUniquePlan(101, "weak-mix");
        corpus.addTestPlan(plan, 0, 101,
                AdmissionReason.BRANCH_AND_TRACE,
                TraceEvidenceStrength.UNSUPPORTED_BUT_REPEATABLE,
                StructuredCandidateStrength.NONE,
                QueuePriorityClass.BRANCH_AND_WEAK_TRACE);
        QueuedTestPlan entry = corpus.pollQueuedTestPlan(1);
        assertNotNull(entry);
        assertEquals(SchedulerClass.SHADOW_EVAL, entry.schedulerClass,
                "repeatable but lower-confidence trace routes to SHADOW_EVAL");
        assertEquals(4, entry.plannedMutationBudget);
    }

    @Test
    void traceOnlyStrongStaysGatedInShadowEval() {
        // Phase 4 rule: TRACE_ONLY_STRONG is "gated and rare, not
        // default" — it does not auto-promote to MAIN_EXPLOIT.
        TestPlanCorpus corpus = new TestPlanCorpus(
                new ObservabilityMetrics(null));
        TestPlan plan = makeUniquePlan(102, "trace-only-strong");
        corpus.addTestPlan(plan, 0, 102,
                AdmissionReason.TRACE_ONLY_TRIDIFF_EXCLUSIVE,
                TraceEvidenceStrength.STRONG,
                StructuredCandidateStrength.NONE,
                QueuePriorityClass.TRACE_ONLY_STRONG);
        QueuedTestPlan entry = corpus.pollQueuedTestPlan(1);
        assertNotNull(entry);
        assertEquals(SchedulerClass.SHADOW_EVAL, entry.schedulerClass,
                "trace-only strong stays in SHADOW_EVAL by default");
    }

    @Test
    void branchOnlyStillRoutesToBranchScout() {
        // Rule: the same branch-backbone policy must behave coherently
        // with trace enablement off — branch-only admissions continue
        // to reach BRANCH_SCOUT regardless of the new knobs.
        TestPlanCorpus corpus = new TestPlanCorpus(
                new ObservabilityMetrics(null));
        TestPlan plan = makeUniquePlan(103, "branch-only");
        corpus.addTestPlan(plan, 0, 103,
                AdmissionReason.BRANCH_ONLY,
                TraceEvidenceStrength.NONE,
                StructuredCandidateStrength.NONE,
                QueuePriorityClass.BRANCH_ONLY);
        QueuedTestPlan entry = corpus.pollQueuedTestPlan(1);
        assertNotNull(entry);
        assertEquals(SchedulerClass.BRANCH_SCOUT, entry.schedulerClass);
        assertEquals(10, entry.plannedMutationBudget);
    }

    // ------------------------------------------------------------------
    // Branch-backbone payoff reweight
    // ------------------------------------------------------------------

    @Test
    void branchPayoffAppliesReweightBonusOnBranchScout() {
        ObservabilityMetrics metrics = new ObservabilityMetrics(null);
        TestPlanCorpus corpus = new TestPlanCorpus(metrics);

        TestPlan plan = makeUniquePlan(200, "bb-reweight");
        corpus.addTestPlan(plan, 0, 200,
                AdmissionReason.BRANCH_ONLY,
                TraceEvidenceStrength.NONE,
                StructuredCandidateStrength.NONE,
                QueuePriorityClass.BRANCH_ONLY);

        // The queued entry sits in BRANCH_SCOUT; a downstream branch
        // payoff should trigger both the base score bump and the
        // branch-backbone reweight bonus.
        corpus.notifyBranchPayoff(200);

        assertEquals(1,
                metrics.getSchedulerBranchPayoff(SchedulerClass.BRANCH_SCOUT));
        assertEquals(1,
                metrics.getSchedulerBranchBackboneReweights(
                        SchedulerClass.BRANCH_SCOUT));
    }

    @Test
    void branchPayoffReweightAppliesOnMainExploit() {
        ObservabilityMetrics metrics = new ObservabilityMetrics(null);
        TestPlanCorpus corpus = new TestPlanCorpus(metrics);
        TestPlan plan = makeUniquePlan(201, "bb-reweight-main");
        corpus.addTestPlan(plan, 0, 201,
                AdmissionReason.BRANCH_AND_TRACE,
                TraceEvidenceStrength.STRONG,
                StructuredCandidateStrength.NONE,
                QueuePriorityClass.BRANCH_AND_STRONG_TRACE);
        corpus.notifyBranchPayoff(201);
        assertEquals(1, metrics.getSchedulerBranchBackboneReweights(
                SchedulerClass.MAIN_EXPLOIT));
    }

    @Test
    void branchPayoffReweightDisabledWhenBackboneOff() {
        Config.getConf().enableBranchBackboneControls = false;
        ObservabilityMetrics metrics = new ObservabilityMetrics(null);
        TestPlanCorpus corpus = new TestPlanCorpus(metrics);
        TestPlan plan = makeUniquePlan(202, "bb-off");
        corpus.addTestPlan(plan, 0, 202,
                AdmissionReason.BRANCH_ONLY,
                TraceEvidenceStrength.NONE,
                StructuredCandidateStrength.NONE,
                QueuePriorityClass.BRANCH_ONLY);
        corpus.notifyBranchPayoff(202);
        assertEquals(1,
                metrics.getSchedulerBranchPayoff(SchedulerClass.BRANCH_SCOUT));
        assertEquals(0, metrics.getSchedulerBranchBackboneReweights(
                SchedulerClass.BRANCH_SCOUT),
                "reweight counter must stay 0 when backbone controls are off");
    }

    // ------------------------------------------------------------------
    // Per-lane accelerated decay
    // ------------------------------------------------------------------

    @Test
    void shadowEvalDecaysBeforeGlobalThreshold() {
        // Global threshold is 5; SHADOW_EVAL threshold is 2 so a
        // shadow plan with 2 dequeues of the same lineage root and no
        // payoff should be dropped before a comparable main-exploit
        // plan would ever decay.
        ObservabilityMetrics metrics = new ObservabilityMetrics(null);
        TestPlanCorpus corpus = new TestPlanCorpus(metrics);

        for (int i = 0; i < 3; i++) {
            TestPlan plan = makeUniquePlan(300 + i, "shadow-" + i);
            corpus.addTestPlan(plan, i, 300,
                    AdmissionReason.TRACE_ONLY_WINDOW_SIM,
                    TraceEvidenceStrength.WEAK,
                    StructuredCandidateStrength.NONE,
                    QueuePriorityClass.TRACE_ONLY_WEAK);
            assertNotNull(corpus.pollQueuedTestPlan(10 + i));
        }
        TestPlan survivor = makeUniquePlan(400, "shadow-survivor");
        corpus.addTestPlan(survivor, 5, 300,
                AdmissionReason.TRACE_ONLY_WINDOW_SIM,
                TraceEvidenceStrength.WEAK,
                StructuredCandidateStrength.NONE,
                QueuePriorityClass.TRACE_ONLY_WEAK);
        corpus.decayStaleEntries();

        // SHADOW_EVAL has no demotion target — accelerated decay drops
        // the entry outright.
        assertNull(corpus.pollQueuedTestPlan(20),
                "accelerated SHADOW_EVAL decay should have removed the survivor");
        assertTrue(metrics.getSchedulerDecayDemotions(
                SchedulerClass.SHADOW_EVAL) >= 1);
    }

    @Test
    void branchScoutDecaysBeforeGlobalThresholdWhenConfigured() {
        // Global threshold is 5; BRANCH_SCOUT threshold is 3 so a
        // BRANCH_SCOUT plan with only 3 dequeues of the same lineage
        // should demote to SHADOW_EVAL, even though the global
        // threshold has not been reached.
        ObservabilityMetrics metrics = new ObservabilityMetrics(null);
        TestPlanCorpus corpus = new TestPlanCorpus(metrics);
        Config.getConf().branchScoutMinOccupancy = 0;

        for (int i = 0; i < 4; i++) {
            TestPlan plan = makeUniquePlan(500 + i, "bs-" + i);
            corpus.addTestPlan(plan, i, 500,
                    AdmissionReason.BRANCH_ONLY,
                    TraceEvidenceStrength.NONE,
                    StructuredCandidateStrength.NONE,
                    QueuePriorityClass.BRANCH_ONLY);
            assertNotNull(corpus.pollQueuedTestPlan(20 + i));
        }
        TestPlan survivor = makeUniquePlan(600, "bs-survivor");
        corpus.addTestPlan(survivor, 5, 500,
                AdmissionReason.BRANCH_ONLY,
                TraceEvidenceStrength.NONE,
                StructuredCandidateStrength.NONE,
                QueuePriorityClass.BRANCH_ONLY);
        corpus.decayStaleEntries();

        QueuedTestPlan dequeued = corpus.pollQueuedTestPlan(30);
        assertNotNull(dequeued);
        assertEquals(SchedulerClass.SHADOW_EVAL, dequeued.schedulerClass,
                "branch-scout should decay to shadow-eval at the lane threshold");
        assertTrue(metrics.getSchedulerDecayDemotions(
                SchedulerClass.BRANCH_SCOUT) >= 1);
    }

    // ------------------------------------------------------------------
    // Weak-candidate quarantine
    // ------------------------------------------------------------------

    @Test
    void quarantineRejectsAdmissionAfterTwoShadowDecays() {
        ObservabilityMetrics metrics = new ObservabilityMetrics(null);
        TestPlanCorpus corpus = new TestPlanCorpus(metrics);

        // Phase 4 quarantine requires two SHADOW_EVAL drop events on
        // the same lineage. Each drop event happens when a queued
        // SHADOW_EVAL plan is decayed out of the queue (its
        // lineageRoot already has enough dequeues on the clock). The
        // test warms the lineage's dequeue counter, then admits two
        // more SHADOW_EVAL survivors and decays them back to back.
        int lineageRoot = 700;

        // Warm the lineage: two admit→dequeue cycles push
        // dequeuesPerLineage[700]=2 (matches the shadow threshold).
        for (int warm = 0; warm < 2; warm++) {
            TestPlan plan = makeUniquePlan(700 + warm,
                    "warm-" + warm);
            corpus.addTestPlan(plan, warm, lineageRoot,
                    AdmissionReason.TRACE_ONLY_WINDOW_SIM,
                    TraceEvidenceStrength.WEAK,
                    StructuredCandidateStrength.NONE,
                    QueuePriorityClass.TRACE_ONLY_WEAK);
            assertNotNull(corpus.pollQueuedTestPlan(warm));
        }

        // First survivor — decayStaleEntries drops it, first shadow
        // decay event recorded on the lineage.
        TestPlan survivor1 = makeUniquePlan(710, "decay-1");
        corpus.addTestPlan(survivor1, 2, lineageRoot,
                AdmissionReason.TRACE_ONLY_WINDOW_SIM,
                TraceEvidenceStrength.WEAK,
                StructuredCandidateStrength.NONE,
                QueuePriorityClass.TRACE_ONLY_WEAK);
        corpus.decayStaleEntries();
        assertEquals(0, corpus.size(),
                "first survivor should have been decayed out of SHADOW_EVAL");

        // Second survivor — another decay event fires and crosses the
        // quarantine threshold (weakCandidateQuarantineDecayEvents=2).
        TestPlan survivor2 = makeUniquePlan(711, "decay-2");
        corpus.addTestPlan(survivor2, 3, lineageRoot,
                AdmissionReason.TRACE_ONLY_WINDOW_SIM,
                TraceEvidenceStrength.WEAK,
                StructuredCandidateStrength.NONE,
                QueuePriorityClass.TRACE_ONLY_WEAK);
        corpus.decayStaleEntries();

        assertTrue(metrics.getSchedulerQuarantineEvents(
                SchedulerClass.SHADOW_EVAL) >= 1,
                "after repeated shadow decays the lineage must be quarantined");
        assertTrue(corpus.quarantinedLineageCount() >= 1);

        // New admission attempt on the quarantined lineage is
        // rejected. Counter ticks on the target lane.
        TestPlan rejected = makeUniquePlan(800, "quarantined-attempt");
        boolean admitted = corpus.addTestPlan(rejected, 4, lineageRoot,
                AdmissionReason.BRANCH_ONLY,
                TraceEvidenceStrength.NONE,
                StructuredCandidateStrength.NONE,
                QueuePriorityClass.BRANCH_ONLY);
        assertFalse(admitted,
                "quarantine must reject non-candidate admissions");
        assertTrue(metrics.getSchedulerQuarantineRejections(
                SchedulerClass.BRANCH_SCOUT) >= 1);
    }

    @Test
    void quarantineBypassedByCandidateParent() {
        ObservabilityMetrics metrics = new ObservabilityMetrics(null);
        TestPlanCorpus corpus = new TestPlanCorpus(metrics);
        int lineageRoot = 900;

        // Warm + two decay events to drive the lineage into
        // quarantine (same recipe as the rejection test).
        for (int warm = 0; warm < 2; warm++) {
            TestPlan plan = makeUniquePlan(900 + warm,
                    "qbypass-warm-" + warm);
            corpus.addTestPlan(plan, warm, lineageRoot,
                    AdmissionReason.TRACE_ONLY_WINDOW_SIM,
                    TraceEvidenceStrength.WEAK,
                    StructuredCandidateStrength.NONE,
                    QueuePriorityClass.TRACE_ONLY_WEAK);
            assertNotNull(corpus.pollQueuedTestPlan(warm));
        }
        for (int decay = 0; decay < 2; decay++) {
            TestPlan plan = makeUniquePlan(910 + decay,
                    "qbypass-decay-" + decay);
            corpus.addTestPlan(plan, 2 + decay, lineageRoot,
                    AdmissionReason.TRACE_ONLY_WINDOW_SIM,
                    TraceEvidenceStrength.WEAK,
                    StructuredCandidateStrength.NONE,
                    QueuePriorityClass.TRACE_ONLY_WEAK);
            corpus.decayStaleEntries();
        }
        assertTrue(corpus.quarantinedLineageCount() >= 1);

        TestPlan candidate = makeUniquePlan(1000, "qbypass-candidate");
        boolean admitted = corpus.addCandidateParent(candidate, 4,
                lineageRoot,
                AdmissionReason.BRANCH_ONLY,
                TraceEvidenceStrength.NONE,
                StructuredCandidateStrength.STRONG,
                QueuePriorityClass.BRANCH_ONLY);
        assertTrue(admitted,
                "strong candidate parents must bypass quarantine cooldown");
        QueuedTestPlan d = corpus.pollQueuedTestPlan(5);
        assertNotNull(d);
        assertEquals(SchedulerClass.REPRO_CONFIRM, d.schedulerClass);
    }

    @Test
    void weakCandidatePayoffClearsPendingQuarantine() {
        // Regression for review fix 1: notifyWeakCandidatePayoff must
        // also clear the pending shadow-decay counter, same as
        // notifyBranchPayoff / notifyStrongCandidatePayoff. Otherwise
        // a lineage that produced a single weak oracle hit would
        // still keep its quarantine runway and eventually get
        // quarantined despite being productive.
        ObservabilityMetrics metrics = new ObservabilityMetrics(null);
        TestPlanCorpus corpus = new TestPlanCorpus(metrics);
        int lineageRoot = 1500;

        // Trigger ONE shadow decay event — with threshold=2 this is
        // not enough to quarantine by itself.
        TestPlan first = makeUniquePlan(1500, "weak-payoff-1");
        corpus.addTestPlan(first, 0, lineageRoot,
                AdmissionReason.TRACE_ONLY_WINDOW_SIM,
                TraceEvidenceStrength.WEAK,
                StructuredCandidateStrength.NONE,
                QueuePriorityClass.TRACE_ONLY_WEAK);
        for (int i = 0; i < 3; i++) {
            assertNotNull(corpus.pollQueuedTestPlan(1 + i));
            corpus.addTestPlan(
                    makeUniquePlan(1510 + i,
                            "weak-payoff-pump-" + i),
                    2 + i, lineageRoot,
                    AdmissionReason.TRACE_ONLY_WINDOW_SIM,
                    TraceEvidenceStrength.WEAK,
                    StructuredCandidateStrength.NONE,
                    QueuePriorityClass.TRACE_ONLY_WEAK);
        }
        corpus.decayStaleEntries();
        assertTrue(metrics.getSchedulerDecayDemotions(
                SchedulerClass.SHADOW_EVAL) >= 1,
                "one shadow decay event should have fired");
        assertEquals(0, corpus.quarantinedLineageCount(),
                "one decay event must not be enough to quarantine");

        // A weak candidate payoff must cancel the pending decay count
        // for this lineage, so the next decay event cannot push the
        // counter over the quarantine threshold on its own.
        corpus.notifyWeakCandidatePayoff(lineageRoot);

        // Fire a second decay — the accumulator is already cleared,
        // so this counts as a fresh first event rather than the
        // second.
        TestPlan another = makeUniquePlan(1520, "weak-payoff-after");
        corpus.addTestPlan(another, 5, lineageRoot,
                AdmissionReason.TRACE_ONLY_WINDOW_SIM,
                TraceEvidenceStrength.WEAK,
                StructuredCandidateStrength.NONE,
                QueuePriorityClass.TRACE_ONLY_WEAK);
        corpus.decayStaleEntries();
        assertEquals(0, corpus.quarantinedLineageCount(),
                "weak payoff must reset the quarantine runway — no "
                        + "quarantine after the follow-up decay");
    }

    @Test
    void payoffClearsQuarantineAccumulator() {
        ObservabilityMetrics metrics = new ObservabilityMetrics(null);
        TestPlanCorpus corpus = new TestPlanCorpus(metrics);
        int lineageRoot = 1100;

        // Single SHADOW_EVAL drop — not enough alone to quarantine (2
        // events required). A branch payoff must clear the pending
        // count so a subsequent drop does not push into quarantine.
        TestPlan first = makeUniquePlan(1100, "payoff-clears-1");
        corpus.addTestPlan(first, 0, lineageRoot,
                AdmissionReason.TRACE_ONLY_WINDOW_SIM,
                TraceEvidenceStrength.WEAK,
                StructuredCandidateStrength.NONE,
                QueuePriorityClass.TRACE_ONLY_WEAK);
        for (int i = 0; i < 3; i++) {
            assertNotNull(corpus.pollQueuedTestPlan(1 + i));
            corpus.addTestPlan(
                    makeUniquePlan(1110 + i, "payoff-clears-pump-" + i),
                    2 + i, lineageRoot,
                    AdmissionReason.TRACE_ONLY_WINDOW_SIM,
                    TraceEvidenceStrength.WEAK,
                    StructuredCandidateStrength.NONE,
                    QueuePriorityClass.TRACE_ONLY_WEAK);
        }
        corpus.decayStaleEntries();
        // One shadow decay so far.
        long shadowDecaysAfterFirst = metrics
                .getSchedulerDecayDemotions(SchedulerClass.SHADOW_EVAL);
        assertTrue(shadowDecaysAfterFirst >= 1);

        // Payoff clears the shadow-decay tracking for the lineage so
        // a single additional drop never reaches the quarantine
        // threshold.
        corpus.notifyBranchPayoff(lineageRoot);
        assertEquals(0, corpus.quarantinedLineageCount());
    }

    // ------------------------------------------------------------------
    // Trace-disabled coherence — same knobs still work
    // ------------------------------------------------------------------

    @Test
    void branchBackboneCoherentWhenTraceAdmissionsAbsent() {
        // Phase 6 A/B rule: the branch-backbone policy must work
        // identically whether trace admissions fire or not. We
        // simulate a trace-off campaign by only admitting BRANCH_ONLY
        // plans; the quarantine counter should stay zero (no shadow
        // decays to trigger it) while the reweight counter should
        // still activate when branch payoffs arrive.
        ObservabilityMetrics metrics = new ObservabilityMetrics(null);
        TestPlanCorpus corpus = new TestPlanCorpus(metrics);

        TestPlan plan = makeUniquePlan(1200, "trace-off");
        corpus.addTestPlan(plan, 0, 1200,
                AdmissionReason.BRANCH_ONLY,
                TraceEvidenceStrength.NONE,
                StructuredCandidateStrength.NONE,
                QueuePriorityClass.BRANCH_ONLY);
        corpus.notifyBranchPayoff(1200);
        corpus.decayStaleEntries();

        assertEquals(1, metrics.getSchedulerBranchBackboneReweights(
                SchedulerClass.BRANCH_SCOUT));
        assertEquals(0, metrics.getSchedulerQuarantineEvents(
                SchedulerClass.SHADOW_EVAL),
                "no shadow admission → no quarantine event, matches the "
                        + "trace-off coherence requirement");
    }

    // ------------------------------------------------------------------
    // Routing context wired into scheduling policy
    // ------------------------------------------------------------------

    @Test
    void richRoutingContextLiftsInitialScoreWithinLane() {
        // Regression for the reviewer's "wire the routing-context
        // fields into actual scheduling policy" concern. Two
        // admissions land in the same lane with the same
        // TraceEvidenceStrength, but the second carries actionable
        // routing context (boundary-involved flow + order anomaly +
        // FLOW_BACKED support). The dequeue order must prefer the
        // richer admission because of the score bonus applied in
        // TestPlanCorpus.initialScoreFor.
        ObservabilityMetrics metrics = new ObservabilityMetrics(null);
        TestPlanCorpus corpus = new TestPlanCorpus(metrics);

        TestPlan plain = makeUniquePlan(2000, "plain");
        TestPlan rich = makeUniquePlan(2001, "rich");
        StageMutationHint plainHint = StageMutationHint.empty();
        StageMutationHint richHint = new StageMutationHint(
                "POST_STAGE_1",
                StageMutationHint.StageKindHint.POST_STAGE,
                1,
                Collections.singleton(1),
                StageMutationHint.SignalType.BRANCH_AND_STRONG_TRACE,
                true, false, false, false, true,
                null, "CLIENT->REPLICA", "CLIENT->REPLICA", true,
                TraceSupportClass.FLOW_BACKED);

        corpus.addTestPlan(plain, 0, 2000,
                AdmissionReason.BRANCH_AND_TRACE,
                TraceEvidenceStrength.STRONG,
                StructuredCandidateStrength.NONE,
                QueuePriorityClass.BRANCH_AND_STRONG_TRACE, plainHint);
        corpus.addTestPlan(rich, 1, 2001,
                AdmissionReason.BRANCH_AND_TRACE,
                TraceEvidenceStrength.STRONG,
                StructuredCandidateStrength.NONE,
                QueuePriorityClass.BRANCH_AND_STRONG_TRACE, richHint);

        QueuedTestPlan first = corpus.pollQueuedTestPlan(2);
        assertNotNull(first);
        assertSame(rich, first.plan,
                "routing-context bonus must lift the richer admission "
                        + "ahead of the neutral one within the same lane");
    }

    // ------------------------------------------------------------------
    // Seed lifecycle counters
    // ------------------------------------------------------------------

    @Test
    void quarantineEventCreditsSeedLifecycle() {
        ObservabilityMetrics metrics = new ObservabilityMetrics(null);
        TestPlanCorpus corpus = new TestPlanCorpus(metrics);
        int lineageRoot = 1300;
        SeedLifecycle lifecycle = metrics.recordSeedAddition(
                lineageRoot, /* creationRound */ 0,
                AdmissionReason.TRACE_ONLY_WINDOW_SIM, -1);
        assertNotNull(lifecycle);

        // Warm + two decays to drive the lineage into quarantine (same
        // recipe as the rejection test).
        for (int warm = 0; warm < 2; warm++) {
            TestPlan plan = makeUniquePlan(1300 + warm, "warm-" + warm);
            corpus.addTestPlan(plan, warm, lineageRoot,
                    AdmissionReason.TRACE_ONLY_WINDOW_SIM,
                    TraceEvidenceStrength.WEAK,
                    StructuredCandidateStrength.NONE,
                    QueuePriorityClass.TRACE_ONLY_WEAK);
            assertNotNull(corpus.pollQueuedTestPlan(warm));
        }
        for (int decay = 0; decay < 2; decay++) {
            TestPlan plan = makeUniquePlan(1310 + decay,
                    "decay-" + decay);
            corpus.addTestPlan(plan, 2 + decay, lineageRoot,
                    AdmissionReason.TRACE_ONLY_WINDOW_SIM,
                    TraceEvidenceStrength.WEAK,
                    StructuredCandidateStrength.NONE,
                    QueuePriorityClass.TRACE_ONLY_WEAK);
            corpus.decayStaleEntries();
        }
        assertTrue(lifecycle.descendantBranchBackboneQuarantineEvents
                .get() >= 1,
                "quarantine events should credit the lineage's seed lifecycle");
    }

    @Test
    void branchBackboneReweightCreditsSeedLifecycle() {
        ObservabilityMetrics metrics = new ObservabilityMetrics(null);
        TestPlanCorpus corpus = new TestPlanCorpus(metrics);
        int lineageRoot = 1400;
        SeedLifecycle lifecycle = metrics.recordSeedAddition(
                lineageRoot, 0, AdmissionReason.BRANCH_ONLY, -1);

        TestPlan plan = makeUniquePlan(1400, "rw-life");
        corpus.addTestPlan(plan, 0, lineageRoot,
                AdmissionReason.BRANCH_ONLY,
                TraceEvidenceStrength.NONE,
                StructuredCandidateStrength.NONE,
                QueuePriorityClass.BRANCH_ONLY);
        corpus.notifyBranchPayoff(lineageRoot);

        assertNotNull(lifecycle);
        assertEquals(1, lifecycle.descendantBranchBackboneReweightEvents
                .get(),
                "branch-backbone reweight must credit the lineage lifecycle");
    }

    // ------------------------------------------------------------------
    // Server admission-chain end-to-end
    // ------------------------------------------------------------------

    @Test
    void serverAdmissionChainRoutesLowerConfidenceBranchTraceToShadowEval() {
        // Exercises the full admission chain exactly as FuzzingServer
        // runs it in updateStatus: round-level strength →
        // isTraceAdmissible gate (Phase 4, with support class) →
        // classifyAdmissionReason → classifyQueuePriorityClass →
        // TestPlanCorpus.addTestPlan → pollQueuedTestPlan. Input is a
        // WEAK round with FLOW_BACKED support and branch novelty, the
        // Phase-4 case the reviewer called out as unreachable from real
        // execution before this fix.
        Config.getConf().enableLowerConfidenceTraceAdmission = true;

        TraceEvidenceStrength strength = FuzzingServer
                .classifyRoundTraceEvidenceStrength(
                        /* traceInteresting */ true,
                        /* strongFiringWindows */ 0,
                        /* weakFiringWindows */ 1,
                        /* unsupportedButRepeatableFiringWindows */ 0,
                        /* unsupportedFiringWindows */ 0,
                        /* aggregateSimFired */ false);
        assertEquals(TraceEvidenceStrength.WEAK, strength);
        assertTrue(FuzzingServer.isTraceAdmissible(true, strength,
                TraceSupportClass.FLOW_BACKED));
        AdmissionReason reason = FuzzingServer.classifyAdmissionReason(
                /* newBranchCoverage */ true,
                /* traceInteresting */ true,
                /* triDiffExclusiveFired */ true,
                /* windowSimFired */ false,
                /* aggregateSimFired */ false);
        assertEquals(AdmissionReason.BRANCH_AND_TRACE, reason);
        QueuePriorityClass priority = FuzzingServer
                .classifyQueuePriorityClass(reason, strength);
        assertEquals(QueuePriorityClass.BRANCH_AND_WEAK_TRACE, priority);

        ObservabilityMetrics metrics = new ObservabilityMetrics(null);
        TestPlanCorpus corpus = new TestPlanCorpus(metrics);
        TestPlan plan = makeUniquePlan(5000, "e2e-weak-supported");
        corpus.addTestPlan(plan, 0, 5000, reason, strength,
                StructuredCandidateStrength.NONE, priority);
        QueuedTestPlan entry = corpus.pollQueuedTestPlan(1);
        assertNotNull(entry);
        assertEquals(SchedulerClass.SHADOW_EVAL, entry.schedulerClass,
                "supported+weak trace must land in SHADOW_EVAL end-to-end");
        assertEquals(4, entry.plannedMutationBudget,
                "SHADOW_EVAL mutation budget applies");
        assertEquals(1, metrics.getSchedulerEnqueues(
                SchedulerClass.SHADOW_EVAL));
    }

    @Test
    void serverAdmissionChainRoutesUnsupportedButRepeatableToShadowEval() {
        // Same end-to-end chain but for the "repeatable rolling-only
        // upgrade-critical traffic" pattern (the Apr16 HDFS
        // 2.10.2 → 3.3.6 case the Phase 3 scorer labels
        // UNSUPPORTED_BUT_REPEATABLE). Phase 4 now admits this as a
        // trace-only shadow entry instead of rejecting it on the
        // Phase 3 STRONG-only gate.
        Config.getConf().enableLowerConfidenceTraceAdmission = true;

        TraceEvidenceStrength strength = FuzzingServer
                .classifyRoundTraceEvidenceStrength(
                        /* traceInteresting */ true,
                        /* strongFiringWindows */ 0,
                        /* weakFiringWindows */ 0,
                        /* unsupportedButRepeatableFiringWindows */ 1,
                        /* unsupportedFiringWindows */ 0,
                        /* aggregateSimFired */ false);
        assertEquals(TraceEvidenceStrength.UNSUPPORTED_BUT_REPEATABLE,
                strength);
        assertTrue(FuzzingServer.isTraceAdmissible(true, strength,
                TraceSupportClass.UNSUPPORTED));

        AdmissionReason reason = FuzzingServer.classifyAdmissionReason(
                /* newBranchCoverage */ false,
                /* traceInteresting */ true,
                /* triDiffExclusiveFired */ true,
                /* windowSimFired */ false,
                /* aggregateSimFired */ false);
        assertEquals(AdmissionReason.TRACE_ONLY_TRIDIFF_EXCLUSIVE, reason);
        QueuePriorityClass priority = FuzzingServer
                .classifyQueuePriorityClass(reason, strength);
        assertEquals(QueuePriorityClass.TRACE_ONLY_WEAK, priority);

        TestPlanCorpus corpus = new TestPlanCorpus(
                new ObservabilityMetrics(null));
        TestPlan plan = makeUniquePlan(5100, "e2e-unsupported-repeatable");
        corpus.addTestPlan(plan, 0, 5100, reason, strength,
                StructuredCandidateStrength.NONE, priority);
        QueuedTestPlan entry = corpus.pollQueuedTestPlan(1);
        assertNotNull(entry);
        assertEquals(SchedulerClass.SHADOW_EVAL, entry.schedulerClass);
    }

    @Test
    void serverAdmissionChainStillBlocksWeakWithoutFlowSupport() {
        // Symmetric negative case: the same chain must still reject a
        // WEAK round whose support class stays below the FLOW_BACKED
        // floor. Proves the support-class gate in isTraceAdmissible
        // is doing its job.
        Config.getConf().enableLowerConfidenceTraceAdmission = true;

        TraceEvidenceStrength strength = FuzzingServer
                .classifyRoundTraceEvidenceStrength(
                        true, 0, 1, 0, 0, false);
        assertEquals(TraceEvidenceStrength.WEAK, strength);
        assertFalse(FuzzingServer.isTraceAdmissible(true, strength,
                TraceSupportClass.FAMILY_BACKED),
                "family-only support must not clear the Phase 4 floor");
        assertFalse(FuzzingServer.isTraceAdmissible(true, strength,
                TraceSupportClass.BACKGROUND_ONLY));
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
                new ArrayList<>(Arrays.asList(
                        "SELECT COUNT(*) FROM " + tag,
                        "SELECT MAX(id) FROM " + tag)),
                new ArrayList<>());
        plan.lineageTestId = testId;
        return plan;
    }

    @SuppressWarnings("unused")
    private static List<ShellCommand> shells(String... cmds) {
        List<ShellCommand> out = new ArrayList<>();
        for (String c : cmds) {
            out.add(new ShellCommand(c, 0));
        }
        return Collections.unmodifiableList(out);
    }
}
