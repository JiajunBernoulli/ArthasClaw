# ArthasClaw Architecture

## BotAgent.java - "Boot" Strategy

1. **Detect/Download**: On startup, the program checks if `arthas-boot.jar` exists locally. If not, it automatically downloads from Alibaba Cloud.

2. **Dynamic Injection**: Runs `java -jar arthas-boot.jar --attach-only` to attach Arthas to the target process.

3. **Server-side Loading**: After the target process loads the Arthas Agent, it loads the core jar package (`arthas-core.jar`) from `~/.arthas/lib/` (user directory cache) or downloads from the network.

4. **Start MCP Server**: Arthas starts an HTTP Server inside the target process (default port 8563).

## Summary

Our 3 classes play the role of MCP Client:

- **BotAgent**: Responsible for "ignition" - injecting Arthas into the target process.
- **McpClient**: Responsible for "connection" - connecting to the HTTP interface exposed by Arthas in the target process.
- **AiAgent**: Responsible for "thinking" - converting your natural language into tool calls to Arthas.

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

## Security Guidelines

**IMPORTANT**: Never hardcode secrets, API keys, or passwords in the codebase.

- Use environment variables for sensitive configuration
- Add sensitive files to `.gitignore`
- Review commits before pushing to ensure no credentials are exposed
