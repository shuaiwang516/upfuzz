package org.zlab.upfuzz.fuzzingengine.server;

import static org.junit.jupiter.api.Assertions.*;

import java.util.*;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import org.zlab.upfuzz.fuzzingengine.Config;
import org.zlab.upfuzz.fuzzingengine.packet.ValidationResult;

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
        assertNull(ValidationResultComparator.compare(a, b, "A", "B"));
    }

    // --- success/success divergent ---

    @Test
    void successSuccessDivergent() {
        List<ValidationResult> a = Arrays.asList(success("foo"));
        List<ValidationResult> b = Arrays.asList(success("bar"));
        String report = ValidationResultComparator.compare(a, b, "A", "B");
        assertNotNull(report);
        assertTrue(report.contains("PAYLOAD_DIVERGENCE"));
    }

    // --- fail/fail same class ---

    @Test
    void failFailSameClass() {
        List<ValidationResult> a = Arrays.asList(failure("NoSuchFile"));
        List<ValidationResult> b = Arrays.asList(failure("NoSuchFile"));
        assertNull(ValidationResultComparator.compare(a, b, "A", "B"));
    }

    // --- fail/fail different class ---

    @Test
    void failFailDifferentClass() {
        List<ValidationResult> a = Arrays.asList(failure("NoSuchFile"));
        List<ValidationResult> b = Arrays.asList(failure("Timeout"));
        String report = ValidationResultComparator.compare(a, b, "A", "B");
        assertNotNull(report);
        assertTrue(report.contains("FAILURE_CLASS_DIVERGENCE"));
    }

    // --- asymmetric success/fail ---

    @Test
    void asymmetricSuccessFail() {
        List<ValidationResult> a = Arrays.asList(success("data"));
        List<ValidationResult> b = Arrays.asList(failure("InvalidRequest"));
        String report = ValidationResultComparator.compare(a, b, "A", "B");
        assertNotNull(report);
        assertTrue(report.contains("ASYMMETRIC"));
    }

    @Test
    void asymmetricFailSuccess() {
        List<ValidationResult> a = Arrays.asList(failure("InvalidRequest"));
        List<ValidationResult> b = Arrays.asList(success("data"));
        String report = ValidationResultComparator.compare(a, b, "A", "B");
        assertNotNull(report);
        assertTrue(report.contains("ASYMMETRIC"));
    }

    // --- size mismatch ---

    @Test
    void sizeMismatch() {
        List<ValidationResult> a = Arrays
                .asList(success("x"), success("y"));
        List<ValidationResult> b = Arrays.asList(success("x"));
        String report = ValidationResultComparator.compare(a, b, "A", "B");
        assertNotNull(report);
        assertTrue(report.contains("SIZE MISMATCH"));
    }

    // --- null lists ---

    @Test
    void nullListReturnsNull() {
        assertNull(ValidationResultComparator.compare(null, null, "A", "B"));
        assertNull(ValidationResultComparator.compare(
                Arrays.asList(success("x")), null, "A", "B"));
    }

    // --- empty lists ---

    @Test
    void emptyListsEquivalent() {
        assertNull(ValidationResultComparator.compare(
                new ArrayList<>(), new ArrayList<>(), "A", "B"));
    }

    // --- multi-entry mixed ---

    @Test
    void multiEntryPartialDivergence() {
        List<ValidationResult> a = Arrays.asList(
                success("same"), success("different"));
        List<ValidationResult> b = Arrays.asList(
                success("same"), success("other"));
        String report = ValidationResultComparator.compare(a, b, "A", "B");
        assertNotNull(report);
        assertFalse(report.contains("[cmd 0]"));
        assertTrue(report.contains("[cmd 1]"));
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
        assertNull(ValidationResultComparator.compare(a, b, "Old", "Rolling"));
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
        String report = ValidationResultComparator.compare(
                a, b, "Old", "Rolling");
        assertNotNull(report,
                "seconds divergence in non-stat output must be reported");
        assertTrue(report.contains("PAYLOAD_DIVERGENCE"));
    }
}
