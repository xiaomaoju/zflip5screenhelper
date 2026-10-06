package io.github.flipcover.controls;

import android.os.SystemClock;
import java.util.ArrayDeque;
import java.util.function.LongSupplier;

/** Bounded process-only technical events; never accepts app identities or Intent contents. */
final class AppLaunchDiagnostics {
    static final int CAPACITY = 96, LINE_LIMIT = 256;
    private final ArrayDeque<String> events = new ArrayDeque<>();
    private final LongSupplier clock;
    private long sequence;
    AppLaunchDiagnostics() { this(SystemClock::elapsedRealtime); }
    AppLaunchDiagnostics(LongSupplier clock) { this.clock = clock; }
    synchronized long begin(String source) { long request = ++sequence; event(request, "begin source=" + source); return request; }
    synchronized void event(long request, String state) {
        String line = clock.getAsLong() + "ms #" + request + " " + state.replace('\n', ' ').replace('\r', ' ');
        if (line.length() > LINE_LIMIT) line = line.substring(0, LINE_LIMIT);
        if (events.size() == CAPACITY) events.removeFirst();
        events.addLast(line);
    }
    synchronized String report() {
        return "应用启动诊断（本次进程，开机后毫秒；最多96条，不含应用名称）\n" + (events.isEmpty() ? "暂无启动记录" : String.join("\n", events)) + "\n";
    }
}
