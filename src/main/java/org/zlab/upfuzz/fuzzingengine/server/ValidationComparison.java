package org.zlab.upfuzz.fuzzingengine.server;

import org.zlab.upfuzz.fuzzingengine.server.observability.StructuredCandidateStrength;

/**
 * Phase 1 structured outcome of comparing two {@code List<ValidationResult>}
 * sequences from different lanes (e.g. Old-Old vs Rolling).
 *
 * <p>Supersedes the Phase 0 {@code String}-or-{@code null} return value of
 * {@link ValidationResultComparator#compare}. The string-based API collapsed
 * three concerns into one: "is there a divergence", "what kind", and "how
 * confident". Phase 1 needs all three independently so the checker-D
 * routing layer can separate strong from weak structured candidates
 * without re-parsing report text.
 *
 * <p>Strength follows the Apr15 master plan:
 * <ul>
 *   <li>{@link StructuredCandidateStrength#STRONG} — payload or typed
 *       failure-class divergence where every lane produced a stable
 *       result (stable success or stable domain failure).</li>
 *   <li>{@link StructuredCandidateStrength#WEAK} — any side reported
 *       {@code UNKNOWN} / {@code DAEMON_ERROR} / raw {@code NonZeroExit},
 *       any side was transient ({@code Timeout}, {@code SafeMode}), the
 *       rows had a size mismatch, or an asymmetric success/failure leaned
 *       on an unstable failing side.</li>
 *   <li>{@link StructuredCandidateStrength#NONE} — the two lanes are
 *       equivalent.</li>
 * </ul>
 */
public final class ValidationComparison {

    /** Kind of divergence observed between two validation-result lists. */
    public enum Kind {
        /** Both lanes stable but normalized payloads differ. */
        PAYLOAD_DIVERGENCE,
        /** Both lanes failed with different {@code failureClass}. */
        FAILURE_CLASS_DIVERGENCE,
        /** One lane succeeded while the other failed. */
        ASYMMETRIC_SUCCESS_FAILURE,
        /** One list has fewer results than the other. */
        SIZE_MISMATCH,
        /** The two lanes compared as equivalent. */
        EQUIVALENT;

        /**
         * Higher ordinal = more severe. Used to pick the dominant kind
         * when a list-level comparison aggregates many per-row kinds.
         */
        int severity() {
            switch (this) {
            case PAYLOAD_DIVERGENCE:
                return 4;
            case ASYMMETRIC_SUCCESS_FAILURE:
                return 3;
            case FAILURE_CLASS_DIVERGENCE:
                return 2;
            case SIZE_MISMATCH:
                return 1;
            case EQUIVALENT:
            default:
                return 0;
            }
        }
    }

    public final boolean equivalent;
    public final Kind comparisonKind;
    public final StructuredCandidateStrength strength;
    public final boolean involvesUnknown;
    public final boolean involvesDaemonError;
    /**
     * Human-readable report of the divergence. {@code null} when
     * {@link #equivalent} is true. For list-level comparisons this is the
     * same multi-line text the legacy Phase 0 comparator returned, so
     * existing report writers can embed it unchanged.
     */
    public final String reportLine;

    public ValidationComparison(
            boolean equivalent,
            Kind comparisonKind,
            StructuredCandidateStrength strength,
            boolean involvesUnknown,
            boolean involvesDaemonError,
            String reportLine) {
        this.equivalent = equivalent;
        this.comparisonKind = comparisonKind == null ? Kind.EQUIVALENT
                : comparisonKind;
        this.strength = strength == null ? StructuredCandidateStrength.NONE
                : strength;
        this.involvesUnknown = involvesUnknown;
        this.involvesDaemonError = involvesDaemonError;
        this.reportLine = reportLine;
    }

    public static ValidationComparison equivalent() {
        return new ValidationComparison(
                /*equivalent*/ true,
                Kind.EQUIVALENT,
                StructuredCandidateStrength.NONE,
                /*involvesUnknown*/ false,
                /*involvesDaemonError*/ false,
                /*reportLine*/ null);
    }

    public boolean isDivergent() {
        return !equivalent;
    }
}
