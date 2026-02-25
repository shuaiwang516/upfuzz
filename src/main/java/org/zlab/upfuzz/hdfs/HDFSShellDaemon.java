package org.zlab.upfuzz.hdfs;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.zlab.net.tracker.Trace;
import org.zlab.upfuzz.docker.Docker;
import org.zlab.upfuzz.fuzzingengine.Config;
import org.zlab.upfuzz.fuzzingengine.ShellDaemon;
import org.zlab.upfuzz.utils.Utilities;

import java.io.*;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.Base64;

public class HDFSShellDaemon extends ShellDaemon {
    static Logger logger = LogManager.getLogger(HDFSShellDaemon.class);
    private static final int SOCKET_CONNECT_TIMEOUT_MS = 3 * 1000;
    private static final int SOCKET_READ_TIMEOUT_MS = 30 * 1000;

    private Socket socket;

    public HDFSShellDaemon(String ipAddress, int port, String executorID,
            Docker docker) {
        int retry = 20;
        logger.info("[HKLOG] executor ID = " + executorID + "  "
                + "Connect to hdfs shell daemon:" + ipAddress + "...");
        for (int i = 0; i < retry; ++i) {
            try {
                logger.debug("[HKLOG] executor ID = " + executorID + "  "
                        + "Connect to hdfs shell:" + ipAddress + "..." + i);
                socket = new Socket();
                socket.connect(new InetSocketAddress(ipAddress, port),
                        SOCKET_CONNECT_TIMEOUT_MS);
                socket.setSoTimeout(SOCKET_READ_TIMEOUT_MS);
                logger.info("[HKLOG] executor ID = " + executorID + "  "
                        + "hdfs shell connected: " + ipAddress);
                return;
            } catch (Exception ignored) {
            }
            try {
                Thread.sleep(10 * 1000);
            } catch (InterruptedException ignored) {
            }

            // try {
            // Process grepProc = docker.runInContainer(new String[] {
            // "/bin/sh", "-c",
            // "ps -ef | grep org.apache.hadoop.hdfs.server | wc -l"
            // });
            // String result = new String(
            // grepProc.getInputStream().readAllBytes()).strip();
            // int processNum = Integer.parseInt(result);
            // logger.debug("[HKLOG] processNum = " + processNum);
            // if (Integer.parseInt(result) <= 2) {
            // // Process has died
            // break;
            // }

            // } catch (Exception e) {
            // e.printStackTrace();
            // }
        }
        throw new RuntimeException("[HKLOG] executor ID = " + executorID
                + "  " + "cannot connect to hdfs shell at " + ipAddress);
    }

    public HdfsPacket execute(String cmd)
            throws IOException {
        if (socket == null || socket.isClosed()) {
            throw new IOException("hdfs shell socket is not connected");
        }
        DataInputStream in = new DataInputStream(socket.getInputStream());
        DataOutputStream out = new DataOutputStream(socket.getOutputStream());
        out.writeInt(cmd.getBytes().length);
        out.write(cmd.getBytes());
        out.flush();

        int packetLength = in.readInt();
        if (Config.getConf().debug) {
            logger.info("hdfs daemon ret len = " + packetLength);
        }
        byte[] bytes = new byte[packetLength];
        in.readFully(bytes);
        String hdfsMessage = new String(bytes);

        Gson gson = new GsonBuilder()
                .setLenient()
                .create();

        // logger.info("hdfs Message: " + hdfsMessage);
        HdfsPacket hdfsPacket = null;
        try {
            hdfsPacket = gson.fromJson(hdfsMessage,
                    HdfsPacket.class);
            String normalizedCmd = cmd == null ? "" : cmd.trim().toLowerCase();
            boolean keepRawMessage = normalizedCmd.equals("collecttrace")
                    || normalizedCmd.equals("cleartrace");
            if (!keepRawMessage && hdfsPacket.message != null) {
                hdfsPacket.message = Utilities.decodeString(hdfsPacket.message)
                        .replace("\0", "");
            }
        } catch (Exception e) {
            e.printStackTrace();
            logger.error("ERROR: Cannot read from json\n WRONG_HDFS MESSAGE: "
                    + hdfsMessage);
        }
        if (Config.getConf().debug) {
            logger.debug(
                    "HdfsMessage:\n" +
                            new GsonBuilder().setPrettyPrinting().create()
                                    .toJson(hdfsPacket));
        }
        return hdfsPacket;
    }

    @Override
    public String executeCommand(String command) throws Exception {
        HdfsPacket cp = execute(command);
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

    public Trace collectTrace() throws Exception {
        HdfsPacket cp = execute("collecttrace");
        if (cp == null) {
            throw new RuntimeException("collecttrace returned null packet");
        }
        if (cp.exitValue != 0) {
            throw new RuntimeException(
                    "collecttrace failed: " + cp.error);
        }
        if (cp.message == null || cp.message.isEmpty()) {
            return new Trace();
        }

        byte[] traceBytes = Base64.getDecoder().decode(cp.message);
        try (ObjectInputStream objectInputStream = new ObjectInputStream(
                new ByteArrayInputStream(traceBytes))) {
            Trace trace = (Trace) objectInputStream.readObject();
            try {
                execute("cleartrace");
            } catch (Exception ignored) {
            }
            return trace;
        }
    }

    public static class HdfsPacket {
        public String cmd;
        public int exitValue;
        public String message;
        public String error;
        public double timeUsage;

        public HdfsPacket() {
            cmd = "";
            exitValue = 0;
            message = "";
            error = "";
            timeUsage = -1;
        }
    }
}
