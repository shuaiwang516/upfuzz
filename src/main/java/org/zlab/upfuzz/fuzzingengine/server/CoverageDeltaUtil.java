package org.zlab.upfuzz.fuzzingengine.server;

import org.zlab.upfuzz.fuzzingengine.Config;
import org.zlab.upfuzz.fuzzingengine.server.observability.BranchNoveltyClass;

/**
 * Phase 5 helper for classifying per-round branch novelty and mapping
 * it to scheduler score boosts.
 *
 * <p>All methods are stateless and package-visible so both
 * {@link FuzzingServer} and {@link TestPlanCorpus} can call them
 * without circular dependencies.
 */
public final class CoverageDeltaUtil {
    private CoverageDeltaUtil() {
    }

    /**
     * Classify this round's branch coverage novelty based on the probe
     * counts computed by Phase 0 attribution in
     * {@code FuzzingServer.updateStatus}.
     *
     * <p>The classification is a strict waterfall: the highest-value
     * class whose probes are non-zero wins. When multiple classes have
     * probes, the round is labelled by the highest because the
     * scheduler should reward the <em>most useful</em> novelty this
     * round produced.
     */
    public static BranchNoveltyClass classifyNovelty(
            int oldVersionBaselineOnlyProbes,
            int oldVersionRollingOnlyProbes,
            int oldVersionSharedProbes,
            int newVersionBaselineOnlyProbes,
            int newVersionRollingOnlyProbes,
            int newVersionSharedProbes) {
        int total = oldVersionBaselineOnlyProbes + oldVersionRollingOnlyProbes
                + oldVersionSharedProbes + newVersionBaselineOnlyProbes
                + newVersionRollingOnlyProbes + newVersionSharedProbes;
        if (total == 0) {
            return BranchNoveltyClass.NONE;
        }
        if (newVersionRollingOnlyProbes > 0) {
            return BranchNoveltyClass.ROLLING_POST_UPGRADE;
        }
        if (oldVersionRollingOnlyProbes > 0) {
            return BranchNoveltyClass.ROLLING_PRE_UPGRADE_ONLY;
        }
        if (oldVersionSharedProbes > 0 || newVersionSharedProbes > 0) {
            return BranchNoveltyClass.SHARED;
        }
        if (oldVersionBaselineOnlyProbes > 0
                || newVersionBaselineOnlyProbes > 0) {
            return BranchNoveltyClass.BASELINE_ONLY;
        }
        return BranchNoveltyClass.NONE;
    }

    /**
     * Return a per-version novelty source label for observability CSVs.
     * Classifies which lane contributed novelty for one version axis.
     */
    public static String classifyVersionNoveltySource(
            int baselineOnlyProbes,
            int rollingOnlyProbes,
            int sharedProbes) {
        if (baselineOnlyProbes == 0 && rollingOnlyProbes == 0
                && sharedProbes == 0) {
            return "NONE";
        }
        if (rollingOnlyProbes > 0 && baselineOnlyProbes == 0) {
            return "ROLLING_ONLY";
        }
        if (baselineOnlyProbes > 0 && rollingOnlyProbes == 0) {
            return "BASELINE_ONLY";
        }
        if (rollingOnlyProbes > 0 && baselineOnlyProbes > 0) {
            return "MIXED";
        }
        return "SHARED";
    }

    /**
     * Compute the scheduler score boost for a given novelty class.
     * Reads boost values from config when available; falls back to
     * hard-coded defaults so tests and offline replay still work
     * without a live config.
     */
    public static double noveltyScoreBoost(BranchNoveltyClass noveltyClass) {
        if (noveltyClass == null) {
            return 0.0;
        }
        Config.Configuration cfg = Config.getConf();
        switch (noveltyClass) {
        case ROLLING_POST_UPGRADE:
            return cfg != null
                    ? cfg.rollingPostUpgradeScoreBoost
                    : 3.0;
        case ROLLING_PRE_UPGRADE_ONLY:
            return cfg != null
                    ? cfg.rollingPreUpgradeScoreBoost
                    : 0.5;
        case SHARED:
            return cfg != null ? cfg.sharedNoveltyScoreBoost : 1.0;
        case BASELINE_ONLY:
            return cfg != null ? cfg.baselineOnlyScoreBoost : 0.5;
        default:
            return 0.0;
        }
    }
}
