package org.zlab.upfuzz.fuzzingengine.server;

import java.util.List;

import org.zlab.upfuzz.fuzzingengine.Config;
import org.zlab.upfuzz.fuzzingengine.packet.ValidationResult;
import org.zlab.upfuzz.fuzzingengine.server.observability.StructuredCandidateStrength;
import org.zlab.upfuzz.utils.Utilities;

/**
 * Outcome-aware comparison of structured validation results.
 * Implements the tri-lane comparison matrix:
 * <ul>
 *   <li>success/success → compare normalized payload</li>
 *   <li>fail/fail → compare failureClass strings</li>
 *   <li>success/fail (asymmetric) → always divergence</li>
 * </ul>
 *
 * <p>Phase 1 routing needs every comparison to carry a confidence label so
 * the checker-D layer can split strong from weak structured candidates
 * without re-parsing text. This class therefore returns
 * {@link ValidationComparison} objects instead of the Phase 0 string-or-null
 * API; {@link #compare(List, List, String, String)} aggregates per-row
 * comparisons into a single list-level outcome.
 */
public class ValidationResultComparator {

    /**
     * Compare two lists of structured validation results.
     *
     * @return a list-level {@link ValidationComparison}. Never {@code null}
     *         — an equivalent list pair returns
     *         {@link ValidationComparison#equivalent()}.
     */
    public static ValidationComparison compare(List<ValidationResult> listA,
            List<ValidationResult> listB, String labelA, String labelB) {
        if (listA == null || listB == null) {
            return ValidationComparison.equivalent();
        }
        int sizeA = listA.size();
        int sizeB = listB.size();
        int maxSize = Math.max(sizeA, sizeB);
        if (maxSize == 0) {
            return ValidationComparison.equivalent();
        }

        StringBuilder report = new StringBuilder();
        ValidationComparison.Kind dominantKind = ValidationComparison.Kind.EQUIVALENT;
        StructuredCandidateStrength aggregateStrength = StructuredCandidateStrength.NONE;
        boolean anyDivergence = false;
        boolean involvesUnknown = false;
        boolean involvesDaemonError = false;

        for (int i = 0; i < maxSize; i++) {
            ValidationResult a = i < sizeA ? listA.get(i) : null;
            ValidationResult b = i < sizeB ? listB.get(i) : null;

            involvesUnknown = involvesUnknown
                    || touchesUnstableClass(a, "UNKNOWN")
                    || touchesUnstableClass(b, "UNKNOWN");
            involvesDaemonError = involvesDaemonError
                    || touchesUnstableClass(a, "DAEMON_ERROR")
                    || touchesUnstableClass(b, "DAEMON_ERROR");

            if (a == null || b == null) {
                anyDivergence = true;
                dominantKind = upgradeKind(dominantKind,
                        ValidationComparison.Kind.SIZE_MISMATCH);
                // size mismatches are by definition unstable — one side
                // failed to produce a result at all
                aggregateStrength = mergeStrength(aggregateStrength,
                        StructuredCandidateStrength.WEAK);
                report.append("  [cmd ").append(i)
                        .append("] SIZE MISMATCH: ")
                        .append(labelA).append(" has ")
                        .append(a != null ? "result" : "nothing")
                        .append(", ")
                        .append(labelB).append(" has ")
                        .append(b != null ? "result" : "nothing")
                        .append("\n");
                continue;
            }

            ValidationComparison rowComparison = compareSingle(a, b, labelA,
                    labelB);
            if (!rowComparison.equivalent) {
                anyDivergence = true;
                dominantKind = upgradeKind(dominantKind,
                        rowComparison.comparisonKind);
                aggregateStrength = mergeStrength(aggregateStrength,
                        rowComparison.strength);
                report.append("  [cmd ").append(i).append("] ")
                        .append(rowComparison.reportLine).append("\n");
            }
            involvesUnknown = involvesUnknown
                    || rowComparison.involvesUnknown;
            involvesDaemonError = involvesDaemonError
                    || rowComparison.involvesDaemonError;
        }

        if (!anyDivergence) {
            return ValidationComparison.equivalent();
        }

        String aggregateReport = "Structured validation divergence (" + labelA
                + " vs " + labelB + "):\n" + report;
        return new ValidationComparison(
                /*equivalent*/ false,
                dominantKind,
                aggregateStrength,
                involvesUnknown,
                involvesDaemonError,
                aggregateReport);
    }

    /**
     * Compare a single pair of validation results. Returns a
     * {@link ValidationComparison} describing the row-level outcome.
     */
    static ValidationComparison compareSingle(ValidationResult a,
            ValidationResult b, String labelA, String labelB) {
        boolean aUnknown = a.failureClass != null
                && "UNKNOWN".equals(a.failureClass);
        boolean bUnknown = b.failureClass != null
                && "UNKNOWN".equals(b.failureClass);
        boolean aDaemonError = a.failureClass != null
                && "DAEMON_ERROR".equals(a.failureClass);
        boolean bDaemonError = b.failureClass != null
                && "DAEMON_ERROR".equals(b.failureClass);
        boolean involvesUnknown = aUnknown || bUnknown;
        boolean involvesDaemonError = aDaemonError || bDaemonError;

        boolean aSuccess = a.isSuccess();
        boolean bSuccess = b.isSuccess();

        if (aSuccess && bSuccess) {
            // success/success → compare normalized payload
            String normalizedA = normalize(a.stdout, a.command);
            String normalizedB = normalize(b.stdout, b.command);
            if (normalizedA.equals(normalizedB)) {
                return ValidationComparison.equivalent();
            }
            String reportLine = "PAYLOAD_DIVERGENCE: " + labelA + "="
                    + truncate(a.stdout) + " vs " + labelB + "="
                    + truncate(b.stdout);
            // both sides are stable successes — this is always strong
            return new ValidationComparison(
                    /*equivalent*/ false,
                    ValidationComparison.Kind.PAYLOAD_DIVERGENCE,
                    StructuredCandidateStrength.STRONG,
                    involvesUnknown,
                    involvesDaemonError,
                    reportLine);
        }

        if (!aSuccess && !bSuccess) {
            // fail/fail → compare failure class
            if (a.failureClass.equals(b.failureClass)) {
                return ValidationComparison.equivalent();
            }
            String reportLine = "FAILURE_CLASS_DIVERGENCE: " + labelA + "="
                    + a.failureClass + " vs " + labelB + "="
                    + b.failureClass;
            StructuredCandidateStrength rowStrength = (a.isStableDomainFailure()
                    && b.isStableDomainFailure())
                            ? StructuredCandidateStrength.STRONG
                            : StructuredCandidateStrength.WEAK;
            return new ValidationComparison(
                    /*equivalent*/ false,
                    ValidationComparison.Kind.FAILURE_CLASS_DIVERGENCE,
                    rowStrength,
                    involvesUnknown,
                    involvesDaemonError,
                    reportLine);
        }

        // success/fail asymmetry → always report as divergence
        String reportLine = "ASYMMETRIC: " + labelA + "="
                + (aSuccess ? "SUCCESS" : a.failureClass) + " vs "
                + labelB + "="
                + (bSuccess ? "SUCCESS" : b.failureClass);
        // Strong only when the failing side is a stable domain failure —
        // otherwise the asymmetry could just be an unstable/transient
        // glitch and is not a safe promotion signal.
        ValidationResult failingSide = aSuccess ? b : a;
        StructuredCandidateStrength rowStrength = failingSide
                .isStableDomainFailure()
                        ? StructuredCandidateStrength.STRONG
                        : StructuredCandidateStrength.WEAK;
        return new ValidationComparison(
                /*equivalent*/ false,
                ValidationComparison.Kind.ASYMMETRIC_SUCCESS_FAILURE,
                rowStrength,
                involvesUnknown,
                involvesDaemonError,
                reportLine);
    }

    private static boolean touchesUnstableClass(ValidationResult vr,
            String target) {
        return vr != null && vr.failureClass != null
                && target.equals(vr.failureClass);
    }

    private static ValidationComparison.Kind upgradeKind(
            ValidationComparison.Kind current,
            ValidationComparison.Kind candidate) {
        return candidate.severity() > current.severity() ? candidate : current;
    }

    private static StructuredCandidateStrength mergeStrength(
            StructuredCandidateStrength current,
            StructuredCandidateStrength rowStrength) {
        // Aggregate across a list of row comparisons. The first divergent
        // row sets a non-NONE strength; subsequent rows can only degrade
        // STRONG to WEAK, never upgrade WEAK to STRONG. NONE means "no
        // divergent rows yet", so any incoming non-NONE strength wins.
        if (current == StructuredCandidateStrength.NONE) {
            return rowStrength;
        }
        if (rowStrength == StructuredCandidateStrength.WEAK) {
            return StructuredCandidateStrength.WEAK;
        }
        return current;
    }

    private static boolean isTimestampCommand(String command) {
        // dfs -stat "%y" returns a full YYYY-MM-DD HH:MM:SS timestamp
        return command != null && command.contains("-stat");
    }

    private static String normalize(String payload, String command) {
        if (payload == null)
            return "";
        String s = payload;
        if (Config.getConf().maskTimestamp) {
            if (isTimestampCommand(command)) {
                s = Utilities.maskTimeStampHHMMSS(s);
            } else {
                s = Utilities.maskTimeStampHHSS(s);
            }
            s = Utilities.maskTimeStampYYYYMMDD(s);
            s = Utilities.maskRubyObject(s);
            s = Utilities.maskScanTime(s);
        }
        return s;
    }

    private static String truncate(String s) {
        if (s == null)
            return "null";
        return s.length() > 120 ? s.substring(0, 120) + "..." : s;
    }
}
