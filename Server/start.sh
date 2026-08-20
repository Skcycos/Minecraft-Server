#!/usr/bin/env bash
cd "$(dirname "$0")"
export PATH="/home/tanrunn/.local/java/jdk-21.0.7+6/bin:$PATH"

while true; do
    echo "[启动脚本] 服务器启动中..."
    ./run.sh nogui "$@"
    echo "[启动脚本] 服务器已停止，5秒后自动重启..."
    sleep 5
done
