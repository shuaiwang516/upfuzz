package org.zlab.upfuzz.cassandra;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import java.io.*;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.LinkedList;
import java.util.List;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.zlab.upfuzz.docker.Docker;
import org.zlab.upfuzz.fuzzingengine.ShellDaemon;
import org.zlab.upfuzz.utils.Utilities;
import org.zlab.upfuzz.fuzzingengine.Config;

public class CassandraCqlshDaemon extends ShellDaemon {
    static Logger logger = LogManager.getLogger(CassandraCqlshDaemon.class);
    private Socket socket;
    public static String cqlshPython2Script;
    public static String cqlshPython3Script;

    public static List<String> noiseErrors = new LinkedList<>();
    static {
        noiseErrors.add("Bootstrap Token collision");
    }

    static {
        InputStream cqlsh_daemon2 = CassandraCqlshDaemon.class.getClassLoader()
                .getResourceAsStream(
                        "cqlsh_daemon2.py");
        InputStream cqlsh_daemon3 = CassandraCqlshDaemon.class.getClassLoader()
                .getResourceAsStream(
                        "cqlsh_daemon3.py");
        if (cqlsh_daemon2 == null) {
            System.err.println("cannot find cqlsh_daemon.py");
        }
        byte[] bytes = new byte[65536];
        int cnt;
        try {
            cnt = cqlsh_daemon2.read(bytes);
            cqlshPython2Script = new String(bytes, 0, cnt);
            cnt = cqlsh_daemon3.read(bytes);
            cqlshPython3Script = new String(bytes, 0, cnt);
        } catch (IOException e) {
            e.printStackTrace();
            System.exit(1);
        }
    }

    public CassandraCqlshDaemon(String ipAddress, int port, Docker docker) {
        int SLEEP_INTERVAL = 1;
        int retry = Config.getConf().CASSANDRA_RETRY_TIMEOUT / SLEEP_INTERVAL;
        logger.info("[HKLOG] executor ID = " + docker.executorID + "  "
                + "Connect to cqlsh:" + ipAddress + "...");
        Long totalReadTimeFromProcess = 0L;
        Long totalProcExecTime = 0L;
        for (int i = 0; i < retry; ++i) {
            try {
                if (i % 10 == 0) {
                    logger.debug("[HKLOG] executor ID = " + docker.executorID
                            + "  "
                            + "Connect to cqlsh:" + ipAddress + "..." + i);
                    socket = new Socket();
                    socket.connect(new InetSocketAddress(ipAddress, port),
                            3 * 1000);
                    logger.info(
                            "[HKLOG] executor ID = " + docker.executorID + "  "
                                    + "Cqlsh connected: " + ipAddress);
                    if (Config.getConf().debug) {
                        logger.debug(
                                "[CassandraCqlshDaemon] Needed total proc exec time "
                                        + totalProcExecTime + " ms"
                                        + " and total read time "
                                        + totalReadTimeFromProcess + " ms");
                    }
                    totalReadTimeFromProcess = 0L;
                    totalProcExecTime = 0L;
                    return;
                }
            } catch (Exception ignored) {
            }
            try {
                Thread.sleep(SLEEP_INTERVAL * 1000);
            } catch (InterruptedException ignored) {
            }

            // After WAIT_INTERVAL, the process should have started
            if (i * SLEEP_INTERVAL >= Config.getConf().WAIT_INTERVAL) {
                try {
                    Long curTime = System.currentTimeMillis();
                    Process grepProc = docker.runInContainer(new String[] {
                            "/bin/sh", "-c",
                            "ps -ef | grep org.apache.cassandra.service.CassandraDaemon | wc -l"
                    });
                    totalProcExecTime += System.currentTimeMillis() - curTime;
                    curTime = System.currentTimeMillis();
                    String result = new String(
                            grepProc.getInputStream().readAllBytes()).strip();
                    totalReadTimeFromProcess += System.currentTimeMillis()
                            - curTime;

                    // TODO: print logs to terminal...
                    // if (Config.getConf().debug) {
                    // logger.debug(
                    // String.format(
                    // "[CassandraCqlshDaemon] read the bytes in %d ms",
                    // System.currentTimeMillis() - curTime));
                    // Process grepProc2 = docker.runInContainer(new String[] {
                    // "/bin/sh", "-c",
                    // "cat /var/log/supervisor/cassandra-stderr*"
                    // });
                    // String result2 = new String(
                    // grepProc2.getInputStream().readAllBytes()).strip();
                    // System.err.println("grep check result2 = " + result2);
                    // }

                    if (Config.getConf().cassandraEnableTimeoutCheck) {
                        int processNum = Integer.parseInt(result);
                        if (Integer.parseInt(result) <= 2) {
                            // Process has died
                            logger.debug("result = " + result);
                            logger.debug("[HKLOG] processNum = " + processNum
                                    + " smaller than 2, "
                                    + "system process died");
                            break;
                        }
                    }

                } catch (Exception e) {
                    e.printStackTrace();
                }
            }

        }
        throw new RuntimeException("[HKLOG] executor ID = " + docker.executorID
                + "  " + "cannot connect to cqlsh at " + ipAddress);
    }

    public CqlshPacket execute(String cmd) throws Exception {
        // Convert the command string to bytes to accurately measure its length
        Utilities.serializeSingleCommand(cmd, socket.getOutputStream());
        String cqlshMessage = Utilities.deserializeSingleCommandResult(
                new DataInputStream(socket.getInputStream()));

        // Convert JSON string back to object
        Gson gson = new GsonBuilder().setLenient().create();
        CqlshPacket cqlshPacket = gson.fromJson(cqlshMessage,
                CqlshPacket.class);

        // Assert: the cmd must be equal
        if (!cqlshPacket.cmd.equals(cmd)) {
            logger.error("cmd size = " + cmd.length()
                    + ", cqlshPacket.cmd size = " + cqlshPacket.cmd.length());
            logger.error("cmd = " + cmd);
            logger.error("cqlshPacket.cmd = " + cqlshPacket.cmd);
            throw new RuntimeException(
                    "cqlshPacket.cmd not equal to cmd: " + cqlshPacket.cmd
                            + " != "
                            + cmd);
        }

        cqlshPacket.message = Utilities.decodeString(cqlshPacket.message)
                .replace("\0", "");
        cqlshPacket.error = Utilities.decodeString(cqlshPacket.error)
                .replace("\0", "");
        if (Config.getConf().debug) {
            logger.debug("[CqlshDaemon] cqlsh message after decode: "
                    + cqlshPacket.message);
        }

        // logger.info("value size = " + cqlshPacket.message.length());

        // logger.info(
        // "CqlshMessage:\n" +
        // new GsonBuilder().setPrettyPrinting().create()
        // .toJson(cqlshPacket));
        return cqlshPacket;
    }

    public static boolean testPortAvailable(int port) {
        Process p;
        try {
            p = Utilities.exec(new String[] { "bin/sh", "-c",
                    "netstat -tunlp | grep -P \":" +
                            port + "[\\s$]\"" },
                    new File("/"));
            int ret = p.waitFor();
            return ret == 1;
        } catch (IOException | InterruptedException e) {
            e.printStackTrace();
        }
        return false;
    }

    @Override
    public String executeCommand(String command) throws Exception {
        CassandraCqlshDaemon.CqlshPacket cp = execute(command);
        String ret = "null message";
        if (cp != null) {
            if (cp.message.isEmpty())
                ret = cp.error;
            else
                ret = cp.message;
        }
        if (cp != null)
            if (Config.getConf().debug) {
                logger.debug(String.format(
                        "command = {%s}, result = {%s}, error = {%s}, exitValue = {%d}",
                        command, cp.message, cp.error,
                        cp.exitValue));
            }
        return ret;
    }

    public static class CqlshPacket {
        public String cmd;
        public int exitValue;
        public String message;
        public String error;
        public double timeUsage;

        public CqlshPacket() {
            cmd = "";
            exitValue = 0;
            message = "";
            error = "";
            timeUsage = -1;
        }
    }

    /**
     * Destroy the current process.
     */
    public void destroy() {
    }
}
