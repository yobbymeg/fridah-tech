package com.fridah.tech;

import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.graphics.drawable.Drawable;
import android.content.res.ColorStateList;
import android.text.InputType;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;

/** Small helpers for the premium dark/neon UI, all programmatic. */
public class UiKit {

    // palette
    public static final int ACCENT = 0xFF00E5FF;
    public static final int ACCENT2 = 0xFF7C4DFF;
    public static final int PINK = 0xFFFF4D9E;
    public static final int GREEN = 0xFF00E676;
    public static final int RED = 0xFFFF5252;
    public static final int TXT = 0xFFE9EFFF;
    public static final int MUT = 0xFF8DA1C7;
    public static final int DARK = 0xFF04101C;

    public static int dp(Context c, float v) {
        return (int) (v * c.getResources().getDisplayMetrics().density + 0.5f);
    }

    public static GradientDrawable gradient(int c1, int c2, float radiusPx) {
        GradientDrawable g = new GradientDrawable(GradientDrawable.Orientation.TL_BR, new int[]{c1, c2});
        g.setCornerRadius(radiusPx);
        return g;
    }

    public static GradientDrawable panel(Context c, float radius) {
        GradientDrawable g = new GradientDrawable();
        g.setCornerRadius(dp(c, radius));
        g.setColor(0xE610192D);
        g.setStroke(dp(c, 1), 0x2E3D5FA8);
        return g;
    }

    public static RippleDrawable ripple(Drawable content) {
        ColorStateList cl = new ColorStateList(new int[][]{new int[]{0}}, new int[]{0x2EFFFFFF});
        return new RippleDrawable(cl, content, content);
    }

    public static TextView button(Context c, String text, int c1, int c2) {
        TextView t = new TextView(c);
        t.setText(text);
        t.setTextColor(DARK);
        t.setTypeface(Typeface.DEFAULT_BOLD);
        t.setGravity(Gravity.CENTER);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        t.setBackground(ripple(gradient(c1, c2, dp(c, 28))));
        t.setPadding(dp(c, 22), dp(c, 13), dp(c, 22), dp(c, 13));
        return t;
    }

    public static TextView ghostButton(Context c, String text) {
        TextView t = new TextView(c);
        t.setText(text);
        t.setTextColor(ACCENT);
        t.setTypeface(Typeface.DEFAULT_BOLD);
        t.setGravity(Gravity.CENTER);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        GradientDrawable g = new GradientDrawable();
        g.setCornerRadius(dp(c, 28));
        g.setColor(0x1400E5FF);
        g.setStroke(dp(c, 1), 0x5500E5FF);
        t.setBackground(ripple(g));
        t.setPadding(dp(c, 22), dp(c, 11), dp(c, 22), dp(c, 11));
        return t;
    }

    public static EditText field(Context c, String hint) {
        EditText e = new EditText(c);
        e.setHint(hint);
        e.setTextColor(TXT);
        e.setHintTextColor(0x667C8DB0);
        e.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        GradientDrawable g = new GradientDrawable();
        g.setCornerRadius(dp(c, 14));
        g.setColor(0xFF0A1122);
        g.setStroke(dp(c, 1), 0x3E2A3F6E);
        e.setBackground(g);
        e.setPadding(dp(c, 16), dp(c, 13), dp(c, 16), dp(c, 13));
        return e;
    }

    public static EditText phoneField(Context c, String hint) {
        EditText e = field(c, hint);
        e.setInputType(InputType.TYPE_CLASS_PHONE);
        return e;
    }

    public static EditText pinField(Context c, String hint) {
        EditText e = field(c, hint);
        e.setInputType(InputType.TYPE_CLASS_NUMBER);
        e.setTransformationMethod(android.text.method.PasswordTransformationMethod.getInstance());
        e.setMaxLines(1);
        return e;
    }

    public static TextView label(Context c, String text, int color, float sp, boolean bold) {
        TextView t = new TextView(c);
        t.setText(text);
        t.setTextColor(color);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        if (bold) t.setTypeface(Typeface.DEFAULT_BOLD);
        return t;
    }

    public static LinearLayout column(Context c) {
        LinearLayout l = new LinearLayout(c);
        l.setOrientation(LinearLayout.VERTICAL);
        return l;
    }

    public static LinearLayout row(Context c) {
        LinearLayout l = new LinearLayout(c);
        l.setOrientation(LinearLayout.HORIZONTAL);
        l.setGravity(Gravity.CENTER_VERTICAL);
        return l;
    }

    public static FrameLayoutHolder frame(Context c) {
        return new FrameLayoutHolder(c);
    }

    public static class FrameLayoutHolder {
        public final android.widget.FrameLayout v;
        FrameLayoutHolder(Context c) { v = new android.widget.FrameLayout(c); }
    }
}
