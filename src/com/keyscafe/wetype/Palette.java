package com.keyscafe.wetype;

/**
 * 调色板：键周特效用的 8 套主题色，以及按键配色用的 9 个基色。
 *
 * <p>颜色全部写死成 ARGB int，不读资源——因为这个类同时被模块进程（预览）
 * 和输入法进程（真机）加载，少一层资源依赖少一类问题。
 */
public final class Palette {

    /** 与 {@link FxConfig#THEME_NAMES} 共用一份名字，避免两处不同步 */
    public static final String[] THEME_NAMES = FxConfig.THEME_NAMES;

    /**
     * 8 个主题，每个 4 色。同一主题内的 4 个色相接近但明度不同，
     * 用来给涟漪的多圈、溅射的多个粒子做层次。
     */
    public static final int[][] THEMES = {
            {0xFF00E5FF, 0xFF18FFFF, 0xFF64FFDA, 0xFF00B0FF}, // 霓虹
            {0xFFB388FF, 0xFF7C4DFF, 0xFFE040FB, 0xFFD500F9}, // 电紫
            {0xFFFF80AB, 0xFFFF4081, 0xFFFFB3C7, 0xFFF50057}, // 樱花
            {0xFFFFD54F, 0xFFFFB300, 0xFFFF8F00, 0xFFFFE082}, // 琥珀
            {0xFF69F0AE, 0xFF00E676, 0xFFB2FF59, 0xFF1DE9B6}, // 极光
            {0xFFFF7043, 0xFFFF3D00, 0xFFFFAB40, 0xFFFF1744}, // 火焰
            {0xFFFFFFFF, 0xFFE0E0E0, 0xFFBDBDBD, 0xFFF5F5F5}, // 霜白
            {0xFFFF5252, 0xFFFFD740, 0xFF69F0AE, 0xFF40C4FF}, // 彩虹
    };

    /** 按键配色可选基色 */
    public static final String[] COLOR_NAMES = {
            "自动", "霓虹", "电紫", "樱花", "琥珀", "极光", "火焰", "霜白", "墨黑"
    };

    /** 与 {@link #COLOR_NAMES} 一一对应；0 号是占位，实际取值见 {@link #base} */
    public static final int[] COLORS = {
            0x00000000, // 自动：跟随粒子主题
            0xFF00E5FF,
            0xFF8C6CFF,
            0xFFFF80AB,
            0xFFFFC107,
            0xFF5CF2B4,
            0xFFFF6E40,
            0xFFFFFFFF,
            0xFF101418,
    };

    /** 预览键帽的底色（顶部/底部），只在设置页用 */
    public static final int KEY_BASE = 0xFF1B1F27;
    public static final int KEY_BASE_TOP = 0xFF262C37;

    /** 键帽文字颜色 */
    public static final int TEXT = 0xFFF2F5FA;

    private Palette() {
    }

    /** 取主题色组，越界自动回到第 0 套 */
    public static int[] theme(int i) {
        if (i < 0 || i >= THEMES.length) i = 0;
        return THEMES[i];
    }

    /**
     * 从主题里取一色，并做轻微色相扰动，让同一批粒子不呆板。
     *
     * @param theme  主题下标
     * @param index  取第几色（循环取用）
     * @param jitter -0.5 ~ 0.5 的扰动系数
     */
    public static int pick(int theme, int index, float jitter) {
        int[] t = theme(theme);
        if (theme == 7) {
            // 彩虹主题：直接按 index 循环色相，得到真正的多色
            float hue = (index * 37f + jitter * 40f) % 360f;
            return hsv(hue, 0.85f, 1f);
        }
        int c = t[index % t.length];
        float[] hsv = new float[3];
        android.graphics.Color.colorToHSV(c, hsv);
        hsv[0] = (hsv[0] + jitter * 14f + 360f) % 360f;   // 色相 ±7°
        hsv[1] = Math.min(1f, hsv[1] * (1f + jitter * 0.15f));
        return hsv(hsv[0], hsv[1], hsv[2]);
    }

    /**
     * 按键配色的基色。
     *
     * <p>0 号「自动」不是固定色，而是取当前主题的首色——这样键周特效和键内配色
     * 默认就是同一个色系，不用用户手动配两次。
     */
    public static int base(int colorIndex, int theme) {
        if (colorIndex == 0) return theme(theme)[0];
        return COLORS[colorIndex % COLORS.length];
    }

    public static int hsv(float h, float s, float v) {
        return android.graphics.Color.HSVToColor(new float[]{h, s, v});
    }

    /** 替换 alpha 通道，保留 RGB */
    public static int alpha(int color, int a) {
        return (color & 0x00FFFFFF) | ((a & 0xFF) << 24);
    }

    /**
     * 与白色混合，得到更亮的同色系；{@code f} 可以为负值，表示与黑色混合（变暗）。
     *
     * @param f -1 ~ 1
     */
    public static int lighten(int color, float f) {
        int r = (color >> 16) & 0xFF, g = (color >> 8) & 0xFF, b = color & 0xFF;
        r = (int) (r + (255 - r) * f);
        g = (int) (g + (255 - g) * f);
        b = (int) (b + (255 - b) * f);
        return (color & 0xFF000000) | (r << 16) | (g << 8) | b;
    }
}
