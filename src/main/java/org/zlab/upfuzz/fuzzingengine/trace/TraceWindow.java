package org.zlab.upfuzz.fuzzingengine.trace;

import org.zlab.net.tracker.Trace;
import java.io.Serializable;
import java.util.Set;

/**
 * A single workload window: trace data collected between two disruptive
 * boundaries for one lane.
 */
public class TraceWindow implements Serializable {
    private static final long serialVersionUID = 20260406L;

    /** Ordinal position in the round (0-based). */
    public final int ordinal;

    /** Why this window was opened. */
    public final String openReason;

    /** Why this window was closed. */
    public final String closeReason;

    /** Index of the event that closed this window (-1 if round end). */
    public final int closeEventIdx;

    /** Normalized comparison stage ID (used for cross-lane alignment). */
    public final String comparisonStageId;

    /** Kind of stage. */
    public final StageKind stageKind;

    /** Abstract node set for this stage transition (cross-lane alignment key). */
    public final Set<Integer> normalizedTransitionNodeSet;

    /** Lane-local set of nodes actually running upgraded bits. */
    public final Set<Integer> rawUpgradedNodeSet;

    /** Lane-local version layout string. */
    public final String rawVersionLayout;

    /** Whether this window should participate in primary cross-lane comparison. */
    public final boolean comparableAcrossLanes;

    /** Per-node traces for this window. Index = node index. */
    public final Trace[] nodeTraces;

    public enum StageKind {
        PRE_UPGRADE, POST_STAGE, POST_FINAL_STAGE, LIFECYCLE_ONLY, FAULT_RECOVERY,
    }

    public TraceWindow(int ordinal, String openReason, String closeReason,
            int closeEventIdx, String comparisonStageId, StageKind stageKind,
            Set<Integer> normalizedTransitionNodeSet,
            Set<Integer> rawUpgradedNodeSet, String rawVersionLayout,
            boolean comparableAcrossLanes, Trace[] nodeTraces) {
        this.ordinal = ordinal;
        this.openReason = openReason;
        this.closeReason = closeReason;
        this.closeEventIdx = closeEventIdx;
        this.comparisonStageId = comparisonStageId;
        this.stageKind = stageKind;
        this.normalizedTransitionNodeSet = normalizedTransitionNodeSet;
        this.rawUpgradedNodeSet = rawUpgradedNodeSet;
        this.rawVersionLayout = rawVersionLayout;
        this.comparableAcrossLanes = comparableAcrossLanes;
        this.nodeTraces = nodeTraces;
    }

    /** Merge all per-node traces into a single timestamp-ordered trace. */
    public Trace mergedTrace() {
        return Trace.mergeBasedOnTimestamp(nodeTraces);
    }

    /** Total event count across all nodes. */
    public int totalEventCount() {
        int total = 0;
        for (Trace t : nodeTraces) {
            if (t != null)
                total += t.size();
        }
        return total;
    }

    @Override
    public String toString() {
        return "TraceWindow{ordinal=" + ordinal
                + ", stage=" + comparisonStageId
                + ", kind=" + stageKind
                + ", transitionNodes=" + normalizedTransitionNodeSet
                + ", rawUpgraded=" + rawUpgradedNodeSet
                + ", layout=" + rawVersionLayout
                + ", comparable=" + comparableAcrossLanes
                + ", events=" + totalEventCount()
                + "}";
    }
}
