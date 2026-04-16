package org.zlab.upfuzz.fuzzingengine.server;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Deque;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.zlab.upfuzz.fuzzingengine.Config;
import org.zlab.upfuzz.fuzzingengine.server.observability.AdmissionReason;
import org.zlab.upfuzz.fuzzingengine.server.observability.ObservabilityMetrics;
import org.zlab.upfuzz.fuzzingengine.server.observability.QueueActivityRow;
import org.zlab.upfuzz.fuzzingengine.server.observability.QueuePriorityClass;
import org.zlab.upfuzz.fuzzingengine.server.observability.SchedulerClass;
import org.zlab.upfuzz.fuzzingengine.server.observability.StructuredCandidateStrength;
import org.zlab.upfuzz.fuzzingengine.server.observability.TraceEvidenceStrength;
import org.zlab.upfuzz.fuzzingengine.testplan.TestPlan;

/**
 * Short-term scheduler for admitted test plans.
 *
 * <p>Phase 3 replaces the Phase 0 FIFO queue with four class-specific
 * sub-queues keyed by the internal {@link SchedulerClass}:
 *
 * <ul>
 *   <li>{@link SchedulerClass#REPRO_CONFIRM}: strong structured
 *       candidate parents — strict-priority lane used to re-observe
 *       and stabilize real bug candidates.</li>
 *   <li>{@link SchedulerClass#MAIN_EXPLOIT}: branch + strong trace
 *       (and unlabeled branch-backed admissions) — the primary
 *       weighted-round-robin lane.</li>
 *   <li>{@link SchedulerClass#BRANCH_SCOUT}: pure branch-only
 *       admissions. Always kept alive so branch exploration does
 *       not collapse.</li>
 *   <li>{@link SchedulerClass#SHADOW_EVAL}: branch + weak trace,
 *       trace-only strong, and trace-only weak — the low-budget
 *       shadow lane Phase 3 uses to bound weak-evidence cost.</li>
 * </ul>
 *
 * <p>Observability note: {@link SchedulerClass} is the source of
 * truth for the scheduler lane. The admission-facing
 * {@link QueuePriorityClass} label is still recorded per-row on
 * enqueue/dequeue so offline parsers can correlate admission reason
 * with scheduler lane, but the per-lane counters and the per-round
 * snapshot CSV are keyed by {@link SchedulerClass}.
 *
 * <p>A Phase 3 flag ({@link Config.Configuration#usePriorityTestPlanScheduler})
 * keeps the legacy single-FIFO behavior available for rollback and
 * offline A/B tests.
 */
public class TestPlanCorpus {
    private static final Logger logger = LogManager
            .getLogger(TestPlanCorpus.class);

    private final EnumMap<SchedulerClass, Deque<QueuedTestPlan>> queues = new EnumMap<>(
            SchedulerClass.class);
    private final Deque<QueuedTestPlan> legacyFifo = new ArrayDeque<>();
    private final Map<String, QueuedTestPlan> signatureIndex = new HashMap<>();

    // Round-robin deficit counters for weighted scheduling
    private final EnumMap<SchedulerClass, Integer> deficits = new EnumMap<>(
            SchedulerClass.class);

    // Phase 3 decay bookkeeping — count dequeues per lineage root so a
    // plan that keeps getting selected without any downstream payoff
    // can be demoted one class.
    private final Map<Integer, Integer> dequeuesPerLineage = new HashMap<>();

    private final ObservabilityMetrics observabilityMetrics;

    public TestPlanCorpus() {
        this(null);
    }

    public TestPlanCorpus(ObservabilityMetrics observabilityMetrics) {
        this.observabilityMetrics = observabilityMetrics;
        for (SchedulerClass c : SchedulerClass.values()) {
            queues.put(c, new ArrayDeque<>());
            deficits.put(c, 0);
        }
    }

    // === Size / occupancy ===

    public int size() {
        if (Config.getConf() != null
                && !Config.getConf().usePriorityTestPlanScheduler) {
            return legacyFifo.size();
        }
        int total = 0;
        for (Deque<QueuedTestPlan> q : queues.values()) {
            total += q.size();
        }
        return total;
    }

    public boolean isEmpty() {
        return size() == 0;
    }

    /** Live occupancy keyed by scheduler lane. */
    public Map<SchedulerClass, Integer> occupancyBySchedulerClass() {
        EnumMap<SchedulerClass, Integer> out = new EnumMap<>(
                SchedulerClass.class);
        for (SchedulerClass c : SchedulerClass.values()) {
            out.put(c, 0);
        }
        if (Config.getConf() != null
                && !Config.getConf().usePriorityTestPlanScheduler) {
            for (QueuedTestPlan entry : legacyFifo) {
                out.merge(entry.schedulerClass, 1, Integer::sum);
            }
            return out;
        }
        for (Map.Entry<SchedulerClass, Deque<QueuedTestPlan>> e : queues
                .entrySet()) {
            out.put(e.getKey(), e.getValue().size());
        }
        return out;
    }

    /**
     * Admission-facing occupancy (by {@link QueuePriorityClass}). Kept
     * for legacy callers; the Phase 3 scheduler snapshot now uses
     * {@link #occupancyBySchedulerClass()}.
     */
    public Map<QueuePriorityClass, Integer> occupancyByPriorityClass() {
        EnumMap<QueuePriorityClass, Integer> out = new EnumMap<>(
                QueuePriorityClass.class);
        for (QueuePriorityClass c : QueuePriorityClass.values()) {
            out.put(c, 0);
        }
        for (QueuedTestPlan entry : allEntries()) {
            out.merge(entry.priorityClass, 1, Integer::sum);
        }
        return out;
    }

    // === Legacy TestPlan-returning API (preserves Phase 0/1/2 callers) ===

    public TestPlan getTestPlan() {
        return getTestPlan(-1);
    }

    /**
     * Pop the next plan and emit a DEQUEUE {@link QueueActivityRow} with
     * the labels the plan was admitted under. When Phase 3 scheduling is
     * enabled, the plan is selected by strict-priority repro_confirm +
     * weighted round-robin, and carries a class-aware mutation budget.
     * When Phase 3 is disabled, the legacy FIFO ordering is preserved.
     */
    public TestPlan getTestPlan(long roundId) {
        QueuedTestPlan entry = pollQueuedTestPlan(roundId);
        return entry == null ? null : entry.plan;
    }

    /**
     * Phase 3 callers (FuzzingServer.fuzzRollingTestPlan) use this to
     * retrieve the full queued wrapper — they need the class and
     * mutation budget to record per-class spend.
     */
    public QueuedTestPlan pollQueuedTestPlan(long roundId) {
        QueuedTestPlan entry;
        if (Config.getConf() != null
                && !Config.getConf().usePriorityTestPlanScheduler) {
            entry = legacyFifo.poll();
        } else {
            entry = pollStratified();
        }
        if (entry == null) {
            return null;
        }
        entry.dequeueCount++;
        dequeuesPerLineage.merge(entry.lineageRoot, 1, Integer::sum);
        if (signatureIndex.get(entry.compactSignature) == entry) {
            signatureIndex.remove(entry.compactSignature);
        }
        if (observabilityMetrics != null) {
            int budget = entry.plannedMutationBudget;
            observabilityMetrics.recordQueueActivity(new QueueActivityRow(
                    roundId,
                    entry.enqueueTestId,
                    entry.lineageRoot,
                    QueueActivityRow.Action.DEQUEUE,
                    entry.admissionReason,
                    entry.traceEvidenceStrength,
                    entry.candidateStrength,
                    entry.priorityClass,
                    entry.schedulerClass,
                    budget));
            observabilityMetrics.recordSchedulerDequeue(entry.schedulerClass);
            observabilityMetrics.recordSchedulerMutationBudgetSpent(
                    entry.schedulerClass, budget);
        }
        return entry;
    }

    /**
     * Legacy unlabeled add — retained for callers that do not compute
     * admission-confidence labels. Routed through the Phase 3 class
     * mapper with {@link QueuePriorityClass#UNKNOWN}; UNKNOWN lands in
     * the branch-scout queue by default because branch exploration must
     * never be dropped, even for unlabeled plans.
     */
    public boolean addTestPlan(TestPlan testPlan) {
        return addTestPlan(
                testPlan,
                -1L,
                testPlan != null ? testPlan.lineageTestId : -1,
                AdmissionReason.UNKNOWN,
                TraceEvidenceStrength.NONE,
                StructuredCandidateStrength.NONE,
                QueuePriorityClass.UNKNOWN);
    }

    /**
     * Label-aware enqueue. Returns true when the plan is admitted to
     * the scheduler (including dedup collapse). False means the plan
     * was dropped because the target queue is full and the new plan
     * scored lower than every existing entry.
     */
    public boolean addTestPlan(TestPlan testPlan,
            long roundId,
            int lineageRoot,
            AdmissionReason admissionReason,
            TraceEvidenceStrength traceEvidenceStrength,
            StructuredCandidateStrength structuredCandidateStrength,
            QueuePriorityClass queuePriorityClass) {
        SchedulerClass schedClass = mapToSchedulerClass(queuePriorityClass);
        return enqueue(testPlan,
                roundId,
                lineageRoot,
                admissionReason,
                traceEvidenceStrength,
                structuredCandidateStrength,
                queuePriorityClass,
                schedClass,
                false);
    }

    /**
     * Phase 3 candidate parents (strong structured candidates feeding
     * the repro-confirm lane) are promoted to
     * {@link SchedulerClass#REPRO_CONFIRM} explicitly via this entry
     * point. The admission-facing priority class is preserved as-is —
     * only the internal scheduler lane is lifted.
     */
    public boolean addCandidateParent(TestPlan testPlan,
            long roundId,
            int lineageRoot,
            AdmissionReason admissionReason,
            TraceEvidenceStrength traceEvidenceStrength,
            StructuredCandidateStrength structuredCandidateStrength,
            QueuePriorityClass queuePriorityClass) {
        return enqueue(testPlan,
                roundId,
                lineageRoot,
                admissionReason,
                traceEvidenceStrength,
                structuredCandidateStrength,
                queuePriorityClass,
                SchedulerClass.REPRO_CONFIRM,
                true);
    }

    private boolean enqueue(TestPlan testPlan,
            long roundId,
            int lineageRoot,
            AdmissionReason admissionReason,
            TraceEvidenceStrength traceEvidenceStrength,
            StructuredCandidateStrength structuredCandidateStrength,
            QueuePriorityClass queuePriorityClass,
            SchedulerClass schedClass,
            boolean candidateParent) {
        int testPacketId = testPlan != null ? testPlan.lineageTestId : -1;
        boolean phase3Enabled = Config.getConf() == null
                || Config.getConf().usePriorityTestPlanScheduler;
        // Phase 3 dedup key = lineageRoot + plan skeleton, so two
        // independent parents producing the same skeleton are NOT
        // collapsed into a single queue entry. The phase plan
        // explicitly asks for lineage-root-aware dedup.
        boolean dedupEnabled = phase3Enabled
                && Config.getConf() != null
                && Config.getConf().enableTestPlanCompactDedup;
        String signature = dedupEnabled && testPlan != null
                ? testPlan.compactSignature(lineageRoot)
                : ("pkt:" + testPacketId);
        int plannedBudget = resolveMutationBudget(schedClass);
        double initialScore = candidateParent
                ? 10.0
                : initialScoreFor(schedClass, traceEvidenceStrength,
                        structuredCandidateStrength);

        QueuedTestPlan entry = new QueuedTestPlan(
                testPlan,
                testPacketId,
                lineageRoot,
                admissionReason,
                traceEvidenceStrength,
                structuredCandidateStrength,
                queuePriorityClass,
                schedClass,
                roundId,
                plannedBudget,
                signature,
                initialScore);

        // Legacy rollback: pure FIFO, no dedup, no class routing.
        // Rollback must reproduce Phase 0 behavior exactly, so a
        // duplicate plan still enqueues as a second entry.
        if (!phase3Enabled) {
            legacyFifo.add(entry);
            emitEnqueueRow(entry, roundId, testPacketId, lineageRoot,
                    plannedBudget);
            return true;
        }

        if (dedupEnabled) {
            QueuedTestPlan existing = signatureIndex.get(signature);
            if (existing != null && existing.plan != null) {
                return handleDedupCollision(existing, entry, roundId,
                        testPacketId, lineageRoot, schedClass,
                        candidateParent);
            }
        }

        Deque<QueuedTestPlan> target = queues.get(schedClass);
        int capacity = resolveCapacity(schedClass);
        if (capacity > 0 && target.size() >= capacity) {
            if (!evictLowestScore(target, entry.score)) {
                logger.debug(
                        "TestPlanCorpus {} queue full (cap={}), dropping plan testPacketId={}",
                        schedClass, capacity, testPacketId);
                return false;
            }
        }
        target.add(entry);
        signatureIndex.put(signature, entry);
        emitEnqueueRow(entry, roundId, testPacketId, lineageRoot,
                plannedBudget);
        return true;
    }

    /**
     * Handle a compact-signature dedup collision.
     *
     * <p>Strength ladder is the {@link SchedulerClass} ordinal: lower
     * ordinal = stronger ({@code REPRO_CONFIRM < MAIN_EXPLOIT <
     * BRANCH_SCOUT < SHADOW_EVAL}). Candidate parents always win
     * because they ask for the strict-priority {@code REPRO_CONFIRM}
     * lane explicitly.
     *
     * <p>When the incoming admission is stronger, move the existing
     * entry from its current lane to the stronger lane and overwrite
     * its admission labels. When it is weaker (or equal), just bump
     * score on the existing entry — a stronger queued entry must
     * never be demoted by a later weaker re-admission.
     */
    private boolean handleDedupCollision(QueuedTestPlan existing,
            QueuedTestPlan incoming,
            long roundId,
            int testPacketId,
            int lineageRoot,
            SchedulerClass incomingClass,
            boolean candidateParent) {
        boolean shouldPromote = candidateParent
                || incomingClass.ordinal() < existing.schedulerClass
                        .ordinal();
        if (!shouldPromote) {
            existing.onDedupCollision();
            if (observabilityMetrics != null) {
                observabilityMetrics.recordSchedulerDedupCollision(
                        existing.schedulerClass);
                observabilityMetrics
                        .recordQueueActivity(new QueueActivityRow(
                                roundId,
                                testPacketId,
                                lineageRoot,
                                QueueActivityRow.Action.ENQUEUE,
                                existing.admissionReason,
                                existing.traceEvidenceStrength,
                                existing.candidateStrength,
                                existing.priorityClass,
                                existing.schedulerClass,
                                existing.plannedMutationBudget));
                observabilityMetrics.recordSchedulerEnqueue(
                        existing.schedulerClass);
            }
            return true;
        }

        // Promote: move the existing entry to the stronger lane and
        // overwrite its admission labels so the CSV row on the next
        // dequeue reflects the strongest admission that touched it.
        SchedulerClass oldClass = existing.schedulerClass;
        Deque<QueuedTestPlan> newQueue = queues.get(incomingClass);
        int newCapacity = resolveCapacity(incomingClass);
        double promotedScore = Math.max(existing.score, incoming.score)
                + 0.5;
        if (newCapacity > 0 && newQueue.size() >= newCapacity
                && queues.get(oldClass) != newQueue) {
            if (!evictLowestScore(newQueue, promotedScore)) {
                // Cannot fit in the stronger lane — leave the existing
                // entry where it is and fall back to a score bump.
                existing.onDedupCollision();
                if (observabilityMetrics != null) {
                    observabilityMetrics.recordSchedulerDedupCollision(
                            existing.schedulerClass);
                }
                return true;
            }
        }

        Deque<QueuedTestPlan> oldQueue = queues.get(oldClass);
        if (oldQueue != null) {
            oldQueue.remove(existing);
        }

        int newBudget = resolveMutationBudget(incomingClass);
        existing.promoteTo(incomingClass, newBudget,
                incoming.priorityClass,
                incoming.admissionReason,
                incoming.traceEvidenceStrength,
                incoming.candidateStrength,
                incoming.score);

        if (oldQueue != newQueue) {
            newQueue.add(existing);
        } else {
            // Same lane — nothing to move, just re-insert for the
            // within-queue score ordering to re-evaluate.
            newQueue.add(existing);
        }

        if (observabilityMetrics != null) {
            observabilityMetrics.recordSchedulerDedupCollision(oldClass);
            observabilityMetrics
                    .recordQueueActivity(new QueueActivityRow(
                            roundId,
                            testPacketId,
                            lineageRoot,
                            QueueActivityRow.Action.ENQUEUE,
                            existing.admissionReason,
                            existing.traceEvidenceStrength,
                            existing.candidateStrength,
                            existing.priorityClass,
                            existing.schedulerClass,
                            existing.plannedMutationBudget));
            observabilityMetrics.recordSchedulerEnqueue(
                    existing.schedulerClass);
        }
        return true;
    }

    private void emitEnqueueRow(QueuedTestPlan entry,
            long roundId,
            int testPacketId,
            int lineageRoot,
            int plannedBudget) {
        if (observabilityMetrics == null) {
            return;
        }
        observabilityMetrics.recordQueueActivity(new QueueActivityRow(
                roundId,
                testPacketId,
                lineageRoot,
                QueueActivityRow.Action.ENQUEUE,
                entry.admissionReason,
                entry.traceEvidenceStrength,
                entry.candidateStrength,
                entry.priorityClass,
                entry.schedulerClass,
                plannedBudget));
        observabilityMetrics.recordSchedulerEnqueue(entry.schedulerClass);
    }

    // === Phase 3 payoff / decay hooks ===

    /**
     * Credit any queued plan descended from {@code lineageRoot} with a
     * branch payoff. Updates scores so the scheduler prefers plans that
     * have already produced novelty. Payoff is also forwarded to the
     * scheduler observability counters, keyed by the plan's current
     * scheduler class.
     */
    public void notifyBranchPayoff(int lineageRoot) {
        if (lineageRoot < 0) {
            return;
        }
        for (QueuedTestPlan entry : allEntries()) {
            if (entry.lineageRoot == lineageRoot) {
                entry.onBranchPayoff();
                if (observabilityMetrics != null) {
                    observabilityMetrics
                            .recordSchedulerBranchPayoff(entry.schedulerClass);
                }
            }
        }
    }

    public void notifyStrongCandidatePayoff(int lineageRoot) {
        if (lineageRoot < 0) {
            return;
        }
        for (QueuedTestPlan entry : allEntries()) {
            if (entry.lineageRoot == lineageRoot) {
                entry.onStrongCandidatePayoff();
                if (observabilityMetrics != null) {
                    observabilityMetrics.recordSchedulerStrongPayoff(
                            entry.schedulerClass);
                }
            }
        }
    }

    public void notifyWeakCandidatePayoff(int lineageRoot) {
        if (lineageRoot < 0) {
            return;
        }
        for (QueuedTestPlan entry : allEntries()) {
            if (entry.lineageRoot == lineageRoot) {
                entry.onWeakCandidatePayoff();
                if (observabilityMetrics != null) {
                    observabilityMetrics.recordSchedulerWeakPayoff(
                            entry.schedulerClass);
                }
            }
        }
    }

    /**
     * Phase 3 decay sweep. Runs at the end of every differential round.
     * Any queued plan whose {@code lineageRoot} has been dequeued
     * {@code testPlanDequeueDecayThreshold} times without any
     * downstream payoff credit gets demoted one class (or dropped from
     * {@link SchedulerClass#SHADOW_EVAL}).
     *
     * <p>The ladder is {@code REPRO_CONFIRM -> MAIN_EXPLOIT ->
     * BRANCH_SCOUT -> SHADOW_EVAL -> drop} and is computed from the
     * plan's <em>current</em> {@link SchedulerClass} (the iteration
     * key), not from its admission-facing priority class. The two can
     * diverge — a plan admitted as {@code BRANCH_ONLY} that was
     * promoted to {@code REPRO_CONFIRM} must decay into
     * {@code MAIN_EXPLOIT} on the first demotion, not into
     * {@code SHADOW_EVAL} the priority-class mapper would produce.
     *
     * <p>Queues are processed in reverse enum order so a demoted entry
     * lands in an already-processed queue and cannot cascade to a
     * second demotion in the same sweep.
     */
    public void decayStaleEntries() {
        if (Config.getConf() == null) {
            return;
        }
        int threshold = Config.getConf().testPlanDequeueDecayThreshold;
        if (threshold <= 0) {
            return;
        }
        if (!Config.getConf().usePriorityTestPlanScheduler) {
            return;
        }
        SchedulerClass[] classes = SchedulerClass.values();
        // Reverse order: SHADOW_EVAL, BRANCH_SCOUT, MAIN_EXPLOIT,
        // REPRO_CONFIRM. A demoted entry lands in an already-visited
        // lane so it will not be double-decayed in the same sweep.
        for (int i = classes.length - 1; i >= 0; i--) {
            SchedulerClass source = classes[i];
            Deque<QueuedTestPlan> sourceQueue = queues.get(source);
            Iterator<QueuedTestPlan> it = sourceQueue.iterator();
            while (it.hasNext()) {
                QueuedTestPlan entry = it.next();
                int dequeues = dequeuesPerLineage.getOrDefault(
                        entry.lineageRoot, 0);
                if (entry.payoffCredits > 0 || dequeues < threshold) {
                    continue;
                }
                SchedulerClass demoted = demote(source);
                if (demoted == null) {
                    it.remove();
                    if (signatureIndex.get(entry.compactSignature) == entry) {
                        signatureIndex.remove(entry.compactSignature);
                    }
                    if (observabilityMetrics != null) {
                        observabilityMetrics.recordSchedulerDecayDemotion(
                                source);
                    }
                    continue;
                }
                if (demoted == source) {
                    continue;
                }
                it.remove();
                entry.schedulerClass = demoted;
                entry.plannedMutationBudget = resolveMutationBudget(demoted);
                entry.score = Math.max(0.0, entry.score - 1.0);
                queues.get(demoted).add(entry);
                if (observabilityMetrics != null) {
                    // Record the demotion against the SOURCE lane so
                    // the counter reflects "how much decay happened
                    // out of each lane", not "into each lane".
                    observabilityMetrics
                            .recordSchedulerDecayDemotion(source);
                }
            }
        }
    }

    // === Internals ===

    private Collection<QueuedTestPlan> allEntries() {
        List<QueuedTestPlan> all = new ArrayList<>();
        if (Config.getConf() != null
                && !Config.getConf().usePriorityTestPlanScheduler) {
            all.addAll(legacyFifo);
            return all;
        }
        for (Deque<QueuedTestPlan> q : queues.values()) {
            all.addAll(q);
        }
        return all;
    }

    private QueuedTestPlan pollStratified() {
        // Strict priority: when a strong structured candidate is
        // queued in repro_confirm, always drain it first so the
        // scheduler spends its next slot re-observing a real-bug
        // candidate instead of generic branch/trace exploration.
        Deque<QueuedTestPlan> reproQueue = queues
                .get(SchedulerClass.REPRO_CONFIRM);
        if (reproQueue != null && !reproQueue.isEmpty()) {
            return takeBestFromClass(SchedulerClass.REPRO_CONFIRM);
        }

        // Deficit-based weighted round-robin across the remaining
        // classes: for every non-empty queue, add its weight to the
        // deficit; pick the queue with the highest deficit; decrement
        // by its weight so subsequent picks rotate fairly.
        int[] weights = resolveWeights();
        SchedulerClass[] wrrClasses = new SchedulerClass[] {
                SchedulerClass.MAIN_EXPLOIT,
                SchedulerClass.BRANCH_SCOUT,
                SchedulerClass.SHADOW_EVAL
        };
        int totalWeight = 0;
        for (SchedulerClass c : wrrClasses) {
            totalWeight += weights[c.ordinal()];
        }
        SchedulerClass pick = null;
        if (totalWeight > 0) {
            for (SchedulerClass c : wrrClasses) {
                if (queues.get(c).isEmpty()) {
                    continue;
                }
                deficits.merge(c, weights[c.ordinal()], Integer::sum);
            }
            int best = Integer.MIN_VALUE;
            for (SchedulerClass c : wrrClasses) {
                if (queues.get(c).isEmpty()) {
                    continue;
                }
                int d = deficits.getOrDefault(c, 0);
                if (d > best) {
                    best = d;
                    pick = c;
                }
            }
            if (pick != null) {
                deficits.merge(pick, -Math.max(1, weights[pick.ordinal()]),
                        Integer::sum);
            }
        }
        if (pick == null) {
            // Fallback: first non-empty WRR class in class order
            for (SchedulerClass c : wrrClasses) {
                if (!queues.get(c).isEmpty()) {
                    pick = c;
                    break;
                }
            }
        }
        if (pick == null) {
            return null;
        }
        return takeBestFromClass(pick);
    }

    private QueuedTestPlan takeBestFromClass(SchedulerClass c) {
        Deque<QueuedTestPlan> q = queues.get(c);
        if (q.isEmpty()) {
            return null;
        }
        QueuedTestPlan bestEntry = null;
        double bestScore = Double.NEGATIVE_INFINITY;
        long bestAge = Long.MAX_VALUE;
        for (QueuedTestPlan entry : q) {
            if (entry.score > bestScore
                    || (entry.score == bestScore
                            && entry.enqueueRound < bestAge)) {
                bestScore = entry.score;
                bestAge = entry.enqueueRound;
                bestEntry = entry;
            }
        }
        if (bestEntry != null) {
            q.remove(bestEntry);
        }
        return bestEntry;
    }

    private int[] resolveWeights() {
        Config.Configuration cfg = Config.getConf();
        int[] w = new int[SchedulerClass.values().length];
        if (cfg == null) {
            w[SchedulerClass.REPRO_CONFIRM.ordinal()] = 4;
            w[SchedulerClass.MAIN_EXPLOIT.ordinal()] = 8;
            w[SchedulerClass.BRANCH_SCOUT.ordinal()] = 3;
            w[SchedulerClass.SHADOW_EVAL.ordinal()] = 1;
            return w;
        }
        w[SchedulerClass.REPRO_CONFIRM.ordinal()] = Math.max(0,
                cfg.reproConfirmQueueWeight);
        w[SchedulerClass.MAIN_EXPLOIT.ordinal()] = Math.max(0,
                cfg.mainExploitQueueWeight);
        w[SchedulerClass.BRANCH_SCOUT.ordinal()] = Math.max(0,
                cfg.branchScoutQueueWeight);
        w[SchedulerClass.SHADOW_EVAL.ordinal()] = Math.max(0,
                cfg.shadowEvalQueueWeight);
        return w;
    }

    private int resolveCapacity(SchedulerClass c) {
        Config.Configuration cfg = Config.getConf();
        if (cfg == null) {
            return 0;
        }
        switch (c) {
        case REPRO_CONFIRM:
            return cfg.reproConfirmQueueMaxSize;
        case MAIN_EXPLOIT:
            return cfg.mainExploitQueueMaxSize;
        case BRANCH_SCOUT:
            return cfg.branchScoutQueueMaxSize;
        case SHADOW_EVAL:
            return cfg.shadowEvalQueueMaxSize;
        default:
            return 0;
        }
    }

    /** Evict the lowest-scoring entry below {@code threshold}. */
    private boolean evictLowestScore(Deque<QueuedTestPlan> q,
            double threshold) {
        QueuedTestPlan worst = null;
        for (QueuedTestPlan entry : q) {
            if (worst == null || entry.score < worst.score) {
                worst = entry;
            }
        }
        if (worst == null || worst.score > threshold) {
            return false;
        }
        q.remove(worst);
        if (signatureIndex.get(worst.compactSignature) == worst) {
            signatureIndex.remove(worst.compactSignature);
        }
        return true;
    }

    /**
     * Resolve the mutation budget for a scheduler class. Falls back to
     * the legacy {@code testPlanMutationEpoch} when Phase 3 is off or
     * when the per-class knob is unset.
     */
    public static int resolveMutationBudget(SchedulerClass c) {
        Config.Configuration cfg = Config.getConf();
        if (cfg == null) {
            return 20;
        }
        if (!cfg.usePriorityTestPlanScheduler) {
            return cfg.testPlanMutationEpoch;
        }
        switch (c) {
        case REPRO_CONFIRM:
            return cfg.reproConfirmMutationEpoch > 0
                    ? cfg.reproConfirmMutationEpoch
                    : cfg.testPlanMutationEpoch;
        case MAIN_EXPLOIT:
            return cfg.mainExploitMutationEpoch > 0
                    ? cfg.mainExploitMutationEpoch
                    : cfg.testPlanMutationEpoch;
        case BRANCH_SCOUT:
            return cfg.branchScoutMutationEpoch > 0
                    ? cfg.branchScoutMutationEpoch
                    : cfg.testPlanMutationEpoch;
        case SHADOW_EVAL:
            return cfg.shadowEvalMutationEpoch;
        default:
            return cfg.testPlanMutationEpoch;
        }
    }

    public static int resolveMutationBudgetForPriorityClass(
            QueuePriorityClass pc) {
        return resolveMutationBudget(mapToSchedulerClass(pc));
    }

    /**
     * Map the observability-level {@link QueuePriorityClass} onto the
     * internal scheduler lane. This is the single source of truth for
     * how Phase 1/2 labels flow into Phase 3 scheduling.
     */
    public static SchedulerClass mapToSchedulerClass(
            QueuePriorityClass priorityClass) {
        if (priorityClass == null) {
            return SchedulerClass.BRANCH_SCOUT;
        }
        switch (priorityClass) {
        case BRANCH_AND_STRONG_TRACE:
            return SchedulerClass.MAIN_EXPLOIT;
        case BRANCH_ONLY:
            return SchedulerClass.BRANCH_SCOUT;
        case BRANCH_AND_WEAK_TRACE:
        case TRACE_ONLY_STRONG:
        case TRACE_ONLY_WEAK:
            return SchedulerClass.SHADOW_EVAL;
        case UNKNOWN:
        default:
            // Unlabeled admissions default to the branch scout lane so
            // branch exploration stays alive even when upstream labels
            // are missing.
            return SchedulerClass.BRANCH_SCOUT;
        }
    }

    private static double initialScoreFor(SchedulerClass schedClass,
            TraceEvidenceStrength traceStrength,
            StructuredCandidateStrength candStrength) {
        double base;
        switch (schedClass) {
        case REPRO_CONFIRM:
            base = 8.0;
            break;
        case MAIN_EXPLOIT:
            base = 5.0;
            break;
        case BRANCH_SCOUT:
            base = 3.0;
            break;
        case SHADOW_EVAL:
        default:
            base = 1.0;
            break;
        }
        if (traceStrength == TraceEvidenceStrength.STRONG) {
            base += 1.0;
        } else if (traceStrength == TraceEvidenceStrength.WEAK) {
            base += 0.25;
        }
        if (candStrength == StructuredCandidateStrength.STRONG) {
            base += 2.0;
        }
        return base;
    }

    /**
     * Demotion ladder used by decay:
     * repro_confirm -> main_exploit -> branch_scout -> shadow_eval -> drop.
     * The branch scout lane exists precisely so decayed main_exploit
     * plans still get a chance to rediscover coverage before sinking
     * into the shadow lane.
     */
    private static SchedulerClass demote(SchedulerClass c) {
        if (c == null) {
            return null;
        }
        switch (c) {
        case REPRO_CONFIRM:
            return SchedulerClass.MAIN_EXPLOIT;
        case MAIN_EXPLOIT:
            return SchedulerClass.BRANCH_SCOUT;
        case BRANCH_SCOUT:
            return SchedulerClass.SHADOW_EVAL;
        case SHADOW_EVAL:
        default:
            return null;
        }
    }
}
