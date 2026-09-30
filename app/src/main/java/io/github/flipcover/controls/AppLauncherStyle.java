package io.github.flipcover.controls;

/** Shared dp parameters and pixel geometry; both hosts consume these values with fixed fonts. */
final class AppLauncherStyle {
    static final int LABEL_SP = 9;
    static final int GRID_COLUMNS = 5, GRID_ROWS = 3;
    static final int PANEL_PADDING_X = 5, PANEL_PADDING_Y = 2, RAIL_FRAME_PADDING = 0;
    static final int APP_PADDING = 2, APP_LABEL_GAP = 2, APP_MIN_ICON = 12;
    static final int RAIL_WIDTH = 44, RAIL_GAP = 4, RAIL_ICON = 20, RAIL_CELL = 32;
    static final int RAIL_PADDING = 1, RAIL_LABEL_GAP = 1;
    static final float RAIL_LABEL_SP = 6.4f;
    static final int FOLDER_COLUMNS = 3, FOLDER_PADDING = 4, FOLDER_LABEL_GAP = 2;
    static final int FOLDER_MEMBER_ICON = 30, FOLDER_MEMBER_HEIGHT = 52;
    static final float FOLDER_ICON_INSET = 3f / 32;
    static final int DOCK_ROW = 34, DOCK_TOP = 2, DOCK_EDGE = 2, DOCK_TOOL_WIDTH = 24;
    static final int DOCK_MIN_CELL = 28, DOCK_ICON = 28, DOCK_MIN_ICON = 16, DOCK_APPS_ICON = 24, DOCK_CLEAR_ICON = 18, DOCK_SEPARATOR_HEIGHT = 18;
    static final float DOCK_SEPARATOR_WIDTH = .5f, DOCK_SEPARATOR_MARGIN = 1;
    static final int DOCK_COLOR = 0xFF22272F, DOCK_SEPARATOR = 0xFF65717D;
    static final int[] FOLDER_COLORS = {0xFF292E37, 0xFF304858, 0xFF354C42, 0xFF51404B, 0xFF4D4561, 0xFF594C35};
    static android.content.Context fixedFontContext(android.content.Context source) {
        android.view.ContextThemeWrapper context = new android.view.ContextThemeWrapper(source, 0);
        android.content.res.Configuration override = new android.content.res.Configuration(); override.fontScale = 1f;
        context.applyOverrideConfiguration(override); context.getTheme().setTo(source.getTheme()); return context;
    }
    static float panelRadius(android.content.Context context) { return context.getResources().getDimension(R.dimen.launcher_panel_radius) / context.getResources().getDisplayMetrics().density; }
    static float folderRadius(android.content.Context context) { return context.getResources().getDimension(R.dimen.launcher_folder_radius) / context.getResources().getDisplayMetrics().density; }
    static float dockRadius(android.content.Context context) { return context.getResources().getDimension(R.dimen.launcher_dock_radius) / context.getResources().getDisplayMetrics().density; }
    static int iconSize(String density) { return switch (density) { case "normal" -> 36; case "easy" -> 42; default -> 30; }; }
    static int iconSize(String density, float cellWidth) { return Math.max(APP_MIN_ICON, Math.min(iconSize(density), (int) cellWidth - 2 * APP_PADDING)); }
    static int dockIconSize(float cellWidth) { return Math.min(DOCK_ICON, Math.max(DOCK_MIN_ICON, Math.round(cellWidth) - 2 * APP_PADDING)); }
    static int folderMemberIconSize(float cellWidth) { return Math.max(APP_MIN_ICON, Math.min(FOLDER_MEMBER_ICON, (int) cellWidth - 2 * APP_PADDING)); }
    static int dockHeight() { return DOCK_ROW + DOCK_TOP; }
    static int folderSide(int width, int height) { return Math.max(1, Math.min(width, height)); }
    static int cellWidth(int width, int columns) { return Math.max(1, width / columns); }
    static int cellHeight(int height, int rows) { return Math.max(1, height / rows); }
    static android.graphics.Rect gridCell(int width, int height, int columns, int rows, int slot, int span, int page) {
        int capacity = columns * rows, cellWidth = cellWidth(width, columns), cellHeight = cellHeight(height, rows);
        int left = (slot / capacity - page) * width + slot % capacity % columns * cellWidth, top = slot % capacity / columns * cellHeight;
        return new android.graphics.Rect(left, top, left + cellWidth * span, top + cellHeight * span);
    }
    record FolderGeometry(android.graphics.Rect surface, android.graphics.Rect preview, android.graphics.Rect label) { }
    static FolderGeometry folderGeometry(android.content.Context context, int width, int height, int labelHeight) {
        int side = folderSide(width, height), left = (width - side) / 2, top = (height - side) / 2, pad = Ui.dp(context, FOLDER_PADDING), gap = Ui.dp(context, FOLDER_LABEL_GAP);
        int textHeight = Math.min(labelHeight, Math.max(1, side - 2 * pad)), previewSide = Math.max(1, side - 2 * pad - textHeight - gap);
        android.graphics.Rect surface = new android.graphics.Rect(left, top, left + side, top + side);
        android.graphics.Rect preview = new android.graphics.Rect((width - previewSide) / 2, top + pad, (width - previewSide) / 2 + previewSide, top + pad + previewSide);
        android.graphics.Rect label = new android.graphics.Rect(left + pad, top + side - pad - textHeight, left + side - pad, top + side - pad);
        return new FolderGeometry(surface, preview, label);
    }
    static android.graphics.Rect folderIconBounds(int previewSide, int index) {
        float cell = previewSide / (float) FOLDER_COLUMNS; int size = Math.max(1, Math.round(cell * (1 - 2 * FOLDER_ICON_INSET)));
        int left = Math.round((index % FOLDER_COLUMNS + .5f) * cell - size / 2f), top = Math.round((index / FOLDER_COLUMNS + .5f) * cell - size / 2f);
        return new android.graphics.Rect(left, top, left + size, top + size);
    }
}
