#!/bin/bash
# 双击启动 QuantBoard（Finder 里双击本文件，会自动开一个终端窗口跑服务）
# 关掉那个终端窗口 = 停止服务；服务不由对话/WorkBuddy 管理，可长期运行。
cd "$(dirname "$0")" || exit 1
if lsof -nP -iTCP:8848 -sTCP:LISTEN >/dev/null 2>&1; then
  echo "8848 已被占用，看板应该已经在运行： http://127.0.0.1:8848/"
  sleep 3
  exit 0
fi
./run.sh 8848
