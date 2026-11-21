package com.keyscafe.wetype;

import android.content.Context;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * 设置页的界面小工具：单位换算、圆角背景、文本、卡片、芯片。
 *
 * <p>整个设置页是纯 Java 动态构建的，没有一行 XML 布局文件。这样做的好处是
 * 编译链可以极简（不需要 aapt2 处理复杂布局），也方便在没有 IDE 的环境里改样式。
 */
public final class Ui {

    /** 页面背景（近黑，带一点蓝） */
    public static final int BG = 0xFF0A0D13;
    /** 卡片底部色 */
    public static final int CARD = 0xFF121722;
    /** 卡片顶部色（渐变起点，比底部略亮） */
    public static final int CARD_HI = 0xFF171E2B;
    /** 卡片描边 */
    public static final int STROKE = 0xFF222B3A;
    /** 主文字 */
    public static final int TEXT = 0xFFF2F5FA;
    /** 次要文字 */
    public static final int SUB = 0xFF8892A6;
    /** 强调色（青） */
    public static final int ACCENT = 0xFF00E5FF;
    /** 强调色的低透明版本，用作选中芯片的底色 */
    public static final int ACCENT_DIM = 0x3300E5FF;

    private Ui() {
    }

    /** dp → px（带 0.5 四舍五入，避免小数值被截断导致 1px 线消失） */
    public static int dp(Context c, float v) {
        return (int) (TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v,
                c.getResources().getDisplayMetrics()) + 0.5f);
    }

    /** sp → px（字号，跟随系统字体缩放） */
    public static float sp(Context c, float v) {
        return TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, v, c.getResources().getDisplayMetrics());
    }

    /** 纯色圆角矩形，可选描边 */
    public static GradientDrawable round(int fill, float radius, int stroke, int strokeW) {
        GradientDrawable d = new GradientDrawable();
        d.setShape(GradientDrawable.RECTANGLE);
        d.setColor(fill);
        d.setCornerRadius(radius);
        if (strokeW > 0) d.setStroke(strokeW, stroke);
        return d;
    }

    /** 上下渐变圆角矩形，可选描边 */
    public static GradientDrawable gradient(int top, int bottom, float radius, int stroke, int strokeW) {
        GradientDrawable d = new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, new int[]{top, bottom});
        d.setShape(GradientDrawable.RECTANGLE);
        d.setCornerRadius(radius);
        if (strokeW > 0) d.setStroke(strokeW, stroke);
        return d;
    }

    /** 快速造一个 TextView */
    public static TextView text(Context c, CharSequence s, float sizeSp, int color, boolean bold) {
        TextView t = new TextView(c);
        t.setText(s);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sizeSp);
        t.setTextColor(color);
        if (bold) t.setTypeface(t.getTypeface(), Typeface.BOLD);
        return t;
    }

    /** 一张标准卡片：竖向 LinearLayout + 渐变背景 + 22dp 圆角 + 16dp 内边距 */
    public static LinearLayout card(Context c) {
        LinearLayout l = new LinearLayout(c);
        l.setOrientation(LinearLayout.VERTICAL);
        l.setBackground(gradient(CARD_HI, CARD, dp(c, 22), STROKE, dp(c, 1)));
        int p = dp(c, 16);
        l.setPadding(p, p, p, p);
        return l;
    }

    /** 可点芯片（单选样式） */
    public static TextView chip(Context c, String label, boolean on) {
        TextView t = text(c, label, 13f, on ? ACCENT : SUB, on);
        t.setGravity(Gravity.CENTER);
        int px = dp(c, 13), py = dp(c, 7);
        t.setPadding(px, py, px, py);
        t.setBackground(round(on ? ACCENT_DIM : CARD_HI, dp(c, 11),
                on ? ACCENT : STROKE, dp(c, 1)));
        t.setClickable(true);
        return t;
    }

    /** 切换芯片的选中/未选中外观。只换颜色与背景，不重建 View，因此不闪 */
    public static void setChipState(TextView t, boolean on) {
        Context c = t.getContext();
        t.setTextColor(on ? ACCENT : SUB);
        t.setTypeface(t.getTypeface(), on ? Typeface.BOLD : Typeface.NORMAL);
        t.setBackground(round(on ? ACCENT_DIM : CARD_HI, dp(c, 11), on ? ACCENT : STROKE, dp(c, 1)));
    }

    /** 插一个固定高度的空行（比设 margin 更直观） */
    public static void addGap(LinearLayout parent, int dpVal) {
        View v = new View(parent.getContext());
        v.setLayoutParams(new LinearLayout.LayoutParams(1, dp(parent.getContext(), dpVal)));
        parent.addView(v);
    }

    /** 以 dp 为单位设置内边距 */
    public static void pad(View v, int l, int t, int r, int b) {
        v.setPadding(dp(v.getContext(), l), dp(v.getContext(), t), dp(v.getContext(), r), dp(v.getContext(), b));
    }

    /** 一条 1px 分割线 */
    public static GradientDrawable line() {
        return round(0x1AFFFFFF, 1, 0, 0);
    }

    /** 给颜色套一个 0~1 的透明度 */
    public static int rgba(int color, float alpha) {
        return (color & 0x00FFFFFF) | (((int) (alpha * 255)) << 24);
    }
}
