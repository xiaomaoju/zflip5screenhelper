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
    AppFolderTile(Context context) {
        super(context); setPadding(Ui.dp(context, 4), Ui.dp(context, 4), Ui.dp(context, 4), Ui.dp(context, 4));
        for (int i = 0; i < 9; i++) { icons[i] = new ImageView(context); icons[i].setScaleType(ImageView.ScaleType.FIT_CENTER); icons[i].setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO); addView(icons[i]); }
        title = Ui.text(context, "", 9, Ui.TEXT); title.setGravity(android.view.Gravity.CENTER); title.setSingleLine(); title.setEllipsize(android.text.TextUtils.TruncateAt.END); title.setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO); addView(title);
        setFocusable(true);
    }
    void bind(AppWorkspaceLayout.Folder folder, BiConsumer<ImageView, String> icon, int notifications) {
        bind(folder, icon, notifications, false);
    }
    void bind(AppWorkspaceLayout.Folder folder, BiConsumer<ImageView, String> icon, int notifications, boolean compact) {
        setBackground(Ui.ripple(getContext(), COLORS[folder.color()], 14)); title.setText((compact ? folder.members().size() + " · " : "") + folder.name() + (notifications > 0 ? " · " + notifications : ""));
        setContentDescription(folder.name() + "，文件夹，" + folder.members().size() + "个应用" + (notifications > 0 ? "，" + notifications + "条活动通知" : "") + "，点击打开");
        for (int i = 0; i < 9; i++) { icons[i].setVisibility(i < folder.members().size() ? VISIBLE : INVISIBLE); if (i < folder.members().size()) icon.accept(icons[i], folder.members().get(i)); else { icons[i].setTag(null); icons[i].setImageDrawable(null); } }
    }
    @Override protected void onMeasure(int w, int h) {
        int width = MeasureSpec.getSize(w), height = MeasureSpec.getSize(h); setMeasuredDimension(width, height);
        title.measure(MeasureSpec.makeMeasureSpec(Math.max(1, width - getPaddingLeft() - getPaddingRight()), MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(Math.max(0, height / 3), MeasureSpec.AT_MOST));
        int size = Math.max(1, Math.min((width - getPaddingLeft() - getPaddingRight()) / 3, (height - getPaddingTop() - getPaddingBottom() - title.getMeasuredHeight()) / 3) - Ui.dp(getContext(), 4));
        for (ImageView image : icons) image.measure(MeasureSpec.makeMeasureSpec(size, MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(size, MeasureSpec.EXACTLY));
    }
    @Override protected void onLayout(boolean changed, int l, int t, int r, int b) {
        int width = getWidth() - getPaddingLeft() - getPaddingRight(), bottom = getHeight() - getPaddingBottom(), height = bottom - title.getMeasuredHeight() - getPaddingTop();
        title.layout(getPaddingLeft(), bottom - title.getMeasuredHeight(), getWidth() - getPaddingRight(), bottom);
        for (int i = 0; i < 9; i++) { ImageView icon = icons[i]; int x = getPaddingLeft() + (int) ((i % 3 + .5f) * width / 3) - icon.getMeasuredWidth() / 2, y = getPaddingTop() + (int) ((i / 3 + .5f) * height / 3) - icon.getMeasuredHeight() / 2; icon.layout(x, y, x + icon.getMeasuredWidth(), y + icon.getMeasuredHeight()); }
    }
}
