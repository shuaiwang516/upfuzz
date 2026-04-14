package org.zlab.upfuzz.fuzzingengine.server.observability;

/**
 * Structured reason codes for corpus admission events.
 *
 * <p>Used by {@link ObservabilityMetrics} to distinguish which rule admitted a
 * seed, so later analysis can separate branch-driven admissions from the
 * different trace-driven admission paths. Keep this enum small and stable —
 * downstream CSVs depend on the string names.
 *
 * <p>Phase 1 note: {@link #TRACE_ONLY_TRIDIFF_MISSING} is retained for
 * historical CSV compatibility but is never produced by the classifier under
 * the current admission policy — missing-message churn is no longer a direct
 * admission path. The fact that missing co-fired on a given round is still
 * captured on the per-round {@code tri_diff_missing_fired} boolean column.
 */
public enum AdmissionReason {
    BRANCH_ONLY, BRANCH_AND_TRACE, TRACE_ONLY_WINDOW_SIM, TRACE_ONLY_TRIDIFF_EXCLUSIVE, TRACE_ONLY_TRIDIFF_MISSING, UNKNOWN;
}
