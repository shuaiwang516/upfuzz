package org.zlab.upfuzz.cassandra;

import java.util.List;
import java.util.Locale;

import org.zlab.upfuzz.fuzzingengine.packet.TestPlanFeedbackPacket;
import org.zlab.upfuzz.fuzzingengine.packet.ValidationResult;
import org.zlab.upfuzz.fuzzingengine.server.CrossClusterComparisonOutcome;
import org.zlab.upfuzz.fuzzingengine.server.observability.StructuredCandidateStrength;

/**
 * Cassandra-only guard for schema-propagation transients.
 *
 * <p>Cassandra rolling-upgrade campaigns can repeatedly produce the same
 * unconfirmed schema-metadata signature: baselines agree, while the rolling
 * lane either rejects a schema-derived read with InvalidRequest/UNKNOWN or
 * returns a zero-row SELECT * payload whose only difference is the column
 * header. These are useful artifacts for offline inspection, but they should
 * not receive the same online payoff as a confirmed data divergence.
 */
public final class CassandraSchemaTransientOracle {
    private static final String DEMOTION_LINE_PREFIX =
            "[cassandra-schema-transient]";

    private CassandraSchemaTransientOracle() {
    }

    public static CrossClusterComparisonOutcome demoteIfUnconfirmed(
            CrossClusterComparisonOutcome outcome,
            TestPlanFeedbackPacket[] packets) {
        if (outcome == null || !outcome.diverged
                || outcome.strength != StructuredCandidateStrength.STRONG) {
            return outcome;
        }

        DemotionDecision decision = classify(packets);
        if (!decision.demote) {
            return outcome;
        }

        String report = updateConfidenceLine(outcome.report)
                + DEMOTION_LINE_PREFIX
                + " demotedTo=WEAK, reason=" + decision.reason
                + ", affectedRows=" + decision.affectedRows + "\n";
        return new CrossClusterComparisonOutcome(
                true,
                StructuredCandidateStrength.WEAK,
                report,
                outcome.containsUnknown,
                outcome.containsDaemonError);
    }

    static DemotionDecision classify(TestPlanFeedbackPacket[] packets) {
        if (packets == null || packets.length < 3) {
            return DemotionDecision.keep("missing_packets");
        }
        List<ValidationResult> oldResults = packets[0].validationResults;
        List<ValidationResult> rollingResults = packets[1].validationResults;
        List<ValidationResult> newResults = packets[2].validationResults;
        if (oldResults == null || rollingResults == null
                || newResults == null) {
            return DemotionDecision.keep("missing_validation_results");
        }
        int maxSize = Math.max(oldResults.size(),
                Math.max(rollingResults.size(), newResults.size()));
        if (maxSize == 0) {
            return DemotionDecision.keep("empty_validation_results");
        }

        String fullSequence = packets[1].fullSequence == null
                ? ""
                : packets[1].fullSequence;
        boolean schemaChangingSequence = hasSchemaChange(fullSequence);
        int affectedRows = 0;
        boolean sawInvalidOrUnknown = false;
        boolean sawZeroRowHeaderOnly = false;

        for (int i = 0; i < maxSize; i++) {
            ValidationResult oldResult = get(oldResults, i);
            ValidationResult rollingResult = get(rollingResults, i);
            ValidationResult newResult = get(newResults, i);
            if (oldResult == null || rollingResult == null
                    || newResult == null) {
                return DemotionDecision.keep("size_mismatch");
            }

            if (sameResult(oldResult, rollingResult)
                    && sameResult(newResult, rollingResult)) {
                continue;
            }

            if (!oldAndNewAgree(oldResult, newResult)) {
                return DemotionDecision.keep("baselines_do_not_agree");
            }

            if (isRollingInvalidOrUnknownSchemaRead(oldResult, rollingResult,
                    newResult, schemaChangingSequence)) {
                affectedRows++;
                sawInvalidOrUnknown = true;
                continue;
            }

            if (isZeroRowSelectStarHeaderOnlyDiff(oldResult, rollingResult)
                    && isZeroRowSelectStarHeaderOnlyDiff(newResult,
                            rollingResult)) {
                affectedRows++;
                sawZeroRowHeaderOnly = true;
                continue;
            }

            return DemotionDecision.keep("non_schema_transient_row");
        }

        if (affectedRows == 0) {
            return DemotionDecision.keep("no_schema_transient_rows");
        }
        String reason;
        if (sawInvalidOrUnknown && sawZeroRowHeaderOnly) {
            reason = "rolling_invalid_or_unknown_plus_zero_row_header_diff";
        } else if (sawInvalidOrUnknown) {
            reason = "rolling_invalid_or_unknown_schema_read";
        } else {
            reason = "zero_row_select_star_header_diff";
        }
        return DemotionDecision.demote(reason, affectedRows);
    }

    private static ValidationResult get(List<ValidationResult> results,
            int index) {
        return index < results.size() ? results.get(index) : null;
    }

    private static boolean isRollingInvalidOrUnknownSchemaRead(
            ValidationResult oldResult,
            ValidationResult rollingResult,
            ValidationResult newResult,
            boolean schemaChangingSequence) {
        return schemaChangingSequence
                && oldResult.isSuccess()
                && newResult.isSuccess()
                && isSelectRead(rollingResult.command)
                && isInvalidOrUnknown(rollingResult.failureClass);
    }

    private static boolean isZeroRowSelectStarHeaderOnlyDiff(
            ValidationResult baseline,
            ValidationResult rolling) {
        return baseline.isSuccess()
                && rolling.isSuccess()
                && isSelectStar(baseline.command)
                && isSelectStar(rolling.command)
                && hasZeroRows(baseline.stdout)
                && hasZeroRows(rolling.stdout);
    }

    private static boolean oldAndNewAgree(ValidationResult oldResult,
            ValidationResult newResult) {
        return sameResult(oldResult, newResult);
    }

    private static boolean sameResult(ValidationResult a,
            ValidationResult b) {
        if (a == null || b == null) {
            return a == b;
        }
        if (a.exitCode != b.exitCode) {
            return false;
        }
        if (!stringEquals(a.failureClass, b.failureClass)) {
            return false;
        }
        return stringEquals(a.stdout, b.stdout)
                && stringEquals(a.stderr, b.stderr);
    }

    private static boolean stringEquals(String a, String b) {
        return (a == null ? "" : a).equals(b == null ? "" : b);
    }

    private static boolean isInvalidOrUnknown(String failureClass) {
        return "InvalidRequest".equals(failureClass)
                || "UNKNOWN".equals(failureClass);
    }

    private static boolean isSelectRead(String command) {
        if (command == null) {
            return false;
        }
        return command.trim().toUpperCase(Locale.ROOT).startsWith("SELECT ");
    }

    private static boolean isSelectStar(String command) {
        if (command == null) {
            return false;
        }
        String normalized = command.trim().toUpperCase(Locale.ROOT)
                .replaceAll("\\s+", " ");
        return normalized.startsWith("SELECT * ");
    }

    private static boolean hasZeroRows(String stdout) {
        if (stdout == null) {
            return false;
        }
        return stdout.toLowerCase(Locale.ROOT).contains("(0 rows)");
    }

    private static boolean hasSchemaChange(String fullSequence) {
        String normalized = fullSequence == null
                ? ""
                : fullSequence.toUpperCase(Locale.ROOT);
        return normalized.contains("CREATE KEYSPACE")
                || normalized.contains("DROP KEYSPACE")
                || normalized.contains("ALTER KEYSPACE")
                || normalized.contains("CREATE TABLE")
                || normalized.contains("DROP TABLE")
                || normalized.contains("ALTER TABLE")
                || normalized.contains("CREATE TYPE")
                || normalized.contains("DROP TYPE")
                || normalized.contains("ALTER TYPE")
                || normalized.contains("CREATE INDEX")
                || normalized.contains("DROP INDEX");
    }

    private static String updateConfidenceLine(String report) {
        String safeReport = report == null ? "" : report;
        return safeReport.replace("[confidence] strength=STRONG",
                "[confidence] strength=WEAK");
    }

    static final class DemotionDecision {
        final boolean demote;
        final String reason;
        final int affectedRows;

        private DemotionDecision(boolean demote, String reason,
                int affectedRows) {
            this.demote = demote;
            this.reason = reason;
            this.affectedRows = affectedRows;
        }

        static DemotionDecision demote(String reason, int affectedRows) {
            return new DemotionDecision(true, reason, affectedRows);
        }

        static DemotionDecision keep(String reason) {
            return new DemotionDecision(false, reason, 0);
        }
    }
}
