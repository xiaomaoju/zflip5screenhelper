package io.github.flipcover.controls;

import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;

/** Fit native widget content without stretching or cropping its fixed-size layout. */
final class WidgetContentLayout {
    record Minimum(int width, int height) { }
    record Fit(float width, float height, float scale) { }
    static Minimum minimum(View view) {
        int width = view.getMinimumWidth(), height = view.getMinimumHeight();
        if (view instanceof ViewGroup group) {
            int childrenWidth = 0, childrenHeight = 0;
            boolean vertical = view instanceof LinearLayout linear && linear.getOrientation() == LinearLayout.VERTICAL;
            boolean horizontal = view instanceof LinearLayout linear && linear.getOrientation() == LinearLayout.HORIZONTAL;
            for (int i = 0; i < group.getChildCount(); i++) {
                View child = group.getChildAt(i); if (child.getVisibility() == View.GONE) continue;
                Minimum minimum = minimum(child); int childWidth = minimum.width(), childHeight = minimum.height();
                if (child.getLayoutParams() instanceof ViewGroup.MarginLayoutParams margins) {
                    childWidth += margins.leftMargin + margins.rightMargin; childHeight += margins.topMargin + margins.bottomMargin;
                }
                childrenWidth = horizontal ? childrenWidth + childWidth : Math.max(childrenWidth, childWidth);
                childrenHeight = vertical ? childrenHeight + childHeight : Math.max(childrenHeight, childHeight);
            }
            width = Math.max(width, childrenWidth + view.getPaddingLeft() + view.getPaddingRight());
            height = Math.max(height, childrenHeight + view.getPaddingTop() + view.getPaddingBottom());
        }
        return new Minimum(width, height);
    }
    static Fit fit(float width, float height, float naturalWidth, float naturalHeight) {
        float scale = Math.min(1, Math.min(width / Math.max(1, naturalWidth), height / Math.max(1, naturalHeight)));
        return new Fit(width / scale, height / scale, scale);
    }
}
