package io.github.flipcover.controls;

import org.junit.Test;
import static org.junit.Assert.*;

public class AppLaunchDiagnosticsTest {
    @Test public void requestsStayCorrelatedAndTheOldestEventsAreEvicted() {
        AppLaunchDiagnostics log = new AppLaunchDiagnostics(() -> 1234);
        long first = log.begin("native_card"), second = log.begin("floating");
        log.event(first, "rejected reason=display_changed");
        log.event(second, "start_activity returned visibility=unconfirmed");
        assertTrue(log.report().contains("1234ms #1 rejected reason=display_changed"));
        assertTrue(log.report().contains("1234ms #2 start_activity returned visibility=unconfirmed"));
        for (int i = 0; i < 2000; i++) log.event(second, "event=" + i);
        String report = log.report();
        assertFalse(report.contains("source=native_card"));
        assertFalse(report.contains("event=1903\n"));
        assertTrue(report.contains("event=1904\n"));
        assertTrue(report.contains("event=1999\n"));
        assertEquals(AppLaunchDiagnostics.CAPACITY, report.lines().filter(line -> line.startsWith("1234ms")).count());
    }
    @Test public void eachEventIsBoundedAndCannotInjectAnotherLine() {
        AppLaunchDiagnostics log = new AppLaunchDiagnostics(() -> 0);
        log.event(1, "a\nb\rc" + "x".repeat(1000));
        String[] lines = log.report().split("\n");
        assertEquals(2, lines.length);
        assertEquals(AppLaunchDiagnostics.LINE_LIMIT, lines[1].length());
        assertTrue(lines[1].startsWith("0ms #1 a b c"));
        assertTrue(new AppLaunchDiagnostics(() -> 0).report().contains("暂无启动记录"));
    }
}
