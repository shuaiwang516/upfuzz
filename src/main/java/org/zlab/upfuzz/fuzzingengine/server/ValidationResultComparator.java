package org.zlab.upfuzz.fuzzingengine.server;

import java.util.List;

import org.zlab.upfuzz.fuzzingengine.Config;
import org.zlab.upfuzz.fuzzingengine.packet.ValidationResult;
import org.zlab.upfuzz.utils.Utilities;

/**
 * Outcome-aware comparison of structured validation results.
 * Implements the tri-lane comparison matrix:
 * <ul>
 *   <li>success/success → compare normalized payload</li>
 *   <li>fail/fail → compare failureClass strings</li>
 *   <li>success/fail (asymmetric) → always divergence</li>
 * </ul>
 */
public class ValidationResultComparator {

    /**
     * Compare two lists of structured validation results.
     *
     * @return null if equivalent, or a human-readable diff report if divergent.
     */
    public static String compare(List<ValidationResult> listA,
            List<ValidationResult> listB, String labelA, String labelB) {
        if (listA == null || listB == null) {
            return null;
        }
        int sizeA = listA.size();
        int sizeB = listB.size();
        int maxSize = Math.max(sizeA, sizeB);
        if (maxSize == 0) {
            return null;
        }

        StringBuilder report = new StringBuilder();
        boolean anyDivergence = false;

        for (int i = 0; i < maxSize; i++) {
            ValidationResult a = i < sizeA ? listA.get(i) : null;
            ValidationResult b = i < sizeB ? listB.get(i) : null;

            if (a == null || b == null) {
                anyDivergence = true;
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

            String divergence = compareSingle(a, b, labelA, labelB);
            if (divergence != null) {
                anyDivergence = true;
                report.append("  [cmd ").append(i).append("] ")
                        .append(divergence).append("\n");
            }
        }

        if (!anyDivergence) {
            return null;
        }

        return "Structured validation divergence (" + labelA + " vs "
                + labelB + "):\n" + report;
    }

    /**
     * Compare a single pair of validation results.
     *
     * @return null if equivalent, or description of divergence.
     */
    static String compareSingle(ValidationResult a, ValidationResult b,
            String labelA, String labelB) {
        boolean aSuccess = a.isSuccess();
        boolean bSuccess = b.isSuccess();

        if (aSuccess && bSuccess) {
            // success/success → compare normalized payload
            String normalizedA = normalize(a.stdout, a.command);
            String normalizedB = normalize(b.stdout, b.command);
            if (normalizedA.equals(normalizedB)) {
                return null;
            }
            return "PAYLOAD_DIVERGENCE: " + labelA + "=" + truncate(a.stdout)
                    + " vs " + labelB + "=" + truncate(b.stdout);
        }

        if (!aSuccess && !bSuccess) {
            // fail/fail → compare failure class
            if (a.failureClass.equals(b.failureClass)) {
                return null; // same error class = equivalent
            }
            return "FAILURE_CLASS_DIVERGENCE: " + labelA + "="
                    + a.failureClass + " vs " + labelB + "="
                    + b.failureClass;
        }

        // success/fail asymmetry → always report as divergence
        return "ASYMMETRIC: " + labelA + "="
                + (aSuccess ? "SUCCESS" : a.failureClass) + " vs "
                + labelB + "="
                + (bSuccess ? "SUCCESS" : b.failureClass);
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
