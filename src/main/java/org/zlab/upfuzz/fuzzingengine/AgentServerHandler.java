package org.zlab.upfuzz.fuzzingengine;

import java.io.IOException;
import java.net.Socket;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.jacoco.core.data.ExecutionData;
import org.jacoco.core.data.ExecutionDataStore;
import org.jacoco.core.data.ExecutionDataWriter;
import org.jacoco.core.data.IExecutionDataVisitor;
import org.jacoco.core.data.ISessionInfoVisitor;
import org.jacoco.core.data.SessionInfo;
import org.jacoco.core.runtime.RemoteControlReader;
import org.jacoco.core.runtime.RemoteControlWriter;
import org.zlab.upfuzz.fuzzingengine.executor.Executor;
import org.zlab.upfuzz.hbase.HBaseDockerCluster;
import org.zlab.upfuzz.hdfs.HdfsDockerCluster;
import org.zlab.upfuzz.ozone.OzoneDockerCluster;
import org.zlab.upfuzz.utils.Utilities;
import java.util.concurrent.ConcurrentHashMap;
import java.util.Map;

public class AgentServerHandler
        implements Runnable, ISessionInfoVisitor, IExecutionDataVisitor {

    static Logger logger = LogManager.getLogger(AgentServerHandler.class);

    private final Executor executor;
    private final Socket socket;
    private String sessionId;
    private SessionInfo sesInfo;

    public CountDownLatch okCMD = new CountDownLatch(1);

    private final RemoteControlReader reader;
    private final RemoteControlWriter writer;

    private final ExecutionDataWriter fileWriter;

    private final int maxn = 10240;

    private boolean registered = false;

    private byte[] buffer;

    AgentServerHandler(final Executor executor, final Socket socket,
            ExecutionDataWriter fileWriter) throws IOException {

        // logger.info("AgentServerHandler registering: "
        // + socket.getRemoteSocketAddress());
        this.executor = executor;
        this.socket = socket;
        this.fileWriter = fileWriter;

        this.socket.setSendBufferSize(128 * 1024);
        this.socket.setReceiveBufferSize(128 * 1024);

        // Just send a valid header:
        writer = new RemoteControlWriter(socket.getOutputStream());

        reader = new RemoteControlReader(socket.getInputStream());
        reader.setSessionInfoVisitor(this);
        reader.setExecutionDataVisitor(this);
        buffer = new byte[maxn];
    }

    @Override
    public void run() {
        try {
            while (reader.read()) {
                okCMD.countDown();
            }

            // logger.debug(String.format("connection %s, sessionId = %s
            // closed",
            // socket.getRemoteSocketAddress(), sessionId));
            socket.close();
            // synchronized (fileWriter) {
            // fileWriter.flush();
            // }
        } catch (final IOException e) {
            logger.error("Error while handling agent connection: " + sessionId);
            e.printStackTrace();
            executor.agentHandler.remove(sessionId);
        }
    }

    private void register(SessionInfo info) {
        sesInfo = info;
        sessionId = info.getId();
        String[] sessionSplit = sessionId.split("-");
        if (sessionSplit.length != 4) {
            System.err.println("Invalid sessionId " + sessionId);
            return;
        }

        // hdfs filter: only collecting nn, snn and dn
        if (sessionSplit[0].equals("hdfs")
                && !Utilities.contains(sessionSplit[3],
                        HdfsDockerCluster.includeJacocoHandlers)) {
            // if (Config.getConf().debug) {
            // logger.info("Skip register: "
            // + socket.getRemoteSocketAddress().toString() + " " +
            // sessionId + ": not in target hdfs process");
            // }
            return;
        }

        // hbase filter: master, regionserver, zookeeper
        if (sessionSplit[0].equals("hbase")
                && !Utilities.contains(sessionSplit[3],
                        HBaseDockerCluster.includeJacocoHandlers)) {
            // if (Config.getConf().debug) {
            // logger.info("Skip register: "
            // + socket.getRemoteSocketAddress().toString() + " " +
            // sessionId + ": not in target hdfs process");
            // }
            return;
        }

        // ozone filter: collecting dn, recon, om and scm
        if (sessionSplit[0].equals("ozone")
                && !Utilities.contains(sessionSplit[3],
                        OzoneDockerCluster.includeJacocoHandlers)) {
            return;
        }

        if (sessionSplit[3].equals("null")) {
            // if (Config.getConf().debug) {
            // logger.info("Skip register: "
            // + socket.getRemoteSocketAddress().toString() + " " +
            // sessionId + ", main function is null");
            // }

            return;
        }
        logger.debug(
                "Agent" + socket.getRemoteSocketAddress().toString() + " " +
                        sessionId + " registered");
        // There could be several process to monitor
        // We need to update this variable
        // How to make sure that handler is updated???
        // We performed the upgrade but packet is not received.
        // We might lose the coverage
        // logger.info("[HKLOG: AgenetServerHandler] Session ID: " + sessionId);
        executor.agentHandler.put(sessionId, this);
        for (Map.Entry<String, AgentServerHandler> entry : executor.agentHandler
                .entrySet()) {
            // logger.info("After putting in agentHandler: "
            // + entry.getKey() + " : " + entry.getValue().sessionId);
        }

        String identifier = sessionSplit[0], executorID = sessionSplit[1],
                index = sessionSplit[2], nodeID = sessionSplit[3];
        // logger.info("identifier: " + identifier + ", executorID: "
        // + executorID + ", index: " + index + ", nodeID: " + nodeID);

        if (!executor.sessionGroup.containsKey(executorID)) {
            // logger.info("executor ID " + executorID
            // + " not available in session group");
            executor.sessionGroup.put(executorID, new HashSet<>());
        }
        // logger.info("adding sessionId: " + sessionId);
        // logger.info("executorID: " + executorID);
        executor.sessionGroup.get(executorID).add(sessionId);
        registered = true;
    }

    public void visitSessionInfo(final SessionInfo info) {
        if (!registered) {
            // logger.info(info + " not registered");
            register(info);
        } else {
            // logger.debug("Retrieving execution Data for session: " +
            // info.getId());
        }
        // synchronized (fileWriter) {
        // fileWriter.visitSessionInfo(info);
        // }
    }

    public void visitClassExecution(final ExecutionData data) {
        // logger.info(sessionId + " get data");
        // logger.info(data.getName());
        if (executor.agentStore.containsKey(sessionId)) {
            ExecutionDataStore store = executor.agentStore.get(sessionId);

            ExecutionData preData = store.get(data.getId());
            if (preData != null) {
                // FIXME take the maxinum value when merging data
                data.merge(preData, false);
            }
            store.put(data);
            executor.agentStore.put(sessionId, store);
        } else {
            ExecutionDataStore store = new ExecutionDataStore();
            store.put(data);
            executor.agentStore.put(sessionId, store);
        }
        // synchronized (fileWriter) {
        // fileWriter.visitClassExecution(data);
        // }
    }

    public void collect() {
        // FIXME frequently collect null
        logger.debug("collect coverage for " + sessionId + "...");
        try {
            writer.visitDumpCommand(true, true);
        } catch (IOException e) {
            logger.debug("agent connection " + sessionId + " closed");
            return;
        }
        okCMD = new CountDownLatch(1);
        synchronized (okCMD) {
            try {
                okCMD.await(1000, TimeUnit.MILLISECONDS);
            } catch (InterruptedException e) {
                logger.error("handler collect " + sessionId + "... timeout");
            }
        }
    }

    /**
     * Phase 5: non-destructive coverage snapshot. Dumps the agent's
     * current execution data WITHOUT resetting it, so the final
     * round-end {@link #collect()} still sees the full cumulative
     * coverage. Used by stage boundary snapshots so they do not
     * drain probes from the end-of-round collection.
     */
    public void collectSnapshot() {
        logger.debug("snapshot coverage for " + sessionId + "...");
        try {
            writer.visitDumpCommand(true, false);
        } catch (IOException e) {
            logger.debug("agent connection " + sessionId + " closed");
            return;
        }
        okCMD = new CountDownLatch(1);
        synchronized (okCMD) {
            try {
                okCMD.await(1000, TimeUnit.MILLISECONDS);
            } catch (InterruptedException e) {
                logger.error(
                        "handler snapshot " + sessionId + "... timeout");
            }
        }
    }
}
