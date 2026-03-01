package org.zlab.upfuzz.fuzzingengine.packet;

import java.io.Serializable;

/**
 * Structured representation of a single validation command result.
 * Preserves exit code and failure classification that the legacy
 * String-based pipeline discards.
 */
public class ValidationResult implements Serializable {
    private static final long serialVersionUID = 1L;

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
