#!/bin/bash
# ArthasClaw Async Task Test Script
# Usage: ./task_test.sh
# Tests the async task creation and management functionality

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
MATH_DIR="$SCRIPT_DIR/examples/math"
AGENT_DIR="$SCRIPT_DIR/agent"
JAR_NAME="arthas-claw-0.0.8-jar-with-dependencies.jar"
LOG_FILE="/tmp/arthasclaw_task_test.log"

echo "=========================================="
echo "  ArthasClaw Async Task Test"
echo "=========================================="
echo ""

# Cleanup function
cleanup() {
    echo "" >> "$LOG_FILE"
    echo "[*] Cleaning up..." >> "$LOG_FILE"
    if [ -n "$MATH_PID" ] && kill -0 $MATH_PID 2>/dev/null; then
        kill $MATH_PID 2>/dev/null || true
        echo "[+] Stopped MathGame process (PID: $MATH_PID)"
    fi
    # Clean up residual arthas processes
    pkill -f "arthas.*$MATH_PID" 2>/dev/null || true
    # Kill agent if still running
    if [ -n "$AGENT_PID" ] && kill -0 $AGENT_PID 2>/dev/null; then
        kill $AGENT_PID 2>/dev/null || true
    fi
}
trap cleanup EXIT

# Clear previous logs
> "$LOG_FILE"

# 1. Compile and start MathGame
echo "[1/5] Compiling and starting MathGame..."
cd "$MATH_DIR"
if [ ! -f "MathGame.class" ] || [ "MathGame.java" -nt "MathGame.class" ]; then
    javac MathGame.java
fi

# Start MathGame in background
java MathGame > /tmp/mathgame.log 2>&1 &
MATH_PID=$!
echo "[+] MathGame started (PID: $MATH_PID)"

# Wait for process to stabilize
sleep 2

# Verify process is still running
if ! kill -0 $MATH_PID 2>/dev/null; then
    echo "[-] MathGame failed to start"
    cat /tmp/mathgame.log
    exit 1
fi

# 2. Attach Arthas
echo ""
echo "[2/5] Attaching Arthas to target process..."

cd "$AGENT_DIR"

# Generate MCP password
if command -v uuidgen &> /dev/null; then
    MCP_PASSWORD=$(uuidgen | tr -d '-' | tr '[:upper:]' '[:lower:]')
else
    MCP_PASSWORD="$(date +%s)$$$(RANDOM)"
fi

# Write Arthas configuration
ARTHAS_CONF_DIR="$HOME/.arthas/conf"
mkdir -p "$ARTHAS_CONF_DIR"
cat > "$ARTHAS_CONF_DIR/arthas.properties" << EOF
# MCP (Model Context Protocol) configuration
arthas.mcpEndpoint=/mcp
arthas.password=$MCP_PASSWORD
EOF

# Use arthas-boot.jar to attach
ARTHAS_HOME="$HOME/.arthas/lib/4.1.8/arthas"
ARTHAS_BOOT="$ARTHAS_HOME/arthas-boot.jar"

if [ -f "$ARTHAS_BOOT" ]; then
    echo "[*] Using existing Arthas installation..."
    java -jar "$ARTHAS_BOOT" --attach-only "$MATH_PID" >> "$LOG_FILE" 2>&1 &
    ARTHAS_PID=$!
else
    echo "[-] Arthas not installed, please run the Agent first"
    exit 1
fi

# Wait for Arthas MCP service (port 8563)
echo "[*] Waiting for Arthas MCP service to start (port 8563)..."
MAX_WAIT=60
WAIT_COUNT=0
while [ $WAIT_COUNT -lt $MAX_WAIT ]; do
    # Use lsof for macOS compatibility
    if lsof -i :8563 -sTCP:LISTEN -t >/dev/null 2>&1; then
        echo "[+] Arthas MCP service is ready"
        break
    fi
    sleep 1
    WAIT_COUNT=$((WAIT_COUNT + 1))
    if [ $((WAIT_COUNT % 10)) -eq 0 ]; then
        echo "[*] Waiting... ($WAIT_COUNT seconds)"
    fi
done

if [ $WAIT_COUNT -ge $MAX_WAIT ]; then
    echo "[-] Arthas MCP service startup timeout"
    cat "$LOG_FILE"
    exit 1
fi

wait $ARTHAS_PID 2>/dev/null || true

# 3. Test 1: Create async task
echo ""
echo "[3/5] Test 1: Creating async task..."
echo "    Request: Watch MathGame.run method 3 times"

# Create input file with commands
INPUT_FILE=$(mktemp)
echo "watch MathGame run method 3 times" > "$INPUT_FILE"
echo "/tasks" >> "$INPUT_FILE"
echo "exit" >> "$INPUT_FILE"

# Run agent in background with input (macOS compatible - no timeout)
cd "$AGENT_DIR"
java -jar "target/$JAR_NAME" "$MATH_PID" < "$INPUT_FILE" >> "$LOG_FILE" 2>&1 &
AGENT_PID=$!

# Wait for agent with timeout (manual implementation)
TIMEOUT_SEC=90
WAIT_COUNT=0
while [ $WAIT_COUNT -lt $TIMEOUT_SEC ]; do
    if ! kill -0 $AGENT_PID 2>/dev/null; then
        break
    fi
    sleep 1
    WAIT_COUNT=$((WAIT_COUNT + 1))
done

# If still running after timeout, kill it
if kill -0 $AGENT_PID 2>/dev/null; then
    echo "[*] Agent timeout after ${TIMEOUT_SEC}s, terminating..."
    kill $AGENT_PID 2>/dev/null
fi

wait $AGENT_PID 2>/dev/null
EXIT_CODE=$?
rm -f "$INPUT_FILE"

echo "[*] Agent exited with code: $EXIT_CODE"

sleep 2

# 4. Test 2: Check task list
echo ""
echo "[4/5] Test 2: Checking task list..."

# Check for task creation
if grep -q "Task ID:" "$LOG_FILE"; then
    echo "[+] Task created successfully"
    TASK_ID=$(grep "Task ID:" "$LOG_FILE" | head -1 | grep -o 'task_[a-f0-9]*' || echo "")
    if [ -n "$TASK_ID" ]; then
        echo "[+] Task ID: $TASK_ID"
    fi
else
    echo "[-] Task creation not detected"
fi

# Check for task list output
if grep -q "Task List:" "$LOG_FILE" || grep -q "No tasks found" "$LOG_FILE"; then
    echo "[+] /tasks command working"
else
    echo "[-] /tasks command not detected"
fi

# Check for async task tool call
if grep -q "create_async_task" "$LOG_FILE"; then
    echo "[+] create_async_task tool was called"
fi

# 5. Display results
echo ""
echo "[5/5] Test Results..."

echo ""
echo "--- Key Logs ---"
grep -E "(Task|task|🤖|create_async|/tasks|AI:|Tool|Error|Loaded)" "$LOG_FILE" | head -50

echo ""
echo "=========================================="

# Determine test result
PASS_COUNT=0

# Check 1: AI responded
if grep -q "🤖 AI:" "$LOG_FILE"; then
    echo "  ✅ AI response received"
    PASS_COUNT=$((PASS_COUNT + 1))
else
    echo "  ❌ No AI response"
fi

# Check 2: Task created (either via tool or manually)
if grep -q "Task ID:" "$LOG_FILE" || grep -q "create_async_task" "$LOG_FILE"; then
    echo "  ✅ Async task creation detected"
    PASS_COUNT=$((PASS_COUNT + 1))
else
    echo "  ❌ No task creation detected"
fi

# Check 3: /tasks command works
if grep -q "Task List:" "$LOG_FILE" || grep -q "No tasks found" "$LOG_FILE"; then
    echo "  ✅ Task list command working"
    PASS_COUNT=$((PASS_COUNT + 1))
else
    echo "  ❌ Task list command not working"
fi

echo "=========================================="
echo ""

if [ $PASS_COUNT -ge 2 ]; then
    echo "✅ Test PASSED ($PASS_COUNT/3 checks passed)"
    echo ""
    echo "Full log: $LOG_FILE"
    exit 0
else
    echo "❌ Test FAILED ($PASS_COUNT/3 checks passed)"
    echo ""
    echo "--- Full Log (last 100 lines) ---"
    tail -100 "$LOG_FILE"
    exit 1
fi
