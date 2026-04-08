package org.zlab.upfuzz.hbase;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.zlab.upfuzz.docker.Docker;
import org.zlab.upfuzz.fuzzingengine.ClusterStuckException;
import org.zlab.upfuzz.fuzzingengine.Config;
import org.zlab.upfuzz.fuzzingengine.ShellDaemon;
import org.zlab.upfuzz.fuzzingengine.packet.ValidationResult;
import org.zlab.upfuzz.utils.Utilities;

import java.io.*;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketTimeoutException;

public class HBaseShellDaemon extends ShellDaemon {
    static Logger logger = LogManager.getLogger(HBaseShellDaemon.class);

    private Socket socket;
    private final String ipAddress;
    private final int port;

    public HBaseShellDaemon(String ipAddress, int port, String executorID,
            Docker docker) {
        this.ipAddress = ipAddress;
        this.port = port;
        logger.info("[HKLOG] executor ID = " + executorID + "  "
                + "Connect to hbase shell daemon:" + ipAddress + "...");
        for (int i = 0; i < Config.getConf().hbaseDaemonRetryTimes; ++i) {
            try {
                if (i % 5 == 0) {
                    logger.debug("[HKLOG] executor ID = " + executorID + "  "
                            + "Connect to hbase shell:" + ipAddress + "..."
                            + i);
                }
                socket = new Socket();
                socket.connect(new InetSocketAddress(ipAddress, port),
                        3 * 1000);
                logger.info("[HKLOG] executor ID = " + executorID + "  "
                        + "hbase shell connected: " + ipAddress);
                return;
            } catch (Exception ignored) {
            }
            try {
                Thread.sleep(5 * 1000);
            } catch (InterruptedException ignored) {
            }
        }
        throw new RuntimeException("[HKLOG] executor ID = " + executorID
                + "  " + "cannot connect to hbase shell at " + ipAddress);
    }

    public HBasePacket execute(String cmd)
            throws IOException, ClusterStuckException {
        // If socket is null (e.g. a prior timeout's reconnect failed),
        // attempt to re-establish before giving up. This prevents one
        // transient reconnect miss from permanently poisoning the session
        // for the rest of the readiness-probe loop.
        if (socket == null) {
            logger.warn(
                    "HBase shell daemon socket is null before execute(); "
                            + "attempting reconnect for command [{}]",
                    cmd);
            reconnect(); // throws IOException if it fails — caller sees it
        }

        socket.setSoTimeout(240_000); // 4 minutes

        try {
            Utilities.serializeSingleCommand(cmd, socket.getOutputStream());

            String hbaseMessage = Utilities.deserializeSingleCommandResult(
                    new DataInputStream(socket.getInputStream()));

            Gson gson = new GsonBuilder()
                    .setLenient()
                    .create();

            HBasePacket hbasePacket = null;
            try {
                hbasePacket = gson.fromJson(hbaseMessage, HBasePacket.class);
            } catch (Exception e) {
                e.printStackTrace();
                logger.error(
                        "ERROR: Cannot read from JSON. WRONG_HBase MESSAGE: "
                                + hbaseMessage);
            }

            if (Config.getConf().debug) {
                String prettyJson = new GsonBuilder().setPrettyPrinting()
                        .create().toJson(hbasePacket);
                logger.debug("HBaseMessage:\n" + prettyJson);
            }

            return hbasePacket;

        } catch (SocketTimeoutException e) {
            // The command was already sent over TCP, but the read timed out.
            // The daemon-side shell process may still complete the command
            // later, so any future bytes on this socket are ambiguous — they
            // could belong to the timed-out response or to a later command.
            //
            // Reconnect the client socket so the caller never retries on an
            // ambiguous stream. This is best-effort client-side stream
            // resynchronization, NOT a guaranteed daemon reset. The shared
            // HBase shell process is stateful and may still be executing the
            // timed-out command. For read-only readiness probes (status,
            // list_namespace) this is an acceptable trade-off; for mutating
            // commands the caller should still treat the outcome as unknown.
            logger.warn(
                    "HBase shell daemon timed out on command [{}]; "
                            + "closing and reconnecting socket",
                    cmd, e);
            try {
                socket.close();
            } catch (IOException ignored) {
            }
            boolean reconnected = false;
            try {
                reconnect();
                reconnected = true;
            } catch (IOException reconnectEx) {
                // reconnect() assigns a new Socket before connect(), so on
                // failure the field points to an unconnected socket. Null it
                // out so the next execute() call fails fast with a clear NPE
                // / IOException rather than silently reusing a broken socket.
                socket = null;
                logger.error(
                        "HBase shell daemon reconnect failed after timeout; "
                                + "socket is now null — subsequent calls will fail",
                        reconnectEx);
            }
            throw new ClusterStuckException(
                    reconnected
                            ? "Command execution timed out; socket reconnected before retry."
                            : "Command execution timed out; reconnect also failed.",
                    e);
        }
    }

    /**
     * Re-establish the TCP connection to the HBase shell daemon at the same
     * host:port.  This resets the client-side byte stream so future commands
     * are not polluted by leftover bytes from a timed-out response.
     *
     * <p><b>Important</b>: this does NOT reset the daemon-side HBase shell
     * process.  If a prior command is still executing asynchronously in the
     * daemon, reconnecting only ensures the client reads fresh responses on
     * the new socket.
     */
    private void reconnect() throws IOException {
        socket = new Socket();
        socket.connect(new InetSocketAddress(ipAddress, port), 30_000);
        socket.setSoTimeout(240_000);
        logger.info("HBase shell daemon reconnected to {}:{}", ipAddress,
                port);
    }

    @Override
    public String executeCommand(String command) throws Exception {
        HBasePacket cp = execute(command);
        String ret = "null message";
        if (cp != null) {
            ret = cp.message + cp.error;
        }
        if (cp != null)
            logger.debug(String.format(
                    "command = {%s}, result = {%s}, error = {%s}, exitValue = {%d}",
                    command, cp.message, cp.error,
                    cp.exitValue));
        return ret;
    }

    @Override
    public ValidationResult executeCommandStructured(String command)
            throws Exception {
        HBasePacket cp = execute(command);
        if (cp == null) {
            return new ValidationResult(command, -1, "", "",
                    "DAEMON_ERROR");
        }
        String failureClass = classifyHBaseFailure(cp);
        return new ValidationResult(command, cp.exitValue,
                cp.message, cp.error, failureClass);
    }

    private static String classifyHBaseFailure(HBasePacket cp) {
        if (cp.exitValue == 0 && cp.error.isEmpty()) {
            return "OK";
        }
        String err = cp.error + cp.message;
        if (err.contains("NoSuchColumnFamilyException"))
            return "NoSuchColumnFamily";
        if (err.contains("TableNotFoundException"))
            return "TableNotFound";
        if (err.contains("TableExistsException"))
            return "TableExists";
        if (err.contains("NamespaceNotFoundException"))
            return "NamespaceNotFound";
        if (err.contains("NamespaceExistException"))
            return "NamespaceExists";
        if (err.contains("TableNotDisabledException"))
            return "TableNotDisabled";
        if (err.contains("TableNotEnabledException"))
            return "TableNotEnabled";
        if (err.contains("RegionException"))
            return "RegionException";
        if (err.contains("ERROR:") || err.contains("ERROR"))
            return "HBaseError";
        if (cp.exitValue != 0)
            return "NonZeroExit";
        return "UNKNOWN";
    }

    public static class HBasePacket {
        public String cmd;
        public int exitValue;
        public double timeUsage;
        public String message;
        public String error;

        public HBasePacket() {
            cmd = "";
            exitValue = 0;
            timeUsage = -1;
            message = "";
            error = "";
        }
    }
}
