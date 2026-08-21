#!/usr/bin/env bash
cd "$(dirname "$0")"

# 强制使用 Java 21（Homebrew 安装的 Temurin）
export JAVA_HOME=$(/usr/libexec/java_home -v 21)
export PATH="$JAVA_HOME/bin:$PATH"

# 确认一下当前用的是哪个 Java（可选，方便调试）
echo "[启动脚本] 使用 Java: $(java -version 2>&1 | head -n 1)"

while true; do
    echo "[启动脚本] 服务器启动中..."
    ./run.sh nogui "$@"
    echo "[启动脚本] 服务器已停止，5秒后自动重启..."
    sleep 5
done