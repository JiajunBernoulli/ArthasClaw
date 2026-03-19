#!/bin/bash
# ArthasClaw 一键测试脚本
# 用法: ./test.sh [问题]
# 示例: ./test.sh "MathGame有哪些方法"

# 默认问题
QUESTION="${1:-MathGame有哪些方法}"
SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
MATH_DIR="$SCRIPT_DIR/examples/math"
AGENT_DIR="$SCRIPT_DIR/agent"
LOG_FILE="/tmp/arthasclaw_test.log"
RESULT_FILE="/tmp/arthasclaw_result.txt"

echo "=========================================="
echo "  ArthasClaw 一键测试脚本"
echo "=========================================="
echo ""

# 清理函数
cleanup() {
    echo "" >> "$LOG_FILE"
    echo "[*] 清理中..." >> "$LOG_FILE"
    if [ -n "$MATH_PID" ] && kill -0 $MATH_PID 2>/dev/null; then
        kill $MATH_PID 2>/dev/null || true
        echo "[+] 已停止 MathGame 进程 (PID: $MATH_PID)"
    fi
    # 清理可能残留的 arthas 进程
    pkill -f "arthas.*$MATH_PID" 2>/dev/null || true
}
trap cleanup EXIT

# 清理之前的日志
> "$LOG_FILE"
> "$RESULT_FILE"

# 1. 编译并启动 MathGame
echo "[1/4] 编译并启动 MathGame..."
cd "$MATH_DIR"
if [ ! -f "MathGame.class" ] || [ "MathGame.java" -nt "MathGame.class" ]; then
    javac MathGame.java
fi

# 后台启动 MathGame，输出重定向到临时文件
java MathGame > /tmp/mathgame.log 2>&1 &
MATH_PID=$!
echo "[+] MathGame 已启动 (PID: $MATH_PID)"

# 等待进程稳定
sleep 2

# 验证进程仍在运行
if ! kill -0 $MATH_PID 2>/dev/null; then
    echo "[-] MathGame 启动失败"
    cat /tmp/mathgame.log
    exit 1
fi

# 2. 先手动 attach Arthas
echo ""
echo "[2/4] Attach Arthas 到目标进程..."

cd "$AGENT_DIR"

# 设置环境变量
# export OPENAI_API_KEY=""
# export OPENAI_BASE_URL=""
# export OPENAI_MODEL=""

# 生成 MCP 密码 (兼容无 uuidgen 的系统)
MCP_PASSWORD=$(cat /proc/sys/kernel/random/uuid 2>/dev/null | tr -d '-' || echo "$(date +%s)$$$(RANDOM)" | tr -d ' ')

# 写入 Arthas 配置
ARTHAS_CONF_DIR="$HOME/.arthas/conf"
mkdir -p "$ARTHAS_CONF_DIR"
cat > "$ARTHAS_CONF_DIR/arthas.properties" << EOF
# MCP (Model Context Protocol) configuration
arthas.mcpEndpoint=/mcp
arthas.password=$MCP_PASSWORD
EOF

# 使用 arthas-boot.jar attach
ARTHAS_HOME="$HOME/.arthas/lib/4.1.8/arthas"
ARTHAS_BOOT="$ARTHAS_HOME/arthas-boot.jar"

if [ -f "$ARTHAS_BOOT" ]; then
    echo "[*] 使用已有 Arthas 安装..."
    java -jar "$ARTHAS_BOOT" --attach-only "$MATH_PID" >> "$LOG_FILE" 2>&1 &
    ARTHAS_PID=$!
else
    echo "[-] Arthas 未安装，请先运行一次 Agent"
    exit 1
fi

# 等待 Arthas attach 完成 (检查端口 8563)
echo "[*] 等待 Arthas MCP 服务启动 (端口 8563)..."
MAX_WAIT=60
WAIT_COUNT=0
while [ $WAIT_COUNT -lt $MAX_WAIT ]; do
    if ss -tlnp 2>/dev/null | grep -q ":8563"; then
        echo "[+] Arthas MCP 服务已就绪"
        break
    fi
    sleep 1
    WAIT_COUNT=$((WAIT_COUNT + 1))
    if [ $((WAIT_COUNT % 10)) -eq 0 ]; then
        echo "[*] 等待中... ($WAIT_COUNT 秒)"
    fi
done

if [ $WAIT_COUNT -ge $MAX_WAIT ]; then
    echo "[-] Arthas MCP 服务启动超时"
    cat "$LOG_FILE"
    exit 1
fi

# 等待 arthas-boot 进程结束
wait $ARTHAS_PID 2>/dev/null || true

# 3. 启动 BotAgent 并提问
echo ""
echo "[3/4] 启动 Agent 并提问..."
echo "    问题: $QUESTION"
echo ""

# 创建输入文件
INPUT_FILE=$(mktemp)
echo "$QUESTION" > "$INPUT_FILE"
echo "exit" >> "$INPUT_FILE"

# 使用 script 命令模拟 TTY，设置较大的超时
script -q -c "timeout 120 java -jar target/bot-1.0.0-jar-with-dependencies.jar $MATH_PID" /dev/null < "$INPUT_FILE" >> "$LOG_FILE" 2>&1 &
BOT_PID=$!

# 等待 Agent 完成
wait $BOT_PID 2>/dev/null
EXIT_CODE=$?

rm -f "$INPUT_FILE"

# 4. 检查结果
echo ""
echo "[4/4] 测试结果检查..."

# 显示关键日志
echo "--- 关键日志 ---"
grep -E "(🤖|Connected|MCP|Error|Exception)" "$LOG_FILE" | head -30

# 检查输出中是否包含 AI 回复的关键标记
if grep -q "🤖 AI:" "$LOG_FILE"; then
    # 提取 AI 回复
    grep -A 10 "🤖 AI:" "$LOG_FILE" | head -20 > "$RESULT_FILE"
    
    echo ""
    echo "=========================================="
    echo "  ✅ 测试成功！获得 AI 回复"
    echo "=========================================="
    echo ""
    echo "--- AI 回复 ---"
    cat "$RESULT_FILE"
    echo ""
    echo "完整日志: $LOG_FILE"
    exit 0
else
    echo ""
    echo "=========================================="
    echo "  ❌ 测试失败！未获得有效回复"
    echo "=========================================="
    echo ""
    echo "--- 完整日志 ---"
    cat "$LOG_FILE"
    exit 1
fi
