package org.zlab.upfuzz.fuzzingengine.trace;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;

/**
 * Ordered list of TraceWindows for one lane in one round.
 */
public class WindowedTrace implements Serializable {
    private static final long serialVersionUID = 20260406L;

    private final List<TraceWindow> windows = new ArrayList<>();

    public void addWindow(TraceWindow window) {
        windows.add(window);
    }

    public List<TraceWindow> getWindows() {
        return windows;
    }

    public List<TraceWindow> getComparableWindows() {
        List<TraceWindow> result = new ArrayList<>();
        for (TraceWindow w : windows) {
            if (w.comparableAcrossLanes)
                result.add(w);
        }
        return result;
    }

    public int size() {
        return windows.size();
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder("WindowedTrace{");
        for (int i = 0; i < windows.size(); i++) {
            if (i > 0)
                sb.append(", ");
            sb.append(windows.get(i));
        }
        sb.append("}");
        return sb.toString();
    }
}
