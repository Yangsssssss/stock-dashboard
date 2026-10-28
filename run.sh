#!/bin/bash
# QuantBoard 看板启动（Java / Spring Boot 后端）
# 用法：./run.sh [端口]   默认 8848；需 Docker Desktop 运行中（MySQL 容器）
cd "$(dirname "$0")"

# 加载本地配置（数据库口令等），.env 不入库，参考 .env.example
if [ -f .env ]; then
  set -a
  . ./.env
  set +a
fi

PORT="${1:-${PORT:-8848}}"
JAR="backend-java/target/quantboard-server.jar"

# 端口守卫：已经有实例在跑就直接退出（退出码 0，避免 launchd 反复拉起）。
# 多实例并发抢同一个端口会让先启动的那个 bind 失败，是日志里"连开三个只有一个起来"的原因。
if lsof -nP -iTCP:"$PORT" -sTCP:LISTEN >/dev/null 2>&1; then
  echo "[run.sh] 端口 $PORT 已有实例在监听，本次启动跳过。"
  exit 0
fi

if [ ! -f "$JAR" ]; then
  echo "首次运行，构建中…"
  (cd backend-java && ./mvnw -q package -DskipTests)
fi

# -XX:+ExitOnOutOfMemoryError: 内存溢出时让进程真退出，交给 launchd 重启，而不是半死不活地挂着
exec java -XX:+ExitOnOutOfMemoryError -jar "$JAR" --server.port="$PORT"
