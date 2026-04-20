package org.zlab.upfuzz.fuzzingengine.server.observability;

/**
 * Confidence label for trace evidence admitted this round.
 *
 * <p>Phase 0 only <em>records</em> this label. Later phases consume it to
 * gate trace-only admissions, shrink the short-term queue cost of weak
 * trace-only seeds, and attribute branch novelty back to strong vs weak
 * trace evidence.
 *
 * <ul>
 *   <li>{@link #NONE} — the round produced no interesting trace evidence
 *       (or trace scoring was disabled).</li>
 *   <li>{@link #UNSUPPORTED} — some windows fired but none were backed by
 *       any 3-way support (neither family nor flow overlap existed across
 *       all three lanes). These are the Apr15-style {@code all3=0} windows
 *       that the Phase 3 scorer keeps out of the strong admission path.</li>
 *   <li>{@link #UNSUPPORTED_BUT_REPEATABLE} — Phase 3 addition. The
 *       window lacked 3-way support but carried repeated rolling-only
 *       upgrade-critical traffic (for instance the Apr16 HDFS
 *       {@code 2.10.2 -> 3.3.6} pattern: many strong structured candidates
 *       but no trace-level overlap). Never auto-promoted to
 *       {@link #STRONG}, but preserved as a distinct observability and
 *       scheduler category so it can be exploited carefully later.</li>
 *   <li>{@link #WEAK} — at least one firing window had support but either
 *       the stage is not mixed-version-relevant, the baselines do not
 *       agree strongly enough, or the composite score falls below the
 *       strong threshold.</li>
 *   <li>{@link #STRONG} — the Phase 3 scorer cleared the strong threshold
 *       with non-background support. This is the only label that can
 *       drive trace-backed admission.</li>
 * </ul>
 */
public enum TraceEvidenceStrength {
    NONE, UNSUPPORTED, UNSUPPORTED_BUT_REPEATABLE, WEAK, STRONG;
}
