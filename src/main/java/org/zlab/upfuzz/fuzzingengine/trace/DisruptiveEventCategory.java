package org.zlab.upfuzz.fuzzingengine.trace;

import org.zlab.upfuzz.fuzzingengine.testplan.event.Event;
import org.zlab.upfuzz.fuzzingengine.testplan.event.downgradeop.DowngradeOp;
import org.zlab.upfuzz.fuzzingengine.testplan.event.fault.*;
import org.zlab.upfuzz.fuzzingengine.testplan.event.upgradeop.*;

/**
 * Classifies events as disruptive boundaries vs. workload.
 */
public enum DisruptiveEventCategory {
    /** Advances a normalized comparison stage (UpgradeOp, RestartFailure). */
    STAGE_ADVANCING,

    /** Rolling-only lifecycle boundary (PrepareUpgrade, FinalizeUpgrade, HDFSStopSNN). */
    LIFECYCLE_ONLY,

    /** Fault injection (NodeFailure, LinkFailure, etc.). */
    FAULT,

    /** Fault recovery. */
    FAULT_RECOVERY,

    /** Not disruptive — workload event (ShellCommand, etc.). */
    WORKLOAD;

    public static DisruptiveEventCategory classify(Event event) {
        if (event instanceof UpgradeOp)
            return STAGE_ADVANCING;
        if (event instanceof RestartFailure)
            return STAGE_ADVANCING;
        if (event instanceof PrepareUpgrade)
            return LIFECYCLE_ONLY;
        if (event instanceof FinalizeUpgrade)
            return LIFECYCLE_ONLY;
        if (event instanceof HDFSStopSNN)
            return LIFECYCLE_ONLY;
        if (event instanceof DowngradeOp)
            return STAGE_ADVANCING;
        if (event instanceof FaultRecover)
            return FAULT_RECOVERY;
        if (event instanceof Fault)
            return FAULT;
        return WORKLOAD;
    }

    public boolean isDisruptive() {
        return this != WORKLOAD;
    }

    /** Whether this boundary type should advance the normalized comparison stage. */
    public boolean advancesComparisonStage() {
        return this == STAGE_ADVANCING;
    }
}
