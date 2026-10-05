package io.github.flipcover.controls;

import android.content.Context;
import android.view.MenuItem;
import android.view.View;
import android.widget.PopupMenu;

/** Keep application menus in the captured window; native pointer mode keeps the original popup. */
final class InputPopupMenu extends PopupMenu {
    private final View anchor;
    InputPopupMenu(Context context, View anchor) { super(context, anchor); this.anchor = anchor; }
    MenuItem add(CharSequence title) { return getMenu().add(0, getMenu().size() + 1, 0, title); }
    @Override public void show() {
        for (android.view.ViewParent p = anchor.getParent(); p instanceof View; p = p.getParent()) if (p instanceof InputSurface surface && surface.navigating()) { surface.menu(this, anchor); return; }
        super.show();
    }
}
