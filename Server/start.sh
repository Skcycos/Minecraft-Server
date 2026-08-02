#!/usr/bin/env bash
cd "$(dirname "$0")"
export PATH="/home/tanrunn/.local/java/jdk-21.0.7+6/bin:$PATH"
exec ./run.sh nogui "$@"
