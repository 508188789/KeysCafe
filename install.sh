#!/usr/bin/env bash
#
# 键彩 · 安装 + 授权
#
# 做三件事：
#   1. 安装 APK
#   2. 授予 WRITE_SECURE_SETTINGS（配置跨进程传递必需）
#   3. 强制停止微信输入法，让模块在下次启动时加载
#
# 用法：
#   bash install.sh                 # 自动找 adb
#   ADB=/path/to/adb bash install.sh
#
set -uo pipefail

HERE="$(cd "$(dirname "$0")" && pwd)"

PKG="com.keyscafe.wetype"
IME="com.tencent.wetype"
PERM="android.permission.WRITE_SECURE_SETTINGS"

# 找 adb：环境变量 → PATH → 仓库旁边工具链（开发机常见布局）
if [ -n "${ADB:-}" ]; then
  :
elif command -v adb >/dev/null 2>&1; then
  ADB="adb"
elif [ -x "$HERE/../tools/platform-tools/adb.exe" ]; then
  ADB="$HERE/../tools/platform-tools/adb.exe"
elif [ -x "$HERE/../tools/platform-tools/adb" ]; then
  ADB="$HERE/../tools/platform-tools/adb"
else
  echo "!! 找不到 adb，请安装 platform-tools 或设置 ADB=/path/to/adb" >&2
  exit 1
fi

APK="$HERE/KeysCafe.apk"
[ -f "$APK" ] || { echo "!! 找不到 $APK，先执行 bash build.sh" >&2; exit 1; }

# 检查设备
if ! "$ADB" get-state >/dev/null 2>&1; then
  echo "!! 没有检测到设备。请确认已开启 USB 调试，并在手机上点「允许」。" >&2
  "$ADB" devices
  exit 1
fi

echo "==> 安装 $PKG"
"$ADB" install -r "$APK" || exit 1

echo "==> 授予 WRITE_SECURE_SETTINGS"
# 必须 su：Android 16 起 shell(uid 2000) 已无权 grant 签名级权限
if "$ADB" shell "su -c 'pm grant $PKG $PERM'" 2>/dev/null; then
  echo "    OK"
else
  echo "    失败。请确认设备已 root，然后手动执行："
  echo "    adb shell su -c 'pm grant $PKG $PERM'"
fi

echo "==> 重启输入法进程"
"$ADB" shell "su -c 'am force-stop $IME'" 2>/dev/null

cat <<'EOF'

安装完成。还需要在手机上做两步（只需一次）：

  1. 打开 LSPosed → 模块 → 勾选「键彩」
  2. 点进「键彩」→ 作用域 → 勾选「微信输入法」(com.tencent.wetype)
  3. 强制停止微信输入法一次（或在 LSPosed 里点「重启应用」）

之后打开任意输入框打字即可，改样式 1 秒内自动生效，不用重启。

看日志： adb logcat -s KeysCafe
EOF
