package com.keyscafe.wetype;

import android.content.Context;
import android.util.TypedValue;
import android.view.View;
import android.view.ViewGroup;

/**
 * 极简流式布局：子 View 从左到右排，一行放不下就换行。
 *
 * <p>用来放芯片（样式是 7 个、主题是 8 个，横排一行肯定放不下）。
 * 不用 {@code FlexboxLayout} 之类的外部依赖，因为整个模块不引任何第三方库——
 * 依赖越少，D8 打出来的 dex 越小，Xposed 加载越快。
 */
public class FlowLayout extends ViewGroup {

    /** 子 View 之间的间距（px），横竖相同 */
    private final int gap;

    public FlowLayout(Context c, int gapPx) {
        super(c);
        this.gap = gapPx;
    }

    /**
     * 测量：按可用宽度逐行试排，累计总高度。
     *
     * <p>注意宽度直接取 {@code MeasureSpec.getSize}，因为父容器给的是
     * {@code MATCH_PARENT}，流式布局的宽度就是它拿到的宽度。
     */
    @Override
    protected void onMeasure(int widthSpec, int heightSpec) {
        int width = MeasureSpec.getSize(widthSpec);
        if (width <= 0) width = getResources().getDisplayMetrics().widthPixels;
        int x = 0, y = 0, lineH = 0;
        for (int i = 0; i < getChildCount(); i++) {
            View ch = getChildAt(i);
            if (ch.getVisibility() == GONE) continue;
            // 子 View 宽度上限就是整行宽度，高度不限
            measureChild(ch, MeasureSpec.makeMeasureSpec(width, MeasureSpec.AT_MOST), heightSpec);
            int w = ch.getMeasuredWidth();
            int h = ch.getMeasuredHeight();
            if (x > 0 && x + w > width) {
                x = 0;
                y += lineH + gap;
                lineH = 0;
            }
            x += w + gap;
            lineH = Math.max(lineH, h);
        }
        // 最后一行的高度别漏掉
        setMeasuredDimension(width, y + lineH);
    }

    /**
     * 摆放：与测量逻辑完全一致地再走一遍。
     *
     * <p>两处逻辑必须严格相同，否则会出现「换行了但位置算错」。
     * 这里没有单独缓存行信息——芯片总量只有十几个，重算的开销可以忽略。
     */
    @Override
    protected void onLayout(boolean changed, int l, int t, int r, int b) {
        int width = r - l;
        int x = 0, y = 0, lineH = 0;
        for (int i = 0; i < getChildCount(); i++) {
            View ch = getChildAt(i);
            if (ch.getVisibility() == GONE) continue;
            int w = ch.getMeasuredWidth();
            int h = ch.getMeasuredHeight();
            if (x > 0 && x + w > width) {
                x = 0;
                y += lineH + gap;
                lineH = 0;
            }
            ch.layout(x, y, x + w, y + h);
            x += w + gap;
            lineH = Math.max(lineH, h);
        }
    }

    static int dp(Context c, float v) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, c.getResources().getDisplayMetrics());
    }
}
