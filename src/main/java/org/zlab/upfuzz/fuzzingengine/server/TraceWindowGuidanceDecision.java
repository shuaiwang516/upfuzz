package org.zlab.upfuzz.fuzzingengine.server;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import org.zlab.net.tracker.classifier.ProtocolFamily;
import org.zlab.upfuzz.fuzzingengine.server.observability.TraceEvidenceStrength;

/**
 * Phase 3 per-window guidance decision.
 *
 * <p>Replaces the Phase 1/2 {@code TriDiffWindowDecision}: supports the
 * same admission booleans for backwards compatibility with the rolling
 * exclusive admission path, but carries the full Phase 3 component
 * scores, support classification, corroboration counters, and
 * {@link TraceEvidenceStrength} label. The admission path still uses
 * the {@code triDiffInteresting} / {@code exclusiveInteresting} pair —
 * Phase 3 only reshapes the <em>strength</em> decision around the new
 * family / flow / order signals.
 *
 * <p>The tri-execution contract is preserved explicitly: the
 * baseline-baseline agreement score and the rolling-vs-baseline
 * divergence score are stored as separate fields instead of being
 * collapsed into one anomaly scalar. The final composite is a simple
 * product / sum of those explicit components plus bounded bonuses, so
 * offline replay can reproduce every firing decision from the emitted
 * {@code WindowTriggerRow} without re-running the server.
 *
 * <p>The decision is intentionally an immutable value type. It is built
 * through the nested {@link Builder} so the scorer can assemble every
 * component in one pass without surprising the reader with a 40-argument
 * constructor.
 */
public final class TraceWindowGuidanceDecision {

    // --- Admission booleans (preserved from Phase 1/2) ---
    public final boolean exclusiveInteresting;
    public final boolean missingInteresting;
    public final boolean triDiffInteresting;
    public final boolean windowSimInteresting;
    public final boolean windowFired;

    // --- Support classification ---
    public final TraceSupportClass supportClass;
    public final int familySupportCount;
    public final int flowSupportCount;
    public final int baselineFlowSupportCount;
    public final int backgroundFamilySupportCount;
    public final int upgradeCriticalSupportCount;
    public final int detailedAllThreeCount;

    // --- Raw similarity components ---
    public final double familyJaccardOoRo;
    public final double familyJaccardRoNn;
    public final double familyJaccardOoNn;
    public final double familyClassWeightedSimOoRo;
    public final double familyClassWeightedSimRoNn;
    public final double familyClassWeightedSimOoNn;
    public final double flowJaccardOoRo;
    public final double flowJaccardRoNn;
    public final double flowJaccardOoNn;
    public final double explicitFlowJaccardOoRo;
    public final double explicitFlowJaccardRoNn;
    public final double explicitFlowJaccardOoNn;
    public final double orderSimilarityOoRo;
    public final double orderSimilarityRoNn;
    public final double orderSimilarityOoNn;

    // --- Tri-execution components ---
    public final double baselineAgreementScore;
    public final double rollingDivergenceScore;
    public final double orderDivergenceScore;
    public final double backgroundShareRolling;

    // --- Bonuses / penalties (observability) ---
    public final double boundaryBonusApplied;
    public final double orderBonusApplied;
    public final double backgroundCapApplied;

    // --- Final score and strength ---
    public final double compositeScore;
    public final double strongScoreThreshold;
    public final double weakScoreThreshold;
    public final TraceEvidenceStrength traceEvidenceStrength;

    // --- Corroboration counters ---
    public final int upgradedBoundaryEventCount;
    public final int boundaryInvolvedRollingFlowCount;
    public final int rollingExclusiveUpgradeCriticalEventCount;
    /**
     * Number of rolling-lane messages whose payload class changed between
     * the old and new versions. Preserved as a live corroborator for
     * non-mode-5 rolling runs (see
     * {@link org.zlab.upfuzz.fuzzingengine.Config.Configuration#useChangedMessageRollingTraceCorroboration()}).
     * Mode 5 retires this signal and the field is always {@code 0} there.
     */
    public final int changedMessageCount;

    // --- Dominant families for offline analysis ---
    public final ProtocolFamily dominantSupportedFamily;
    public final ProtocolFamily dominantDivergentFamily;
    public final ProtocolFamily dominantOrderAnomalousFamily;

    // --- Derived classification flags ---
    public final boolean backgroundOnly;
    public final boolean upgradeCriticalInvolved;
    public final boolean mixedFamilyProfile;
    public final boolean rollingOnlyUpgradeCriticalPresent;

    // --- Firing reasons (ordered list of short labels) ---
    public final List<String> firingReasons;

    // --- Legacy gate booleans retained for replay/observability parity ---
    public final boolean supportGatePassed;
    public final boolean stageGatePassed;
    public final boolean changedMessageGatePassed;

    private TraceWindowGuidanceDecision(Builder b) {
        this.exclusiveInteresting = b.exclusiveInteresting;
        this.missingInteresting = b.missingInteresting;
        this.triDiffInteresting = b.triDiffInteresting;
        this.windowSimInteresting = b.windowSimInteresting;
        this.windowFired = b.windowFired;
        this.supportClass = b.supportClass;
        this.familySupportCount = b.familySupportCount;
        this.flowSupportCount = b.flowSupportCount;
        this.baselineFlowSupportCount = b.baselineFlowSupportCount;
        this.backgroundFamilySupportCount = b.backgroundFamilySupportCount;
        this.upgradeCriticalSupportCount = b.upgradeCriticalSupportCount;
        this.detailedAllThreeCount = b.detailedAllThreeCount;
        this.familyJaccardOoRo = b.familyJaccardOoRo;
        this.familyJaccardRoNn = b.familyJaccardRoNn;
        this.familyJaccardOoNn = b.familyJaccardOoNn;
        this.familyClassWeightedSimOoRo = b.familyClassWeightedSimOoRo;
        this.familyClassWeightedSimRoNn = b.familyClassWeightedSimRoNn;
        this.familyClassWeightedSimOoNn = b.familyClassWeightedSimOoNn;
        this.flowJaccardOoRo = b.flowJaccardOoRo;
        this.flowJaccardRoNn = b.flowJaccardRoNn;
        this.flowJaccardOoNn = b.flowJaccardOoNn;
        this.explicitFlowJaccardOoRo = b.explicitFlowJaccardOoRo;
        this.explicitFlowJaccardRoNn = b.explicitFlowJaccardRoNn;
        this.explicitFlowJaccardOoNn = b.explicitFlowJaccardOoNn;
        this.orderSimilarityOoRo = b.orderSimilarityOoRo;
        this.orderSimilarityRoNn = b.orderSimilarityRoNn;
        this.orderSimilarityOoNn = b.orderSimilarityOoNn;
        this.baselineAgreementScore = b.baselineAgreementScore;
        this.rollingDivergenceScore = b.rollingDivergenceScore;
        this.orderDivergenceScore = b.orderDivergenceScore;
        this.backgroundShareRolling = b.backgroundShareRolling;
        this.boundaryBonusApplied = b.boundaryBonusApplied;
        this.orderBonusApplied = b.orderBonusApplied;
        this.backgroundCapApplied = b.backgroundCapApplied;
        this.compositeScore = b.compositeScore;
        this.strongScoreThreshold = b.strongScoreThreshold;
        this.weakScoreThreshold = b.weakScoreThreshold;
        this.traceEvidenceStrength = b.traceEvidenceStrength == null
                ? TraceEvidenceStrength.NONE
                : b.traceEvidenceStrength;
        this.upgradedBoundaryEventCount = b.upgradedBoundaryEventCount;
        this.boundaryInvolvedRollingFlowCount = b.boundaryInvolvedRollingFlowCount;
        this.rollingExclusiveUpgradeCriticalEventCount = b.rollingExclusiveUpgradeCriticalEventCount;
        this.changedMessageCount = b.changedMessageCount;
        this.dominantSupportedFamily = b.dominantSupportedFamily;
        this.dominantDivergentFamily = b.dominantDivergentFamily;
        this.dominantOrderAnomalousFamily = b.dominantOrderAnomalousFamily;
        this.backgroundOnly = b.backgroundOnly;
        this.upgradeCriticalInvolved = b.upgradeCriticalInvolved;
        this.mixedFamilyProfile = b.mixedFamilyProfile;
        this.rollingOnlyUpgradeCriticalPresent = b.rollingOnlyUpgradeCriticalPresent;
        this.firingReasons = b.firingReasons == null
                ? Collections.<String> emptyList()
                : Collections
                        .unmodifiableList(new ArrayList<>(b.firingReasons));
        this.supportGatePassed = b.supportGatePassed;
        this.stageGatePassed = b.stageGatePassed;
        this.changedMessageGatePassed = b.changedMessageGatePassed;
    }

    /** Compact category label for {@code WindowTriggerRow}. */
    public String familyProfileLabel() {
        if (backgroundOnly) {
            return "BACKGROUND_ONLY";
        }
        if (mixedFamilyProfile) {
            return "MIXED";
        }
        if (upgradeCriticalInvolved) {
            return "UPGRADE_CRITICAL";
        }
        return "OTHER";
    }

    public String firingReasonJoined() {
        if (firingReasons.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < firingReasons.size(); i++) {
            if (i > 0) {
                sb.append('|');
            }
            sb.append(firingReasons.get(i));
        }
        return sb.toString();
    }

    public static Builder builder() {
        return new Builder();
    }

    /**
     * Mutable assembler used by {@link TraceWindowGuidanceScorer}. Every
     * field has a sensible zero default so the scorer can fill in only the
     * components it computed for a given window without worrying about
     * missing null-handling down the line.
     */
    public static final class Builder {
        boolean exclusiveInteresting;
        boolean missingInteresting;
        boolean triDiffInteresting;
        boolean windowSimInteresting;
        boolean windowFired;
        TraceSupportClass supportClass = TraceSupportClass.UNSUPPORTED;
        int familySupportCount;
        int flowSupportCount;
        int baselineFlowSupportCount;
        int backgroundFamilySupportCount;
        int upgradeCriticalSupportCount;
        int detailedAllThreeCount;
        double familyJaccardOoRo;
        double familyJaccardRoNn;
        double familyJaccardOoNn;
        double familyClassWeightedSimOoRo;
        double familyClassWeightedSimRoNn;
        double familyClassWeightedSimOoNn;
        double flowJaccardOoRo;
        double flowJaccardRoNn;
        double flowJaccardOoNn;
        double explicitFlowJaccardOoRo;
        double explicitFlowJaccardRoNn;
        double explicitFlowJaccardOoNn;
        double orderSimilarityOoRo;
        double orderSimilarityRoNn;
        double orderSimilarityOoNn;
        double baselineAgreementScore;
        double rollingDivergenceScore;
        double orderDivergenceScore;
        double backgroundShareRolling;
        double boundaryBonusApplied;
        double orderBonusApplied;
        double backgroundCapApplied;
        double compositeScore;
        double strongScoreThreshold;
        double weakScoreThreshold;
        TraceEvidenceStrength traceEvidenceStrength = TraceEvidenceStrength.NONE;
        int upgradedBoundaryEventCount;
        int boundaryInvolvedRollingFlowCount;
        int rollingExclusiveUpgradeCriticalEventCount;
        int changedMessageCount;
        ProtocolFamily dominantSupportedFamily;
        ProtocolFamily dominantDivergentFamily;
        ProtocolFamily dominantOrderAnomalousFamily;
        boolean backgroundOnly;
        boolean upgradeCriticalInvolved;
        boolean mixedFamilyProfile;
        boolean rollingOnlyUpgradeCriticalPresent;
        List<String> firingReasons;
        boolean supportGatePassed;
        boolean stageGatePassed;
        boolean changedMessageGatePassed;

        public Builder exclusiveInteresting(boolean v) {
            this.exclusiveInteresting = v;
            return this;
        }

        public Builder missingInteresting(boolean v) {
            this.missingInteresting = v;
            return this;
        }

        public Builder triDiffInteresting(boolean v) {
            this.triDiffInteresting = v;
            return this;
        }

        public Builder windowSimInteresting(boolean v) {
            this.windowSimInteresting = v;
            return this;
        }

        public Builder windowFired(boolean v) {
            this.windowFired = v;
            return this;
        }

        public Builder supportClass(TraceSupportClass v) {
            this.supportClass = v == null ? TraceSupportClass.UNSUPPORTED : v;
            return this;
        }

        public Builder familySupportCount(int v) {
            this.familySupportCount = v;
            return this;
        }

        public Builder flowSupportCount(int v) {
            this.flowSupportCount = v;
            return this;
        }

        public Builder baselineFlowSupportCount(int v) {
            this.baselineFlowSupportCount = v;
            return this;
        }

        public Builder backgroundFamilySupportCount(int v) {
            this.backgroundFamilySupportCount = v;
            return this;
        }

        public Builder upgradeCriticalSupportCount(int v) {
            this.upgradeCriticalSupportCount = v;
            return this;
        }

        public Builder detailedAllThreeCount(int v) {
            this.detailedAllThreeCount = v;
            return this;
        }

        public Builder familyJaccards(double ooRo, double roNn, double ooNn) {
            this.familyJaccardOoRo = ooRo;
            this.familyJaccardRoNn = roNn;
            this.familyJaccardOoNn = ooNn;
            return this;
        }

        public Builder familyClassWeighted(double ooRo, double roNn,
                double ooNn) {
            this.familyClassWeightedSimOoRo = ooRo;
            this.familyClassWeightedSimRoNn = roNn;
            this.familyClassWeightedSimOoNn = ooNn;
            return this;
        }

        public Builder flowJaccards(double ooRo, double roNn, double ooNn) {
            this.flowJaccardOoRo = ooRo;
            this.flowJaccardRoNn = roNn;
            this.flowJaccardOoNn = ooNn;
            return this;
        }

        public Builder explicitFlowJaccards(double ooRo, double roNn,
                double ooNn) {
            this.explicitFlowJaccardOoRo = ooRo;
            this.explicitFlowJaccardRoNn = roNn;
            this.explicitFlowJaccardOoNn = ooNn;
            return this;
        }

        public Builder orderSimilarities(double ooRo, double roNn,
                double ooNn) {
            this.orderSimilarityOoRo = ooRo;
            this.orderSimilarityRoNn = roNn;
            this.orderSimilarityOoNn = ooNn;
            return this;
        }

        public Builder baselineAgreementScore(double v) {
            this.baselineAgreementScore = v;
            return this;
        }

        public Builder rollingDivergenceScore(double v) {
            this.rollingDivergenceScore = v;
            return this;
        }

        public Builder orderDivergenceScore(double v) {
            this.orderDivergenceScore = v;
            return this;
        }

        public Builder backgroundShareRolling(double v) {
            this.backgroundShareRolling = v;
            return this;
        }

        public Builder boundaryBonusApplied(double v) {
            this.boundaryBonusApplied = v;
            return this;
        }

        public Builder orderBonusApplied(double v) {
            this.orderBonusApplied = v;
            return this;
        }

        public Builder backgroundCapApplied(double v) {
            this.backgroundCapApplied = v;
            return this;
        }

        public Builder compositeScore(double v) {
            this.compositeScore = v;
            return this;
        }

        public Builder strongScoreThreshold(double v) {
            this.strongScoreThreshold = v;
            return this;
        }

        public Builder weakScoreThreshold(double v) {
            this.weakScoreThreshold = v;
            return this;
        }

        public Builder traceEvidenceStrength(TraceEvidenceStrength v) {
            this.traceEvidenceStrength = v;
            return this;
        }

        public Builder upgradedBoundaryEventCount(int v) {
            this.upgradedBoundaryEventCount = v;
            return this;
        }

        public Builder boundaryInvolvedRollingFlowCount(int v) {
            this.boundaryInvolvedRollingFlowCount = v;
            return this;
        }

        public Builder rollingExclusiveUpgradeCriticalEventCount(int v) {
            this.rollingExclusiveUpgradeCriticalEventCount = v;
            return this;
        }

        public Builder changedMessageCount(int v) {
            this.changedMessageCount = v;
            return this;
        }

        public Builder dominantSupportedFamily(ProtocolFamily v) {
            this.dominantSupportedFamily = v;
            return this;
        }

        public Builder dominantDivergentFamily(ProtocolFamily v) {
            this.dominantDivergentFamily = v;
            return this;
        }

        public Builder dominantOrderAnomalousFamily(ProtocolFamily v) {
            this.dominantOrderAnomalousFamily = v;
            return this;
        }

        public Builder backgroundOnly(boolean v) {
            this.backgroundOnly = v;
            return this;
        }

        public Builder upgradeCriticalInvolved(boolean v) {
            this.upgradeCriticalInvolved = v;
            return this;
        }

        public Builder mixedFamilyProfile(boolean v) {
            this.mixedFamilyProfile = v;
            return this;
        }

        public Builder rollingOnlyUpgradeCriticalPresent(boolean v) {
            this.rollingOnlyUpgradeCriticalPresent = v;
            return this;
        }

        public Builder firingReasons(List<String> v) {
            this.firingReasons = v;
            return this;
        }

        public Builder supportGatePassed(boolean v) {
            this.supportGatePassed = v;
            return this;
        }

        public Builder stageGatePassed(boolean v) {
            this.stageGatePassed = v;
            return this;
        }

        public Builder changedMessageGatePassed(boolean v) {
            this.changedMessageGatePassed = v;
            return this;
        }

        public TraceWindowGuidanceDecision build() {
            return new TraceWindowGuidanceDecision(this);
        }
    }
}
