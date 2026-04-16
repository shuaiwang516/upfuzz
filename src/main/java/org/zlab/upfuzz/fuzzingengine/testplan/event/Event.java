package org.zlab.upfuzz.fuzzingengine.testplan.event;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.zlab.upfuzz.fuzzingengine.Config;
import org.zlab.upfuzz.utils.Utilities;

import java.io.Serializable;

/**
 * Event is the base class for all the operations during the upgrade process.
 *      (1) A user/admin command
 *      (2) A fault (Network/Crash)
 *      (3) An upgrade command
 */
public class Event implements Serializable {
    static Logger logger = LogManager.getLogger(Event.class);

    protected String type;
    public int interval; // ms, execution interval from last event

    public int index; // index of the event in the test plan (for mutation)

    public Event(String type) {
        this.type = type;
        // delay is a random number between 10ms and 200ms
        interval = generateInterval();
    }

    public static int generateInterval() {
        return Utilities.randWithRange(Config.getConf().intervalMin,
                Config.getConf().intervalMax);
    }

    /**
     * Phase 3 short-term-dedup helper. Returns a stable, self-describing
     * fragment that captures this event's structural identity (node
     * indices, node sets, command text, …) for use in
     * {@link org.zlab.upfuzz.fuzzingengine.testplan.TestPlan#compactSignature()}.
     *
     * <p>The default returns an empty string so subclasses that have no
     * meaningful identity (e.g. an abstract or marker event) produce
     * the class-name-only signature the top-level caller already
     * emits. Subclasses that carry node indices, sets, or payload text
     * (UpgradeOp, ShellCommand, the fault family) must override this
     * method so independent plans with different fault structure do
     * not collapse into a single dedup entry.
     *
     * <p>Implementations should be:
     * <ul>
     *   <li>deterministic (no random),</li>
     *   <li>stable across JVM runs (no object identities),</li>
     *   <li>order-insensitive for set-valued fields (sort node ids).</li>
     * </ul>
     */
    public String compactSignatureFragment() {
        return "";
    }
}
