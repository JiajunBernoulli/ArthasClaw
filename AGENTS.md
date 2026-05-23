# ArthasClaw Architecture

## BotArthas.java - "Boot" Strategy

1. **Detect/Download**: On startup, the program checks if `arthas-boot.jar` exists locally. If not, it automatically downloads from Alibaba Cloud.

2. **Dynamic Injection**: Runs `java -jar arthas-boot.jar --attach-only` to attach Arthas to the target process.

3. **Server-side Loading**: After the target process loads the Arthas Agent, it loads the core jar package (`arthas-core.jar`) from `~/.arthas/lib/` (user directory cache) or downloads from the network.

4. **Start MCP Server**: Arthas starts an HTTP Server inside the target process (default port 8563).

## Summary

Our core classes play the role of MCP Client:

- **BotArthas**: Responsible for "ignition" - injecting Arthas into the target process.
- **McpClient**: Responsible for "connection" - connecting to the HTTP interface exposed by Arthas in the target process.
- **LoopAgent**: Responsible for "orchestration" - coordinating the AI assistant workflow.
- **McpToolHandler**: Responsible for "tool discovery" - fetching and configuring MCP tools.
- **ChatResponseHandler**: Responsible for "thinking" - processing LLM responses and managing tool calls.
- **ContextWindowManager**: Responsible for "memory management" - managing conversation context window with automatic sliding window and LLM-based summary compression to prevent unbounded token growth.

This is the charm of MCP (Model Context Protocol) - we don't need to re-implement diagnostic tools, we just need to connect to existing powerful tools (Arthas) through standard protocols.

## Commit Message Convention

Use the following prefixes with a colon, followed by a lowercase description:

- `feat`: new feature
- `fix`: bug fix
- `refactor`: code refactoring without feature changes
- `chore`: maintenance tasks (build, dependencies, etc.)
- `doc`: documentation updates
- `test`: adding or updating tests
- `style`: code style changes (formatting, whitespace, etc.)

**Format**: `<prefix>: <lowercase description>`

**NOTE**: All commit messages must be written in English.

**Examples**:
- `feat: add one-click startup script`
- `fix: resolve mcp connection timeout issue`
- `refactor: extract common logic into utility class`
- `chore: update dependencies to latest versions`
- `doc: add installation guide to readme`
- `test: add unit tests for mcp client`
- `style: fix indentation in bot agent class`

## Code Language Guidelines

**IMPORTANT**: Documents should primarily be in English, except when testing or documentation requires Chinese.

- All prompts, messages, and UI text must be in English
- This applies to Java source code, shell scripts
- User input (natural language queries) can be in any language

## Security Guidelines

**IMPORTANT**: Never hardcode secrets, API keys, or passwords in the codebase.

- Use environment variables for sensitive configuration
- Add sensitive files to `.gitignore`
- Review commits before pushing to ensure no credentials are exposed

## Code Quality Guidelines

**IMPORTANT**: All code changes must adhere to the following quality standards.

### Code Style

- Follow [Google Java Style Guide](https://google.github.io/styleguide/javaguide.html)
- Use 2-space indentation
- Max line length: 100 characters
- Use meaningful variable and method names
- Avoid unnecessary comments; prefer self-documenting code

### Java 8 Compatibility

**IMPORTANT**: The project must remain compatible with Java 8.

- Do NOT use Java 9+ features:
  - No `var` keyword (Java 10+)
  - No `List.of()`, `Set.of()`, `Map.of()` (Java 9+) - use `Collections.unmodifiableList()` instead
  - No `Optional.ifPresentOrElse()` (Java 9+) - use traditional `if-else` pattern
  - No `String.lines()` (Java 11+)
  - No private methods in interfaces (Java 9+)
  - No module system (JPMS)
- Use `Files.write()` instead of `Files.writeString()` (Java 11+)
- Always test with Java 8 before submitting changes

### Test Coverage

**IMPORTANT**: All new code changes require adequate test coverage.

- Minimum 30% line coverage for changed code
- Write unit tests for:
  - New classes and methods
  - Bug fixes (regression tests)
  - Complex business logic
- Use JUnit 4.x (compatible with Java 8)
- Use Mockito 4.x for mocking (last Java 8 compatible version)
- Run tests before committing: `mvn test`
- Coverage reports are generated during CI pipeline
