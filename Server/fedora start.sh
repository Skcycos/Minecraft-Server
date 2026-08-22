#!/usr/bin/env bash
set -Eeuo pipefail

SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
cd "$SCRIPT_DIR"

if ! JAVA_BIN="$(command -v java)"; then
    echo "[fedora start] 未找到 Java，请先安装 Java 21。" >&2
    exit 1
fi

JAVA_VERSION="$("$JAVA_BIN" -version 2>&1 | sed -nE 's/.*version "([0-9]+).*/\1/p' | head -n 1)"
if [[ "$JAVA_VERSION" != "21" ]]; then
    echo "[fedora start] 需要 Java 21，当前检测到 Java ${JAVA_VERSION:-未知}。" >&2
    exit 1
fi

echo "[fedora start] 工作目录：$SCRIPT_DIR"
echo "[fedora start] Java：$JAVA_BIN"
"$JAVA_BIN" -version 2>&1 | head -n 1
echo "[fedora start] 正在启动服务器；按 Ctrl+C 可正常停止。"

exec "$JAVA_BIN" @user_jvm_args.txt \
    @libraries/net/neoforged/neoforge/21.1.248/unix_args.txt \
    nogui "$@"
