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
if [ ! -f "$JAR" ]; then
  echo "首次运行，构建中…"
  (cd backend-java && ./mvnw -q package -DskipTests)
fi
exec java -jar "$JAR" --server.port="$PORT"
