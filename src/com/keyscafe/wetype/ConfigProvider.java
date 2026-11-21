package com.keyscafe.wetype;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;

/**
 * 只读配置出口（<b>退路</b>，当前并未生效）。
 *
 * <p>设计意图：让微信输入法进程通过
 * {@code contentResolver.call("content://com.keyscafe.wetype.config", "get", ...)}
 * 拿到配置 JSON。
 *
 * <p>现实情况：Android 11 起有包可见性限制，输入法进程没有声明要访问我们，
 * 这个 call 会直接抛 {@code Unknown authority}——调用方不是我们，改不了它的
 * manifest 来加 {@code <queries>}。所以主通道改成了 {@code Settings.Global}。
 * 这个类保留着，一是如果将来把模块改成「输入法主动 query」的形态还能用，
 * 二是它无害（只暴露主题色参数，不含隐私），保留成本为零。
 *
 * <p>没有敏感数据，因此不设任何权限。
 */
public class ConfigProvider extends ContentProvider {

    public static final String AUTHORITY = "com.keyscafe.wetype.config";

    @Override
    public boolean onCreate() {
        return true;
    }

    /**
     * 唯一有意义的入口：{@code call("get")} 返回当前配置。
     *
     * <p>任何异常都降级为「返回默认配置」，绝不把异常抛回调用方——
     * 否则输入法侧捕获不到就会影响键盘启动。
     */
    @Override
    public Bundle call(String method, String arg, Bundle extras) {
        Bundle b = new Bundle();
        if ("get".equals(method)) {
            try {
                FxConfig c = Store.load(getContext());
                b.putString("json", c.toJson().toString());
            } catch (Throwable t) {
                b.putString("json", new FxConfig().toJson().toString());
            }
        }
        return b;
    }

    // ------------------------------------------------------------------
    // 以下为标准 ContentProvider 接口，本 Provider 不提供表式访问，全部空实现
    // ------------------------------------------------------------------

    @Override
    public Cursor query(Uri uri, String[] projection, String selection, String[] selectionArgs, String sortOrder) {
        return null;
    }

    @Override
    public String getType(Uri uri) {
        return null;
    }

    @Override
    public Uri insert(Uri uri, ContentValues values) {
        return null;
    }

    @Override
    public int delete(Uri uri, String selection, String[] selectionArgs) {
        return 0;
    }

    @Override
    public int update(Uri uri, ContentValues values, String selection, String[] selectionArgs) {
        return 0;
    }
}
