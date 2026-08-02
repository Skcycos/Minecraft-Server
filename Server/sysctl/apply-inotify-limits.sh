#!/usr/bin/env bash
# 将 inotify 限额写入系统并立即生效（需要 sudo）
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
SRC="${SCRIPT_DIR}/99-inotify-minecraft.conf"
DEST="/etc/sysctl.d/99-inotify-minecraft.conf"

if [[ ! -f "$SRC" ]]; then
  echo "找不到配置文件: $SRC" >&2
  exit 1
fi

echo "安装 $DEST ..."
sudo install -m 644 "$SRC" "$DEST"

echo "应用 sysctl ..."
sudo sysctl --system >/dev/null
# 确保当前会话立即生效
sudo sysctl -w fs.inotify.max_user_instances=512
sudo sysctl -w fs.inotify.max_user_watches=524288

echo
echo "当前值："
echo "  max_user_instances = $(cat /proc/sys/fs/inotify/max_user_instances)"
echo "  max_user_watches    = $(cat /proc/sys/fs/inotify/max_user_watches)"
echo
echo "完成。可正常启动服务端：./start.sh"
