package org.zlab.upfuzz.fuzzingengine.server;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
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
import org.zlab.upfuzz.fuzzingengine.testplan.event.fault.LinkFailure;
import org.zlab.upfuzz.fuzzingengine.testplan.event.fault.PartitionFailure;
import org.zlab.upfuzz.fuzzingengine.testplan.event.upgradeop.UpgradeOp;

/**
 * Phase 3 regression tests for {@link TestPlanCorpus}.
 *
 * <p>Phase 3 turns the short-term queue into a stratified scheduler
 * with four class-specific sub-queues keyed by the internal
 * {@link SchedulerClass}, strict-priority {@code REPRO_CONFIRM},
 * weighted round-robin selection across the remaining lanes,
 * class-aware mutation budgets, lineage-root-aware compact-signature
 * dedup that includes multi-node fault identity, and decay of plans
 * whose lineage repeatedly dequeues without payoff. These tests lock
 * in that behavior so later tuning rounds cannot silently regress.
 *
 * <p>Review round 1 additions:
 *
 * <ul>
 *   <li>{@code scheduler_class} is the source of truth for the
 *       scheduler lane — observability counters and dequeue accounting
 *       must track it, not {@link QueuePriorityClass}.</li>
 *   <li>Decay must follow the demotion ladder from the plan's current
 *       scheduler lane, never from its admission priority class.</li>
 *   <li>Compact dedup must be lineage-root-aware and must include
 *       {@link LinkFailure#nodeIndex1}/{@code nodeIndex2} and
 *       {@link PartitionFailure} node sets so distinct plans do not
 *       collapse.</li>
 * </ul>
 */
class TestPlanCorpusPhase3Test {

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
        Config.setInstance(cfg);
    }

    // ------------------------------------------------------------------
    // Priority-class mapping
    // ------------------------------------------------------------------

    @Test
    void mapToSchedulerClassRoutesEveryPriorityClass() {
        assertEquals(SchedulerClass.MAIN_EXPLOIT,
                TestPlanCorpus.mapToSchedulerClass(
                        QueuePriorityClass.BRANCH_AND_STRONG_TRACE));
        assertEquals(SchedulerClass.BRANCH_SCOUT,
                TestPlanCorpus.mapToSchedulerClass(
                        QueuePriorityClass.BRANCH_ONLY));
        assertEquals(SchedulerClass.SHADOW_EVAL,
                TestPlanCorpus.mapToSchedulerClass(
                        QueuePriorityClass.BRANCH_AND_WEAK_TRACE));
        assertEquals(SchedulerClass.SHADOW_EVAL,
                TestPlanCorpus.mapToSchedulerClass(
                        QueuePriorityClass.TRACE_ONLY_STRONG));
        assertEquals(SchedulerClass.SHADOW_EVAL,
                TestPlanCorpus.mapToSchedulerClass(
                        QueuePriorityClass.TRACE_ONLY_WEAK));
        assertEquals(SchedulerClass.BRANCH_SCOUT,
                TestPlanCorpus.mapToSchedulerClass(
                        QueuePriorityClass.UNKNOWN));
        assertEquals(SchedulerClass.BRANCH_SCOUT,
                TestPlanCorpus.mapToSchedulerClass(null));
    }

    @Test
    void mutationBudgetResolvedByClass() {
        assertEquals(30, TestPlanCorpus.resolveMutationBudget(
                SchedulerClass.MAIN_EXPLOIT));
        assertEquals(10, TestPlanCorpus.resolveMutationBudget(
                SchedulerClass.BRANCH_SCOUT));
        assertEquals(4, TestPlanCorpus.resolveMutationBudget(
                SchedulerClass.SHADOW_EVAL));
        assertEquals(50, TestPlanCorpus.resolveMutationBudget(
                SchedulerClass.REPRO_CONFIRM));
    }

    // ------------------------------------------------------------------
    // Admission + dequeue
    // ------------------------------------------------------------------

    @Test
    void strongBranchAndTraceOutranksBranchOnly() {
        ObservabilityMetrics metrics = new ObservabilityMetrics(null);
        TestPlanCorpus corpus = new TestPlanCorpus(metrics);

        TestPlan branchOnly = makeUniquePlan(100, "branchOnly");
        TestPlan strongMix = makeUniquePlan(101, "strongMix");

        corpus.addTestPlan(branchOnly, 0, 100,
                AdmissionReason.BRANCH_ONLY,
                TraceEvidenceStrength.NONE,
                StructuredCandidateStrength.NONE,
                QueuePriorityClass.BRANCH_ONLY);
        corpus.addTestPlan(strongMix, 1, 101,
                AdmissionReason.BRANCH_AND_TRACE,
                TraceEvidenceStrength.STRONG,
                StructuredCandidateStrength.NONE,
                QueuePriorityClass.BRANCH_AND_STRONG_TRACE);

        QueuedTestPlan first = corpus.pollQueuedTestPlan(2);
        assertNotNull(first);
        assertSame(strongMix, first.plan,
                "strong branch+trace must dequeue before branch-only");
        assertEquals(SchedulerClass.MAIN_EXPLOIT, first.schedulerClass);
        assertEquals(QueuePriorityClass.BRANCH_AND_STRONG_TRACE,
                first.priorityClass);
        assertEquals(30, first.plannedMutationBudget);
    }

    @Test
    void strongCandidateParentPromotesIntoReproConfirm() {
        ObservabilityMetrics metrics = new ObservabilityMetrics(null);
        TestPlanCorpus corpus = new TestPlanCorpus(metrics);

        TestPlan mainExploit = makeUniquePlan(200, "mainExploit");
        TestPlan strongCandidateParent = makeUniquePlan(201,
                "strongCandidateParent");

        corpus.addTestPlan(mainExploit, 0, 200,
                AdmissionReason.BRANCH_AND_TRACE,
                TraceEvidenceStrength.STRONG,
                StructuredCandidateStrength.NONE,
                QueuePriorityClass.BRANCH_AND_STRONG_TRACE);
        corpus.addCandidateParent(strongCandidateParent, 1, 201,
                AdmissionReason.BRANCH_ONLY,
                TraceEvidenceStrength.NONE,
                StructuredCandidateStrength.STRONG,
                QueuePriorityClass.BRANCH_ONLY);

        QueuedTestPlan first = corpus.pollQueuedTestPlan(2);
        assertNotNull(first);
        assertSame(strongCandidateParent, first.plan,
                "repro_confirm must outrank main_exploit even when "
                        + "the observability priority is BRANCH_ONLY");
        assertEquals(50, first.plannedMutationBudget);
        // scheduler_class is REPRO_CONFIRM even though the admission
        // label says BRANCH_ONLY — that is the whole point of the
        // "scheduler_class is the source of truth" invariant.
        assertEquals(SchedulerClass.REPRO_CONFIRM, first.schedulerClass);
        assertEquals(QueuePriorityClass.BRANCH_ONLY, first.priorityClass);
    }

    @Test
    void branchOnlyGetsBranchScoutBudget() {
        TestPlanCorpus corpus = new TestPlanCorpus(
                new ObservabilityMetrics(null));
        TestPlan plan = makeUniquePlan(300, "scout");
        corpus.addTestPlan(plan, 0, 300,
                AdmissionReason.BRANCH_ONLY,
                TraceEvidenceStrength.NONE,
                StructuredCandidateStrength.NONE,
                QueuePriorityClass.BRANCH_ONLY);

        QueuedTestPlan entry = corpus.pollQueuedTestPlan(1);
        assertNotNull(entry);
        assertEquals(10, entry.plannedMutationBudget);
        assertEquals(SchedulerClass.BRANCH_SCOUT, entry.schedulerClass);
    }

    @Test
    void shadowEvalReceivesWeakTraceAtLowBudget() {
        TestPlanCorpus corpus = new TestPlanCorpus(
                new ObservabilityMetrics(null));
        TestPlan plan = makeUniquePlan(400, "shadow");
        corpus.addTestPlan(plan, 0, 400,
                AdmissionReason.TRACE_ONLY_WINDOW_SIM,
                TraceEvidenceStrength.WEAK,
                StructuredCandidateStrength.NONE,
                QueuePriorityClass.TRACE_ONLY_WEAK);

        QueuedTestPlan entry = corpus.pollQueuedTestPlan(1);
        assertNotNull(entry);
        assertEquals(4, entry.plannedMutationBudget);
        assertEquals(SchedulerClass.SHADOW_EVAL, entry.schedulerClass);
    }

    // ------------------------------------------------------------------
    // Compact dedup
    // ------------------------------------------------------------------

    @Test
    void compactSignatureCollapsesDuplicatesUnderSameLineage() {
        ObservabilityMetrics metrics = new ObservabilityMetrics(null);
        TestPlanCorpus corpus = new TestPlanCorpus(metrics);

        TestPlan a = makeUniquePlan(500, "same");
        TestPlan b = makeUniquePlan(501, "same");

        corpus.addTestPlan(a, 0, 500,
                AdmissionReason.BRANCH_AND_TRACE,
                TraceEvidenceStrength.STRONG,
                StructuredCandidateStrength.NONE,
                QueuePriorityClass.BRANCH_AND_STRONG_TRACE);
        // Same lineage root → must collapse.
        corpus.addTestPlan(b, 1, 500,
                AdmissionReason.BRANCH_AND_TRACE,
                TraceEvidenceStrength.STRONG,
                StructuredCandidateStrength.NONE,
                QueuePriorityClass.BRANCH_AND_STRONG_TRACE);

        assertEquals(1, corpus.size(),
                "dedup should collapse two admissions from the same lineage");
        assertEquals(1, metrics.getSchedulerDedupCollisions(
                SchedulerClass.MAIN_EXPLOIT));
    }

    @Test
    void dedupIsLineageAware() {
        // Reviewer fix: the same plan skeleton admitted from two
        // INDEPENDENT parents must not collapse into a single queue
        // entry. The dedup key now includes the lineage root.
        ObservabilityMetrics metrics = new ObservabilityMetrics(null);
        TestPlanCorpus corpus = new TestPlanCorpus(metrics);

        TestPlan a = makeUniquePlan(600, "shared");
        TestPlan b = makeUniquePlan(601, "shared");
        // The underlying skeleton is identical.
        assertEquals(a.compactSignature(), b.compactSignature());

        corpus.addTestPlan(a, 0, /*lineageRoot=*/600,
                AdmissionReason.BRANCH_AND_TRACE,
                TraceEvidenceStrength.STRONG,
                StructuredCandidateStrength.NONE,
                QueuePriorityClass.BRANCH_AND_STRONG_TRACE);
        corpus.addTestPlan(b, 1, /*lineageRoot=*/700,
                AdmissionReason.BRANCH_AND_TRACE,
                TraceEvidenceStrength.STRONG,
                StructuredCandidateStrength.NONE,
                QueuePriorityClass.BRANCH_AND_STRONG_TRACE);

        assertEquals(2, corpus.size(),
                "independent lineage roots must not collapse via dedup");
        assertEquals(0, metrics.getSchedulerDedupCollisions(
                SchedulerClass.MAIN_EXPLOIT));
    }

    @Test
    void differentSignaturesDoNotCollapse() {
        ObservabilityMetrics metrics = new ObservabilityMetrics(null);
        TestPlanCorpus corpus = new TestPlanCorpus(metrics);

        TestPlan a = makeUniquePlan(700, "plan-a");
        TestPlan b = makeUniquePlan(701, "plan-b");
        assertNotEquals(a.compactSignature(), b.compactSignature());

        corpus.addTestPlan(a, 0, 700,
                AdmissionReason.BRANCH_ONLY,
                TraceEvidenceStrength.NONE,
                StructuredCandidateStrength.NONE,
                QueuePriorityClass.BRANCH_ONLY);
        corpus.addTestPlan(b, 1, 701,
                AdmissionReason.BRANCH_ONLY,
                TraceEvidenceStrength.NONE,
                StructuredCandidateStrength.NONE,
                QueuePriorityClass.BRANCH_ONLY);

        assertEquals(2, corpus.size());
        assertEquals(0, metrics.getSchedulerDedupCollisions(
                SchedulerClass.BRANCH_SCOUT));
    }

    @Test
    void linkFailureNodesAreInSignature() {
        // Reviewer fix: multi-node fault identity must reach the
        // signature. Two plans that differ only in LinkFailure
        // endpoints must produce different signatures.
        TestPlan planAB = makePlanWithFault(800,
                new LinkFailure(0, 1));
        TestPlan planAC = makePlanWithFault(801,
                new LinkFailure(0, 2));
        assertNotEquals(planAB.compactSignature(),
                planAC.compactSignature());

        // Order insensitivity: LinkFailure(0,1) and LinkFailure(1,0)
        // describe the same physical link and must collide.
        TestPlan planBA = makePlanWithFault(802,
                new LinkFailure(1, 0));
        assertEquals(planAB.compactSignature(),
                planBA.compactSignature());
    }

    @Test
    void partitionFailureNodeSetsAreInSignature() {
        // Reviewer fix: PartitionFailure node sets must reach the
        // signature so two plans with disjoint partition topologies
        // do not collapse.
        Set<Integer> side1 = new LinkedHashSet<>(Arrays.asList(0, 1));
        Set<Integer> side2 = new LinkedHashSet<>(Arrays.asList(2));
        Set<Integer> altSide1 = new LinkedHashSet<>(Arrays.asList(0));
        Set<Integer> altSide2 = new LinkedHashSet<>(Arrays.asList(1, 2));
        TestPlan plan01vs2 = makePlanWithFault(900,
                new PartitionFailure(side1, side2));
        TestPlan plan0vs12 = makePlanWithFault(901,
                new PartitionFailure(altSide1, altSide2));
        assertNotEquals(plan01vs2.compactSignature(),
                plan0vs12.compactSignature(),
                "different partition topologies must not collide");

        // Mirror-image partition must collapse.
        TestPlan plan2vs01 = makePlanWithFault(902,
                new PartitionFailure(side2, side1));
        assertEquals(plan01vs2.compactSignature(),
                plan2vs01.compactSignature(),
                "mirror partitions describe the same cut");
    }

    @Test
    void dedupCanBeDisabledViaConfig() {
        Config.getConf().enableTestPlanCompactDedup = false;
        TestPlanCorpus corpus = new TestPlanCorpus(
                new ObservabilityMetrics(null));

        TestPlan a = makeUniquePlan(1000, "dup");
        TestPlan b = makeUniquePlan(1001, "dup");
        corpus.addTestPlan(a, 0, 1000,
                AdmissionReason.BRANCH_ONLY,
                TraceEvidenceStrength.NONE,
                StructuredCandidateStrength.NONE,
                QueuePriorityClass.BRANCH_ONLY);
        corpus.addTestPlan(b, 1, 1000,
                AdmissionReason.BRANCH_ONLY,
                TraceEvidenceStrength.NONE,
                StructuredCandidateStrength.NONE,
                QueuePriorityClass.BRANCH_ONLY);

        assertEquals(2, corpus.size());
    }

    // ------------------------------------------------------------------
    // Review round 2: dedup promotion + rollback isolation
    // ------------------------------------------------------------------

    @Test
    void dedupPromotesWeakEntryToStronger() {
        // Reviewer fix: a stronger re-admission must upgrade the live
        // queued entry to the stronger lane, not just bump score.
        ObservabilityMetrics metrics = new ObservabilityMetrics(null);
        TestPlanCorpus corpus = new TestPlanCorpus(metrics);

        TestPlan weak = makeUniquePlan(2500, "promote");
        TestPlan strong = makeUniquePlan(2501, "promote");

        // First admission: TRACE_ONLY_WEAK → SHADOW_EVAL, budget=4.
        corpus.addTestPlan(weak, 0, 2500,
                AdmissionReason.TRACE_ONLY_WINDOW_SIM,
                TraceEvidenceStrength.WEAK,
                StructuredCandidateStrength.NONE,
                QueuePriorityClass.TRACE_ONLY_WEAK);
        // Second admission (same lineage, same skeleton):
        // BRANCH_AND_STRONG_TRACE → MAIN_EXPLOIT, budget=30.
        corpus.addTestPlan(strong, 1, 2500,
                AdmissionReason.BRANCH_AND_TRACE,
                TraceEvidenceStrength.STRONG,
                StructuredCandidateStrength.NONE,
                QueuePriorityClass.BRANCH_AND_STRONG_TRACE);

        assertEquals(1, corpus.size(),
                "dedup should collapse duplicates under same lineage");
        // Dedup counter ticks on the OLD lane (source of promotion).
        assertEquals(1, metrics.getSchedulerDedupCollisions(
                SchedulerClass.SHADOW_EVAL));

        QueuedTestPlan dequeued = corpus.pollQueuedTestPlan(2);
        assertNotNull(dequeued);
        assertEquals(SchedulerClass.MAIN_EXPLOIT, dequeued.schedulerClass,
                "dedup promotion must move the entry to the stronger lane");
        assertEquals(30, dequeued.plannedMutationBudget);
        assertEquals(QueuePriorityClass.BRANCH_AND_STRONG_TRACE,
                dequeued.priorityClass,
                "admission labels must reflect the strongest admission");
        assertEquals(AdmissionReason.BRANCH_AND_TRACE,
                dequeued.admissionReason);
        assertEquals(TraceEvidenceStrength.STRONG,
                dequeued.traceEvidenceStrength);
    }

    @Test
    void dedupPromotesIntoReproConfirmOnCandidateParent() {
        // Reviewer fix: a duplicate arriving via addCandidateParent
        // must enter REPRO_CONFIRM even if an earlier admission for
        // the same signature landed in a weaker lane.
        ObservabilityMetrics metrics = new ObservabilityMetrics(null);
        TestPlanCorpus corpus = new TestPlanCorpus(metrics);

        TestPlan base = makeUniquePlan(2600, "cand");
        TestPlan promoted = makeUniquePlan(2601, "cand");

        corpus.addTestPlan(base, 0, 2600,
                AdmissionReason.BRANCH_AND_TRACE,
                TraceEvidenceStrength.STRONG,
                StructuredCandidateStrength.NONE,
                QueuePriorityClass.BRANCH_AND_STRONG_TRACE);
        // Re-admission via candidate-parent path with the same
        // signature. Must move the entry from MAIN_EXPLOIT to
        // REPRO_CONFIRM.
        corpus.addCandidateParent(promoted, 1, 2600,
                AdmissionReason.BRANCH_ONLY,
                TraceEvidenceStrength.NONE,
                StructuredCandidateStrength.STRONG,
                QueuePriorityClass.BRANCH_ONLY);

        assertEquals(1, corpus.size());
        assertEquals(1, metrics.getSchedulerDedupCollisions(
                SchedulerClass.MAIN_EXPLOIT));

        QueuedTestPlan dequeued = corpus.pollQueuedTestPlan(2);
        assertNotNull(dequeued);
        assertEquals(SchedulerClass.REPRO_CONFIRM, dequeued.schedulerClass,
                "candidate-parent re-admission must enter REPRO_CONFIRM");
        assertEquals(50, dequeued.plannedMutationBudget);
        assertEquals(StructuredCandidateStrength.STRONG,
                dequeued.candidateStrength,
                "candidate strength must be the max of the two");
    }

    @Test
    void dedupDoesNotDemoteStrongerQueuedEntry() {
        // Reviewer invariant: a later weaker re-admission must NOT
        // demote a stronger queued entry.
        ObservabilityMetrics metrics = new ObservabilityMetrics(null);
        TestPlanCorpus corpus = new TestPlanCorpus(metrics);

        TestPlan strongFirst = makeUniquePlan(2700, "nodemote");
        TestPlan weakSecond = makeUniquePlan(2701, "nodemote");

        corpus.addCandidateParent(strongFirst, 0, 2700,
                AdmissionReason.BRANCH_ONLY,
                TraceEvidenceStrength.NONE,
                StructuredCandidateStrength.STRONG,
                QueuePriorityClass.BRANCH_ONLY);
        corpus.addTestPlan(weakSecond, 1, 2700,
                AdmissionReason.TRACE_ONLY_WINDOW_SIM,
                TraceEvidenceStrength.WEAK,
                StructuredCandidateStrength.NONE,
                QueuePriorityClass.TRACE_ONLY_WEAK);

        assertEquals(1, corpus.size());
        QueuedTestPlan dequeued = corpus.pollQueuedTestPlan(2);
        assertNotNull(dequeued);
        assertEquals(SchedulerClass.REPRO_CONFIRM, dequeued.schedulerClass,
                "a weaker re-admission must NEVER demote a stronger "
                        + "queued entry");
        assertEquals(50, dequeued.plannedMutationBudget);
        assertEquals(StructuredCandidateStrength.STRONG,
                dequeued.candidateStrength);
    }

    @Test
    void legacyFifoDoesNotCollapseDuplicates() {
        // Reviewer fix: when Phase 3 scheduler is off, rollback must
        // be exact FIFO — duplicate plans stay as two separate
        // entries in insertion order, regardless of the dedup config.
        Config.getConf().usePriorityTestPlanScheduler = false;
        Config.getConf().enableTestPlanCompactDedup = true;
        ObservabilityMetrics metrics = new ObservabilityMetrics(null);
        TestPlanCorpus corpus = new TestPlanCorpus(metrics);

        TestPlan a = makeUniquePlan(2800, "legacy-dup");
        TestPlan b = makeUniquePlan(2801, "legacy-dup");
        corpus.addTestPlan(a, 0, 2800,
                AdmissionReason.BRANCH_ONLY,
                TraceEvidenceStrength.NONE,
                StructuredCandidateStrength.NONE,
                QueuePriorityClass.BRANCH_ONLY);
        corpus.addTestPlan(b, 1, 2800,
                AdmissionReason.BRANCH_ONLY,
                TraceEvidenceStrength.NONE,
                StructuredCandidateStrength.NONE,
                QueuePriorityClass.BRANCH_ONLY);

        assertEquals(2, corpus.size(),
                "legacy FIFO must not collapse duplicate plans");
        // Dedup collision counter must stay at 0 in rollback mode.
        assertEquals(0, metrics.getSchedulerDedupCollisions(
                SchedulerClass.BRANCH_SCOUT));
        // FIFO ordering: a is popped before b.
        assertSame(a, corpus.getTestPlan(2));
        assertSame(b, corpus.getTestPlan(3));
        assertNull(corpus.getTestPlan(4));
    }

    // ------------------------------------------------------------------
    // Decay
    // ------------------------------------------------------------------

    @Test
    void decayDemotesMainExploitToBranchScout() {
        ObservabilityMetrics metrics = new ObservabilityMetrics(null);
        TestPlanCorpus corpus = new TestPlanCorpus(metrics);
        Config.getConf().testPlanDequeueDecayThreshold = 2;

        // Repeatedly enqueue+dequeue plans on the same lineage root
        // so the dequeuesPerLineage counter accumulates without any
        // payoff credit.
        for (int i = 0; i < 3; i++) {
            TestPlan plan = makeUniquePlan(1100 + i,
                    "decay-" + i);
            corpus.addTestPlan(plan, i, 1100,
                    AdmissionReason.BRANCH_AND_TRACE,
                    TraceEvidenceStrength.STRONG,
                    StructuredCandidateStrength.NONE,
                    QueuePriorityClass.BRANCH_AND_STRONG_TRACE);
            assertNotNull(corpus.pollQueuedTestPlan(10 + i));
        }
        // Push a survivor and run decay.
        TestPlan plan = makeUniquePlan(1200, "decay-trigger");
        corpus.addTestPlan(plan, 4, 1100,
                AdmissionReason.BRANCH_AND_TRACE,
                TraceEvidenceStrength.STRONG,
                StructuredCandidateStrength.NONE,
                QueuePriorityClass.BRANCH_AND_STRONG_TRACE);
        corpus.decayStaleEntries();

        QueuedTestPlan dequeued = corpus.pollQueuedTestPlan(15);
        assertNotNull(dequeued);
        assertEquals(SchedulerClass.BRANCH_SCOUT, dequeued.schedulerClass,
                "decay must move main_exploit down to branch_scout");
        assertEquals(10, dequeued.plannedMutationBudget);
        assertTrue(metrics.getSchedulerDecayDemotions(
                SchedulerClass.MAIN_EXPLOIT) >= 1);
    }

    @Test
    void decayFromReproConfirmStopsAtMainExploit() {
        // Reviewer fix: a plan sitting in REPRO_CONFIRM must decay to
        // MAIN_EXPLOIT (one step down on the ladder), NOT to whatever
        // the admission priority class would have mapped to (which for
        // BRANCH_ONLY would have been BRANCH_SCOUT or worse).
        ObservabilityMetrics metrics = new ObservabilityMetrics(null);
        TestPlanCorpus corpus = new TestPlanCorpus(metrics);
        Config.getConf().testPlanDequeueDecayThreshold = 2;

        // Burn the lineage root: enqueue+dequeue twice on the same
        // lineage, with each plan landing in REPRO_CONFIRM via the
        // candidate-parent path (admission priority stays BRANCH_ONLY).
        for (int i = 0; i < 3; i++) {
            TestPlan plan = makeUniquePlan(1300 + i,
                    "repro-decay-" + i);
            corpus.addCandidateParent(plan, i, 1300,
                    AdmissionReason.BRANCH_ONLY,
                    TraceEvidenceStrength.NONE,
                    StructuredCandidateStrength.STRONG,
                    QueuePriorityClass.BRANCH_ONLY);
            assertNotNull(corpus.pollQueuedTestPlan(20 + i));
        }
        TestPlan survivor = makeUniquePlan(1400, "repro-survivor");
        corpus.addCandidateParent(survivor, 4, 1300,
                AdmissionReason.BRANCH_ONLY,
                TraceEvidenceStrength.NONE,
                StructuredCandidateStrength.STRONG,
                QueuePriorityClass.BRANCH_ONLY);
        corpus.decayStaleEntries();

        QueuedTestPlan dequeued = corpus.pollQueuedTestPlan(25);
        assertNotNull(dequeued);
        assertEquals(SchedulerClass.MAIN_EXPLOIT, dequeued.schedulerClass,
                "repro_confirm must decay to main_exploit, not "
                        + "re-derive from priorityClass");
        assertEquals(30, dequeued.plannedMutationBudget);
        assertTrue(metrics.getSchedulerDecayDemotions(
                SchedulerClass.REPRO_CONFIRM) >= 1);
    }

    @Test
    void decayKeepsPlansWithPayoffAtOriginalBudget() {
        TestPlanCorpus corpus = new TestPlanCorpus(
                new ObservabilityMetrics(null));
        Config.getConf().testPlanDequeueDecayThreshold = 2;

        for (int i = 0; i < 3; i++) {
            TestPlan plan = makeUniquePlan(1500 + i,
                    "payoff-" + i);
            corpus.addTestPlan(plan, i, 1500,
                    AdmissionReason.BRANCH_AND_TRACE,
                    TraceEvidenceStrength.STRONG,
                    StructuredCandidateStrength.NONE,
                    QueuePriorityClass.BRANCH_AND_STRONG_TRACE);
            assertNotNull(corpus.pollQueuedTestPlan(30 + i));
        }
        TestPlan survivor = makeUniquePlan(1600, "survivor");
        corpus.addTestPlan(survivor, 4, 1500,
                AdmissionReason.BRANCH_AND_TRACE,
                TraceEvidenceStrength.STRONG,
                StructuredCandidateStrength.NONE,
                QueuePriorityClass.BRANCH_AND_STRONG_TRACE);
        // Credit a branch payoff BEFORE decay fires.
        corpus.notifyBranchPayoff(1500);
        corpus.decayStaleEntries();

        QueuedTestPlan dequeued = corpus.pollQueuedTestPlan(35);
        assertNotNull(dequeued);
        assertEquals(SchedulerClass.MAIN_EXPLOIT, dequeued.schedulerClass,
                "plans with payoff credits must not decay");
        assertEquals(30, dequeued.plannedMutationBudget);
    }

    @Test
    void decaySweepsInReverseOrderWithoutCascading() {
        // Reviewer check: queues are processed in reverse enum order
        // so a demoted REPRO_CONFIRM -> MAIN_EXPLOIT entry should not
        // be demoted again in the same sweep.
        TestPlanCorpus corpus = new TestPlanCorpus(
                new ObservabilityMetrics(null));
        Config.getConf().testPlanDequeueDecayThreshold = 1;

        // Dequeue once to bump dequeuesPerLineage[2000]=1.
        TestPlan warm = makeUniquePlan(1999, "warm");
        corpus.addCandidateParent(warm, 0, 2000,
                AdmissionReason.BRANCH_ONLY,
                TraceEvidenceStrength.NONE,
                StructuredCandidateStrength.STRONG,
                QueuePriorityClass.BRANCH_ONLY);
        assertNotNull(corpus.pollQueuedTestPlan(1));

        // Now enqueue the survivor in REPRO_CONFIRM (same lineage root).
        TestPlan entry = makeUniquePlan(2001, "sweep");
        corpus.addCandidateParent(entry, 2, 2000,
                AdmissionReason.BRANCH_ONLY,
                TraceEvidenceStrength.NONE,
                StructuredCandidateStrength.STRONG,
                QueuePriorityClass.BRANCH_ONLY);
        corpus.decayStaleEntries();

        QueuedTestPlan dequeued = corpus.pollQueuedTestPlan(3);
        assertNotNull(dequeued);
        // Should be exactly one step down the ladder — MAIN_EXPLOIT —
        // not cascaded to BRANCH_SCOUT or SHADOW_EVAL.
        assertEquals(SchedulerClass.MAIN_EXPLOIT, dequeued.schedulerClass);
    }

    // ------------------------------------------------------------------
    // Legacy FIFO
    // ------------------------------------------------------------------

    @Test
    void legacyFifoModePreservesInsertionOrder() {
        Config.getConf().usePriorityTestPlanScheduler = false;
        ObservabilityMetrics metrics = new ObservabilityMetrics(null);
        TestPlanCorpus corpus = new TestPlanCorpus(metrics);

        TestPlan a = makeUniquePlan(1700, "legacy-a");
        TestPlan b = makeUniquePlan(1701, "legacy-b");
        corpus.addTestPlan(a, 0, 1700,
                AdmissionReason.BRANCH_ONLY,
                TraceEvidenceStrength.NONE,
                StructuredCandidateStrength.NONE,
                QueuePriorityClass.BRANCH_ONLY);
        corpus.addTestPlan(b, 1, 1701,
                AdmissionReason.BRANCH_AND_TRACE,
                TraceEvidenceStrength.STRONG,
                StructuredCandidateStrength.NONE,
                QueuePriorityClass.BRANCH_AND_STRONG_TRACE);

        assertSame(a, corpus.getTestPlan(2));
        assertSame(b, corpus.getTestPlan(3));
        assertNull(corpus.getTestPlan(4));
    }

    // ------------------------------------------------------------------
    // Observability counters
    // ------------------------------------------------------------------

    @Test
    void observabilityCountersTrackPerSchedulerClass() {
        ObservabilityMetrics metrics = new ObservabilityMetrics(null);
        TestPlanCorpus corpus = new TestPlanCorpus(metrics);

        TestPlan a = makeUniquePlan(1800, "obs-a");
        TestPlan b = makeUniquePlan(1801, "obs-b");
        corpus.addTestPlan(a, 0, 1800,
                AdmissionReason.BRANCH_AND_TRACE,
                TraceEvidenceStrength.STRONG,
                StructuredCandidateStrength.NONE,
                QueuePriorityClass.BRANCH_AND_STRONG_TRACE);
        corpus.addTestPlan(b, 1, 1801,
                AdmissionReason.BRANCH_ONLY,
                TraceEvidenceStrength.NONE,
                StructuredCandidateStrength.NONE,
                QueuePriorityClass.BRANCH_ONLY);

        assertEquals(1, metrics.getSchedulerEnqueues(
                SchedulerClass.MAIN_EXPLOIT));
        assertEquals(1, metrics.getSchedulerEnqueues(
                SchedulerClass.BRANCH_SCOUT));

        QueuedTestPlan d1 = corpus.pollQueuedTestPlan(2);
        assertNotNull(d1);
        QueuedTestPlan d2 = corpus.pollQueuedTestPlan(3);
        assertNotNull(d2);

        assertEquals(1, metrics.getSchedulerDequeues(
                SchedulerClass.MAIN_EXPLOIT));
        assertEquals(1, metrics.getSchedulerDequeues(
                SchedulerClass.BRANCH_SCOUT));
        assertEquals(30, metrics.getSchedulerMutationBudgetSpent(
                SchedulerClass.MAIN_EXPLOIT));
        assertEquals(10, metrics.getSchedulerMutationBudgetSpent(
                SchedulerClass.BRANCH_SCOUT));
    }

    @Test
    void reproConfirmCountersTrackPromotedCandidates() {
        // Reviewer fix: promoting a plan into REPRO_CONFIRM via
        // addCandidateParent must increment REPRO_CONFIRM counters,
        // not the observability label counters.
        ObservabilityMetrics metrics = new ObservabilityMetrics(null);
        TestPlanCorpus corpus = new TestPlanCorpus(metrics);

        TestPlan plan = makeUniquePlan(1900, "repro");
        corpus.addCandidateParent(plan, 0, 1900,
                AdmissionReason.BRANCH_ONLY,
                TraceEvidenceStrength.NONE,
                StructuredCandidateStrength.STRONG,
                QueuePriorityClass.BRANCH_ONLY);

        assertEquals(1, metrics.getSchedulerEnqueues(
                SchedulerClass.REPRO_CONFIRM));
        assertEquals(0, metrics.getSchedulerEnqueues(
                SchedulerClass.BRANCH_SCOUT));

        QueuedTestPlan d = corpus.pollQueuedTestPlan(1);
        assertNotNull(d);
        assertEquals(1, metrics.getSchedulerDequeues(
                SchedulerClass.REPRO_CONFIRM));
        assertEquals(50, metrics.getSchedulerMutationBudgetSpent(
                SchedulerClass.REPRO_CONFIRM));
    }

    @Test
    void payoffCountersCreditTheCurrentSchedulerLane() {
        ObservabilityMetrics metrics = new ObservabilityMetrics(null);
        TestPlanCorpus corpus = new TestPlanCorpus(metrics);

        TestPlan plan = makeUniquePlan(2100, "payoff");
        corpus.addTestPlan(plan, 0, 2100,
                AdmissionReason.BRANCH_AND_TRACE,
                TraceEvidenceStrength.STRONG,
                StructuredCandidateStrength.NONE,
                QueuePriorityClass.BRANCH_AND_STRONG_TRACE);

        corpus.notifyBranchPayoff(2100);
        corpus.notifyStrongCandidatePayoff(2100);
        corpus.notifyWeakCandidatePayoff(2100);

        assertEquals(1, metrics.getSchedulerBranchPayoff(
                SchedulerClass.MAIN_EXPLOIT));
        assertEquals(1, metrics.getSchedulerStrongPayoff(
                SchedulerClass.MAIN_EXPLOIT));
        assertEquals(1, metrics.getSchedulerWeakPayoff(
                SchedulerClass.MAIN_EXPLOIT));
    }

    // ------------------------------------------------------------------
    // Snapshot / occupancy
    // ------------------------------------------------------------------

    @Test
    void occupancyBySchedulerClassReflectsLiveState() {
        ObservabilityMetrics metrics = new ObservabilityMetrics(null);
        TestPlanCorpus corpus = new TestPlanCorpus(metrics);

        corpus.addCandidateParent(makeUniquePlan(2200, "a"), 0, 2200,
                AdmissionReason.BRANCH_ONLY,
                TraceEvidenceStrength.NONE,
                StructuredCandidateStrength.STRONG,
                QueuePriorityClass.BRANCH_ONLY);
        corpus.addTestPlan(makeUniquePlan(2201, "b"), 1, 2201,
                AdmissionReason.BRANCH_AND_TRACE,
                TraceEvidenceStrength.STRONG,
                StructuredCandidateStrength.NONE,
                QueuePriorityClass.BRANCH_AND_STRONG_TRACE);
        corpus.addTestPlan(makeUniquePlan(2202, "c"), 2, 2202,
                AdmissionReason.BRANCH_ONLY,
                TraceEvidenceStrength.NONE,
                StructuredCandidateStrength.NONE,
                QueuePriorityClass.BRANCH_ONLY);
        corpus.addTestPlan(makeUniquePlan(2203, "d"), 3, 2203,
                AdmissionReason.TRACE_ONLY_WINDOW_SIM,
                TraceEvidenceStrength.WEAK,
                StructuredCandidateStrength.NONE,
                QueuePriorityClass.TRACE_ONLY_WEAK);

        assertEquals(4, corpus.size());
        assertEquals(1, (int) corpus.occupancyBySchedulerClass()
                .get(SchedulerClass.REPRO_CONFIRM));
        assertEquals(1, (int) corpus.occupancyBySchedulerClass()
                .get(SchedulerClass.MAIN_EXPLOIT));
        assertEquals(1, (int) corpus.occupancyBySchedulerClass()
                .get(SchedulerClass.BRANCH_SCOUT));
        assertEquals(1, (int) corpus.occupancyBySchedulerClass()
                .get(SchedulerClass.SHADOW_EVAL));
    }

    @Test
    void queueActivityRowCarriesSchedulerClass() {
        // Reviewer fix: the CSV row for every enqueue/dequeue must
        // carry the scheduler_class column so offline parsers can
        // reconstruct the scheduler's full state transition history.
        // We exercise this indirectly by verifying that a candidate
        // parent with admission-label BRANCH_ONLY lands in
        // REPRO_CONFIRM in the dequeue metrics.
        ObservabilityMetrics metrics = new ObservabilityMetrics(null);
        TestPlanCorpus corpus = new TestPlanCorpus(metrics);

        TestPlan plan = makeUniquePlan(2300, "csv-row");
        corpus.addCandidateParent(plan, 0, 2300,
                AdmissionReason.BRANCH_ONLY,
                TraceEvidenceStrength.NONE,
                StructuredCandidateStrength.STRONG,
                QueuePriorityClass.BRANCH_ONLY);
        corpus.pollQueuedTestPlan(1);

        // The enqueue/dequeue metrics must credit REPRO_CONFIRM, not
        // BRANCH_SCOUT (which is what the priority-class mapper
        // would have produced for BRANCH_ONLY).
        assertEquals(1, metrics.getSchedulerEnqueues(
                SchedulerClass.REPRO_CONFIRM));
        assertEquals(0, metrics.getSchedulerEnqueues(
                SchedulerClass.BRANCH_SCOUT));
        assertEquals(1, metrics.getSchedulerDequeues(
                SchedulerClass.REPRO_CONFIRM));
        assertEquals(0, metrics.getSchedulerDequeues(
                SchedulerClass.BRANCH_SCOUT));
    }

    // ------------------------------------------------------------------
    // Test helpers
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

    /**
     * Build a plan with a fixed event shape so two calls differ only in
     * the supplied fault event. Used by the fault-identity tests.
     */
    private static TestPlan makePlanWithFault(int testId, Event fault) {
        List<Event> events = new ArrayList<>();
        events.add(new ShellCommand("SELECT * FROM t", 0));
        events.add(fault);
        events.add(new UpgradeOp(1));
        events.add(new ShellCommand("INSERT INTO t VALUES (1)", 1));
        TestPlan plan = new TestPlan(3, events,
                new ArrayList<>(Arrays.asList(
                        "SELECT COUNT(*) FROM t",
                        "SELECT MAX(id) FROM t")),
                new ArrayList<>());
        plan.lineageTestId = testId;
        return plan;
    }

    /** Not used by assertions but kept for parity with generic helpers. */
    @SuppressWarnings("unused")
    private static Set<Integer> setOf(Integer... items) {
        return new HashSet<>(Arrays.asList(items));
    }
}
