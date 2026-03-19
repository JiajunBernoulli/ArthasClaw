#!/bin/bash
# ArthasClaw 一键启动脚本
# 用法: ./start.sh [问题]

JAR_URL="https://repo1.maven.org/maven2/io/github/jiajunbernoulli/arthas-claw/0.0.1-beta/arthas-claw-0.0.1-beta-jar-with-dependencies.jar"
JAR_NAME="arthas-claw-0.0.1-beta-jar-with-dependencies.jar"
SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
JAR_PATH="$SCRIPT_DIR/$JAR_NAME"

echo "=========================================="
echo "  ArthasClaw 一键启动"
echo "=========================================="
echo ""

# 1. 下载 JAR（如果不存在）
if [ ! -f "$JAR_PATH" ]; then
    echo "[1/4] 下载 ArthasClaw..."
    curl -L -o "$JAR_PATH" "$JAR_URL"
    if [ $? -ne 0 ]; then
        echo "[-] 下载失败"
        exit 1
    fi
    echo "[+] 下载完成: $JAR_PATH"
else
    echo "[1/4] JAR 已存在，跳过下载"
fi

# 2. 检查并设置 OpenAI 环境变量
echo ""
echo "[2/4] 检查 OpenAI 配置..."

# 检查 OPENAI_API_KEY
if [ -z "$OPENAI_API_KEY" ]; then
    read -p "请输入 OPENAI_API_KEY: " OPENAI_API_KEY
    export OPENAI_API_KEY
    echo "[+] OPENAI_API_KEY 已设置"
else
    echo "[+] OPENAI_API_KEY 已存在"
fi

# 检查 OPENAI_BASE_URL
if [ -z "$OPENAI_BASE_URL" ]; then
    read -p "请输入 OPENAI_BASE_URL (如 https://api.openai.com/v1): " OPENAI_BASE_URL
    export OPENAI_BASE_URL
    echo "[+] OPENAI_BASE_URL 已设置"
else
    echo "[+] OPENAI_BASE_URL 已存在"
fi

# 检查 OPENAI_MODEL
if [ -z "$OPENAI_MODEL" ]; then
    read -p "请输入 OPENAI_MODEL (如 gpt-4o): " OPENAI_MODEL
    export OPENAI_MODEL
    echo "[+] OPENAI_MODEL 已设置"
else
    echo "[+] OPENAI_MODEL 已存在"
fi

# 3. 列出 Java 进程让用户选择
echo ""
echo "[3/4] 选择目标 Java 进程..."

# 获取 Java 进程列表
JAVA_PIDS=$(ps -eo pid,comm | grep java | awk '{print $1}')
JAVA_COUNT=$(echo "$JAVA_PIDS" | wc -l)

if [ -z "$JAVA_PIDS" ]; then
    echo "[-] 未找到运行中的 Java 进程"
    echo "[*] 请先启动目标 Java 应用，然后重新运行此脚本"
    exit 1
fi

# 显示进程详情
echo ""
echo "可用 Java 进程:"
echo "--------------------------------------------------"
printf "%-5s %-10s %s\n" "序号" "PID" "命令行"
echo "--------------------------------------------------"

i=1
declare -a PID_ARRAY
while IFS= read -r pid; do
    CMDLINE=$(ps -p "$pid" -o command= 2>/dev/null | head -c 80)
    printf "%-5s %-10s %s\n" "[$i]" "$pid" "$CMDLINE"
    PID_ARRAY[$i]=$pid
    ((i++))
done <<< "$JAVA_PIDS"

echo "--------------------------------------------------"

# 如果只有一个进程，自动选择
if [ "$JAVA_COUNT" -eq 1 ]; then
    SELECTED_PID="$JAVA_PIDS"
    echo "[*] 检测到唯一 Java 进程，自动选择 PID: $SELECTED_PID"
else
    echo ""
    read -p "请输入序号选择目标进程 [1-$((JAVA_COUNT))]: " SELECTION
    
    if ! [[ "$SELECTION" =~ ^[0-9]+$ ]] || [ "$SELECTION" -lt 1 ] || [ "$SELECTION" -gt "$JAVA_COUNT" ]; then
        echo "[-] 无效选择"
        exit 1
    fi
    
    SELECTED_PID=${PID_ARRAY[$SELECTION]}
    echo "[+] 已选择 PID: $SELECTED_PID"
fi

# 4. 启动 ArthasClaw
echo ""
echo "[4/4] 启动 ArthasClaw..."
echo "=========================================="
echo ""

# 启动 jar，传入 PID 和用户的问题
QUESTION="${1:-}"
if [ -n "$QUESTION" ]; then
    java -jar "$JAR_PATH" "$SELECTED_PID" "$QUESTION"
else
    java -jar "$JAR_PATH" "$SELECTED_PID"
fi
