package io.github.flipcover.controls;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.drawable.Drawable;
import android.graphics.Paint;
import android.view.View;

/** Original local diagrams; never screen captures or assertions about live device state. */
final class SettingsIllustration extends View {
    static final int CHROME = 0, GESTURES = 1, ROTATION = 2, SAFE_AREA = 3;
    private final int kind;
    private final Prefs prefs;
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final StatusBarView.ClockDrawable chromeClock = new StatusBarView.ClockDrawable();
    private final ChromeShadowDrawable chromeShadow = ChromeShadowDrawable.forIcon(chromeClock, 1.8f);
    private int orientation = -1;
    SettingsIllustration(Context context, int kind, Prefs prefs) { super(context); this.kind = kind; this.prefs = prefs; setContentDescription(kind == ROTATION ? "方向示意，不立即旋转应用" : kind == CHROME ? "明暗背景下的悬浮栏风格示意" : "安全区域示意，以系统实际缺口为准"); if (kind == CHROME) setLayerType(LAYER_TYPE_SOFTWARE, null); if (kind == GESTURES) refreshGestures(); setMinimumHeight(Ui.dp(context, 112)); }
    static String entryLabel(String position) { return switch (position) { case "top_right" -> "右顶部 · 下滑 ↓"; case "bottom_right" -> "右底部 · 上滑 ↑"; default -> "左顶部 · 下滑 ↓"; }; }
    void refreshGestures() {
        StringBuilder description = new StringBuilder("白条位置与面板展开方向预览；");
        for (int angle = 0; angle < 4; angle++) description.append(angle * 90).append("°：").append(entryLabel(prefs.entryPosition(angle))).append("；");
        setContentDescription(description.toString()); invalidate();
    }
    private void drawEntryPreview(Canvas canvas, int angle, float centerX, float availableWidth) {
        int width = angle % 2 == 0 ? 748 : 720, height = angle % 2 == 0 ? 720 : 748;
        DockGeometry.Box[] cameras = {new DockGeometry.Box(379, 654, 369, 66), new DockGeometry.Box(654, 0, 66, 369), new DockGeometry.Box(0, 0, 369, 66), new DockGeometry.Box(0, 379, 66, 369)};
        java.util.List<DockGeometry.Box> cuts = java.util.List.of(cameras[angle]);
        DockGeometry.Placement dock = DockGeometry.resolve(width, height, cuts, 2.125f, (angle + 3) % 4, .46f, .088f, true);
        int topInset = DockGeometry.panelEntryTopInset(prefs.entryPosition(angle), prefs.statusScale(), 2.125f);
        DockGeometry.Placement entry = DockGeometry.panelEntry(dock, dock, width, height, cuts, 2.125f, 24, prefs.entryPosition(angle), topInset);
        float scale = Math.min(Math.max(20, availableWidth) / width, 68f / height);
        canvas.save(); canvas.translate(centerX - width * scale / 2, 10); canvas.scale(scale, scale);
        rect(canvas, 0, 0, width, height, 48, 0xFF535862); rect(canvas, 9, 9, width - 9, height - 9, 42, 0xFF242935);
        DockGeometry.Box cut = cameras[angle], band = entry.touch();
        boolean top = entry.edge() == DockGeometry.TOP;
        rect(canvas, 20, top ? band.bottom() : height / 2f, width - 20, top ? height / 2f : band.y(), 24, 0xFF294A80);
        rect(canvas, cut.x(), cut.y(), cut.right(), cut.bottom(), 18, 0xFF535862);
        DockGeometry.Chrome chrome = DockGeometry.panelEntryChrome(entry, 2.125f);
        for (DockGeometry.Box bar : new DockGeometry.Box[]{chrome.firstHandle(), chrome.secondHandle()}) {
            float y = band.y() + bar.y(); rect(canvas, band.x() + bar.x(), y, band.x() + bar.right(), y + Math.max(bar.height(), 1.5f / scale), 1 / scale, SettingsUi.ACCENT);
        }
        float arrowX = band.x() + band.width() / 2f, direction = top ? 1 : -1;
        float arrowStart = top ? band.bottom() + 4 / scale : band.y() - 4 / scale, arrowEnd = arrowStart + direction * 13 / scale;
        paint.setColor(SettingsUi.ACCENT); paint.setStrokeWidth(1.5f / scale);
        canvas.drawLine(arrowX, arrowStart, arrowX, arrowEnd, paint);
        canvas.drawLine(arrowX, arrowEnd, arrowX - 3 / scale, arrowEnd - direction * 4 / scale, paint); canvas.drawLine(arrowX, arrowEnd, arrowX + 3 / scale, arrowEnd - direction * 4 / scale, paint);
        canvas.restore(); paint.setColor(SettingsUi.TEXT); paint.setTextSize(12 * getResources().getConfiguration().fontScale); paint.setTextAlign(Paint.Align.CENTER);
        canvas.drawText(angle * 90 + "°", centerX, 98, paint); paint.setTextAlign(Paint.Align.LEFT);
    }
    void setOrientation(int value) { orientation = value; invalidate(); }
    @Override protected void onMeasure(int widthSpec, int heightSpec) { setMeasuredDimension(MeasureSpec.getSize(widthSpec), Ui.dp(getContext(), 112)); }
    private void rect(Canvas c, float l, float t, float r, float b, float radius, int color) { paint.setColor(color); paint.setStyle(Paint.Style.FILL); c.drawRoundRect(l, t, r, b, radius, radius, paint); }
    @Override protected void onDraw(Canvas canvas) {
        float s = getHeight() / 112f, width = getWidth() / s; canvas.save(); canvas.scale(s, s);
        if (kind == GESTURES) {
            for (int angle = 0; angle < 4; angle++) drawEntryPreview(canvas, angle, width * (angle + .5f) / 4, width / 4 - 12);
        } else if (kind == CHROME) {
            rect(canvas, 14, 16, width / 2 - 4, 96, 14, 0xFF30343C); rect(canvas, width / 2 + 4, 16, width - 14, 96, 14, 0xFFE9EDF3);
            for (int side = 0; side < 2; side++) {
                float x = side == 0 ? 28 : width / 2 + 18; if (prefs.chromeStyle().equals("black")) rect(canvas, x - 5, 44, x + width / 2 - 44, 73, 8, 0xFF000000);
                if (chromeClock.update("12:45  ▪", StatusBarView.CLOCK_SIZE_DP * 1.8f, Ui.chromeColor(prefs), 64)) chromeShadow.invalidateShadow();
                Drawable text = prefs.chromeStyle().equals("contrast") ? chromeShadow : chromeClock;
                text.setBounds(Math.round(x), 0, Math.round(x) + (int) Math.ceil(chromeClock.advance()), 112); text.draw(canvas);
            }
        } else {
            canvas.save(); canvas.translate(width / 2, 51); if (kind == ROTATION && orientation >= 0 && orientation < 4) canvas.rotate(orientation * 90);
            rect(canvas, -32, -38, 32, 38, 10, 0xFF535862); rect(canvas, -28, -34, 28, 34, 7, 0xFF242935); rect(canvas, -20, -20, 20, 14, 5, 0xFF294A80);
            if (kind == SAFE_AREA) { paint.setColor(SettingsUi.ACCENT); paint.setStyle(Paint.Style.STROKE); paint.setStrokeWidth(2); canvas.drawRoundRect(-23, -28, 23, 28, 5, 5, paint); paint.setStyle(Paint.Style.FILL); }
            rect(canvas, 0, 25, 28, 34, 3, 0xFF535862); rect(canvas, 1, 21, 11, 23, 1, SettingsUi.ACCENT); rect(canvas, 15, 21, 25, 23, 1, SettingsUi.ACCENT); canvas.restore();
        }
        canvas.restore();
    }
}
