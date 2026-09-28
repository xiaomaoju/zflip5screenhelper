package io.github.flipcover.controls;

import android.os.Bundle;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.ScrollView;
import java.util.ArrayList;

/** Bounded in-activity history. Stores UI state, never device capabilities or application history. */
final class SettingsNavigator {
    private final ArrayList<Bundle> history = new ArrayList<>();
    private Bundle current;
    private boolean replay;
    Bundle current() { return current; }
    void restore(Bundle saved) {
        if (saved == null) return;
        current = saved.getBundle("current"); ArrayList<Bundle> entries = saved.getParcelableArrayList("history"); if (entries != null) history.addAll(entries); replay = true;
    }
    Bundle enter(Bundle arguments, ScrollView scroll, ViewGroup body) {
        if (replay) { replay = false; Bundle state = current == null ? null : current.getBundle("ui"); return state == null ? new Bundle() : state; }
        Bundle state = capture(scroll, body);
        boolean same = current != null && identity(current).equals(identity(arguments));
        if (current != null) { current.putBundle("ui", state); if (!same) { history.add(current); if (history.size() > 24) history.remove(0); } }
        current = new Bundle(arguments); current.putBundle("ui", same ? state : new Bundle()); return current.getBundle("ui");
    }
    Bundle back() { if (history.isEmpty()) return null; current = history.remove(history.size() - 1); replay = true; return current; }
    Bundle save(ScrollView scroll, ViewGroup body) { if (current != null) current.putBundle("ui", capture(scroll, body)); Bundle out = new Bundle(); out.putBundle("current", current); out.putParcelableArrayList("history", history); return out; }
    private String identity(Bundle value) { String page = value.getString("page", "main"); return page + (page.equals("library") || page.equals("order") ? "|" + value.getString("editing", "") : "") + (page.equals("app_orientation") ? "|" + value.getString("package", "") : ""); }
    static Bundle capture(ScrollView scroll, ViewGroup body) {
        Bundle state = new Bundle(); if (scroll == null || body == null) return state;
        state.putInt("y", scroll.getScrollY()); captureViews(body, state);
        if (body instanceof SettingsApplications applications) applications.saveUiState(state);
        View anchor = visibleAnchor(body, body, scroll.getScrollY());
        if (anchor != null) { state.putString("anchor", (String) anchor.getTag()); state.putInt("offset", scroll.getScrollY() - topInBody(anchor, body)); }
        return state;
    }
    private static int topInBody(View view, ViewGroup body) {
        int top = view.getTop(); while (view.getParent() instanceof View parent && parent != body) { top += parent.getTop(); view = parent; } return top;
    }
    private static View visibleAnchor(ViewGroup parent, ViewGroup body, int y) {
        for (int i = 0; i < parent.getChildCount(); i++) {
            View child = parent.getChildAt(i); if (child.getVisibility() != View.VISIBLE || topInBody(child, body) + child.getHeight() <= y) continue;
            if (child.getTag() instanceof String tag && (tag.startsWith("order-item-") || tag.startsWith("widget-provider-") || tag.startsWith("widget-card-"))) return child;
            if (child instanceof ViewGroup group) { View nested = visibleAnchor(group, body, y); if (nested != null) return nested; }
            if (child.getTag() instanceof String) return child;
        }
        return null;
    }
    private static void captureViews(View view, Bundle state) {
        if (view.getTag() instanceof String tag) { if (view instanceof EditText text) { state.putString(tag, text.getText().toString()); state.putBoolean(tag + "-visible", view.getVisibility() == View.VISIBLE); } if (view instanceof android.widget.ListView list) state.putParcelable("list-" + tag, list.onSaveInstanceState()); if (view.isFocused()) state.putString("focus", tag); }
        if (view instanceof ViewGroup group) for (int i = 0; i < group.getChildCount(); i++) captureViews(group.getChildAt(i), state);
    }
    static void restoreViews(ScrollView scroll, ViewGroup body, Bundle state) {
        scroll.post(() -> {
            if (!scroll.isAttachedToWindow()) return;
            String anchor = state.getString("anchor"), focus = state.getString("focus"); View target = anchor == null ? null : body.findViewWithTag(anchor);
            if (focus != null) { View focused = body.findViewWithTag(focus); if (focused != null) focused.requestFocus(); }
            scroll.scrollTo(0, target == null ? state.getInt("y", 0) : topInBody(target, body) + state.getInt("offset"));
        });
    }
}
