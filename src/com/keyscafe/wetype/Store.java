package com.keyscafe.wetype;

import android.content.Context;
import android.content.SharedPreferences;

import java.io.File;
import java.io.FileOutputStream;

/**
 * 模块 App 侧的配置存取（只跑在模块自己的进程里）。
 *
 * <p>一次保存会写三个地方，各有用途：
 * <ol>
 *   <li>{@code SharedPreferences("cfg")} —— 本进程自己读，最可靠；</li>
 *   <li>{@code Settings.Global} —— <b>主通道</b>，给被注入的输入法进程读；</li>
 *   <li>{@code files/keyscafe.json} —— 退路，给框架的 {@code openRemoteFile} 用。</li>
 * </ol>
 *
 * <p>第 3 项会把文件权限放开到「其他用户可读」、目录放开到「可穿越」，
 * 目的是让输入法进程能按绝对路径直读。里面只有主题色和几个 0~10 的整数，
 * 不含任何隐私数据，因此这个放宽是可接受的；如果你不喜欢，可以删掉
 * {@link #save} 里那段 setReadable/setExecutable，只靠前两条通道工作。
 */
public final class Store {

    private static final String SP = "cfg";
    private static final String KEY = "json";

    /** 与输入法进程约定的文件名（框架通道会按这个名字找） */
    public static final String FILE = "keyscafe.json";

    private Store() {
    }

    public static SharedPreferences sp(Context c) {
        return c.getSharedPreferences(SP, Context.MODE_PRIVATE);
    }

    /**
     * 读配置。优先 SharedPreferences，其次退回到 JSON 文件；
     * 都没有则返回默认配置（全开）。
     */
    public static FxConfig load(Context c) {
        String s = sp(c).getString(KEY, null);
        if (s == null) {
            try {
                File f = new File(c.getFilesDir(), FILE);
                if (f.exists()) s = read(f);
            } catch (Throwable ignored) {
            }
        }
        return s == null ? new FxConfig() : FxConfig.fromString(s);
    }

    /**
     * 保存并向输入法进程发布配置。
     *
     * @return true 表示成功写进 {@code Settings.Global}——也就是输入法真的能读到。
     *         返回 false 时设置页会提示用户去执行 {@code pm grant}。
     */
    public static boolean save(Context c, FxConfig cfg) {
        String json = cfg.toJson().toString();
        sp(c).edit().putString(KEY, json).apply();

        // 主通道：写进系统设置，输入法进程直接可读
        boolean published = false;
        try {
            published = android.provider.Settings.Global.putString(
                    c.getContentResolver(), GlobalKeys.KEY, json);
        } catch (Throwable ignored) {
            // 没有 WRITE_SECURE_SETTINGS 时会抛 SecurityException，这里降级不报错
        }

        // 退路：落一份文件，并放开读权限（框架 openRemoteFile / 直读都用得上）
        try {
            File dir = c.getFilesDir();
            File f = new File(dir, FILE);
            FileOutputStream o = new FileOutputStream(f);
            o.write(json.getBytes("UTF-8"));
            o.flush();
            o.close();
            // 目录需要 o+x（可穿越），文件需要 o+r。只放开这一个文件，不动其它。
            f.setReadable(true, false);
            dir.setExecutable(true, false);
            File data = dir.getParentFile();
            if (data != null) data.setExecutable(true, false);
        } catch (Throwable ignored) {
        }
        return published;
    }

    private static String read(File f) {
        try (java.io.FileInputStream in = new java.io.FileInputStream(f)) {
            byte[] b = new byte[(int) f.length()];
            int n = in.read(b);
            return n > 0 ? new String(b, 0, n, "UTF-8") : null;
        } catch (Throwable t) {
            return null;
        }
    }
}
