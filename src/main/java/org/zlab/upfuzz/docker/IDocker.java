package org.zlab.upfuzz.docker;

import org.zlab.net.tracker.Trace;
import org.zlab.ocov.tracker.ObjectGraphCoverage;
import org.zlab.upfuzz.fuzzingengine.LogInfo;
import org.zlab.upfuzz.fuzzingengine.packet.ValidationResult;

import java.util.Set;

public interface IDocker {
    String getNetworkIP();

    int start() throws Exception;

    void teardown();

    boolean build() throws Exception;

    void flush() throws Exception;

    void shutdown() throws Exception;

    void upgrade() throws Exception;

    void upgradeFromCrash() throws Exception;

    void downgrade() throws Exception;

    ObjectGraphCoverage getFormatCoverage() throws Exception;

    Trace collectTrace() throws Exception;

    void clearFormatCoverage() throws Exception;

    // remove all system data (data/ in cassandra)
    boolean clear();

    LogInfo grepLogInfo(Set<String> blackListErrorLog);

    String formatComposeYaml();

    String execCommand(String command) throws Exception;

    default ValidationResult execCommandStructured(String command)
            throws Exception {
        String result = execCommand(command);
        return new ValidationResult(command, 0, result, "", "OK");
    }
}
