#!/bin/zsh
# 启动多市场看板：python3 标准库，无依赖
cd "$(dirname "$0")"
PORT="${PORT:-8848}"
echo "启动 QuantBoard → http://127.0.0.1:$PORT"
exec python3 server.py
