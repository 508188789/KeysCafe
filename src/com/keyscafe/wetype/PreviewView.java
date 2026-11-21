package com.keyscafe.wetype;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Shader;
import android.os.SystemClock;
import android.view.MotionEvent;
import android.view.View;

import java.util.ArrayList;

/**
 * 设置页里的迷你键盘预览（3 行、22 个假按键）。
 *
 * <p>它不是「模拟」特效，而是<b>真的跑同一套特效代码</b>：按键对象就是普通的
 * {@link PKey}，{@link Fx} 只管拿它当身份标识和矩形来源，不关心它是真按键还是假按键。
 * 因此这里看到的效果与真机一致——这也是开发期调试的主要手段
 * （在手机上改参数，不用每次都去打字验证）。
 *
 * <p>绘制顺序刻意与真机保持一致：
 * 缩放 → 画键帽 → 在缩放坐标系外叠配色 → 最后画整块键盘的粒子层。
 * 注意配色叠加必须放在 {@code restoreToCount} <b>之后</b>——真机上钩子也是在
 * 输入法画完并恢复 canvas 之后才叠色的，否则会跟着键帽一起缩放。
 */
public class PreviewView extends View {

    private static final String[] ROW0 = {"q", "w", "e", "r", "t", "y", "u", "i", "o", "p"};
    private static final String[] ROW1 = {"a", "s", "d", "f", "g", "h", "j", "k", "l"};

    private final ArrayList<PKey> keys = new ArrayList<>();
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint stroke = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF rf = new RectF();

    /** transformOf 的输出缓冲，复用避免每帧分配 */
    private final float[] tr = new float[3];

    /** 当前按下的预览键 */
    private PKey pressedKey;

    /** 键帽圆角（px），由布局阶段算出，配色层共用同一个值才能贴合 */
    private int corner;

    public PreviewView(Context c) {
        super(c);
        // 硬件加速：粒子和渐变都是 GPU 友好的绘制指令，软件层在中端机上会掉帧
        setLayerType(LAYER_TYPE_HARDWARE, null);
        fill.setStyle(Paint.Style.FILL);
        stroke.setStyle(Paint.Style.STROKE);
        textPaint.setTextAlign(Paint.Align.CENTER);
        buildKeys();
    }

    /** 配置变化时刷新引擎并重绘（设置页每次改动都会调） */
    public void onConfigChanged(FxConfig cfg) {
        Fx.I.setConfig(cfg);
        invalidate();
    }

    private PKey add(String label, boolean wide, boolean icon) {
        PKey k = new PKey();
        k.label = label;
        k.wide = wide;
        k.icon = icon;
        keys.add(k);
        return k;
    }

    /** 固定 3 行：qwerty 首行、次行、功能行 */
    private void buildKeys() {
        keys.clear();
        for (String s : ROW0) add(s, false, false);
        for (String s : ROW1) add(s, false, false);
        add("⌫", false, true);
        add("空格", true, false);
        add("⏎", false, true);
    }

    @Override
    protected void onSizeChanged(int w, int h, int ow, int oh) {
        layoutKeys(w, h);
        super.onSizeChanged(w, h, ow, oh);
    }

    /**
     * 按「10 列网格」排布三行，模拟真实键盘的比例。
     *
     * <p>第二行 9 键居中、第三行左右功能键 1.4 倍宽，都和真机布局一致，
     * 这样预览里看到的特效范围才有参考价值。
     */
    private void layoutKeys(int w, int h) {
        int pad = Ui.dp(getContext(), 8);
        int gap = Ui.dp(getContext(), 5);
        int rows = 3;
        int rowH = (h - pad * 2 - gap * (rows - 1)) / rows;
        int unit = (w - pad * 2 - gap * 9) / 10;   // 10 列
        // 圆角取行高 26%，与微信输入法键帽的观感接近
        corner = Math.max(Ui.dp(getContext(), 5), (int) (rowH * 0.26f));

        int i = 0;
        // 第一行：10 键
        for (int c = 0; c < 10; c++, i++) {
            PKey k = keys.get(i);
            int left = pad + c * (unit + gap);
            k.rect.set(left, pad, left + unit, pad + rowH);
        }
        // 第二行：9 键居中
        int y1 = pad + rowH + gap;
        int startX = pad + (w - pad * 2 - (9 * unit + 8 * gap)) / 2;
        for (int c = 0; c < 9; c++, i++) {
            PKey k = keys.get(i);
            int left = startX + c * (unit + gap);
            k.rect.set(left, y1, left + unit, y1 + rowH);
        }
        // 第三行：⌫ / 空格 / ⏎，两侧功能键宽 1.4 倍
        int y2 = y1 + rowH + gap;
        int sideW = (int) (unit * 1.4f);
        PKey del = keys.get(i++);
        del.rect.set(pad, y2, pad + sideW, y2 + rowH);
        int spL = pad + sideW + gap;
        int spR = w - pad - sideW - gap;
        keys.get(i++).rect.set(spL, y2, spR, y2 + rowH);
        keys.get(i).rect.set(w - pad - sideW, y2, w - pad, y2 + rowH);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        // 推进特效引擎的时钟；真机上这一步发生在键盘 onDraw 之前
        Fx.I.beginFrame(SystemClock.uptimeMillis());

        // 面板背景：竖向渐变 + 描边
        rf.set(1, 1, getWidth() - 1, getHeight() - 1);
        fill.setShader(new LinearGradient(0, 0, 0, getHeight(),
                0xFF151B27, 0xFF0D1119, Shader.TileMode.CLAMP));
        canvas.drawRoundRect(rf, Ui.dp(getContext(), 18), Ui.dp(getContext(), 18), fill);
        fill.setShader(null);
        stroke.setStrokeWidth(Ui.dp(getContext(), 1));
        stroke.setColor(0xFF232C3C);
        canvas.drawRoundRect(rf, Ui.dp(getContext(), 18), Ui.dp(getContext(), 18), stroke);

        for (int i = 0; i < keys.size(); i++) {
            PKey k = keys.get(i);
            // 1) 动效：拿到缩放参数后套在 canvas 上
            Fx.I.transformOf(k, k.rect, tr);
            int save = canvas.save();
            if (tr[0] != 1f || tr[1] != 1f || tr[2] != 0f) {
                canvas.scale(tr[0], tr[1], k.rect.exactCenterX(), k.rect.exactCenterY());
                if (tr[2] != 0f) canvas.translate(0f, tr[2]);
            }
            drawKey(canvas, k);
            canvas.restoreToCount(save);
            // 2) 配色：恢复坐标系之后再叠，因此不会被缩放
            Fx.I.drawKeyOverlay(canvas, k, k.rect, corner);
        }

        // 3) 键周粒子/波纹：整块键盘之上
        Fx.I.drawOverlay(canvas);

        // 还有动画就继续请求下一帧；没有就停下，避免空转耗电
        if (Fx.I.busy()) postInvalidateOnAnimation();
    }

    /** 画一个键帽：渐变底 + 顶部高光 + 描边 + 居中文字 */
    private void drawKey(Canvas canvas, PKey k) {
        rf.set(k.rect.left, k.rect.top, k.rect.right, k.rect.bottom);
        float rad = corner;
        // 键帽底色
        fill.setShader(new LinearGradient(0, rf.top, 0, rf.bottom,
                Palette.KEY_BASE_TOP, Palette.KEY_BASE, Shader.TileMode.CLAMP));
        canvas.drawRoundRect(rf, rad, rad, fill);
        fill.setShader(null);
        // 顶部 1px 高光，模拟键帽的立体边
        stroke.setStrokeWidth(Ui.dp(getContext(), 1));
        stroke.setColor(0x14FFFFFF);
        canvas.drawLine(rf.left + rad * 0.7f, rf.top + 1, rf.right - rad * 0.7f, rf.top + 1, stroke);
        // 边框
        stroke.setColor(0xFF2C3543);
        canvas.drawRoundRect(rf, rad, rad, stroke);

        // 文字：用 FontMetrics 做视觉居中（不是按 baseline 硬算）
        textPaint.setColor(Palette.TEXT);
        textPaint.setTextSize(k.wide ? Ui.sp(getContext(), 11) : Ui.sp(getContext(), 13));
        textPaint.setFakeBoldText(!k.icon);
        Paint.FontMetrics fm = textPaint.getFontMetrics();
        float baseY = rf.centerY() - (fm.ascent + fm.descent) / 2f;
        canvas.drawText(k.label, rf.centerX(), baseY, textPaint);
    }

    /**
     * 触摸：直接复用真机那套逻辑（命中 → onDown；滑出 → onUp + 重新命中；抬起 → onUp）。
     *
     * <p>因此预览里能验证的手感是真手感，包括「按住不放会怎样」。
     */
    @Override
    public boolean onTouchEvent(MotionEvent e) {
        Fx.I.beginFrame(SystemClock.uptimeMillis());
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN: {
                PKey k = hit(e.getX(), e.getY());
                if (k != null) {
                    pressedKey = k;
                    Fx.I.onDown(k, k.rect, e.getX(), e.getY());
                    invalidate();
                }
                return true;
            }
            case MotionEvent.ACTION_MOVE: {
                if (pressedKey != null && !pressedKey.rect.contains((int) e.getX(), (int) e.getY())) {
                    Fx.I.onUp(pressedKey);
                    pressedKey = null;
                    invalidate();
                }
                return true;
            }
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL: {
                if (pressedKey != null) {
                    Fx.I.onUp(pressedKey);
                    pressedKey = null;
                }
                invalidate();
                return true;
            }
        }
        return super.onTouchEvent(e);
    }

    private PKey hit(float x, float y) {
        for (int i = keys.size() - 1; i >= 0; i--) {
            if (keys.get(i).rect.contains((int) x, (int) y)) return keys.get(i);
        }
        return null;
    }

    /** 预览用的假按键：只有文字与矩形，没有输入法那套复杂状态 */
    static final class PKey {
        String label;
        boolean wide, icon;
        final Rect rect = new Rect();
    }
}
