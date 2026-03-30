#!/bin/bash
# ArthasClaw One-Click Startup Script
# Usage: ./start.sh [question]

JAR_URL="https://repo1.maven.org/maven2/io/github/jiajunbernoulli/arthas-claw/0.0.9/arthas-claw-0.0.9-jar-with-dependencies.jar"
JAR_NAME="arthas-claw-0.0.9-jar-with-dependencies.jar"
SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
JAR_PATH="$SCRIPT_DIR/$JAR_NAME"

# Determine input source: use /dev/tty if running in a pipe, otherwise use stdin
if [ ! -t 0 ]; then
    TTY_PATH="/dev/tty"
else
    TTY_PATH="/dev/stdin"
fi

echo "=========================================="
echo "  ArthasClaw One-Click Startup"
echo "=========================================="
echo ""

# 1. Download JAR if not exists
if [ ! -f "$JAR_PATH" ]; then
    echo "[1/3] Downloading ArthasClaw..."
    curl -L -o "$JAR_PATH" "$JAR_URL"
    if [ $? -ne 0 ]; then
        echo "[-] Download failed"
        exit 1
    fi
    echo "[+] Download complete: $JAR_PATH"
else
    echo "[1/3] JAR already exists, skipping download"
fi

# 2. Configure OpenAI API (prompt if not set)
echo ""
echo "[2/3] Configure OpenAI API..."

if [ -z "$OPENAI_API_KEY" ]; then
    echo ""
    echo "OPENAI_API_KEY is not set."
    printf "Enter OPENAI_API_KEY: "
    read -rs API_KEY_INPUT < "$TTY_PATH"
    echo ""
    export OPENAI_API_KEY="$API_KEY_INPUT"
else
    echo "[+] OPENAI_API_KEY already set"
fi

if [ -z "$OPENAI_BASE_URL" ]; then
    printf "Enter OPENAI_BASE_URL (press Enter for default: https://api.openai.com/v1): "
    read -r BASE_URL_INPUT < "$TTY_PATH"
    if [ -n "$BASE_URL_INPUT" ]; then
        export OPENAI_BASE_URL="$BASE_URL_INPUT"
    else
        export OPENAI_BASE_URL="https://api.openai.com/v1"
    fi
else
    echo "[+] OPENAI_BASE_URL already set: $OPENAI_BASE_URL"
fi

if [ -z "$OPENAI_MODEL" ]; then
    printf "Enter OPENAI_MODEL (press Enter for default: gpt-4o-mini): "
    read -r MODEL_INPUT < "$TTY_PATH"
    if [ -n "$MODEL_INPUT" ]; then
        export OPENAI_MODEL="$MODEL_INPUT"
    else
        export OPENAI_MODEL="gpt-4o-mini"
    fi
else
    echo "[+] OPENAI_MODEL already set: $OPENAI_MODEL"
fi

# 3. List Java processes for user selection
echo ""
echo "[3/3] Select target Java process..."

# Get Java process list
JAVA_PIDS=$(ps -eo pid,comm | grep java | awk '{print $1}')
JAVA_COUNT=$(echo "$JAVA_PIDS" | wc -l | tr -d ' ')

if [ -z "$JAVA_PIDS" ]; then
    echo "[-] No running Java processes found"
    echo "[*] Please start your target Java application first, then run this script again"
    exit 1
fi

# Display process details
echo ""
echo "Available Java processes:"
echo "--------------------------------------------------"
printf "%-5s %-10s %s\n" "No." "PID" "Command Line"
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

# Auto-select if only one process
if [ "$JAVA_COUNT" -eq 1 ]; then
    SELECTED_PID="$JAVA_PIDS"
    echo "[*] Single Java process detected, auto-selecting PID: $SELECTED_PID"
else
    echo ""
    printf "Enter process number [1-%d]: " "$JAVA_COUNT"
    read -r SELECTION < "$TTY_PATH"
    
    if ! [[ "$SELECTION" =~ ^[0-9]+$ ]] || [ "$SELECTION" -lt 1 ] || [ "$SELECTION" -gt "$JAVA_COUNT" ]; then
        echo "[-] Invalid selection"
        exit 1
    fi
    
    SELECTED_PID=${PID_ARRAY[$SELECTION]}
    echo "[+] Selected PID: $SELECTED_PID"
fi

# 4. Start ArthasClaw
echo ""
echo "Starting ArthasClaw..."
echo "=========================================="
echo ""

# Start jar with PID and optional question
QUESTION="${1:-}"
if [ -n "$QUESTION" ]; then
    java -jar "$JAR_PATH" "$SELECTED_PID" "$QUESTION" < "$TTY_PATH"
else
    java -jar "$JAR_PATH" "$SELECTED_PID" < "$TTY_PATH"
fi
