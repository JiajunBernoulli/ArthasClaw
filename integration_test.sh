#!/bin/bash
# ArthasClaw Integration Test Script
# Usage: ./integration_test.sh [question]
# Example: ./integration_test.sh "What methods does MathGame have?"

# Default question
QUESTION="${1:-What methods does MathGame have?}"
SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
MATH_DIR="$SCRIPT_DIR/examples/math"
AGENT_DIR="$SCRIPT_DIR/agent"
JAR_NAME="arthas-claw-0.0.2-jar-with-dependencies.jar"
LOG_FILE="/tmp/arthasclaw_test.log"
RESULT_FILE="/tmp/arthasclaw_result.txt"

echo "=========================================="
echo "  ArthasClaw Integration Test"
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
}
trap cleanup EXIT

# Clear previous logs
> "$LOG_FILE"
> "$RESULT_FILE"

# 1. Compile and start MathGame
echo "[1/4] Compiling and starting MathGame..."
cd "$MATH_DIR"
if [ ! -f "MathGame.class" ] || [ "MathGame.java" -nt "MathGame.class" ]; then
    javac MathGame.java
fi

# Start MathGame in background, output redirected to temp file
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

# 2. Manually attach Arthas
echo ""
echo "[2/4] Attaching Arthas to target process..."

cd "$AGENT_DIR"

# Generate MCP password (compatible with systems without uuidgen)
MCP_PASSWORD=$(cat /proc/sys/kernel/random/uuid 2>/dev/null | tr -d '-' || echo "$(date +%s)$$$(RANDOM)" | tr -d ' ')

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

# Wait for Arthas attach to complete (check port 8563)
echo "[*] Waiting for Arthas MCP service to start (port 8563)..."
MAX_WAIT=60
WAIT_COUNT=0
while [ $WAIT_COUNT -lt $MAX_WAIT ]; do
    if ss -tlnp 2>/dev/null | grep -q ":8563"; then
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

# Wait for arthas-boot process to finish
wait $ARTHAS_PID 2>/dev/null || true

# 3. Start Agent and ask question
echo ""
echo "[3/4] Starting Agent and asking question..."
echo "    Question: $QUESTION"
echo ""

# Create input file
INPUT_FILE=$(mktemp)
echo "$QUESTION" > "$INPUT_FILE"
echo "exit" >> "$INPUT_FILE"

# Use script command to simulate TTY, set larger timeout
script -q -c "timeout 120 java -jar target/$JAR_NAME $MATH_PID" /dev/null < "$INPUT_FILE" >> "$LOG_FILE" 2>&1 &
BOT_PID=$!

# Wait for Agent to complete
wait $BOT_PID 2>/dev/null
EXIT_CODE=$?

rm -f "$INPUT_FILE"

# 4. Check results
echo ""
echo "[4/4] Checking test results..."

# Display key logs
echo "--- Key Logs ---"
grep -E "(🤖|Connected|MCP|Error|Exception)" "$LOG_FILE" | head -30

# Check if output contains AI response markers
if grep -q "🤖 AI:" "$LOG_FILE"; then
    # Extract AI response
    grep -A 10 "🤖 AI:" "$LOG_FILE" | head -20 > "$RESULT_FILE"
    
    echo ""
    echo "=========================================="
    echo "  ✅ Test passed! Got AI response"
    echo "=========================================="
    echo ""
    echo "--- AI Response ---"
    cat "$RESULT_FILE"
    echo ""
    echo "Full log: $LOG_FILE"
    exit 0
else
    echo ""
    echo "=========================================="
    echo "  ❌ Test failed! No valid response received"
    echo "=========================================="
    echo ""
    echo "--- Full Log ---"
    cat "$LOG_FILE"
    exit 1
fi
