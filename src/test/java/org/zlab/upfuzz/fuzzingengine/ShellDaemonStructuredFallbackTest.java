package org.zlab.upfuzz.fuzzingengine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.zlab.upfuzz.fuzzingengine.packet.ValidationResult;

class ShellDaemonStructuredFallbackTest {

    @Test
    void defaultStructuredFallbackMarksSuccessfulCommandAsOk()
            throws Exception {
        ShellDaemon daemon = new ShellDaemon() {
            @Override
            public String executeCommand(String command) {
                return "stdout";
            }
        };

        ValidationResult result = daemon.executeCommandStructured("echo");
        assertEquals(0, result.exitCode);
        assertEquals("OK", result.failureClass);
        assertEquals("stdout", result.stdout);
        assertEquals("", result.stderr);
        assertTrue(result.isSuccess());
    }
}
