package org.zlab.upfuzz.fuzzingengine.server;

import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.zlab.net.tracker.classifier.ProtocolFamily;
import org.zlab.net.tracker.classifier.ProtocolFamilyClass;
import org.zlab.net.tracker.diff.DiffComputeMessageTriDiff;
import org.zlab.net.tracker.diff.FamilyFlowSimilarity;
import org.zlab.net.tracker.flow.FlowExtractionResult;
import org.zlab.net.tracker.flow.TraceFlowKey;
import org.zlab.upfuzz.fuzzingengine.Config;
import org.zlab.upfuzz.fuzzingengine.server.observability.TraceEvidenceStrength;
import org.zlab.upfuzz.fuzzingengine.trace.TraceWindow;

/**
 * Phase 3 per-window trace guidance scorer. Replaces the Phase 2
 * {@code evaluateTriDiffWindow} path.
 *
 * <p>The scorer consumes the Phase 1 family taxonomy and the Phase 2
 * logical-flow extraction, produces the composite support class, and
 * explicitly keeps baseline-baseline agreement and rolling-vs-baseline
 * divergence as separate scoring components. The final
 * {@link TraceEvidenceStrength} comes from comparing a bounded composite
 * score against the configured strong / weak thresholds, plus a tiered
 * rule set that prevents background-only or unsupported windows from
 * reaching {@link TraceEvidenceStrength#STRONG}.
 *
 * <p>The class also preserves the Phase 1 / Phase 2 admission booleans
 * ({@code exclusiveInteresting}, {@code missingInteresting},
 * {@code triDiffInteresting}) by consuming the same
 * {@link DiffComputeMessageTriDiff.MessageTriDiffResult}. The admission
 * contract from earlier phases ("rolling-exclusive churn admits seeds")
 * stays unchanged; Phase 3 only reshapes the <em>strength</em>
 * decision.
 */
public final class TraceWindowGuidanceScorer {

    private TraceWindowGuidanceScorer() {
    }

    /**
     * Immutable config snapshot. Extracted from {@link Config.Configuration}
     * at the start of each scoring pass so unit tests can build explicit
     * {@link Weights} instances without standing up a full server config.
     */
    public static final class Weights {
        public final double upgradeCriticalWeight;
        public final double backgroundWeight;
        public final double unknownWeight;
        public final double strongScoreThreshold;
        public final double weakScoreThreshold;
        public final double boundaryBonus;
        public final double orderBonusCap;
        public final double backgroundCap;
        public final double minBaselineAgreementForStrong;
        public final double minRollingDivergenceForStrong;

        public Weights(double upgradeCriticalWeight, double backgroundWeight,
                double unknownWeight, double strongScoreThreshold,
                double weakScoreThreshold, double boundaryBonus,
                double orderBonusCap,
                double backgroundCap, double minBaselineAgreementForStrong,
                double minRollingDivergenceForStrong) {
            this.upgradeCriticalWeight = upgradeCriticalWeight;
            this.backgroundWeight = backgroundWeight;
            this.unknownWeight = unknownWeight;
            this.strongScoreThreshold = strongScoreThreshold;
            this.weakScoreThreshold = weakScoreThreshold;
            this.boundaryBonus = boundaryBonus;
            this.orderBonusCap = orderBonusCap;
            this.backgroundCap = backgroundCap;
            this.minBaselineAgreementForStrong = minBaselineAgreementForStrong;
            this.minRollingDivergenceForStrong = minRollingDivergenceForStrong;
        }

        public static Weights fromConfig(Config.Configuration conf) {
            return new Weights(
                    conf.traceUpgradeCriticalFamilyWeight,
                    conf.traceBackgroundFamilyWeight,
                    conf.traceUnknownFamilyWeight,
                    conf.traceStrongScoreThreshold,
                    conf.traceWeakScoreThreshold,
                    conf.traceBoundaryBonus,
                    conf.traceOrderBonusCap,
                    conf.traceBackgroundCap,
                    conf.traceMinBaselineAgreementForStrong,
                    conf.traceMinRollingDivergenceForStrong);
        }
    }

    /**
     * Score a single aligned window.
     *
     * @param triDiff
     *            message-identity tri-diff result. Still drives the admission
     *            booleans (exclusive / missing / triDiffInteresting) and the
     *            {@code detailed_all_three_count} diagnostic field.
     * @param oldOldFlows
     *            Phase 2 flow extraction for the {@code old-old} lane.
     * @param rollingFlows
     *            Phase 2 flow extraction for the rolling lane.
     * @param newNewFlows
     *            Phase 2 flow extraction for the {@code new-new} lane.
     * @param stageKind
     *            window stage kind; only mixed-version-relevant stages
     *            (POST_STAGE / POST_FINAL_STAGE / FAULT_RECOVERY) can reach
     *            {@link TraceEvidenceStrength#STRONG}.
     * @param windowSimInteresting
     *            true when the per-window similarity check already fired —
     *            preserved for admission contract compatibility.
     * @param upgradedBoundaryEventCount
     *            Phase 0 per-event boundary crossings (topology-aware).
     * @param boundaryInvolvedRollingFlowCount
     *            Phase 2 rolling-lane flows whose events traversed the
     *            upgraded-node boundary.
     * @param changedMessageCount
     *            number of rolling-lane messages whose payload class
     *            changed between the old and new versions. Kept as a
     *            live corroborator alongside the boundary counts for
     *            non-mode-5 rolling runs; callers should pass {@code 0}
     *            when
     *            {@link org.zlab.upfuzz.fuzzingengine.Config.Configuration#useChangedMessageRollingTraceCorroboration()}
     *            is {@code false} (mode 5 / mode 6).
     * @param rollingExclusiveMinCount
     *            minimum rolling-exclusive message count for the Phase 1
     *            admission rule.
     * @param rollingExclusiveFractionThreshold
     *            minimum rolling-exclusive fraction for the Phase 1 admission
     *            rule.
     * @param rollingMissingMinCount
     *            minimum rolling-missing message count for the observability
     *            counter.
     * @param rollingMissingFractionThreshold
     *            minimum rolling-missing fraction for the observability
     *            counter.
     * @param weights
     *            per-campaign tuning bundle.
     */
    public static TraceWindowGuidanceDecision score(
            DiffComputeMessageTriDiff.MessageTriDiffResult triDiff,
            FlowExtractionResult oldOldFlows, FlowExtractionResult rollingFlows,
            FlowExtractionResult newNewFlows, TraceWindow.StageKind stageKind,
            boolean windowSimInteresting, int upgradedBoundaryEventCount,
            int boundaryInvolvedRollingFlowCount, int changedMessageCount,
            int rollingExclusiveMinCount,
            double rollingExclusiveFractionThreshold,
            int rollingMissingMinCount,
            double rollingMissingFractionThreshold, Weights weights) {
        TraceWindowGuidanceDecision.Builder b = TraceWindowGuidanceDecision
                .builder();
        Weights w = weights == null ? defaultWeights() : weights;
        FlowExtractionResult oo = oldOldFlows == null
                ? FlowExtractionResult.empty()
                : oldOldFlows;
        FlowExtractionResult ro = rollingFlows == null
                ? FlowExtractionResult.empty()
                : rollingFlows;
        FlowExtractionResult nn = newNewFlows == null
                ? FlowExtractionResult.empty()
                : newNewFlows;

        applyTriDiffAdmissionBooleans(triDiff, stageKind, windowSimInteresting,
                rollingExclusiveMinCount, rollingExclusiveFractionThreshold,
                rollingMissingMinCount, rollingMissingFractionThreshold, b);

        boolean windowFired = b.exclusiveInteresting || b.windowSimInteresting;
        b.windowFired(windowFired);

        // --- Similarity components -------------------------------------
        Map<ProtocolFamily, Integer> ooFamilies = oo.familyMultiset();
        Map<ProtocolFamily, Integer> roFamilies = ro.familyMultiset();
        Map<ProtocolFamily, Integer> nnFamilies = nn.familyMultiset();

        double familyJaccardOoRo = FamilyFlowSimilarity
                .familyJaccard(ooFamilies, roFamilies);
        double familyJaccardRoNn = FamilyFlowSimilarity
                .familyJaccard(roFamilies, nnFamilies);
        double familyJaccardOoNn = FamilyFlowSimilarity
                .familyJaccard(ooFamilies, nnFamilies);
        b.familyJaccards(familyJaccardOoRo, familyJaccardRoNn,
                familyJaccardOoNn);

        double weightedOoRo = FamilyFlowSimilarity.familyClassWeightedJaccard(
                ooFamilies,
                roFamilies, w.upgradeCriticalWeight, w.backgroundWeight,
                w.unknownWeight);
        double weightedRoNn = FamilyFlowSimilarity.familyClassWeightedJaccard(
                roFamilies,
                nnFamilies, w.upgradeCriticalWeight, w.backgroundWeight,
                w.unknownWeight);
        double weightedOoNn = FamilyFlowSimilarity.familyClassWeightedJaccard(
                ooFamilies,
                nnFamilies, w.upgradeCriticalWeight, w.backgroundWeight,
                w.unknownWeight);
        b.familyClassWeighted(weightedOoRo, weightedRoNn, weightedOoNn);

        Map<TraceFlowKey, Integer> ooFlows = oo.flowMultiset();
        Map<TraceFlowKey, Integer> roFlows = ro.flowMultiset();
        Map<TraceFlowKey, Integer> nnFlows = nn.flowMultiset();
        b.flowJaccards(FamilyFlowSimilarity.flowJaccard(ooFlows, roFlows),
                FamilyFlowSimilarity.flowJaccard(roFlows, nnFlows),
                FamilyFlowSimilarity.flowJaccard(ooFlows, nnFlows));
        b.explicitFlowJaccards(
                FamilyFlowSimilarity.explicitFlowJaccard(ooFlows, roFlows),
                FamilyFlowSimilarity.explicitFlowJaccard(roFlows, nnFlows),
                FamilyFlowSimilarity.explicitFlowJaccard(ooFlows, nnFlows));

        // Order is evaluated per-(srcRole->dstRole) local context and
        // filtered to UPGRADE_CRITICAL traffic. The plan explicitly
        // forbids two patterns the old single-global-sequence version
        // suffered from: (1) penalizing reorderings between unrelated
        // async flows (N0->N1 heartbeats versus N2->N0 repairs), and
        // (2) letting background chatter interleaving dominate the
        // order signal. Per-role-pair LCS averaged over shared pairs
        // satisfies both — pairs that only exist on one lane are
        // skipped (they are by definition independent), and the class
        // filter removes BACKGROUND entries before the LCS runs.
        double orderSimOoRo = FamilyFlowSimilarity
                .perRolePairCompressedOrderSimilarity(
                        oo.perRolePairCompressedFamilyOrder(),
                        ro.perRolePairCompressedFamilyOrder(),
                        ProtocolFamilyClass.UPGRADE_CRITICAL);
        double orderSimRoNn = FamilyFlowSimilarity
                .perRolePairCompressedOrderSimilarity(
                        ro.perRolePairCompressedFamilyOrder(),
                        nn.perRolePairCompressedFamilyOrder(),
                        ProtocolFamilyClass.UPGRADE_CRITICAL);
        double orderSimOoNn = FamilyFlowSimilarity
                .perRolePairCompressedOrderSimilarity(
                        oo.perRolePairCompressedFamilyOrder(),
                        nn.perRolePairCompressedFamilyOrder(),
                        ProtocolFamilyClass.UPGRADE_CRITICAL);
        b.orderSimilarities(orderSimOoRo, orderSimRoNn, orderSimOoNn);

        // --- Support classification -----------------------------------
        SupportSummary support = computeSupport(ooFamilies, roFamilies,
                nnFamilies, ooFlows,
                roFlows, nnFlows);
        b.supportClass(support.supportClass)
                .familySupportCount(support.familySupportCount)
                .flowSupportCount(support.flowSupportCount)
                .baselineFlowSupportCount(support.baselineFlowSupportCount)
                .backgroundFamilySupportCount(
                        support.backgroundFamilySupportCount)
                .upgradeCriticalSupportCount(
                        support.upgradeCriticalSupportCount)
                .detailedAllThreeCount(
                        triDiff == null ? 0 : triDiff.totalAllThreeCount())
                .supportGatePassed(
                        support.supportClass != TraceSupportClass.UNSUPPORTED);

        // --- Tri-execution scoring components -------------------------
        double baselineAgreementScore = clampUnit(weightedOoNn);
        double rollingDivergenceScore = clampUnit(
                1.0 - Math.min(clampUnit(weightedOoRo),
                        clampUnit(weightedRoNn)));
        double orderDivergenceScore = clampUnit(
                1.0 - Math.min(clampUnit(orderSimOoRo),
                        clampUnit(orderSimRoNn)));
        double backgroundShareRolling = FamilyFlowSimilarity.classShare(
                roFamilies,
                ProtocolFamilyClass.BACKGROUND);
        double upgradeCriticalShareRolling = FamilyFlowSimilarity.classShare(
                roFamilies,
                ProtocolFamilyClass.UPGRADE_CRITICAL);

        b.baselineAgreementScore(baselineAgreementScore)
                .rollingDivergenceScore(rollingDivergenceScore)
                .orderDivergenceScore(orderDivergenceScore)
                .backgroundShareRolling(backgroundShareRolling);

        // --- Classification flags -------------------------------------
        boolean backgroundOnly = support.supportClass == TraceSupportClass.BACKGROUND_ONLY;
        boolean upgradeCriticalInvolved = support.upgradeCriticalSupportCount > 0
                || upgradeCriticalShareRolling > 0.0;
        boolean mixedFamilyProfile = support.upgradeCriticalSupportCount > 0
                && support.backgroundFamilySupportCount > 0;
        b.backgroundOnly(backgroundOnly)
                .upgradeCriticalInvolved(upgradeCriticalInvolved)
                .mixedFamilyProfile(mixedFamilyProfile);

        // --- Corroboration counters -----------------------------------
        int rollingExclusiveUpgradeCritical = countRollingExclusiveUpgradeCriticalEvents(
                ooFamilies, roFamilies, nnFamilies);
        boolean rollingOnlyUpgradeCriticalPresent = rollingExclusiveUpgradeCritical > 0;
        int safeChangedMessageCount = Math.max(0, changedMessageCount);
        b.upgradedBoundaryEventCount(upgradedBoundaryEventCount)
                .boundaryInvolvedRollingFlowCount(
                        boundaryInvolvedRollingFlowCount)
                .rollingExclusiveUpgradeCriticalEventCount(
                        rollingExclusiveUpgradeCritical)
                .changedMessageCount(safeChangedMessageCount)
                .rollingOnlyUpgradeCriticalPresent(
                        rollingOnlyUpgradeCriticalPresent);

        // --- Dominant families for observability ----------------------
        ProtocolFamily dominantSupportedFamily = findDominantSupportedFamily(
                ooFamilies, roFamilies,
                nnFamilies);
        ProtocolFamily dominantDivergentFamily = findDominantDivergentFamily(
                ooFamilies, roFamilies,
                nnFamilies);
        ProtocolFamily dominantOrderAnomalousFamily = findDominantOrderAnomalousFamily(
                oo.perRolePairCompressedFamilyOrder(),
                ro.perRolePairCompressedFamilyOrder(),
                nn.perRolePairCompressedFamilyOrder());
        b.dominantSupportedFamily(dominantSupportedFamily)
                .dominantDivergentFamily(dominantDivergentFamily)
                .dominantOrderAnomalousFamily(dominantOrderAnomalousFamily);

        // --- Bonuses --------------------------------------------------
        boolean flowBackedOrBetter = support.supportClass
                .atLeast(TraceSupportClass.FLOW_BACKED);
        boolean familyBackedOrBetter = support.supportClass
                .atLeast(TraceSupportClass.FAMILY_BACKED);

        // Corroboration bonus: fires once when the rolling lane carries
        // any mixed-version evidence — an upgraded-boundary crossing, a
        // boundary-involved flow, or a changed-message payload (the
        // latter is always zero on mode 5 / mode 6 via
        // Config.useChangedMessageRollingTraceCorroboration). All three
        // paths are alternatives, not stacking; the bonus amount is
        // capped at {@code traceBoundaryBonus}.
        boolean corroborationFired = upgradedBoundaryEventCount > 0
                || boundaryInvolvedRollingFlowCount > 0
                || safeChangedMessageCount > 0;
        double boundaryBonusApplied = corroborationFired
                ? Math.max(0.0, w.boundaryBonus)
                : 0.0;
        double orderBonusApplied = flowBackedOrBetter
                ? Math.min(Math.max(0.0, w.orderBonusCap),
                        orderDivergenceScore * w.orderBonusCap)
                : 0.0;
        b.boundaryBonusApplied(boundaryBonusApplied)
                .orderBonusApplied(orderBonusApplied);

        // --- Composite score ------------------------------------------
        double baseScore = baselineAgreementScore * rollingDivergenceScore;
        double rawComposite = baseScore + boundaryBonusApplied
                + orderBonusApplied;
        double backgroundCapApplied = 0.0;
        if (!familyBackedOrBetter) {
            double cap = Math.max(0.0, w.backgroundCap);
            if (rawComposite > cap) {
                backgroundCapApplied = rawComposite - cap;
                rawComposite = cap;
            }
        }
        double compositeScore = clampNonNegative(rawComposite);
        b.backgroundCapApplied(backgroundCapApplied)
                .compositeScore(compositeScore)
                .strongScoreThreshold(w.strongScoreThreshold)
                .weakScoreThreshold(w.weakScoreThreshold);

        // --- Stage gate ----------------------------------------------
        boolean mixedVersionStage = stageKind == TraceWindow.StageKind.POST_STAGE
                || stageKind == TraceWindow.StageKind.POST_FINAL_STAGE
                || stageKind == TraceWindow.StageKind.FAULT_RECOVERY;
        b.stageGatePassed(mixedVersionStage);

        // --- Change gate: any corroboration path clears it ---
        // Boundary crossings, boundary-involved flows, and
        // changed-message payloads are alternative paths.
        boolean changedMessageGatePassed = corroborationFired;
        b.changedMessageGatePassed(changedMessageGatePassed);

        // --- Strength decision ---------------------------------------
        List<String> reasons = new ArrayList<>();
        TraceEvidenceStrength strength = decideStrength(windowFired,
                support.supportClass,
                mixedVersionStage, baselineAgreementScore,
                rollingDivergenceScore, compositeScore,
                rollingOnlyUpgradeCriticalPresent, upgradedBoundaryEventCount,
                boundaryInvolvedRollingFlowCount, safeChangedMessageCount,
                orderDivergenceScore, w, reasons);
        b.traceEvidenceStrength(strength).firingReasons(reasons);

        return b.build();
    }

    // ---------------------------------------------------------------
    // Admission booleans: preserve the Phase 1 contract exactly.
    // ---------------------------------------------------------------
    private static void applyTriDiffAdmissionBooleans(
            DiffComputeMessageTriDiff.MessageTriDiffResult triDiff,
            TraceWindow.StageKind stageKind, boolean windowSimInteresting,
            int rollingExclusiveMinCount,
            double rollingExclusiveFractionThreshold,
            int rollingMissingMinCount, double rollingMissingFractionThreshold,
            TraceWindowGuidanceDecision.Builder b) {
        if (triDiff == null) {
            b.exclusiveInteresting(false).missingInteresting(false)
                    .triDiffInteresting(false)
                    .windowSimInteresting(windowSimInteresting);
            return;
        }
        int rollingExclusive = triDiff.rollingExclusiveCount();
        int rollingMissing = triDiff.rollingMissingCount();
        double exclusiveFraction = triDiff.rollingExclusiveFraction();
        double missingFraction = triDiff.rollingMissingFraction();

        boolean exclusiveInteresting = rollingExclusive >= rollingExclusiveMinCount
                && exclusiveFraction >= rollingExclusiveFractionThreshold;

        boolean preUpgradeStage = stageKind == TraceWindow.StageKind.PRE_UPGRADE;
        boolean missingInteresting = !preUpgradeStage
                && rollingMissing >= rollingMissingMinCount
                && missingFraction >= rollingMissingFractionThreshold;

        // Phase 1 hotfix: missing-only windows do not admit seeds.
        boolean triDiffInteresting = exclusiveInteresting;

        b.exclusiveInteresting(exclusiveInteresting)
                .missingInteresting(missingInteresting)
                .triDiffInteresting(triDiffInteresting)
                .windowSimInteresting(windowSimInteresting);
    }

    // ---------------------------------------------------------------
    // Support classification.
    // ---------------------------------------------------------------
    private static SupportSummary computeSupport(
            Map<ProtocolFamily, Integer> oo,
            Map<ProtocolFamily, Integer> ro, Map<ProtocolFamily, Integer> nn,
            Map<TraceFlowKey, Integer> ooFlows,
            Map<TraceFlowKey, Integer> roFlows,
            Map<TraceFlowKey, Integer> nnFlows) {
        int familySupportCount = 0;
        int backgroundFamilySupportCount = 0;
        int upgradeCriticalSupportCount = 0;
        Set<ProtocolFamily> familiesInAllThree = new HashSet<>(oo.keySet());
        familiesInAllThree.retainAll(ro.keySet());
        familiesInAllThree.retainAll(nn.keySet());
        for (ProtocolFamily family : familiesInAllThree) {
            familySupportCount++;
            ProtocolFamilyClass clazz = family.familyClass();
            if (clazz == ProtocolFamilyClass.UPGRADE_CRITICAL) {
                upgradeCriticalSupportCount++;
            } else if (clazz == ProtocolFamilyClass.BACKGROUND) {
                backgroundFamilySupportCount++;
            }
        }

        int flowSupportCount = 0;
        int explicitFlowSupportCount = 0;
        Set<TraceFlowKey> flowsInAllThree = new HashSet<>(ooFlows.keySet());
        flowsInAllThree.retainAll(roFlows.keySet());
        flowsInAllThree.retainAll(nnFlows.keySet());
        for (TraceFlowKey key : flowsInAllThree) {
            flowSupportCount++;
            if (key.hasExplicitCorrelation()) {
                explicitFlowSupportCount++;
            }
        }

        Set<TraceFlowKey> baselineFlows = new HashSet<>(ooFlows.keySet());
        baselineFlows.retainAll(nnFlows.keySet());
        int baselineFlowSupportCount = baselineFlows.size();

        TraceSupportClass supportClass;
        if (familySupportCount == 0) {
            supportClass = TraceSupportClass.UNSUPPORTED;
        } else if (upgradeCriticalSupportCount == 0
                && backgroundFamilySupportCount == 0) {
            // Only UNKNOWN families overlap. Plan rule: "UNKNOWN never
            // becomes strong support by itself"; treated as UNSUPPORTED
            // so the uncategorized tail cannot masquerade as background
            // stability.
            supportClass = TraceSupportClass.UNSUPPORTED;
        } else if (upgradeCriticalSupportCount == 0) {
            // Only BACKGROUND (plus possibly UNKNOWN) families overlap.
            // Plan rule: background overlap cannot by itself create
            // strong support.
            supportClass = TraceSupportClass.BACKGROUND_ONLY;
        } else if (explicitFlowSupportCount > 0) {
            supportClass = TraceSupportClass.FULL;
        } else if (flowSupportCount > 0) {
            supportClass = TraceSupportClass.FLOW_BACKED;
        } else {
            supportClass = TraceSupportClass.FAMILY_BACKED;
        }
        return new SupportSummary(supportClass, familySupportCount,
                flowSupportCount,
                baselineFlowSupportCount, backgroundFamilySupportCount,
                upgradeCriticalSupportCount);
    }

    // ---------------------------------------------------------------
    // Rolling-only upgrade-critical event count.
    // ---------------------------------------------------------------
    private static int countRollingExclusiveUpgradeCriticalEvents(
            Map<ProtocolFamily, Integer> oo, Map<ProtocolFamily, Integer> ro,
            Map<ProtocolFamily, Integer> nn) {
        int sum = 0;
        for (Map.Entry<ProtocolFamily, Integer> entry : ro.entrySet()) {
            ProtocolFamily family = entry.getKey();
            if (family.familyClass() != ProtocolFamilyClass.UPGRADE_CRITICAL) {
                continue;
            }
            int roCount = entry.getValue();
            int ooCount = oo.getOrDefault(family, 0);
            int nnCount = nn.getOrDefault(family, 0);
            if (ooCount == 0 && nnCount == 0) {
                sum += roCount;
            }
        }
        return sum;
    }

    // ---------------------------------------------------------------
    // Dominant family helpers (observability only).
    // ---------------------------------------------------------------
    private static ProtocolFamily findDominantSupportedFamily(
            Map<ProtocolFamily, Integer> oo,
            Map<ProtocolFamily, Integer> ro, Map<ProtocolFamily, Integer> nn) {
        ProtocolFamily best = null;
        int bestMin = -1;
        int bestClassRank = -1;
        for (ProtocolFamily family : unionFamilies(oo, ro, nn)) {
            int shared = Math.min(oo.getOrDefault(family, 0),
                    Math.min(ro.getOrDefault(family, 0),
                            nn.getOrDefault(family, 0)));
            if (shared <= 0) {
                continue;
            }
            int classRank = classRank(family.familyClass());
            if (classRank > bestClassRank
                    || (classRank == bestClassRank && shared > bestMin)) {
                best = family;
                bestMin = shared;
                bestClassRank = classRank;
            }
        }
        return best;
    }

    private static ProtocolFamily findDominantDivergentFamily(
            Map<ProtocolFamily, Integer> oo,
            Map<ProtocolFamily, Integer> ro, Map<ProtocolFamily, Integer> nn) {
        ProtocolFamily best = null;
        double bestGap = 0.0;
        int bestClassRank = -1;
        for (ProtocolFamily family : unionFamilies(oo, ro, nn)) {
            int r = ro.getOrDefault(family, 0);
            int o = oo.getOrDefault(family, 0);
            int n = nn.getOrDefault(family, 0);
            double mean = (o + n) / 2.0;
            double gap = Math.abs(r - mean);
            if (gap <= 0) {
                continue;
            }
            int classRank = classRank(family.familyClass());
            if (classRank > bestClassRank
                    || (classRank == bestClassRank && gap > bestGap)) {
                best = family;
                bestGap = gap;
                bestClassRank = classRank;
            }
        }
        return best;
    }

    /**
     * Return the rolling-lane UPGRADE_CRITICAL family that most often
     * appears at the first mismatch position between the rolling lane's
     * per-role-pair compressed family sequence and either baseline
     * sequence. When the rolling lane's per-role-pair sequences match
     * both baselines identically (no order mismatch exists on any
     * shared pair), this returns {@code null} — the
     * {@code dominant_order_anomalous_family} CSV column must reflect a
     * real order anomaly, not an arbitrary overlap.
     *
     * <p>Deterministic tiebreak: when several families tie on mismatch
     * count, the family with the lower {@link ProtocolFamily#ordinal()}
     * wins, so the emitted field is stable across reruns and replay.
     */
    static ProtocolFamily findDominantOrderAnomalousFamily(
            Map<String, List<ProtocolFamily>> oo,
            Map<String, List<ProtocolFamily>> ro,
            Map<String, List<ProtocolFamily>> nn) {
        if (ro == null || ro.isEmpty()) {
            return null;
        }
        EnumMap<ProtocolFamily, Integer> mismatchCounts = new EnumMap<>(
                ProtocolFamily.class);
        for (Map.Entry<String, List<ProtocolFamily>> roEntry : ro.entrySet()) {
            String pair = roEntry.getKey();
            List<ProtocolFamily> roFiltered = FamilyFlowSimilarity
                    .filterByClass(
                            roEntry.getValue(),
                            ProtocolFamilyClass.UPGRADE_CRITICAL);
            if (roFiltered.isEmpty()) {
                continue;
            }
            recordFirstOrderMismatch(roFiltered,
                    oo == null ? null : oo.get(pair), mismatchCounts);
            recordFirstOrderMismatch(roFiltered,
                    nn == null ? null : nn.get(pair), mismatchCounts);
        }
        if (mismatchCounts.isEmpty()) {
            return null;
        }
        ProtocolFamily best = null;
        int bestCount = Integer.MIN_VALUE;
        for (Map.Entry<ProtocolFamily, Integer> entry : mismatchCounts
                .entrySet()) {
            ProtocolFamily family = entry.getKey();
            int count = entry.getValue();
            if (count > bestCount || (count == bestCount && best != null
                    && family.ordinal() < best.ordinal())) {
                best = family;
                bestCount = count;
            }
        }
        return best;
    }

    /**
     * Walk two filtered compressed-family sequences for a single
     * role-pair and record the rolling-side family at the first index
     * where the sequences diverge. If the rolling sequence extends past
     * the baseline's length after a matching prefix, the first extra
     * rolling entry is recorded instead. Matching (or empty) baselines
     * contribute nothing.
     */
    private static void recordFirstOrderMismatch(
            List<ProtocolFamily> roFiltered,
            List<ProtocolFamily> baselineRaw,
            EnumMap<ProtocolFamily, Integer> mismatchCounts) {
        if (baselineRaw == null) {
            return;
        }
        List<ProtocolFamily> baselineFiltered = FamilyFlowSimilarity
                .filterByClass(baselineRaw,
                        ProtocolFamilyClass.UPGRADE_CRITICAL);
        if (baselineFiltered.isEmpty()) {
            return;
        }
        int min = Math.min(roFiltered.size(), baselineFiltered.size());
        for (int i = 0; i < min; i++) {
            ProtocolFamily roFamily = roFiltered.get(i);
            if (roFamily != baselineFiltered.get(i)) {
                mismatchCounts.merge(roFamily, 1, Integer::sum);
                return;
            }
        }
        // Sequences agree on the shared prefix. If the rolling side is
        // longer, the first extra entry is the anomaly.
        if (roFiltered.size() > min) {
            mismatchCounts.merge(roFiltered.get(min), 1, Integer::sum);
        }
    }

    // ---------------------------------------------------------------
    // Final strength decision.
    // ---------------------------------------------------------------
    private static TraceEvidenceStrength decideStrength(boolean windowFired,
            TraceSupportClass supportClass, boolean mixedVersionStage,
            double baselineAgreement,
            double rollingDivergence, double compositeScore,
            boolean rollingOnlyUpgradeCriticalPresent,
            int upgradedBoundaryEventCount,
            int boundaryInvolvedRollingFlowCount, int changedMessageCount,
            double orderDivergenceScore, Weights w, List<String> reasons) {
        if (!windowFired) {
            return TraceEvidenceStrength.NONE;
        }
        if (supportClass == TraceSupportClass.UNSUPPORTED) {
            if (rollingOnlyUpgradeCriticalPresent) {
                reasons.add("rolling_only_upgrade_critical");
                return TraceEvidenceStrength.UNSUPPORTED_BUT_REPEATABLE;
            }
            return TraceEvidenceStrength.UNSUPPORTED;
        }
        if (supportClass == TraceSupportClass.BACKGROUND_ONLY) {
            reasons.add("background_only_cap");
            // BACKGROUND_ONLY windows can still reach WEAK (they fired),
            // but never STRONG. The background cap has already clipped
            // the composite score above traceBackgroundCap.
            return compositeScore >= w.weakScoreThreshold
                    ? TraceEvidenceStrength.WEAK
                    : TraceEvidenceStrength.WEAK;
        }
        if (!mixedVersionStage) {
            reasons.add("stage_not_mixed_version");
            return TraceEvidenceStrength.WEAK;
        }
        boolean strongOk = supportClass.atLeast(TraceSupportClass.FAMILY_BACKED)
                && baselineAgreement >= w.minBaselineAgreementForStrong
                && rollingDivergence >= w.minRollingDivergenceForStrong
                && compositeScore >= w.strongScoreThreshold;
        if (strongOk) {
            reasons.add("composite_reached_strong");
            if (upgradedBoundaryEventCount > 0
                    || boundaryInvolvedRollingFlowCount > 0) {
                reasons.add("boundary_corroborated");
            }
            if (changedMessageCount > 0) {
                reasons.add("changed_message_corroborated");
            }
            if (orderDivergenceScore > 0.0
                    && supportClass.atLeast(TraceSupportClass.FLOW_BACKED)) {
                reasons.add("order_signal_present");
            }
            return TraceEvidenceStrength.STRONG;
        }
        if (baselineAgreement < w.minBaselineAgreementForStrong) {
            reasons.add("baseline_agreement_low");
        }
        if (rollingDivergence < w.minRollingDivergenceForStrong) {
            reasons.add("rolling_divergence_low");
        }
        if (compositeScore >= w.weakScoreThreshold) {
            reasons.add("composite_reached_weak");
        } else {
            reasons.add("composite_below_weak");
        }
        return TraceEvidenceStrength.WEAK;
    }

    // ---------------------------------------------------------------
    // Helpers.
    // ---------------------------------------------------------------
    private static double clampUnit(double v) {
        if (Double.isNaN(v)) {
            return 0.0;
        }
        if (v < 0.0) {
            return 0.0;
        }
        if (v > 1.0) {
            return 1.0;
        }
        return v;
    }

    private static double clampNonNegative(double v) {
        if (Double.isNaN(v) || v < 0.0) {
            return 0.0;
        }
        return v;
    }

    private static int classRank(ProtocolFamilyClass clazz) {
        if (clazz == null) {
            return 0;
        }
        switch (clazz) {
        case UPGRADE_CRITICAL:
            return 2;
        case UNKNOWN:
            return 1;
        case BACKGROUND:
        default:
            return 0;
        }
    }

    private static Set<ProtocolFamily> unionFamilies(
            Map<ProtocolFamily, Integer> oo,
            Map<ProtocolFamily, Integer> ro, Map<ProtocolFamily, Integer> nn) {
        Set<ProtocolFamily> set = new HashSet<>();
        if (oo != null) {
            set.addAll(oo.keySet());
        }
        if (ro != null) {
            set.addAll(ro.keySet());
        }
        if (nn != null) {
            set.addAll(nn.keySet());
        }
        return set;
    }

    private static Weights defaultWeights() {
        // Safe defaults used when a unit test constructs a scorer without
        // going through Config. These mirror the production defaults in
        // Config.Configuration but are duplicated here so ssg-runtime-level
        // replays are not coupled to upfuzz-shuai's Config.
        return new Weights(
                /* upgradeCriticalWeight */ 1.0,
                /* backgroundWeight */ 0.2,
                /* unknownWeight */ 0.4,
                /* strongScoreThreshold */ 0.35,
                /* weakScoreThreshold */ 0.10,
                /* boundaryBonus */ 0.15,
                /* orderBonusCap */ 0.10,
                /* backgroundCap */ 0.10,
                /* minBaselineAgreementForStrong */ 0.55,
                /* minRollingDivergenceForStrong */ 0.20);
    }

    private static final class SupportSummary {
        final TraceSupportClass supportClass;
        final int familySupportCount;
        final int flowSupportCount;
        final int baselineFlowSupportCount;
        final int backgroundFamilySupportCount;
        final int upgradeCriticalSupportCount;

        SupportSummary(TraceSupportClass supportClass, int familySupportCount,
                int flowSupportCount, int baselineFlowSupportCount,
                int backgroundFamilySupportCount,
                int upgradeCriticalSupportCount) {
            this.supportClass = supportClass;
            this.familySupportCount = familySupportCount;
            this.flowSupportCount = flowSupportCount;
            this.baselineFlowSupportCount = baselineFlowSupportCount;
            this.backgroundFamilySupportCount = backgroundFamilySupportCount;
            this.upgradeCriticalSupportCount = upgradeCriticalSupportCount;
        }
    }

    /** Unused placeholder to silence an unused-imports warning from Collections. */
    @SuppressWarnings("unused")
    private static final Object KEEP_COLLECTIONS_REFERENCE = Collections
            .emptyList();
}
