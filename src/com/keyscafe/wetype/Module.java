package com.keyscafe.wetype;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Rect;
import android.net.Uri;
import android.os.Bundle;
import android.os.SystemClock;
import android.util.Log;
import android.view.Choreographer;
import android.view.MotionEvent;
import android.view.View;

import java.io.File;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Set;

import io.github.libxposed.api.XposedInterface;
import io.github.libxposed.api.XposedModule;
import io.github.libxposed.api.XposedModuleInterface;

/**
 * 「键彩」在微信输入法进程里的入口（Xposed 模块主类）。
 *
 * <h2>为什么钩在绘制层</h2>
 * 微信输入法 4.0.1 的键盘<b>不是</b>「一个按键一个 View」，而是整块自绘的
 * {@code com.tencent.wetype.plugin.hld.keyboard.selfdraw.o}（KeyboardView）。
 * 按键只是绘制循环里的数据对象 {@code ...selfdraw.j}，由 {@code ...selfdraw.e}
 * （ButtonDrawer）的 {@code b(j)} 一个个画出来，按下/抬起也只是改一下按键对象的内部状态。
 *
 * <p>所以三类特效只能落在绘制过程里：
 * <pre>
 * 键盘 View.draw(Canvas)
 *   ├─ onDraw → 遍历按键 → ButtonDrawer.b(button)      ← 钩这里（drawKey）
 *   │     ├─ canvas.save()
 *   │     ├─ canvas.scale(sx, sy, cx, cy)   ← Key Motion：按键动效
 *   │     ├─ 原按键绘制（背景/文字/图标）
 *   │     ├─ 按键配色叠加（Key Color）        ← 在 chain.proceed() 之后
 *   │     └─ canvas.restore()
 *   └─ 粒子/波纹叠加层                        ← 钩这里（键盘 draw 之后）
 * </pre>
 *
 * <p>按压时机不依赖输入法内部回调（它没有对外暴露），而是拦 {@code dispatchTouchEvent}，
 * 拿「上一帧刚刚画过的按键矩形」做命中测试。这样滑动输入、长按、候选栏切换都不会错位，
 * 也不需要知道按键对象的任何字段含义。
 *
 * <h2>抗混淆策略：一切按签名，不按名字</h2>
 * 反编译工具 jadx 会用 Kotlin {@code @Metadata} 把方法名「还原」成可读名字，例如它显示
 * {@code getDrawRect()}，而 dex 里的真实名字是 {@code F()}；{@code getHitRect()} 实际是
 * {@code t()}。因此本类里所有反射查找都只依赖<b>参数类型 / 返回类型</b>：
 * <ul>
 *   <li>「画一个按键」的方法 = 1 个参数、返回 void、参数类型带「无参且返回 Rect」的方法</li>
 *   <li>按键矩形 getter = 无参、返回 {@code android.graphics.Rect}；有两个时按面积采样自动挑</li>
 *   <li>drawerType getter = 无参返回 String，且返回值命中 drawerMap 的 key 集合</li>
 * </ul>
 * 即使下个版本把混淆名全换一遍，这套逻辑依然能挂上。
 *
 * <h2>线程模型</h2>
 * 所有钩子都跑在输入法自己的 UI 线程上（绘制与触摸本来就在那里），因此不需要额外加锁，
 * 也不存在跨线程访问 Canvas 的问题。动画通过 {@link Choreographer} 逐帧
 * {@code invalidate()} 驱动，动画结束后自动停帧，空闲时零开销。
 */
public final class Module extends XposedModule {

    /** logcat 标签：{@code adb logcat -s KeysCafe} */
    static final String TAG = "KeysCafe";

    /** 作用域包名（见 META-INF/xposed/scope.list） */
    static final String TARGET = "com.tencent.wetype";

    /** 退路用的 ContentProvider authority（见 {@link ConfigProvider}） */
    static final String AUTHORITY = "com.keyscafe.wetype.config";

    // ------------------------------------------------------------------
    // 反射句柄（安装时解析一次，之后每帧直接复用）
    // ------------------------------------------------------------------

    /** 按键对象的「无参、返回 Rect」方法，通常是 hitRect 与 drawRect 两个 */
    private Method[] mRectMethods;

    /** ButtonDrawer 里持有当前 Canvas 的字段——省得再从参数拿 */
    private Field fCanvas;

    /** ButtonDrawer 里持有 drawerType → 绘制器 的 Map 字段 */
    private Field fDrawerMap;

    /** 读 drawerType 的方法（探测得到，可能是 null） */
    private Method mTypeGetter;

    /** drawerType 是否已探测过（只探一次） */
    private boolean typeProbed;

    /** drawerMap 的 key 集合，即全部合法的 drawerType 字符串 */
    private Set<?> drawerTypes;

    // ------------------------------------------------------------------
    // 按键矩形自动判定：两个候选里哪个才是「绘制矩形」
    // ------------------------------------------------------------------

    /** 已选定的矩形方法下标；-1 表示还在采样 */
    private int rectSel = -1;

    /** 两个候选方法各自的累计面积，用来判断谁是绘制区（hitRect 通常比 drawRect 大） */
    private final long[] areaSum = new long[2];

    /** 已采样次数 */
    private int areaSamples;

    /** 采样 60 个按键后定型；60 足够覆盖 qwerty + 数字 + 功能键 */
    private static final int AREA_SAMPLES = 60;

    /** transformOf 的输出缓冲，复用避免每帧分配 */
    private final float[] tmpTr = new float[3];

    /** 调试描边画笔（只有 cfg.debug 为 true 时才用） */
    private final Paint dbg = new Paint(Paint.ANTI_ALIAS_FLAG);

    {
        dbg.setStyle(Paint.Style.STROKE);
        dbg.setStrokeWidth(2f);
    }

    // ------------------------------------------------------------------
    // 帧内状态
    // ------------------------------------------------------------------

    /** 本帧画过的按键：{@code Object[]{keyObject, Rect}}，供命中测试用 */
    private final ArrayList<Object[]> frameKeys = new ArrayList<>();

    /** 当前按下的按键集合（理论上只有一个，用 IdentityHashMap 是因为按键对象没有稳定 hashCode） */
    private final IdentityHashMap<Object, Boolean> pressed = new IdentityHashMap<>();

    /** 按键「是否空白键」缓存，避免每帧都反射调用 */
    private final IdentityHashMap<Object, Boolean> blankCache = new IdentityHashMap<>();

    /** 键盘 View，用来 invalidate 触发重绘 */
    private View keyboardView;

    /** 输入法进程的 Context，读 Settings.Global 用 */
    private Context appContext;

    /** 帧循环是否已在运行，防止重复 post */
    private boolean frameLoopRunning;

    // ------------------------------------------------------------------
    // 配置
    // ------------------------------------------------------------------

    /** 当前生效的配置（默认全开） */
    private FxConfig cfg = new FxConfig();

    /** 上次读配置的时间戳，用于节流 */
    private long cfgAt;

    /** 「配置读不到」只报一次，避免刷屏 */
    private boolean cfgWarned;

    /** remote files 列表是否已打印过 */
    private boolean filesProbed;

    /** 首次按下时把两个候选矩形的实际值打一次日志，方便排查 */
    private boolean rectDumped;

    // ------------------------------------------------------------------
    // 生命周期
    // ------------------------------------------------------------------

    /**
     * 进程加载回调：只处理微信输入法自己的进程，其它进程直接放行。
     *
     * <p>注意这里<b>不能</b>做重活——它在进程启动早期被调用，异常会拖慢输入法启动，
     * 所以整个 install 都包在 try/catch 里，失败只记日志。
     */
    @Override
    public void onPackageLoaded(XposedModuleInterface.PackageLoadedParam param) {
        if (!TARGET.equals(param.getPackageName())) return;
        try {
            install(param.getDefaultClassLoader());
        } catch (Throwable t) {
            loge("install failed", t);
        }
    }

    // ------------------------------------------------------------------ 安装

    /**
     * 解析目标类并挂上三个钩子。
     *
     * <p>顺序很重要：先找到 KeyboardView，再由它的 {@code getButtonDrawer()} 拿到
     * ButtonDrawer 的类型，再由「画按键」方法的参数类型拿到按键类型——一路靠类型推导，
     * 不硬编码除 KeyboardView 之外的任何类名。
     *
     * @param cl 输入法自己的 ClassLoader（不能用模块的，看不到目标类）
     */
    private void install(ClassLoader cl) throws Throwable {
        Class<?> kb = resolveKeyboard(cl);
        if (kb == null) {
            loge("keyboard class not found", null);
            return;
        }
        Method getDrawer = findMethod(kb, "getButtonDrawer");
        if (getDrawer == null) {
            loge("getButtonDrawer not found on " + kb.getName(), null);
            return;
        }
        Class<?> drawerCls = getDrawer.getReturnType();
        Method drawKey = pickDrawKeyMethod(drawerCls);
        if (drawKey == null) {
            loge("drawKey method not found in " + drawerCls.getName(), null);
            return;
        }
        Class<?> keyCls = drawKey.getParameterTypes()[0];
        mRectMethods = findRectMethods(keyCls);
        fCanvas = findFieldOfType(drawerCls, Canvas.class);
        fDrawerMap = findFieldOfType(drawerCls, Map.class);
        if (mRectMethods.length == 0 || fCanvas == null) {
            loge("rect/canvas accessor missing (rects=" + mRectMethods.length + ")", null);
            return;
        }

        Method kbDraw = findMethod(kb, "draw", Canvas.class);
        Method kbTouch = findMethod(kb, "dispatchTouchEvent", MotionEvent.class);
        if (kbDraw == null || kbTouch == null) {
            loge("draw/touch not found on " + kb.getName(), null);
            return;
        }

        // ==============================================================
        // 钩子 1：单个按键 —— 缩放（Key Motion）+ 内部配色（Key Color）
        // ==============================================================
        // 拦在「画一个按键」之前：
        //   1. 取出该按键的绘制矩形（顺便登记到 frameKeys，供触摸命中测试）
        //   2. 若动效需要缩放，canvas.save() + scale，让输入法自己在缩放后的坐标系里画
        //   3. chain.proceed() 让它照常画完
        //   4. finally 里 restore，再在同样位置叠一层配色
        // 用 finally 而不是顺序语句，是为了保证输入法内部抛异常时 canvas 栈不会失衡。
        hook(drawKey).setPriority(XposedInterface.PRIORITY_DEFAULT).intercept(chain -> {
            Object button = chain.getArg(0);
            Object drawer = chain.getThisObject();
            Canvas canvas = null;
            Rect r = null;
            boolean scaled = false;
            try {
                canvas = (Canvas) fCanvas.get(drawer);
                // isBlank() 过滤掉纯占位键（空白键、间距），它们没有可绘制的键帽
                if (canvas != null && button != null && !isBlank(drawer, button)) {
                    r = rectOf(button);
                    if (r != null && !r.isEmpty()) {
                        frameKeys.add(new Object[]{button, r});
                        Fx.I.transformOf(button, r, tmpTr);
                        if (tmpTr[0] != 1f || tmpTr[1] != 1f || tmpTr[2] != 0f) {
                            canvas.save();
                            // 以按键中心为缩放原点，键帽才是「原地」放大/缩小
                            canvas.scale(tmpTr[0], tmpTr[1], r.exactCenterX(), r.exactCenterY());
                            // 竖向下沉效果：缩放之外再整体下移一点
                            if (tmpTr[2] != 0f) canvas.translate(0f, tmpTr[2]);
                            scaled = true;
                        }
                    }
                }
            } catch (Throwable ignored) {
                // 任何反射失败都当作「本帧不加特效」，绝不干扰输入法本身
            }
            try {
                return chain.proceed();
            } finally {
                try {
                    if (scaled) canvas.restore();
                    if (canvas != null && r != null && !r.isEmpty()) {
                        // 配色叠加在输入法画完之后，因此不会被键帽盖住
                        Fx.I.drawKeyOverlay(canvas, button, r, 0f);
                        if (cfg.debug) {
                            // 绿色 = 按键层矩形，用来核对与键帽是否严格对齐
                            dbg.setColor(0xFF00FF66);
                            canvas.drawRect(r, dbg);
                        }
                    }
                } catch (Throwable ignored) {
                }
            }
        });

        // ==============================================================
        // 钩子 2：键盘整体 —— 帧起点 + 粒子/波纹叠加（Keyboard Color）
        // ==============================================================
        // 在键盘 onDraw 之前重置帧状态（清空 frameKeys、推进动画时钟），
        // 在 onDraw 之后把粒子与波纹画到同一个 canvas 上——这样特效和按键共用坐标系，
        // 不需要任何坐标换算。
        hook(kbDraw).intercept(chain -> {
            try {
                onFrameStart(chain.getThisObject());
            } catch (Throwable ignored) {
            }
            Object ret = chain.proceed();
            try {
                Canvas c = (Canvas) chain.getArg(0);
                Fx.I.drawOverlay(c);
                if (cfg.debug) {
                    // 红色 = 特效层看到的按键矩形，与绿色重合说明两层坐标一致
                    dbg.setColor(0xFFFF3355);
                    for (int i = 0, n = frameKeys.size(); i < n; i++) {
                        Rect rr = (Rect) frameKeys.get(i)[1];
                        if (rr != null) c.drawRect(rr, dbg);
                    }
                }
            } catch (Throwable ignored) {
            }
            return ret;
        });

        // ==============================================================
        // 钩子 3：触摸 —— 命中测试，决定「哪个键被按下」
        // ==============================================================
        // 只旁听、不改写事件，一定 proceed()，否则会破坏输入法自己的手势识别。
        hook(kbTouch).intercept(chain -> {
            try {
                onTouch(chain.getThisObject(), (MotionEvent) chain.getArg(0));
            } catch (Throwable ignored) {
            }
            return chain.proceed();
        });

        logi("hooked kb=" + kb.getName() + " drawer=" + drawerCls.getName()
                + " key=" + keyCls.getName() + " drawKey=" + drawKey.getName()
                + " rects=" + mRectMethods[0].getName()
                + (mRectMethods.length > 1 ? "/" + mRectMethods[1].getName() : ""));
    }

    /**
     * 找到键盘 View 的类。
     *
     * <p>先按已知类名试（4.0.x 用 {@code selfdraw.o}），再在继承链上找带
     * {@code getButtonDrawer()} 的那一级——因为具体键盘（中文九宫格、英文 qwerty）
     * 都是它的子类，钩父类的 draw/dispatchTouchEvent 即可覆盖全部布局。
     */
    private Class<?> resolveKeyboard(ClassLoader cl) {
        String[] candidates = {
                "com.tencent.wetype.plugin.hld.keyboard.selfdraw.o",
                "com.tencent.wetype.plugin.hld.keyboard.selfdraw.S2ChineseQwertyKeyboard",
                "com.tencent.wetype.plugin.hld.keyboard.selfdraw.S3EnglishQwertyKeyboard",
        };
        for (String n : candidates) {
            Class<?> c = load(cl, n);
            if (c == null) continue;
            for (Class<?> k = c; k != null && k != Object.class; k = k.getSuperclass()) {
                if (findMethod(k, "getButtonDrawer") != null) return k;
            }
        }
        return null;
    }

    /**
     * 在 ButtonDrawer 里挑出「画一个按键」的方法。
     *
     * <p>判据（全部基于签名，与名字无关）：
     * 1 个参数、返回 {@code void}、参数不是基本类型/数组/Canvas，且参数类型带有
     * 「无参返回 Rect」的方法（说明它是按键模型）。
     *
     * <p>若有多个候选，取<b>名字最短</b>的那个——混淆后的方法名普遍是单字母，
     * 而 lambda/合成方法名更长（{@code lambda$draw$0}），这个启发式在实践中够用。
     */
    private Method pickDrawKeyMethod(Class<?> drawerCls) {
        for (Class<?> k = drawerCls; k != null && k != Object.class; k = k.getSuperclass()) {
            Method best = null;
            for (Method m : k.getDeclaredMethods()) {
                if (m.getParameterCount() != 1) continue;
                if (m.getReturnType() != void.class) continue;
                Class<?> p = m.getParameterTypes()[0];
                if (p.isPrimitive() || p.isArray() || p == Canvas.class) continue;
                if (findRectMethods(p).length == 0) continue;
                if (best == null || m.getName().length() < best.getName().length()) best = m;
            }
            if (best != null) return best;
        }
        return null;
    }

    /**
     * 收集「无参、返回 {@code android.graphics.Rect}」的方法。
     *
     * <p>按键对象上一般有两个：{@code getDrawRect()}（实际绘制区）与
     * {@code getHitRect()}（触摸判定区，通常比绘制区大一圈）。这里不猜名字，
     * 两个都收下，交由 {@link #rectOf} 按面积自动选定。
     */
    private static Method[] findRectMethods(Class<?> c) {
        ArrayList<Method> out = new ArrayList<>(2);
        for (Class<?> k = c; k != null && k != Object.class; k = k.getSuperclass()) {
            for (Method m : k.getDeclaredMethods()) {
                if (m.getParameterCount() == 0 && m.getReturnType() == Rect.class) {
                    m.setAccessible(true);
                    // 子类重写父类同名方法时只保留一个，避免重复统计
                    boolean dup = false;
                    for (Method e : out) {
                        if (e.getName().equals(m.getName())) {
                            dup = true;
                            break;
                        }
                    }
                    if (!dup) out.add(m);
                }
            }
            if (!out.isEmpty()) break;
        }
        return out.toArray(new Method[0]);
    }

    // ------------------------------------------------------------------ 按键识别

    /**
     * 判断这个按键是不是「空白」占位（只有位置、不画键帽）。
     *
     * <p>空白键如果也参与缩放，会在键盘上出现莫名抖动的空白区域，所以必须跳过。
     * 判定方式是读它的 drawerType，与常量 {@code "blankType"} 比较。
     * 结果按按键对象缓存——drawerType 不会变，没必要每帧反射。
     */
    private boolean isBlank(Object drawer, Object button) {
        Boolean c = blankCache.get(button);
        if (c != null) return c;
        boolean blank = false;
        try {
            if (!typeProbed) probeTypeGetter(drawer, button);
            if (mTypeGetter != null) {
                Object t = mTypeGetter.invoke(button);
                blank = "blankType".equals(t);
            }
        } catch (Throwable ignored) {
        }
        if (blankCache.size() > 512) blankCache.clear();  // 防止键盘重排后无限增长
        blankCache.put(button, blank);
        return blank;
    }

    /**
     * 反查「哪个无参 String 方法读的是 drawerType」。
     *
     * <p>技巧：ButtonDrawer 里有个 {@code Map<String, 绘制器>}，它的 key 集合就是
     * 全部合法 drawerType。遍历按键对象的所有「无参返回 String」的方法，谁返回的值
     * 落在这个集合里，谁就是 drawerType getter。这样完全不依赖方法名。
     */
    private void probeTypeGetter(Object drawer, Object button) {
        typeProbed = true;   // 只探一次，失败也不再重试（避免每帧都扫方法表）
        try {
            if (fDrawerMap == null) return;
            Object map = fDrawerMap.get(drawer);
            if (!(map instanceof Map)) return;
            drawerTypes = ((Map<?, ?>) map).keySet();
            if (drawerTypes.isEmpty()) return;
            for (Class<?> k = button.getClass(); k != null && k != Object.class; k = k.getSuperclass()) {
                for (Method m : k.getDeclaredMethods()) {
                    if (m.getParameterCount() != 0 || m.getReturnType() != String.class) continue;
                    m.setAccessible(true);
                    Object v;
                    try {
                        v = m.invoke(button);
                    } catch (Throwable t) {
                        continue;   // 有些 getter 会 NPE，跳过继续找
                    }
                    if (v != null && drawerTypes.contains(v)) {
                        mTypeGetter = m;
                        logi("drawerType getter = " + m.getName() + " (" + v + ")");
                        return;
                    }
                }
            }
        } catch (Throwable ignored) {
        }
    }

    /**
     * 取按键的绘制矩形。
     *
     * <p>候选有两个（drawRect / hitRect）时，不能凭名字判断，改为<b>累计面积采样</b>：
     * 触摸判定区一定不小于绘制区，所以平均面积更小的那个才是键帽实际占的区域。
     * 采样 {@value #AREA_SAMPLES} 个按键后定型并缓存下标，之后零成本。
     *
     * <p>为什么必须选小面积的那个：配色层要贴着键帽画、缩放要以键帽中心为原点，
     * 用 hitRect 会让特效比键帽大一圈（表现为颜色溢出到按键外面）。
     */
    private Rect rectOf(Object key) {
        try {
            if (mRectMethods.length == 1) return (Rect) mRectMethods[0].invoke(key);
            if (rectSel >= 0) return (Rect) mRectMethods[rectSel].invoke(key);
            // 未定型：统计平均面积，较小的那个是绘制矩形
            Rect a = (Rect) mRectMethods[0].invoke(key);
            Rect b = (Rect) mRectMethods[1].invoke(key);
            if (a != null) areaSum[0] += (long) a.width() * a.height();
            if (b != null) areaSum[1] += (long) b.width() * b.height();
            if (++areaSamples >= AREA_SAMPLES) {
                rectSel = areaSum[0] <= areaSum[1] ? 0 : 1;
                logi("drawRect getter = " + mRectMethods[rectSel].getName()
                        + " sum0=" + areaSum[0] + " sum1=" + areaSum[1]);
                if (mRectMethods.length > 1 && rectSel == 0) {
                    try {
                        Rect x = (Rect) mRectMethods[0].invoke(key);
                        Rect y = (Rect) mRectMethods[1].invoke(key);
                        logi("sample " + mRectMethods[0].getName() + "=" + x + " "
                                + mRectMethods[1].getName() + "=" + y);
                    } catch (Throwable ignored) {
                    }
                }
            }
            return a;
        } catch (Throwable t) {
            return null;
        }
    }

    // ------------------------------------------------------------------ 帧 / 触摸

    /**
     * 每帧绘制开始时调用：刷新配置、推进动画时钟、清空上一帧的按键列表。
     *
     * <p>把「读配置」放在这里而不是只在触摸时读，是因为键盘可能在用户没碰它的情况下
     * 出现（比如自动弹出），那时也需要拿到最新配置。
     */
    private void onFrameStart(Object view) {
        if (!(view instanceof View)) return;
        keyboardView = (View) view;
        if (appContext == null) appContext = keyboardView.getContext();
        refreshConfig(false);
        Fx.I.beginFrame(SystemClock.uptimeMillis());
        frameKeys.clear();
    }

    /**
     * 触摸旁听：把系统事件翻译成「按下某个键 / 抬起」。
     *
     * <p>三种情况：
     * <ul>
     *   <li>DOWN —— 命中测试，按下；</li>
     *   <li>MOVE —— 手指滑出了当前按键的绘制区（注意用绘制区而非判定区，与原厂反馈一致），
     *       先抬起再重新命中，这样滑动输入时特效会跟着手指移动；</li>
     *   <li>UP / CANCEL —— 全部抬起。</li>
     * </ul>
     * 最后如果还有动画在跑，主动踢一次帧循环。
     */
    private void onTouch(Object viewObj, MotionEvent e) {
        if (e == null || !(viewObj instanceof View)) return;
        if (keyboardView == null) keyboardView = (View) viewObj;
        if (appContext == null) appContext = ((View) viewObj).getContext();
        refreshConfig(false);
        // 触摸事件可能早于下一帧绘制到达，先推进时钟，动画时间才准
        Fx.I.beginFrame(SystemClock.uptimeMillis());

        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN: {
                Object k = hit(e.getX(), e.getY());
                if (k != null) {
                    press(k, e.getX(), e.getY());
                    kick();   // 立刻开始动画帧循环
                }
                break;
            }
            case MotionEvent.ACTION_MOVE: {
                Object cur = pressed.isEmpty() ? null : pressed.keySet().iterator().next();
                if (cur != null) {
                    Rect r = rectOf(cur);
                    if (r == null || !r.contains((int) e.getX(), (int) e.getY())) {
                        releaseAll();
                        Object k = hit(e.getX(), e.getY());
                        if (k != null) press(k, e.getX(), e.getY());
                    }
                }
                break;
            }
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                releaseAll();
                break;
        }
        if (Fx.I.busy()) kick();
    }

    /**
     * 记录一次按下，并通知特效引擎（它会生成粒子/波纹、启动动效）。
     *
     * <p>首次按下时把两个候选矩形的真实值打一条日志：现场排查「特效偏了」时，
     * 一眼就能看出是不是矩形选错了。
     */
    private void press(Object key, float x, float y) {
        Rect r = rectOf(key);
        if (r == null) return;
        if (!rectDumped) {
            rectDumped = true;
            StringBuilder sb = new StringBuilder("press@(" + (int) x + "," + (int) y + ")");
            for (Method m : mRectMethods) {
                try {
                    sb.append(' ').append(m.getName()).append('=').append(m.invoke(key));
                } catch (Throwable ignored) {
                }
            }
            logi(sb.toString());
        }
        pressed.put(key, Boolean.TRUE);
        Fx.I.onDown(key, r, x, y);
    }

    /** 抬起所有按下的键（正常情况下只有一个） */
    private void releaseAll() {
        if (pressed.isEmpty()) return;
        for (Object k : pressed.keySet()) Fx.I.onUp(k);
        pressed.clear();
        kick();
    }

    /**
     * 命中测试：从最近画过的按键里倒序找第一个包含该点的。
     *
     * <p>倒序是为了让后画的（上层）优先，符合视觉直觉——弹出层键若与底层键重叠，
     * 命中的应该是看得见的那个。
     */
    private Object hit(float x, float y) {
        for (int i = frameKeys.size() - 1; i >= 0; i--) {
            Object[] ent = frameKeys.get(i);
            Rect r = (Rect) ent[1];
            if (r != null && !r.isEmpty() && r.contains((int) x, (int) y)) return ent[0];
        }
        return null;
    }

    /**
     * 帧回调：动画未结束时 invalidate 键盘并预约下一帧。
     *
     * <p>这是整套特效「有动画才耗电、没动画零开销」的关键——只要
     * {@link Fx#busy()} 返回 false 就不再预约，输入法回到完全空闲状态。
     */
    private final Choreographer.FrameCallback frameCb = new Choreographer.FrameCallback() {
        @Override
        public void doFrame(long frameTimeNanos) {
            frameLoopRunning = false;
            View v = keyboardView;
            if (v == null) return;
            if (Fx.I.busy()) {
                v.invalidate();
                kick();
            }
        }
    };

    /** 预约下一帧（幂等：已预约则不重复） */
    private void kick() {
        if (frameLoopRunning) return;
        frameLoopRunning = true;
        try {
            Choreographer.getInstance().postFrameCallback(frameCb);
        } catch (Throwable t) {
            frameLoopRunning = false;
        }
    }

    // ------------------------------------------------------------------ 配置

    /**
     * 读取配置，五种通道依次尝试，前一个拿到数据就不再往下走。
     *
     * <p>为什么要五条通道：模块 App 与微信输入法是两个进程，Android 11+ 的包可见性
     * 与 SELinux per-app MLS 会把大部分常规共享手段（ContentProvider、私有目录文件、
     * 远程偏好）全部挡掉，详见 README 的踩坑表。目前真正生效的是第 1 条
     * {@code Settings.Global}，其余保留作为不同 ROM / 未来版本的退路。
     *
     * <p>节流 700ms：绘制与触摸都可能调用它，但不该每次都读系统设置。
     * 用户改完样式到生效最多 0.7 秒，体感是「立刻」。
     */
    private void refreshConfig(boolean force) {
        long now = SystemClock.uptimeMillis();
        if (!force && now - cfgAt < 700) return;
        cfgAt = now;

        // 1) Settings.Global：所有应用可读，且不受包可见性 / SELinux 分类影响（主通道）
        String json = readGlobal();
        // 2) 框架提供：读模块私有目录（openRemoteFile）
        if (json == null) json = readRemoteFile();
        // 3) LSPosed 远程偏好
        if (json == null) json = readRemote();
        // 4) 退路：ContentProvider
        if (json == null) json = readProvider();
        // 5) 最后退路：按绝对路径直读模块文件（多数 ROM 上会被 SELinux 挡住）
        if (json == null) json = readModuleFile();
        // 6) 兜底：默认全开
        if (json == null) {
            if (!cfgWarned) {
                cfgWarned = true;
                loge("config unreachable, using defaults", null);
            }
            if (Fx.I.config() != cfg) Fx.I.setConfig(cfg);
            return;
        }
        FxConfig n = FxConfig.fromString(json);
        // 只在真的变了才替换并清空特效缓存，避免每 700ms 都把动画打断一次
        if (!sameAs(n, cfg)) {
            cfg = n;
            Fx.I.setConfig(cfg);
        }
    }

    /** 主通道：{@code Settings.Global} 里的一个字符串键 */
    private String readGlobal() {
        Context c = appContext;
        if (c == null) return null;
        try {
            String v = android.provider.Settings.Global.getString(c.getContentResolver(), GlobalKeys.KEY);
            return (v == null || v.isEmpty()) ? null : v;
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * 最后退路：模块 App 把自己的 files/keyscafe.json 放开了读权限，这里按绝对路径直读。
     *
     * <p>实测多数 ROM 上会被 SELinux 的 per-app MLS 分类拒绝（返回 null），
     * 保留是为了在不做 per-app 隔离的环境里还能用。
     */
    private String readModuleFile() {
        try {
            String dir = getModuleApplicationInfo().dataDir;
            File f = new File(dir, "files/" + Store.FILE);
            if (!f.exists() || f.length() <= 0 || f.length() > 65536) return null;
            byte[] b = new byte[(int) f.length()];
            try (java.io.FileInputStream in = new java.io.FileInputStream(f)) {
                int n = in.read(b);
                if (n <= 0) return null;
                return new String(b, 0, n, "UTF-8").trim();
            }
        } catch (Throwable t) {
            return null;
        }
    }

    /** 框架通道：{@code openRemoteFile} 读模块 files 目录（首次会打印目录内容便于排查） */
    private String readRemoteFile() {
        try {
            if (!filesProbed) {
                filesProbed = true;
                StringBuilder sb = new StringBuilder();
                for (String f : listRemoteFiles()) sb.append(f).append(',');
                logi("remote files: [" + sb + "]");
            }
            android.os.ParcelFileDescriptor pfd = openRemoteFile(Store.FILE);
            if (pfd == null) return null;
            try (java.io.FileInputStream in = new java.io.FileInputStream(pfd.getFileDescriptor())) {
                byte[] buf = new byte[8192];
                int n = in.read(buf);
                if (n <= 0) return null;
                return new String(buf, 0, n, "UTF-8").trim();
            } finally {
                pfd.close();
            }
        } catch (Throwable t) {
            return null;
        }
    }

    /** 框架通道：{@code getRemotePreferences}（只对框架自己管理的偏好生效） */
    private String readRemote() {
        try {
            android.content.SharedPreferences sp = getRemotePreferences("cfg");
            return sp == null ? null : sp.getString("json", null);
        } catch (Throwable t) {
            return null;
        }
    }

    /** 退路通道：模块 App 里那个只读 ContentProvider（会被包可见性拦截） */
    private String readProvider() {
        Context c = appContext;
        if (c == null) return null;
        try {
            Bundle b = c.getContentResolver().call(Uri.parse("content://" + AUTHORITY), "get", null, null);
            return b == null ? null : b.getString("json");
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * 逐字段比较配置。
     *
     * <p>不可用 {@code equals}：{@link FxConfig} 每次解析都是新对象，且没有实现
     * {@code equals}。逐字段比较虽然啰嗦，但能避免「值没变也重置动画」。
     */
    private static boolean sameAs(FxConfig a, FxConfig b) {
        return a.master == b.master
                && a.kcOn == b.kcOn && a.kcStyle == b.kcStyle && a.kcTheme == b.kcTheme
                && a.kcDensity == b.kcDensity && a.kcSize == b.kcSize && a.kcSpeed == b.kcSpeed
                && a.kcClip == b.kcClip
                && a.klOn == b.klOn && a.klStyle == b.klStyle && a.klColor == b.klColor
                && a.klAlpha == b.klAlpha && a.klPressedOnly == b.klPressedOnly
                && a.kmOn == b.kmOn && a.kmStyle == b.kmStyle && a.kmStrength == b.kmStrength
                && a.kmDur == b.kmDur && a.debug == b.debug;
    }

    // ------------------------------------------------------------------ 工具

    /** 用目标进程的 ClassLoader 加载类，失败返回 null（不抛异常） */
    private static Class<?> load(ClassLoader cl, String name) {
        try {
            return Class.forName(name, false, cl);
        } catch (Throwable t) {
            return null;
        }
    }

    /** 沿继承链找方法并 setAccessible（先找子类，符合重写语义） */
    private static Method findMethod(Class<?> c, String name, Class<?>... params) {
        for (Class<?> k = c; k != null && k != Object.class; k = k.getSuperclass()) {
            try {
                Method m = k.getDeclaredMethod(name, params);
                m.setAccessible(true);
                return m;
            } catch (Throwable ignored) {
            }
        }
        return null;
    }

    /**
     * 沿继承链找「类型恰好是 type」的第一个字段。
     *
     * <p>按类型找而不是按名字找，同样是抗混淆：Canvas 字段在混淆后叫 {@code a}/{@code b}，
     * 但类型一定还是 {@code android.graphics.Canvas}。
     */
    private static Field findFieldOfType(Class<?> c, Class<?> type) {
        for (Class<?> k = c; k != null && k != Object.class; k = k.getSuperclass()) {
            for (Field f : k.getDeclaredFields()) {
                if (f.getType() == type) {
                    f.setAccessible(true);
                    return f;
                }
            }
        }
        return null;
    }

    /** 同时输出到 logcat 与 LSPosed 日志面板 */
    void logi(String msg) {
        Log.i(TAG, msg);
        try {
            log(Log.INFO, TAG, msg);
        } catch (Throwable ignored) {
        }
    }

    /** 同上，错误级别 */
    void loge(String msg, Throwable t) {
        Log.e(TAG, msg, t);
        try {
            log(Log.ERROR, TAG, msg, t);
        } catch (Throwable ignored) {
        }
    }
}
