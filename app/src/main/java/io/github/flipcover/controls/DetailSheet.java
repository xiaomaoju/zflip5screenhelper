package io.github.flipcover.controls;

import android.animation.ValueAnimator;
import android.content.Context;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.DecelerateInterpolator;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

/** One modal layer. Natural card size, bounded by the selected display's safe area. */
final class DetailSheet extends FrameLayout {
    final LinearLayout content;
    final TextView title;
    private final Card card;
    private final LinearLayout header;
    private final ScrollView scroll;
    private View footer;
    private boolean compactGlassControls;
    private boolean panelStyle;
    void panelStyle() {
        panelStyle=true; title.setTextSize(PanelUi.TITLE); title.setSingleLine(); title.setEllipsize(android.text.TextUtils.TruncateAt.END); title.setLayoutParams(new LinearLayout.LayoutParams(0,-2,1));
        header.setPadding(Ui.dp(getContext(),PanelUi.INSET),0,Ui.dp(getContext(),2),0); content.setPadding(Ui.dp(getContext(),PanelUi.INSET),0,Ui.dp(getContext(),PanelUi.INSET),Ui.dp(getContext(),PanelUi.GAP));
        for (int i=0;i<header.getChildCount();i++) if (header.getChildAt(i)!=title) { View action=header.getChildAt(i); action.setLayoutParams(new LinearLayout.LayoutParams(Ui.dp(getContext(),PanelUi.SLOT),Ui.dp(getContext(),PanelUi.SLOT))); action.setPadding(Ui.dp(getContext(),8),Ui.dp(getContext(),8),Ui.dp(getContext(),8),Ui.dp(getContext(),8)); }
    }
    void glassControls() {
        compactGlassControls=true;
        if (footer!=null && !(footer instanceof PanelActionSlot)) { card.removeView(footer); footer=new PanelActionSlot(footer); card.addView(footer); }
    }
    private final Runnable dismiss;
    private boolean closing, multiplePointers, runtimeTouch = true;
    private android.view.ViewTreeObserver.OnPreDrawListener entrance;
    private boolean entranceReady;
    private android.graphics.RectF sourceBounds;
    private float originX, originY, originScale;
    private DetailSheetMotion motion;
    private Runnable backAction;
    private java.util.function.BooleanSupplier closeRequest;
    void onBack(Runnable action) { backAction = action; }
    void onCloseRequest(java.util.function.BooleanSupplier action) { closeRequest = action; }
    void back() { if (backAction != null) backAction.run(); else close(); }
    private int maximumWidthDp = 296;
    private float widthFraction = 1;
    private boolean stableWidth;
    private int viewportHeightDp;
    private boolean handlesWindowBack;
    private android.window.OnBackInvokedDispatcher backDispatcher;
    private android.window.OnBackInvokedCallback backCallback;
    private java.util.function.IntConsumer contentHeightListener;
    @Override public boolean dispatchTouchEvent(MotionEvent event) {
        // A new gesture belongs to the underlying panel as soon as dismissal starts.
        // The gesture that invoked close already has its touch target and cannot click through.
        if (closing) return false;
        if (!runtimeTouch) return super.dispatchTouchEvent(event);
        if (event.getActionMasked() == MotionEvent.ACTION_DOWN) multiplePointers = false;
        if (!multiplePointers && (event.getPointerCount() > 1 || event.getActionMasked() == MotionEvent.ACTION_POINTER_DOWN)) {
            multiplePointers = true; MotionEvent cancel = MotionEvent.obtain(event); cancel.setAction(MotionEvent.ACTION_CANCEL); super.dispatchTouchEvent(cancel); cancel.recycle();
        }
        return multiplePointers || super.dispatchTouchEvent(event);
    }
    DetailSheet(Context context, String name, Runnable dismiss) {
        super(context); this.dismiss = dismiss; setTag("detail-sheet"); setBackgroundColor(0x55000000); setClickable(true); setFocusableInTouchMode(true);
        setOnClickListener(v -> close()); setOnKeyListener((v, key, event) -> { if (key != KeyEvent.KEYCODE_BACK && key != KeyEvent.KEYCODE_ESCAPE) return false; if (event.getAction() == KeyEvent.ACTION_UP) back(); return true; });
        card = new Card(context); card.setTag("detail-card"); card.setBackground(Ui.background(context, 0xF222252B, 18)); card.setClipToOutline(true); card.setClickable(true); card.setElevation(Ui.dp(context, 6));
        header = Ui.row(context); header.setPadding(Ui.dp(context, 10), Ui.dp(context, 3), Ui.dp(context, 4), Ui.dp(context, 2));
        title = Ui.heading(context, name, 13); header.addView(title, new LinearLayout.LayoutParams(-2, -2, 1));
        View close = Ui.iconButton(context, R.drawable.ic_ms_close, "关闭详情", this::close); close.setTag("detail-close"); close.setPadding(Ui.dp(context, 14), Ui.dp(context, 14), Ui.dp(context, 14), Ui.dp(context, 14)); header.addView(close, new LinearLayout.LayoutParams(Ui.dp(context, 48), Ui.dp(context, 48))); card.addView(header);
        content = Ui.column(context); content.setPadding(Ui.dp(context, 10), 0, Ui.dp(context, 10), Ui.dp(context, 5));
        scroll = new ScrollView(context); scroll.setTag("detail-scroll"); scroll.setFillViewport(false); scroll.addView(content); card.addView(scroll);
        addView(card, new FrameLayout.LayoutParams(-2, -2, Gravity.CENTER)); setAccessibilityPaneTitle(name);
    }
    void footer(String text, Runnable action) {
        if (footer != null) card.removeView(footer);
        footer = Ui.button(getContext(), text, action); ((TextView) footer).setTextSize(11);
        footer.setMinimumHeight(Ui.dp(getContext(), 34)); ((android.widget.Button) footer).setMinHeight(Ui.dp(getContext(), 34));
        if (panelStyle) PanelUi.action((android.widget.Button)footer);
        float radius = Ui.dp(getContext(), 18); float[] corners = {0, 0, 0, 0, radius, radius, radius, radius};
        android.graphics.drawable.GradientDrawable surface = Ui.background(getContext(), 0xFF202329, 0), mask = Ui.background(getContext(), android.graphics.Color.WHITE, 0);
        surface.setCornerRadii(corners); mask.setCornerRadii(corners);
        footer.setBackground(new android.graphics.drawable.RippleDrawable(android.content.res.ColorStateList.valueOf(0x24FFFFFF), surface, mask)); if (compactGlassControls) footer=new PanelActionSlot(footer); card.addView(footer); card.requestLayout();
    }
    /** Media needs a small icon toolbar, without a repeated heading or text footer. */
    void mediaHeader(View... actions) {
        title.setText(""); title.setVisibility(INVISIBLE);
        header.setPadding(Ui.dp(getContext(),panelStyle ? PanelUi.INSET : 7),Ui.dp(getContext(),panelStyle ? 0 : 3),Ui.dp(getContext(),panelStyle ? 2 : 7),Ui.dp(getContext(),panelStyle ? 0 : 3));
        int padding = Ui.dp(getContext(), panelStyle ? 8 : 14),size=Ui.dp(getContext(),panelStyle ? PanelUi.SLOT : 48);
        for (View action : actions) { action.setPadding(padding, padding, padding, padding); header.addView(action, header.indexOfChild(title), new LinearLayout.LayoutParams(size,size)); }
        card.setBackground(Ui.background(getContext(), 0xF2181A1E, 18));
    }
    void onContentHeight(java.util.function.IntConsumer listener) { contentHeightListener = listener; card.requestLayout(); }
    void compactWidth(int maximumDp, float fraction) { maximumWidthDp = maximumDp; widthFraction = fraction; card.requestLayout(); }
    /** Dynamic content updates inside a bounded viewport, never resizing the entering card. */
    void stableViewport(int heightDp) { stableWidth = true; viewportHeightDp = heightDp; card.requestLayout(); }
    void stableWidth() { if (!stableWidth) { stableWidth = true; card.requestLayout(); } }
    void handleWindowBack() { handlesWindowBack = true; }
    /** Folder-only hierarchy: compact text and glyphs, with full-sized header touch targets. */
    void folderStyle(String name, int count, View edit) {
        compactWidth(240, .76f); title.setTextSize(11);
        android.text.SpannableString label = new android.text.SpannableString(name + "  " + count + "/9");
        label.setSpan(new android.text.style.RelativeSizeSpan(.82f), name.length(), label.length(), 0);
        label.setSpan(new android.text.style.ForegroundColorSpan(Ui.MUTED), name.length(), label.length(), 0); title.setText(label);
        header.setPadding(Ui.dp(getContext(), 12), 0, Ui.dp(getContext(), 2), 0);
        header.addView(edit, header.getChildCount() - 1);
        for (int i = 0; i < header.getChildCount(); i++) if (header.getChildAt(i) != title) { View button = header.getChildAt(i); button.setLayoutParams(new LinearLayout.LayoutParams(Ui.dp(getContext(), 48), Ui.dp(getContext(), 48))); int pad = Ui.dp(getContext(), 16); button.setPadding(pad, pad, pad, pad); }
        content.setPadding(Ui.dp(getContext(), 6), 0, Ui.dp(getContext(), 6), Ui.dp(getContext(), 6));
        card.setBackground(Ui.background(getContext(), 0xFA22252B, 18));
    }
    void extraHeader(View view) { header.addView(view, header.getChildCount() - 1); }
    /** Settings opt into their own typography without changing runtime control sheets. */
    void settingsStyle() {
        runtimeTouch = false;
        card.setBackground(Ui.background(getContext(), SettingsUi.SURFACE, 20)); title.setTextSize(20);
        header.setPadding(Ui.dp(getContext(), 14), Ui.dp(getContext(), 6), Ui.dp(getContext(), 4), Ui.dp(getContext(), 6));
        content.setPadding(0, 0, 0, Ui.dp(getContext(), 8));
    }
    void enter(View source) { enter(source, null); }
    void enter(View source, PanelGlassSession glass) {
        cancelEntrance(); setAlpha(0);
        // Capture the button before focus or a later layout can change its position.
        sourceBounds = null;
        if (source != null && source.isAttachedToWindow() && source.getWidth() > 0 && source.getHeight() > 0) {
            int[] position = new int[2]; source.getLocationOnScreen(position);
            sourceBounds = new android.graphics.RectF(position[0], position[1], position[0] + source.getWidth() * source.getScaleX(), position[1] + source.getHeight() * source.getScaleY());
        }
        requestFocus();
        entranceReady = glass == null;
        if (glass != null) { glass.decorate(); glass.whenModalReady(() -> { if (!closing) { entranceReady = true; invalidate(); } }); }
        entrance = () -> {
            if (closing) { cancelEntrance(); return true; }
            // Decoration can replace action containers on global layout. Never draw that intermediate tree.
            if (isLayoutRequested() || card.isLayoutRequested()) return false;
            if (!entranceReady) return true;
            cancelEntrance();
            if (!ValueAnimator.areAnimatorsEnabled()) { setAlpha(1); return true; }
            updateOrigin();
            card.setScaleX(originScale); card.setScaleY(originScale); card.setTranslationX(originX); card.setTranslationY(originY);
            if (panelStyle) { if (motion == null) motion = new DetailSheetMotion(card, this); motion.animateTo(1, 0, 0, true, null); }
            else {
                animate().alpha(1).setDuration(240).setInterpolator(new DecelerateInterpolator(2)).start();
                card.animate().scaleX(1).scaleY(1).translationX(0).translationY(0).setDuration(240).setInterpolator(new DecelerateInterpolator(2)).start();
            }
            return true;
        };
        getViewTreeObserver().addOnPreDrawListener(entrance); invalidate();
    }
    private void updateOrigin() {
        originX = 0; originY = Ui.dp(getContext(), 10); originScale = .92f;
        if (sourceBounds == null) return;
        // Use the untransformed layout even when closing partway through the entrance.
        // Recompute for content that changed size while the sheet was open.
        int[] position = new int[2]; getLocationOnScreen(position);
        originX = sourceBounds.centerX() - position[0] - card.getLeft() - card.getWidth() / 2f;
        originY = sourceBounds.centerY() - position[1] - card.getTop() - card.getHeight() / 2f;
        originScale = Math.min(1, Math.min(sourceBounds.width() / Math.max(1, card.getWidth()), sourceBounds.height() / Math.max(1, card.getHeight())));
    }
    private void cancelEntrance() { if (entrance != null) { getViewTreeObserver().removeOnPreDrawListener(entrance); entrance = null; } }
    void close() {
        if (closing || closeRequest != null && !closeRequest.getAsBoolean()) return; closing = true; cancelEntrance(); animate().cancel(); card.animate().withEndAction(null).cancel();
        if (!ValueAnimator.areAnimatorsEnabled() || getAlpha() == 0) { if (motion != null) motion.cancel(); dismiss.run(); return; }
        updateOrigin();
        if (motion != null) { motion.animateTo(originScale, originX, originY, false, dismiss); return; }
        android.view.animation.AccelerateInterpolator reverse = new android.view.animation.AccelerateInterpolator(2);
        animate().alpha(0).setDuration(240).setInterpolator(reverse).start();
        card.animate().scaleX(originScale).scaleY(originScale).translationX(originX).translationY(originY).setDuration(240).setInterpolator(reverse).withEndAction(dismiss).start();
    }
    @Override protected void onAttachedToWindow() { super.onAttachedToWindow(); if (handlesWindowBack && android.os.Build.VERSION.SDK_INT >= 33) { backDispatcher = findOnBackInvokedDispatcher(); if (backDispatcher != null) { backCallback = this::back; backDispatcher.registerOnBackInvokedCallback(android.window.OnBackInvokedDispatcher.PRIORITY_DEFAULT, backCallback); } } }
    @Override protected void onDetachedFromWindow() { if (android.os.Build.VERSION.SDK_INT >= 33 && backDispatcher != null) { backDispatcher.unregisterOnBackInvokedCallback(backCallback); backDispatcher = null; backCallback = null; } closing = true; cancelEntrance(); if (motion != null) motion.cancel(); animate().cancel(); card.animate().withEndAction(null).cancel(); super.onDetachedFromWindow(); }
    private final class Card extends ViewGroup {
        Card(Context context) { super(context); }
        @Override protected void onMeasure(int widthSpec, int heightSpec) {
            int availableWidth = MeasureSpec.getSize(widthSpec);
            int preferredWidth = Math.max(Ui.dp(getContext(), 192), Math.round(availableWidth * widthFraction));
            int maxWidth = Math.max(1, Math.min(availableWidth - Ui.dp(getContext(), 16), Math.min(preferredWidth, Ui.dp(getContext(), maximumWidthDp))));
            int maxHeight = Math.max(1, MeasureSpec.getSize(heightSpec) - Ui.dp(getContext(), 16));
            if (viewportHeightDp > 0) maxHeight = Math.min(maxHeight, Ui.dp(getContext(), viewportHeightDp));
            int atMostWidth = MeasureSpec.makeMeasureSpec(maxWidth, MeasureSpec.AT_MOST), atMostHeight = MeasureSpec.makeMeasureSpec(maxHeight, MeasureSpec.AT_MOST);
            int natural = 0;
            for (int i = 0; i < getChildCount(); i++) { View child = getChildAt(i); child.measure(atMostWidth, atMostHeight); natural = Math.max(natural, child.getMeasuredWidth()); }
            int width = stableWidth ? maxWidth : Math.min(maxWidth, natural), exactWidth = MeasureSpec.makeMeasureSpec(width, MeasureSpec.EXACTLY);
            header.measure(exactWidth, atMostHeight); int chrome = header.getMeasuredHeight();
            if (footer != null) { footer.measure(exactWidth, MeasureSpec.makeMeasureSpec(Math.max(0, maxHeight - chrome), MeasureSpec.AT_MOST)); chrome += footer.getMeasuredHeight(); }
            if (contentHeightListener != null) contentHeightListener.accept(Math.max(0, maxHeight - chrome - content.getPaddingTop() - content.getPaddingBottom()));
            scroll.measure(exactWidth, MeasureSpec.makeMeasureSpec(Math.max(0, maxHeight - chrome), viewportHeightDp > 0 ? MeasureSpec.EXACTLY : MeasureSpec.AT_MOST));
            setMeasuredDimension(width, viewportHeightDp > 0 ? maxHeight : Math.min(maxHeight, chrome + scroll.getMeasuredHeight()));
        }
        @Override protected void onLayout(boolean changed, int l, int t, int r, int b) {
            int top = header.getMeasuredHeight(); header.layout(0, 0, getMeasuredWidth(), top);
            scroll.layout(0, top, getMeasuredWidth(), top + scroll.getMeasuredHeight());
            if (footer != null) footer.layout(0, top + scroll.getMeasuredHeight(), getMeasuredWidth(), getMeasuredHeight());
        }
    }
}
