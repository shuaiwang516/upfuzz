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
 * support accounting. Phase 2 appended the compact flow-level summary
 * columns. Phase 3 appends the composite scorer components (family /
 * flow / order similarities, support class, baseline agreement, rolling
 * divergence, composite score, firing reasons, dominant family labels).
 *
 * <p>The row is written to {@code trace_window_summary.csv} in the
 * runner observability bundle; the header and toCsvRow() order must
 * stay in lockstep. See the Phase 3 plan for the full column
 * contract.
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
    // --- Phase 3 scorer component columns ---
    public final String supportClass;
    public final int familySupportCount;
    public final int flowSupportCount;
    public final int baselineFlowSupportCount;
    public final int backgroundFamilySupportCount;
    public final int upgradeCriticalSupportCount;
    public final double familyJaccardOoRo;
    public final double familyJaccardRoNn;
    public final double familyJaccardOoNn;
    public final double familyWeightedSimOoRo;
    public final double familyWeightedSimRoNn;
    public final double familyWeightedSimOoNn;
    public final double flowJaccardOoRo;
    public final double flowJaccardRoNn;
    public final double flowJaccardOoNn;
    public final double explicitFlowJaccardOoRo;
    public final double explicitFlowJaccardRoNn;
    public final double explicitFlowJaccardOoNn;
    public final double orderSimilarityOoRo;
    public final double orderSimilarityRoNn;
    public final double orderSimilarityOoNn;
    public final double baselineAgreementScore;
    public final double rollingDivergenceScore;
    public final double orderDivergenceScore;
    public final double backgroundShareRolling;
    public final double boundaryBonusApplied;
    public final double orderBonusApplied;
    public final double backgroundCapApplied;
    public final double compositeScore;
    public final int rollingExclusiveUpgradeCriticalEventCount;
    public final String dominantSupportedFamily;
    public final String dominantDivergentFamily;
    public final String dominantOrderAnomalousFamily;
    public final String familyProfileLabel;
    public final boolean rollingOnlyUpgradeCriticalPresent;
    public final String firingReasons;

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
            String flowTopDivergentDetailsRolling,
            String supportClass,
            int familySupportCount,
            int flowSupportCount,
            int baselineFlowSupportCount,
            int backgroundFamilySupportCount,
            int upgradeCriticalSupportCount,
            double familyJaccardOoRo,
            double familyJaccardRoNn,
            double familyJaccardOoNn,
            double familyWeightedSimOoRo,
            double familyWeightedSimRoNn,
            double familyWeightedSimOoNn,
            double flowJaccardOoRo,
            double flowJaccardRoNn,
            double flowJaccardOoNn,
            double explicitFlowJaccardOoRo,
            double explicitFlowJaccardRoNn,
            double explicitFlowJaccardOoNn,
            double orderSimilarityOoRo,
            double orderSimilarityRoNn,
            double orderSimilarityOoNn,
            double baselineAgreementScore,
            double rollingDivergenceScore,
            double orderDivergenceScore,
            double backgroundShareRolling,
            double boundaryBonusApplied,
            double orderBonusApplied,
            double backgroundCapApplied,
            double compositeScore,
            int rollingExclusiveUpgradeCriticalEventCount,
            String dominantSupportedFamily,
            String dominantDivergentFamily,
            String dominantOrderAnomalousFamily,
            String familyProfileLabel,
            boolean rollingOnlyUpgradeCriticalPresent,
            String firingReasons) {
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
        this.supportClass = supportClass == null ? "" : supportClass;
        this.familySupportCount = familySupportCount;
        this.flowSupportCount = flowSupportCount;
        this.baselineFlowSupportCount = baselineFlowSupportCount;
        this.backgroundFamilySupportCount = backgroundFamilySupportCount;
        this.upgradeCriticalSupportCount = upgradeCriticalSupportCount;
        this.familyJaccardOoRo = familyJaccardOoRo;
        this.familyJaccardRoNn = familyJaccardRoNn;
        this.familyJaccardOoNn = familyJaccardOoNn;
        this.familyWeightedSimOoRo = familyWeightedSimOoRo;
        this.familyWeightedSimRoNn = familyWeightedSimRoNn;
        this.familyWeightedSimOoNn = familyWeightedSimOoNn;
        this.flowJaccardOoRo = flowJaccardOoRo;
        this.flowJaccardRoNn = flowJaccardRoNn;
        this.flowJaccardOoNn = flowJaccardOoNn;
        this.explicitFlowJaccardOoRo = explicitFlowJaccardOoRo;
        this.explicitFlowJaccardRoNn = explicitFlowJaccardRoNn;
        this.explicitFlowJaccardOoNn = explicitFlowJaccardOoNn;
        this.orderSimilarityOoRo = orderSimilarityOoRo;
        this.orderSimilarityRoNn = orderSimilarityRoNn;
        this.orderSimilarityOoNn = orderSimilarityOoNn;
        this.baselineAgreementScore = baselineAgreementScore;
        this.rollingDivergenceScore = rollingDivergenceScore;
        this.orderDivergenceScore = orderDivergenceScore;
        this.backgroundShareRolling = backgroundShareRolling;
        this.boundaryBonusApplied = boundaryBonusApplied;
        this.orderBonusApplied = orderBonusApplied;
        this.backgroundCapApplied = backgroundCapApplied;
        this.compositeScore = compositeScore;
        this.rollingExclusiveUpgradeCriticalEventCount = rollingExclusiveUpgradeCriticalEventCount;
        this.dominantSupportedFamily = dominantSupportedFamily == null ? ""
                : dominantSupportedFamily;
        this.dominantDivergentFamily = dominantDivergentFamily == null ? ""
                : dominantDivergentFamily;
        this.dominantOrderAnomalousFamily = dominantOrderAnomalousFamily == null
                ? ""
                : dominantOrderAnomalousFamily;
        this.familyProfileLabel = familyProfileLabel == null ? ""
                : familyProfileLabel;
        this.rollingOnlyUpgradeCriticalPresent = rollingOnlyUpgradeCriticalPresent;
        this.firingReasons = firingReasons == null ? "" : firingReasons;
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
                "flow_top_divergent_details_rolling",
                "support_class",
                "family_support_count",
                "flow_support_count",
                "baseline_flow_support_count",
                "background_family_support_count",
                "upgrade_critical_support_count",
                "family_jaccard_oo_ro",
                "family_jaccard_ro_nn",
                "family_jaccard_oo_nn",
                "family_weighted_sim_oo_ro",
                "family_weighted_sim_ro_nn",
                "family_weighted_sim_oo_nn",
                "flow_jaccard_oo_ro",
                "flow_jaccard_ro_nn",
                "flow_jaccard_oo_nn",
                "explicit_flow_jaccard_oo_ro",
                "explicit_flow_jaccard_ro_nn",
                "explicit_flow_jaccard_oo_nn",
                "order_similarity_oo_ro",
                "order_similarity_ro_nn",
                "order_similarity_oo_nn",
                "baseline_agreement_score",
                "rolling_divergence_score",
                "order_divergence_score",
                "background_share_rolling",
                "boundary_bonus_applied",
                "order_bonus_applied",
                "background_cap_applied",
                "composite_score",
                "rolling_exclusive_upgrade_critical_events",
                "dominant_supported_family",
                "dominant_divergent_family",
                "dominant_order_anomalous_family",
                "family_profile_label",
                "rolling_only_upgrade_critical_present",
                "firing_reasons");
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
        sb.append(csvEscape(flowTopDivergentDetailsRolling)).append(',');
        sb.append(csvEscape(supportClass)).append(',');
        sb.append(familySupportCount).append(',');
        sb.append(flowSupportCount).append(',');
        sb.append(baselineFlowSupportCount).append(',');
        sb.append(backgroundFamilySupportCount).append(',');
        sb.append(upgradeCriticalSupportCount).append(',');
        sb.append(formatDouble(familyJaccardOoRo)).append(',');
        sb.append(formatDouble(familyJaccardRoNn)).append(',');
        sb.append(formatDouble(familyJaccardOoNn)).append(',');
        sb.append(formatDouble(familyWeightedSimOoRo)).append(',');
        sb.append(formatDouble(familyWeightedSimRoNn)).append(',');
        sb.append(formatDouble(familyWeightedSimOoNn)).append(',');
        sb.append(formatDouble(flowJaccardOoRo)).append(',');
        sb.append(formatDouble(flowJaccardRoNn)).append(',');
        sb.append(formatDouble(flowJaccardOoNn)).append(',');
        sb.append(formatDouble(explicitFlowJaccardOoRo)).append(',');
        sb.append(formatDouble(explicitFlowJaccardRoNn)).append(',');
        sb.append(formatDouble(explicitFlowJaccardOoNn)).append(',');
        sb.append(formatDouble(orderSimilarityOoRo)).append(',');
        sb.append(formatDouble(orderSimilarityRoNn)).append(',');
        sb.append(formatDouble(orderSimilarityOoNn)).append(',');
        sb.append(formatDouble(baselineAgreementScore)).append(',');
        sb.append(formatDouble(rollingDivergenceScore)).append(',');
        sb.append(formatDouble(orderDivergenceScore)).append(',');
        sb.append(formatDouble(backgroundShareRolling)).append(',');
        sb.append(formatDouble(boundaryBonusApplied)).append(',');
        sb.append(formatDouble(orderBonusApplied)).append(',');
        sb.append(formatDouble(backgroundCapApplied)).append(',');
        sb.append(formatDouble(compositeScore)).append(',');
        sb.append(rollingExclusiveUpgradeCriticalEventCount).append(',');
        sb.append(csvEscape(dominantSupportedFamily)).append(',');
        sb.append(csvEscape(dominantDivergentFamily)).append(',');
        sb.append(csvEscape(dominantOrderAnomalousFamily)).append(',');
        sb.append(csvEscape(familyProfileLabel)).append(',');
        sb.append(rollingOnlyUpgradeCriticalPresent).append(',');
        sb.append(csvEscape(firingReasons));
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
