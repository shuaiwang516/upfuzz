package org.zlab.upfuzz.fuzzingengine.server;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.zlab.upfuzz.fuzzingengine.Config;
import org.zlab.upfuzz.fuzzingengine.testplan.event.Event;
import org.zlab.upfuzz.fuzzingengine.testplan.event.command.ShellCommand;
import org.zlab.upfuzz.fuzzingengine.testplan.event.downgradeop.DowngradeOp;
import org.zlab.upfuzz.fuzzingengine.testplan.event.fault.*;
import org.zlab.upfuzz.fuzzingengine.testplan.event.upgradeop.FinalizeUpgrade;
import org.zlab.upfuzz.fuzzingengine.testplan.event.upgradeop.HDFSStopSNN;
import org.zlab.upfuzz.fuzzingengine.testplan.event.upgradeop.PrepareUpgrade;
import org.zlab.upfuzz.fuzzingengine.testplan.event.upgradeop.UpgradeOp;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.LinkedList;
import java.util.List;

public class EventParser {
    static Logger logger = LogManager.getLogger(EventParser.class);

    /**
     * read from file, construct a test plan
     */
    public static List<Event> construct() {
        Path commandPath = Paths.get(System.getProperty("user.dir"),
                "examplecase");
        String testPlanFileName = "testplan.txt";
        if ("hdfs".equals(Config.getConf().system)) {
            testPlanFileName = "testplan_hdfs_example.txt";
        }
        List<String> commands = FuzzingServer.readCommands(
                commandPath.resolve(testPlanFileName));

        List<Event> events = new LinkedList<>();
        for (String eventStr : commands) {
            Event e = constructSingleEvent(eventStr);
            if (e != null)
                events.add(e);
        }
        return events;
    }

    public static Event constructSingleEvent(String eventStr) {
        // parse string and transform to a event
        if (eventStr.isEmpty())
            return null;

        try {
            int idx = eventStr.indexOf("]");
            int idx1, idx2;
            String type = eventStr.substring(1, idx);
            String restStr = eventStr.substring(idx + 1);
            switch (type) {
            case "Command":
                // TODO: record node id and parse it
                idx1 = restStr.indexOf("{");
                String commandStr = restStr.substring(idx1 + 1,
                        restStr.length() - 1);
                return new ShellCommand(commandStr);
            case "UpgradeOp":
                idx1 = restStr.indexOf("[");
                idx2 = restStr.indexOf("]");
                int nodeIdx = Integer
                        .parseInt(restStr.substring(idx1 + 1, idx2));
                return new UpgradeOp(nodeIdx);
            case "DowngradeOp":
                idx1 = restStr.indexOf("[");
                idx2 = restStr.indexOf("]");
                nodeIdx = Integer.parseInt(restStr.substring(idx1 + 1, idx2));
                return new DowngradeOp(nodeIdx);
            case "Fault":
                if (restStr.contains("LinkFailure")) {
                    idx1 = restStr.indexOf("[");
                    idx2 = restStr.indexOf("]");
                    int nodeIdx1 = Integer
                            .parseInt(restStr.substring(idx1 + 1, idx2));
                    idx1 = restStr.indexOf("[", idx1 + 1);
                    idx2 = restStr.indexOf("]", idx2 + 1);
                    int nodeIdx2 = Integer
                            .parseInt(restStr.substring(idx1 + 1, idx2));
                    return new LinkFailure(nodeIdx1, nodeIdx2);
                } else if (restStr.contains("RestartFailure")) {
                    idx1 = restStr.indexOf("[");
                    idx2 = restStr.indexOf("]");
                    nodeIdx = Integer
                            .parseInt(restStr.substring(idx1 + 1, idx2));
                    return new RestartFailure(nodeIdx);
                } else if (restStr.contains("Isolate")) {
                    idx1 = restStr.indexOf("[");
                    idx2 = restStr.indexOf("]");
                    nodeIdx = Integer
                            .parseInt(restStr.substring(idx1 + 1, idx2));
                    return new IsolateFailure(nodeIdx);
                } else if (restStr.contains("NodeFailure")) {
                    idx1 = restStr.indexOf("[");
                    idx2 = restStr.indexOf("]");
                    nodeIdx = Integer
                            .parseInt(restStr.substring(idx1 + 1, idx2));
                    return new NodeFailure(nodeIdx);
                } else {
                    logger.error("cannot parse " + eventStr);
                    return null;
                }
            case "FaultRecover":
                if (restStr.contains("LinkFailure Recover")) {
                    idx1 = restStr.indexOf("[");
                    idx2 = restStr.indexOf("]");
                    int nodeIdx1 = Integer
                            .parseInt(restStr.substring(idx1 + 1, idx2));
                    idx1 = restStr.indexOf("[", idx1 + 1);
                    idx2 = restStr.indexOf("]", idx2 + 1);
                    int nodeIdx2 = Integer
                            .parseInt(restStr.substring(idx1 + 1, idx2));
                    return new LinkFailureRecover(nodeIdx1, nodeIdx2);
                } else if (restStr.contains("IsolateFailureRecover")) {
                    idx1 = restStr.indexOf("[");
                    idx2 = restStr.indexOf("]");
                    nodeIdx = Integer
                            .parseInt(restStr.substring(idx1 + 1, idx2));
                    return new IsolateFailureRecover(nodeIdx);
                } else if (restStr.contains("NodeFailure Recover")) {
                    idx1 = restStr.indexOf("[");
                    idx2 = restStr.indexOf("]");
                    nodeIdx = Integer
                            .parseInt(restStr.substring(idx1 + 1, idx2));
                    return new NodeFailureRecover(nodeIdx);
                } else {
                    logger.error("cannot parse " + eventStr);
                    return null;
                }
            case "FinalizeUpgrade":
                return new FinalizeUpgrade();
            case "PrepareUpgrade":
                return new PrepareUpgrade();
            case "HDFS_Specific":
                if (restStr.contains("Shutdown secondary namenode")) {
                    return new HDFSStopSNN();
                }
            default:
                return null;
            }
        } catch (Exception e) {
            logger.error("cannot parse " + eventStr);
            return null;
        }
    }
}
