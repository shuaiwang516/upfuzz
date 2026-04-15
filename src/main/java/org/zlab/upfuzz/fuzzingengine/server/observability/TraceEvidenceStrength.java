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
 *   <li>{@link #UNSUPPORTED} — some windows fired but none were backed
 *       by three-way shared support. These are the {@code all3=0}
 *       windows that Apr15 diagnostics flagged as unreliable.</li>
 *   <li>{@link #WEAK} — at least one firing window had some baseline
 *       shared support but the stage is still a low-signal stage
 *       (PRE_UPGRADE) or no changed-message / upgraded-boundary event
 *       co-occurred.</li>
 *   <li>{@link #STRONG} — at least one firing window is backed by
 *       three-way shared support, is in a mixed-version-relevant stage,
 *       and has corroborating changed-message or upgraded-boundary
 *       evidence.</li>
 * </ul>
 */
public enum TraceEvidenceStrength {
    NONE, UNSUPPORTED, WEAK, STRONG;
}
