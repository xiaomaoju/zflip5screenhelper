package io.github.flipcover.controls;

import java.util.ArrayList;
import java.util.List;

/** Coordinates are display pixels, not dp; cutout rectangles already follow display rotation. */
public final class DockGeometry {
    public static final int BOTTOM = 0, LEFT = 1, TOP = 2, RIGHT = 3;
    public record Box(int x, int y, int width, int height) {
        public int right() { return x + width; }
        public int bottom() { return y + height; }
        public Box intersect(Box other) {
            int left = Math.max(x, other.x), top = Math.max(y, other.y);
            return new Box(left, top, Math.max(0, Math.min(right(), other.right()) - left), Math.max(0, Math.min(bottom(), other.bottom()) - top));
        }
    }
    public record Placement(Box visual, Box touch, Box panel, int edge, boolean measured) {
        public boolean vertical() { return edge == LEFT || edge == RIGHT; }
    }
    public record Slots(Box pager, Box fixed, int pageSize, int extent) { }
    public record Chrome(Box icons, Box firstHandle, Box secondHandle) { }
    record ClockHandle(Box collapsed, Box expanded) { }
    static Box outsideDisplay(Box target, int edge, int width, int height) {
        return new Box(edge == LEFT ? -target.width() : edge == RIGHT ? width : target.x(), edge == TOP ? -target.height() : edge == BOTTOM ? height : target.y(), target.width(), target.height());
    }
    static Box interpolate(Box from, Box to, float progress) {
        float t = Math.max(0, Math.min(1, progress));
        return new Box(Math.round(from.x() + (to.x() - from.x()) * t), Math.round(from.y() + (to.y() - from.y()) * t), from.width(), from.height());
    }
    static ClockHandle clockHandlePositions(Placement dock, Box expanded, Box systemSafe) {
        Box visual = dock.visual().intersect(systemSafe);
        int w = Math.min(expanded.width(), visual.width()), h = Math.min(expanded.height(), visual.height());
        Box collapsed = new Box(visual.x() + (visual.width() - w) / 2, visual.y() + (visual.height() - h) / 2, w, h);
        Box open = new Box(expanded.x() + (expanded.width() - w) / 2, expanded.y() + (expanded.height() - h) / 2, w, h);
        return new ClockHandle(collapsed, open);
    }
    /** One small target on the inward side of the dock, clear of buttons and physical/system edges. */
    static Box clockHandle(Placement dock, int width, int height, List<Box> cutouts, float density, Box systemSafe, Box entryTouch) {
        Box safe = panelContent(dock, width, height, cutouts).intersect(systemSafe), touch = dock.touch(), visual = dock.visual();
        int gap = Math.max(1, Math.round(2 * density));
        int left = safe.x(), top = safe.y(), right = safe.right(), bottom = safe.bottom();
        switch (dock.edge()) {
            case TOP -> top = Math.max(top, touch.bottom() + gap);
            case LEFT -> left = Math.max(left, touch.right() + gap);
            case RIGHT -> right = Math.min(right, touch.x() - gap);
            default -> bottom = Math.min(bottom, touch.y() - gap);
        }
        int w = Math.min(Math.max(0, right - left), Math.round((dock.vertical() ? 28 : 48) * density));
        int h = Math.min(Math.max(0, bottom - top), Math.round((dock.vertical() ? 48 : 28) * density));
        int x = dock.edge() == LEFT ? left : dock.edge() == RIGHT ? right - w : Math.max(left, Math.min(right - w, visual.x() + (visual.width() - w) / 2));
        int y = dock.edge() == TOP ? top : dock.edge() == BOTTOM ? bottom - h : Math.max(top, Math.min(bottom - h, visual.y() + (visual.height() - h) / 2));
        Box candidate = new Box(x, y, w, h), overlap = candidate.intersect(entryTouch);
        if (overlap.width() > 0 && overlap.height() > 0) {
            switch (dock.edge()) {
                case TOP -> y = entryTouch.bottom() + gap;
                case LEFT -> x = entryTouch.right() + gap;
                case RIGHT -> x = entryTouch.x() - gap - w;
                default -> y = entryTouch.y() - gap - h;
            }
            if (x < left || y < top || x + w > right || y + h > bottom) return new Box(left, top, 0, 0);
        }
        return new Box(x, y, w, h);
    }
    /** Horizontal entry at the selected top corner or the original camera-bottom right position. */
    public static Placement panelEntry(Placement anchor, Placement dock, int width, int height, List<Box> cutouts, float density, int homeInset, String position) {
        return panelEntry(anchor, dock, width, height, cutouts, density, homeInset, position, 0);
    }
    public static int panelEntryTopInset(String position, int statusScale, float density) {
        if (position.equals("bottom_right")) return 0;
        // Control center still displays its status row when the floating row is disabled.
        int gap = position.equals("top_left") ? 2 : 10;
        return Math.round(Math.round(20 * density) * statusScale / 100f) + Math.round(gap * density);
    }
    public static Placement panelEntry(Placement anchor, Placement dock, int width, int height, List<Box> cutouts, float density, int homeInset, String position, int topInset) {
        int margin = Math.max(4, Math.round(3 * density)), thickness = Math.max(1, Math.round((topInset > 0 ? 24 : 16) * density));
        Box safe = panelContent(dock, width, height, cutouts), touch = dock.touch();
        boolean topEntry = !position.equals("bottom_right"), leftEntry = position.equals("top_left");
        int right = safe.right() - margin, left = safe.x() + margin;
        int length = Math.max(1, Math.round(safe.width() * .38f));
        if (leftEntry) right = Math.min(right, left + length); else left = Math.max(left, right - length);
        int top, bottom;
        if (topEntry) {
            top = safe.y() + topInset;
            for (Box cut : cutouts) if (cut.y() == 0 && cut.height() < height / 3 && left < cut.right() && right > cut.x()) top = Math.max(top, cut.bottom() + margin);
            bottom = Math.min(height, top + thickness);
        } else {
            bottom = Math.min(safe.bottom(), height - Math.max(0, homeInset));
            for (Box cut : cutouts) if (cut.bottom() >= height && cut.height() < height / 3) {
                left = Math.max(safe.x() + margin, cut.x() + margin); right = Math.min(right, cut.right() - margin); bottom = Math.min(bottom, cut.y());
            }
            if (cutouts.isEmpty() && anchor.edge() == BOTTOM) { left = Math.max(safe.x() + margin, anchor.visual().right() + margin); bottom = Math.min(bottom, anchor.visual().y()); }
            top = Math.max(safe.y(), bottom - thickness);
        }
        if (bottom > touch.y() && top < touch.bottom() && left < touch.right() && right > touch.x()) {
            if (dock.edge() == RIGHT) right = Math.min(right, touch.x() - margin);
            else if (dock.edge() == LEFT) left = Math.max(left, touch.right() + margin);
            else if (topEntry) { top = touch.bottom() + margin; bottom = Math.min(height, top + thickness); }
            else { bottom = Math.min(bottom, touch.y() - margin); top = Math.max(safe.y(), bottom - thickness); }
        }
        left = Math.max(0, Math.min(left, width - 1)); right = Math.max(left + 1, Math.min(width, right));
        top = Math.max(0, Math.min(top, height - 1)); bottom = Math.max(top + 1, Math.min(height, bottom));
        Box strip = new Box(left, top, right - left, bottom - top); int edge = topEntry ? TOP : BOTTOM;
        // Android chooses the gesture's window on DOWN: the lowered artwork must
        // not leave a dead band between the display edge and the touch window.
        Box entryTouch = topEntry && topInset > 0 ? new Box(left, safe.y(), right - left, bottom - safe.y()) : strip;
        Placement entry = new Placement(strip, entryTouch, dock.panel(), edge, !cutouts.isEmpty());
        return new Placement(strip, entryTouch, panelContent(entry, width, height, cutouts), edge, entry.measured());
    }
    /** View-local drawing bounds. The physical edge touch target stays unchanged. */
    public static Chrome chrome(Placement p, float density) { return chrome(p, density, 5); }
    /** The dedicated entry sits one dp inside its selected safe edge. */
    public static Chrome panelEntryChrome(Placement p, float density) {
        Chrome result = chrome(p, density, 1);
        int offset = p.edge() == TOP ? p.visual().y() - p.touch().y() : 0;
        Box a = result.firstHandle(), b = result.secondHandle();
        return new Chrome(result.icons(), new Box(a.x(), a.y() + offset, a.width(), a.height()), new Box(b.x(), b.y() + offset, b.width(), b.height()));
    }
    /** Retain the edge-to-handle swipe target, reclaiming only the unused tail below the artwork. */
    public static Placement compactTopEntry(Placement entry, float density) {
        if (entry.edge() != TOP) return entry;
        Chrome handles = panelEntryChrome(entry, density); Box visual = entry.visual(), touch = entry.touch(), panel = entry.panel();
        int bottom = Math.min(touch.bottom(), touch.y() + Math.max(handles.firstHandle().bottom(), handles.secondHandle().bottom()) + Math.max(1, Math.round(3 * density)));
        return new Placement(new Box(visual.x(), visual.y(), visual.width(), bottom - visual.y()), new Box(touch.x(), touch.y(), touch.width(), bottom - touch.y()), new Box(panel.x(), bottom, panel.width(), Math.max(0, panel.bottom() - bottom)), entry.edge(), entry.measured());
    }
    /** Preserve the content's dimensions when it fits; only redistribute vertical spare space. */
    public static Box centerVertically(Box content, Box available) {
        int height = Math.min(content.height(), available.height());
        return new Box(content.x(), available.y() + (available.height() - height) / 2, content.width(), height);
    }
    private static Chrome chrome(Placement p, float density, int edgeInsetDp) {
        Box v = p.visual(), t = p.touch(); boolean vertical = p.vertical();
        int x = v.x() - t.x(), y = v.y() - t.y();
        int length = vertical ? v.height() : v.width(), cross = vertical ? t.width() : t.height();
        int stroke = Math.max(1, Math.min(cross, Math.round(2 * density)));
        int edgeInset = Math.min(Math.max(0, cross - stroke), Math.round(edgeInsetDp * density));
        int endInset = Math.min(length / 6, Math.max(Math.round(10 * density), Math.round(length * .1f)));
        int first = (vertical ? y : x) + endInset, second = (vertical ? y : x) + length / 2 + endInset;
        int handleLength = Math.max(1, length / 2 - 2 * endInset);
        int across = p.edge() == TOP || p.edge() == LEFT ? edgeInset : cross - edgeInset - stroke;
        Box a = vertical ? new Box(across, first, stroke, handleLength) : new Box(first, across, handleLength, stroke);
        Box b = vertical ? new Box(across, second, stroke, handleLength) : new Box(second, across, handleLength, stroke);
        int reserve = Math.min(Math.max(0, cross - 1), edgeInset + stroke + Math.round(3 * density));
        int left = x, top = y, right = x + v.width(), bottom = y + v.height();
        switch (p.edge()) {
            case TOP -> top = Math.max(top, reserve);
            case LEFT -> left = Math.max(left, reserve);
            case RIGHT -> right = Math.min(right, t.width() - reserve);
            default -> bottom = Math.min(bottom, t.height() - reserve);
        }
        return new Chrome(new Box(left, top, Math.max(1, right - left), Math.max(1, bottom - top)), a, b);
    }
    public static Placement handlesOnly(Placement placement, float density) {
        Box touch = placement.touch;
        int thickness = Math.min(Math.max(1, Math.round(16 * density)), placement.vertical() ? touch.width : touch.height);
        Box strip = switch (placement.edge) {
            case TOP -> new Box(touch.x, touch.y, touch.width, thickness);
            case LEFT -> new Box(touch.x, touch.y, thickness, touch.height);
            case RIGHT -> new Box(touch.right() - thickness, touch.y, thickness, touch.height);
            default -> new Box(touch.x, touch.bottom() - thickness, touch.width, thickness);
        };
        return new Placement(strip, strip, placement.panel, placement.edge, placement.measured);
    }
    /** Include the very first physical display pixel so bezel-to-screen swipes have a DOWN target. */
    public static Placement edgeTouch(Placement p, int width, int height) {
        Box v = p.visual(), t = p.touch(); Box touch = switch (p.edge()) {
            case TOP -> new Box(t.x(), 0, t.width(), v.bottom());
            case LEFT -> new Box(0, t.y(), v.right(), t.height());
            case RIGHT -> new Box(v.x(), t.y(), width - v.x(), t.height());
            default -> new Box(t.x(), v.y(), t.width(), height - v.y());
        };
        return new Placement(v, touch, p.panel(), p.edge(), p.measured());
    }
    /** Content ends at the physical notch/visible dock, not at an oversized touch target. */
    public static Box panelContent(Placement p, int width, int height, List<Box> cutouts) {
        int left = 0, top = 0, right = width, bottom = height;
        for (Box c : cutouts) {
            if (c.y() == 0 && c.height() < height / 3) top = Math.max(top, c.bottom());
            if (c.bottom() >= height && c.height() < height / 3) bottom = Math.min(bottom, c.y());
            if (c.x() == 0 && c.width() < width / 3) left = Math.max(left, c.right());
            if (c.right() >= width && c.width() < width / 3) right = Math.min(right, c.x());
        }
        Box v = p.visual();
        switch (p.edge()) { case TOP -> top = Math.max(top, v.bottom()); case LEFT -> left = Math.max(left, v.right()); case RIGHT -> right = Math.min(right, v.x()); default -> bottom = Math.min(bottom, v.y()); }
        return new Box(left, top, Math.max(1, right - left), Math.max(1, bottom - top));
    }
    /** Non-touchable status spans the safe top, preserving left/right groups regardless of entry position. */
    public static Box statusBar(Box area, int displayWidth, int height, float density) {
        int margin = Math.max(4, Math.round(3 * density));
        int left = area.x() <= margin ? 0 : area.x(), top = area.y();
        int right = area.right() >= displayWidth - margin ? displayWidth : area.right();
        int bottom = area.y() + height;
        return new Box(left, top, right - left, bottom - top);
    }
    /** Native cards sit behind the entry window; reserve its artwork, not the panel's gesture band. */
    public static Box widgetContent(Placement dock, Placement entry, Box status, int width, int height, List<Box> cutouts, float density) {
        Box safe = panelContent(dock, width, height, cutouts); Chrome chrome = panelEntryChrome(entry, density);
        int top = safe.y(), bottom = safe.bottom();
        if (status != null) top = Math.max(top, status.bottom());
        if (entry.edge() == TOP) top = Math.max(top, entry.touch().y() + Math.max(chrome.firstHandle().bottom(), chrome.secondHandle().bottom()));
        else bottom = Math.min(bottom, entry.touch().y() + Math.min(chrome.firstHandle().y(), chrome.secondHandle().y()));
        return new Box(safe.x(), top, safe.width(), Math.max(0, bottom - top));
    }
    /** Reclaim the entry band before applying all current system edges; preserve the existing upward shift. */
    public static Box hubContent(Placement placement, int width, int height, List<Box> cutouts, Box systemSafe) {
        Box safe = panelContent(placement, width, height, cutouts);
        int top = Math.max(safe.y(), placement.panel().y()), bottom = Math.min(safe.bottom(), systemSafe.bottom());
        // Move the whole launcher above visible navigation before reducing its height.
        // System top safety and the notch/shortcut edge both limit that movement.
        top = Math.max(Math.max(safe.y(), systemSafe.y()), top - Math.max(0, safe.bottom() - bottom));
        return new Box(safe.x(), top, safe.width(), Math.max(0, bottom - top)).intersect(systemSafe);
    }
    /** Move along the inward normal only; preserve physical cutout handedness. */
    public static Placement avoidEdge(Placement p, int screenWidth, int screenHeight, int inset, int gap) {
        Box v = p.visual(), t = p.touch(), area = p.panel();
        int existing = switch (p.edge()) { case TOP -> v.y(); case LEFT -> v.x(); case RIGHT -> screenWidth - v.right(); default -> screenHeight - v.bottom(); };
        int available = switch (p.edge()) { case TOP -> screenHeight - t.bottom(); case LEFT -> screenWidth - t.right(); case RIGHT -> t.x(); default -> t.y(); };
        int shift = Math.min(Math.max(0, available), Math.max(0, inset + gap - existing));
        int dx = p.edge() == LEFT ? shift : p.edge() == RIGHT ? -shift : 0, dy = p.edge() == TOP ? shift : p.edge() == BOTTOM ? -shift : 0;
        Box moved = new Box(t.x() + dx, t.y() + dy, t.width(), t.height());
        int left = area.x(), top = area.y(), right = area.right(), bottom = area.bottom();
        switch (p.edge()) { case TOP -> top = Math.max(top, moved.bottom()); case LEFT -> left = Math.max(left, moved.right()); case RIGHT -> right = Math.min(right, moved.x()); default -> bottom = Math.min(bottom, moved.y()); }
        return new Placement(new Box(v.x() + dx, v.y() + dy, v.width(), v.height()), moved, new Box(left, top, Math.max(1, right - left), Math.max(1, bottom - top)), p.edge(), p.measured());
    }
    public static Slots slots(Placement placement, int total) {
        return slots(placement, total, false);
    }
    public static Slots slots(Placement placement, int total, boolean fixedFirst) {
        int count = Math.max(2, Math.min(5, total));
        int width = placement.touch.width, height = placement.touch.height;
        int length = placement.vertical() ? placement.visual.height : placement.visual.width;
        int rowStart = placement.vertical() ? placement.visual.y - placement.touch.y : placement.visual.x - placement.touch.x;
        int extent = length - length / count;
        int start = rowStart + (fixedFirst ? length - extent : 0), fixedStart = rowStart + (fixedFirst ? 0 : extent);
        Box pager = new Box(placement.vertical() ? 0 : start, placement.vertical() ? start : 0, placement.vertical() ? width : extent, placement.vertical() ? extent : height);
        Box fixed = placement.vertical() ? new Box(0, fixedStart, width, length - extent) : new Box(fixedStart, 0, length - extent, height);
        return new Slots(pager, fixed, count - 1, extent);
    }
    private record Lane(Box box, int edge) { }
    public static Placement resolve(int width, int height, List<Box> cutouts, float density, int corner,
                                    float widthRatio, float heightRatio, boolean automatic) {
        int margin = Math.max(4, Math.round(3 * density));
        int left = margin, top = margin, right = width - margin, bottom = height - margin;
        List<Lane> lanes = new ArrayList<>();
        for (Box c : cutouts) {
            if (c.y == 0 && c.height < height / 3) {
                top = Math.max(top, c.bottom() + margin);
                add(lanes, new Box(0, 0, c.x, c.height), TOP);
                add(lanes, new Box(c.right(), 0, width - c.right(), c.height), TOP);
            }
            if (c.bottom() >= height && c.height < height / 3) {
                bottom = Math.min(bottom, c.y - margin);
                add(lanes, new Box(0, c.y, c.x, c.height), BOTTOM);
                add(lanes, new Box(c.right(), c.y, width - c.right(), c.height), BOTTOM);
            }
            if (c.x == 0 && c.width < width / 3) {
                left = Math.max(left, c.right() + margin);
                add(lanes, new Box(0, 0, c.width, c.y), LEFT);
                add(lanes, new Box(0, c.bottom(), c.width, height - c.bottom()), LEFT);
            }
            if (c.right() >= width && c.width < width / 3) {
                right = Math.min(right, c.x - margin);
                add(lanes, new Box(c.x, 0, c.width, c.y), RIGHT);
                add(lanes, new Box(c.x, c.bottom(), c.width, height - c.bottom()), RIGHT);
            }
        }
        Lane lane = null;
        if (automatic) for (Lane candidate : lanes) {
            int length = candidate.edge % 2 == 0 ? candidate.box.width : candidate.box.height;
            int thickness = candidate.edge % 2 == 0 ? candidate.box.height : candidate.box.width;
            if (length >= 90 * density && thickness >= 14 * density && (lane == null || candidate.box.width * candidate.box.height > lane.box.width * lane.box.height)) lane = candidate;
        }
        boolean measured = lane != null;
        int edge = (corner + 1) % 4;
        Box visual;
        if (measured) {
            edge = lane.edge;
            Box c = lane.box;
            int visualWidth = c.width - margin * 2, visualHeight = c.height - margin * 2;
            int x = c.x + margin, y = c.y + margin;
            // Keep the outer endpoint; leave one eighth of the tail clear toward the camera.
            if (edge % 2 == 0) {
                int compactWidth = Math.max(1, Math.round(visualWidth * .875f));
                if (c.x > 0) x += visualWidth - compactWidth;
                visualWidth = compactWidth;
            } else {
                int compactHeight = Math.max(1, Math.round(visualHeight * .875f));
                if (c.y > 0) y += visualHeight - compactHeight;
                visualHeight = compactHeight;
            }
            visual = new Box(x, y, visualWidth, visualHeight);
        } else {
            int length = Math.round(Math.min(width, height) * widthRatio);
            int thickness = Math.round(Math.min(width, height) * heightRatio);
            int visualWidth = edge % 2 == 0 ? length : thickness;
            int visualHeight = edge % 2 == 0 ? thickness : length;
            // When a cutout exists but no usable tail is reported, stay inside its safe rectangle.
            int safeLeft = automatic && !cutouts.isEmpty() ? left : margin;
            int safeTop = automatic && !cutouts.isEmpty() ? top : margin;
            int safeRight = automatic && !cutouts.isEmpty() ? right : width - margin;
            int safeBottom = automatic && !cutouts.isEmpty() ? bottom : height - margin;
            visualWidth = Math.min(visualWidth, safeRight - safeLeft);
            visualHeight = Math.min(visualHeight, safeBottom - safeTop);
            visual = new Box(corner == 0 || corner == 3 ? safeLeft : safeRight - visualWidth,
                corner < 2 ? safeTop : safeBottom - visualHeight, visualWidth, visualHeight);
        }
        // The narrower artwork leaves a camera-side extension for the pinned action.
        Box footprint = measured ? new Box(lane.box.x + margin, lane.box.y + margin, lane.box.width - margin * 2, lane.box.height - margin * 2) : visual;
        int target = Math.round(44 * density);
        int touchWidth = edge % 2 == 0 ? footprint.width : Math.max(footprint.width, target);
        int touchHeight = edge % 2 == 0 ? Math.max(footprint.height, target) : footprint.height;
        Box touch = new Box(edge == RIGHT ? footprint.right() - touchWidth : footprint.x,
            edge == BOTTOM ? footprint.bottom() - touchHeight : footprint.y, touchWidth, touchHeight);
        switch (edge) {
            case BOTTOM -> bottom = Math.min(bottom, touch.y - margin);
            case TOP -> top = Math.max(top, touch.bottom() + margin);
            case LEFT -> left = Math.max(left, touch.right() + margin);
            case RIGHT -> right = Math.min(right, touch.x - margin);
        }
        return new Placement(visual, touch, new Box(left, top, Math.max(1, right - left), Math.max(1, bottom - top)), edge, measured);
    }
    private static void add(List<Lane> lanes, Box box, int edge) { if (box.width > 0 && box.height > 0) lanes.add(new Lane(box, edge)); }
}
