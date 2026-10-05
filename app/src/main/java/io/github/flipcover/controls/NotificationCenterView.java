package io.github.flipcover.controls;

import android.app.Notification;
import android.content.Context;
import android.graphics.Rect;
import android.service.notification.StatusBarNotification;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewParent;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Mounted notification content. Existing groups/children keep their views and reading state. */
final class NotificationCenterView extends LinearLayout {
    interface Actions {
        void open(StatusBarNotification item);
        void settings(StatusBarNotification item);
        void permission();
        void clear(List<StatusBarNotification> items);
    }
    final Button newNotice, clearAll;
    private final Actions actions;
    private final LinearLayout list;
    private final TextView empty, status;
    private final Button permission, more;
    private final LinkedHashMap<String, GroupRow> rows = new LinkedHashMap<>();
    private List<StatusBarNotification> latest = List.of();
    private final Set<String> shownKeys = new HashSet<>();
    private final Set<String> knownGroups = new HashSet<>();
    private boolean ready, updating;
    private int limit = 30;
    private NotificationSwipeRow openRow;
    private ScrollView scroll;
    private TextView panelTitle;
    private android.view.ViewTreeObserver.OnPreDrawListener anchorRestore;
    private final Runnable tick = new Runnable() {
        @Override public void run() {
            if (!isAttachedToWindow() || getWindowVisibility() != VISIBLE) return;
            for (GroupRow row : rows.values()) row.updateTimes();
            postDelayed(this, 60000 - System.currentTimeMillis() % 60000);
        }
    };
    NotificationCenterView(Context context, Actions actions) {
        super(context); this.actions = actions; setOrientation(VERTICAL); setTag("notification-center");
        empty = Ui.text(context, "", 13, Ui.MUTED); empty.setPadding(dp(8), dp(12), dp(8), dp(12)); Ui.add(this, empty);
        permission = PanelUi.button(context, "前往授权", actions::permission); Ui.add(this, permission);
        setClipChildren(false); list = Ui.column(context); list.setClipChildren(false); list.setTag("notification-list"); addView(list, new LayoutParams(-1, -2));
        more = PanelUi.button(context, "显示更多", () -> { limit += 30; update(ready, latest); }); Ui.add(this, more);
        status = Ui.text(context, "", 10, Ui.MUTED); status.setGravity(Gravity.CENTER); status.setPadding(0, dp(4), 0, dp(4)); Ui.add(this, status);
        newNotice = PanelUi.button(context, "", () -> { if (scroll != null) scroll.scrollTo(0, 0); update(ready, latest); }); newNotice.setTag("notification-new"); newNotice.setTextSize(10); newNotice.setMinHeight(dp(30)); newNotice.setMinimumHeight(dp(30)); newNotice.setVisibility(GONE);
        clearAll = PanelUi.button(context, "0", () -> clear(latest)); clearAll.setTag("notification-clear-all"); clearAll.setSingleLine();
        clearAll.setTextSize(12); clearAll.setMinHeight(dp(36)); clearAll.setMinimumHeight(dp(36)); clearAll.setMinWidth(dp(50)); clearAll.setMinimumWidth(dp(50)); clearAll.setPadding(dp(9), 0, dp(9), 0); clearAll.setFontFeatureSettings("tnum");
        android.graphics.drawable.Drawable trash = Ui.icon(context, R.drawable.ic_ms_delete, Ui.TEXT); trash.setBounds(0, 0, dp(PanelUi.ACTION_ICON), dp(PanelUi.ACTION_ICON)); clearAll.setCompoundDrawables(trash, null, null, null); clearAll.setCompoundDrawablePadding(dp(5)); clearAll.setGravity(Gravity.CENTER);
        update(false, List.of());
    }
    private int dp(float value) { return Ui.dp(getContext(), value); }
    void bindPanelTitle(TextView title) { panelTitle = title; panelTitle.setVisibility(newNotice.getVisibility() == VISIBLE ? INVISIBLE : VISIBLE); }
    private void clear(List<StatusBarNotification> selected) {
        List<StatusBarNotification> request = NotificationGroups.clearRequest(selected, latest);
        Set<String> keys = new HashSet<>(NotificationGroups.clearKeys(request, latest));
        for (GroupRow row : rows.values()) {
            if (!row.group.items.isEmpty() && row.group.items.stream().allMatch(item -> keys.contains(item.getKey()))) { row.clearing = true; row.header.swipe.prepareDismiss(); }
            for (Card card : row.cards.values()) if (card.current != null && keys.contains(card.current.getKey())) { card.clearRequested = true; if (row.children.getVisibility() == VISIBLE) card.swipe.prepareDismiss(); }
        }
        actions.clear(request);
    }
    private void animateRemoval(View departing) {
        if (!android.animation.ValueAnimator.areAnimatorsEnabled() || scroll != null && scroll.getScrollY() > 0) return;
        android.transition.ChangeBounds bounds = new android.transition.ChangeBounds(); bounds.setDuration(TaskSpring.DURATION); bounds.setInterpolator(TaskSpring::progress);
        for (GroupRow row : rows.values()) {
            if (row == departing || row.retiring) continue;
            if (departing.getParent() == row.children) {
                for (Card card : row.cards.values()) if (card.swipe != departing && !card.swipe.dismissing()) bounds.addTarget(card.swipe);
            } else bounds.addTarget(row);
        }
        if (!bounds.getTargets().isEmpty()) android.transition.TransitionManager.beginDelayedTransition(list, bounds);
    }
    private void removeGroup(GroupRow row) {
        if (rows.get(row.group.key) != row || !row.retiring) return;
        preserveReadingPosition(row); animateRemoval(row);
        if (openRow == row.header.swipe || row.cards.values().stream().anyMatch(card -> card.swipe == openRow)) openRow = null;
        list.removeView(row); rows.remove(row.group.key); update(ready, latest);
    }
    private void animateChange() {
        if (android.animation.ValueAnimator.areAnimatorsEnabled()) { android.transition.ChangeBounds bounds = new android.transition.ChangeBounds(); bounds.setDuration(160); android.transition.TransitionManager.beginDelayedTransition(list, bounds); }
    }
    private static void text(TextView view, CharSequence value) { if (!TextUtils.equals(view.getText(), value)) view.setText(value); }
    void update(boolean connected, List<StatusBarNotification> snapshot) {
        if (updating) return; updating = true;
        try {
            preserveReadingPosition();
            ready = connected; latest = connected ? new ArrayList<>(snapshot) : List.of();
            boolean reading = scroll != null && scroll.getScrollY() > dp(8) && !rows.isEmpty();
            List<StatusBarNotification> visible = new ArrayList<>(); int pending = 0;
            // Count real notifications only; summary updates are not "new messages".
            for (NotificationGroups.Group group : NotificationGroups.build(latest)) for (StatusBarNotification item : group.items) {
                if (!reading || shownKeys.contains(item.getKey())) visible.add(item); else pending++;
            }
            if (!reading) { shownKeys.clear(); for (StatusBarNotification item : visible) shownKeys.add(item.getKey()); }
            else { Set<String> active = new HashSet<>(); for (StatusBarNotification item : latest) active.add(item.getKey()); shownKeys.retainAll(active); }
            text(newNotice, pending + "条新通知 ↑"); newNotice.setVisibility(pending > 0 ? VISIBLE : GONE);
            if (panelTitle != null) panelTitle.setVisibility(pending > 0 ? INVISIBLE : VISIBLE);
            List<NotificationGroups.Group> groups = NotificationGroups.build(visible);
            Map<String, NotificationGroups.Group> next = new LinkedHashMap<>(); for (NotificationGroups.Group group : groups) next.put(group.key, group);
            java.util.Iterator<Map.Entry<String, GroupRow>> old = rows.entrySet().iterator();
            while (old.hasNext()) {
                Map.Entry<String, GroupRow> entry = old.next(); GroupRow row = entry.getValue();
                if (!next.containsKey(entry.getKey())) {
                    if (connected && row.clearing && row.exit()) continue;
                    if (openRow == row.header.swipe || row.cards.values().stream().anyMatch(card -> card.swipe == openRow)) openRow = null;
                    row.retiring = false; row.header.swipe.cancelDismiss(); for (Card card : row.cards.values()) card.swipe.cancelDismiss();
                    list.removeView(row); old.remove();
                }
            }
            boolean populated = !rows.isEmpty();
            for (NotificationGroups.Group group : groups) {
                GroupRow row = rows.get(group.key);
                boolean arrived = populated && !knownGroups.contains(group.key);
                if (row == null && (rows.size() < limit || arrived)) {
                    if (rows.size() >= limit) limit++;
                    int insertion = 0; while (insertion < list.getChildCount() && ((GroupRow) list.getChildAt(insertion)).group.items.get(0).getPostTime() >= group.items.get(0).getPostTime()) insertion++;
                    row = new GroupRow(group); rows.put(group.key, row); LayoutParams params = new LayoutParams(-1, -2); params.bottomMargin = dp(7); list.addView(row, insertion, params);
                }
                if (row != null) row.update(group);
            }
            knownGroups.clear(); knownGroups.addAll(next.keySet());
            int clearable = NotificationGroups.clearable(latest).size();
            text(empty, connected ? "暂无通知" : "通知使用权未连接，请检查授权"); empty.setVisibility(groups.isEmpty() && rows.isEmpty() ? VISIBLE : GONE); permission.setVisibility(connected ? GONE : VISIBLE);
            text(clearAll, String.valueOf(clearable)); clearAll.setEnabled(clearable > 0); clearAll.getCompoundDrawables()[0].setTint(clearable > 0 ? Ui.TEXT : Ui.MUTED);
            String clearDescription = "清除全部可清除通知，" + clearable + "条"; clearAll.setContentDescription(clearDescription); clearAll.setTooltipText(clearDescription);
            text(status, connected && !latest.isEmpty() && clearable == 0 ? "仅剩常驻通知" : ""); status.setVisibility(status.length() == 0 ? GONE : VISIBLE);
            int remaining = groups.size() - rows.size(); text(more, "显示更多 · " + remaining + "组"); more.setVisibility(remaining > 0 ? VISIBLE : GONE);
        } finally { updating = false; }
    }
    private void reveal(NotificationSwipeRow row) { if (openRow != null && openRow != row) openRow.showActions(false); openRow = row; }
    private int screenTop(View view) { int[] position = new int[2]; view.getLocationInWindow(position); return position[1]; }
    private void preserveReadingPosition() { preserveReadingPosition(null); }
    private void preserveReadingPosition(View departing) {
        if (scroll == null || scroll.getScrollY() == 0 || anchorRestore != null) return;
        int top = screenTop(scroll); View anchor = null;
        for (int i = 0; i < list.getChildCount() && anchor == null; i++) {
            GroupRow group = (GroupRow) list.getChildAt(i);
            if (group == departing || group.retiring) continue;
            if (group.header.swipe != departing && screenTop(group.header.swipe) + group.header.swipe.getHeight() > top) anchor = group.header.swipe;
            else if (group.children.getVisibility() == VISIBLE) for (int j = 0; j < group.children.getChildCount(); j++) {
                View child = group.children.getChildAt(j); if (child != departing && !(child instanceof NotificationSwipeRow swipe && swipe.dismissing()) && screenTop(child) + child.getHeight() > top) { anchor = child; break; }
            }
        }
        if (anchor == null) return;
        View saved = anchor; int offset = screenTop(saved) - top, previousScroll = scroll.getScrollY();
        anchorRestore = () -> {
            getViewTreeObserver().removeOnPreDrawListener(anchorRestore); anchorRestore = null;
            if (scroll != null && saved.isAttachedToWindow() && scroll.getScrollY() == previousScroll) {
                int delta = screenTop(saved) - screenTop(scroll) - offset; if (delta != 0) scroll.scrollBy(0, delta);
            }
            return true;
        };
        getViewTreeObserver().addOnPreDrawListener(anchorRestore);
    }
    @Override public boolean dispatchTouchEvent(MotionEvent event) {
        if (event.getActionMasked() == MotionEvent.ACTION_DOWN && openRow != null) {
            Rect bounds = new Rect(); openRow.getGlobalVisibleRect(bounds);
            if (!bounds.contains((int) event.getRawX(), (int) event.getRawY())) { openRow.showActions(false); openRow = null; }
        }
        return super.dispatchTouchEvent(event);
    }
    void closeActions() { if (openRow != null) openRow.showActions(false); openRow = null; }
    @Override protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        for (ViewParent parent = getParent(); parent != null; parent = parent.getParent()) if (parent instanceof ScrollView found) { scroll = found; break; }
        if (scroll != null) scroll.setOnScrollChangeListener((v, x, y, oldX, oldY) -> { if (y == 0 && newNotice.getVisibility() == VISIBLE) update(ready, latest); });
        removeCallbacks(tick); post(tick);
    }
    @Override protected void onWindowVisibilityChanged(int visibility) {
        super.onWindowVisibilityChanged(visibility); removeCallbacks(tick);
        if (visibility == VISIBLE && isAttachedToWindow()) post(tick);
        else if (visibility != VISIBLE && isAttachedToWindow()) {
            for (GroupRow row : rows.values()) { if (row.header.swipe.dismissing()) row.header.swipe.cancelDismiss(); for (Card card : row.cards.values()) if (card.swipe.dismissing()) card.swipe.cancelDismiss(); }
            update(ready, latest);
        }
    }
    @Override protected void onDetachedFromWindow() {
        removeCallbacks(tick); android.transition.TransitionManager.endTransitions(list); if (anchorRestore != null) { getViewTreeObserver().removeOnPreDrawListener(anchorRestore); anchorRestore = null; } if (scroll != null) scroll.setOnScrollChangeListener(null); scroll = null; closeActions(); super.onDetachedFromWindow();
    }
    private final class GroupRow extends LinearLayout {
        NotificationGroups.Group group;
        final Card header;
        final LinearLayout children;
        final LinkedHashMap<String, Card> cards = new LinkedHashMap<>();
        boolean expanded, clearing, retiring;
        GroupRow(NotificationGroups.Group group) {
            super(NotificationCenterView.this.getContext()); setOrientation(VERTICAL); setClipChildren(false); setTag(group.key); this.group = group;
            header = new Card(() -> {
                if (this.group.items.size() > 1) toggle();
                else actions.open(this.group.items.get(0));
            }, () -> clear(this.group.items));
            addView(header.swipe, new LayoutParams(-1, -2)); children = Ui.column(getContext()); children.setClipChildren(false); children.setPadding(dp(6), dp(4), 0, 0); addView(children, new LayoutParams(-1, -2));
        }
        @Override public boolean dispatchTouchEvent(MotionEvent event) { return retiring || super.dispatchTouchEvent(event); }
        boolean exit() {
            retiring = true;
            boolean animated = header.swipe.dismiss(() -> removeGroup(this));
            for (Card card : cards.values()) {
                if (animated) card.swipe.dismiss(() -> { });
                else animated = card.swipe.dismiss(() -> removeGroup(this));
            }
            retiring = animated; return animated;
        }
        private void removeChild(String key, Card card) {
            if (retiring || cards.get(key) != card) return;
            preserveReadingPosition(card.swipe); animateRemoval(card.swipe);
            if (openRow == card.swipe) openRow = null;
            children.removeView(card.swipe); cards.remove(key); NotificationCenterView.this.update(ready, latest);
        }
        void update(NotificationGroups.Group next) {
            if (retiring) { retiring = clearing = false; header.swipe.cancelDismiss(); for (Card card : cards.values()) card.swipe.cancelDismiss(); }
            if (clearing && next.items.stream().anyMatch(item -> group.items.stream().noneMatch(before -> before.getKey().equals(item.getKey()) && before.getPostTime() == item.getPostTime() && before.getUid() == item.getUid()))) clearing = false;
            Set<String> keys = new HashSet<>(); for (StatusBarNotification item : next.items) keys.add(item.getKey());
            boolean exitingChild = false;
            java.util.Iterator<Map.Entry<String, Card>> old = cards.entrySet().iterator();
            while (old.hasNext()) {
                Map.Entry<String, Card> entry = old.next(); Card card = entry.getValue(); String key = entry.getKey();
                if (!keys.contains(key)) {
                    if (ready && card.clearRequested && card.swipe.dismiss(() -> removeChild(key, card))) { exitingChild = true; continue; }
                    card.swipe.cancelDismiss();
                    children.removeView(card.swipe); old.remove();
                } else if (card.swipe.dismissing()) { card.swipe.cancelDismiss(); card.clearRequested = false; }
            }
            group = next;
            if (exitingChild) {
                header.current = next.items.get(0);
                for (StatusBarNotification item : next.items) { Card card = cards.get(item.getKey()); if (card != null) card.update(item, 1, false, item.isClearable()); }
                return;
            }
            boolean multiple = next.items.size() > 1;
            header.update(next.items.get(0), next.items.size(), multiple && expanded, !NotificationGroups.clearable(next.items).isEmpty());
            header.groupToggle = multiple ? this::toggle : null;
            header.updateOverflow(); header.swipe.setLayers(multiple && !expanded ? next.items.size() : 1);
            children.setVisibility(multiple && expanded ? VISIBLE : GONE);
            if (!multiple || !expanded) return;
            for (StatusBarNotification item : next.items) {
                Card card = cards.get(item.getKey());
                if (card == null) {
                    Card[] reference = new Card[1]; card = new Card(() -> actions.open(reference[0].current), () -> clear(List.of(reference[0].current))); reference[0] = card;
                    int insertion = 0; for (Card existing : cards.values()) if (existing.current.getPostTime() >= item.getPostTime()) insertion++;
                    cards.put(item.getKey(), card); LayoutParams params = new LayoutParams(-1, -2); params.bottomMargin = dp(4); children.addView(card.swipe, insertion, params);
                }
                card.update(item, 1, false, item.isClearable());
            }
        }
        private void toggle() { animateChange(); expanded = !expanded; closeActions(); update(group); }
        void updateTimes() { header.updateTime(); if (expanded) for (Card card : cards.values()) card.updateTime(); }
    }
    private final class Card {
        final NotificationSwipeRow swipe;
        final LinearLayout surface, rail;
        final TextView title, detail, time, meta, badge;
        final ImageView icon;
        final ImageButton expand;
        final View clear;
        StatusBarNotification current;
        Runnable groupToggle;
        boolean textExpanded, groupExpanded, clearRequested, expandOpened;
        int count;
        String appName = "", appPackage = "";
        Card(Runnable click, Runnable dismiss) {
            Context context = getContext(); surface = new NotificationCardSurface(context); surface.setBackground(Ui.ripple(context, Ui.SURFACE, 14)); surface.setPadding(dp(8), dp(8), dp(4), dp(8));
            LinearLayout content = Ui.row(context); content.setGravity(Gravity.CENTER_VERTICAL);
            FrameLayout iconBox = new FrameLayout(context); iconBox.setMinimumWidth(dp(34)); iconBox.setMinimumHeight(dp(32)); icon = new ImageView(context); icon.setTag("notification-icon"); iconBox.addView(icon, new FrameLayout.LayoutParams(dp(26), dp(26), Gravity.LEFT | Gravity.CENTER_VERTICAL));
            badge = Ui.text(context, "", 9, Ui.TEXT); badge.setGravity(Gravity.CENTER); badge.setSingleLine(); badge.setMinWidth(dp(14)); badge.setMinHeight(dp(15)); badge.setPadding(dp(3), dp(1), dp(3), dp(1)); badge.setBackground(Ui.background(context, 0xFF444B55, 8)); badge.setTag("notification-count"); iconBox.addView(badge, new FrameLayout.LayoutParams(-2, -2, Gravity.RIGHT | Gravity.TOP));
            LayoutParams image = new LayoutParams(-2, -2); image.rightMargin = dp(5); image.gravity = Gravity.CENTER_VERTICAL; content.addView(iconBox, image);
            LinearLayout words = Ui.column(context), heading = Ui.row(context); heading.setGravity(Gravity.TOP);
            title = Ui.heading(context, "", 12); title.setMaxLines(1); title.setEllipsize(TextUtils.TruncateAt.END); heading.addView(title, new LayoutParams(0, -2, 1));
            time = Ui.text(context, "", 9, Ui.MUTED); time.setSingleLine(); time.setGravity(Gravity.RIGHT); time.setTag("notification-time"); LayoutParams clock = new LayoutParams(-2, -2); clock.leftMargin = dp(5); clock.topMargin = dp(2); heading.addView(time, clock); words.addView(heading);
            detail = Ui.text(context, "", 11, Ui.MUTED); detail.setMaxLines(2); detail.setEllipsize(TextUtils.TruncateAt.END); detail.setPadding(0, dp(4), 0, 0); words.addView(detail);
            meta = Ui.text(context, "", 9, Ui.MUTED); meta.setPadding(0, dp(4), 0, 0); meta.setVisibility(GONE); words.addView(meta); content.addView(words, new LayoutParams(0, -2, 1));
            expand = PanelUi.icon(context, R.drawable.ic_ms_keyboard_arrow_down, "展开通知", () -> { if (groupToggle != null) groupToggle.run(); else toggleText(); }); expand.setTag("notification-expand"); content.addView(expand,new LayoutParams(dp(PanelUi.SLOT),dp(PanelUi.SLOT))); surface.addView(content);
            int expandPad = dp((PanelUi.SLOT - PanelUi.ACTION_ICON * PanelUi.ACTION_SCALE) * .5f); expand.setPadding(expandPad, expandPad, expandPad, expandPad);
            rail = Ui.row(context); rail.setGravity(Gravity.RIGHT | Gravity.CENTER_VERTICAL); rail.setClipChildren(false);
            View settings = action(R.drawable.ic_ms_settings, "应用通知设置", () -> { if (current != null) actions.settings(current); }); settings.setTag("notification-settings"); rail.addView(settings, new LayoutParams(dp(PanelUi.SLOT), dp(PanelUi.SLOT)));
            clear = action(R.drawable.ic_ms_delete, "清除此通知", dismiss); clear.setTag("notification-clear"); rail.addView(clear, new LayoutParams(dp(PanelUi.SLOT), dp(PanelUi.SLOT)));
            swipe = new NotificationSwipeRow(context, surface, rail, this::revealActions);
            surface.setOnClickListener(v -> { if (swipe.opened()) swipe.showActions(false); else click.run(); }); surface.setOnLongClickListener(v -> { swipe.showActions(true); return true; });
            title.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> updateOverflow()); detail.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> updateOverflow());
        }
        private void revealActions() { reveal(swipe); }
        private View action(int resource, String description, Runnable callback) {
            LinearLayout button = Ui.column(getContext()); button.setGravity(Gravity.CENTER); button.setClipChildren(false); button.setBackground(Ui.ripple(getContext(),0,8)); button.setContentDescription(description); button.setFocusable(true); button.setOnClickListener(v -> { if (resource != R.drawable.ic_ms_delete) closeActions(); callback.run(); });
            FrameLayout face=new FrameLayout(getContext()); face.setTag("notification-action-face"); face.setDuplicateParentStateEnabled(true); face.setBackground(Ui.background(getContext(),resource == R.drawable.ic_ms_delete ? 0xFF51282D : Ui.SURFACE,1000)); face.setScaleX(PanelUi.ACTION_SCALE); face.setScaleY(PanelUi.ACTION_SCALE); button.addView(face,new LayoutParams(dp(PanelUi.SLOT),dp(PanelUi.SLOT)));
            ImageView image = new ImageView(getContext()); image.setImageDrawable(Ui.icon(getContext(), resource, resource == R.drawable.ic_ms_delete ? 0xFFFFB4AB : Ui.TEXT)); image.setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO); face.addView(image,new FrameLayout.LayoutParams(dp(PanelUi.ACTION_ICON),dp(PanelUi.ACTION_ICON),Gravity.CENTER));
            return button;
        }
        void update(StatusBarNotification item, int count, boolean groupExpanded, boolean canClear) {
            if (current != null && (!current.getKey().equals(item.getKey()) || current.getPostTime() != item.getPostTime() || current.getUid() != item.getUid())) { clearRequested = false; swipe.cancelMergedAction(); }
            current = item; this.count = count; this.groupExpanded = groupExpanded;
            InputNavigation.bind(surface, "notice:" + (count > 1 ? "group:" : "item:") + item.getKey(), InputNavigation.Region.NOTICE, surface::performClick, this::inputMenu);
            if (!appPackage.equals(item.getPackageName())) {
                appPackage = item.getPackageName(); appName = appPackage;
                try { appName = getContext().getPackageManager().getApplicationLabel(getContext().getPackageManager().getApplicationInfo(appPackage, 0)).toString(); icon.setImageDrawable(getContext().getPackageManager().getApplicationIcon(appPackage)); }
                catch (Exception missing) { icon.setImageDrawable(Ui.icon(getContext(), R.drawable.ic_ms_notifications, Ui.TEXT)); }
                icon.setContentDescription(appName);
            }
            String heading = notificationText(item, Notification.EXTRA_TITLE); if (heading.isEmpty()) heading = appName;
            String body = notificationText(item, Notification.EXTRA_BIG_TEXT); if (body.isEmpty()) body = notificationText(item, Notification.EXTRA_TEXT);
            if (body.isEmpty()) body = "无可用正文";
            text(title, groupExpanded ? appName : heading); text(detail, groupExpanded ? count + "条通知 · 点击收起" : body);
            int lines = count > 1 || !textExpanded ? 1 : Integer.MAX_VALUE; if (title.getMaxLines() != lines) title.setMaxLines(lines);
            int bodyLines = count > 1 || !textExpanded ? 2 : Integer.MAX_VALUE; if (detail.getMaxLines() != bodyLines) detail.setMaxLines(bodyLines); meta.setVisibility(count == 1 && textExpanded ? VISIBLE : GONE);
            text(badge, count > 99 ? "99+" : String.valueOf(count)); badge.setVisibility(count > 1 ? VISIBLE : GONE); badge.setContentDescription(count + "条通知");
            clear.setVisibility(canClear ? VISIBLE : GONE); clear.setEnabled(canClear); clear.setContentDescription(count > 1 ? "清除此组可清除的通知" : "清除此通知"); swipe.setActionCount(canClear ? 2 : 1);
            surface.setContentDescription(appName + "，" + heading + (count > 1 ? "，" + count + "条通知，点击展开或收起" : "") + "，长按显示设置" + (canClear ? "与清除" : ""));
            updateTime(); updateOverflow();
        }
        private void inputMenu() {
            if (current == null) return; StatusBarNotification opened = current; int openedCount = count;
            java.util.function.BooleanSupplier valid = () -> current == opened && count == openedCount && surface.isAttachedToWindow();
            InputPopupMenu menu = new InputPopupMenu(getContext(), surface);
            if (groupToggle != null) menu.add(groupExpanded ? "折叠分组" : "展开分组").setOnMenuItemClickListener(value -> { if (valid.getAsBoolean() && groupToggle != null) groupToggle.run(); return true; });
            else menu.add(textExpanded ? "折叠正文" : "展开正文").setOnMenuItemClickListener(value -> { if (valid.getAsBoolean()) toggleText(); return true; });
            menu.add("应用通知设置").setOnMenuItemClickListener(value -> { if (valid.getAsBoolean()) actions.settings(opened); return true; });
            menu.add(count > 1 ? "清除此分组" : "清除此通知").setEnabled(clear.isEnabled()).setOnMenuItemClickListener(value -> { if (valid.getAsBoolean() && clear.isEnabled()) clear.performClick(); return true; }); menu.show();
        }
        void updateTime() {
            if (current == null) return; long now = System.currentTimeMillis(), timestamp = NotificationGroups.timestamp(current, now);
            java.time.ZoneId zone = java.time.ZoneId.systemDefault(); java.time.LocalDate day = java.time.Instant.ofEpochMilli(timestamp).atZone(zone).toLocalDate(), today = java.time.Instant.ofEpochMilli(now).atZone(zone).toLocalDate();
            String label;
            if (timestamp <= now && now - timestamp < 60000) label = "刚刚";
            else if (timestamp <= now && now - timestamp < 3600000) label = (now - timestamp) / 60000 + "分钟前";
            else if (day.equals(today)) label = android.text.format.DateFormat.getTimeFormat(getContext()).format(new java.util.Date(timestamp));
            else if (day.equals(today.minusDays(1))) label = "昨天";
            else label = android.text.format.DateFormat.format(day.getYear() == today.getYear() ? "M/d" : "yy/M/d", timestamp).toString();
            text(time, label); String full = android.text.format.DateFormat.format("yyyy/MM/dd HH:mm", timestamp).toString(); time.setContentDescription(full); text(meta, appName + " · " + full);
        }
        void toggleText() {
            animateChange(); textExpanded = !textExpanded; title.setMaxLines(textExpanded ? Integer.MAX_VALUE : 1); detail.setMaxLines(textExpanded ? Integer.MAX_VALUE : 2); meta.setVisibility(textExpanded ? VISIBLE : GONE); updateOverflow();
        }
        private boolean clipped(TextView view) { android.text.Layout layout = view.getLayout(); return layout != null && layout.getLineCount() > 0 && layout.getEllipsisCount(layout.getLineCount() - 1) > 0; }
        void updateOverflow() {
            boolean opened = count > 1 ? groupExpanded : textExpanded;
            int visibility = count > 1 || textExpanded || clipped(title) || clipped(detail) ? VISIBLE : INVISIBLE; if (expand.getVisibility() != visibility) expand.setVisibility(visibility);
            if (expandOpened != opened) { expandOpened = opened; expand.setImageDrawable(Ui.icon(getContext(), opened ? R.drawable.ic_ms_keyboard_arrow_up : R.drawable.ic_ms_keyboard_arrow_down, Ui.TEXT)); }
            expand.setContentDescription(count > 1 ? opened ? "收起通知组" : "展开通知组" : opened ? "折叠通知" : "展开通知");
        }
    }
    private static String notificationText(StatusBarNotification item, String key) {
        try { CharSequence value = item.getNotification().extras.getCharSequence(key); if (value == null) return ""; String text = value.toString(); return text.substring(0, Math.min(4096, text.length())); }
        catch (RuntimeException failure) { return ""; }
    }
}
