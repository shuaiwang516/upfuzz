package org.zlab.upfuzz.fuzzingengine;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.Map;
import java.util.HashMap;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.jacoco.core.data.ExecutionDataStore;
import org.zlab.upfuzz.fuzzingengine.packet.*;
import org.zlab.upfuzz.utils.Pair;
import org.zlab.upfuzz.utils.Utilities;
import org.zlab.upfuzz.fuzzingengine.executor.Executor;
import org.zlab.upfuzz.fuzzingengine.LogInfo;

class VersionDeltaStackedTestThread implements Callable<StackedFeedbackPacket> {
    static Logger logger = LogManager
            .getLogger(VersionDeltaStackedTestThread.class);

    private final Executor executor;
    private final int direction;
    private final StackedTestPacket stackedTestPacket;
    private final boolean isDowngradeSupported;
    private final int group; // Shared decision variable

    int CLUSTER_START_RETRY = 3;

    public VersionDeltaStackedTestThread(Executor executor, int direction,
            StackedTestPacket stackedTestPacket, boolean isDowngradeSupported,
            int group) {
        this.executor = executor;
        this.direction = direction;
        this.stackedTestPacket = stackedTestPacket;
        this.group = group;
        this.isDowngradeSupported = isDowngradeSupported;
    }

    public boolean startUpExecutor() {
        logger.info(
                "[HKLOG] Fuzzing client: starting up executor: " + direction);
        for (int i = 0; i < CLUSTER_START_RETRY; i++) {
            try {
                if (executor.startup()) {
                    if (Config.getConf().debug) {
                        logger.info(
                                "[Fuzzing Client] started up executor after trial "
                                        + i);
                    }
                    return true;
                }
            } catch (Exception e) {
                logger.error("An error occurred", e);
            }
            executor.teardown();
        }
        logger.error("original version cluster cannot start up");
        return false;
    }

    public void tearDownExecutor() {
        executor.clearState();
        executor.teardown();
    }

    public StackedFeedbackPacket runTestBatchBeforeChangingTheVersion(
            Executor executor, StackedTestPacket stackedTestPacket,
            int direction) {
        // if the middle of test has already broken an invariant
        // we stop executing.
        int executedTestNum = 0;
        boolean breakNewInv = false;
        Map<Integer, FeedbackPacket> testID2FeedbackPacket = new HashMap<>();
        Map<Integer, LogInfo> logInfoBeforeVersionChange = new HashMap<>();
        Map<Integer, List<String>> testID2oriResults = new HashMap<>();

        System.out.println("Invoked with direction: " + direction);

        int j = 0;
        for (TestPacket tp : stackedTestPacket.getTestPacketList()) {
            j += 1;
            if (tp != null) {
                executedTestNum++;
                logger.info(j + "th testPacket null? "
                        + ((tp == null) ? " YES" : (tp.testPacketID)));

                executor.executeCommands(tp.originalCommandSequenceList);
                if (Config.getConf().flushAfterTest)
                    executor.flush();

                FeedBack[] feedBacks = new FeedBack[stackedTestPacket.nodeNum];
                for (int i = 0; i < stackedTestPacket.nodeNum; i++) {
                    feedBacks[i] = new FeedBack();
                }
                ExecutionDataStore[] oriCoverages = (direction == 0)
                        ? executor
                                .collectCoverageSeparate("original")
                        : executor
                                .collectCoverageSeparate("upgraded");
                if (oriCoverages != null) {
                    int coverageBound = Math.min(stackedTestPacket.nodeNum,
                            oriCoverages.length);
                    for (int nodeIdx = 0; nodeIdx < coverageBound; nodeIdx++) {
                        feedBacks[nodeIdx].originalCodeCoverage = oriCoverages[nodeIdx];
                    }
                }
                testID2FeedbackPacket.put(
                        tp.testPacketID,
                        new FeedbackPacket(tp.systemID,
                                stackedTestPacket.nodeNum,
                                tp.testPacketID, feedBacks, null));

                List<String> oriResult = executor
                        .executeCommands(tp.validationCommandSequenceList);
                testID2oriResults.put(tp.testPacketID, oriResult);

                if (Config.getConf().useFormatCoverage) {
                    if (stackedTestPacket.clientGroupForVersionDelta != 2) {
                        Path formatInfoFolder;
                        Path oriFormatInfoFolder = Paths.get("configInfo")
                                .resolve(Config.getConf().originalVersion);
                        Path upFormatInfoFolder = Paths.get("configInfo")
                                .resolve(Config.getConf().upgradedVersion);
                        if (direction == 0)
                            formatInfoFolder = oriFormatInfoFolder;
                        else
                            formatInfoFolder = upFormatInfoFolder;
                        testID2FeedbackPacket
                                .get(tp.testPacketID).formatCoverage = executor
                                        .getFormatCoverage(formatInfoFolder);
                    }
                }
            } else {
                logger.info(j + "th testPacket null? "
                        + ((tp == null) ? " YES" : (tp.testPacketID)));
            }
        }

        StackedFeedbackPacket stackedFeedbackPacket = new StackedFeedbackPacket(
                stackedTestPacket.configFileName,
                Utilities.extractTestIDs(stackedTestPacket));
        stackedFeedbackPacket.fullSequence = FuzzingClient
                .recordStackedTestPacket(
                        stackedTestPacket);
        stackedFeedbackPacket.breakNewInv = breakNewInv;

        // LOG checking1
        if (Config.getConf().enableLogCheck) {
            // logger.info("[HKLOG] error log checking");
            logInfoBeforeVersionChange = executor.grepLogInfo();
        }
        if (Config.getConf().enableLogCheck
                && FuzzingClient.hasERRORLOG(logInfoBeforeVersionChange)) {
            stackedFeedbackPacket.hasERRORLog = true;
            stackedFeedbackPacket.errorLogReport = FuzzingClient
                    .genErrorLogReport(
                            executor.executorID,
                            stackedTestPacket.configFileName,
                            logInfoBeforeVersionChange);
        }

        for (int testPacketIdx = 0; testPacketIdx < executedTestNum; testPacketIdx++) {
            TestPacket tp = stackedTestPacket.getTestPacketList()
                    .get(testPacketIdx);
            FeedbackPacket feedbackPacket = testID2FeedbackPacket
                    .get(tp.testPacketID);
            List<String> oriResult = testID2oriResults.get(tp.testPacketID);
            LogInfo logInfo = logInfoBeforeVersionChange.get(tp.testPacketID);
            stackedFeedbackPacket.addFeedbackPacket(feedbackPacket);
            stackedFeedbackPacket.oriResults.add(oriResult);
            stackedFeedbackPacket.logInfos.add(logInfo);
        }
        stackedFeedbackPacket.setVersion(executor.dockerCluster.version);
        return stackedFeedbackPacket;
    }

    public StackedFeedbackPacket changeVersionAndRunTheTestBatch(
            Executor executor,
            StackedTestPacket stackedTestPacket, int direction,
            boolean isDowngradeSupported,
            StackedFeedbackPacket stackedFeedbackPacket) {

        Map<Integer, List<String>> testID2modifiedVersionResults = new HashMap<>();
        boolean upgradeStatus = false;
        boolean downgradeStatus = false;
        Map<Integer, FeedbackPacket> testID2FeedbackPacket = new HashMap<>();
        Map<Integer, List<String>> testID2oriResults = new HashMap<>();
        Map<Integer, List<String>> testID2upResults = new HashMap<>();
        Map<Integer, LogInfo> logInfoBeforeVersionChange = new HashMap<>();

        int i = 0;
        for (int id : stackedFeedbackPacket.testIDs) {
            testID2FeedbackPacket.put(id,
                    stackedFeedbackPacket.getFpList().get(i));
            testID2oriResults.put(id,
                    stackedFeedbackPacket.getOriResults().get(i));
            i += 1;
        }

        int executedTestNum = stackedTestPacket.getTestPacketList().size();
        if (direction == 0)
            upgradeStatus = executor.fullStopUpgrade();
        else {
            if (isDowngradeSupported) {
                downgradeStatus = executor.downgrade();
            } else {
                downgradeStatus = true;
            }
        }

        if ((direction == 0) && (!upgradeStatus)) {
            // upgrade failed
            String upgradeFailureReport = FuzzingClient.genUpgradeFailureReport(
                    executor.executorID, stackedTestPacket.configFileName);
            stackedFeedbackPacket.isUpgradeProcessFailed = true;
            stackedFeedbackPacket.upgradeFailureReport = upgradeFailureReport;
        } else if ((direction == 1) && (!downgradeStatus)) {
            // downgrade failed
            String downgradeFailureReport = FuzzingClient
                    .genDowngradeFailureReport(
                            executor.executorID,
                            stackedTestPacket.configFileName);
            stackedFeedbackPacket.isDowngradeProcessFailed = true;
            stackedFeedbackPacket.downgradeFailureReport = downgradeFailureReport;
        } else if ((direction == 0) && (upgradeStatus)) {
            // logger.info("upgrade succeed");
            stackedFeedbackPacket.isUpgradeProcessFailed = false;
            for (int testPacketIdx = 0; testPacketIdx < executedTestNum; testPacketIdx++) {
                TestPacket tp = stackedTestPacket.getTestPacketList()
                        .get(testPacketIdx);
                if (tp != null) {
                    System.out.println(
                            testID2FeedbackPacket.get(tp.testPacketID));

                    List<String> upResult = executor
                            .executeCommands(tp.validationCommandSequenceList);
                    testID2modifiedVersionResults.put(tp.testPacketID,
                            upResult);
                    if (Config.getConf().collUpFeedBack) {
                        ExecutionDataStore[] upCoverages = executor
                                .collectCoverageSeparate("upgraded");
                        if (upCoverages != null) {
                            int coverageBound = Math.min(
                                    stackedTestPacket.nodeNum,
                                    upCoverages.length);
                            for (int nodeIdx = 0; nodeIdx < coverageBound; nodeIdx++) {
                                testID2FeedbackPacket.get(
                                        tp.testPacketID).feedBacks[nodeIdx].upgradedCodeCoverage = upCoverages[nodeIdx];
                            }
                        }
                    }
                    Pair<Boolean, String> compareRes = executor
                            .checkResultConsistency(
                                    testID2oriResults.get(tp.testPacketID),
                                    testID2modifiedVersionResults
                                            .get(tp.testPacketID),
                                    true);
                    FeedbackPacket feedbackPacket = testID2FeedbackPacket
                            .get(tp.testPacketID);
                    if (!compareRes.left) {
                        String failureReport = FuzzingClient
                                .genInconsistencyReport(
                                        executor.executorID,
                                        stackedTestPacket.configFileName,
                                        compareRes.right,
                                        FuzzingClient
                                                .recordSingleTestPacket(tp));
                        feedbackPacket.isInconsistent = true;
                        if (compareRes.right
                                .contains(
                                        "Insignificant Result inconsistency")) {
                            feedbackPacket.isInconsistencyInsignificant = true;
                        }
                        feedbackPacket.inconsistencyReport = failureReport;
                    }
                    feedbackPacket.validationReadResults = testID2upResults
                            .get(tp.testPacketID);
                } else {
                    logger.info(testPacketIdx + "th testPacket null? "
                            + ((tp == null) ? " YES" : (tp.testPacketID)));
                }
            }
        } else if ((direction == 1) && (downgradeStatus)) {
            // logger.info("upgrade succeed");
            if (isDowngradeSupported) {
                stackedFeedbackPacket.isDowngradeProcessFailed = false;
                for (int testPacketIdx = 0; testPacketIdx < executedTestNum; testPacketIdx++) {
                    TestPacket tp = stackedTestPacket.getTestPacketList()
                            .get(testPacketIdx);
                    if (tp != null) {
                        List<String> downResult = executor
                                .executeCommands(
                                        tp.validationCommandSequenceList);
                        testID2modifiedVersionResults.put(tp.testPacketID,
                                downResult);
                        if (Config.getConf().collDownFeedBack) {
                            ExecutionDataStore[] downCoverages = executor
                                    .collectCoverageSeparate("original");
                            if (downCoverages != null) {
                                int coverageBound = Math.min(
                                        stackedTestPacket.nodeNum,
                                        downCoverages.length);
                                for (int nodeIdx = 0; nodeIdx < coverageBound; nodeIdx++) {
                                    System.out.println(
                                            testID2FeedbackPacket
                                                    .get(tp.testPacketID));
                                    testID2FeedbackPacket.get(
                                            tp.testPacketID).feedBacks[nodeIdx].downgradedCodeCoverage = downCoverages[nodeIdx];
                                }
                            }
                        }
                        Pair<Boolean, String> compareRes = executor
                                .checkResultConsistency(
                                        testID2oriResults.get(tp.testPacketID),
                                        testID2modifiedVersionResults
                                                .get(tp.testPacketID),
                                        true);
                        // Update FeedbackPacket
                        FeedbackPacket feedbackPacket = testID2FeedbackPacket
                                .get(tp.testPacketID);
                        if (!compareRes.left) {
                            String failureReport = FuzzingClient
                                    .genInconsistencyReport(
                                            executor.executorID,
                                            stackedTestPacket.configFileName,
                                            compareRes.right,
                                            FuzzingClient
                                                    .recordSingleTestPacket(
                                                            tp));
                            feedbackPacket.isInconsistent = true;
                            feedbackPacket.inconsistencyReport = failureReport;
                        }
                        feedbackPacket.validationReadResults = testID2upResults
                                .get(tp.testPacketID);
                    } else {
                        logger.info(testPacketIdx + "th testPacket null? "
                                + ((tp == null) ? " YES" : (tp.testPacketID)));
                    }
                }
            } else {
                stackedFeedbackPacket.isDowngradeProcessFailed = false;
                return stackedFeedbackPacket;
            }
        }

        // update stackedFeedbackPacket
        for (int testPacketIdx = 0; testPacketIdx < executedTestNum; testPacketIdx++) {
            TestPacket tp = stackedTestPacket.getTestPacketList()
                    .get(testPacketIdx);
            FeedbackPacket feedbackPacket = testID2FeedbackPacket
                    .get(tp.testPacketID);
            stackedFeedbackPacket.updateFeedbackPacket(testPacketIdx,
                    feedbackPacket);
        }

        // test downgrade
        if (Config.getConf().testDowngrade) {
            // logger.info("downgrade cluster");
            if (isDowngradeSupported) {
                downgradeStatus = executor.downgrade();
                if (!downgradeStatus) {
                    // downgrade failed
                    stackedFeedbackPacket.isDowngradeProcessFailed = true;
                    stackedFeedbackPacket.downgradeFailureReport = FuzzingClient
                            .genDowngradeFailureReport(
                                    executor.executorID,
                                    stackedFeedbackPacket.configFileName);
                }
            }
        }

        // LOG checking2
        if (Config.getConf().enableLogCheck) {
            assert logInfoBeforeVersionChange != null;
            Map<Integer, LogInfo> logInfo = FuzzingClient
                    .extractErrorLog(executor, logInfoBeforeVersionChange);
            if (FuzzingClient.hasERRORLOG(logInfo)) {
                stackedFeedbackPacket.hasERRORLog = true;
                stackedFeedbackPacket.errorLogReport = FuzzingClient
                        .genErrorLogReport(
                                executor.executorID,
                                stackedTestPacket.configFileName,
                                logInfo);
            }
        }
        return stackedFeedbackPacket;
    }

    @Override
    public StackedFeedbackPacket call() throws Exception {

        if (Config.getConf().debug) {
            logger.info("[Fuzzing Client] Call to start up executor: "
                    + (direction == 0 ? "upgrade" : "downgrade"));
        }
        boolean startUpStatus = startUpExecutor();
        if (!startUpStatus) {
            // old version **cluster** start up problem, this won't be upgrade
            // bugs
            logger.info("[Fuzzing Client] Cluster startup problem: "
                    + (direction == 0 ? "upgrade" : "downgrade"));
            return null;
        }

        if (Config.getConf().startUpClusterForDebugging) {
            logger.info("[Debugging Mode] Start up the cluster only");
            Utilities.sleepAndExit(36000);
        }
        try {
            StackedFeedbackPacket stackedFeedbackPacketBeforeVersionChange = runTestBatchBeforeChangingTheVersion(
                    executor,
                    stackedTestPacket, direction);

            if (this.group == 1) {
                tearDownExecutor();
                return stackedFeedbackPacketBeforeVersionChange;
            } else {
                StackedFeedbackPacket stackedFeedbackPacket = changeVersionAndRunTheTestBatch(
                        executor, stackedTestPacket, direction,
                        isDowngradeSupported,
                        stackedFeedbackPacketBeforeVersionChange);
                tearDownExecutor();
                return stackedFeedbackPacket;
            }
        } catch (Exception e) {
            // Make sure there's no dangling containers
            tearDownExecutor();
            throw e;
        }
    }
}
