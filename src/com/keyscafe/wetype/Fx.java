package com.keyscafe.wetype;

import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RadialGradient;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Shader;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.Random;

/**
 * 特效引擎：把「当前时间 + 按键状态」算成一堆要画的图形。
 *
 * <h2>设计约束</h2>
 * 只依赖 {@code android.graphics}，<b>不引用微信输入法的任何类</b>。好处有两个：
 * <ul>
 *   <li>设置页的预览（{@link PreviewView}）和真机钩子（{@link Module}）跑的是同一份代码，
 *       所见即所得，不存在「预览好看、真机不一样」；</li>
 *   <li>目标应用升级换了包名/类名，这个文件一行都不用改。</li>
 * </ul>
 *
 * <p>坐标系是<b>键盘 View 的本地坐标</b>——传进来的按键 {@link Rect} 用的就是这套坐标，
 * 所以我们画的东西天然和按键对齐，不需要任何换算。
 *
 * <h2>三类特效对应关系</h2>
 * <table border="1">
 *   <tr><th>类别</th><th>入口</th><th>画在哪</th></tr>
 *   <tr><td>Key Motion（按键动效）</td><td>{@link #transformOf}</td>
 *       <td>返回缩放参数，由调用方套 canvas.scale</td></tr>
 *   <tr><td>Key Color（按键配色）</td><td>{@link #drawKeyOverlay}</td><td>键帽之上</td></tr>
 *   <tr><td>Keyboard Color（键周特效）</td><td>{@link #drawOverlay}</td><td>整个键盘之上</td></tr>
 * </table>
 *
 * <h2>性能</h2>
 * 键盘每帧都会重画所有按键，所以这里必须做到「零分配」才不掉帧：
 * Paint / Path / RectF 全部复用字段；渐变 Shader 按按键缓存；粒子数有硬上限。
 * 动画结束后 {@link #busy()} 返回 false，调用方就不再 invalidate，空闲时完全不耗电。
 */
public final class Fx {

    /** 全局单例。输入法进程里只有一个键盘，模块进程里只有一个预览，够用 */
    public static final Fx I = new Fx();

    /** 当前配置（由 {@link Module} 或 {@link PreviewView} 注入） */
    private FxConfig cfg = new FxConfig();

    /** 飞行中的粒子（溅射样式） */
    private final ArrayList<Particle> parts = new ArrayList<>();

    /** 扩散中的波纹/光晕/星点 */
    private final ArrayList<Wave> waves = new ArrayList<>();

    /** 每个按键的按下状态与时间戳 */
    private final IdentityHashMap<Object, KeyAnim> anims = new IdentityHashMap<>();

    /** 每个按键的渐变 Shader 缓存（避免每帧 new 几十个 Shader 触发 GC） */
    private final IdentityHashMap<Object, ShaderCache> shaders = new IdentityHashMap<>();

    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint stroke = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path clipPath = new Path();
    private final Path starPath = new Path();
    private final RectF rf = new RectF();
    private final Random rnd = new Random();

    /** 粒子上限：再多了纯属浪费，中端机上会开始掉帧 */
    private static final int MAX_PARTS = 160;

    /** 当前帧时间（uptimeMillis），每帧由 {@link #beginFrame} 更新 */
    private long now;

    /** 上次清理过期数据的时间 */
    private long lastPrune;

    private Fx() {
        stroke.setStyle(Paint.Style.STROKE);
        stroke.setStrokeCap(Paint.Cap.ROUND);
    }

    /**
     * 换配置。
     *
     * <p>两件事必须做：总开关从开变关时清空所有动画（否则残留粒子会一直飘），
     * 以及清空 Shader 缓存（颜色可能变了）。
     */
    public void setConfig(FxConfig c) {
        if (c == null) return;
        boolean wasOn = cfg.master;
        cfg = c;
        if (wasOn && !c.master) clear();
        shaders.clear();
    }

    public FxConfig config() {
        return cfg;
    }

    /** 清空所有运行中的特效（关总开关、切换页面时用） */
    public void clear() {
        parts.clear();
        waves.clear();
        anims.clear();
        shaders.clear();
    }

    // ------------------------------------------------------------------ 帧

    /**
     * 推进时钟。每帧开头调用一次。
     *
     * <p>顺手做低频清理（700ms 一次）：清掉已经播完的粒子/波纹和早就松手的按键记录，
     * 否则 {@link #anims} 会随着按键对象不断变化而无限增长。
     */
    public void beginFrame(long uptimeMs) {
        now = uptimeMs;
        if (now - lastPrune > 700) {
            lastPrune = now;
            prune();
        }
    }

    /** 清理过期数据；用倒序删除避免下标错位 */
    private void prune() {
        for (int i = parts.size() - 1; i >= 0; i--) {
            if (now - parts.get(i).t0 > parts.get(i).life) parts.remove(i);
        }
        for (int i = waves.size() - 1; i >= 0; i--) {
            if (now - waves.get(i).t0 > waves.get(i).life) waves.remove(i);
        }
        if (!anims.isEmpty()) {
            Iterator<Map.Entry<Object, KeyAnim>> it = anims.entrySet().iterator();
            while (it.hasNext()) {
                KeyAnim a = it.next().getValue();
                // 松手 900ms 后仍无人引用，说明这个按键对象已经废弃（键盘重排）
                if (!a.pressed && now - a.releaseT > 900) it.remove();
            }
        }
        if (shaders.size() > 256) shaders.clear();
    }

    /**
     * 是否还有动画需要继续刷新——调用方据此决定要不要 invalidate 下一帧。
     *
     * <p>这是「空闲零开销」的开关：没有粒子、没有波纹、所有按键都静止时返回 false，
     * 帧循环就此停下。
     */
    public boolean busy() {
        if (!parts.isEmpty() || !waves.isEmpty()) return true;
        for (KeyAnim a : anims.values()) {
            if (a.pressed || now - a.releaseT < 900) return true;
        }
        return false;
    }

    // ------------------------------------------------------------------ 输入

    /**
     * 按下某个键：记录时间戳，并按当前样式生成键周特效。
     *
     * @param key 按键对象（只当身份标识用，不读写它的任何字段）
     * @param r   该按键的绘制矩形
     * @param x   触点坐标（溅射粒子从这里出发，而非按键中心）
     * @param y   同上
     */
    public void onDown(Object key, Rect r, float x, float y) {
        if (!cfg.master || key == null || r == null || r.isEmpty()) return;
        KeyAnim a = anims.get(key);
        if (a == null) {
            a = new KeyAnim();
            // 每个键一个固定随机种子：同一个键每次按下的配色保持一致，看起来才是「这个键的颜色」
            a.seed = rnd.nextInt(997);
            anims.put(key, a);
        }
        a.pressed = true;
        a.pressT = now;
        a.releaseT = 0;
        a.fadeFrom = 1f;
        if (cfg.kcOn) spawn(key, r, x, y);
    }

    /** 松开：只记录时间，真正的衰减在 {@link #levelOf} / {@link #transformOf} 里算 */
    public void onUp(Object key) {
        KeyAnim a = anims.get(key);
        if (a == null || !a.pressed) return;
        a.pressed = false;
        a.releaseT = now;
        a.fadeFrom = 1f;
    }

    // ------------------------------------------------------------------ 缩放

    /**
     * 计算按键当前的缩放/位移，供调用方套在 canvas 上。
     *
     * @param out 输出长度 3 的数组：{@code {scaleX, scaleY, translateY(px)}}
     *
     * <p>五套曲线（{@code cfg.kmStyle}）：
     * <ul>
     *   <li>0 收缩 —— 按下匀速缩小，松手回弹；</li>
     *   <li>1 放大 —— 按下略微放大，松手缩回；</li>
     *   <li>2 弹跳 —— 按下缩小，松手用 easeOutBack 过冲回弹（默认样式，最接近 Keys Cafe）；</li>
     *   <li>3 果冻 —— 横竖反向形变，松手做阻尼振荡；</li>
     *   <li>4 下沉 —— 轻微缩小 + 整体下移，像真的按进去。</li>
     * </ul>
     *
     * <p>三个时间参数：按下动画 {@code pressMs}、松手动画 {@code relMs}，都随
     * {@code cfg.kmDur} 线性放大；形变幅度 {@code d} 随 {@code cfg.kmStrength} 线性放大。
     */
    public void transformOf(Object key, Rect r, float[] out) {
        out[0] = 1f;
        out[1] = 1f;
        out[2] = 0f;
        if (!cfg.master || !cfg.kmOn) return;
        KeyAnim a = anims.get(key);
        if (a == null) return;   // 这个键从没被按过，不用算

        float d = 0.016f * cfg.kmStrength;      // 基础形变幅度
        float pressMs = 40f + cfg.kmDur * 10f;  // 按下动画时长
        float relMs = 130f + cfg.kmDur * 30f;   // 松手动画时长
        float sx = 1f, sy = 1f, ty = 0f;

        if (a.pressed) {
            // ease-out-cubic：一开始快、接近终点变慢，按下去的手感最自然
            float e = 1f - (float) Math.pow(1f - clamp01((now - a.pressT) / pressMs), 3);
            switch (cfg.kmStyle) {
                case 0:
                    sx = sy = 1f - d * e;
                    break;
                case 1:
                    sx = sy = 1f + d * 0.85f * e;
                    break;
                case 2:
                    sx = sy = 1f - d * e;
                    break;
                case 3:
                    // 横竖反向：横向变宽的同时纵向变扁，才有「果冻」感
                    sx = 1f + d * 0.62f * e;
                    sy = 1f - d * 0.52f * e;
                    break;
                default:
                    sx = sy = 1f - d * 0.42f * e;
                    ty = d * 0.6f * r.height() * e;
                    break;
            }
        } else if (a.releaseT > 0) {
            float p = clamp01((now - a.releaseT) / relMs);
            switch (cfg.kmStyle) {
                case 0:
                    sx = sy = (1f - d) + d * easeOutCubic(p);
                    break;
                case 1: {
                    float from = 1f + d * 0.85f;
                    sx = sy = from - d * 0.85f * easeOutCubic(p);
                    break;
                }
                case 2: {
                    // 从「按下时的缩小态」用 easeOutBack 回到 1，会先冲过头再回来 = 弹一下
                    float from = 1f - d;
                    sx = sy = from + (1f - from) * easeOutBack(p, 2.6f);
                    break;
                }
                case 3: {
                    // 阻尼正弦振荡，振幅随 (1-p)^2.2 衰减
                    float osc = (float) (Math.sin(p * Math.PI * 3f) * Math.pow(1f - p, 2.2));
                    sx = 1f + d * 0.62f * osc;
                    sy = 1f - d * 0.52f * osc;
                    break;
                }
                default: {
                    float e = easeOutCubic(p);
                    sx = sy = (1f - d * 0.42f) + d * 0.42f * e;
                    ty = d * 0.6f * r.height() * (1f - e);
                    break;
                }
            }
        }
        out[0] = sx;
        out[1] = sy;
        out[2] = ty;
    }

    /** 三次缓出：起步快、结尾平缓 */
    private static float easeOutCubic(float t) {
        float u = 1f - t;
        return 1f - u * u * u;
    }

    /** 回弹缓动：会冲过终点再回退，{@code c1} 越大冲得越猛 */
    private static float easeOutBack(float t, float c1) {
        float c3 = c1 + 1f;
        float u = t - 1f;
        return 1f + c3 * u * u * u + c1 * u * u;
    }

    private static float clamp01(float v) {
        return v < 0f ? 0f : (v > 1f ? 1f : v);
    }

    // ------------------------------------------------------------------ 按键内部配色

    /**
     * 按键配色的「强度」，0~1。
     *
     * <p>只在按下时着色（默认）时：按下用 70ms 淡入，松手用 220ms 淡出。
     * 这个渐隐是必要的——直接开/关会闪，看起来像 bug。
     */
    public float levelOf(Object key) {
        if (!cfg.master || !cfg.klOn) return 0f;
        if (!cfg.klPressedOnly) return 1f;   // 常亮模式：所有键一直着色
        KeyAnim a = anims.get(key);
        if (a == null) return 0f;
        if (a.pressed) return clamp01((now - a.pressT) / 70f);
        float p = clamp01((now - a.releaseT) / 220f);
        return (1f - p) * a.fadeFrom;
    }

    /**
     * 在键帽之上叠一层配色/图案（Key Color）。
     *
     * <p>必须在输入法画完这个按键<b>之后</b>调用，否则会被键帽盖住。
     *
     * <p>做法：先用圆角路径 clip 出键帽轮廓，再在里面填色/画图案，
     * 这样即使图案画到了边上也不会溢出圆角。
     *
     * @param corner 圆角半径；传 0 表示按矩形短边的 28% 自动估算（真机上用这个）
     */
    public void drawKeyOverlay(Canvas canvas, Object key, Rect r, float corner) {
        float lv = levelOf(key);
        if (lv <= 0.01f || r.isEmpty()) return;
        int baseColor = Palette.base(cfg.klColor, cfg.kcTheme);
        int a = (int) (cfg.klAlpha / 100f * 255f * lv);   // 用户不透明度 × 当前强度
        if (a <= 2) return;

        // 内缩 1.5px：避免和键帽边框重叠出现一条硬边
        float inset = 1.5f;
        rf.set(r.left + inset, r.top + inset, r.right - inset, r.bottom - inset);
        if (rf.width() <= 0 || rf.height() <= 0) return;
        float c = corner <= 0 ? Math.min(rf.width(), rf.height()) * 0.28f : corner;
        c = Math.min(c, Math.min(rf.width(), rf.height()) / 2f);

        int save = canvas.save();
        clipPath.reset();
        clipPath.addRoundRect(rf, c, c, Path.Direction.CW);
        canvas.clipPath(clipPath);

        // 每次进来先把画笔状态复位，防止上一个样式留下的 shader/alpha 影响这一次
        paint.setStyle(Paint.Style.FILL);
        paint.setAlpha(255);
        paint.setShader(null);
        stroke.setAlpha(255);
        stroke.setShader(null);

        switch (cfg.klStyle) {
            case 0: // 纯色
                paint.setColor(Palette.alpha(baseColor, a));
                canvas.drawRect(rf, paint);
                break;

            case 1: // 渐变：左上亮、右下暗
                paint.setShader(shaderFor(key, 1, baseColor));
                paint.setAlpha(a);
                canvas.drawRect(rf, paint);
                paint.setShader(null);
                break;

            case 2: { // 流光：一条高光从左扫到右
                paint.setColor(Palette.alpha(baseColor, (int) (a * 0.55f)));
                canvas.drawRect(rf, paint);
                KeyAnim an = anims.get(key);
                float prog = 1f;
                if (an != null) {
                    // 按下时光带跑一遍，松手再跑一遍，形成「点亮」效果
                    if (an.pressed) prog = clamp01((now - an.pressT) / 320f);
                    else prog = clamp01((now - an.releaseT) / 320f);
                }
                float span = rf.width() * 0.55f;
                float x = rf.left - span + (rf.width() + span * 2) * prog;
                // 白→白→透明，两端透明中间实，看起来才像一道光
                paint.setShader(new LinearGradient(x, rf.bottom, x + span, rf.top,
                        new int[]{0x00FFFFFF, 0xFFFFFFFF, 0x00FFFFFF},
                        new float[]{0f, 0.5f, 1f}, Shader.TileMode.CLAMP));
                paint.setAlpha(Math.min(255, (int) (a * 1.6f)));
                canvas.drawRect(rf, paint);
                paint.setShader(null);
                paint.setAlpha(255);
                break;
            }

            case 3: { // 斜纹：45° 平行线
                paint.setColor(Palette.alpha(baseColor, (int) (a * 0.45f)));
                canvas.drawRect(rf, paint);
                stroke.setColor(Palette.alpha(Palette.lighten(baseColor, 0.3f), a));
                stroke.setStrokeWidth(Math.max(1.5f, rf.height() * 0.055f));
                float step = rf.height() * 0.42f;
                // 起点从左侧外面开始，保证左下角也被覆盖
                for (float x = rf.left - rf.height(); x < rf.right + rf.height(); x += step) {
                    canvas.drawLine(x, rf.bottom, x + rf.height(), rf.top, stroke);
                }
                break;
            }

            case 4: { // 点阵
                paint.setColor(Palette.alpha(baseColor, (int) (a * 0.35f)));
                canvas.drawRect(rf, paint);
                paint.setColor(Palette.alpha(Palette.lighten(baseColor, 0.45f), a));
                float gap = Math.max(6f, rf.width() / 6f);   // 间距下限 6px，否则小键会糊成一片
                float rad = Math.max(1.2f, gap * 0.14f);
                for (float y = rf.top + gap * 0.5f; y < rf.bottom; y += gap) {
                    for (float x = rf.left + gap * 0.5f; x < rf.right; x += gap) {
                        canvas.drawCircle(x, y, rad, paint);
                    }
                }
                break;
            }

            case 5: { // 霓虹边：外粗内亮两条描边
                stroke.setColor(Palette.alpha(baseColor, (int) (a * 0.55f)));
                stroke.setStrokeWidth(Math.max(5f, rf.height() * 0.16f));
                // 描边是居中的，半径要减去半个线宽，光晕才不会跑到圆角外面
                float cc = Math.max(1f, c - stroke.getStrokeWidth() / 2f);
                canvas.drawRoundRect(rf, cc, cc, stroke);
                stroke.setColor(Palette.alpha(Palette.lighten(baseColor, 0.55f), a));
                stroke.setStrokeWidth(Math.max(1.5f, rf.height() * 0.045f));
                canvas.drawRoundRect(rf, cc, cc, stroke);
                break;
            }

            case 6: // 彩虹：固定五色渐变，与主题无关
                paint.setShader(shaderFor(key, 6, baseColor));
                paint.setAlpha(a);
                canvas.drawRect(rf, paint);
                paint.setShader(null);
                break;
        }
        canvas.restoreToCount(save);
    }

    /**
     * 渐变着色器按按键缓存。
     *
     * <p>不用缓存的话，每个按下的键每帧都要 new 一个 LinearGradient，键盘一帧几十个键，
     * GC 压力会直接变成掉帧。缓存键包含「样式 + 颜色 + 矩形四边」，因为键位可能随
     * 横竖屏 / 键盘高度变化，矩形变了必须重建。
     */
    private Shader shaderFor(Object key, int style, int color) {
        ShaderCache sc = shaders.get(key);
        if (sc != null && sc.style == style && sc.color == color
                && sc.l == (int) rf.left && sc.t == (int) rf.top
                && sc.r == (int) rf.right && sc.b == (int) rf.bottom) {
            return sc.shader;
        }
        Shader sh;
        if (style == 6) {
            // 彩虹：五色等距分布
            sh = new LinearGradient(rf.left, rf.bottom, rf.right, rf.top,
                    new int[]{0xFFFF5252, 0xFFFFD740, 0xFF69F0AE, 0xFF40C4FF, 0xFFB388FF},
                    null, Shader.TileMode.CLAMP);
        } else {
            // 渐变：基色提亮 38% → 压暗 55%
            sh = new LinearGradient(rf.left, rf.top, rf.right, rf.bottom,
                    Palette.lighten(color, 0.38f), Palette.lighten(color, -0.55f),
                    Shader.TileMode.CLAMP);
        }
        if (sc == null) {
            sc = new ShaderCache();
            shaders.put(key, sc);
        }
        sc.style = style;
        sc.color = color;
        sc.l = (int) rf.left;
        sc.t = (int) rf.top;
        sc.r = (int) rf.right;
        sc.b = (int) rf.bottom;
        sc.shader = sh;
        return sh;
    }

    // ------------------------------------------------------------------ 按键周围特效

    /**
     * 生成键周特效（Keyboard Color）。五种样式共用这里的分发。
     *
     * <p>关键细节：
     * <ul>
     *   <li>溅射的粒子从<b>触点</b>出发，不是从按键中心——按边缘和按中心看起来应该不一样；</li>
     *   <li>随机种子带 {@code key.seed}，同一个键每次按下的配色稳定，不同键之间有差异；</li>
     *   <li>波纹若开启 clip，则只画在键帽轮廓内部（相当于「按键内泛光」）。</li>
     * </ul>
     */
    private void spawn(Object key, Rect r, float x, float y) {
        int dens = cfg.kcDensity;
        int sizeP = cfg.kcSize;
        float speed = 0.35f + cfg.kcSpeed * 0.13f;
        int theme = cfg.kcTheme;
        KeyAnim a = anims.get(key);
        int seed = a == null ? 0 : a.seed;

        float cx = r.exactCenterX();
        float cy = r.exactCenterY();
        // 触点钳制在按键内：手指可能已经滑出边缘才触发 DOWN
        float tx = Math.max(r.left + 1, Math.min(r.right - 1, x));
        float ty = Math.max(r.top + 1, Math.min(r.bottom - 1, y));

        switch (cfg.kcStyle) {
            case 0: { // 溅射：一圈粒子 + 一个中心亮点
                int n = 4 + dens * 2;
                for (int i = 0; i < n; i++) {
                    float ang = (float) (rnd.nextFloat() * Math.PI * 2f);
                    float dx = tx - cx, dy = ty - cy;
                    if (dx * dx + dy * dy > 4f) {
                        // 触点偏离中心时，粒子以「从中心指向触点」的方向为主轴散射
                        float base = (float) Math.atan2(dy, dx);
                        ang = base + (rnd.nextFloat() - 0.5f) * 2.4f;
                    }
                    // 初速正比于按键宽度，小键和大键的溅射范围才协调
                    float sp = (0.06f + rnd.nextFloat() * 0.16f) * speed * r.width() * 2.2f;
                    Particle p = new Particle();
                    p.x = tx;
                    p.y = ty;
                    p.vx = (float) Math.cos(ang) * sp;
                    p.vy = (float) Math.sin(ang) * sp;
                    p.gy = r.height() * 2.4f;   // 重力，让粒子有下坠感
                    p.t0 = now;
                    p.life = 360 + rnd.nextInt(320) + cfg.kcSpeed * 14;
                    p.size = 1.2f + rnd.nextFloat() * sizeP * 0.55f;
                    p.color = Palette.pick(theme, i + seed, rnd.nextFloat() - 0.5f);
                    p.shape = rnd.nextInt(3);   // 0 圆点 1 碎片 2 星芒
                    p.rot = rnd.nextFloat() * 360f;
                    parts.add(p);
                }
                Wave w = new Wave();
                w.kind = 2;
                w.cx = tx;
                w.cy = ty;
                w.r1 = r.width() * (0.25f + sizeP * 0.05f);
                w.t0 = now;
                w.life = 260;
                w.color = Palette.pick(theme, 1, 0f);
                w.rect.set(r);
                w.clip = cfg.kcClip;
                waves.add(w);
                break;
            }
            case 1: { // 涟漪：2~3 圈由中心向外扩散的圆环，彼此错开 70ms
                int n = cfg.kcClip ? 2 : 3;
                for (int i = 0; i < n; i++) {
                    Wave w = new Wave();
                    w.kind = 0;
                    w.cx = cx;
                    w.cy = cy;
                    w.r0 = r.height() * 0.18f;
                    w.r1 = Math.max(r.width(), r.height()) * (0.55f + i * 0.22f) * (0.8f + speed * 0.3f);
                    w.t0 = now - i * 70L;   // 负偏移 = 第 2、3 圈一开始就已扩散了一段
                    w.life = 380 + cfg.kcSpeed * 26;
                    w.color = Palette.pick(theme, i, 0f);
                    w.strokeW = Math.max(1.6f, r.height() * 0.06f * (1f + sizeP * 0.05f));
                    w.rect.set(r);
                    w.clip = cfg.kcClip;
                    waves.add(w);
                }
                break;
            }
            case 2: { // 星点：按键内随机冒出的小星星，逐个延迟出现
                int n = 3 + dens;
                for (int i = 0; i < n; i++) {
                    Wave w = new Wave();
                    w.kind = 3;
                    w.cx = r.left + rnd.nextFloat() * r.width();
                    w.cy = r.top + rnd.nextFloat() * r.height();
                    w.r0 = (2f + rnd.nextFloat() * sizeP) * 1.6f;
                    w.t0 = now + i * 26L;   // 正偏移 = 未来才出现，形成「接连闪出」
                    w.life = 380 + rnd.nextInt(280);
                    w.color = Palette.pick(theme, i + seed, rnd.nextFloat() - 0.5f);
                    w.rect.set(r);
                    waves.add(w);
                }
                break;
            }
            case 3: { // 光晕：一团径向渐变的柔光
                Wave w = new Wave();
                w.kind = 1;
                w.cx = cx;
                w.cy = cy;
                w.r1 = Math.max(r.width(), r.height()) * (0.85f + sizeP * 0.05f);
                w.t0 = now;
                w.life = 340 + cfg.kcSpeed * 20;
                w.color = Palette.pick(theme, 0, 0f);
                w.rect.set(r);
                w.clip = cfg.kcClip;
                waves.add(w);
                break;
            }
            default: { // 波纹填充：从触点开始把整个键「灌满」的渐变
                Wave w = new Wave();
                w.kind = 4;
                w.cx = tx;
                w.cy = ty;
                w.r1 = Math.max(r.width(), r.height()) * 1.15f;
                w.t0 = now;
                w.life = 420 + cfg.kcSpeed * 24;
                w.color = Palette.pick(theme, 2, 0f);
                w.rect.set(r);
                w.clip = cfg.kcClip;
                waves.add(w);
                break;
            }
        }
        // 硬上限：连续快速打字时粒子会堆积，超出就从最老的开始丢
        while (parts.size() > MAX_PARTS) parts.remove(0);
    }

    /**
     * 键盘 {@code draw()} 之后调用，把粒子/波纹画在同一个 canvas 上。
     *
     * <p>顺序：先波纹后粒子——粒子在最上层，看起来才是「溅起来」的。
     */
    public void drawOverlay(Canvas canvas) {
        if (!cfg.master || !cfg.kcOn) return;
        if (parts.isEmpty() && waves.isEmpty()) return;

        for (int i = 0, n = waves.size(); i < n; i++) {
            Wave w = waves.get(i);
            float p = (now - w.t0) / (float) w.life;
            if (p >= 0f && p <= 1f) drawWave(canvas, w, p);
        }
        for (int i = 0, n = parts.size(); i < n; i++) {
            drawParticle(canvas, parts.get(i));
        }
    }

    /**
     * 画一个波纹/光晕/星点。
     *
     * @param p 进度 0~1（已由调用方校验在范围内）
     *
     * <p>需要 clip 时按按键圆角做裁剪，让特效看起来是「从键帽里透出来的」。
     */
    private void drawWave(Canvas canvas, Wave w, float p) {
        int save = 0;
        boolean clipped = false;
        if (w.clip && !w.rect.isEmpty()) {
            save = canvas.save();
            float c = Math.min(w.rect.width(), w.rect.height()) * 0.28f;
            clipPath.reset();
            rf.set(w.rect.left + 1f, w.rect.top + 1f, w.rect.right - 1f, w.rect.bottom - 1f);
            clipPath.addRoundRect(rf, c, c, Path.Direction.CW);
            canvas.clipPath(clipPath);
            clipped = true;
        }
        paint.setShader(null);
        paint.setAlpha(255);
        paint.setStyle(Paint.Style.FILL);

        switch (w.kind) {
            case 0: { // 涟漪环：半径缓出扩张，透明度按 (1-p)^1.5 衰减
                float e = easeOutCubic(p);
                float radius = w.r0 + (w.r1 - w.r0) * e;
                int a = (int) (255 * Math.pow(1f - p, 1.5f));
                stroke.setShader(null);
                stroke.setStrokeWidth(w.strokeW);
                stroke.setColor(Palette.alpha(Palette.lighten(w.color, 0.25f), a));
                canvas.drawCircle(w.cx, w.cy, radius, stroke);
                break;
            }
            case 1: { // 光晕：径向渐变从中心向外淡出
                float radius = Math.max(1f, w.r1 * easeOutCubic(p));
                int a = (int) (150 * Math.pow(1f - p, 2f));
                paint.setShader(new RadialGradient(w.cx, w.cy, radius,
                        new int[]{Palette.alpha(w.color, 255), Palette.alpha(w.color, 110), Palette.alpha(w.color, 0)},
                        new float[]{0f, 0.55f, 1f}, Shader.TileMode.CLAMP));
                paint.setAlpha(a);
                canvas.drawCircle(w.cx, w.cy, radius, paint);
                paint.setShader(null);
                paint.setAlpha(255);
                break;
            }
            case 2: { // 溅射的中心亮点：白心光斑
                float radius = Math.max(1f, w.r1 * easeOutCubic(p));
                int a = (int) (200 * Math.pow(1f - p, 2.2f));
                paint.setShader(new RadialGradient(w.cx, w.cy, radius,
                        new int[]{0xFFFFFFFF, Palette.alpha(w.color, 190), Palette.alpha(w.color, 0)},
                        new float[]{0f, 0.4f, 1f}, Shader.TileMode.CLAMP));
                paint.setAlpha(a);
                canvas.drawCircle(w.cx, w.cy, radius, paint);
                paint.setShader(null);
                paint.setAlpha(255);
                break;
            }
            case 3: { // 星点：sin 曲线控制大小与透明度，一亮一灭
                float tw = (float) Math.sin(p * Math.PI);
                paint.setColor(Palette.alpha(w.color, (int) (255 * tw)));
                star(w.cx, w.cy, w.r0 * tw * 1.9f, w.r0 * tw * 0.5f, p * 220f);
                canvas.drawPath(starPath, paint);
                break;
            }
            default: { // 波纹填充：从触点向外的实心径向渐变
                float radius = Math.max(1f, w.r1 * easeOutCubic(p));
                int a = (int) (170 * Math.pow(1f - p, 1.4f));
                paint.setShader(new RadialGradient(w.cx, w.cy, radius,
                        new int[]{Palette.alpha(w.color, 255), Palette.alpha(Palette.lighten(w.color, 0.4f), 200),
                                Palette.alpha(w.color, 0)},
                        new float[]{0f, 0.72f, 1f}, Shader.TileMode.CLAMP));
                paint.setAlpha(a);
                canvas.drawCircle(w.cx, w.cy, radius, paint);
                paint.setShader(null);
                paint.setAlpha(255);
                break;
            }
        }
        if (clipped) canvas.restoreToCount(save);
    }

    /**
     * 生成八芒星路径到 {@link #starPath}。
     *
     * <p>复用同一个 Path 对象（而不是每次 new），因为粒子每帧都要画几十个。
     *
     * @param outer 外顶点半径
     * @param inner 内顶点半径（决定尖角的锐度）
     * @param rot   旋转角度
     */
    private void star(float cx, float cy, float outer, float inner, float rot) {
        starPath.reset();
        for (int i = 0; i < 8; i++) {
            double ang = Math.toRadians(rot + i * 45);
            float r = (i % 2 == 0) ? outer : inner;
            float px = cx + (float) Math.cos(ang) * r;
            float py = cy + (float) Math.sin(ang) * r;
            if (i == 0) starPath.moveTo(px, py);
            else starPath.lineTo(px, py);
        }
        starPath.close();
    }

    /**
     * 画一个粒子：简单的抛体运动 {@code y = y0 + vy·t + ½·g·t²}。
     *
     * <p>粒子本身不存位置，每次按发射时刻和速度实时算——省内存，也不会有累积误差。
     */
    private void drawParticle(Canvas canvas, Particle p) {
        float lifeP = (now - p.t0) / (float) p.life;
        if (lifeP < 0f || lifeP > 1f) return;
        float t = (now - p.t0) / 1000f;
        float x = p.x + p.vx * t;
        float y = p.y + p.vy * t + 0.5f * p.gy * t * t;
        // 透明度按 (1-p)^2 衰减：尾巴收得干净，不会留下拖影
        float a = (1f - lifeP);
        a = a * a * 255f;
        if (a < 3) return;
        paint.setStyle(Paint.Style.FILL);
        paint.setShader(null);
        paint.setAlpha(255);
        float r = p.size * (1f - lifeP * 0.55f);   // 边飞边缩

        switch (p.shape) {
            case 0: // 圆点：外圈实色 + 内圈高光
                paint.setColor(Palette.alpha(p.color, (int) a));
                canvas.drawCircle(x, y, r, paint);
                paint.setColor(Palette.alpha(Palette.lighten(p.color, 0.6f), (int) (a * 0.85f)));
                canvas.drawCircle(x, y, r * 0.45f, paint);
                break;
            case 1: // 碎片：自旋的小矩形
                paint.setColor(Palette.alpha(p.color, (int) a));
                canvas.save();
                canvas.rotate(p.rot + (now - p.t0) * 0.25f, x, y);
                canvas.drawRect(x - r, y - r * 0.45f, x + r, y + r * 0.45f, paint);
                canvas.restore();
                break;
            default: // 星芒：自旋的八芒星
                paint.setColor(Palette.alpha(p.color, (int) a));
                star(x, y, r * 2f, r * 0.55f, p.rot + (now - p.t0) * 0.4f);
                canvas.drawPath(starPath, paint);
                break;
        }
    }

    // ------------------------------------------------------------------ 数据

    /** 一个飞行中的粒子。字段全是 float，没有对象引用，GC 友好 */
    static final class Particle {
        float x, y;        // 发射点
        float vx, vy;      // 初速度（px/s）
        float gy;          // 重力加速度（px/s²）
        float size, rot;   // 大小、初始旋转角
        long t0, life;     // 发射时刻、存活时长（ms）
        int color, shape;  // 颜色、形状 0圆点/1碎片/2星芒
    }

    /** 一个波纹/光晕/星点。用 kind 区分五种画法 */
    static final class Wave {
        int kind;          // 0 涟漪 1 光晕 2 溅射亮点 3 星点 4 波纹填充
        float cx, cy;      // 中心
        float r0, r1;      // 起止半径（星点用 r0 当尺寸）
        float strokeW;     // 线宽（仅涟漪）
        long t0, life;     // 起始时刻、存活时长
        int color;
        boolean clip;      // 是否裁剪到按键轮廓内
        final RectF rect = new RectF();
    }

    /** 单个按键的动画状态 */
    static final class KeyAnim {
        boolean pressed;
        long pressT;       // 按下时刻
        long releaseT;     // 松手时刻，0 表示还没松
        int seed;          // 每个键固定的随机种子，保证配色稳定
        float fadeFrom;    // 淡出起始强度
    }

    /** 渐变 Shader 缓存项，附带生成它时的参数，参数一变就重建 */
    static final class ShaderCache {
        int style, color, l, t, r, b;
        Shader shader;
    }
}
