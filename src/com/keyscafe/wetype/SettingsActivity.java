package com.keyscafe.wetype;

import android.app.Activity;
import android.content.res.ColorStateList;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.widget.CompoundButton;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

/**
 * 设置界面：一张卡片放实时预览，下面按三大类分组放控件。
 *
 * <h2>纯代码构建 UI</h2>
 * 没有 XML 布局，全部用 {@link Ui} 里的工具动态拼。好处是编译链可以只用
 * aapt2 + javac + d8，不需要处理复杂的资源引用；代价是代码长一点。
 *
 * <h2>改一下就生效</h2>
 * 每个控件回调里都调 {@link #apply()}：把配置写出去（{@code Settings.Global}），
 * 输入法进程在下一帧读配置时就会拿到新值（它每 700ms 最多读一次）。
 * 所以用户不需要重启输入法，也不用杀进程。
 */
public class SettingsActivity extends Activity {

    /** 布尔控件的回调（用接口而不是 lambda，是为了兼容不启用 desugar 的构建配置） */
    interface BoolSetter {
        void on(boolean v);
    }

    /** 整数控件的回调 */
    interface IntSetter {
        void on(int v);
    }

    /** 当前正在编辑的配置 */
    private FxConfig cfg;

    /** 预览视图，改动时同步刷新 */
    private PreviewView preview;

    /** 「没有写权限」的提示只弹一次，否则每次调滑杆都弹会疯 */
    private boolean grantedHinted;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        cfg = Store.load(this);
        Fx.I.setConfig(cfg);
        // 启动时无条件发布一次：这样即使用户从没进过设置页改任何东西，
        // 首次安装后输入法进程也能读到一份合法配置（而不是走默认值兜底）。
        Store.save(this, cfg);

        Window w = getWindow();
        w.setStatusBarColor(Ui.BG);
        w.setNavigationBarColor(Ui.BG);

        ScrollView sv = new ScrollView(this);
        sv.setBackgroundColor(Ui.BG);
        sv.setFillViewport(true);
        final LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        int p = Ui.dp(this, 18);
        // 顶部多留一个状态栏高度：本页面不用 fitsSystemWindows，手动让开
        root.setPadding(p, Ui.dp(this, 14) + statusBar(), p, Ui.dp(this, 28));
        sv.addView(root, new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));

        buildHeader(root);
        buildMaster(root);
        buildPreview(root);
        buildKeyboardSection(root);
        buildKeySection(root);
        buildMotionSection(root);
        buildFooter(root);

        setContentView(sv);
    }

    /** 读系统状态栏高度（拿不到就用 28dp 兜底） */
    private int statusBar() {
        int id = getResources().getIdentifier("status_bar_height", "dimen", "android");
        return id > 0 ? getResources().getDimensionPixelSize(id) : Ui.dp(this, 28);
    }

    // ------------------------------------------------------------------ 顶部

    /** 标题行：大标题 + 一个小徽章 + 一行副标题 */
    private void buildHeader(LinearLayout root) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);

        TextView title = Ui.text(this, "键彩", 30, Ui.TEXT, true);
        row.addView(title);

        TextView badge = Ui.text(this, "  KEYS CAFE  ", 9.5f, Ui.ACCENT, true);
        badge.setBackground(Ui.round(Ui.ACCENT_DIM, Ui.dp(this, 6), Ui.ACCENT, Ui.dp(this, 1)));
        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        blp.leftMargin = Ui.dp(this, 10);
        row.addView(badge, blp);
        root.addView(row);

        TextView sub = Ui.text(this, "微信输入法 · 按键特效模块 · LSPosed API 102", 12.5f, Ui.SUB, false);
        root.addView(sub, lp(-1, -2, 8));
    }

    /** 总开关卡片：关掉后键盘完全恢复原样 */
    private void buildMaster(LinearLayout root) {
        LinearLayout card = Ui.card(this);
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);

        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.addView(Ui.text(this, "特效总开关", 15, Ui.TEXT, true));
        TextView hint = Ui.text(this, "关闭后键盘完全恢复原样", 11.5f, Ui.SUB, false);
        col.addView(hint, lp(-2, -2, 3));
        // weight=1：让文字占满剩余宽度，开关贴右边
        row.addView(col, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        final Switch sw = newSwitch(cfg.master);
        sw.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton b, boolean v) {
                cfg.master = v;
                apply();
            }
        });
        row.addView(sw);
        card.addView(row);
        root.addView(card, lp(-1, -2, 16));
    }

    /** 实时预览卡片：点按键就能看到效果 */
    private void buildPreview(LinearLayout root) {
        LinearLayout card = Ui.card(this);
        card.setPadding(Ui.dp(this, 10), Ui.dp(this, 12), Ui.dp(this, 10), Ui.dp(this, 10));

        LinearLayout head = new LinearLayout(this);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setGravity(Gravity.CENTER_VERTICAL);
        TextView t = Ui.text(this, "实时预览", 13, Ui.TEXT, true);
        head.addView(t, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        head.addView(Ui.text(this, "点按键试试 ↓", 11, Ui.SUB, false));
        LinearLayout.LayoutParams hlp = new LinearLayout.LayoutParams(-1, -2);
        hlp.leftMargin = Ui.dp(this, 6);
        hlp.bottomMargin = Ui.dp(this, 8);
        card.addView(head, hlp);

        preview = new PreviewView(this);
        LinearLayout.LayoutParams plp = new LinearLayout.LayoutParams(-1, Ui.dp(this, 200));
        plp.leftMargin = Ui.dp(this, 4);
        plp.rightMargin = Ui.dp(this, 4);
        card.addView(preview, plp);
        preview.onConfigChanged(cfg);

        root.addView(card, lp(-1, -2, 14));
    }

    // ------------------------------------------------------------------ 三大类

    /** Keyboard Color：键周粒子/波纹 */
    private void buildKeyboardSection(LinearLayout root) {
        LinearLayout body = section(root, "键盘特效",
                "按下时在按键周围溅射粒子、扩散涟漪或光晕", cfg.kcOn, new BoolSetter() {
                    @Override
                    public void on(boolean v) {
                        cfg.kcOn = v;
                        apply();
                    }
                });

        chipRow(body, "样式", FxConfig.KC_STYLE_NAMES, cfg.kcStyle, new IntSetter() {
            @Override
            public void on(int v) {
                cfg.kcStyle = v;
                apply();
            }
        });
        chipRow(body, "配色", FxConfig.THEME_NAMES, cfg.kcTheme, new IntSetter() {
            @Override
            public void on(int v) {
                cfg.kcTheme = v;
                apply();
            }
        });
        slider(body, "粒子量", 1, 10, cfg.kcDensity, new IntSetter() {
            @Override
            public void on(int v) {
                cfg.kcDensity = v;
                apply();
            }
        });
        slider(body, "大小", 1, 10, cfg.kcSize, new IntSetter() {
            @Override
            public void on(int v) {
                cfg.kcSize = v;
                apply();
            }
        });
        slider(body, "扩散速度", 1, 10, cfg.kcSpeed, new IntSetter() {
            @Override
            public void on(int v) {
                cfg.kcSpeed = v;
                apply();
            }
        });
        switchRow(body, "特效限制在按键范围内", cfg.kcClip, new BoolSetter() {
            @Override
            public void on(boolean v) {
                cfg.kcClip = v;
                apply();
            }
        });
    }

    /** Key Color：键内配色/图案 */
    private void buildKeySection(LinearLayout root) {
        LinearLayout body = section(root, "按键配色",
                "按键内部的颜色变化与图案（纯色 / 渐变 / 流光 / 斜纹…）", cfg.klOn, new BoolSetter() {
                    @Override
                    public void on(boolean v) {
                        cfg.klOn = v;
                        apply();
                    }
                });

        chipRow(body, "样式", FxConfig.KL_STYLE_NAMES, cfg.klStyle, new IntSetter() {
            @Override
            public void on(int v) {
                cfg.klStyle = v;
                apply();
            }
        });
        colorRow(body, cfg.klColor, new IntSetter() {
            @Override
            public void on(int v) {
                cfg.klColor = v;
                apply();
            }
        });
        slider(body, "不透明度", 0, 100, cfg.klAlpha, new IntSetter() {
            @Override
            public void on(int v) {
                cfg.klAlpha = v;
                apply();
            }
        });
        switchRow(body, "只在按下时着色", cfg.klPressedOnly, new BoolSetter() {
            @Override
            public void on(boolean v) {
                cfg.klPressedOnly = v;
                apply();
            }
        });
    }

    /** Key Motion：按键自身动效 */
    private void buildMotionSection(LinearLayout root) {
        LinearLayout body = section(root, "按键动效",
                "按下时按键自身的缩放 / 弹跳 / 果冻形变", cfg.kmOn, new BoolSetter() {
                    @Override
                    public void on(boolean v) {
                        cfg.kmOn = v;
                        apply();
                    }
                });

        chipRow(body, "样式", FxConfig.KM_STYLE_NAMES, cfg.kmStyle, new IntSetter() {
            @Override
            public void on(int v) {
                cfg.kmStyle = v;
                apply();
            }
        });
        slider(body, "幅度", 1, 10, cfg.kmStrength, new IntSetter() {
            @Override
            public void on(int v) {
                cfg.kmStrength = v;
                apply();
            }
        });
        slider(body, "时长", 1, 10, cfg.kmDur, new IntSetter() {
            @Override
            public void on(int v) {
                cfg.kmDur = v;
                apply();
            }
        });
    }

    /** 底部：启用步骤说明 */
    private void buildFooter(LinearLayout root) {
        LinearLayout card = Ui.card(this);
        card.addView(Ui.text(this, "怎么让它生效", 14, Ui.TEXT, true));

        String[] steps = {
                "1. 打开 LSPosed → 模块 → 启用「键彩」",
                "2. 在作用域里勾选「微信输入法」（com.tencent.wetype）",
                "3. 强制停止微信输入法，再重新唤出键盘",
                "",
                "之后在任意输入框里打字即可；改样式不用重启，1 秒内自动生效。"
        };
        for (String s : steps) {
            TextView t = Ui.text(this, s, 12, s.startsWith("之后") ? Ui.ACCENT : Ui.SUB, false);
            t.setLineSpacing(Ui.dp(this, 3), 1f);
            card.addView(t, lp(-1, -2, s.isEmpty() ? 2 : 7));
        }
        root.addView(card, lp(-1, -2, 14));

        TextView foot = Ui.text(this, "在 LSPosed 中启用后，本页面可随时调整；未启用时预览照常工作。", 10.5f, 0xFF5A6478, false);
        foot.setGravity(Gravity.CENTER);
        root.addView(foot, lp(-1, -2, 14));
    }

    // ------------------------------------------------------------------ 组件

    /**
     * 造一个「分区卡片」：标题 + 右侧开关 + 说明 + 可置灰的内容区。
     *
     * <p>分区开关关闭时把内容区 alpha 降到 0.35，比直接禁用所有控件更直观
     * （用户还能点开看看参数，只是知道它当前不生效）。
     *
     * @return 内容区容器，调用方往里塞控件
     */
    private LinearLayout section(LinearLayout root, String title, String desc, boolean on, BoolSetter setter) {
        LinearLayout card = Ui.card(this);

        LinearLayout head = new LinearLayout(this);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setGravity(Gravity.CENTER_VERTICAL);

        // 标题前的小圆点，纯装饰
        View dot = new View(this);
        dot.setBackground(Ui.round(Ui.ACCENT, Ui.dp(this, 5), 0, 0));
        LinearLayout.LayoutParams dlp = new LinearLayout.LayoutParams(Ui.dp(this, 7), Ui.dp(this, 7));
        dlp.rightMargin = Ui.dp(this, 9);
        head.addView(dot, dlp);

        head.addView(Ui.text(this, title, 17, Ui.TEXT, true),
                new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        final Switch sw = newSwitch(on);
        head.addView(sw);
        card.addView(head);

        TextView d = Ui.text(this, desc, 11.5f, Ui.SUB, false);
        card.addView(d, lp(-1, -2, 6));

        final LinearLayout body = new LinearLayout(this);
        body.setOrientation(LinearLayout.VERTICAL);
        card.addView(body, lp(-1, -2, 6));
        body.setAlpha(on ? 1f : 0.35f);

        sw.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton b, boolean v) {
                setter.on(v);
                body.setAlpha(v ? 1f : 0.35f);
            }
        });

        root.addView(card, lp(-1, -2, 14));
        return body;
    }

    /**
     * 一行可换行的芯片（单选）。
     *
     * @param names 选项名
     * @param cur   当前选中下标
     */
    private void chipRow(LinearLayout body, String label, final String[] names, int cur, final IntSetter setter) {
        body.addView(Ui.text(this, label, 11.5f, Ui.SUB, false), lp(-1, -2, 14));

        FlowLayout flow = new FlowLayout(this, Ui.dp(this, 7));
        final TextView[] chips = new TextView[names.length];
        for (int i = 0; i < names.length; i++) {
            final int idx = i;
            final TextView chip = Ui.chip(this, names[i], i == cur);
            chips[i] = chip;
            chip.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    // 手动维护单选态：全部置为未选，再把点中的设成选中
                    for (int k = 0; k < chips.length; k++) Ui.setChipState(chips[k], k == idx);
                    setter.on(idx);
                }
            });
            flow.addView(chip);
        }
        body.addView(flow, lp(-1, -2, 8));
    }

    /** 一行圆形色点（单选）。0 号「自动」画成卡片底色，因为它不是具体颜色 */
    private void colorRow(LinearLayout body, int cur, final IntSetter setter) {
        body.addView(Ui.text(this, "颜色", 11.5f, Ui.SUB, false), lp(-1, -2, 14));

        FlowLayout flow = new FlowLayout(this, Ui.dp(this, 8));
        final View[] dots = new View[Palette.COLOR_NAMES.length];
        for (int i = 0; i < Palette.COLOR_NAMES.length; i++) {
            final int idx = i;
            final int fill = Palette.COLORS[i] == 0 ? Ui.CARD_HI : Palette.COLORS[i];
            final View dot = new View(this);
            int size = Ui.dp(this, 30);
            LinearLayout.LayoutParams dlp = new LinearLayout.LayoutParams(size, size);
            dot.setLayoutParams(dlp);
            dot.setBackground(dotBg(fill, i == cur, Palette.COLOR_NAMES[i]));
            dots[i] = dot;
            dot.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    for (int k = 0; k < dots.length; k++) {
                        dots[k].setBackground(dotBg(Palette.COLORS[k] == 0 ? Ui.CARD_HI : Palette.COLORS[k], k == idx,
                                Palette.COLOR_NAMES[k]));
                    }
                    setter.on(idx);
                }
            });
            flow.addView(dot);
        }
        body.addView(flow, lp(-1, -2, 8));
    }

    /**
     * 色点背景。
     *
     * <p>三种描边：选中 → 白圈（醒目）；墨黑 → 用主题描边色（否则黑点看不见）；
     * 其余 → 半透明白（浅色点也能看出边界）。
     */
    private GradientDrawable dotBg(int fill, boolean selected, String name) {
        GradientDrawable d = new GradientDrawable();
        d.setShape(GradientDrawable.OVAL);
        d.setColor(fill);
        if (selected) {
            d.setStroke(Ui.dp(this, 2), Ui.TEXT);
        } else if ("墨黑".equals(name)) {
            d.setStroke(Ui.dp(this, 1), Ui.STROKE);
        } else {
            d.setStroke(Ui.dp(this, 1), 0x33FFFFFF);
        }
        return d;
    }

    /**
     * 带数值显示的滑杆。
     *
     * @param min 最小值（SeekBar 只能从 0 开始，所以内部做偏移）
     */
    private void slider(LinearLayout body, String label, int min, int max, int cur, final IntSetter setter) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.addView(Ui.text(this, label, 11.5f, Ui.SUB, false),
                new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        final TextView val = Ui.text(this, String.valueOf(cur), 11.5f, Ui.ACCENT, true);
        row.addView(val);
        body.addView(row, lp(-1, -2, 12));

        SeekBar sb = new SeekBar(this);
        sb.setMax(max - min);
        sb.setProgress(Math.max(0, Math.min(max - min, cur - min)));
        int[][] states = {{android.R.attr.state_enabled}};
        sb.setProgressTintList(new ColorStateList(states, new int[]{Ui.ACCENT}));
        sb.setThumbTintList(new ColorStateList(states, new int[]{Ui.ACCENT}));
        sb.setProgressBackgroundTintList(new ColorStateList(new int[][]{{}}, new int[]{0xFF2A3240}));
        sb.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar s, int progress, boolean fromUser) {
                int v = min + progress;
                val.setText(String.valueOf(v));
                // 只有用户拖动才写配置；程序设置初始值时不触发保存
                if (fromUser) setter.on(v);
            }

            @Override
            public void onStartTrackingTouch(SeekBar s) {
            }

            @Override
            public void onStopTrackingTouch(SeekBar s) {
            }
        });
        body.addView(sb, lp(-1, -2, -2));
    }

    /** 一行「文字 + 开关」 */
    private void switchRow(LinearLayout body, String label, boolean on, final BoolSetter setter) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.addView(Ui.text(this, label, 12, Ui.SUB, false),
                new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        Switch sw = newSwitch(on);
        sw.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton b, boolean v) {
                setter.on(v);
            }
        });
        row.addView(sw);
        body.addView(row, lp(-1, -2, 10));
    }

    /** 统一配色的开关（默认的紫色 Switch 与整体色调不搭） */
    private Switch newSwitch(boolean on) {
        Switch s = new Switch(this);
        s.setChecked(on);
        int[][] st = {{android.R.attr.state_checked}, {}};
        s.setThumbTintList(new ColorStateList(st, new int[]{Ui.ACCENT, 0xFF79839A}));
        s.setTrackTintList(new ColorStateList(st, new int[]{0x6600E5FF, 0xFF2A3240}));
        return s;
    }

    /** 快速造 LinearLayout.LayoutParams；{@code -1/-2} 即 MATCH_PARENT/WRAP_CONTENT */
    private LinearLayout.LayoutParams lp(int w, int h, int topMarginDp) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(w, h);
        if (topMarginDp > 0) p.topMargin = Ui.dp(this, topMarginDp);
        return p;
    }

    // ------------------------------------------------------------------ 保存

    /**
     * 任意控件改动后都走这里：钳制 → 保存 → 刷新预览 → 必要时提示授权。
     *
     * <p>保存失败（写不进 {@code Settings.Global}）时只提示一次，并给出可直接
     * 复制执行的命令——这是新用户最常踩的坑。
     */
    private void apply() {
        cfg.sanitize();
        boolean ok = Store.save(this, cfg);
        if (preview != null) preview.onConfigChanged(cfg);
        if (!ok && !grantedHinted) {
            grantedHinted = true;
            Toast.makeText(this,
                    "设置已保存，但无法写入系统通道。请在电脑上执行：adb shell su -c \"pm grant com.keyscafe.wetype android.permission.WRITE_SECURE_SETTINGS\"",
                    Toast.LENGTH_LONG).show();
        }
    }
}
