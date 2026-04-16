package org.zlab.upfuzz.fuzzingengine.server.observability;

/**
 * One row per short-term queue transition (enqueue or dequeue).
 *
 * <p>Phase 0 schema: the {@code queue_priority_class} column records
 * the admission-facing label (branch only, branch + strong trace,
 * trace-only weak, …). Phase 3 adds a second label
 * {@code scheduler_class} — the internal lane the plan lives in
 * (repro_confirm, main_exploit, branch_scout, shadow_eval). The two
 * can diverge: for example, a plan admitted as {@code BRANCH_ONLY} can
 * be routed into {@code REPRO_CONFIRM} when it is promoted as a
 * strong structured candidate parent, and a {@code BRANCH_AND_STRONG_TRACE}
 * plan can decay down to {@code SHADOW_EVAL} after repeated dequeues
 * with no payoff. Recording both lets offline analyses reconstruct
 * the full scheduler state transition history from the CSV alone.
 *
 * <p>{@code plannedMutationBudget} reflects the mutation epoch the
 * dequeued plan is about to consume. For enqueues (which do not yet have
 * a chosen budget) the field is set to {@code -1}.
 */
public final class QueueActivityRow {
    public enum Action {
        ENQUEUE, DEQUEUE
    }

    public final long roundId;
    public final int testPacketId;
    public final int lineageRoot;
    public final Action action;
    public final AdmissionReason admissionReason;
    public final TraceEvidenceStrength traceEvidenceStrength;
    public final StructuredCandidateStrength structuredCandidateStrength;
    public final QueuePriorityClass queuePriorityClass;
    public final SchedulerClass schedulerClass;
    public final int plannedMutationBudget;

    public QueueActivityRow(
            long roundId,
            int testPacketId,
            int lineageRoot,
            Action action,
            AdmissionReason admissionReason,
            TraceEvidenceStrength traceEvidenceStrength,
            StructuredCandidateStrength structuredCandidateStrength,
            QueuePriorityClass queuePriorityClass,
            SchedulerClass schedulerClass,
            int plannedMutationBudget) {
        this.roundId = roundId;
        this.testPacketId = testPacketId;
        this.lineageRoot = lineageRoot;
        this.action = action == null ? Action.ENQUEUE : action;
        this.admissionReason = admissionReason == null
                ? AdmissionReason.UNKNOWN
                : admissionReason;
        this.traceEvidenceStrength = traceEvidenceStrength == null
                ? TraceEvidenceStrength.NONE
                : traceEvidenceStrength;
        this.structuredCandidateStrength = structuredCandidateStrength == null
                ? StructuredCandidateStrength.NONE
                : structuredCandidateStrength;
        this.queuePriorityClass = queuePriorityClass == null
                ? QueuePriorityClass.UNKNOWN
                : queuePriorityClass;
        this.schedulerClass = schedulerClass == null
                ? SchedulerClass.BRANCH_SCOUT
                : schedulerClass;
        this.plannedMutationBudget = plannedMutationBudget;
    }

    public static String csvHeader() {
        return String.join(",",
                "round_id",
                "test_packet_id",
                "lineage_root",
                "enqueue_or_dequeue",
                "admission_reason",
                "trace_evidence_strength",
                "structured_candidate_strength",
                "queue_priority_class",
                "scheduler_class",
                "planned_mutation_budget");
    }

    public String toCsvRow() {
        StringBuilder sb = new StringBuilder();
        sb.append(roundId).append(',');
        sb.append(testPacketId).append(',');
        sb.append(lineageRoot).append(',');
        sb.append(action.name()).append(',');
        sb.append(admissionReason.name()).append(',');
        sb.append(traceEvidenceStrength.name()).append(',');
        sb.append(structuredCandidateStrength.name()).append(',');
        sb.append(queuePriorityClass.name()).append(',');
        sb.append(schedulerClass.name()).append(',');
        sb.append(plannedMutationBudget);
        return sb.toString();
    }
}
