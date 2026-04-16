package org.zlab.upfuzz.fuzzingengine.server.observability;

/**
 * Phase 5 classification of a round's branch coverage novelty by source.
 *
 * <p>The ordering of the enum values reflects scheduling value — higher
 * value = more useful for mixed-version fuzzing:
 *
 * <ul>
 *   <li>{@link #ROLLING_POST_UPGRADE}: new probes appeared in the
 *       rolling lane's upgraded (new-version) coverage that did NOT
 *       appear in the new-new baseline. This is the most valuable
 *       novelty class because it represents coverage uniquely exposed
 *       by the rolling-upgrade experience.</li>
 *   <li>{@link #ROLLING_PRE_UPGRADE_ONLY}: new probes appeared in the
 *       rolling lane's original (old-version) coverage that did NOT
 *       appear in the old-old baseline, but the rolling lane's
 *       upgraded side contributed nothing novel. This is medium-value
 *       — the rolling cluster exercised different pre-upgrade paths,
 *       which may or may not matter post-upgrade.</li>
 *   <li>{@link #SHARED}: all new probes appeared in both the baseline
 *       and rolling lanes. This is generic coverage — it would have
 *       been found without the rolling-upgrade path.</li>
 *   <li>{@link #BASELINE_ONLY}: new probes appeared only in the
 *       baseline lanes (old-old and/or new-new), not in rolling.
 *       This is the least useful for mixed-version guidance.</li>
 *   <li>{@link #NONE}: no new branch probes this round.</li>
 * </ul>
 *
 * <p>The scheduler uses this classification to boost the score of
 * rolling-post-upgrade seeds (they get more mutation energy) and to
 * avoid spending main-exploit budget on baseline-only novelty.
 */
public enum BranchNoveltyClass {
    ROLLING_POST_UPGRADE, ROLLING_PRE_UPGRADE_ONLY, SHARED, BASELINE_ONLY, NONE
}
