package org.zlab.upfuzz.docker;

import org.zlab.net.tracker.Trace;
import org.zlab.ocov.tracker.ObjectGraphCoverage;
import org.zlab.upfuzz.fuzzingengine.Config;
import org.zlab.upfuzz.fuzzingengine.packet.ValidationResult;
import org.zlab.upfuzz.utils.Utilities;

import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.PrintWriter;
import java.net.Socket;
import java.util.concurrent.TimeUnit;

public abstract class Docker extends DockerMeta implements IDocker {

    public abstract void chmodDir() throws IOException, InterruptedException;

    public void restart() throws Exception {
        String[] containerRecoverCMD = new String[] {
                "docker", "compose", "restart", serviceName
        };
        Process containerRecoverProcess = Utilities.exec(
                containerRecoverCMD,
                workdir);
        containerRecoverProcess.waitFor();

        // recreate connection
        start();
        logger.info(
                String.format("Node%d restart successfully!", index));
    }

    @Override
    public ObjectGraphCoverage getFormatCoverage() throws Exception {
        // execute check inv command
        Socket socket = new Socket(networkIP,
                Config.instance.formatCoveragePort);

        ObjectInputStream in = new ObjectInputStream(socket.getInputStream());
        PrintWriter out = new PrintWriter(socket.getOutputStream(), true);
        out.println("collect format coverage"); // send a command to the server

        ObjectGraphCoverage response = (ObjectGraphCoverage) in.readObject();
        logger.debug(
                "Received format coverage dump Id size: "
                        + response.dumpId2ObjCoverageWithContext.keySet()
                                .size());
        // clean up resources
        out.close();
        in.close();
        socket.close();
        return response;
    }

    @Override
    public void clearFormatCoverage() throws Exception {
        // execute check inv command
        Socket socket = new Socket(networkIP,
                Config.instance.formatCoveragePort);

        ObjectInputStream in = new ObjectInputStream(socket.getInputStream());
        PrintWriter out = new PrintWriter(socket.getOutputStream(), true);
        out.println("clear"); // send a command to the server
        logger.debug("clear format coverage");
        // clean up resources
        out.close();
        in.close();
        socket.close();
    }

    @Override
    public Trace collectTrace() throws Exception {
        final int maxAttempts = 15;
        final long retrySleepMillis = 1000L;
        Exception lastException = null;

        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            try (Socket socket = new Socket(networkIP,
                    Config.instance.formatCoveragePort);
                    ObjectInputStream in = new ObjectInputStream(
                            socket.getInputStream());
                    PrintWriter out = new PrintWriter(
                            socket.getOutputStream(), true)) {
                out.println("collect trace"); // send a command to the server
                Trace response = (Trace) in.readObject();
                logger.debug("Received trace = " + response);
                return response;
            } catch (IOException e) {
                lastException = e;
                if (attempt == maxAttempts) {
                    throw e;
                }
                logger.debug(String.format(
                        "Trace daemon not ready on node[%d], attempt %d/%d",
                        index, attempt, maxAttempts));
                try {
                    Thread.sleep(retrySleepMillis);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    throw new IOException(
                            "Interrupted while retrying trace collection", ie);
                }
            }
        }

        throw lastException == null
                ? new IOException("Unknown error while collecting trace")
                : lastException;
    }

    @Override
    public String execCommand(String command) throws Exception {
        long startTime = System.currentTimeMillis();
        assert shell != null;
        String ret = shell.executeCommand(command);
        long endTime = System.currentTimeMillis();

        long timeElapsed = TimeUnit.SECONDS.convert(
                endTime - startTime, TimeUnit.MILLISECONDS);

        if (Config.getConf().debug) {
            logger.debug(String.format(
                    "Command is sent to node[%d], exec time: %dms",
                    index, timeElapsed));
        }
        return ret;
    }

    public ValidationResult execCommandStructured(String command)
            throws Exception {
        assert shell != null;
        return shell.executeCommandStructured(command);
    }
}
