#!/usr/bin/env bash
#
# 键彩 · 构建脚本
#
# 不依赖 Gradle，直接用 aapt2 + javac + d8 + zipalign + apksigner 拼一个 APK。
# 这样构建链只有 5 个命令行工具，出问题一眼能看出是哪一步。
#
# ┌─ 需要的环境 ────────────────────────────────────────────────┐
# │ 1. JDK 17+                       （javac / jar / keytool）  │
# │ 2. Android SDK                   （build-tools、platform 34）│
# │ 3. libxposed API 102 的 classes.jar                          │
# │    → 见 libs/README.md，放到 libs/api102/classes.jar         │
# │ 4. zip                                                       │
# └──────────────────────────────────────────────────────────────┘
#
# 环境变量（都有默认值，按需覆盖）：
#   JAVA_HOME      JDK 根目录
#   ANDROID_HOME   Android SDK 根目录（也认 ANDROID_SDK_ROOT）
#   API_JAR        libxposed classes.jar 的路径
#   ZIP_LEVEL      压缩级别，默认 9
#
# 用法：
#   bash build.sh          # 产出 KeysCafe.apk
#   bash install.sh        # 安装到已连接的设备
#
set -euo pipefail

HERE="$(cd "$(dirname "$0")" && pwd)"
OUT="$HERE/build"

die() { echo "!! $*" >&2; exit 1; }

# ---------------------------------------------------------------------------
# Windows / MSYS 上路径要转成 `C:/x/y` 形式，否则 javac、adb 会不认
# ---------------------------------------------------------------------------
tom() {
  if command -v cygpath >/dev/null 2>&1; then cygpath -m "$1"; else printf '%s' "$1"; fi
}

# 同一个工具在 Windows 上带 .exe/.bat 后缀，Unix 上没有。
# 注意不能用 -x 判断：Git 检出的 .bat 在 MSYS 下没有执行位，但能被正常调用。
pick() {
  for c in "$@"; do
    [ -f "$c" ] && { printf '%s' "$c"; return 0; }
  done
  return 1
}

# ---------------------------------------------------------------------------
# 1. JDK
# ---------------------------------------------------------------------------
if [ -n "${JAVA_HOME:-}" ] && [ -x "$JAVA_HOME/bin/javac" ]; then
  JAVAC="$JAVA_HOME/bin/javac"
elif [ -n "${JAVA_HOME:-}" ] && [ -x "$JAVA_HOME/bin/javac.exe" ]; then
  JAVAC="$JAVA_HOME/bin/javac.exe"
else
  JAVAC="$(command -v javac || true)"
  [ -n "$JAVAC" ] || die "找不到 javac。请安装 JDK 17+ 并设置 JAVA_HOME。"
  JAVA_HOME="$(cd "$(dirname "$(readlink -f "$JAVAC" 2>/dev/null || echo "$JAVAC")")/.." && pwd)"
fi
JAR="$(pick "$JAVA_HOME/bin/jar" "$JAVA_HOME/bin/jar.exe")" || die "找不到 jar"
KEYTOOL="$(pick "$JAVA_HOME/bin/keytool" "$JAVA_HOME/bin/keytool.exe")" || KEYTOOL=""

# ---------------------------------------------------------------------------
# 2. Android SDK
# ---------------------------------------------------------------------------
SDK="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}"
if [ -z "$SDK" ]; then
  for c in "$HOME/Android/Sdk" "$HOME/Library/Android/sdk" "$LOCALAPPDATA/Android/Sdk"; do
    [ -d "$c" ] && { SDK="$c"; break; }
  done
fi
[ -n "$SDK" ] && [ -d "$SDK" ] || die "找不到 Android SDK。请设置 ANDROID_HOME。"

# 取版本号最大的 build-tools
BT="$(ls -d "$SDK"/build-tools/*/ 2>/dev/null | sed 's:/*$::' | sort -V | tail -1)"
[ -n "$BT" ] || die "找不到 build-tools，请用 sdkmanager 安装 build-tools;34.0.0"
AAPT2="$(pick "$BT/aapt2" "$BT/aapt2.exe")" || die "找不到 aapt2"
ZIPALIGN="$(pick "$BT/zipalign" "$BT/zipalign.exe")" || die "找不到 zipalign"
APKSIGNER="$(pick "$BT/apksigner" "$BT/apksigner.bat" "$BT/apksigner")" || die "找不到 apksigner"
D8="$(pick "$BT/d8" "$BT/d8.bat")" || die "找不到 d8"

# android.jar 优先用 34，没有就退回最新的
AJAR_PATH=""
for p in "$SDK/platforms/android-34/android.jar" "$(ls -d "$SDK"/platforms/android-*/android.jar 2>/dev/null | sort -V | tail -1)"; do
  [ -f "$p" ] && { AJAR_PATH="$p"; break; }
done
[ -n "$AJAR_PATH" ] || die "找不到 android.jar，请安装 platforms;android-34"
AJAR="$(tom "$AJAR_PATH")"

# ---------------------------------------------------------------------------
# 3. libxposed API
# ---------------------------------------------------------------------------
API_PATH="${API_JAR:-}"
if [ -z "$API_PATH" ]; then
  for c in "$HERE/libs/api102/classes.jar" "$HERE/../tools/libs/api102/classes.jar"; do
    [ -f "$c" ] && { API_PATH="$c"; break; }
  done
fi
[ -n "$API_PATH" ] && [ -f "$API_PATH" ] || die "找不到 libxposed API 的 classes.jar
   下载 https://repo1.maven.org/maven2/io/github/libxposed/api/102.0.0/api-102.0.0.aar
   解出里面的 classes.jar 放到 libs/api102/classes.jar（详见 libs/README.md）"
API="$(tom "$API_PATH")"

# 类路径分隔符：Windows 是 ;，其它是 :
case "$(uname -s)" in
  MINGW*|MSYS*|CYGWIN*) SEP=';' ;;
  *) SEP=':' ;;
esac

echo "==> 环境"
echo "    JDK        $JAVA_HOME"
echo "    build-tools $BT"
echo "    android.jar $AJAR_PATH"
echo "    api.jar    $API_PATH"

# ---------------------------------------------------------------------------
# 构建
# ---------------------------------------------------------------------------
rm -rf "$OUT"
mkdir -p "$OUT/res" "$OUT/gen" "$OUT/classes" "$OUT/dex"

echo "==> 编译资源"
"$AAPT2" compile --dir "$HERE/res" -o "$OUT/res/resources.zip"

echo "==> 链接资源"
"$AAPT2" link \
  -o "$OUT/base.apk" \
  -I "$AJAR" \
  --manifest "$HERE/AndroidManifest.xml" \
  -R "$OUT/res/resources.zip" \
  --java "$OUT/gen" \
  --min-sdk-version 29 \
  --target-sdk-version 34 \
  --version-code 1 \
  --version-name 1.0 \
  --auto-add-overlay

echo "==> 编译 Java"
# javac 只认 C:/x/y 形式，逐行转换（@file 不支持命令行参数里的转义）
: > "$OUT/sources.txt"
while IFS= read -r f; do tom "$f" >> "$OUT/sources.txt"; echo >> "$OUT/sources.txt"; done \
  < <(find "$HERE/src" "$OUT/gen" -name '*.java')

# -J-Duser.language=en：否则中文环境下的 javac 报错信息是乱码，不好看也不好搜
"$JAVAC" -J-Duser.language=en -J-Duser.country=US -nowarn \
  -source 11 -target 11 -encoding UTF-8 \
  -classpath "$AJAR$SEP$API" \
  -d "$OUT/classes" \
  @"$OUT/sources.txt"

[ -f "$OUT/classes/com/keyscafe/wetype/Module.class" ] || die "编译失败：Module.class 没生成"

echo "==> dex"
"$JAR" cf "$OUT/classes.jar" -C "$OUT/classes" .
"$D8" --release --min-api 29 --lib "$AJAR" --output "$OUT/dex" "$OUT/classes.jar"

echo "==> 打包"
cp "$OUT/base.apk" "$OUT/app.apk"
( cd "$OUT" && zip -q -j app.apk dex/classes.dex )
# Xposed 模块的元数据必须原样放进 META-INF/xposed/，框架靠它识别模块
mkdir -p "$OUT/meta/META-INF/xposed"
cp "$HERE/meta/module.prop" "$HERE/meta/java_init.list" "$HERE/meta/scope.list" "$OUT/meta/META-INF/xposed/"
( cd "$OUT/meta" && zip -q -r "$OUT/app.apk" META-INF )

echo "==> 对齐 + 签名"
KS="${KEYSTORE:-$HERE/keystore.jks}"
if [ ! -f "$KS" ]; then
  echo "    没有密钥库，生成一个（口令 android，仅用于本地调试）"
  [ -n "$KEYTOOL" ] || die "找不到 keytool"
  "$KEYTOOL" -genkeypair -keystore "$KS" -alias k -keyalg RSA -keysize 2048 \
    -validity 10000 -storepass android -keypass android \
    -dname "CN=KeysCafe, O=KeysCafe" > /dev/null 2>&1
fi
"$ZIPALIGN" -f -p 4 "$OUT/app.apk" "$OUT/app-aligned.apk"
"$APKSIGNER" sign --ks "$KS" --ks-pass pass:android --key-pass pass:android \
  --v1-signing-enabled true --v2-signing-enabled true \
  --out "$HERE/KeysCafe.apk" "$OUT/app-aligned.apk"

ls -lh "$HERE/KeysCafe.apk"
echo "OK"
