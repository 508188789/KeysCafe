package com.keyscafe.wetype;

/**
 * 配置的跨进程通道：{@code Settings.Global} 里的一个字符串键。
 *
 * <h2>为什么是 Settings.Global</h2>
 * 模块 App 与微信输入法是两个独立进程，配置要传过去。试过并失败的路子（见 README 踩坑表）：
 * ContentProvider 被 Android 11+ 包可见性挡住、模块私有目录被 SELinux per-app MLS 挡住、
 * {@code getRemotePreferences} 与 {@code openRemoteFile} 拿不到普通模块的文件。
 *
 * <p>{@code Settings.Global} 是系统设置，<b>所有应用可读</b>，不受包可见性限制，
 * 也不在 per-app 隔离的路径里，因此是唯一稳定的单向通道。
 *
 * <p>代价：写入需要 {@code WRITE_SECURE_SETTINGS}，这是系统级权限，只能靠
 * {@code adb shell pm grant}（或 root 的 {@code su -c pm grant}）一次性授权，
 * 普通安装流程给不了。安装脚本 {@code install.sh} 已经带了这一步。
 *
 * <p>注意：Manifest 里必须<b>声明</b>该权限，否则 {@code pm grant} 会静默失败
 * （权限没在包里出现，grant 找不到目标）。
 */
public final class GlobalKeys {

    /** 键名。改它会导致已安装用户升级后丢一次配置，非必要不要动 */
    public static final String KEY = "keyscafe_fx_config";

    private GlobalKeys() {
    }
}
