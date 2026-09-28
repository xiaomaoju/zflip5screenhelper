package io.github.flipcover.controls;

import java.util.ArrayList;
import java.util.List;

/** Coordinates are display pixels, not dp; cutout rectangles already follow display rotation. */
public final class DockGeometry {
    public static final int BOTTOM = 0, LEFT = 1, TOP = 2, RIGHT = 3;
    public record Box(int x, int y, int width, int height) {
        public int right() { return x + width; }
        public int bottom() { return y + height; }
    }
    public record Placement(Box visual, Box touch, Box panel, int edge, boolean measured) {
        public boolean vertical() { return edge == LEFT || edge == RIGHT; }
    }
    public record Slots(Box pager, Box fixed, int pageSize, int extent) { }
    public record Chrome(Box icons, Box firstHandle, Box secondHandle) { }
    /** Always horizontal at the current bottom-right; normal camera-bottom posture uses its ledge. */
    public static Placement panelEntry(Placement anchor, Placement dock, int width, int height, List<Box> cutouts, float density, int homeInset) {
        int margin = Math.max(4, Math.round(3 * density)), thickness = Math.max(1, Math.round(16 * density));
        Box bottomCamera = null;
        for (Box cut : cutouts) if (cut.bottom() >= height && cut.height() < height / 3 && (bottomCamera == null || cut.width() > bottomCamera.width())) bottomCamera = cut;
        Box safe = panelContent(dock, width, height, cutouts), v = anchor.visual();
        int right = safe.right() - margin, left = Math.max(safe.x() + margin, right - Math.round(safe.width() * .38f));
        int bottom = Math.min(safe.bottom(), height - Math.max(0, homeInset)) - margin;
        if (bottomCamera != null) {
            left = Math.max(safe.x() + margin, bottomCamera.x() + margin);
            right = Math.min(right, bottomCamera.right() - margin);
            bottom = Math.min(bottom, bottomCamera.y());
        } else if (cutouts.isEmpty() && anchor.edge() == BOTTOM) {
            // Normal posture without reported cutout: retain the user's calibrated camera-side estimate.
            left = Math.max(safe.x() + margin, v.right() + margin); bottom = Math.min(bottom, v.y());
        }
        Box touch = dock.touch();
        if (bottom > touch.y() && bottom - thickness < touch.bottom() && left < touch.right() && right > touch.x()) {
            if (dock.edge() == RIGHT) right = Math.min(right, touch.x() - margin);
            else if (dock.edge() == LEFT) left = Math.max(left, touch.right() + margin);
            else bottom = Math.min(bottom, touch.y() - margin);
        }
        left = Math.min(left, right - 1);
        int top = Math.max(safe.y(), bottom - thickness);
        Box strip = new Box(left, top, Math.max(1, right - left), Math.max(1, bottom - top));
        Placement entry = new Placement(strip, strip, dock.panel(), BOTTOM, !cutouts.isEmpty());
        return new Placement(strip, strip, panelContent(entry, width, height, cutouts), BOTTOM, entry.measured());
    }
    /** View-local drawing bounds. The physical edge touch target stays unchanged. */
    public static Chrome chrome(Placement p, float density) {
        Box v = p.visual(), t = p.touch(); boolean vertical = p.vertical();
        int x = v.x() - t.x(), y = v.y() - t.y();
        int length = vertical ? v.height() : v.width(), cross = vertical ? t.width() : t.height();
        int stroke = Math.max(1, Math.min(cross, Math.round(2 * density)));
        int edgeInset = Math.min(Math.max(0, cross - stroke), Math.round(5 * density));
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
        Box v = p.visual(); Box touch = switch (p.edge()) {
            case TOP -> new Box(v.x(), 0, v.width(), v.bottom());
            case LEFT -> new Box(0, v.y(), v.right(), v.height());
            case RIGHT -> new Box(v.x(), v.y(), width - v.x(), v.height());
            default -> new Box(v.x(), v.y(), v.width(), height - v.y());
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
    /** Hub covers the temporarily suspended entry, while retaining notch, chrome and Home safety. */
    public static Box hubContent(Placement placement, int width, int height, List<Box> cutouts, int homeInset) {
        Box safe = panelContent(placement, width, height, cutouts);
        int top = Math.max(safe.y(), placement.panel().y()), bottom = Math.min(safe.bottom(), height - Math.max(0, homeInset));
        return new Box(safe.x(), top, safe.width(), Math.max(1, bottom - top));
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
        int length = placement.vertical() ? height : width;
        int extent = length - length / count;
        int start = fixedFirst ? length - extent : 0, fixedStart = fixedFirst ? 0 : extent;
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
            visual = new Box(c.x + margin, c.y + margin, c.width - margin * 2, c.height - margin * 2);
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
        int target = Math.round(44 * density);
        int touchWidth = edge % 2 == 0 ? visual.width : Math.max(visual.width, target);
        int touchHeight = edge % 2 == 0 ? Math.max(visual.height, target) : visual.height;
        Box touch = new Box(edge == RIGHT ? visual.right() - touchWidth : visual.x,
            edge == BOTTOM ? visual.bottom() - touchHeight : visual.y, touchWidth, touchHeight);
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
