package io.github.flipcover.controls;

import java.util.Set;

/** Own status overlay only; a short window transition never restarts its deadline. */
final class StatusAppVisibility {
    static final long SETTLE_MILLIS = 500;
    private Set<String> applications = Set.of();
    private String foreground = "";
    private long unresolvedUntil = -1;

    void applications(Set<String> selected) {
        applications = Set.copyOf(selected);
        if (applications.isEmpty()) reset();
    }
    boolean enabled() { return !applications.isEmpty(); }
    void observed(String packageName, long now) {
        if (!enabled()) return;
        if (packageName != null && !packageName.isEmpty()) {
            foreground = packageName; unresolvedUntil = -1;
        } else if (!hidden()) reset();
        else if (unresolvedUntil < 0) unresolvedUntil = now + SETTLE_MILLIS;
        settle(now);
    }
    void settle(long now) { if (unresolvedUntil >= 0 && now >= unresolvedUntil) reset(); }
    long remaining(long now) { return unresolvedUntil < 0 ? 0 : Math.max(0, unresolvedUntil - now); }
    boolean hidden() { return enabled() && applications.contains(foreground); }
    void reset() { foreground = ""; unresolvedUntil = -1; }
}
