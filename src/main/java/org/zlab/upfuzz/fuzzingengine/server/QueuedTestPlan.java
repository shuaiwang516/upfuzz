package org.zlab.upfuzz.fuzzingengine.server;

import org.zlab.upfuzz.fuzzingengine.server.observability.AdmissionReason;
import org.zlab.upfuzz.fuzzingengine.server.observability.QueuePriorityClass;
import org.zlab.upfuzz.fuzzingengine.server.observability.SchedulerClass;
import org.zlab.upfuzz.fuzzingengine.server.observability.StructuredCandidateStrength;
import org.zlab.upfuzz.fuzzingengine.server.observability.TraceEvidenceStrength;
import org.zlab.upfuzz.fuzzingengine.testplan.TestPlan;

/**
 * Phase 3 wrapper that carries scheduler metadata alongside a queued
 * {@link TestPlan}. Previously the short-term queue stored plans
 * implicitly via {@link TestPlan#lineageTestId} and an unlabeled
 * FIFO entry; Phase 3 promotes every queue entry into this explicit
 * record so the scheduler can reason about class, budget, and
 * dedup/decay state without round-tripping through {@code TestPlan}.
 *
 * <p>Two labels are tracked: {@link #priorityClass} is the
 * admission-facing reason the plan was saved
 * ({@link QueuePriorityClass}), while {@link #schedulerClass} is the
 * internal {@link SchedulerClass} lane the plan lives in. These can
 * diverge — for example, a plan admitted as {@code BRANCH_ONLY} can
 * be promoted into {@link SchedulerClass#REPRO_CONFIRM} when it
 * produced a strong structured candidate, and a plan originally in
 * {@link SchedulerClass#MAIN_EXPLOIT} can decay down to
 * {@link SchedulerClass#BRANCH_SCOUT} or
 * {@link SchedulerClass#SHADOW_EVAL} after repeated dequeues without
 * payoff. Decay writes back to {@link #schedulerClass} so
 * observability and payoff credits always reflect the current lane.
 */
public final class QueuedTestPlan {
    /** The plan that will be cloned and mutated at dequeue time. */
    public final TestPlan plan;

    /**
     * Test packet id captured at enqueue time. Mirrors the legacy
     * {@code TestPlan.lineageTestId} so offline parsers can join
     * admission-row events with queue-activity rows.
     */
    public final int enqueueTestId;

    /**
     * Root lineage id for this plan — the oldest ancestor still
     * present in {@link ObservabilityMetrics}. Used by Phase 3 dedup
     * and by the decay logic to credit descendant payoff back to the
     * enqueued wrapper.
     */
    public final int lineageRoot;

    /**
     * Admission labels describing the <em>most recent</em> admission
     * that touched this entry. Mutable because a stronger
     * re-admission (via compact-signature dedup) overwrites them
     * with the stronger labels so the scheduler, observability, and
     * CSV rows all reflect the current strength — otherwise a plan
     * admitted as {@code TRACE_ONLY_WEAK} would keep its weak label
     * even after a later admission rediscovered it with
     * {@code BRANCH_AND_STRONG_TRACE}.
     */
    public AdmissionReason admissionReason;
    public TraceEvidenceStrength traceEvidenceStrength;
    public StructuredCandidateStrength candidateStrength;
    public QueuePriorityClass priorityClass;

    /**
     * Internal scheduler lane. Mutable so decay can write back the
     * demoted lane on every demotion step, and so compact-dedup can
     * write back the <em>promoted</em> lane when a stronger
     * re-admission collides with a weaker queued entry. Both keep
     * observability and payoff credits in sync with the live
     * scheduler state.
     */
    public SchedulerClass schedulerClass;

    /**
     * Finished round id at the moment of enqueue. Used to age entries
     * when the scheduler needs a recency tiebreaker within a class.
     */
    public final long enqueueRound;

    /**
     * Mutation budget that should be consumed when this entry is
     * eventually dequeued. Resolved at enqueue time from the queue
     * class so offline replay can reconstruct scheduler decisions
     * without re-running the mapping.
     */
    public int plannedMutationBudget;

    /**
     * Compact signature used by the Phase 3 short-term dedup pass.
     * A duplicate admission that matches {@link #compactSignature}
     * bumps {@link #score} on this entry rather than inserting a
     * second, near-identical plan.
     */
    public final String compactSignature;

    /**
     * Number of times this entry has been dequeued so far. Phase 3
     * uses this together with downstream payoff to decay plans that
     * keep being selected but produce no new seeds.
     */
    public int dequeueCount;

    /**
     * Cumulative payoff credits (branch / strong / weak) attributed
     * to mutations that descended from this queued plan while it was
     * still on the queue. Payoff is counted as a score boost for the
     * eviction and decay passes but does not remove the entry.
     */
    public int payoffCredits;

    /**
     * Phase 3 selection score. Higher is better. Initialized from a
     * class-dependent base score and bumped by dedup collisions and
     * by downstream payoff credits. Class-level weighted round-robin
     * owns cross-queue ordering; this score decides within-queue
     * ordering.
     */
    public double score;

    /**
     * Phase 4 stage-focused mutation hint. Captured at admission time
     * in {@code FuzzingServer.updateStatus(...)} from the aligned
     * trace windows that drove the admission and consumed by the
     * {@link StageAwareTestPlanMutator}. Never {@code null} —
     * admissions without any firing window store {@link StageMutationHint#empty()}
     * so the mutator falls back to generic
     * {@link org.zlab.upfuzz.fuzzingengine.testplan.TestPlan#mutate mutate}.
     * Mutable so compact-signature dedup can overwrite the hint with
     * the stronger re-admission's information (same promotion semantics
     * as the admission labels).
     */
    public StageMutationHint stageMutationHint;

    /**
     * Phase 4 per-parent confirmation budget. When the queued entry
     * carries {@code needsConfirmation=true}, Phase 4 reserves a small
     * number of low-edit-distance / replay / minimization children
     * that are generated through
     * {@code FuzzingServer.spendConfirmationBudget(...)} instead of
     * going through the normal mutation epoch. Decremented each time
     * a confirmation child is emitted.
     */
    public int confirmationBudgetRemaining;

    public QueuedTestPlan(
            TestPlan plan,
            int enqueueTestId,
            int lineageRoot,
            AdmissionReason admissionReason,
            TraceEvidenceStrength traceEvidenceStrength,
            StructuredCandidateStrength candidateStrength,
            QueuePriorityClass priorityClass,
            SchedulerClass schedulerClass,
            long enqueueRound,
            int plannedMutationBudget,
            String compactSignature,
            double initialScore) {
        this(plan, enqueueTestId, lineageRoot, admissionReason,
                traceEvidenceStrength, candidateStrength, priorityClass,
                schedulerClass, enqueueRound, plannedMutationBudget,
                compactSignature, initialScore,
                StageMutationHint.empty(), 0);
    }

    public QueuedTestPlan(
            TestPlan plan,
            int enqueueTestId,
            int lineageRoot,
            AdmissionReason admissionReason,
            TraceEvidenceStrength traceEvidenceStrength,
            StructuredCandidateStrength candidateStrength,
            QueuePriorityClass priorityClass,
            SchedulerClass schedulerClass,
            long enqueueRound,
            int plannedMutationBudget,
            String compactSignature,
            double initialScore,
            StageMutationHint stageMutationHint,
            int confirmationBudgetRemaining) {
        this.plan = plan;
        this.enqueueTestId = enqueueTestId;
        this.lineageRoot = lineageRoot;
        this.admissionReason = admissionReason == null
                ? AdmissionReason.UNKNOWN
                : admissionReason;
        this.traceEvidenceStrength = traceEvidenceStrength == null
                ? TraceEvidenceStrength.NONE
                : traceEvidenceStrength;
        this.candidateStrength = candidateStrength == null
                ? StructuredCandidateStrength.NONE
                : candidateStrength;
        this.priorityClass = priorityClass == null
                ? QueuePriorityClass.UNKNOWN
                : priorityClass;
        this.schedulerClass = schedulerClass == null
                ? SchedulerClass.BRANCH_SCOUT
                : schedulerClass;
        this.enqueueRound = enqueueRound;
        this.plannedMutationBudget = plannedMutationBudget;
        this.compactSignature = compactSignature == null
                ? ""
                : compactSignature;
        this.dequeueCount = 0;
        this.payoffCredits = 0;
        this.score = initialScore;
        this.stageMutationHint = stageMutationHint == null
                ? StageMutationHint.empty()
                : stageMutationHint;
        this.confirmationBudgetRemaining = Math.max(0,
                confirmationBudgetRemaining);
    }

    /** Bump score on a dedup collision so popular skeletons rise. */
    public void onDedupCollision() {
        this.score += 0.5;
    }

    /**
     * Overwrite the admission labels and scheduler state with those of
     * a stronger re-admission. Called by
     * {@code TestPlanCorpus.enqueue} when a duplicate admission
     * arrives for a signature already in the short-term queue and the
     * new admission is stronger. The caller is responsible for
     * removing the entry from the old lane and inserting into the new
     * lane — this method only updates the record's own fields. Score
     * is taken as the max of the two so the promoted entry does not
     * lose accumulated payoff.
     */
    public void promoteTo(SchedulerClass newSchedulerClass,
            int newPlannedMutationBudget,
            QueuePriorityClass newPriorityClass,
            AdmissionReason newAdmissionReason,
            TraceEvidenceStrength newTraceEvidenceStrength,
            StructuredCandidateStrength newCandidateStrength,
            double incomingScore) {
        promoteTo(newSchedulerClass, newPlannedMutationBudget,
                newPriorityClass, newAdmissionReason,
                newTraceEvidenceStrength, newCandidateStrength,
                incomingScore, null, 0);
    }

    /**
     * Phase 4 overload: a stronger re-admission also carries a fresh
     * {@link StageMutationHint} and a new confirmation budget.
     * Hints are replaced (not merged) because the stronger round's
     * stage information is the more useful target for exploitation
     * mutation. Confirmation budget takes the max of the two so we
     * never lose a candidate parent's confirmation allocation to a
     * weaker later admission.
     */
    public void promoteTo(SchedulerClass newSchedulerClass,
            int newPlannedMutationBudget,
            QueuePriorityClass newPriorityClass,
            AdmissionReason newAdmissionReason,
            TraceEvidenceStrength newTraceEvidenceStrength,
            StructuredCandidateStrength newCandidateStrength,
            double incomingScore,
            StageMutationHint newStageMutationHint,
            int newConfirmationBudget) {
        if (newSchedulerClass != null) {
            this.schedulerClass = newSchedulerClass;
        }
        if (newPlannedMutationBudget > 0) {
            this.plannedMutationBudget = newPlannedMutationBudget;
        }
        if (newPriorityClass != null) {
            this.priorityClass = newPriorityClass;
        }
        if (newAdmissionReason != null) {
            this.admissionReason = newAdmissionReason;
        }
        if (newTraceEvidenceStrength != null && newTraceEvidenceStrength
                .ordinal() > this.traceEvidenceStrength.ordinal()) {
            this.traceEvidenceStrength = newTraceEvidenceStrength;
        }
        if (newCandidateStrength != null && newCandidateStrength
                .ordinal() > this.candidateStrength.ordinal()) {
            this.candidateStrength = newCandidateStrength;
        }
        if (newStageMutationHint != null
                && newStageMutationHint.hasStageInfo()) {
            this.stageMutationHint = newStageMutationHint;
        }
        if (newConfirmationBudget > this.confirmationBudgetRemaining) {
            this.confirmationBudgetRemaining = newConfirmationBudget;
        }
        this.score = Math.max(this.score, incomingScore) + 0.5;
    }

    /** Record a downstream branch payoff credit. */
    public void onBranchPayoff() {
        this.payoffCredits++;
        this.score += 1.0;
    }

    /** Record a downstream strong candidate payoff credit. */
    public void onStrongCandidatePayoff() {
        this.payoffCredits += 4;
        this.score += 4.0;
    }

    /** Record a weak candidate payoff credit. */
    public void onWeakCandidatePayoff() {
        this.payoffCredits++;
        this.score += 0.25;
    }
}
