package org.zlab.upfuzz.fuzzingengine.server.observability;

/**
 * Fine-grained label for weak rolling-upgrade candidate rounds.
 *
 * <p>A "weak" candidate is one where the primary signal is not a
 * structured cross-cluster divergence — it is a lane-local event crash,
 * a rolling-only error log, or a structured divergence that fell out of
 * the strong bucket because a lane produced an unstable outcome. Phase 0
 * records the finer-grained label so Phase 1 routing can keep each
 * subclass in its own bucket without re-parsing verdicts from logs.
 *
 * <ul>
 *   <li>{@link #NONE} — not a weak candidate.</li>
 *   <li>{@link #ROLLING_ONLY_EVENT_FAILURE} — the event-crash reached
 *       candidate status because only the rolling lane failed, but the
 *       baselines were stable.</li>
 *   <li>{@link #ROLLING_ONLY_ERROR_LOG} — rolling emitted an ERROR log
 *       line classified as a rolling-upgrade candidate while both
 *       baselines were clean.</li>
 *   <li>{@link #UNSTABLE_STRUCTURED_DIVERGENCE} — Checker D reported a
 *       cross-cluster inconsistency, but at least one lane reported an
 *       UNKNOWN / DAEMON_ERROR outcome, so the structured divergence
 *       should not be treated as a strong signal.</li>
 *   <li>{@link #OTHER} — any other weak candidate class, reserved for
 *       future subclasses.</li>
 * </ul>
 */
public enum WeakCandidateKind {
    NONE, ROLLING_ONLY_EVENT_FAILURE, ROLLING_ONLY_ERROR_LOG, UNSTABLE_STRUCTURED_DIVERGENCE, OTHER;
}
