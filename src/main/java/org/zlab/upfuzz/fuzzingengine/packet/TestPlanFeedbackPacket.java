package org.zlab.upfuzz.fuzzingengine.packet;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.zlab.net.tracker.Trace;
import org.zlab.upfuzz.fuzzingengine.FeedBack;
import org.zlab.upfuzz.fuzzingengine.trace.WindowedTrace;

import java.io.DataInputStream;
import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;

public class TestPlanFeedbackPacket extends Packet implements Serializable {
    private static final long serialVersionUID = 20260406L;
    static Logger logger = LogManager.getLogger(TestPlanFeedbackPacket.class);

    public enum LaneStatus {
        OK, TIMEOUT, EXCEPTION, NULL_PACKET, INTERRUPTED
    }

    public String systemID;
    public int testPacketID;

    // If the upgradeOp failed, this will be marked as true
    // We expect the system upgrade op should always succeed
    // no matter whether the normal command is correct or not
    public String fullSequence = ""; // for reproducing
    public String configFileName;

    public boolean isEventFailed = false; // One event failed (including
                                          // downgrade op)
    public String eventFailedReport;

    // Differential lane collection/wait outcome.
    // NOTE: this is orthogonal to isEventFailed:
    // laneStatus=OK means packet collection succeeded, while execution may
    // still fail (isEventFailed=true) inside that lane.
    public String laneName = "";
    public LaneStatus laneStatus = LaneStatus.OK;
    public String laneFailureReason = "";

    public boolean hasERRORLog = false;
    public String errorLogReport = "";

    // For test plan, we only collect the new version coverage
    public FeedBack[] feedBacks;

    public Trace[] trace;

    // Windowed trace for stage-aware comparison
    public WindowedTrace windowedTrace;

    // Validation read results for cross-cluster comparison
    public List<String> validationReadResults = new ArrayList<>();

    // Structured validation results (WS1) — null when sent by old clients
    public List<ValidationResult> validationResults = null;

    // TODO: We might want to compare the state between
    // (1) Rolling upgrade and (2) Full-stop upgrade
    public boolean isInconsistent = false; // true if inconsistent
    public String inconsistencyReport = "";

    public TestPlanFeedbackPacket(String systemID, String configFileName,
            int testPacketID, FeedBack[] feedBacks) {
        this.type = PacketType.TestPlanFeedbackPacket;

        this.systemID = systemID;
        this.configFileName = configFileName;
        this.testPacketID = testPacketID;
        this.feedBacks = feedBacks;

    }

    public static TestPlanFeedbackPacket read(DataInputStream in) {
        return (TestPlanFeedbackPacket) read(in, TestPlanFeedbackPacket.class);
    }
}
