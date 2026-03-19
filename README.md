# ArthasClaw: JVM AI Assistant

<p align="center">
  <img src="docs/rectangle.jpg" alt="ArthasClaw Logo" width="400">
</p>

[中文说明](docs/README_CN.md)

JVM diagnostic assistant with natural language interface, powered by Arthas.

## Quickstart

### 1. Build the Project

```bash
cd agent
mvn clean package
```

### 2. Start the Target Application

Use the included MathGame example:

```bash
cd examples/math
javac MathGame.java
java MathGame &
```

### 3. Find the Process PID

```bash
jps | grep MathGame
# Output example: 12345 MathGame
```

### 4. Run ArthasClaw TUI

```bash
# Set your OpenAI API key
export OPENAI_API_KEY=sk-xxx

# Optional: set custom base URL (default: OpenAI)
export OPENAI_BASE_URL=https://api.openai.com/v1/chat/completions

# Optional: set model (default: gpt-4o-mini)
export OPENAI_MODEL=gpt-4o-mini

# Run with target PID
cd agent/target
java -jar bot-1.0.0-jar-with-dependencies.jar <PID>
```

### 5. Ask Natural Language Questions

Once the TUI is running, you can ask questions like:

```
arthas> 分析MathGame run方法的耗时
arthas> 查看线程死锁情况
arthas> MathGame有哪些方法?
```

# TODO
[] skill
[] memory
