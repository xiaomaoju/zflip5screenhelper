package io.github.flipcover.controls;

import java.util.Set;

/** Manual choice lasts for the current foreground app, including all of its pages. */
final class DockVisibility {
    private String foreground = "";
    private Boolean manual;
    private boolean keyboard;
    private Boolean keyboardManual;
    private Set<String> matchedApplications;
    private String matchedForeground;
    private boolean automaticCompact;
    void foreground(String packageName) {
        if (packageName == null || packageName.isEmpty() || packageName.equals(foreground)) return;
        foreground = packageName; manual = null; keyboardManual = null;
    }
    void keyboard(boolean visible) { if (keyboard != visible) { keyboard = visible; keyboardManual = null; } }
    boolean needsForeground() { return manual != null || keyboardManual != null; }
    void rulesChanged() { matchedApplications = null; matchedForeground = null; }
    /** Application rules are an immutable configuration snapshot, replaced on changes. */
    boolean compact(boolean automatic, Set<String> compactApps) {
        if (keyboard) return keyboardManual == null || keyboardManual;
        if (manual != null) return manual;
        if (!automatic || compactApps.isEmpty()) return false;
        if (matchedApplications != compactApps || matchedForeground != foreground) {
            matchedApplications = compactApps; matchedForeground = foreground;
            automaticCompact = foreground.isEmpty() || compactApps.contains(foreground);
        }
        return automaticCompact;
    }
    void toggle(boolean automatic, Set<String> compactApps) { boolean next = !compact(automatic, compactApps); if (keyboard) keyboardManual = next; else manual = next; }
    void reset() { foreground = ""; manual = null; keyboard = false; keyboardManual = null; rulesChanged(); }
}
