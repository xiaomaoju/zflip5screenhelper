package io.github.flipcover.controls;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.view.View;

/** Original local diagrams; never screen captures or assertions about live device state. */
final class SettingsIllustration extends View {
    static final int CHROME = 0, GESTURES = 1, ROTATION = 2, SAFE_AREA = 3;
    private final int kind;
    private final Prefs prefs;
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private int orientation = -1;
    SettingsIllustration(Context context, int kind, Prefs prefs) { super(context); this.kind = kind; this.prefs = prefs; setContentDescription(kind == ROTATION ? "方向示意，不立即旋转应用" : kind == GESTURES ? "触发后的横向双白条上滑示意；平时隐藏，0° 在摄像头上方，其他方向在画面右下方" : kind == CHROME ? "明暗背景下的悬浮栏风格示意" : "安全区域示意，以系统实际缺口为准"); setMinimumHeight(Ui.dp(context, 112)); }
    void setOrientation(int value) { orientation = value; invalidate(); }
    @Override protected void onMeasure(int widthSpec, int heightSpec) { setMeasuredDimension(MeasureSpec.getSize(widthSpec), Ui.dp(getContext(), 112)); }
    private void rect(Canvas c, float l, float t, float r, float b, float radius, int color) { paint.setColor(color); paint.setStyle(Paint.Style.FILL); c.drawRoundRect(l, t, r, b, radius, radius, paint); }
    @Override protected void onDraw(Canvas canvas) {
        float s = getHeight() / 112f, width = getWidth() / s; canvas.save(); canvas.scale(s, s);
        if (kind == CHROME) {
            rect(canvas, 14, 16, width / 2 - 4, 96, 14, 0xFF30343C); rect(canvas, width / 2 + 4, 16, width - 14, 96, 14, 0xFFE9EDF3);
            for (int side = 0; side < 2; side++) { float x = side == 0 ? 28 : width / 2 + 18; if (prefs.chromeStyle().equals("black")) rect(canvas, x - 5, 44, x + width / 2 - 44, 73, 8, 0xFF000000); paint.setColor(prefs.chromeStyle().equals("dark") ? 0xFF202124 : 0xFFF5F5F7); paint.setTextSize(18); if (prefs.chromeStyle().equals("contrast")) { paint.setStyle(Paint.Style.STROKE); paint.setStrokeWidth(2); paint.setColor(0xFF111111); canvas.drawText("12:45  ▪", x, 64, paint); paint.setStyle(Paint.Style.FILL); paint.setColor(0xFFF5F5F7); } canvas.drawText("12:45  ▪", x, 64, paint); }
        } else {
            canvas.save(); canvas.translate(width / 2, 51); if (kind == ROTATION && orientation >= 0 && orientation < 4) canvas.rotate(orientation * 90);
            rect(canvas, -32, -38, 32, 38, 10, 0xFF535862); rect(canvas, -28, -34, 28, 34, 7, 0xFF242935); rect(canvas, -20, -20, 20, 14, 5, 0xFF294A80);
            if (kind == SAFE_AREA) { paint.setColor(SettingsUi.ACCENT); paint.setStyle(Paint.Style.STROKE); paint.setStrokeWidth(2); canvas.drawRoundRect(-23, -28, 23, 28, 5, 5, paint); paint.setStyle(Paint.Style.FILL); }
            rect(canvas, 0, 25, 28, 34, 3, 0xFF535862); rect(canvas, 1, 21, 11, 23, 1, SettingsUi.ACCENT); rect(canvas, 15, 21, 25, 23, 1, SettingsUi.ACCENT); canvas.restore();
            if (kind == GESTURES) { paint.setColor(SettingsUi.ACCENT); paint.setStrokeWidth(2); for (int side = 0; side < 2; side++) { float x = width / 2 + (side == 0 ? 6 : 20); canvas.drawLine(x, 66, x, 45, paint); canvas.drawLine(x, 45, x - 4, 51, paint); canvas.drawLine(x, 45, x + 4, 51, paint); } }
        }
        canvas.restore();
    }
}
