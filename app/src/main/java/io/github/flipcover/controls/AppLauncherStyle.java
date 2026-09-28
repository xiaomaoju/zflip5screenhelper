package io.github.flipcover.controls;

/** Design tokens shared by ordinary Views and the native card's RemoteViews. */
final class AppLauncherStyle {
    static final int LABEL_SP = 9, PANEL_RADIUS = 20, FOLDER_RADIUS = 14;
    static final int[] FOLDER_COLORS = {0xFF292E37, 0xFF304858, 0xFF354C42, 0xFF51404B, 0xFF4D4561, 0xFF594C35};
    static int iconSize(String density) { return switch (density) { case "normal" -> 36; case "easy" -> 42; default -> 30; }; }
    static int columns(float widthDp, String density) {
        int count = RecentTasks.columns(widthDp);
        if (density.equals("normal")) count = Math.max(3, count - 1);
        if (density.equals("easy")) count = 3;
        return count;
    }
}
