package org.zlab.upfuzz.fuzzingengine.executor;

import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

import org.apache.commons.lang3.RandomStringUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.jacoco.core.data.ExecutionDataStore;
import org.zlab.net.tracker.Trace;
import org.zlab.ocov.tracker.ObjectGraphCoverage;
import org.zlab.upfuzz.docker.DockerCluster;
import org.zlab.upfuzz.docker.DockerMeta;
import org.zlab.upfuzz.docker.IDocker;
import org.zlab.upfuzz.fuzzingengine.AgentServerHandler;
import org.zlab.upfuzz.fuzzingengine.AgentServerSocket;
import org.zlab.upfuzz.fuzzingengine.Config;
import org.zlab.upfuzz.fuzzingengine.packet.ValidationResult;
import org.zlab.upfuzz.fuzzingengine.LogInfo;
import org.zlab.upfuzz.fuzzingengine.testplan.TestPlan;
import org.zlab.upfuzz.fuzzingengine.testplan.event.Event;
import org.zlab.upfuzz.fuzzingengine.testplan.event.command.ShellCommand;
import org.zlab.upfuzz.fuzzingengine.testplan.event.downgradeop.DowngradeOp;
import org.zlab.upfuzz.fuzzingengine.testplan.event.fault.*;
import org.zlab.upfuzz.fuzzingengine.testplan.event.upgradeop.FinalizeUpgrade;
import org.zlab.upfuzz.fuzzingengine.testplan.event.upgradeop.HDFSStopSNN;
import org.zlab.upfuzz.fuzzingengine.testplan.event.upgradeop.PrepareUpgrade;
import org.zlab.upfuzz.fuzzingengine.testplan.event.upgradeop.UpgradeOp;
import org.zlab.upfuzz.fuzzingengine.trace.DisruptiveEventCategory;
import org.zlab.upfuzz.fuzzingengine.trace.TopologyNormalizer;
import org.zlab.upfuzz.fuzzingengine.trace.TraceWindow;
import org.zlab.upfuzz.hdfs.HdfsDockerCluster;
import org.zlab.upfuzz.utils.Pair;

public abstract class Executor implements IExecutor {
    protected static final Logger logger = LogManager.getLogger(Executor.class);

    public int agentPort;
    public Long timestamp = 0L;
    public int eventIdx;
    public String executorID;
    public String systemID = "UnknowDS";
    public int nodeNum;

    public boolean collectFormatCoverage = false;
    public Path configPath;
    public String testPlanExecutionLog = "";
    public int direction;

    // Test plan coverage
    public ExecutionDataStore[] oriCoverage;

    // Test plan trace
    public Trace[] trace;

    // Windowed trace state
    private List<TraceWindow> traceWindows = new ArrayList<>();
    private int windowOrdinal = 0;
    private boolean windowOpen = false;
    private String currentComparisonStageId = "PRE_UPGRADE";
    private TraceWindow.StageKind currentStageKind = TraceWindow.StageKind.PRE_UPGRADE;
    private Set<Integer> currentNormalizedTransitionNodeSet = new HashSet<>();
    private Set<Integer> currentRawUpgradedNodeSet = new HashSet<>();
    private int stageCounter = 0;
    private String currentOpenReason = "";

    public DockerCluster dockerCluster;
    public TopologyNormalizer topologyNormalizer;

    /**
     * key: String -> agentId value: Codecoverage for this agent
     */
    public Map<String, ExecutionDataStore> agentStore;

    /* key: String -> agent Id
     * value: ClientHandler -> the socket to a agent */
    public Map<String, AgentServerHandler> agentHandler;

    /* key: UUID String -> executor Id
     * value: List<String> -> list of all alive agents with the executor Id */
    public ConcurrentHashMap<String, Set<String>> sessionGroup;

    /* socket for client and agents to communicate*/
    public AgentServerSocket agentSocket;

    public String getTestPlanExecutionLog() {
        return testPlanExecutionLog;
    }

    protected Executor() {
        executorID = RandomStringUtils.randomAlphanumeric(8);
    }

    protected Executor(String systemID, int nodeNum) {
        this();
        this.systemID = systemID;
        this.nodeNum = nodeNum;
        this.oriCoverage = new ExecutionDataStore[nodeNum];
        if (Config.getConf().useTrace) {
            this.trace = new Trace[nodeNum];
            for (int i = 0; i < nodeNum; i++) {
                trace[i] = new Trace();
            }
        }
    }

    public void teardown() {
        if (dockerCluster != null)
            dockerCluster.teardown();
        if (agentSocket != null) {
            agentSocket.stopServer();
            agentSocket = null;
        }
    }

    public void setConfigPath(Path ConfigPath) {
        this.configPath = ConfigPath;
        if (this.dockerCluster != null) {
            this.dockerCluster.configpath = ConfigPath;
        }
    }

    public void clearState() {
        executorID = RandomStringUtils.randomAlphanumeric(8);
    }

    public String getSysExecID() {
        return systemID + "-" + executorID;
    }

    public boolean freshStartNewVersion() {
        try {
            return dockerCluster.freshStartNewVersion();
        } catch (Exception e) {
            logger.error(String.format(
                    "new version cannot start up with exception ", e));
            return false;
        }
    }

    public ObjectGraphCoverage getFormatCoverage(Path formatInfoFolder) {
        return dockerCluster.getFormatCoverage(formatInfoFolder);
    }

    public void clearFormatCoverage() {
        dockerCluster.clearFormatCoverage();
    }

    public Trace collectTrace(int nodeIdx) {
        return dockerCluster.collectTrace(nodeIdx);
    }

    public void updateTrace(int nodeIdx) {
        if (!Config.getConf().useTrace)
            return;
        trace[nodeIdx].append(collectTrace(nodeIdx));
    }

    public void updateTrace() {
        if (!Config.getConf().useTrace)
            return;
        for (int i = 0; i < nodeNum; i++) {
            trace[i].append(collectTrace(i));
        }
    }

    /** Collect traces from ALL nodes (snapshot-and-clear). Returns Trace[nodeNum]. */
    public Trace[] snapshotTraceAllNodes() {
        if (!Config.getConf().useTrace)
            return new Trace[nodeNum];
        Trace[] traces = dockerCluster.collectTraceAllNodes();
        if (topologyNormalizer != null) {
            for (int i = 0; i < traces.length; i++) {
                if (traces[i] != null) {
                    traces[i] = topologyNormalizer.normalizeTrace(traces[i]);
                }
            }
        }
        return traces;
    }

    /** Clear traces on ALL nodes without collecting. */
    public void clearTraceAllNodes() {
        if (!Config.getConf().useTrace)
            return;
        dockerCluster.clearTraceAllNodes();
    }

    /** Get the windowed trace collected during the last execute() call. */
    public List<TraceWindow> getTraceWindows() {
        return traceWindows;
    }

    /**
     * Rebuild the legacy flat trace[] from collected windows.
     * This should be used instead of updateTrace() after windowed execute(),
     * because the runtime buffers have already been snapshot-cleared.
     */
    public void rebuildLegacyTraceFromWindows() {
        if (!Config.getConf().useTrace)
            return;
        for (int i = 0; i < nodeNum; i++) {
            trace[i] = new Trace();
        }
        for (TraceWindow window : traceWindows) {
            for (int i = 0; i < nodeNum; i++) {
                if (window.nodeTraces != null && i < window.nodeTraces.length
                        && window.nodeTraces[i] != null) {
                    trace[i].append(window.nodeTraces[i]);
                }
            }
        }
    }

    // --- Windowed trace helpers ---

    private String computeVersionLayout() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < nodeNum; i++) {
            if (i > 0)
                sb.append("-");
            sb.append(currentRawUpgradedNodeSet.contains(i) ? "new" : "old");
        }
        return sb.toString();
    }

    private void openWindow(Event triggerEvent) {
        windowOpen = true;
        currentOpenReason = currentComparisonStageId + "_WORKLOAD";
        logger.info("[TRACE] Window {} opened: stage={}, reason={}",
                windowOrdinal, currentComparisonStageId, currentOpenReason);
    }

    private void closeWindow(Trace[] nodeTraces, String closeReason,
            int closeEventIdx) {
        boolean comparable = (currentStageKind != TraceWindow.StageKind.LIFECYCLE_ONLY
                && currentStageKind != TraceWindow.StageKind.FAULT_RECOVERY);

        TraceWindow window = new TraceWindow(
                windowOrdinal,
                currentOpenReason,
                closeReason,
                closeEventIdx,
                currentComparisonStageId,
                currentStageKind,
                new HashSet<>(currentNormalizedTransitionNodeSet),
                new HashSet<>(currentRawUpgradedNodeSet),
                computeVersionLayout(),
                comparable,
                nodeTraces);

        traceWindows.add(window);
        logger.info("[TRACE] Window {} closed: {}", windowOrdinal, window);
        windowOrdinal++;
        windowOpen = false;
    }

    private void updateStageAfterAdvancingEvent(Event event) {
        stageCounter++;
        int affectedNode = getAffectedNodeIndex(event);

        currentNormalizedTransitionNodeSet.add(affectedNode);

        if (event instanceof UpgradeOp) {
            currentRawUpgradedNodeSet.add(affectedNode);
        }
        // For RestartFailure in baselines, rawUpgradedNodeSet stays unchanged
        // (node restarts same version), but normalizedTransitionNodeSet
        // advances

        boolean isFinal = (currentNormalizedTransitionNodeSet
                .size() >= nodeNum);
        currentComparisonStageId = isFinal ? "POST_FINAL_STAGE"
                : ("POST_STAGE_" + stageCounter);
        currentStageKind = isFinal
                ? TraceWindow.StageKind.POST_FINAL_STAGE
                : TraceWindow.StageKind.POST_STAGE;
    }

    private int getAffectedNodeIndex(Event event) {
        if (event instanceof UpgradeOp)
            return ((UpgradeOp) event).nodeIndex;
        if (event instanceof RestartFailure)
            return ((RestartFailure) event).nodeIndex;
        if (event instanceof DowngradeOp)
            return ((DowngradeOp) event).nodeIndex;
        if (event instanceof NodeFailure)
            return ((NodeFailure) event).nodeIndex;
        return -1;
    }

    public boolean fullStopUpgrade() {
        try {
            return dockerCluster.fullStopUpgrade();
        } catch (Exception e) {
            e.printStackTrace();
            return false;
        }
    }

    public boolean rollingUpgrade() {
        try {
            return dockerCluster.rollingUpgrade();
        } catch (Exception e) {
            e.printStackTrace();
            return false;
        }
    }

    public boolean downgrade() {
        try {
            return dockerCluster.downgrade();
        } catch (Exception e) {
            e.printStackTrace();
            return false;
        }
    }

    public void flush() {
        try {
            if (Config.getConf().debug) {
                logger.debug("cluster flushing");
            }
            dockerCluster.flush();
            if (Config.getConf().debug) {
                logger.debug("cluster flushed");
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    public List<String> executeCommands(List<String> commandList) {
        // TODO: Use Event here, since not all commands are executed
        List<String> ret = new LinkedList<>();
        for (String command : commandList) {
            if (command.isEmpty()) {
                ret.add("");
            } else {
                Long initTime = System.currentTimeMillis();
                ret.add(execShellCommand(new ShellCommand(command)));
                if (Config.getConf().debug) {
                    testPlanExecutionLog += String.format("%.10s", command)
                            + " in "
                            +
                            +(System.currentTimeMillis() - initTime)
                            + " ms, ";
                }
            }
        }
        return ret;
    }

    public List<ValidationResult> executeCommandsStructured(
            List<String> commandList) {
        List<ValidationResult> ret = new LinkedList<>();
        for (String command : commandList) {
            if (command.isEmpty()) {
                ret.add(new ValidationResult(command, 0, "", "", "OK"));
            } else {
                try {
                    ShellCommand sc = new ShellCommand(command);
                    int nodeIndex = sc.getNodeIndex();
                    if (nodeIndex < 0 || nodeIndex >= nodeNum) {
                        ret.add(new ValidationResult(command, -1, "",
                                "Node " + nodeIndex + " out of range: "
                                        + nodeNum,
                                "DAEMON_ERROR"));
                        continue;
                    }
                    if (!dockerCluster.dockerStates[nodeIndex].alive) {
                        ret.add(new ValidationResult(command, -1, "",
                                "Node " + nodeIndex + " is not alive",
                                "DAEMON_ERROR"));
                        continue;
                    }
                    ret.add(dockerCluster.getDocker(nodeIndex)
                            .execCommandStructured(sc.getCommand()));
                } catch (Exception e) {
                    logger.error(e);
                    ret.add(new ValidationResult(command, -1, "",
                            "shell daemon execution problem " + e,
                            "DAEMON_ERROR"));
                }
            }
        }
        return ret;
    }

    public boolean execute(TestPlan testPlan) {
        boolean status = true;

        // Initialize windowed trace state
        traceWindows.clear();
        windowOrdinal = 0;
        windowOpen = false;
        stageCounter = 0;
        currentComparisonStageId = "PRE_UPGRADE";
        currentStageKind = TraceWindow.StageKind.PRE_UPGRADE;
        currentNormalizedTransitionNodeSet = new HashSet<>();
        currentRawUpgradedNodeSet = new HashSet<>();

        // direction=1 means new-new lane: all nodes start upgraded
        if (direction == 1) {
            for (int i = 0; i < nodeNum; i++) {
                currentRawUpgradedNodeSet.add(i);
            }
        }

        // Build topology normalizer for IP/hostname -> role resolution
        if (Config.getConf().useTrace) {
            topologyNormalizer = new TopologyNormalizer();
            for (int i = 0; i < nodeNum; i++) {
                IDocker docker = dockerCluster.getDocker(i);
                String ip = docker.getNetworkIP();
                String role = docker.getNodeRole();
                topologyNormalizer.registerMapping(ip, role);
                // Register Docker service hostname (DC3N<index>)
                topologyNormalizer.registerMapping("DC3N" + i, role);
                // Register container name as hostname fallback
                if (docker instanceof DockerMeta) {
                    String cname = ((DockerMeta) docker).containerName;
                    topologyNormalizer.registerMapping(cname, role);
                }
                // Register system-specific hostname aliases
                for (String alias : docker.getHostnameAliases()) {
                    topologyNormalizer.registerMapping(alias, role);
                }
            }
            logger.info(
                    "[TRACE] Topology normalizer initialized with {} mappings",
                    topologyNormalizer.mappingCount());
        }

        // Clear startup chatter
        if (Config.getConf().useTrace) {
            clearTraceAllNodes();
            logger.info("[TRACE] Cleared startup traces on all nodes");
        }

        for (eventIdx = 0; eventIdx < testPlan.getEvents().size(); eventIdx++) {
            Event event = testPlan.getEvents().get(eventIdx);
            logger.info(String.format("\nhandle %s\n", event));

            if (eventIdx != 0) {
                try {
                    if (Config.getConf().debug)
                        logger.info(
                                String.format("command interval = %d ms",
                                        event.interval));
                    Thread.sleep(event.interval);
                } catch (InterruptedException e) {
                    logger.error("sleep interrupted");
                    for (StackTraceElement stackTraceElement : e
                            .getStackTrace()) {
                        logger.error(stackTraceElement);
                    }
                }
            }

            long initTime = System.currentTimeMillis();
            DisruptiveEventCategory category = DisruptiveEventCategory
                    .classify(event);

            if (category.isDisruptive()) {
                // === CLOSE current window (if open) ===
                if (windowOpen && Config.getConf().useTrace) {
                    Trace[] snapshot = snapshotTraceAllNodes();
                    closeWindow(snapshot, event.toString(), eventIdx);
                }

                // === EXECUTE the disruptive event ===
                boolean eventOk = executeDisruptiveEvent(event, initTime);
                if (!eventOk) {
                    status = false;
                    break;
                }

                // === POST-BOUNDARY: clear traces, update stage ===
                if (Config.getConf().useTrace) {
                    clearTraceAllNodes();
                }

                if (category.advancesComparisonStage()) {
                    updateStageAfterAdvancingEvent(event);
                } else if (category == DisruptiveEventCategory.LIFECYCLE_ONLY) {
                    // Mark next window as lifecycle-only (non-comparable)
                    currentStageKind = TraceWindow.StageKind.LIFECYCLE_ONLY;
                } else if (category == DisruptiveEventCategory.FAULT
                        || category == DisruptiveEventCategory.FAULT_RECOVERY) {
                    // Mark next window as fault-affected (non-comparable)
                    currentStageKind = TraceWindow.StageKind.FAULT_RECOVERY;
                }
                // Window stays closed until next workload event
            } else {
                // === WORKLOAD event ===
                if (!windowOpen && Config.getConf().useTrace) {
                    openWindow(event);
                }

                boolean eventOk = executeWorkloadEvent(event, initTime);
                if (!eventOk) {
                    status = false;
                    break;
                }
            }
        }

        // === CLOSE final window ===
        if (windowOpen && Config.getConf().useTrace) {
            Trace[] snapshot = snapshotTraceAllNodes();
            closeWindow(snapshot, "ROUND_END", -1);
        }

        return status;
    }

    private boolean executeDisruptiveEvent(Event event, long initTime) {
        if (event instanceof UpgradeOp) {
            UpgradeOp upgradeOp = (UpgradeOp) event;
            int nodeIdx = upgradeOp.nodeIndex;
            oriCoverage[nodeIdx] = collectSingleNodeCoverage(nodeIdx,
                    "original");

            if (Config.getConf().debug) {
                testPlanExecutionLog += "(Upgrade) Single node coverage collection in "
                        + (System.currentTimeMillis() - initTime) + " ms, ";
            }
            initTime = System.currentTimeMillis();

            if (!handleUpgradeOp(upgradeOp)) {
                logger.error("UpgradeOp problem");
                if (Config.getConf().debug) {
                    testPlanExecutionLog += "(Upgrade) operation failed in "
                            + (System.currentTimeMillis() - initTime)
                            + " ms, ";
                }
                return false;
            }
            if (Config.getConf().debug) {
                testPlanExecutionLog += "(Upgrade) operation in "
                        + (System.currentTimeMillis() - initTime) + " ms, ";
            }
            return true;
        } else if (event instanceof RestartFailure) {
            boolean ok = dockerCluster
                    .restartContainer(((RestartFailure) event).nodeIndex);
            if (!ok) {
                logger.error("RestartFailure execution problem");
                if (Config.getConf().debug) {
                    testPlanExecutionLog += "(Fault) injection failed in "
                            + (System.currentTimeMillis() - initTime)
                            + " ms, ";
                }
                return false;
            }
            if (Config.getConf().debug) {
                testPlanExecutionLog += "(Fault) injection in "
                        + (System.currentTimeMillis() - initTime) + " ms, ";
            }
            return true;
        } else if (event instanceof Fault) {
            if (!handleFault((Fault) event)) {
                logger.error(
                        String.format("Cannot Inject {%s} here", event));
                if (Config.getConf().debug) {
                    testPlanExecutionLog += "(Fault) injection failed in "
                            + (System.currentTimeMillis() - initTime)
                            + " ms, ";
                }
                return false;
            }
            if (Config.getConf().debug) {
                testPlanExecutionLog += "(Fault) injection in "
                        + (System.currentTimeMillis() - initTime) + " ms, ";
            }
            return true;
        } else if (event instanceof FaultRecover) {
            if (!handleFaultRecover((FaultRecover) event)) {
                logger.error("FaultRecover execution problem");
                if (Config.getConf().debug) {
                    testPlanExecutionLog += "(Recover) recovery failed in "
                            + (System.currentTimeMillis() - initTime)
                            + " ms, ";
                }
                return false;
            }
            if (Config.getConf().debug) {
                testPlanExecutionLog += "(Recover) fault recover in "
                        + (System.currentTimeMillis() - initTime) + " ms, ";
            }
            return true;
        } else if (event instanceof PrepareUpgrade) {
            if (!handlePrepareUpgrade((PrepareUpgrade) event)) {
                logger.error("PrepareUpgrade problem");
                if (Config.getConf().debug) {
                    testPlanExecutionLog += "(Prepare) upgrade failed in "
                            + (System.currentTimeMillis() - initTime)
                            + " ms, ";
                }
                return false;
            }
            if (Config.getConf().debug) {
                testPlanExecutionLog += "(Prepare) upgrade event in "
                        + (System.currentTimeMillis() - initTime) + " ms, ";
            }
            return true;
        } else if (event instanceof FinalizeUpgrade) {
            if (!handleFinalizeUpgrade((FinalizeUpgrade) event)) {
                logger.error("FinalizeUpgrade problem");
                if (Config.getConf().debug) {
                    testPlanExecutionLog += "(Finalize) upgrade failed in "
                            + (System.currentTimeMillis() - initTime)
                            + " ms, ";
                }
                return false;
            }
            if (Config.getConf().debug) {
                testPlanExecutionLog += "(Finalize) upgrade event in "
                        + (System.currentTimeMillis() - initTime) + " ms, ";
            }
            return true;
        } else if (event instanceof HDFSStopSNN) {
            if (!handleHDFSStopSNN((HDFSStopSNN) event)) {
                logger.error("HDFS stop SNN problem");
                if (Config.getConf().debug) {
                    testPlanExecutionLog += "(HDFSStopSNN) event failed in "
                            + (System.currentTimeMillis() - initTime)
                            + " ms, ";
                }
                return false;
            }
            if (Config.getConf().debug) {
                testPlanExecutionLog += "(HDFSStopSNN) HDFS event in "
                        + (System.currentTimeMillis() - initTime) + " ms, ";
            }
            return true;
        } else if (event instanceof DowngradeOp) {
            if (!handleDowngradeOp((DowngradeOp) event)) {
                logger.error("DowngradeOp problem");
                if (Config.getConf().debug) {
                    testPlanExecutionLog += "(Downgrade) operation failed in "
                            + (System.currentTimeMillis() - initTime)
                            + " ms, ";
                }
                return false;
            }
            if (Config.getConf().debug) {
                testPlanExecutionLog += "(Downgrade) operation in "
                        + (System.currentTimeMillis() - initTime) + " ms, ";
            }
            return true;
        }
        logger.error("Unknown disruptive event type: {}", event);
        return false;
    }

    private boolean executeWorkloadEvent(Event event, long initTime) {
        if (event instanceof ShellCommand) {
            if (!handleCommand((ShellCommand) event)) {
                logger.error("ShellCommand problem");
                if (Config.getConf().debug) {
                    testPlanExecutionLog += "(Shell) execution failed in "
                            + (System.currentTimeMillis() - initTime)
                            + " ms, ";
                }
                return false;
            }
            if (Config.getConf().debug) {
                testPlanExecutionLog += "(Shell) command execution in "
                        + (System.currentTimeMillis() - initTime) + " ms, ";
            }
            return true;
        }
        return true;
    }

    public String execShellCommand(ShellCommand command) {
        String ret = "null cp message";
        if (command.getCommand().isEmpty())
            return ret;
        try {
            int nodeIndex = command.getNodeIndex();

            // it needs to be alive
            if (nodeIndex < 0 || nodeIndex >= nodeNum)
                throw new RuntimeException(
                        "Node " + nodeIndex + " out of range: " + nodeNum);
            if (!dockerCluster.dockerStates[nodeIndex].alive)
                throw new RuntimeException(
                        "Node " + nodeIndex + " is not alive");
            return dockerCluster.getDocker(nodeIndex)
                    .execCommand(command.getCommand());
        } catch (Exception e) {
            logger.error(e);
            ret = "shell daemon execution problem " + e;
        }
        return ret;
    }

    public ExecutionDataStore collect(String version) {
        // TODO: Separate the coverage here
        Set<String> agentIdList = sessionGroup.get(executorID + "_" + version);
        logger.info("agentIdList: " + agentIdList);
        logger.info("executorID = " + executorID);
        if (agentIdList == null) {
            logger.error("No agent connection with executor " +
                    executorID);
            return null;
        } else {
            // Clear the code coverage
            for (String agentId : agentIdList) {
                if (agentId.split("-")[3].equals("null"))
                    continue;
                logger.info("collect conn " + agentId);
                AgentServerHandler conn = agentHandler.get(agentId);
                if (conn != null) {
                    agentStore.remove(agentId);
                    conn.collect();
                }
            }

            ExecutionDataStore execStore = new ExecutionDataStore();
            for (String agentId : agentIdList) {
                if (agentId.split("-")[3].equals("null"))
                    continue;
                logger.info("get coverage from " + agentId);
                ExecutionDataStore astore = agentStore.get(agentId);
                if (astore == null) {
                    logger.info("no data");
                } else {
                    // astore : classname -> int[]
                    execStore.merge(astore);
                    logger.trace("astore size: " + astore.getContents().size());
                }
            }
            logger.debug("codecoverage of " + executorID + "_" + version
                    + " size: " + execStore.getContents().size());
            // Send coverage back

            return execStore;
        }
    }

    public ExecutionDataStore collectSingleNodeCoverage(int nodeIdx,
            String version) {
        Set<String> agentIdList = sessionGroup.get(executorID + "_" + version);
        ExecutionDataStore executionDataStore = null;
        logger.info("[Executor] Invoked single node coverage collection");
        if (agentIdList == null) {
            logger.error("No agent connection with executor " +
                    executorID);
        } else {
            for (String agentId : agentIdList) {
                if (agentId.split("-")[3].equals("null"))
                    continue;

                int idx = Integer.parseInt(agentId.split("-")[2]);
                if (nodeIdx == idx) {
                    logger.info("collect conn " + agentId);
                    AgentServerHandler conn = agentHandler.get(agentId);
                    if (conn != null) {
                        agentStore.remove(agentId);
                        conn.collect();
                    }
                    executionDataStore = agentStore.get(agentId);
                    break;
                }
            }
        }
        return executionDataStore;

    }

    public ExecutionDataStore[] collectCoverageSeparate(String version) {
        // TODO: Separate the coverage here
        if (Config.getConf().debug) {
            logger.info("[HKLOG: Executor] Invoked coverage collection for: "
                    + version);
        }
        Set<String> agentIdList = sessionGroup.get(executorID + "_" + version);
        // logger.info("agentIdList: " + agentIdList);
        // logger.info("executorID = " + executorID);
        if (Config.getConf().debug) {
            logger.info("[Executor] Invoked separate coverage collection for: "
                    + executorID);
        }
        if (agentIdList == null) {
            logger.error("No agent connection with executor " +
                    executorID);
            return null;
        } else {
            // Add to the original coverage
            for (String agentId : agentIdList) {
                if (agentId.split("-")[3].equals("null"))
                    continue;
                if (Config.getConf().debug) {
                    logger.info(
                            "[Executor] Going to get connection for agent server handler");
                }
                AgentServerHandler conn = agentHandler.get(agentId);
                if (Config.getConf().debug) {
                    logger.info("[Executor] Going to collect coverage");
                }
                if (conn != null) {
                    agentStore.remove(agentId);
                    conn.collect();
                }
                if (Config.getConf().debug) {
                    logger.info("[Executor] collected coverage");
                }
            }

            ExecutionDataStore[] executionDataStores = new ExecutionDataStore[nodeNum];
            for (int i = 0; i < executionDataStores.length; i++) {
                executionDataStores[i] = new ExecutionDataStore();
            }

            for (String agentId : agentIdList) {
                // logger.info("collect conn " + agentId);
                if (agentId.split("-")[3].equals("null"))
                    continue;
                ExecutionDataStore astore = agentStore.get(agentId);
                if (astore == null) {
                    // logger.info("no data");
                } else {
                    executionDataStores[Integer.parseInt(agentId.split("-")[2])]
                            .merge(astore);
                    // logger.trace("astore size: " +
                    // astore.getContents().size());
                }
            }
            return executionDataStores;
        }
    }

    public Pair<Boolean, String> checkResultConsistency(List<String> oriResult,
            List<String> upResult, boolean compareOldAndNew) {
        return new Pair<>(true, "");
    }

    public String getSubnet() {
        return dockerCluster.getNetworkIP();
    }

    public boolean handleFault(Fault fault) {
        if (fault instanceof LinkFailure) {
            // Link failure between two nodes
            LinkFailure linkFailure = (LinkFailure) fault;
            return dockerCluster.linkFailure(linkFailure.nodeIndex1,
                    linkFailure.nodeIndex2);
        } else if (fault instanceof NodeFailure) {
            // Crash a node
            NodeFailure nodeFailure = (NodeFailure) fault;
            // Trace collection is now handled by windowed execute()
            return dockerCluster.stopContainer(nodeFailure.nodeIndex);
        } else if (fault instanceof IsolateFailure) {
            // Isolate a single node from the rest nodes
            IsolateFailure isolateFailure = (IsolateFailure) fault;
            return dockerCluster.isolateNode(isolateFailure.nodeIndex);
        } else if (fault instanceof PartitionFailure) {
            // Partition two sets of nodes
            PartitionFailure partitionFailure = (PartitionFailure) fault;
            return dockerCluster.partition(partitionFailure.nodeSet1,
                    partitionFailure.nodeSet2);
        } else if (fault instanceof RestartFailure) {
            // RestartFailure is now dispatched directly by
            // executeDisruptiveEvent
            // as a STAGE_ADVANCING event; this path is kept for safety
            RestartFailure nodeFailure = (RestartFailure) fault;
            return dockerCluster.restartContainer(nodeFailure.nodeIndex);
        }
        return false;
    }

    public boolean handleFaultRecover(FaultRecover faultRecover) {
        if (faultRecover instanceof LinkFailureRecover) {
            // Link failure between two nodes
            LinkFailureRecover linkFailureRecover = (LinkFailureRecover) faultRecover;
            boolean ret = dockerCluster.linkFailureRecover(
                    linkFailureRecover.nodeIndex1,
                    linkFailureRecover.nodeIndex2);
            FaultRecover.waitToRebuildConnection();
            return ret;
        } else if (faultRecover instanceof NodeFailureRecover) {
            // recover from node crash
            NodeFailureRecover nodeFailureRecover = (NodeFailureRecover) faultRecover;
            return dockerCluster
                    .killContainerRecover(nodeFailureRecover.nodeIndex);
        } else if (faultRecover instanceof IsolateFailureRecover) {
            // Isolate a single node from the rest nodes
            IsolateFailureRecover isolateFailureRecover = (IsolateFailureRecover) faultRecover;
            boolean ret = dockerCluster
                    .isolateNodeRecover(isolateFailureRecover.nodeIndex);
            FaultRecover.waitToRebuildConnection();
            return ret;
        } else if (faultRecover instanceof PartitionFailureRecover) {
            // Partition two sets of nodes
            PartitionFailureRecover partitionFailureRecover = (PartitionFailureRecover) faultRecover;
            boolean ret = dockerCluster.partitionRecover(
                    partitionFailureRecover.nodeSet1,
                    partitionFailureRecover.nodeSet2);
            FaultRecover.waitToRebuildConnection();
            return ret;
        }
        return false;
    }

    public boolean handleCommand(ShellCommand command) {
        // TODO: also handle normal commands

        // Some checks to make sure that at least one server
        // is up
        int liveContainers = 0;
        for (DockerMeta.DockerState dockerState : dockerCluster.dockerStates) {
            if (dockerState.alive)
                liveContainers++;
        }
        if (liveContainers == 0) {
            logger.error("All node is down, cannot execute shell commands!");
            // This shouldn't appear, but if it happens, we should report
            // TODO: report to server as a buggy case
            return false;
        }
        try {
            execShellCommand(command);
        } catch (Exception e) {
            logger.error("shell command execution failed " + e);
            e.printStackTrace();
            return false;
        }
        return true;
    }

    public boolean handleUpgradeOp(UpgradeOp upgradeOp) {
        try {
            dockerCluster.upgrade(upgradeOp.nodeIndex);
        } catch (Exception e) {
            logger.error("upgrade failed due to an exception " + e);
            e.printStackTrace();
            return false;
        }
        return true;
    }

    public boolean handleDowngradeOp(DowngradeOp downgradeOp) {
        try {
            dockerCluster.downgrade(downgradeOp.nodeIndex);
        } catch (Exception e) {
            logger.error("downgrade failed due to an exception " + e);
            e.printStackTrace();
            return false;
        }
        return true;
    }

    public boolean handlePrepareUpgrade(PrepareUpgrade prepareUpgrade) {
        try {
            dockerCluster.prepareUpgrade();
        } catch (Exception e) {
            logger.error("upgrade prepare upgrade due to an exception " + e);
            e.printStackTrace();
            return false;
        }
        return true;
    }

    public boolean handleHDFSStopSNN(HDFSStopSNN hdfsStopSNN) {
        try {
            assert dockerCluster instanceof HdfsDockerCluster;
            ((HdfsDockerCluster) dockerCluster).stopSNN();
        } catch (Exception e) {
            logger.error("hdfs cannot stop SNN due to an exception " + e);
            e.printStackTrace();
            return false;
        }
        return true;
    }

    public boolean handleFinalizeUpgrade(FinalizeUpgrade finalizeUpgrade) {
        try {
            dockerCluster.finalizeUpgrade();
        } catch (Exception e) {
            logger.error("hdfs cannot stop SNN due to an exception " + e);
            e.printStackTrace();
            return false;
        }
        return true;
    }

    public Map<Integer, LogInfo> grepLogInfo() {
        return dockerCluster.grepLogInfo();
    }

}
