# 🦞 ArthasClaw - JVM AI Assistant | [中文说明](docs/README_CN.md)

<p align="center">
  <img src="docs/rectangle.jpg" alt="ArthasClaw Logo" width="800">
</p>

JVM diagnostic assistant with natural language interface, powered by Arthas.

## Why ArthasClaw?

In JVM diagnostic scenarios, ArthasClaw offers these advantages over general-purpose agents:

- **Fewer Dependencies**: OpenClaw requires Node.js 22+, Nanobot requires Python 3.11+, while ArthasClaw only needs Java 8+, making it easy to install on any server running Java applications.
- **Faster & More Stable**: Local tools like Claude Code and Trae may have slow or unreachable network connections to remote Arthas servers. ArthasClaw runs directly on the same server as your Java application, making local requests to Arthas MCP for faster and more stable performance.

## Quickstart

### One-line Install & Run

```bash
curl -sL https://raw.githubusercontent.com/JiajunBernoulli/ArthasClaw/main/start.sh | bash
```

This will:
1. Download the JAR from Maven Central
2. Prompt for OpenAI environment variables if not set
3. List available Java processes for selection
4. Start ArthasClaw with the selected PID
5. Ask natural language questions in the TUI interface

<p align="center">
  <img src="docs/tui_en.gif" alt="ArthasClaw TUI Interface" width="600">
</p>

### Manual Build

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
java -jar arthas-claw-*-jar-with-dependencies.jar <PID>
```

### 5. Ask Natural Language Questions

Once the TUI is running, you can ask questions like:

```
arthasclaw> What methods does MathGame have?
```


## Skill Management

Skills are reusable prompt templates that enhance AI's capabilities for specific diagnostic tasks. You can install, list, enable, disable, and remove skills.

### Skill Commands

```
/skill install <url|path>   Install a skill from URL or local file
/skill list                 List installed skills
/skill show <name>          Show skill details
/skill enable <name>        Enable a skill
/skill disable <name>       Disable a skill
/skill remove <name>        Remove a skill
```

### Skill File Format

Skill files support YAML front matter with markdown body:

```yaml
---
name: deadlock-analyzer
description: Detect and analyze thread deadlocks
version: 1.0.0
author: your-name
tools:
  - thread
  - thread -b
  - stack
---
You are a Java thread deadlock analysis expert.
When analyzing thread issues:
1. Use `thread -b` to find blocking threads
2. Use `thread` for overall thread state
3. Provide actionable solutions
```

### Example: Deadlock Detection

1. **Start a deadlock demo**:
   ```bash
   cd examples/deadlock
   javac -d . DeadlockDemo.java
   java -cp . io.github.jiajunbernoulli.arthasclaw.examples.DeadlockDemo
   ```

2. **Install the deadlock-analyzer skill**:
   ```
   arthasclaw> /skill install examples/deadlock/deadlock-analyzer.md
   [+] Skill installed: deadlock-analyzer v1.0.0
       Description: Detect and analyze thread deadlocks
   ```

3. **Ask AI to analyze deadlock**:
   ```
   arthasclaw> Check for thread deadlock
   ```

The AI will automatically apply the skill's analysis workflow and use the specified Arthas tools.

### Skill Storage

Skills are stored in `~/.arthasclaw/skills/` directory. You can also install skills from URLs:

```
arthasclaw> /skill install https://raw.githubusercontent.com/JiajunBernoulli/ArthasClaw/main/skills/deadlock-analyzer.md
```

## Task Management

ArthasClaw supports asynchronous task management for long-running diagnostic operations. This allows you to run tasks like "watch a method 10 times" in the background while continuing to interact with the system.

### Task Commands

```
/tasks                      List all running and completed tasks
/stop <taskId>             Cancel a running task by ID
```

### Task Lifecycle

Tasks go through the following states:
- **PENDING**: Task created but not yet started
- **RUNNING**: Task is currently executing
- **COMPLETED**: Task finished successfully
- **FAILED**: Task encountered an error
- **CANCELLED**: Task was manually cancelled

### Example Usage

1. **Start a long-running task** (e.g., monitoring method calls):
   ```
   arthasclaw> Watch the calculate method 10 times
   ```
   The system will automatically create an async task for this operation.

2. **Check task status**:
   ```
   arthasclaw> /tasks
   📋 Task List:
   -----------------------------------------------------------------------
   Task ID      | Description              | Status    | Updated At
   -----------------------------------------------------------------------
   task_a1b2c3d4 | Watch MathGame.calculate | RUNNING   | 2026-03-30T18:00:00
   -----------------------------------------------------------------------
   ```

3. **Cancel a running task**:
   ```
   arthasclaw> /stop task_a1b2c3d4
   ✅ Task cancelled: task_a1b2c3d4
   ```

### Task Implementation

Tasks are managed by the `TaskManager` class in the `domain.task` package:
- `Task`: Entity representing a task with lifecycle state
- `TaskManager`: Creates, tracks, and manages task execution
- `TaskExecutor`: Handles execution logic for specific task types

---

