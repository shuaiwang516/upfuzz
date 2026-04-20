package org.zlab.upfuzz.fuzzingengine.trace;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.zlab.net.tracker.Trace;
import org.zlab.net.tracker.classifier.ProtocolFamily;
import org.zlab.net.tracker.flow.BoundaryOracle;
import org.zlab.net.tracker.flow.FlowExtractionResult;
import org.zlab.net.tracker.flow.TraceFlowExtractor;

/**
 * Phase 2 per-window flow-summary bundle. Wraps the three lane-level
 * {@link FlowExtractionResult}s for one aligned window and exposes the
 * derived divergent-family / detail-label views consumed by
 * {@code FuzzingServer} observability and by Phase 3 scoring.
 *
 * <p>Caching contract: {@link #from(TraceWindow, TraceWindow, TraceWindow,
 * Set, TopologySnapshot)} computes the three extractions exactly once.
 * Callers should build one instance per aligned window and reuse it, as
 * recommended by the Phase 2 plan ("do not force the server to recompute
 * grouping repeatedly inside hot loops if it can be cached per merged
 * window").
 *
 * <p>Divergence is measured as the absolute gap between the rolling-lane
 * family count and the mean of the two baseline counts; families with
 * gap zero are omitted from {@link #topDivergentFamilies(int)}. This is
 * intentionally a first-cut, observability-only ordering — Phase 3 will
 * formalise divergent-family scoring once the logical-flow path is
 * stable.
 */
public final class AlignedWindowFlowSummaries {

    private final FlowExtractionResult oldOld;
    private final FlowExtractionResult rolling;
    private final FlowExtractionResult newNew;

    private AlignedWindowFlowSummaries(FlowExtractionResult oldOld,
            FlowExtractionResult rolling, FlowExtractionResult newNew) {
        this.oldOld = oldOld == null ? FlowExtractionResult.empty() : oldOld;
        this.rolling = rolling == null ? FlowExtractionResult.empty()
                : rolling;
        this.newNew = newNew == null ? FlowExtractionResult.empty() : newNew;
    }

    /**
     * Extract flows for all three lanes of an aligned window.
     *
     * <p>The baseline lanes (old-old / new-new) are extracted with
     * {@link BoundaryOracle#NO_BOUNDARY} because by definition the whole
     * cluster runs one version in those lanes — no upgraded/non-upgraded
     * cut exists, so stamping flows with {@code UNRESOLVED} would be
     * misleading. The rolling lane uses a {@link RollingFlowBoundaryOracle}
     * wired against the active {@link TopologySnapshot} and the lane's
     * {@code rawUpgradedNodeSet} so cross-version crossings show up as
     * {@code CROSSING} flows.
     */
    public static AlignedWindowFlowSummaries from(TraceWindow oldOld,
            TraceWindow rolling, TraceWindow newNew,
            Set<Integer> rollingUpgradedNodeSet,
            TopologySnapshot rollingTopologySnapshot) {
        BoundaryOracle rollingOracle = new RollingFlowBoundaryOracle(
                rollingUpgradedNodeSet, rollingTopologySnapshot);
        String stage = rolling != null ? rolling.comparisonStageId : "";
        FlowExtractionResult oo = extract(oldOld, stage,
                BoundaryOracle.NO_BOUNDARY);
        FlowExtractionResult ro = extract(rolling, stage, rollingOracle);
        FlowExtractionResult nn = extract(newNew, stage,
                BoundaryOracle.NO_BOUNDARY);
        return new AlignedWindowFlowSummaries(oo, ro, nn);
    }

    private static FlowExtractionResult extract(TraceWindow window,
            String stage, BoundaryOracle oracle) {
        if (window == null) {
            return FlowExtractionResult.empty();
        }
        Trace merged = window.mergedTrace();
        return TraceFlowExtractor.extract(stage, merged, oracle);
    }

    public FlowExtractionResult oldOld() {
        return oldOld;
    }

    public FlowExtractionResult rolling() {
        return rolling;
    }

    public FlowExtractionResult newNew() {
        return newNew;
    }

    /**
     * Ranked list of families whose rolling-lane count differs from the
     * mean of the two baseline counts. Zero-gap families are filtered
     * out. Ordering: descending gap, ties broken by family name for
     * stability.
     */
    public List<DivergentFamily> topDivergentFamilies(int limit) {
        Map<ProtocolFamily, Integer> ro = rolling.familyMultiset();
        Map<ProtocolFamily, Integer> oo = oldOld.familyMultiset();
        Map<ProtocolFamily, Integer> nn = newNew.familyMultiset();
        List<DivergentFamily> scored = new ArrayList<>();
        LinkedHashMap<ProtocolFamily, Boolean> seen = new LinkedHashMap<>();
        for (ProtocolFamily family : ro.keySet()) {
            seen.putIfAbsent(family, Boolean.TRUE);
        }
        for (ProtocolFamily family : oo.keySet()) {
            seen.putIfAbsent(family, Boolean.TRUE);
        }
        for (ProtocolFamily family : nn.keySet()) {
            seen.putIfAbsent(family, Boolean.TRUE);
        }
        for (ProtocolFamily family : seen.keySet()) {
            int rCount = ro.getOrDefault(family, 0);
            int oCount = oo.getOrDefault(family, 0);
            int nCount = nn.getOrDefault(family, 0);
            double baselineMean = (oCount + nCount) / 2.0;
            double gap = Math.abs(rCount - baselineMean);
            if (gap <= 0.0) {
                continue;
            }
            scored.add(new DivergentFamily(family, rCount, oCount, nCount,
                    gap));
        }
        scored.sort(Comparator
                .comparingDouble(DivergentFamily::gap).reversed()
                .thenComparing(d -> d.family.name()));
        if (limit > 0 && scored.size() > limit) {
            return Collections.unmodifiableList(
                    new ArrayList<>(scored.subList(0, limit)));
        }
        return Collections.unmodifiableList(scored);
    }

    /**
     * Per-family score used by {@link #topDivergentFamilies(int)} and the
     * {@code WindowTriggerRow} CSV. Scores are absolute gaps between the
     * rolling-lane count and the baseline mean — see the class-level
     * divergence contract for rationale.
     */
    public static final class DivergentFamily {
        public final ProtocolFamily family;
        public final int rollingCount;
        public final int oldOldCount;
        public final int newNewCount;
        private final double gap;

        public DivergentFamily(ProtocolFamily family, int rollingCount,
                int oldOldCount, int newNewCount, double gap) {
            this.family = family;
            this.rollingCount = rollingCount;
            this.oldOldCount = oldOldCount;
            this.newNewCount = newNewCount;
            this.gap = gap;
        }

        public double gap() {
            return gap;
        }

        @Override
        public String toString() {
            return family.name() + ":ro=" + rollingCount + ",oo=" + oldOldCount
                    + ",nn=" + newNewCount + ",gap=" + gap;
        }
    }
}
