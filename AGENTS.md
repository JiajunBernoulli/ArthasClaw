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
