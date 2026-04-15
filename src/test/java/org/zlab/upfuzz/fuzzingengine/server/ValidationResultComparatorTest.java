package org.zlab.upfuzz.fuzzingengine.server;

import static org.junit.jupiter.api.Assertions.*;

import java.util.*;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import org.zlab.upfuzz.fuzzingengine.Config;
import org.zlab.upfuzz.fuzzingengine.packet.ValidationResult;
import org.zlab.upfuzz.fuzzingengine.server.observability.StructuredCandidateStrength;

class ValidationResultComparatorTest {

    @BeforeAll
    static void initConfig() {
        if (Config.getConf() == null) {
            new Config();
        }
    }

    static ValidationResult success(String stdout) {
        return new ValidationResult("cmd", 0, stdout, "", "OK");
    }

    static ValidationResult success(String command, String stdout) {
        return new ValidationResult(command, 0, stdout, "", "OK");
    }

    static ValidationResult failure(String failureClass) {
        return new ValidationResult("cmd", 1, "", "error", failureClass);
    }

    // --- success/success equivalent ---

    @Test
    void successSuccessEquivalent() {
        List<ValidationResult> a = Arrays.asList(success("hello"));
        List<ValidationResult> b = Arrays.asList(success("hello"));
        ValidationComparison cmp = ValidationResultComparator.compare(a, b,
                "A", "B");
        assertTrue(cmp.equivalent);
        assertEquals(ValidationComparison.Kind.EQUIVALENT, cmp.comparisonKind);
        assertEquals(StructuredCandidateStrength.NONE, cmp.strength);
        assertNull(cmp.reportLine);
    }

    // --- success/success divergent is STRONG ---

    @Test
    void successSuccessDivergentIsStrong() {
        List<ValidationResult> a = Arrays.asList(success("foo"));
        List<ValidationResult> b = Arrays.asList(success("bar"));
        ValidationComparison cmp = ValidationResultComparator.compare(a, b,
                "A", "B");
        assertFalse(cmp.equivalent);
        assertEquals(ValidationComparison.Kind.PAYLOAD_DIVERGENCE,
                cmp.comparisonKind);
        assertEquals(StructuredCandidateStrength.STRONG, cmp.strength);
        assertNotNull(cmp.reportLine);
        assertTrue(cmp.reportLine.contains("PAYLOAD_DIVERGENCE"));
    }

    // --- fail/fail same class ---

    @Test
    void failFailSameClass() {
        List<ValidationResult> a = Arrays.asList(failure("NoSuchFile"));
        List<ValidationResult> b = Arrays.asList(failure("NoSuchFile"));
        ValidationComparison cmp = ValidationResultComparator.compare(a, b,
                "A", "B");
        assertTrue(cmp.equivalent);
    }

    // --- fail/fail different stable classes is STRONG ---

    @Test
    void failFailDifferentStableClassesIsStrong() {
        List<ValidationResult> a = Arrays.asList(failure("NoSuchFile"));
        List<ValidationResult> b = Arrays.asList(failure("InvalidRequest"));
        ValidationComparison cmp = ValidationResultComparator.compare(a, b,
                "A", "B");
        assertFalse(cmp.equivalent);
        assertEquals(ValidationComparison.Kind.FAILURE_CLASS_DIVERGENCE,
                cmp.comparisonKind);
        assertEquals(StructuredCandidateStrength.STRONG, cmp.strength);
        assertTrue(cmp.reportLine.contains("FAILURE_CLASS_DIVERGENCE"));
    }

    // --- fail/fail where one side is transient is WEAK ---

    @Test
    void failFailWithTransientSideIsWeak() {
        List<ValidationResult> a = Arrays.asList(failure("NoSuchFile"));
        List<ValidationResult> b = Arrays.asList(failure("Timeout"));
        ValidationComparison cmp = ValidationResultComparator.compare(a, b,
                "A", "B");
        assertFalse(cmp.equivalent);
        assertEquals(StructuredCandidateStrength.WEAK, cmp.strength);
    }

    // --- fail/fail where one side is UNKNOWN is WEAK ---

    @Test
    void failFailWithUnknownSideIsWeak() {
        List<ValidationResult> a = Arrays.asList(failure("NoSuchFile"));
        List<ValidationResult> b = Arrays.asList(failure("UNKNOWN"));
        ValidationComparison cmp = ValidationResultComparator.compare(a, b,
                "A", "B");
        assertFalse(cmp.equivalent);
        assertEquals(StructuredCandidateStrength.WEAK, cmp.strength);
        assertTrue(cmp.involvesUnknown);
        assertFalse(cmp.involvesDaemonError);
    }

    // --- fail/fail where one side is DAEMON_ERROR is WEAK ---

    @Test
    void failFailWithDaemonErrorSideIsWeak() {
        List<ValidationResult> a = Arrays.asList(failure("NoSuchFile"));
        List<ValidationResult> b = Arrays.asList(failure("DAEMON_ERROR"));
        ValidationComparison cmp = ValidationResultComparator.compare(a, b,
                "A", "B");
        assertFalse(cmp.equivalent);
        assertEquals(StructuredCandidateStrength.WEAK, cmp.strength);
        assertTrue(cmp.involvesDaemonError);
    }

    // --- UNKNOWN vs UNKNOWN: equivalent (same class) ---

    @Test
    void unknownVsUnknownEquivalent() {
        List<ValidationResult> a = Arrays.asList(failure("UNKNOWN"));
        List<ValidationResult> b = Arrays.asList(failure("UNKNOWN"));
        ValidationComparison cmp = ValidationResultComparator.compare(a, b,
                "A", "B");
        assertTrue(cmp.equivalent);
        assertEquals(StructuredCandidateStrength.NONE, cmp.strength);
    }

    // --- asymmetric success vs typed stable failure is STRONG ---

    @Test
    void asymmetricSuccessVsStableFailureIsStrong() {
        List<ValidationResult> a = Arrays.asList(success("data"));
        List<ValidationResult> b = Arrays.asList(failure("InvalidRequest"));
        ValidationComparison cmp = ValidationResultComparator.compare(a, b,
                "A", "B");
        assertFalse(cmp.equivalent);
        assertEquals(ValidationComparison.Kind.ASYMMETRIC_SUCCESS_FAILURE,
                cmp.comparisonKind);
        assertEquals(StructuredCandidateStrength.STRONG, cmp.strength);
        assertTrue(cmp.reportLine.contains("ASYMMETRIC"));
    }

    // --- asymmetric SUCCESS vs UNKNOWN is WEAK ---

    @Test
    void asymmetricSuccessVsUnknownIsWeak() {
        List<ValidationResult> a = Arrays.asList(success("data"));
        List<ValidationResult> b = Arrays.asList(failure("UNKNOWN"));
        ValidationComparison cmp = ValidationResultComparator.compare(a, b,
                "A", "B");
        assertFalse(cmp.equivalent);
        assertEquals(ValidationComparison.Kind.ASYMMETRIC_SUCCESS_FAILURE,
                cmp.comparisonKind);
        assertEquals(StructuredCandidateStrength.WEAK, cmp.strength);
        assertTrue(cmp.involvesUnknown);
    }

    // --- asymmetric SUCCESS vs DAEMON_ERROR is WEAK ---

    @Test
    void asymmetricSuccessVsDaemonErrorIsWeak() {
        List<ValidationResult> a = Arrays.asList(failure("DAEMON_ERROR"));
        List<ValidationResult> b = Arrays.asList(success("data"));
        ValidationComparison cmp = ValidationResultComparator.compare(a, b,
                "A", "B");
        assertFalse(cmp.equivalent);
        assertEquals(StructuredCandidateStrength.WEAK, cmp.strength);
        assertTrue(cmp.involvesDaemonError);
    }

    // --- asymmetric success vs raw NonZeroExit is WEAK ---

    @Test
    void asymmetricSuccessVsNonZeroExitIsWeak() {
        List<ValidationResult> a = Arrays.asList(success("data"));
        List<ValidationResult> b = Arrays.asList(failure("NonZeroExit"));
        ValidationComparison cmp = ValidationResultComparator.compare(a, b,
                "A", "B");
        assertFalse(cmp.equivalent);
        assertEquals(StructuredCandidateStrength.WEAK, cmp.strength);
    }

    // --- asymmetric SUCCESS vs HBaseError catch-all is WEAK ---

    @Test
    void asymmetricSuccessVsHBaseErrorIsWeak() {
        // HBaseShellDaemon.classifyHBaseFailure emits "HBaseError" for any
        // stderr/stdout line containing "ERROR" that did not match a typed
        // exception. That bucket must never push a comparison into STRONG.
        List<ValidationResult> a = Arrays.asList(success("data"));
        List<ValidationResult> b = Arrays.asList(failure("HBaseError"));
        ValidationComparison cmp = ValidationResultComparator.compare(a, b,
                "A", "B");
        assertFalse(cmp.equivalent);
        assertEquals(ValidationComparison.Kind.ASYMMETRIC_SUCCESS_FAILURE,
                cmp.comparisonKind);
        assertEquals(StructuredCandidateStrength.WEAK, cmp.strength);
    }

    // --- typed stable failure vs HBaseError catch-all is WEAK ---

    @Test
    void typedStableFailureVsHBaseErrorIsWeak() {
        List<ValidationResult> a = Arrays.asList(failure("TableNotFound"));
        List<ValidationResult> b = Arrays.asList(failure("HBaseError"));
        ValidationComparison cmp = ValidationResultComparator.compare(a, b,
                "A", "B");
        assertFalse(cmp.equivalent);
        assertEquals(ValidationComparison.Kind.FAILURE_CLASS_DIVERGENCE,
                cmp.comparisonKind);
        // HBaseError is a catch-all bucket, not a typed exception — a
        // typed-vs-catch-all divergence is not a safe STRONG signal.
        assertEquals(StructuredCandidateStrength.WEAK, cmp.strength);
    }

    // --- size mismatch is always WEAK ---

    @Test
    void sizeMismatchIsWeak() {
        List<ValidationResult> a = Arrays
                .asList(success("x"), success("y"));
        List<ValidationResult> b = Arrays.asList(success("x"));
        ValidationComparison cmp = ValidationResultComparator.compare(a, b,
                "A", "B");
        assertFalse(cmp.equivalent);
        assertEquals(ValidationComparison.Kind.SIZE_MISMATCH,
                cmp.comparisonKind);
        assertEquals(StructuredCandidateStrength.WEAK, cmp.strength);
        assertTrue(cmp.reportLine.contains("SIZE MISMATCH"));
    }

    // --- null lists treated as equivalent (matches Phase 0 semantics) ---

    @Test
    void nullListReturnsEquivalent() {
        assertTrue(ValidationResultComparator.compare(null, null, "A",
                "B").equivalent);
        assertTrue(ValidationResultComparator.compare(
                Arrays.asList(success("x")), null, "A", "B").equivalent);
    }

    // --- empty lists ---

    @Test
    void emptyListsEquivalent() {
        assertTrue(ValidationResultComparator.compare(
                new ArrayList<>(), new ArrayList<>(), "A", "B").equivalent);
    }

    // --- multi-entry mixed: one strong row + one equivalent row → STRONG ---

    @Test
    void multiEntryPartialDivergenceStaysStrong() {
        List<ValidationResult> a = Arrays.asList(
                success("same"), success("different"));
        List<ValidationResult> b = Arrays.asList(
                success("same"), success("other"));
        ValidationComparison cmp = ValidationResultComparator.compare(a, b,
                "A", "B");
        assertFalse(cmp.equivalent);
        assertFalse(cmp.reportLine.contains("[cmd 0]"));
        assertTrue(cmp.reportLine.contains("[cmd 1]"));
        assertEquals(StructuredCandidateStrength.STRONG, cmp.strength);
    }

    // --- multi-entry: one strong row + one weak row → WEAK (min wins) ---

    @Test
    void multiEntryMixedStrengthsAggregatesToWeak() {
        List<ValidationResult> a = Arrays.asList(
                success("foo"),
                success("baz"));
        List<ValidationResult> b = Arrays.asList(
                success("bar"),
                failure("UNKNOWN"));
        ValidationComparison cmp = ValidationResultComparator.compare(a, b,
                "A", "B");
        assertFalse(cmp.equivalent);
        // one strong payload divergence + one weak asymmetric → WEAK wins
        assertEquals(StructuredCandidateStrength.WEAK, cmp.strength);
        assertTrue(cmp.involvesUnknown);
    }

    // --- timestamp masking is scoped to stat commands ---

    @Test
    void statTimestampSecondsSkewSuppressed() {
        // dfs -stat "%y" produces full YYYY-MM-DD HH:MM:SS timestamps;
        // small seconds skew should be masked and not reported
        String cmd = "dfs -stat \"%y\" /path";
        List<ValidationResult> a = Arrays
                .asList(success(cmd, "2026-03-04 23:07:32"));
        List<ValidationResult> b = Arrays
                .asList(success(cmd, "2026-03-04 23:07:39"));
        assertTrue(ValidationResultComparator.compare(a, b, "Old",
                "Rolling").equivalent);
    }

    @Test
    void nonStatHHMMSSContentPreserved() {
        // For non-timestamp commands (e.g. dfs -cat), HH:MM:SS-like
        // patterns in file content must NOT be masked — a real corruption
        // like 12:34:56 vs 12:34:57 should still be reported
        String cmd = "dfs -cat /file";
        List<ValidationResult> a = Arrays
                .asList(success(cmd, "data 12:34:56 end"));
        List<ValidationResult> b = Arrays
                .asList(success(cmd, "data 12:34:57 end"));
        ValidationComparison cmp = ValidationResultComparator.compare(
                a, b, "Old", "Rolling");
        assertFalse(cmp.equivalent,
                "seconds divergence in non-stat output must be reported");
        assertTrue(cmp.reportLine.contains("PAYLOAD_DIVERGENCE"));
        assertEquals(StructuredCandidateStrength.STRONG, cmp.strength);
    }
}
