package org.zlab.upfuzz.fuzzingengine.server.observability;

/**
 * One row per completed differential execution.
 *
 * <p>Captures what happened on a single round even when the round did not
 * result in a corpus addition. Together with {@link WindowTriggerRow} these
 * rows let an offline evaluator reproduce admission decisions under
 * alternative policies (e.g. the Phase 1 hotfix that disables missing-only
 * tri-diff admission) without re-running the campaign.
 *
 * <p>The reason column is {@link AdmissionReason#UNKNOWN} for rounds that
 * were evaluated but not admitted — those rounds are still useful because
 * they reveal which rule fired even when branch coverage gated out the
 * addition.
 */
public final class AdmissionSummaryRow {
    public final long executionIndex;
    public final int testPacketId;
    public final boolean admitted;
    public final AdmissionReason admissionReason;
    public final boolean newBranchCoverage;
    public final boolean traceInteresting;
    public final boolean triDiffExclusiveFired;
    public final boolean triDiffMissingFired;
    public final boolean windowSimFired;
    public final boolean aggregateSimFired;
    public final boolean structuredCandidate;
    public final boolean weakCandidate;
    public final int windowsEvaluated;
    public final String overallVerdict;
    public final long cumulativeBranchOnly;
    public final long cumulativeBranchAndTrace;
    public final long cumulativeTraceOnlyWindowSim;
    public final long cumulativeTraceOnlyTriDiffExclusive;
    public final long cumulativeTraceOnlyTriDiffMissing;

    public AdmissionSummaryRow(
            long executionIndex,
            int testPacketId,
            boolean admitted,
            AdmissionReason admissionReason,
            boolean newBranchCoverage,
            boolean traceInteresting,
            boolean triDiffExclusiveFired,
            boolean triDiffMissingFired,
            boolean windowSimFired,
            boolean aggregateSimFired,
            boolean structuredCandidate,
            boolean weakCandidate,
            int windowsEvaluated,
            String overallVerdict,
            long cumulativeBranchOnly,
            long cumulativeBranchAndTrace,
            long cumulativeTraceOnlyWindowSim,
            long cumulativeTraceOnlyTriDiffExclusive,
            long cumulativeTraceOnlyTriDiffMissing) {
        this.executionIndex = executionIndex;
        this.testPacketId = testPacketId;
        this.admitted = admitted;
        this.admissionReason = admissionReason == null
                ? AdmissionReason.UNKNOWN
                : admissionReason;
        this.newBranchCoverage = newBranchCoverage;
        this.traceInteresting = traceInteresting;
        this.triDiffExclusiveFired = triDiffExclusiveFired;
        this.triDiffMissingFired = triDiffMissingFired;
        this.windowSimFired = windowSimFired;
        this.aggregateSimFired = aggregateSimFired;
        this.structuredCandidate = structuredCandidate;
        this.weakCandidate = weakCandidate;
        this.windowsEvaluated = windowsEvaluated;
        this.overallVerdict = overallVerdict == null ? "" : overallVerdict;
        this.cumulativeBranchOnly = cumulativeBranchOnly;
        this.cumulativeBranchAndTrace = cumulativeBranchAndTrace;
        this.cumulativeTraceOnlyWindowSim = cumulativeTraceOnlyWindowSim;
        this.cumulativeTraceOnlyTriDiffExclusive = cumulativeTraceOnlyTriDiffExclusive;
        this.cumulativeTraceOnlyTriDiffMissing = cumulativeTraceOnlyTriDiffMissing;
    }

    public static String csvHeader() {
        return String.join(",",
                "execution_index",
                "test_packet_id",
                "admitted",
                "admission_reason",
                "new_branch_coverage",
                "trace_interesting",
                "tri_diff_exclusive_fired",
                "tri_diff_missing_fired",
                "window_sim_fired",
                "aggregate_sim_fired",
                "structured_candidate",
                "weak_candidate",
                "windows_evaluated",
                "overall_verdict",
                "cumulative_branch_only",
                "cumulative_branch_and_trace",
                "cumulative_trace_only_window_sim",
                "cumulative_trace_only_tridiff_exclusive",
                "cumulative_trace_only_tridiff_missing");
    }

    public String toCsvRow() {
        StringBuilder sb = new StringBuilder();
        sb.append(executionIndex).append(',');
        sb.append(testPacketId).append(',');
        sb.append(admitted).append(',');
        sb.append(admissionReason.name()).append(',');
        sb.append(newBranchCoverage).append(',');
        sb.append(traceInteresting).append(',');
        sb.append(triDiffExclusiveFired).append(',');
        sb.append(triDiffMissingFired).append(',');
        sb.append(windowSimFired).append(',');
        sb.append(aggregateSimFired).append(',');
        sb.append(structuredCandidate).append(',');
        sb.append(weakCandidate).append(',');
        sb.append(windowsEvaluated).append(',');
        sb.append(csvEscape(overallVerdict)).append(',');
        sb.append(cumulativeBranchOnly).append(',');
        sb.append(cumulativeBranchAndTrace).append(',');
        sb.append(cumulativeTraceOnlyWindowSim).append(',');
        sb.append(cumulativeTraceOnlyTriDiffExclusive).append(',');
        sb.append(cumulativeTraceOnlyTriDiffMissing);
        return sb.toString();
    }

    private static String csvEscape(String field) {
        if (field == null) {
            return "";
        }
        if (field.indexOf(',') < 0 && field.indexOf('"') < 0
                && field.indexOf('\n') < 0) {
            return field;
        }
        return '"' + field.replace("\"", "\"\"") + '"';
    }
}
