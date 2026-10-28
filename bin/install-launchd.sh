#!/bin/bash
# 把 QuantBoard 交给 launchd 托管（只需运行一次；之后开机/登录自动起、崩溃自动重启）
# 必须在真实终端里运行（WorkBuddy 的沙箱无法向 launchd 注册服务）。
set -u

# 同时把过程写到文件，方便没看到窗口时事后核对
mkdir -p "$(cd "$(dirname "$0")/.." && pwd)/logs" 2>/dev/null
LOG="$(cd "$(dirname "$0")/.." && pwd)/logs/install-launchd.out"
exec > >(tee -a "$LOG") 2>&1
echo "=== $(date '+%F %T') 开始执行 ==="
echo "shell=$$ parent=$(ps -o ppid= -p $$ 2>/dev/null | tr -d ' ')"

PLIST="$HOME/Library/LaunchAgents/com.quantboard.dashboard.plist"
LABEL="com.quantboard.dashboard"
PORT="${1:-8848}"
UID_N=$(id -u)

cd "$(dirname "$0")/.." || exit 1

echo "== 1/4 停止当前实例 =="
pids=$(lsof -nP -iTCP:"$PORT" -sTCP:LISTEN 2>/dev/null | awk 'NR>1{print $2}' | sort -u | tr '\n' ' ')
if [ -n "$pids" ]; then
  echo "   kill $pids"
  kill $pids 2>/dev/null
fi
for i in $(seq 1 30); do
  lsof -nP -iTCP:"$PORT" -sTCP:LISTEN >/dev/null 2>&1 || break
  sleep 1
done
if lsof -nP -iTCP:"$PORT" -sTCP:LISTEN >/dev/null 2>&1; then
  echo "   端口仍被占用，尝试强制结束"
  pids=$(lsof -nP -iTCP:"$PORT" -sTCP:LISTEN | awk 'NR>1{print $2}' | sort -u)
  kill -9 $pids 2>/dev/null
  sleep 2
fi

echo "== 2/4 注册 launchd 服务 =="
launchctl bootout "gui/$UID_N/$LABEL" 2>/dev/null
launchctl bootstrap "gui/$UID_N" "$PLIST"
rc=$?
echo "   bootstrap exit=$rc"
if [ $rc -ne 0 ]; then
  echo "   注册失败，改用 load -w 重试"
  launchctl load -w "$PLIST" || { echo "   仍然失败，请检查 $PLIST"; exit 1; }
fi

sleep 2
echo "== 3/4 服务状态 =="
launchctl print "gui/$UID_N/$LABEL" 2>/dev/null | grep -E "^\s*(state|pid|program|last exit code)" | head -6

echo "== 4/4 等待接口就绪 =="
for i in $(seq 1 45); do
  code=$(curl -s -o /dev/null --noproxy '*' -w "%{http_code}" "http://127.0.0.1:$PORT/api/board")
  if [ "$code" = "200" ]; then
    echo "   就绪，用时 ${i}s"
    break
  fi
  sleep 1
done
curl -s -o /dev/null --noproxy '*' -w "   /api/family %{http_code} %{time_total}s\n" "http://127.0.0.1:$PORT/api/family"

echo
echo "完成。服务现在由 launchd 托管："
echo "  · 关闭本窗口不影响运行"
echo "  · 崩溃/被杀后 10 秒内自动重启"
echo "  · 日志：$(pwd)/logs/quantboard.log"
echo "  · 停止：launchctl bootout gui/$UID_N/$LABEL"
echo "  · 看状态：launchctl print gui/$UID_N/$LABEL | head -20"
