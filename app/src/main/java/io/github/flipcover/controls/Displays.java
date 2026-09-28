package io.github.flipcover.controls;

import android.content.Context;
import android.graphics.Point;
import android.hardware.display.DisplayManager;
import android.view.Display;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

public final class Displays {
    public static List<Display> all(Context context) {
        DisplayManager manager = context.getSystemService(DisplayManager.class);
        List<Display> result = new ArrayList<>();
        for (Display display : manager.getDisplays()) result.add(display);
        // Samsung's cover may be addressable but absent from getDisplays while folded.
        for (int id = 1; id <= 12; id++) {
            Display display = manager.getDisplay(id);
            if (display != null && result.stream().noneMatch(d -> d.getDisplayId() == display.getDisplayId())) result.add(display);
        }
        result.sort(Comparator.comparingInt(Display::getDisplayId));
        return result;
    }
    public static Display selected(Context context, Prefs prefs) {
        DisplayManager manager = context.getSystemService(DisplayManager.class);
        if (prefs.displayId() > 0) {
            Display display = manager.getDisplay(prefs.displayId());
            return display != null && display.isValid() ? display : null;
        }
        Display best = null;
        int bestScore = 0;
        for (Display display : all(context)) {
            if (display.getDisplayId() == 0 || !display.isValid() || (display.getFlags() & Display.FLAG_PRIVATE) != 0) continue;
            Point size = size(display);
            float ratio = size.x / (float) Math.max(1, size.y);
            if (ratio < .7 || ratio > 1.4 || (long) size.x * size.y > 1_500_000) continue;
            String name = display.getName().toLowerCase(java.util.Locale.ROOT);
            int score = display.getCutout() != null ? 2 : 0;
            if (name.contains("built-in") || name.contains("内置") || name.contains("cover")) score++;
            if (score > bestScore) { best = display; bestScore = score; }
        }
        return best;
    }
    public static Point size(Display display) { Point size = new Point(); display.getRealSize(size); return size; }
    public static String describe(Display display) {
        Point size = size(display);
        return "屏幕 " + display.getDisplayId() + " · " + size.x + "×" + size.y + " · " + display.getRotation() * 90 + "° · " + display.getName();
    }
}
