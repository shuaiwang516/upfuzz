package org.zlab.upfuzz.fuzzingengine.server;

import java.util.ArrayDeque;
import java.util.Deque;

import org.zlab.upfuzz.fuzzingengine.Config;
import org.zlab.upfuzz.fuzzingengine.server.observability.AdmissionReason;
import org.zlab.upfuzz.fuzzingengine.server.observability.ObservabilityMetrics;
import org.zlab.upfuzz.fuzzingengine.server.observability.QueueActivityRow;
import org.zlab.upfuzz.fuzzingengine.server.observability.QueuePriorityClass;
import org.zlab.upfuzz.fuzzingengine.server.observability.StructuredCandidateStrength;
import org.zlab.upfuzz.fuzzingengine.server.observability.TraceEvidenceStrength;
import org.zlab.upfuzz.fuzzingengine.testplan.TestPlan;

/**
 * Short-term FIFO queue of test plans awaiting mutation.
 *
 * <p>Phase 0 telemetry: every enqueue and every dequeue emits a
 * {@link QueueActivityRow} so offline analyses can see how much queue
 * energy each admission class consumes. The scheduler itself is still
 * a plain FIFO — Phase 3 will consume the labels on the emitted rows.
 *
 * <p>Admission labels are attached at enqueue time and replayed at
 * dequeue time so the DEQUEUE row carries the same class the plan was
 * admitted under.
 */
public class TestPlanCorpus {
    /**
     * Single-item wrapper that carries the Phase 0 admission labels
     * alongside the queued plan. Not exposed outside this class — the
     * server polls {@link TestPlan} instances.
     */
    private static final class Entry {
        final TestPlan testPlan;
        final int testPacketId;
        final int lineageRoot;
        final AdmissionReason admissionReason;
        final TraceEvidenceStrength traceEvidenceStrength;
        final StructuredCandidateStrength structuredCandidateStrength;
        final QueuePriorityClass queuePriorityClass;

        Entry(TestPlan testPlan,
                int testPacketId,
                int lineageRoot,
                AdmissionReason admissionReason,
                TraceEvidenceStrength traceEvidenceStrength,
                StructuredCandidateStrength structuredCandidateStrength,
                QueuePriorityClass queuePriorityClass) {
            this.testPlan = testPlan;
            this.testPacketId = testPacketId;
            this.lineageRoot = lineageRoot;
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
        }
    }

    private final Deque<Entry> queue = new ArrayDeque<>();
    private final ObservabilityMetrics observabilityMetrics;

    public TestPlanCorpus() {
        this(null);
    }

    public TestPlanCorpus(ObservabilityMetrics observabilityMetrics) {
        this.observabilityMetrics = observabilityMetrics;
    }

    public int size() {
        return queue.size();
    }

    public boolean isEmpty() {
        return queue.isEmpty();
    }

    public TestPlan getTestPlan() {
        return getTestPlan(-1);
    }

    /**
     * Pop the next plan and emit a DEQUEUE {@link QueueActivityRow} with
     * the labels the plan was admitted under and the currently-configured
     * mutation budget.
     */
    public TestPlan getTestPlan(long roundId) {
        Entry entry = queue.poll();
        if (entry == null) {
            return null;
        }
        if (observabilityMetrics != null) {
            int plannedMutationBudget = Config
                    .getConf().testPlanMutationEpoch;
            observabilityMetrics.recordQueueActivity(new QueueActivityRow(
                    roundId,
                    entry.testPacketId,
                    entry.lineageRoot,
                    QueueActivityRow.Action.DEQUEUE,
                    entry.admissionReason,
                    entry.traceEvidenceStrength,
                    entry.structuredCandidateStrength,
                    entry.queuePriorityClass,
                    plannedMutationBudget));
        }
        return entry.testPlan;
    }

    /**
     * Legacy unlabeled add — retained for the stacked-tests full-stop
     * path which does not compute admission-confidence labels. Emits a
     * row with {@link QueuePriorityClass#UNKNOWN}.
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
     * Label-aware enqueue. Used by the rolling differential path so the
     * dequeue-side DEQUEUE row carries the same labels as the ENQUEUE row.
     */
    public boolean addTestPlan(TestPlan testPlan,
            long roundId,
            int lineageRoot,
            AdmissionReason admissionReason,
            TraceEvidenceStrength traceEvidenceStrength,
            StructuredCandidateStrength structuredCandidateStrength,
            QueuePriorityClass queuePriorityClass) {
        int testPacketId = testPlan != null ? testPlan.lineageTestId : -1;
        Entry entry = new Entry(testPlan, testPacketId, lineageRoot,
                admissionReason, traceEvidenceStrength,
                structuredCandidateStrength, queuePriorityClass);
        queue.add(entry);
        if (observabilityMetrics != null) {
            observabilityMetrics.recordQueueActivity(new QueueActivityRow(
                    roundId,
                    testPacketId,
                    lineageRoot,
                    QueueActivityRow.Action.ENQUEUE,
                    entry.admissionReason,
                    entry.traceEvidenceStrength,
                    entry.structuredCandidateStrength,
                    entry.queuePriorityClass,
                    -1));
        }
        return true;
    }
}
