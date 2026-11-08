#!/usr/bin/env bash
# =============================================================================
# build-app.sh
#
# 产出 app-debug.apk —— 即设计文档 §11 里程碑 3/4：
# 应用工程消费 app/libs/freeRDPCore-release.aar，assembleDebug → 校验 → 回填仓库。
#
# 流程：
#   1) rsync 仓库到 /tmp 工作区（共享挂载盘（CIFS/vboxsf）不支持符号链接，不能就地构建）
#   2) 写入 local.properties（sdk.dir 指向工作区持久化 SDK）
#   3) ./gradlew :app:assembleDebug（或 assembleRelease）
#   4) 校验 APK 内容、权限清单并复制回 <repo>/app-debug.apk（release 为 app-release.apk）
#
# 用法：
#   scripts/build-app.sh                    # 全流程（debug）
#   scripts/build-app.sh release            # 全流程（release，R8 + 资源压缩）
#   WORKDIR=/tmp/other scripts/build-app.sh # 指定工作区
#
# 前置条件：
#   - app/libs/freeRDPCore-release.aar（由 scripts/build-freerdpcore-aar.sh 产出）
#   - Android SDK：platform 37.0 / build-tools 37.0.0
#     默认位于 <workspace>/tools/android-sdk（随工作区持久化，重启不丢）；
#     可用 ANDROID_HOME 覆盖
#   - JDK 17（默认 /usr/lib/jvm/java-17-openjdk-amd64）
#
# 说明：Gradle 依赖缓存用默认 ~/.gradle（在本地磁盘，重启不丢）；
#       不放进工作区是为了避开共享挂载盘的文件锁对 Gradle/SQLite 缓存的兼容性问题。
# =============================================================================
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
WORKDIR="${WORKDIR:-/tmp/anrdp2-app-build}"

log() { printf '\n\033[1m==> %s\033[0m\n' "$*"; }
die() { printf 'ERROR: %s\n' "$*" >&2; exit 1; }

VARIANT="${1:-debug}"
case "$VARIANT" in
  debug)   TASK="assembleDebug" ;;
  release) TASK="assembleRelease" ;;
  *) die "用法：scripts/build-app.sh [debug|release]" ;;
esac

# ---------------------------------------------------------------- 环境
export ANDROID_HOME="${ANDROID_HOME:-$REPO_ROOT/../tools/android-sdk}"
export ANDROID_SDK_ROOT="${ANDROID_SDK_ROOT:-$ANDROID_HOME}"
if [[ -z "${JAVA_HOME:-}" ]]; then
  for j in /usr/lib/jvm/java-17-openjdk-amd64 /usr/lib/jvm/java-21-openjdk-amd64; do
    if [[ -x "$j/bin/java" ]]; then export JAVA_HOME="$j"; break; fi
  done
fi

# ---------------------------------------------------------------- 前置检查
log "检查前置条件"
[[ -f "$REPO_ROOT/app/libs/freeRDPCore-release.aar" ]] \
  || die "缺少引擎 AAR：app/libs/freeRDPCore-release.aar（先跑 scripts/build-freerdpcore-aar.sh）"
[[ -d "$ANDROID_HOME/platforms/android-37.0" ]] || die "缺少 SDK platform 37.0：$ANDROID_HOME/platforms"
[[ -x "$ANDROID_HOME/build-tools/37.0.0/aapt2" ]] || die "缺少 build-tools 37.0.0：$ANDROID_HOME/build-tools"
[[ -n "${JAVA_HOME:-}" && -x "$JAVA_HOME/bin/java" ]] || die "找不到 JDK，请设置 JAVA_HOME"
for t in rsync unzip; do
  command -v "$t" >/dev/null 2>&1 || die "缺少宿主工具：$t"
done
echo "    ANDROID_HOME = $ANDROID_HOME"
echo "    JAVA_HOME    = $JAVA_HOME"
echo "    WORKDIR      = $WORKDIR"

# ---------------------------------------------------------------- 同步工作区
log "同步仓库到工作区（排除 .git/.gradle/build/.cxx/deps/local.properties）"
mkdir -p "$WORKDIR"
# deps/ 是引擎源码与原生依赖镜像（128MB），仅 AAR 构建需要，应用构建不拷贝
rsync -a --delete \
  --exclude '.git' --exclude '.gradle' --exclude 'build' --exclude '.cxx' \
  --exclude 'deps' --exclude 'local.properties' --exclude 'app-debug.apk' \
  "$REPO_ROOT"/ "$WORKDIR"/

# ---------------------------------------------------------------- SDK 指向
log "写入 local.properties（sdk.dir → $ANDROID_HOME）"
printf 'sdk.dir=%s\n' "$ANDROID_HOME" > "$WORKDIR/local.properties"

# 工作区文件可能因共享挂载带有 CRLF（挂载噪声），直接执行 shebang 会报
# 「无法执行：找不到需要的文件」；Gradle/Groovy/Javac 对 CRLF 免疫，
# 只需把要被执行的 shell 脚本转回 LF。
sed -i 's/\r$//' "$WORKDIR/gradlew"

# ---------------------------------------------------------------- 版本号
# 与 tag 保持一致：取当前提交可达的最近 v* tag；可用 APP_VERSION_NAME 覆盖
APP_VERSION_NAME="${APP_VERSION_NAME:-}"
if [[ -z "$APP_VERSION_NAME" ]]; then
  TAG="$(git -C "$REPO_ROOT" describe --tags --abbrev=0 2>/dev/null || true)"
  [[ "$TAG" == v* ]] && APP_VERSION_NAME="${TAG#v}"
fi
GRADLE_VERSION_ARGS=()
if [[ -n "$APP_VERSION_NAME" ]]; then
  GRADLE_VERSION_ARGS=("-PappVersionName=$APP_VERSION_NAME")
  echo "    VERSION      = $APP_VERSION_NAME"
fi

# ---------------------------------------------------------------- 编译
log "执行 :app:$TASK（AGP 9.2.1 + Gradle 9.6.1 + JDK 17，variant=$VARIANT）"
( cd "$WORKDIR" && ./gradlew ":app:$TASK" --console=plain "${GRADLE_VERSION_ARGS[@]}" )

# ---------------------------------------------------------------- 校验与回填
APK_OUT="$WORKDIR/app/build/outputs/apk/$VARIANT/app-$VARIANT.apk"
[[ -f "$APK_OUT" ]] || die "构建未产出 APK：$APK_OUT"

log "校验 APK 内容"
LISTING="$(unzip -l "$APK_OUT")"
has() { grep -q "$1" <<<"$LISTING"; }

has 'classes[0-9]*\.dex'                        || die "APK 缺少 dex"
has 'lib/arm64-v8a/libfreerdp-android\.so'      || die "APK 缺少 libfreerdp-android.so"
for so in libfreerdp3 libfreerdp-client3 libwinpr3 libssl libcrypto libsqlcipher; do
  has "lib/arm64-v8a/$so\.so" || die "APK 缺少 lib/arm64-v8a/$so.so"
done
grep -E 'classes[0-9]*\.dex|lib/arm64-v8a/' <<<"$LISTING"

log "校验权限（引擎 AAR 合并进来的未使用权限应已被 manifest 移除）"
AAPT2="$ANDROID_HOME/build-tools/37.0.0/aapt2"
if [[ -n "$APP_VERSION_NAME" ]]; then
  BADGING="$("$AAPT2" dump badging "$APK_OUT" 2>/dev/null || true)"
  VERSION="$(sed -n "s/.*versionName='\([^']*\)'.*/\1/p" <<<"$BADGING" | head -1)"
  [[ "$VERSION" == "$APP_VERSION_NAME" ]] || die "APK 版本号 $VERSION 与 tag 版本 $APP_VERSION_NAME 不一致"
  echo "    版本号：$VERSION"
fi
PERMS="$("$AAPT2" dump permissions "$APK_OUT" 2>/dev/null || true)"
for p in CAMERA RECORD_AUDIO READ_EXTERNAL_STORAGE WRITE_EXTERNAL_STORAGE POST_NOTIFICATIONS; do
  if grep -q "android.permission.$p'" <<<"$PERMS"; then
    die "APK 仍包含未使用权限：$p（检查 app/src/main/AndroidManifest.xml 的 tools:node=\"remove\"）"
  fi
done
for p in INTERNET ACCESS_NETWORK_STATE; do
  grep -q "android.permission.$p'" <<<"$PERMS" || die "APK 缺少必需权限：$p"
done
grep -oE "android.permission.[A-Z_]+" <<<"$PERMS" | sort -u

DEST="$REPO_ROOT/app-$VARIANT.apk"
cp "$APK_OUT" "$DEST"
printf '\n'
ls -lh "$DEST"
sha256sum "$DEST"

log "完成"
cat <<EOF
产物：$DEST
下一步（可选）：
  安装到真机 → adb install $DEST
  真机实测  → 连接真实 RDP 服务器，验证列表/编辑/键盘/触摸（里程碑 5）
EOF
