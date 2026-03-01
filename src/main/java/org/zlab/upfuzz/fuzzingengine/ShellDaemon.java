package org.zlab.upfuzz.fuzzingengine;

import org.zlab.upfuzz.fuzzingengine.packet.ValidationResult;

public abstract class ShellDaemon {
    public abstract String executeCommand(String command) throws Exception;

    /**
     * Execute a command and return a structured result preserving exit code
     * and failure classification. Default implementation wraps legacy
     * {@link #executeCommand(String)}.
     */
    public ValidationResult executeCommandStructured(String command)
            throws Exception {
        String result = executeCommand(command);
        return new ValidationResult(command, 0, result, "", "OK");
    }
}
