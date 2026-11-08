#!/usr/bin/env bash
# =============================================================================
# build-tsnet-aar.sh
#
# 产出 app/libs/tsnet.aar + tsnet-sources.jar —— Tailscale 集成（tsnet）的
# gomobile 绑定库。源码在 <repo>/tsnet-android/（薄 wrapper，tailscale 本体
# 由 go.mod 按版本拉取）。
#
# 流程：
#   1) go build ./... 宿主机语法/依赖校验
#   2) gomobile bind -target=android/arm64 产出 AAR
#   3) AAR 与 sources jar 回填到 <repo>/app/libs/
#
# 用法：
#   scripts/build-tsnet-aar.sh
#
# 环境变量（均有默认值，可按本机覆盖）：
#   GOROOT       Go 安装目录（默认 $HOME/go1.27）
#   ANDROID_HOME Android SDK（默认 /opt/android-sdk）
#   GOPROXY      Go 模块代理（默认 https://goproxy.cn,direct）
# =============================================================================
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "$0")/.." && pwd)"
SRC_DIR="$REPO_ROOT/tsnet-android"
OUT_LIBS="$REPO_ROOT/app/libs"

export GOROOT="${GOROOT:-$HOME/go1.27}"
export PATH="$GOROOT/bin:$HOME/go/bin:$PATH"
export GOPATH="${GOPATH:-$HOME/go}"
export ANDROID_HOME="${ANDROID_HOME:-/opt/android-sdk}"
export GOPROXY="${GOPROXY:-https://goproxy.cn,direct}"
# 固定使用本地工具链，防止 go 自动联网下载新版本工具链
export GOTOOLCHAIN=local

command -v go >/dev/null || { echo "错误：$GOROOT/bin 下找不到 go"; exit 1; }
[ -d "$ANDROID_HOME" ] || { echo "错误：ANDROID_HOME=$ANDROID_HOME 不存在"; exit 1; }

cd "$SRC_DIR"

echo "==> 1/3 宿主机编译校验"
go build ./...

echo "==> 2/3 gomobile bind（首次需编译全部依赖，耗时数分钟）"
go run golang.org/x/mobile/cmd/gomobile bind \
    -target=android/arm64 -androidapi=21 \
    -o tsnet.aar .

echo "==> 3/3 回填产物到 app/libs/"
cp tsnet.aar tsnet-sources.jar "$OUT_LIBS/"
ls -lh "$OUT_LIBS/tsnet.aar" "$OUT_LIBS/tsnet-sources.jar"

echo "==> 完成。重新构建 App 请执行：scripts/build-app.sh release"
