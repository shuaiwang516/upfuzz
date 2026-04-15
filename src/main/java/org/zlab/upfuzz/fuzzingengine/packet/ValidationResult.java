package org.zlab.upfuzz.fuzzingengine.packet;

import java.io.Serializable;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

/**
 * Structured representation of a single validation command result.
 * Preserves exit code and failure classification that the legacy
 * String-based pipeline discards.
 */
public class ValidationResult implements Serializable {
    private static final long serialVersionUID = 1L;

    /**
     * Failure classes that represent stable, typed domain errors from the
     * target system. Divergence between two stable domain failures is a
     * strong signal because both sides returned a deterministic verdict.
     *
     * <p>Entries here MUST correspond to specific typed exceptions from
     * the Cassandra / HDFS / HBase shell daemons. Generic catch-alls like
     * {@code HBaseError} (which {@link
     * org.zlab.upfuzz.hbase.HBaseShellDaemon} emits from any
     * {@code "ERROR:"} substring match) belong in
     * {@link #UNSTABLE_FAILURES} instead, otherwise the Phase 1 strong
     * bucket starts accepting comparisons that lean on an untyped side.
     */
    private static final Set<String> STABLE_DOMAIN_FAILURES = Collections
            .unmodifiableSet(new HashSet<>(Arrays.asList(
                    "SyntaxException", "InvalidRequest", "Unavailable",
                    "AlreadyExists", "ConfigurationException",
                    "Unauthorized", "NoSuchFile", "PermissionDenied",
                    "NotADirectory", "IsADirectory", "InvalidFilename",
                    "IllegalArgument", "NoSuchColumnFamily",
                    "TableNotFound", "TableExists", "NamespaceNotFound",
                    "NamespaceExists", "TableNotDisabled",
                    "TableNotEnabled", "RegionException")));

    /**
     * Failure classes that typically reflect transient or environment
     * conditions rather than the command itself. Divergence on these is
     * not a reliable rolling-upgrade signal, but both sides still carry
     * meaningful information.
     */
    private static final Set<String> TRANSIENT_FAILURES = Collections
            .unmodifiableSet(new HashSet<>(Arrays.asList(
                    "Timeout", "OperationTimedOut", "SafeMode")));

    /**
     * Failure classes that mean the result is unstable: either the daemon
     * could not produce any structured answer, the exit code was non-zero
     * with no recognised error, the classifier simply did not match, or
     * the daemon emitted a generic catch-all bucket
     * ({@code HBaseError} from a raw {@code "ERROR:"} substring match).
     * A comparison where any side is unstable can only ever be weak.
     */
    private static final Set<String> UNSTABLE_FAILURES = Collections
            .unmodifiableSet(new HashSet<>(Arrays.asList(
                    "UNKNOWN", "DAEMON_ERROR", "NonZeroExit",
                    "HBaseError")));

    /** The command that was executed. */
    public String command;

    /** Process exit code (0 = success). */
    public int exitCode;

    /** Standard output from the command. */
    public String stdout;

    /** Standard error from the command. */
    public String stderr;

    /**
     * Semantic failure class: "OK", "InvalidRequest", "NoSuchFile",
     * "DAEMON_ERROR", "UNKNOWN", etc.
     */
    public String failureClass;

    public ValidationResult() {
        this.command = "";
        this.exitCode = 0;
        this.stdout = "";
        this.stderr = "";
        this.failureClass = "UNKNOWN";
    }

    public ValidationResult(String command, int exitCode, String stdout,
            String stderr, String failureClass) {
        this.command = command;
        this.exitCode = exitCode;
        this.stdout = stdout == null ? "" : stdout;
        this.stderr = stderr == null ? "" : stderr;
        this.failureClass = failureClass == null ? "UNKNOWN" : failureClass;
    }

    /** True if the command completed successfully. */
    public boolean isSuccess() {
        return exitCode == 0 && "OK".equals(failureClass);
    }

    /**
     * A stable success is a clean, exit-0, OK-classified result. Payload
     * comparisons between two stable successes are the cleanest strong
     * signal mode-5 can produce.
     */
    public boolean isStableSuccess() {
        return isSuccess();
    }

    /**
     * A stable domain failure is a typed error class from the target
     * system (e.g. {@code InvalidRequest}, {@code TableNotFound}). Both
     * sides returning stable domain failures of different classes is a
     * strong signal.
     */
    public boolean isStableDomainFailure() {
        return failureClass != null
                && STABLE_DOMAIN_FAILURES.contains(failureClass);
    }

    /**
     * A transient/environmental failure should never upgrade a candidate
     * into the strong bucket.
     */
    public boolean isTransientFailure() {
        return failureClass != null
                && TRANSIENT_FAILURES.contains(failureClass);
    }

    /**
     * An unstable failure is {@code UNKNOWN}, {@code DAEMON_ERROR}, or a
     * raw {@code NonZeroExit}. Any comparison that touches an unstable
     * result is at most weak, because we cannot distinguish a real
     * divergence from an environment glitch.
     */
    public boolean isUnstableFailure() {
        return failureClass != null
                && UNSTABLE_FAILURES.contains(failureClass);
    }

    /**
     * Reproduce the legacy single-string format used by
     * {@code validationReadResults} for backward compatibility.
     */
    public String toLegacyString() {
        // Legacy path compares the raw merged shell output and keeps empty
        // command results as "" (not "null message").
        String out = stdout == null ? "" : stdout;
        String err = stderr == null ? "" : stderr;
        return out + err;
    }

    @Override
    public String toString() {
        return "ValidationResult{cmd=" + command + ", exit=" + exitCode
                + ", class=" + failureClass + ", stdout="
                + (stdout.length() > 80
                        ? stdout.substring(0, 80) + "..."
                        : stdout)
                + "}";
    }
}
