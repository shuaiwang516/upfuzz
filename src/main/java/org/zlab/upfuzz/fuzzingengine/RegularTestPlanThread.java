package org.zlab.upfuzz.fuzzingengine;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.LinkedList;
import java.util.List;
import java.util.concurrent.*;
import java.util.Map;
import java.util.HashMap;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.jacoco.core.data.ExecutionDataStore;
import org.zlab.upfuzz.fuzzingengine.packet.*;
import org.zlab.upfuzz.utils.Pair;
import org.zlab.upfuzz.utils.Utilities;
import org.zlab.upfuzz.fuzzingengine.executor.Executor;

import static org.zlab.upfuzz.fuzzingengine.FuzzingClient.*;

class RegularTestPlanThread implements Callable<TestPlanFeedbackPacket> {
    static Logger logger = LogManager
            .getLogger(VersionDeltaStackedTestThread.class);

    private final Executor executor;
    private final TestPlanPacket testPlanPacket;

    int CLUSTER_START_RETRY = 3;

    public RegularTestPlanThread(Executor executor,
            TestPlanPacket testPlanPacket) {
        this.executor = executor;
        this.testPlanPacket = testPlanPacket;
    }

    public boolean startUpExecutor() {
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
                && hasERRORLOG(logInfoBeforeVersionChange)) {
            stackedFeedbackPacket.hasERRORLog = true;
            stackedFeedbackPacket.errorLogReport = genErrorLogReport(
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

    @Override
    public TestPlanFeedbackPacket call() throws Exception {

        String testPlanPacketStr = recordTestPlanPacket(testPlanPacket);
        int nodeNum = testPlanPacket.getNodeNum();

        boolean startUpStatus = startUpExecutor();
        if (!startUpStatus)
            return null;

        // LOG checking1
        long curTime2 = System.currentTimeMillis();
        Map<Integer, LogInfo> logInfoBeforeUpgrade = null;
        if (Config.getConf().enableLogCheck) {
            logger.info("[HKLOG] error log checking");
            logInfoBeforeUpgrade = executor.grepLogInfo();
        }
        if (Config.getConf().debug) {
            logger.info(String.format(
                    "[Fuzzing Client] completed first log checking in %d ms",
                    System.currentTimeMillis() - curTime2));
        }

        if (Config.getConf().keepClusterBeforeExecutingTestplan) {
            logger.info(
                    "[Debugging Mode] Start up the cluster only, before executing the test plan");
            Utilities.sleepAndExit(36000);
        }

        boolean status = executor.execute(testPlanPacket.getTestPlan());

        if (Config.getConf().keepClusterAfterExecutingTestplan) {
            logger.info(
                    "[Debugging Mode] Start up the cluster only, after executing the test plan");
            Utilities.sleepAndExit(36000);
        }

        if (Config.getConf().debug)
            logger.info("[Fuzzing Client] completed the testing");

        FeedBack[] testPlanFeedBacks = new FeedBack[nodeNum];

        long curTime = System.currentTimeMillis();
        if (status && Config.getConf().fullStopUpgradeWithFaults) {
            // collect old version coverage
            ExecutionDataStore[] oriCoverages = executor
                    .collectCoverageSeparate("original");
            for (int i = 0; i < nodeNum; i++) {
                testPlanFeedBacks[i] = new FeedBack();
                if (oriCoverages != null && i < oriCoverages.length)
                    testPlanFeedBacks[i].originalCodeCoverage = oriCoverages[i];
            }
            // upgrade
            status = executor.fullStopUpgrade();
            if (!status)
                // update event id
                executor.eventIdx = -1; // this means full-stop upgrade failed
        } else {
            // It contains the new version coverage!
            // collect test plan coverage
            for (int i = 0; i < nodeNum; i++) {
                testPlanFeedBacks[i] = new FeedBack();
                if (executor.oriCoverage != null
                        && i < executor.oriCoverage.length
                        && executor.oriCoverage[i] != null)
                    testPlanFeedBacks[i].originalCodeCoverage = executor.oriCoverage[i];
            }
        }
        if (Config.getConf().debug) {
            logger.info(String.format(
                    "[Fuzzing Client] completed collecting code coverages in %d ms",
                    System.currentTimeMillis() - curTime));
        }

        TestPlanFeedbackPacket testPlanFeedbackPacket = new TestPlanFeedbackPacket(
                testPlanPacket.systemID, testPlanPacket.configFileName,
                testPlanPacket.testPacketID, testPlanFeedBacks);
        testPlanFeedbackPacket.fullSequence = testPlanPacketStr;

        // Collect network traces
        if (Config.getConf().useTrace) {
            executor.updateTrace();
            testPlanFeedbackPacket.trace = executor.trace;
        }

        if (!status) {
            testPlanFeedbackPacket.isEventFailed = true;

            testPlanFeedbackPacket.eventFailedReport = genTestPlanFailureReport(
                    executor.eventIdx, executor.executorID,
                    testPlanPacket.configFileName,
                    testPlanPacketStr);
            testPlanFeedbackPacket.isInconsistent = false;
            testPlanFeedbackPacket.inconsistencyReport = "";
        } else {
            // Test single version
            if (Config.getConf().testSingleVersion) {
                try {
                    ExecutionDataStore[] oriCoverages = executor
                            .collectCoverageSeparate("original");
                    if (oriCoverages != null) {
                        int coverageBound = Math.min(nodeNum,
                                oriCoverages.length);
                        for (int nodeIdx = 0; nodeIdx < coverageBound; nodeIdx++) {
                            testPlanFeedbackPacket.feedBacks[nodeIdx].originalCodeCoverage = oriCoverages[nodeIdx];
                        }
                    }
                } catch (Exception e) {
                    // Cannot collect code coverage in the upgraded version
                    testPlanFeedbackPacket.isEventFailed = true;
                    testPlanFeedbackPacket.eventFailedReport = genOriCoverageCollFailureReport(
                            executor.executorID, testPlanPacket.configFileName,
                            recordTestPlanPacket(testPlanPacket)) + "Exception:"
                            + e;
                    tearDownExecutor();
                    return testPlanFeedbackPacket;
                }

            } else {
                Pair<Boolean, String> compareRes;
                List<String> testPlanReadResults = new LinkedList<>();
                if (!testPlanPacket.getTestPlan().validationCommands
                        .isEmpty()) {
                    // WS1: use structured execution, populate both fields
                    List<ValidationResult> structuredResults = executor
                            .executeCommandsStructured(
                                    testPlanPacket
                                            .getTestPlan().validationCommands);
                    testPlanFeedbackPacket.validationResults = structuredResults;
                    // Backward compat: populate legacy field
                    for (ValidationResult vr : structuredResults) {
                        testPlanReadResults.add(vr.toLegacyString());
                    }
                    testPlanFeedbackPacket.validationReadResults = testPlanReadResults;
                    logger.info("[HKLOG] validationReadResults size = "
                            + testPlanReadResults.size());
                } else {
                    logger.debug("validationCommands is empty!");
                }

                // read comparison between oracle and rolling
                if (!testPlanPacket.getTestPlan().validationReadResultsOracle
                        .isEmpty()) {
                    compareRes = executor
                            .checkResultConsistency(
                                    testPlanPacket
                                            .getTestPlan().validationReadResultsOracle,
                                    testPlanReadResults, false);
                    if (!compareRes.left) {
                        testPlanFeedbackPacket.isInconsistent = true;
                        testPlanFeedbackPacket.inconsistencyReport = genTestPlanInconsistencyReport(
                                executor.executorID,
                                testPlanPacket.configFileName,
                                compareRes.right, testPlanPacketStr);
                    }
                }
                if (testPlanPacket.getTestPlan().validationReadResultsOracle
                        .isEmpty()) {
                    logger.debug("validationReadResultsOracle is empty!");
                }

                try {
                    ExecutionDataStore[] upCoverages = executor
                            .collectCoverageSeparate("upgraded");
                    if (upCoverages != null) {
                        int coverageBound = Math.min(nodeNum,
                                upCoverages.length);
                        for (int nodeIdx = 0; nodeIdx < coverageBound; nodeIdx++) {
                            testPlanFeedbackPacket.feedBacks[nodeIdx].upgradedCodeCoverage = upCoverages[nodeIdx];
                        }
                    }
                } catch (Exception e) {
                    // Cannot collect code coverage in the upgraded version
                    testPlanFeedbackPacket.isEventFailed = true;
                    testPlanFeedbackPacket.eventFailedReport = genUpCoverageCollFailureReport(
                            executor.executorID, testPlanPacket.configFileName,
                            recordTestPlanPacket(testPlanPacket)) + "Exception:"
                            + e;
                    tearDownExecutor();
                    return testPlanFeedbackPacket;
                }
            }
        }

        // LOG checking2
        curTime = System.currentTimeMillis();
        if (Config.getConf().enableLogCheck) {
            if (Config.getConf().testSingleVersion) {
                logger.info("[HKLOG] error log checking");
                Map<Integer, LogInfo> logInfo = extractErrorLog(executor,
                        logInfoBeforeUpgrade);
                if (hasERRORLOG(logInfo)) {
                    testPlanFeedbackPacket.hasERRORLog = true;
                    testPlanFeedbackPacket.errorLogReport = genErrorLogReport(
                            executor.executorID, testPlanPacket.configFileName,
                            logInfo);
                }
            } else {
                logger.info("[HKLOG] error log checking");
                assert logInfoBeforeUpgrade != null;
                Map<Integer, LogInfo> logInfo = extractErrorLog(executor,
                        logInfoBeforeUpgrade);
                if (hasERRORLOG(logInfo)) {
                    testPlanFeedbackPacket.hasERRORLog = true;
                    testPlanFeedbackPacket.errorLogReport = genErrorLogReport(
                            executor.executorID, testPlanPacket.configFileName,
                            logInfo);
                }
            }
        }
        if (Config.getConf().debug) {
            logger.info(String.format(
                    "[Fuzzing Client] completed second log checking in %d ms",
                    System.currentTimeMillis() - curTime));

            logger.info("[Fuzzing Client] Call to teardown executor");
        }
        tearDownExecutor();
        if (Config.getConf().debug) {
            logger.info("[Fuzzing Client] Executor torn down");
        }
        return testPlanFeedbackPacket;
    }
}
