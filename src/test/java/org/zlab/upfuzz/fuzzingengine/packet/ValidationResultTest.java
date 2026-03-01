package org.zlab.upfuzz.fuzzingengine.packet;

import static org.junit.jupiter.api.Assertions.assertEquals;

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
}
