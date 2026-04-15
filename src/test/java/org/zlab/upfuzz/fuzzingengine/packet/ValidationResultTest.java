package org.zlab.upfuzz.fuzzingengine.packet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class ValidationResultTest {

    @Test
    void toLegacyStringKeepsEmptyOutputAsEmptyString() {
        ValidationResult result = new ValidationResult("cmd", 0, "", "", "OK");
        assertEquals("", result.toLegacyString());
    }

    @Test
    void toLegacyStringReturnsStdoutWhenOnlyStdoutExists() {
        ValidationResult result = new ValidationResult("cmd", 0, "out", "",
                "OK");
        assertEquals("out", result.toLegacyString());
    }

    @Test
    void toLegacyStringReturnsStderrWhenOnlyStderrExists() {
        ValidationResult result = new ValidationResult("cmd", 1, "", "err",
                "NonZeroExit");
        assertEquals("err", result.toLegacyString());
    }

    @Test
    void toLegacyStringConcatenatesBothStreams() {
        ValidationResult result = new ValidationResult("cmd", 1, "out", "err",
                "NonZeroExit");
        assertEquals("outerr", result.toLegacyString());
    }

    // --- Phase 1 stability helpers ---

    @Test
    void stableSuccessOnlyForOkExit0() {
        assertTrue(new ValidationResult("cmd", 0, "out", "", "OK")
                .isStableSuccess());
        assertFalse(new ValidationResult("cmd", 1, "out", "", "OK")
                .isStableSuccess());
        assertFalse(new ValidationResult("cmd", 0, "out", "", "UNKNOWN")
                .isStableSuccess());
    }

    @Test
    void stableDomainFailureRecognisesCassandraClasses() {
        assertTrue(new ValidationResult("cmd", 1, "", "err", "InvalidRequest")
                .isStableDomainFailure());
        assertTrue(new ValidationResult("cmd", 1, "", "err", "AlreadyExists")
                .isStableDomainFailure());
        assertTrue(new ValidationResult("cmd", 1, "", "err", "SyntaxException")
                .isStableDomainFailure());
    }

    @Test
    void stableDomainFailureRecognisesHdfsAndHbaseClasses() {
        assertTrue(new ValidationResult("cmd", 1, "", "err", "NoSuchFile")
                .isStableDomainFailure());
        assertTrue(new ValidationResult("cmd", 1, "", "err", "TableNotFound")
                .isStableDomainFailure());
        assertTrue(new ValidationResult("cmd", 1, "", "err", "IllegalArgument")
                .isStableDomainFailure());
    }

    @Test
    void transientFailureRecognisesTimeoutAndSafeMode() {
        assertTrue(new ValidationResult("cmd", 1, "", "err", "Timeout")
                .isTransientFailure());
        assertTrue(
                new ValidationResult("cmd", 1, "", "err", "OperationTimedOut")
                        .isTransientFailure());
        assertTrue(new ValidationResult("cmd", 1, "", "err", "SafeMode")
                .isTransientFailure());
    }

    @Test
    void unstableFailureRecognisesUnknownDaemonErrorNonZeroExit() {
        assertTrue(new ValidationResult("cmd", 1, "", "err", "UNKNOWN")
                .isUnstableFailure());
        assertTrue(new ValidationResult("cmd", 1, "", "err", "DAEMON_ERROR")
                .isUnstableFailure());
        assertTrue(new ValidationResult("cmd", 1, "", "err", "NonZeroExit")
                .isUnstableFailure());
        assertFalse(new ValidationResult("cmd", 1, "", "err", "InvalidRequest")
                .isUnstableFailure());
    }

    @Test
    void hbaseErrorIsUnstableNotStableDomain() {
        // HBaseShellDaemon emits "HBaseError" from a raw
        // `err.contains("ERROR")`
        // catch-all, not from a typed exception. It must be unstable so
        // divergences that rely on it never reach the Phase 1 STRONG bucket.
        ValidationResult r = new ValidationResult("cmd", 1, "", "err",
                "HBaseError");
        assertTrue(r.isUnstableFailure());
        assertFalse(r.isStableDomainFailure());
        assertFalse(r.isTransientFailure());
        assertFalse(r.isStableSuccess());
    }

    @Test
    void stabilityBucketsAreMutuallyExclusive() {
        ValidationResult r = new ValidationResult("cmd", 1, "", "err",
                "NoSuchFile");
        assertTrue(r.isStableDomainFailure());
        assertFalse(r.isStableSuccess());
        assertFalse(r.isTransientFailure());
        assertFalse(r.isUnstableFailure());
    }
}
