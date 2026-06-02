package org.zlab.upfuzz.fuzzingengine.packet;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;

/**
 * Bounded client-side artifacts captured before executor teardown. The server
 * writes these into a candidate failure directory only when the round is
 * classified as a rolling-upgrade bug candidate.
 */
public class CandidateArtifactBundle implements Serializable {
    private static final long serialVersionUID = 20260602L;

    public String laneName = "";
    public String workdirPath = "";
    public long capturedAtMillis;
    public long totalOriginalBytes;
    public long totalCapturedBytes;
    public boolean fileLimitReached;
    public boolean byteLimitReached;
    public final List<String> notes = new ArrayList<>();
    public final List<FileSnippet> files = new ArrayList<>();

    public static class FileSnippet implements Serializable {
        private static final long serialVersionUID = 20260602L;

        public String relativePath;
        public long originalSize;
        public long capturedBytes;
        public boolean tailTruncated;
        public byte[] content;

        public FileSnippet(String relativePath, long originalSize,
                byte[] content, boolean tailTruncated) {
            this.relativePath = relativePath;
            this.originalSize = originalSize;
            this.content = content;
            this.capturedBytes = content == null ? 0 : content.length;
            this.tailTruncated = tailTruncated;
        }
    }

    public CandidateArtifactBundle(String laneName, String workdirPath) {
        this.laneName = laneName == null ? "" : laneName;
        this.workdirPath = workdirPath == null ? "" : workdirPath;
        this.capturedAtMillis = System.currentTimeMillis();
    }

    public void addNote(String note) {
        if (note != null && !note.isEmpty()) {
            notes.add(note);
        }
    }

    public void addFile(FileSnippet file) {
        if (file == null) {
            return;
        }
        files.add(file);
        totalOriginalBytes += file.originalSize;
        totalCapturedBytes += file.capturedBytes;
    }
}
