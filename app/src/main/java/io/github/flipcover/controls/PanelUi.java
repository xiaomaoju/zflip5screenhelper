package io.github.flipcover.controls;

import android.content.Context;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.TextView;

/** Explicit runtime-panel metrics. Settings, launcher and system surfaces do not opt in. */
final class PanelUi {
    static final int TITLE=17, BODY=12, SECONDARY=10, ACTION=12, SLOT=36, GAP=4, INSET=8, STRIP=40;
    private PanelUi() { }
    static Button button(Context c,String label,Runnable action) { Button button=Ui.button(c,label,action); action(button); return button; }
    static void action(Button button) { Context c=button.getContext(); button.setTextSize(ACTION); button.setMinHeight(Ui.dp(c,SLOT)); button.setMinimumHeight(Ui.dp(c,SLOT)); button.setPadding(Ui.dp(c,INSET),Ui.dp(c,4),Ui.dp(c,INSET),Ui.dp(c,4)); }
    static ImageButton icon(Context c,int icon,String label,Runnable action) { return Ui.iconButton(c,icon,label,action); }
    static TextView title(Context c,String label) { TextView text=Ui.heading(c,label,TITLE); text.setSingleLine(); text.setEllipsize(android.text.TextUtils.TruncateAt.END); return text; }
    static EditText search(Context c,String hint,String value) {
        EditText input=new EditText(c); input.setSingleLine(); input.setTextSize(BODY); input.setTextColor(Ui.TEXT); input.setHintTextColor(Ui.MUTED); input.setHint(hint); input.setText(value); input.setPadding(Ui.dp(c,INSET),0,Ui.dp(c,INSET),0); input.setMinimumHeight(Ui.dp(c,SLOT)); input.setBackground(Ui.background(c,Ui.SURFACE,1000)); input.setInputType(android.text.InputType.TYPE_CLASS_TEXT | android.text.InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS); return input;
    }
    static LinearLayout row(Context c,int icon,String title,String subtitle,Runnable action) {
        LinearLayout row=Ui.row(c); row.setMinimumHeight(Ui.dp(c,SLOT)); row.setPadding(0,Ui.dp(c,GAP),0,Ui.dp(c,GAP));
        if (icon!=0) { android.widget.ImageView symbol=new android.widget.ImageView(c); symbol.setImageDrawable(Ui.icon(c,icon,Ui.TEXT)); symbol.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO); LinearLayout.LayoutParams size=new LinearLayout.LayoutParams(Ui.dp(c,18),Ui.dp(c,18)); size.setMarginEnd(Ui.dp(c,INSET)); row.addView(symbol,size); }
        LinearLayout words=Ui.column(c); words.addView(Ui.text(c,title,BODY,Ui.TEXT)); if (!subtitle.isEmpty()) { TextView detail=Ui.text(c,subtitle,SECONDARY,Ui.MUTED); detail.setPadding(0,Ui.dp(c,2),0,0); words.addView(detail); } row.addView(words,new LinearLayout.LayoutParams(0,-2,1));
        if (action!=null) { row.setBackground(Ui.ripple(c,0,8)); row.setFocusable(true); row.setOnClickListener(v -> action.run()); } return row;
    }
    static int label(Context c) { return new int[]{9,10,12}[new Prefs(c).panelLabelSize()]; }
}
