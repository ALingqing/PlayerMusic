#!/usr/bin/env bash
# ============================================================
#  PlayerMusic - 一键安装 ffmpeg 脚本
#  支持:
#   - root/sudo: 用 apt 安装 (Debian/Ubuntu)
#   - 无 root:   下载 ffmpeg 静态构建到用户目录
#   - 自动检测架构 (x86_64 / aarch64)
#  用法:
#   bash install-ffmpeg.sh
# ============================================================

set -e

# 颜色
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
RED='\033[0;31m'
NC='\033[0m'

info()  { echo -e "${GREEN}[INFO]${NC} $*"; }
warn()  { echo -e "${YELLOW}[WARN]${NC} $*"; }
error() { echo -e "${RED}[ERROR]${NC} $*"; }

# 检测是否已有 ffmpeg
if command -v ffmpeg >/dev/null 2>&1; then
    ffmpeg -version 2>&1 | head -n 1
    info "ffmpeg 已安装，无需重复安装。"
    exit 0
fi

# 检测架构
ARCH="$(uname -m)"
case "$ARCH" in
    x86_64|amd64)  BIN_ARCH="amd64";;
    aarch64|arm64) BIN_ARCH="arm64";;
    *) error "不支持的架构: $ARCH (仅支持 x86_64 / aarch64)"; exit 1;;
esac
info "检测到架构: $ARCH"

# 检测是否有 root / sudo
HAVE_SUDO=0
if [ "$(id -u)" = "0" ]; then
    HAVE_SUDO=1
elif command -v sudo >/dev/null 2>&1; then
    HAVE_SUDO=1
fi

# ============ 方式一: apt 安装 (有 root/sudo) ============
if [ "$HAVE_SUDO" = "1" ]; then
    info "检测到 root/sudo，使用 apt 安装 ffmpeg..."
    SUDO=""
    [ "$(id -u)" != "0" ] && SUDO="sudo"
    $SUDO apt-get update -y
    $SUDO apt-get install -y ffmpeg
    if command -v ffmpeg >/dev/null 2>&1; then
        info "ffmpeg 安装成功！"
        ffmpeg -version 2>&1 | head -n 1
        exit 0
    fi
    warn "apt 安装失败或 ffmpeg 不在 PATH，尝试下载静态构建..."
fi

# ============ 方式二: 静态构建 (无 root / apt 失败) ============
info "正在下载 ffmpeg 静态构建 (arch: $BIN_ARCH)..."

# 安装目录: 优先用户目录，避免权限问题
if [ -n "$HOME" ] && [ -w "$HOME" ]; then
    INSTALL_DIR="$HOME/ffmpeg"
else
    INSTALL_DIR="$(pwd)/ffmpeg"
fi
mkdir -p "$INSTALL_DIR/bin"

# johnvansickle 静态构建 (Linux x86_64 / arm64)
BASE_URL="https://johnvansickle.com/ffmpeg/releases/ffmpeg-release-${BIN_ARCH}-static.tar.xz"

TMP_DIR="$(mktemp -d)"
cd "$TMP_DIR"

if command -v curl >/dev/null 2>&1; then
    curl -fL -o ffmpeg.tar.xz "$BASE_URL" || { error "下载失败，请检查网络或手动下载"; exit 1; }
elif command -v wget >/dev/null 2>&1; then
    wget -O ffmpeg.tar.xz "$BASE_URL" || { error "下载失败，请检查网络或手动下载"; exit 1; }
else
    error "未找到 curl 或 wget，无法下载。请先安装其一。"
    exit 1
fi

# 解压
tar -xf ffmpeg.tar.xz || { error "解压失败"; exit 1; }

# 复制二进制
FFMPEG_BIN="$(find . -type f -name ffmpeg -path '*bin*' | head -n 1)"
FFPROBE_BIN="$(find . -type f -name ffprobe -path '*bin*' | head -n 1)"

if [ -n "$FFMPEG_BIN" ]; then
    cp "$FFMPEG_BIN" "$INSTALL_DIR/bin/"
    [ -n "$FFPROBE_BIN" ] && cp "$FFPROBE_BIN" "$INSTALL_DIR/bin/"
    chmod +x "$INSTALL_DIR/bin/"*
    info "ffmpeg 已安装到: $INSTALL_DIR/bin/ffmpeg"
else
    error "未在下载包中找到 ffmpeg 可执行文件。"
    exit 1
fi

cd /
rm -rf "$TMP_DIR"

# ============ 尝试加入 PATH ============
SHELL_CONFIG=""
if [ -f "$HOME/.bashrc" ]; then SHELL_CONFIG="$HOME/.bashrc"; fi
if [ -f "$HOME/.profile" ]; then SHELL_CONFIG="$HOME/.profile"; fi

if [ -n "$SHELL_CONFIG" ]; then
    if ! grep -q "$INSTALL_DIR/bin" "$SHELL_CONFIG" 2>/dev/null; then
        echo "" >> "$SHELL_CONFIG"
        echo "# PlayerMusic ffmpeg" >> "$SHELL_CONFIG"
        echo "export PATH=\"$INSTALL_DIR/bin:\$PATH\"" >> "$SHELL_CONFIG"
        info "已将 ffmpeg 加入 PATH: $SHELL_CONFIG"
    fi
fi

# 立即验证 (当前会话)
export PATH="$INSTALL_DIR/bin:$PATH"
if command -v ffmpeg >/dev/null 2>&1; then
    info "ffmpeg 安装成功！"
    ffmpeg -version 2>&1 | head -n 1
    info "请执行: source $SHELL_CONFIG  (或重启服务器) 使 PATH 永久生效"
else
    warn "ffmpeg 已下载到 $INSTALL_DIR/bin，但当前会话未识别。"
    warn "插件会自动检测该路径，重启服务器即可使用。"
fi

echo ""
info "完成！PlayerMusic 插件将自动检测 ffmpeg。"
