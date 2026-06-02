package org.zlab.upfuzz.fuzzingengine;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.zlab.upfuzz.fuzzingengine.executor.Executor;
import org.zlab.upfuzz.fuzzingengine.packet.CandidateArtifactBundle;

public final class CandidateArtifactCollector {
    private static final Logger logger = LogManager
            .getLogger(CandidateArtifactCollector.class);

    private CandidateArtifactCollector() {
    }

    public static CandidateArtifactBundle capture(Executor executor,
            String laneName) {
        if (Config.getConf() == null
                || !Config.getConf().preserveCandidateArtifacts) {
            return null;
        }
        if (executor == null || executor.dockerCluster == null
                || executor.dockerCluster.workdir == null) {
            return null;
        }

        File workdirFile = executor.dockerCluster.workdir;
        Path workdir = workdirFile.toPath();
        CandidateArtifactBundle bundle = new CandidateArtifactBundle(laneName,
                workdirFile.getAbsolutePath());
        if (!Files.isDirectory(workdir)) {
            bundle.addNote("workdir is missing or not a directory");
            return bundle;
        }

        List<Path> candidates = new ArrayList<>();
        try (Stream<Path> stream = Files.walk(workdir)) {
            stream.filter(Files::isRegularFile)
                    .filter(path -> shouldCapture(workdir, path))
                    .forEach(candidates::add);
        } catch (IOException e) {
            bundle.addNote("failed to scan workdir: " + e);
            return bundle;
        }

        Collections.sort(candidates,
                Comparator.comparingInt((Path path) -> capturePriority(workdir,
                        path))
                        .thenComparing(path -> toRelativePath(workdir, path)));

        int maxFiles = Math.max(0, Config.getConf().candidateArtifactMaxFiles);
        long maxPerFile = Math.max(0L,
                Config.getConf().candidateArtifactMaxBytesPerFile);
        long maxTotal = Math.max(0L,
                Config.getConf().candidateArtifactMaxTotalBytes);
        long capturedTotal = 0L;
        int capturedFiles = 0;
        for (Path path : candidates) {
            if (capturedFiles >= maxFiles) {
                bundle.fileLimitReached = true;
                break;
            }
            if (capturedTotal >= maxTotal) {
                bundle.byteLimitReached = true;
                break;
            }
            File file = path.toFile();
            long originalSize = file.length();
            long byteBudget = Math.min(maxPerFile, maxTotal - capturedTotal);
            if (byteBudget <= 0L) {
                bundle.byteLimitReached = true;
                break;
            }
            try {
                byte[] content = readTail(file, byteBudget);
                boolean truncated = originalSize > content.length;
                bundle.addFile(new CandidateArtifactBundle.FileSnippet(
                        toRelativePath(workdir, path), originalSize, content,
                        truncated));
                capturedTotal += content.length;
                capturedFiles++;
            } catch (IOException e) {
                bundle.addNote("failed to capture "
                        + toRelativePath(workdir, path) + ": " + e);
                logger.warn("failed to capture candidate artifact {}: {}",
                        path, e.toString());
            }
        }
        if (candidates.size() > capturedFiles) {
            bundle.addNote("selected " + candidates.size()
                    + " candidate files, captured " + capturedFiles
                    + " within configured limits");
        }
        return bundle;
    }

    private static boolean shouldCapture(Path root, Path file) {
        String rel = toRelativePath(root, file).toLowerCase();
        String name = file.getFileName().toString().toLowerCase();
        if (isLikelyDataFile(rel)) {
            return false;
        }
        if (name.equals("docker-compose.yaml")
                || name.equals("docker-compose.yml")
                || name.equals(".env")
                || name.endsWith(".env")) {
            return true;
        }
        if (rel.contains("/log/") || rel.contains("/logs/")
                || rel.contains("/consolelog/")) {
            return true;
        }
        if (rel.contains("trace") && isTextLike(name)) {
            return true;
        }
        return isTextLike(name)
                && (name.endsWith(".log") || name.endsWith(".out")
                        || name.endsWith(".err")
                        || name.startsWith("gc.log")
                        || name.contains("stdout")
                        || name.contains("stderr")
                        || name.equals("system.log")
                        || name.equals("debug.log"));
    }

    private static int capturePriority(Path root, Path file) {
        String rel = toRelativePath(root, file).toLowerCase();
        String name = file.getFileName().toString().toLowerCase();
        if (name.equals("docker-compose.yaml")
                || name.equals("docker-compose.yml")
                || name.equals(".env")
                || name.endsWith(".env")) {
            return 0;
        }
        if (name.equals("system.log")
                || name.equals("debug.log")
                || name.contains("master")
                || name.contains("regionserver")
                || name.contains("namenode")
                || name.contains("datanode")
                || name.contains("secondarynamenode")) {
            return 1;
        }
        if (rel.contains("/consolelog/")
                || name.contains("stdout")
                || name.contains("stderr")
                || name.endsWith(".out")
                || name.endsWith(".err")) {
            return 2;
        }
        if (rel.contains("trace")) {
            return 3;
        }
        if (name.startsWith("gc.log")) {
            return 5;
        }
        return 4;
    }

    private static boolean isLikelyDataFile(String rel) {
        return rel.contains("/data/")
                || rel.contains("/commitlog/")
                || rel.contains("/saved_caches/")
                || rel.contains("/hints/")
                || rel.contains("/snapshots/")
                || rel.endsWith(".db")
                || rel.endsWith(".crc")
                || rel.endsWith(".idx")
                || rel.endsWith(".sstable");
    }

    private static boolean isTextLike(String name) {
        return !(name.endsWith(".jar")
                || name.endsWith(".zip")
                || name.endsWith(".tar")
                || name.endsWith(".gz")
                || name.endsWith(".tgz")
                || name.endsWith(".class")
                || name.endsWith(".db"));
    }

    private static byte[] readTail(File file, long maxBytes)
            throws IOException {
        long size = file.length();
        long bytesToRead = Math.min(size, maxBytes);
        if (bytesToRead > Integer.MAX_VALUE) {
            bytesToRead = Integer.MAX_VALUE;
        }
        byte[] buffer = new byte[(int) bytesToRead];
        try (RandomAccessFile raf = new RandomAccessFile(file, "r")) {
            raf.seek(Math.max(0L, size - bytesToRead));
            raf.readFully(buffer);
        }
        return buffer;
    }

    private static String toRelativePath(Path root, Path file) {
        return root.relativize(file).toString().replace(File.separatorChar,
                '/');
    }
}
