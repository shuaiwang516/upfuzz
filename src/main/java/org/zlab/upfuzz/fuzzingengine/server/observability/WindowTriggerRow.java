package org.zlab.upfuzz.fuzzingengine.server.observability;

/**
 * Immutable per-window record of trace-scoring outcomes.
 *
 * <p>One row is emitted for every aligned comparable window that survived the
 * min-event gate, regardless of whether the window ultimately caused an
 * admission. Counters and flags are captured so offline analysis can
 * reproduce admission decisions without re-running the campaign.
 *
 * <p>Phase 0 appended — but did not remove — columns for per-window trace
 * support accounting. The fields are:
 * <ul>
 *   <li>{@link #baselineSharedCount} — number of messages shared by both
 *       baseline lanes (old-old ∩ new-new) for this window. This is the
 *       denominator for the missing-message fraction.</li>
 *   <li>{@link #changedMessageCount} — number of rolling-lane messages
 *       marked {@code changedMessage=true} in this window. Phase 0
 *       retires this signal for {@code testingMode=5}; mode-5 runs
 *       therefore emit {@code 0} here even when the underlying trace
 *       still carries the legacy flag. The column is preserved for
 *       non-mode-5 CSV compatibility.</li>
 *   <li>{@link #upgradedBoundaryEventCount} — number of rolling-lane
 *       messages whose sender and receiver resolved to opposite sides of
 *       the upgraded node set. This is the new topology-aware crossing
 *       count; see {@link #boundaryIndexResolvedEndpoints} et al. for
 *       the per-endpoint resolution histogram.</li>
 *   <li>{@link #traceEvidenceStrength} — the Phase 0 label for this
 *       window; offline analysis can use this to recompute aggregate
 *       labels without re-deriving them from logs.</li>
 *   <li>{@link #supportGatePassed} — true when the window has three-way
 *       shared support, i.e. {@code total_all_three_count > 0}.
 *       {@code baseline_shared_count} alone is not equivalent because it
 *       also includes messages both baselines carry but rolling is
 *       missing, which is exactly the case Apr15 flagged as unreliable.
 *       Phase 2 support-aware gating will consume this column.</li>
 * </ul>
 *
 * <p>Phase 0 also added the per-endpoint boundary resolution histogram
 * (total events, index-resolved, role-unique, role-ambiguous,
 * unresolved) so Apr16-style silent skips — entries whose IP/hostname
 * peerId landed outside the old numeric index parser — become visible
 * instead of disappearing into a {@code 0} crossing count.
 *
 * <p>Phase 2 appended the compact flow-level summary columns described
 * in the plan:
 * <ul>
 *   <li>{@code flow_total_*} — per-lane flow counts (old-old, rolling,
 *       new-new). Useful for spotting lanes whose grouping collapsed or
 *       exploded.</li>
 *   <li>{@code flow_explicit_id_rolling} /
 *       {@code flow_fallback_rolling} — how the rolling lane split
 *       between {@link CorrelationSource#EXPLICIT_LOGICAL_ID} /
 *       {@link CorrelationSource#EXPLICIT_DELIVERY_ID} and the bounded
 *       fallback tier, per system.</li>
 *   <li>{@code flow_boundary_involved_rolling} /
 *       {@code flow_role_ambiguous_boundary_rolling} — flow-level
 *       boundary counts. Both are derived from
 *       {@link org.zlab.upfuzz.fuzzingengine.trace.RollingFlowBoundaryOracle},
 *       which reuses the Phase 0 topology snapshot so raw-IP peers
 *       keep resolving through the same path.</li>
 *   <li>{@code flow_top_divergent_families} /
 *       {@code flow_top_divergent_details_rolling} — Phase 2's
 *       first-cut divergence report; ordered by absolute gap between
 *       the rolling-lane family count and the baseline-mean count.
 *       These are observability-only (no admission effect) — Phase 3
 *       will promote the same inputs into the scoring layer.</li>
 * </ul>
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
    // --- Phase 0 appended columns (trace support + confidence) ---
    public final int baselineSharedCount;
    public final int changedMessageCount;
    public final int upgradedBoundaryEventCount;
    public final TraceEvidenceStrength traceEvidenceStrength;
    public final boolean supportGatePassed;
    // --- Phase 0 boundary-resolution accounting ---
    public final int boundaryEventCountTotal;
    public final int boundaryIndexResolvedEndpoints;
    public final int boundaryRoleResolvedEndpoints;
    public final int boundaryRoleAmbiguousEndpoints;
    public final int boundaryUnresolvedEndpoints;
    // --- Phase 2 flow-level summary columns ---
    public final int flowTotalOldOld;
    public final int flowTotalRolling;
    public final int flowTotalNewNew;
    public final int flowExplicitIdRolling;
    public final int flowFallbackRolling;
    public final int flowGroupingFailedRolling;
    public final int flowBoundaryInvolvedRolling;
    public final int flowRoleAmbiguousBoundaryRolling;
    public final int flowUnresolvedBoundaryRolling;
    public final String flowTopDivergentFamilies;
    public final String flowTopDivergentDetailsRolling;

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
            boolean triDiffMissingFired,
            int baselineSharedCount,
            int changedMessageCount,
            int upgradedBoundaryEventCount,
            TraceEvidenceStrength traceEvidenceStrength,
            boolean supportGatePassed,
            int boundaryEventCountTotal,
            int boundaryIndexResolvedEndpoints,
            int boundaryRoleResolvedEndpoints,
            int boundaryRoleAmbiguousEndpoints,
            int boundaryUnresolvedEndpoints,
            int flowTotalOldOld,
            int flowTotalRolling,
            int flowTotalNewNew,
            int flowExplicitIdRolling,
            int flowFallbackRolling,
            int flowGroupingFailedRolling,
            int flowBoundaryInvolvedRolling,
            int flowRoleAmbiguousBoundaryRolling,
            int flowUnresolvedBoundaryRolling,
            String flowTopDivergentFamilies,
            String flowTopDivergentDetailsRolling) {
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
        this.baselineSharedCount = baselineSharedCount;
        this.changedMessageCount = changedMessageCount;
        this.upgradedBoundaryEventCount = upgradedBoundaryEventCount;
        this.traceEvidenceStrength = traceEvidenceStrength == null
                ? TraceEvidenceStrength.NONE
                : traceEvidenceStrength;
        this.supportGatePassed = supportGatePassed;
        this.boundaryEventCountTotal = boundaryEventCountTotal;
        this.boundaryIndexResolvedEndpoints = boundaryIndexResolvedEndpoints;
        this.boundaryRoleResolvedEndpoints = boundaryRoleResolvedEndpoints;
        this.boundaryRoleAmbiguousEndpoints = boundaryRoleAmbiguousEndpoints;
        this.boundaryUnresolvedEndpoints = boundaryUnresolvedEndpoints;
        this.flowTotalOldOld = flowTotalOldOld;
        this.flowTotalRolling = flowTotalRolling;
        this.flowTotalNewNew = flowTotalNewNew;
        this.flowExplicitIdRolling = flowExplicitIdRolling;
        this.flowFallbackRolling = flowFallbackRolling;
        this.flowGroupingFailedRolling = flowGroupingFailedRolling;
        this.flowBoundaryInvolvedRolling = flowBoundaryInvolvedRolling;
        this.flowRoleAmbiguousBoundaryRolling = flowRoleAmbiguousBoundaryRolling;
        this.flowUnresolvedBoundaryRolling = flowUnresolvedBoundaryRolling;
        this.flowTopDivergentFamilies = flowTopDivergentFamilies == null ? ""
                : flowTopDivergentFamilies;
        this.flowTopDivergentDetailsRolling = flowTopDivergentDetailsRolling == null
                ? ""
                : flowTopDivergentDetailsRolling;
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
                "tri_diff_missing_fired",
                "baseline_shared_count",
                "changed_message_count",
                "upgraded_boundary_event_count",
                "trace_evidence_strength",
                "support_gate_passed",
                "boundary_event_count_total",
                "boundary_event_count_index_resolved",
                "boundary_event_count_role_resolved",
                "boundary_event_count_role_ambiguous",
                "boundary_event_count_unresolved",
                "flow_total_old_old",
                "flow_total_rolling",
                "flow_total_new_new",
                "flow_explicit_id_rolling",
                "flow_fallback_rolling",
                "flow_grouping_failed_rolling",
                "flow_boundary_involved_rolling",
                "flow_role_ambiguous_boundary_rolling",
                "flow_unresolved_boundary_rolling",
                "flow_top_divergent_families",
                "flow_top_divergent_details_rolling");
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
        sb.append(triDiffMissingFired).append(',');
        sb.append(baselineSharedCount).append(',');
        sb.append(changedMessageCount).append(',');
        sb.append(upgradedBoundaryEventCount).append(',');
        sb.append(traceEvidenceStrength.name()).append(',');
        sb.append(supportGatePassed).append(',');
        sb.append(boundaryEventCountTotal).append(',');
        sb.append(boundaryIndexResolvedEndpoints).append(',');
        sb.append(boundaryRoleResolvedEndpoints).append(',');
        sb.append(boundaryRoleAmbiguousEndpoints).append(',');
        sb.append(boundaryUnresolvedEndpoints).append(',');
        sb.append(flowTotalOldOld).append(',');
        sb.append(flowTotalRolling).append(',');
        sb.append(flowTotalNewNew).append(',');
        sb.append(flowExplicitIdRolling).append(',');
        sb.append(flowFallbackRolling).append(',');
        sb.append(flowGroupingFailedRolling).append(',');
        sb.append(flowBoundaryInvolvedRolling).append(',');
        sb.append(flowRoleAmbiguousBoundaryRolling).append(',');
        sb.append(flowUnresolvedBoundaryRolling).append(',');
        sb.append(csvEscape(flowTopDivergentFamilies)).append(',');
        sb.append(csvEscape(flowTopDivergentDetailsRolling));
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
