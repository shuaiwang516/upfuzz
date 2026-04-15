package org.zlab.upfuzz.fuzzingengine.server.observability;

/**
 * Confidence label for structured (Checker D) cross-cluster divergence.
 *
 * <p>Phase 0 only <em>records</em> the label — it does not change admission,
 * promotion, or candidate routing. The classifier runs inside
 * {@code FuzzingServer.updateStatus} so later phases (Phase 1 oracle
 * confidence, Phase 3 value-weighted scheduling) can consume the labels
 * directly instead of re-deriving them from log text.
 *
 * <ul>
 *   <li>{@link #NONE} — no structured divergence observed this round.</li>
 *   <li>{@link #WEAK} — structured divergence exists but at least one
 *       lane reported an unstable outcome (UNKNOWN, DAEMON_ERROR, INFRA
 *       noise). These are the Cassandra checker-D rounds that still
 *       pollute Apr15 candidate counts.</li>
 *   <li>{@link #STRONG} — all three lanes produced stable structured
 *       results and rolling genuinely diverges from both baselines.</li>
 * </ul>
 */
public enum StructuredCandidateStrength {
    NONE, WEAK, STRONG;
}
