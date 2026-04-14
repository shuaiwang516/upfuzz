package org.zlab.upfuzz.fuzzingengine.server.observability;

/**
 * Immutable per-window record of trace-scoring outcomes.
 *
 * <p>One row is emitted for every aligned comparable window that survived the
 * min-event gate, regardless of whether the window ultimately caused an
 * admission. Counters and flags are captured so offline analysis can
 * reproduce admission decisions without re-running the campaign.
 */
public final class WindowTriggerRow {
    public final long round;
    public final int testPacketId;
    public final int windowOrdinal;
    public final String comparisonStage;
    public final int totalMessages;
    public final int totalAllThreeCount;
    public final int rollingExclusive;
    public final int rollingMissing;
    public final double rollingExclusiveFraction;
    public final double rollingMissingFraction;
    public final double simOoRo;
    public final double simRoNn;
    public final double simBaseline;
    public final double rollingMinSimilarity;
    public final double rollingDivergenceMargin;
    public final boolean windowHasEnoughEvents;
    public final boolean windowSimFired;
    public final boolean triDiffExclusiveFired;
    public final boolean triDiffMissingFired;

    public WindowTriggerRow(
            long round,
            int testPacketId,
            int windowOrdinal,
            String comparisonStage,
            int totalMessages,
            int totalAllThreeCount,
            int rollingExclusive,
            int rollingMissing,
            double rollingExclusiveFraction,
            double rollingMissingFraction,
            double simOoRo,
            double simRoNn,
            double simBaseline,
            double rollingMinSimilarity,
            double rollingDivergenceMargin,
            boolean windowHasEnoughEvents,
            boolean windowSimFired,
            boolean triDiffExclusiveFired,
            boolean triDiffMissingFired) {
        this.round = round;
        this.testPacketId = testPacketId;
        this.windowOrdinal = windowOrdinal;
        this.comparisonStage = comparisonStage == null ? "" : comparisonStage;
        this.totalMessages = totalMessages;
        this.totalAllThreeCount = totalAllThreeCount;
        this.rollingExclusive = rollingExclusive;
        this.rollingMissing = rollingMissing;
        this.rollingExclusiveFraction = rollingExclusiveFraction;
        this.rollingMissingFraction = rollingMissingFraction;
        this.simOoRo = simOoRo;
        this.simRoNn = simRoNn;
        this.simBaseline = simBaseline;
        this.rollingMinSimilarity = rollingMinSimilarity;
        this.rollingDivergenceMargin = rollingDivergenceMargin;
        this.windowHasEnoughEvents = windowHasEnoughEvents;
        this.windowSimFired = windowSimFired;
        this.triDiffExclusiveFired = triDiffExclusiveFired;
        this.triDiffMissingFired = triDiffMissingFired;
    }

    public static String csvHeader() {
        return String.join(",",
                "round",
                "test_packet_id",
                "window_ordinal",
                "comparison_stage",
                "total_messages",
                "total_all_three_count",
                "rolling_exclusive",
                "rolling_missing",
                "rolling_exclusive_fraction",
                "rolling_missing_fraction",
                "sim_oo_ro",
                "sim_ro_nn",
                "sim_baseline",
                "rolling_min_similarity",
                "rolling_divergence_margin",
                "window_has_enough_events",
                "window_sim_fired",
                "tri_diff_exclusive_fired",
                "tri_diff_missing_fired");
    }

    public String toCsvRow() {
        StringBuilder sb = new StringBuilder();
        sb.append(round).append(',');
        sb.append(testPacketId).append(',');
        sb.append(windowOrdinal).append(',');
        sb.append(csvEscape(comparisonStage)).append(',');
        sb.append(totalMessages).append(',');
        sb.append(totalAllThreeCount).append(',');
        sb.append(rollingExclusive).append(',');
        sb.append(rollingMissing).append(',');
        sb.append(formatDouble(rollingExclusiveFraction)).append(',');
        sb.append(formatDouble(rollingMissingFraction)).append(',');
        sb.append(formatDouble(simOoRo)).append(',');
        sb.append(formatDouble(simRoNn)).append(',');
        sb.append(formatDouble(simBaseline)).append(',');
        sb.append(formatDouble(rollingMinSimilarity)).append(',');
        sb.append(formatDouble(rollingDivergenceMargin)).append(',');
        sb.append(windowHasEnoughEvents).append(',');
        sb.append(windowSimFired).append(',');
        sb.append(triDiffExclusiveFired).append(',');
        sb.append(triDiffMissingFired);
        return sb.toString();
    }

    private static String formatDouble(double v) {
        if (Double.isNaN(v) || Double.isInfinite(v)) {
            return "";
        }
        return String.format(java.util.Locale.ROOT, "%.4f", v);
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
