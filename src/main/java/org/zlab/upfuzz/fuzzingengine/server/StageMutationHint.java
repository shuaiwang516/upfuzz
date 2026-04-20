package org.zlab.upfuzz.fuzzingengine.server;

import java.io.Serializable;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;

import org.zlab.net.tracker.classifier.ProtocolFamily;
import org.zlab.upfuzz.fuzzingengine.server.observability.StructuredCandidateStrength;
import org.zlab.upfuzz.fuzzingengine.server.observability.TraceEvidenceStrength;
import org.zlab.upfuzz.fuzzingengine.trace.TraceWindow;

/**
 * Phase 4 compact hint describing <em>where</em> a parent test plan was
 * useful so the stage-aware mutator can concentrate effort near that
 * upgrade-relevant transition instead of relying on the generic
 * {@link org.zlab.upfuzz.fuzzingengine.testplan.TestPlan#mutate mutate}
 * fallback.
 *
 * <p>One hint is attached to every
 * {@link QueuedTestPlan} at admission time. The hint is
 * <em>lane-local</em> — all fields come from the rolling lane of the
 * admitted round so the mutator can exploit the stage(s) where the
 * divergence or new branch coverage showed up.
 *
 * <p>Phase 4 extension (Apr 2026): the hint now carries a compact
 * routing-context block that the Phase 4 scheduler and stage-aware
 * mutator consume to target the observed trace divergence without
 * pulling raw scoring state onto the queue:
 *
 * <ul>
 *   <li>{@link #hotProtocolFamily} — the dominant divergent protocol
 *       family in the firing window (falls back to the dominant
 *       supported family when no divergent family exists). Null when
 *       the hint was built for a pure-branch admission.</li>
 *   <li>{@link #hotRolePair} — the {@code "src->dst"} role pair that
 *       carried the most rolling-lane traffic in the firing window.
 *       Empty when unavailable.</li>
 *   <li>{@link #boundaryInvolvedRolePair} — the role pair for the most
 *       boundary-crossing rolling flow, or empty when no flow crossed
 *       an upgraded boundary.</li>
 *   <li>{@link #orderAnomalyPresent} — whether the firing window saw
 *       a compressed-family order divergence strong enough to earn the
 *       Phase 3 order bonus.</li>
 *   <li>{@link #flowSupportClass} — the strongest Phase 3 support tier
 *       reached across any firing window (see
 *       {@link TraceSupportClass}). Drives the Phase 4 routing policy
 *       (full-flow support feeds MAIN_EXPLOIT; flow-backed or
 *       family-backed support feeds SHADOW_EVAL; background-only /
 *       unsupported stays out of the exploitation lanes).</li>
 * </ul>
 *
 * <p>The rest of the hint tracks location / signal context:
 *
 * <ul>
 *   <li>{@link #hotStageId} / {@link #hotStageKind} — the comparison
 *       stage id / kind of the first firing window. Empty and
 *       {@link StageKindHint#UNKNOWN} when no window fired.</li>
 *   <li>{@link #hotWindowOrdinal} — rolling-lane ordinal of that
 *       window. -1 when none fired.</li>
 *   <li>{@link #hotNodeSet} — union of the rolling-lane
 *       {@code rawUpgradedNodeSet} across all firing windows. Tells
 *       the mutator which nodes were already touched by an upgrade
 *       boundary when the plan was admitted, so boundary-crossing
 *       mutators can concentrate on those nodes.</li>
 *   <li>{@link #signalType} — which side of the Phase 1/2 label
 *       system earned this admission (strong structured, weak
 *       structured, strong trace, weak trace, branch-only, …).</li>
 *   <li>{@link #postUpgrade} — true when at least one firing window
 *       was in {@link StageKindHint#POST_STAGE} or
 *       {@link StageKindHint#POST_FINAL_STAGE}. Used by the stage
 *       mutator to decide between pre-upgrade and post-upgrade
 *       workload placement.</li>
 *   <li>{@link #preUpgradeOnly} — true when the parent <em>only</em>
 *       fired in {@link StageKindHint#PRE_UPGRADE} windows. Phase 4
 *       down-ranks these so pre-upgrade churn cannot dominate
 *       exploitation budget.</li>
 *   <li>{@link #faultInfluenced} — true when the admission was
 *       already supported by a fault-recovery window or a fault event
 *       was active near the hotspot. Drives the fault-plus-upgrade
 *       template gate.</li>
 *   <li>{@link #needsConfirmation} — true when this parent carries a
 *       strong structured candidate (so Phase 4 should spend a small
 *       confirmation budget on low-edit-distance children) or weak
 *       structured divergence that deserves re-observation.</li>
 * </ul>
 *
 * <p>The class is immutable (all fields final) and serializable so
 * {@link org.zlab.upfuzz.fuzzingengine.testplan.TestPlan}-clone-based
 * mutation pipelines do not accidentally wipe the hint during
 * {@code SerializationUtils.clone(...)}.
 */
public final class StageMutationHint implements Serializable {
    private static final long serialVersionUID = 20260420L;

    /**
     * Serializable mirror of {@link TraceWindow.StageKind}. We do not
     * reuse the enum directly so the hint can be read and tested
     * without a dependency on the trace package in the future — and so
     * the mutator can switch on a compact closed set even when the
     * trace enum is extended.
     */
    public enum StageKindHint {
        UNKNOWN, PRE_UPGRADE, POST_STAGE, POST_FINAL_STAGE, LIFECYCLE_ONLY, FAULT_RECOVERY,;

        public static StageKindHint from(TraceWindow.StageKind kind) {
            if (kind == null) {
                return UNKNOWN;
            }
            switch (kind) {
            case PRE_UPGRADE:
                return PRE_UPGRADE;
            case POST_STAGE:
                return POST_STAGE;
            case POST_FINAL_STAGE:
                return POST_FINAL_STAGE;
            case LIFECYCLE_ONLY:
                return LIFECYCLE_ONLY;
            case FAULT_RECOVERY:
                return FAULT_RECOVERY;
            default:
                return UNKNOWN;
            }
        }
    }

    /**
     * High-level label describing which signal earned this admission.
     * Used by the stage-aware mutator to pick a mutation family — a
     * strong-structured admission gets confirmation-oriented children,
     * while a branch-only admission gets generic boundary-crossing
     * mutations.
     */
    public enum SignalType {
        BRANCH_ONLY, BRANCH_AND_STRONG_TRACE, BRANCH_AND_WEAK_TRACE, TRACE_ONLY_STRONG, TRACE_ONLY_WEAK, STRONG_STRUCTURED, WEAK_STRUCTURED, ROLLING_ONLY_EVENT, UNKNOWN
    }

    public final String hotStageId;
    public final StageKindHint hotStageKind;
    public final int hotWindowOrdinal;
    public final Set<Integer> hotNodeSet;
    public final SignalType signalType;
    public final boolean postUpgrade;
    public final boolean preUpgradeOnly;
    public final boolean faultInfluenced;
    public final boolean needsConfirmation;
    public final boolean upgradeOrderMattered;

    // --- Phase 4 routing context ---
    public final ProtocolFamily hotProtocolFamily;
    public final String hotRolePair;
    public final String boundaryInvolvedRolePair;
    public final boolean orderAnomalyPresent;
    public final TraceSupportClass flowSupportClass;

    public StageMutationHint(
            String hotStageId,
            StageKindHint hotStageKind,
            int hotWindowOrdinal,
            Set<Integer> hotNodeSet,
            SignalType signalType,
            boolean postUpgrade,
            boolean preUpgradeOnly,
            boolean faultInfluenced,
            boolean needsConfirmation,
            boolean upgradeOrderMattered) {
        this(hotStageId, hotStageKind, hotWindowOrdinal, hotNodeSet,
                signalType, postUpgrade, preUpgradeOnly, faultInfluenced,
                needsConfirmation, upgradeOrderMattered,
                /* hotProtocolFamily */ null,
                /* hotRolePair */ "",
                /* boundaryInvolvedRolePair */ "",
                /* orderAnomalyPresent */ false,
                /* flowSupportClass */ TraceSupportClass.UNSUPPORTED);
    }

    public StageMutationHint(
            String hotStageId,
            StageKindHint hotStageKind,
            int hotWindowOrdinal,
            Set<Integer> hotNodeSet,
            SignalType signalType,
            boolean postUpgrade,
            boolean preUpgradeOnly,
            boolean faultInfluenced,
            boolean needsConfirmation,
            boolean upgradeOrderMattered,
            ProtocolFamily hotProtocolFamily,
            String hotRolePair,
            String boundaryInvolvedRolePair,
            boolean orderAnomalyPresent,
            TraceSupportClass flowSupportClass) {
        this.hotStageId = hotStageId == null ? "" : hotStageId;
        this.hotStageKind = hotStageKind == null ? StageKindHint.UNKNOWN
                : hotStageKind;
        this.hotWindowOrdinal = hotWindowOrdinal;
        this.hotNodeSet = hotNodeSet == null
                ? Collections.emptySet()
                : Collections.unmodifiableSet(
                        new LinkedHashSet<>(hotNodeSet));
        this.signalType = signalType == null ? SignalType.UNKNOWN
                : signalType;
        this.postUpgrade = postUpgrade;
        this.preUpgradeOnly = preUpgradeOnly;
        this.faultInfluenced = faultInfluenced;
        this.needsConfirmation = needsConfirmation;
        this.upgradeOrderMattered = upgradeOrderMattered;
        this.hotProtocolFamily = hotProtocolFamily;
        this.hotRolePair = hotRolePair == null ? "" : hotRolePair;
        this.boundaryInvolvedRolePair = boundaryInvolvedRolePair == null
                ? ""
                : boundaryInvolvedRolePair;
        this.orderAnomalyPresent = orderAnomalyPresent;
        this.flowSupportClass = flowSupportClass == null
                ? TraceSupportClass.UNSUPPORTED
                : flowSupportClass;
    }

    /**
     * A no-op hint used when a plan is admitted without any
     * fired/interesting window. The stage-aware mutator falls back to
     * generic mutation when it sees this hint.
     */
    public static StageMutationHint empty() {
        return new StageMutationHint(
                "",
                StageKindHint.UNKNOWN,
                -1,
                Collections.emptySet(),
                SignalType.UNKNOWN,
                false,
                false,
                false,
                false,
                false,
                null,
                "",
                "",
                false,
                TraceSupportClass.UNSUPPORTED);
    }

    /** True when the hint has enough information to drive targeted mutation. */
    public boolean hasStageInfo() {
        return hotWindowOrdinal >= 0
                && hotStageKind != StageKindHint.UNKNOWN;
    }

    /**
     * True when the Phase 4 routing context carries a non-empty
     * protocol family or role pair. The Phase 4 scheduler consults
     * this to decide whether the hint adds routing value beyond the
     * bare stage coordinates.
     */
    public boolean hasRoutingContext() {
        return hotProtocolFamily != null
                || (hotRolePair != null && !hotRolePair.isEmpty())
                || (boundaryInvolvedRolePair != null
                        && !boundaryInvolvedRolePair.isEmpty())
                || orderAnomalyPresent
                || (flowSupportClass != null
                        && flowSupportClass != TraceSupportClass.UNSUPPORTED);
    }

    /**
     * Classify the Phase 1/2 admission labels into a single
     * {@link SignalType} that the mutator can switch on.
     */
    public static SignalType classifySignal(
            boolean branchBacked,
            TraceEvidenceStrength traceStrength,
            StructuredCandidateStrength candStrength,
            boolean rollingOnlyEventOrErrorLog) {
        if (candStrength == StructuredCandidateStrength.STRONG) {
            return SignalType.STRONG_STRUCTURED;
        }
        if (candStrength == StructuredCandidateStrength.WEAK) {
            return SignalType.WEAK_STRUCTURED;
        }
        if (rollingOnlyEventOrErrorLog) {
            return SignalType.ROLLING_ONLY_EVENT;
        }
        boolean strongTrace = traceStrength == TraceEvidenceStrength.STRONG;
        boolean weakTrace = traceStrength == TraceEvidenceStrength.WEAK
                || traceStrength == TraceEvidenceStrength.UNSUPPORTED_BUT_REPEATABLE
                || traceStrength == TraceEvidenceStrength.UNSUPPORTED;
        if (branchBacked && strongTrace) {
            return SignalType.BRANCH_AND_STRONG_TRACE;
        }
        if (branchBacked && weakTrace) {
            return SignalType.BRANCH_AND_WEAK_TRACE;
        }
        if (branchBacked) {
            return SignalType.BRANCH_ONLY;
        }
        if (strongTrace) {
            return SignalType.TRACE_ONLY_STRONG;
        }
        if (weakTrace) {
            return SignalType.TRACE_ONLY_WEAK;
        }
        return SignalType.UNKNOWN;
    }

    @Override
    public String toString() {
        return "StageMutationHint{"
                + "hotStageId='" + hotStageId + '\''
                + ", hotStageKind=" + hotStageKind
                + ", hotWindowOrdinal=" + hotWindowOrdinal
                + ", hotNodeSet=" + hotNodeSet
                + ", signalType=" + signalType
                + ", postUpgrade=" + postUpgrade
                + ", preUpgradeOnly=" + preUpgradeOnly
                + ", faultInfluenced=" + faultInfluenced
                + ", needsConfirmation=" + needsConfirmation
                + ", upgradeOrderMattered=" + upgradeOrderMattered
                + ", hotProtocolFamily=" + hotProtocolFamily
                + ", hotRolePair='" + hotRolePair + '\''
                + ", boundaryInvolvedRolePair='" + boundaryInvolvedRolePair
                + '\''
                + ", orderAnomalyPresent=" + orderAnomalyPresent
                + ", flowSupportClass=" + flowSupportClass
                + '}';
    }
}
