# Contributing to ArthasClaw

Thank you for your interest in contributing to ArthasClaw! This document provides guidelines and instructions for contributing.

## Table of Contents

- [Code Architecture](#code-architecture)
- [Development Setup](#development-setup)
- [Contributing Skills](#contributing-skills)
- [Pull Request Process](#pull-request-process)

## Code Architecture

ArthasClaw follows **Domain-Driven Design (DDD)** layered architecture:

```
io.github.jiajunbernoulli.arthasclaw/
├── interfaces/             # Interface Layer (Presentation)
│   ├── TUIClient.java          - TUI entry point and main loop
│   ├── CommandDispatcher.java  - Command routing and dispatch
│   ├── DisplayHelper.java      - Terminal output formatting
│   └── bootstrap/
│       └── BotArthas.java      - Arthas attachment bootstrap
│
├── application/            # Application Layer (Use Cases)
│   ├── LoopAgent.java          - AI agent orchestration
│   └── SessionContext.java     - Session and request tracking
│
├── domain/                 # Domain Layer (Core Business Logic)
│   ├── CompletionProvider.java - LLM interface (port)
│   └── skill/
│       ├── Skill.java          - Skill entity
│       ├── SkillManager.java   - Skill lifecycle management
│       └── SkillParser.java    - Skill file parser
│
└── infrastructure/         # Infrastructure Layer (Adapters)
    ├── config/
    │   └── Config.java         - Configuration management
    ├── mcp/
    │   └── McpClient.java      - MCP protocol client
    ├── memory/
    │   └── MemoryManager.java  - Session persistence
    └── llm/
        ├── OpenAICompletionProvider.java
        └── LocalMockProvider.java
```

### Layer Responsibilities

| Layer | Package | Responsibility |
|-------|---------|----------------|
| **Interface** | `interfaces` | User interaction, command parsing, display |
| **Application** | `application` | Workflow orchestration, use case coordination |
| **Domain** | `domain` | Core business logic, domain entities, port interfaces |
| **Infrastructure** | `infrastructure` | External adapters, persistence, configuration |

### Dependency Rule

Dependencies flow **inward only**:
- `interfaces` → `application` → `domain`
- `infrastructure` → `domain` (implements domain interfaces)

## Development Setup

### Prerequisites

- Java 8+
- Maven 3.6+
- A running Java process to diagnose

### Build & Test

```bash
# Build the project
cd agent
mvn clean compile

# Run tests
mvn test

# Create distribution
mvn package
```

### Run Locally

```bash
java -jar target/arthas-claw-*-jar-with-dependencies.jar <PID>
```

## Contributing Skills

**Skills are the primary way to extend ArthasClaw's capabilities!**

A Skill is a prompt template that enhances the AI agent's diagnostic abilities for specific scenarios. We welcome contributions of new skills for:

- Thread analysis and deadlock detection
- Memory leak investigation
- GC tuning guidance
- Classloader troubleshooting
- Performance profiling workflows
- Framework-specific diagnostics (Spring, Dubbo, etc.)

### Skill File Format

Skills use YAML front matter with Markdown body:

```markdown
---
name: deadlock-analyzer
description: Detect and analyze thread deadlocks in Java applications
version: 1.0.0
author: your-username
tools:
  - thread
  - thread -b
  - thread -n 5
  - stack
---

You are a Java thread deadlock analysis expert.

## Deadlock Detection Workflow

1. Use `thread -b` to find blocking threads
2. Use `thread` for overall thread state
3. Analyze stack traces for lock dependencies

## Output Format

Provide:
- Blocked thread identification
- Lock dependency chain
- Root cause analysis
- Remediation suggestions
```

### Skill Metadata Fields

| Field | Required | Description |
|-------|----------|-------------|
| `name` | Yes | Unique skill identifier (kebab-case) |
| `description` | Yes | Brief description of the skill |
| `version` | No | Semantic version (e.g., "1.0.0") |
| `author` | No | Author name or GitHub username |
| `tools` | No | List of Arthas tools used by this skill |

### Contributing a New Skill

1. **Create the skill file** in `~/.arthasclaw/skills/` locally for testing
2. **Test thoroughly** with real diagnostic scenarios
3. **Submit to the skills repository** (or propose a built-in skill):
   - Create a PR adding your skill to the community skills collection
   - Include example usage and expected output

### Skill Best Practices

- **Be specific**: Target a single diagnostic scenario
- **Provide workflow**: Guide the AI through systematic investigation
- **Define output format**: Ensure consistent, actionable results
- **Document tools used**: Help users understand dependencies
- **Include examples**: Show expected input/output patterns

### Skill Ideas Wanted

We're particularly interested in skills for:

- **Spring Boot**: Actuator endpoints, bean lifecycle, auto-configuration
- **Database connection pools**: HikariCP, Druid troubleshooting
- **Kubernetes**: Container-aware diagnostics
- **Microservices**: Distributed tracing, service mesh debugging
- **JVM internals**: JIT compilation, class loading, native memory

## Pull Request Process

### Commit Message Convention

Use conventional commits with lowercase descriptions:

```
<type>: <description>

# Examples:
feat: add thread-deadlock-analyzer skill
fix: resolve mcp connection timeout issue
refactor: extract skill loading logic
doc: update contributing guidelines
test: add tests for skill parser
```

**Types**: `feat`, `fix`, `refactor`, `chore`, `doc`, `test`, `style`

### Code Style

- All code and comments in English
- User-facing prompts can be multilingual
- Follow existing code formatting
- No hardcoded secrets or API keys

### Before Submitting

1. Run `mvn test` to ensure all tests pass
2. Update documentation if needed
3. Add tests for new functionality
4. Keep PRs focused on a single change

## Questions?

Open an issue for:
- Bug reports
- Feature requests
- Skill proposals
- Architecture discussions

Thank you for contributing to ArthasClaw!
