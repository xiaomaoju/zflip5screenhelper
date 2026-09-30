package io.github.flipcover.controls;

import android.content.Context;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;
import java.util.function.BiConsumer;

/** A single accessible target containing nine decorative, cache-backed previews. */
final class AppFolderTile extends ViewGroup {
    static final int[] COLORS = AppLauncherStyle.FOLDER_COLORS;
    private final ImageView[] icons = new ImageView[9];
    private final TextView title;
    private final View surface;
    AppFolderTile(Context context) {
        super(context);
        for (int i = 0; i < 9; i++) { icons[i] = new ImageView(context); icons[i].setScaleType(ImageView.ScaleType.FIT_CENTER); icons[i].setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO); addView(icons[i]); }
        title = Ui.text(context, "", AppLauncherStyle.LABEL_SP, Ui.TEXT); title.setGravity(android.view.Gravity.CENTER); title.setSingleLine(); title.setEllipsize(android.text.TextUtils.TruncateAt.END); title.setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO); addView(title);
        surface = new View(context); surface.setTag("folder-surface"); surface.setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO); surface.setDuplicateParentStateEnabled(true); addView(surface); setChildrenDrawingOrderEnabled(true);
        setFocusable(true);
    }
    void bind(AppWorkspaceLayout.Folder folder, BiConsumer<ImageView, String> icon, int notifications) {
        bind(folder, icon, notifications, false);
    }
    void bind(AppWorkspaceLayout.Folder folder, BiConsumer<ImageView, String> icon, int notifications, boolean compact) {
        surface.setBackground(Ui.ripple(getContext(), COLORS[folder.color()], AppLauncherStyle.folderRadius(getContext()))); title.setText((compact ? folder.members().size() + " · " : "") + folder.name() + (notifications > 0 ? " · " + notifications : ""));
        setContentDescription(folder.name() + "，文件夹，" + folder.members().size() + "个应用" + (notifications > 0 ? "，" + notifications + "条活动通知" : "") + "，点击打开");
        for (int i = 0; i < 9; i++) { icons[i].setVisibility(i < folder.members().size() ? VISIBLE : INVISIBLE); if (i < folder.members().size()) icon.accept(icons[i], folder.members().get(i)); else { icons[i].setTag(null); icons[i].setImageDrawable(null); } }
    }
    @Override protected void onMeasure(int w, int h) {
        int width = MeasureSpec.getSize(w), height = MeasureSpec.getSize(h); setMeasuredDimension(width, height);
        int side = AppLauncherStyle.folderSide(width, height); surface.measure(MeasureSpec.makeMeasureSpec(side, MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(side, MeasureSpec.EXACTLY));
        title.measure(MeasureSpec.makeMeasureSpec(Math.max(1, side - 2 * Ui.dp(getContext(), AppLauncherStyle.FOLDER_PADDING)), MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(Math.max(0, side / 3), MeasureSpec.AT_MOST));
        AppLauncherStyle.FolderGeometry layout = AppLauncherStyle.folderGeometry(getContext(), width, height, title.getMeasuredHeight());
        for (int i = 0; i < icons.length; i++) { android.graphics.Rect box = AppLauncherStyle.folderIconBounds(layout.preview().width(), i); icons[i].measure(MeasureSpec.makeMeasureSpec(box.width(), MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(box.height(), MeasureSpec.EXACTLY)); }
    }
    @Override protected void onLayout(boolean changed, int l, int t, int r, int b) {
        AppLauncherStyle.FolderGeometry layout = AppLauncherStyle.folderGeometry(getContext(), getWidth(), getHeight(), title.getMeasuredHeight());
        android.graphics.Rect box = layout.surface(); surface.layout(box.left, box.top, box.right, box.bottom); box = layout.label(); title.layout(box.left, box.top, box.right, box.bottom);
        for (int i = 0; i < icons.length; i++) { box = AppLauncherStyle.folderIconBounds(layout.preview().width(), i); box.offset(layout.preview().left, layout.preview().top); icons[i].layout(box.left, box.top, box.right, box.bottom); }
    }
    @Override protected int getChildDrawingOrder(int count, int position) { return position == 0 ? count - 1 : position - 1; }
}
