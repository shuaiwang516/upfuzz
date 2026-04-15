package org.zlab.upfuzz.fuzzingengine.server.observability;

/**
 * One row per short-term queue transition (enqueue or dequeue).
 *
 * <p>Phase 0 telemetry only — the queue is still a plain FIFO. These rows
 * let offline analysis answer "how much queue energy did weak plans
 * consume?" before Phase 3 replaces the scheduler. Every enqueue and
 * every dequeue emits a row; the labels are the same as on the per-round
 * admission summary so rows can be joined by {@code testPacketId} offline.
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
        sb.append(plannedMutationBudget);
        return sb.toString();
    }
}
