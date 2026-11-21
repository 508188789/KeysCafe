package com.keyscafe.wetype;

import org.json.JSONObject;

/**
 * 全部可配置项，以及它与 JSON 之间的转换。
 *
 * <h2>为什么用 JSON 字符串传输</h2>
 * 模块 App（设置页所在进程）和微信输入法（特效运行进程）是两个进程，配置要跨进程传递。
 * 压成一个字符串塞进 {@code Settings.Global}，是最不受权限与 SELinux 限制的方式
 * （详见 README 的「配置怎么跨进程传递」一节）。
 *
 * <h2>字段命名约定</h2>
 * <ul>
 *   <li>{@code kc*} —— Keyboard Color，键周特效</li>
 *   <li>{@code kl*} —— Key Color，按键内部配色</li>
 *   <li>{@code km*} —— Key Motion，按键动效</li>
 * </ul>
 *
 * <p>JSON 里 {@code debug} 的键名故意缩成 {@code dbg}，纯粹是历史原因；
 * 改动它会让老配置丢失这个开关，所以保留。
 */
public final class FxConfig {

    /** 特效总开关。关闭后键盘完全恢复原样（连 invalidate 都不做） */
    public boolean master = true;

    // ------------------------------------------------------------------
    // Keyboard Color Effect：按键周围的粒子 / 波纹
    // ------------------------------------------------------------------

    public boolean kcOn = true;

    /** 0 溅射 / 1 涟漪 / 2 星点 / 3 光晕 / 4 波纹填充 */
    public int kcStyle = 0;

    /** 主题配色下标，见 {@link Palette#THEMES} */
    public int kcTheme = 0;

    /** 粒子量 1..10 */
    public int kcDensity = 5;

    /** 粒子/波纹大小 1..10 */
    public int kcSize = 5;

    /** 扩散速度 1..10 */
    public int kcSpeed = 5;

    /** 是否把波纹限制在按键轮廓内（关掉后特效会溢出到按键外） */
    public boolean kcClip = true;

    // ------------------------------------------------------------------
    // Key Color Effect：按键内部颜色 / 图案
    // ------------------------------------------------------------------

    public boolean klOn = true;

    /** 0 纯色 / 1 渐变 / 2 流光 / 3 斜纹 / 4 点阵 / 5 霓虹边 / 6 彩虹 */
    public int klStyle = 0;

    /** 基色下标，见 {@link Palette#COLORS}；0 = 自动（跟随键周特效主题） */
    public int klColor = 0;

    /** 不透明度 0..100 */
    public int klAlpha = 45;

    /** true = 只在按下时着色；false = 所有键常亮 */
    public boolean klPressedOnly = true;

    // ------------------------------------------------------------------
    // Key Motion Effect：按键缩放 / 弹跳
    // ------------------------------------------------------------------

    public boolean kmOn = true;

    /** 0 收缩 / 1 放大 / 2 弹跳 / 3 果冻 / 4 下沉 */
    public int kmStyle = 2;

    /** 形变幅度 1..10 */
    public int kmStrength = 6;

    /** 动画时长 1..10 */
    public int kmDur = 5;

    // ------------------------------------------------------------------
    // 调试
    // ------------------------------------------------------------------

    /**
     * 描边调试：打开后每个按键会画绿框（按键层）与红框（特效层），
     * 用来核对两层坐标是否严格对齐。正式使用时应关闭。
     */
    public boolean debug = false;

    /** 界面显示用的名字，与下面的下标一一对应 */
    public static final String[] KC_STYLE_NAMES = {"溅射", "涟漪", "星点", "光晕", "波纹"};
    public static final String[] KL_STYLE_NAMES = {"纯色", "渐变", "流光", "斜纹", "点阵", "霓虹", "彩虹"};
    public static final String[] KM_STYLE_NAMES = {"收缩", "放大", "弹跳", "果冻", "下沉"};
    public static final String[] THEME_NAMES = {"霓虹", "电紫", "樱花", "琥珀", "极光", "火焰", "霜白", "彩虹"};

    public FxConfig() {
    }

    /** 深拷贝。设置页改参数时用副本，取消后才不会影响正在生效的配置 */
    public FxConfig copy() {
        FxConfig c = new FxConfig();
        c.master = master;
        c.kcOn = kcOn;
        c.kcStyle = kcStyle;
        c.kcTheme = kcTheme;
        c.kcDensity = kcDensity;
        c.kcSize = kcSize;
        c.kcSpeed = kcSpeed;
        c.kcClip = kcClip;
        c.klOn = klOn;
        c.klStyle = klStyle;
        c.klColor = klColor;
        c.klAlpha = klAlpha;
        c.klPressedOnly = klPressedOnly;
        c.kmOn = kmOn;
        c.kmStyle = kmStyle;
        c.kmStrength = kmStrength;
        c.kmDur = kmDur;
        c.debug = debug;
        return c;
    }

    /** 序列化。字段少、结构扁平，手写比反射快也更可控 */
    public JSONObject toJson() {
        JSONObject o = new JSONObject();
        try {
            o.put("master", master);
            o.put("kcOn", kcOn);
            o.put("kcStyle", kcStyle);
            o.put("kcTheme", kcTheme);
            o.put("kcDensity", kcDensity);
            o.put("kcSize", kcSize);
            o.put("kcSpeed", kcSpeed);
            o.put("kcClip", kcClip);
            o.put("klOn", klOn);
            o.put("klStyle", klStyle);
            o.put("klColor", klColor);
            o.put("klAlpha", klAlpha);
            o.put("klPressedOnly", klPressedOnly);
            o.put("kmOn", kmOn);
            o.put("kmStyle", kmStyle);
            o.put("kmStrength", kmStrength);
            o.put("kmDur", kmDur);
            o.put("dbg", debug);
        } catch (Throwable ignored) {
        }
        return o;
    }

    /**
     * 反序列化。所有字段都用 {@code opt*} 带默认值：
     * 旧版本写进去的配置缺少新字段时，新字段自动取默认值，不会崩也不会变 0。
     */
    public static FxConfig fromJson(JSONObject o) {
        FxConfig c = new FxConfig();
        if (o == null) return c;
        c.master = o.optBoolean("master", true);
        c.kcOn = o.optBoolean("kcOn", true);
        c.kcStyle = o.optInt("kcStyle", 0);
        c.kcTheme = o.optInt("kcTheme", 0);
        c.kcDensity = o.optInt("kcDensity", 5);
        c.kcSize = o.optInt("kcSize", 5);
        c.kcSpeed = o.optInt("kcSpeed", 5);
        c.kcClip = o.optBoolean("kcClip", true);
        c.klOn = o.optBoolean("klOn", true);
        c.klStyle = o.optInt("klStyle", 0);
        c.klColor = o.optInt("klColor", 0);
        c.klAlpha = o.optInt("klAlpha", 45);
        c.klPressedOnly = o.optBoolean("klPressedOnly", true);
        c.kmOn = o.optBoolean("kmOn", true);
        c.kmStyle = o.optInt("kmStyle", 2);
        c.kmStrength = o.optInt("kmStrength", 6);
        c.kmDur = o.optInt("kmDur", 5);
        c.debug = o.optBoolean("dbg", false);
        c.sanitize();
        return c;
    }

    /** 从字符串解析；任何异常都退回默认配置（脏数据不该让键盘失效） */
    public static FxConfig fromString(String s) {
        try {
            return fromJson(new JSONObject(s));
        } catch (Throwable t) {
            return new FxConfig();
        }
    }

    /**
     * 把数值钳制到合法区间。
     *
     * <p>必须做：配置可能来自手改的 {@code settings put}，越界的 style 下标会让
     * {@code switch} 落到 default 分支画出意外效果，越界的数组下标会直接抛异常。
     */
    public void sanitize() {
        kcStyle = clamp(kcStyle, 0, KC_STYLE_NAMES.length - 1);
        kcTheme = clamp(kcTheme, 0, THEME_NAMES.length - 1);
        kcDensity = clamp(kcDensity, 1, 10);
        kcSize = clamp(kcSize, 1, 10);
        kcSpeed = clamp(kcSpeed, 1, 10);
        klStyle = clamp(klStyle, 0, KL_STYLE_NAMES.length - 1);
        klColor = clamp(klColor, 0, Palette.COLOR_NAMES.length - 1);
        klAlpha = clamp(klAlpha, 0, 100);
        kmStyle = clamp(kmStyle, 0, KM_STYLE_NAMES.length - 1);
        kmStrength = clamp(kmStrength, 1, 10);
        kmDur = clamp(kmDur, 1, 10);
    }

    private static int clamp(int v, int lo, int hi) {
        return v < lo ? lo : (v > hi ? hi : v);
    }
}
