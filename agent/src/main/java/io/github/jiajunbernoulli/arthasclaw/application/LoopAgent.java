/*
 * Copyright 2026 Jiajun Bernoulli (jiajunbernoulli@users.noreply.github.com)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.github.jiajunbernoulli.arthasclaw.application;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.jiajunbernoulli.arthasclaw.domain.Provider;
import io.github.jiajunbernoulli.arthasclaw.domain.task.TaskExecutor;
import io.github.jiajunbernoulli.arthasclaw.domain.task.TaskManager;
import io.github.jiajunbernoulli.arthasclaw.infrastructure.config.Config;
import io.github.jiajunbernoulli.arthasclaw.infrastructure.mcp.McpClient;
import io.github.jiajunbernoulli.arthasclaw.infrastructure.memory.MemoryManager;
import java.io.IOException;
import java.util.Scanner;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * LoopAgent handles the main AI assistant loop, processing user queries and managing tool calls.
 *
 * <p>This class coordinates between various handlers:
 * <ul>
 *   <li>{@link McpToolHandler} - for MCP tool management</li>
 *   <li>{@link ChatResponseHandler} - for chat response processing</li>
 *   <li>{@link TaskCommandHandler} - for task-related commands</li>
 * </ul>
 */
public class LoopAgent {
  private static final Logger log = LoggerFactory.getLogger(LoopAgent.class);

  private final Provider provider;
  private final ObjectMapper mapper = new ObjectMapper();
  private final McpClient mcpClient;
  private final ArrayNode messages;
  private ArrayNode toolsConfig;
  private String skillsPrompt;
  private SessionContext sessionContext;
  private MemoryManager memoryManager;

  // Handlers
  private final TaskManager taskManager;
  private final TaskExecutor taskExecutor;
  private final TaskCommandHandler taskCommandHandler;
  private final McpToolHandler mcpToolHandler;
  private final ChatResponseHandler chatResponseHandler;

  private static final String BASE_SYSTEM_PROMPT =
      "You are an expert Java diagnostic assistant specialized in runtime troubleshooting.\n"
          + "You have access to Arthas diagnostic tools via MCP protocol.\n\n"

          + "## Core Capabilities\n"
          + "- Thread analysis: deadlock detection, CPU profiling, stack traces\n"
          + "- Memory analysis: heap inspection, GC behavior monitoring\n"
          + "- Method tracing: call monitoring, performance profiling\n"
          + "- Class inspection: loaded classes, bytecode viewing\n\n"

          + "## Tool Usage Guidelines\n"
          + "1. Start with lightweight commands (thread, dashboard) before deep analysis\n"
          + "2. Always explain what you're about to do before calling tools\n"
          + "3. When a tool fails, explain the error and suggest alternatives\n"
          + "4. Present results in a structured, readable format\n\n"

          + "## Language Rule\n"
          + "Always reply in the same language that the user used.\n"
          + "- Chinese input → Chinese output\n"
          + "- English input → English output\n"
          + "- Do not translate unless explicitly asked\n\n"

          + "## Safety Rules\n"
          + "- For destructive operations (e.g., jad, redefine), ask for user confirmation\n"
          + "- Explain potential impact before executing commands\n\n"

          + "## Async Task Support\n"
          + "For long-running operations (e.g., watching a method N times),\n"
          + "use the `create_async_task` tool. Inform the user of task_id.\n"
          + "Commands: /tasks (list), /stop <task_id> (cancel)\n\n"

          + "## Error Handling\n"
          + "- If a tool returns an error, explain the cause and suggest next steps\n"
          + "- If Arthas connection is lost, inform the user and suggest reattaching";

  /**
   * Create LoopAgent with configuration.
   *
   * @param provider completion provider
   * @param mcpClient MCP client
   * @param config configuration
   */
  public LoopAgent(Provider provider, McpClient mcpClient, Config config) {
    this.provider = provider;
    this.mcpClient = mcpClient;
    this.messages = mapper.createArrayNode();
    this.skillsPrompt = "";

    // Initialize task components
    this.taskManager = new TaskManager();
    this.taskExecutor = new TaskExecutor(mcpClient, taskManager);
    this.taskCommandHandler = new TaskCommandHandler(taskManager, taskExecutor, mcpClient, mapper);

    // Initialize handlers
    this.mcpToolHandler = new McpToolHandler(mcpClient, mapper, config);
    this.chatResponseHandler =
        new ChatResponseHandler(
            provider, mcpClient, mapper, taskCommandHandler, memoryManager, config);

    // System prompt
    updateSystemMessage();
  }

  /**
   * Legacy constructor for backward compatibility with default configuration.
   *
   * @param provider completion provider
   * @param mcpClient MCP client
   */
  public LoopAgent(Provider provider, McpClient mcpClient) {
    this(provider, mcpClient, new Config());
  }

  /**
   * Update the system message with skills prompt.
   */
  private void updateSystemMessage() {
    // Remove existing system message if present
    if (messages.size() > 0 && "system".equals(messages.get(0).get("role").asText())) {
      messages.remove(0);
    }

    // Build system message with skills
    StringBuilder systemContent = new StringBuilder(BASE_SYSTEM_PROMPT);
    if (skillsPrompt != null && !skillsPrompt.isEmpty()) {
      systemContent.append("\n\n---\n\n# Installed Skills\n\n").append(skillsPrompt);
    }

    ObjectNode sysMsg = mapper.createObjectNode();
    sysMsg.put("role", "system");
    sysMsg.put("content", systemContent.toString());
    messages.insert(0, sysMsg);
  }

  /**
   * Set the combined skills prompt. This will update the system message.
   *
   * @param skillsPrompt the combined prompt from enabled skills
   */
  public void setSkillsPrompt(String skillsPrompt) {
    this.skillsPrompt = skillsPrompt != null ? skillsPrompt : "";
    updateSystemMessage();
  }

  /**
   * Initialize the agent by fetching tools from MCP. Should be called before processing queries.
   *
   * @return true if initialization succeeded, false otherwise
   */
  public boolean init() {
    this.toolsConfig = mcpToolHandler.fetchTools();
    return toolsConfig != null && toolsConfig.size() > 0;
  }

  /**
   * Set the session context for this agent.
   *
   * @param sessionContext the session context
   */
  public void setSessionContext(SessionContext sessionContext) {
    this.sessionContext = sessionContext;
  }

  /**
   * Set the memory manager for this agent.
   *
   * @param memoryManager the memory manager
   */
  public void setMemoryManager(MemoryManager memoryManager) {
    this.memoryManager = memoryManager;
    // Update handler with new memory manager
    this.chatResponseHandler.setMemoryManager(memoryManager);
  }

  /**
   * Process a single query and return the response.
   *
   * @param input the user query
   */
  public void processQuery(String input) {
    if (input == null || input.trim().isEmpty()) {
      return;
    }

    // Start request context
    String requestId = "-";
    if (sessionContext != null) {
      requestId = sessionContext.startRequest();
    }

    // Log user input (truncate if too long)
    String truncatedInput = input.length() > 200 ? input.substring(0, 200) + "..." : input;
    log.info("[{}] User query: {}", requestId, truncatedInput);

    // Handle system commands
    if (input.startsWith("/")) {
      handleSystemCommand(input);
      // End request context
      if (sessionContext != null) {
        sessionContext.endRequest();
      }
      log.debug("[{}] Request completed", requestId);
      return;
    }

    // Check if user wants to save something to memory
    boolean shouldExtractMemory = memoryManager != null && memoryManager.shouldExtractMemory(input);
    String userMessageForMemory = input;

    ObjectNode userMsg = mapper.createObjectNode();
    userMsg.put("role", "user");
    userMsg.put("content", input);
    messages.add(userMsg);

    // Save user message to session
    if (memoryManager != null) {
      memoryManager.addMessage("user", input);
    }

    try {
      processAiResponse();

      // Extract memory if needed (after AI response)
      if (shouldExtractMemory) {
        extractAndSaveMemory(userMessageForMemory);
      }
    } catch (IOException e) {
      log.error("Error processing AI response: {}", e.getMessage());
      System.err.println("[-] Error: " + e.getMessage());
    } finally {
      // End request context
      if (sessionContext != null) {
        sessionContext.endRequest();
      }
      log.debug("[{}] Request completed", requestId);
    }
  }

  /**
   * Handle system commands like /tasks and /stop taskId.
   *
   * @param command system command
   */
  private void handleSystemCommand(String command) {
    String[] parts = command.trim().split("\\s+");
    String cmd = parts[0].toLowerCase();

    switch (cmd) {
      case "/tasks":
        taskCommandHandler.listTasks();
        break;
      case "/stop":
        if (parts.length > 1) {
          String taskId = parts[1];
          taskCommandHandler.cancelTask(taskId);
        } else {
          System.out.println("❌ Usage: /stop <taskId>");
        }
        break;
      default:
        System.out.println("❌ Unknown command. Available commands: /tasks, /stop <taskId>");
        break;
    }
  }

  /**
   * Get task manager instance.
   *
   * @return task manager
   */
  public TaskManager getTaskManager() {
    return taskManager;
  }

  /**
   * Get task command handler instance.
   *
   * @return task command handler
   */
  public TaskCommandHandler getTaskCommandHandler() {
    return taskCommandHandler;
  }

  /**
   * Extract and save memory from user message using LLM.
   *
   * @param userMessage the user message containing memory request
   */
  private void extractAndSaveMemory(String userMessage) {
    log.info("Extracting memory from user message");

    // Create a simple prompt to extract the fact
    String extractPrompt =
        String.format(
            "Extract the key information the user wants to remember.\n"
                + "Return ONLY a JSON object with 'key' and 'value' fields.\n\n"
                + "Examples:\n"
                + "Input: \"记住，这个问题的根因是连接池配置错误\"\n"
                + "Output: {\"key\": \"rootCause:connection-pool\", "
                + "\"value\": \"连接池配置错误\"}\n\n"
                + "Input: \"Remember, the root cause is connection pool misconfiguration\"\n"
                + "Output: {\"key\": \"rootCause:connection-pool\", "
                + "\"value\": \"connection pool misconfiguration\"}\n\n"
                + "Input: \"%s\"\n"
                + "Output:",
            userMessage.replace("\"", "\\\""));

    try {
      // Create temporary message for extraction
      ArrayNode extractMessages = mapper.createArrayNode();
      ObjectNode extractMsg = mapper.createObjectNode();
      extractMsg.put("role", "user");
      extractMsg.put("content", extractPrompt);
      extractMessages.add(extractMsg);

      ObjectNode response = provider.chatCompletion(extractMessages, null);
      if (response.hasNonNull("content")) {
        String content = response.get("content").asText().trim();

        // Try to parse as JSON
        if (content.startsWith("{")) {
          JsonNode factJson = mapper.readTree(content);
          String key = factJson.has("key") ? factJson.get("key").asText() : "user-note";
          String value = factJson.has("value") ? factJson.get("value").asText() : userMessage;

          memoryManager.addFact(key, value);
          log.info("Memory saved: key={}, value={}", key, value);
          System.out.println("[Memory] Saved: " + key + " = " + value);
        }
      }
    } catch (Exception e) {
      log.error("Failed to extract memory: {}", e.getMessage());
      // Fallback: save the raw message
      if (memoryManager != null) {
        memoryManager.addFact("user-note", userMessage);
      }
    }
  }

  /** Start interactive loop for user queries. */
  public void startInteractiveLoop() {
    System.out.println("🤖 AI Assistant is ready! (Type 'exit' to quit)");
    Scanner scanner = new Scanner(System.in);

    // Fetch tools from MCP
    if (!init()) {
      System.err.println(
          "[-] Warning: Failed to load tools from Arthas. " + "Some functionality may be limited.");
    }

    while (true) {
      System.out.print("\n> ");
      String input = scanner.nextLine().trim();
      if (input.equalsIgnoreCase("exit") || input.equalsIgnoreCase("quit")) {
        break;
      }
      if (input.isEmpty()) {
        continue;
      }

      processQuery(input);
    }

    close();
  }

  /** Close resources. */
  public void close() {
    mcpClient.close();
    provider.close();
  }

  /**
   * Process AI response and handle tool calls.
   *
   * @throws IOException if processing fails
   */
  private void processAiResponse() throws IOException {
    chatResponseHandler.processResponse(
        messages,
        toolsConfig,
        new ChatResponseHandler.ResponseCallback() {
          @Override
          public void onTextResponse(String content) {
            System.out.println("\n🤖 AI: " + content);
          }

          @Override
          public void onToolInvoking(String toolName, String arguments) {
            System.out.println("[*] Calling tool: " + toolName + " with args: " + arguments);
          }

          @Override
          public void onToolResult(int resultLength, boolean wasTruncated) {
            System.out.println("[*] Tool result length: " + resultLength + " chars");
            if (wasTruncated) {
              System.out.println("[!] Tool result was truncated");
            }
          }
        });
  }

  /** Clear conversation history (keep system prompt). */
  public void clearMessages() {
    chatResponseHandler.trimMessages(messages);
    // Keep only system message
    while (messages.size() > 1) {
      messages.remove(1);
    }
  }

  /**
   * Get conversation messages for display.
   *
   * @return the messages array
   */
  public ArrayNode getMessages() {
    return messages;
  }
}
