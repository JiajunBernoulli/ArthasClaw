/*
 * Copyright © 2026 Jiajun Bernoulli (jiajunbernoulli@users.noreply.github.com)
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
import io.github.jiajunbernoulli.arthasclaw.domain.CompletionProvider;
import io.github.jiajunbernoulli.arthasclaw.domain.task.Task;
import io.github.jiajunbernoulli.arthasclaw.domain.task.TaskExecutor;
import io.github.jiajunbernoulli.arthasclaw.domain.task.TaskManager;
import io.github.jiajunbernoulli.arthasclaw.infrastructure.config.Config;
import io.github.jiajunbernoulli.arthasclaw.infrastructure.mcp.McpClient;
import io.github.jiajunbernoulli.arthasclaw.infrastructure.memory.MemoryManager;
import java.io.IOException;
import java.util.Scanner;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * LoopAgent handles the main AI assistant loop, processing user queries and managing tool calls.
 */
public class LoopAgent {
  private static final Logger log = LoggerFactory.getLogger(LoopAgent.class);
  
  // Detect if terminal supports ANSI colors
  private static final boolean SUPPORTS_COLOR = detectColorSupport();
  
  private final CompletionProvider provider;
  private final ObjectMapper mapper = new ObjectMapper();
  private final McpClient mcpClient;
  private final ArrayNode messages;
  private ArrayNode toolsConfig;
  private String skillsPrompt;
  private SessionContext sessionContext;
  private MemoryManager memoryManager;
  private TaskManager taskManager;
  private TaskExecutor taskExecutor;

  // Configuration values
  private final int maxIterations;
  private final int maxMessages;
  private final int maxRetries;
  private final int maxToolResultLength;
  private final long listToolsTimeoutSeconds;
  private final long toolCallTimeoutSeconds;
  private final long retryDelayMs;

  private static final String BASE_SYSTEM_PROMPT = "You are an expert Java diagnostic assistant. "
      + "You have access to Arthas tools via MCP. "
      + "Use the provided tools to inspect and diagnose the Java application.\n\n"
      + "Language Rule: Always reply in the same language that the user used to ask the question. "
      + "- If the input is Chinese, output Chinese. "
      + "- If the input is English, output English. "
      + "- Do not output translations unless explicitly asked.\n\n"
      + "# Async Task Support\n"
      + "For long-running operations (e.g., watching a method multiple times, collecting samples), "
      + "use the `create_async_task` tool to create a background task. This allows the user to "
      + "continue interacting while the task runs.\n"
      + "Typical use cases:\n"
      + "- Watch a method N times: create_async_task with task_type='watch_method'\n"
      + "- Collect performance samples over time\n"
      + "- Any operation that needs to wait for multiple events\n"
      + "After creating a task, inform the user of the task_id and how to check status (/tasks) "
      + "or cancel it (/stop <task_id>).";

  /**
   * Create LoopAgent with configuration.
   *
   * @param provider  completion provider
   * @param mcpClient MCP client
   * @param config    configuration
   */
  public LoopAgent(CompletionProvider provider, McpClient mcpClient, Config config) {
    this.provider = provider;
    this.mcpClient = mcpClient;
    this.messages = mapper.createArrayNode();
    this.skillsPrompt = "";
    this.taskManager = new TaskManager();
    this.taskExecutor = new TaskExecutor(mcpClient, taskManager);

    // Load configuration
    Config.AgentConfig agentConfig = config.getAgent();
    this.maxIterations = agentConfig.getMaxIterations();
    this.maxMessages = agentConfig.getMaxMessages();
    this.maxRetries = agentConfig.getMaxRetries();
    this.maxToolResultLength = agentConfig.getMaxToolResultLength();
    this.listToolsTimeoutSeconds = agentConfig.getListToolsTimeoutSeconds();
    this.toolCallTimeoutSeconds = agentConfig.getToolCallTimeoutSeconds();
    this.retryDelayMs = agentConfig.getRetryDelayMs();

    // System prompt
    updateSystemMessage();
  }

  /**
   * Legacy constructor for backward compatibility with default configuration.
   *
   * @param provider  completion provider
   * @param mcpClient MCP client
   */
  public LoopAgent(CompletionProvider provider, McpClient mcpClient) {
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
   * Initialize the agent by fetching tools from MCP.
   * Should be called before processing queries.
   *
   * @return true if initialization succeeded, false otherwise
   */
  public boolean init() {
    this.toolsConfig = fetchToolsFromMcp();
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
        listTasks();
        break;
      case "/stop":
        if (parts.length > 1) {
          String taskId = parts[1];
          cancelTask(taskId);
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
   * List all tasks.
   */
  private void listTasks() {
    if (taskManager.getTaskCount() == 0) {
      System.out.println("📋 No tasks found");
      return;
    }

    System.out.println("📋 Task List:");
    System.out.println("-----------------------------------------------------------------------");
    System.out.println("Task ID      | Description              | Status    | Updated At");
    System.out.println("-----------------------------------------------------------------------");

    String resetCode = SUPPORTS_COLOR ? "\033[0m" : "";
    for (Task task : taskManager.getAllTasks()) {
      String status = task.getStatus().toString();
      String statusColor = getStatusColor(status);
      String updatedAt = task.getUpdatedAt().toString().substring(0, 19);
      
      System.out.printf("%-12s | %-25s | %s%-7s%s | %s\n", 
          task.getId(), 
          truncateDescription(task.getDescription(), 25), 
          statusColor, 
          status, 
          resetCode, 
          updatedAt);
    }

    System.out.println("-----------------------------------------------------------------------");
  }

  /**
   * Cancel a task by ID.
   *
   * @param taskId task ID
   */
  private void cancelTask(String taskId) {
    boolean cancelled = taskManager.cancelTask(taskId);
    if (cancelled) {
      System.out.println("✅ Task cancelled: " + taskId);
    } else {
      System.out.println("❌ Task not found or cannot be cancelled: " + taskId);
    }
  }

  /**
   * Detect if the terminal supports ANSI color codes.
   *
   * @return true if colors are supported
   */
  private static boolean detectColorSupport() {
    // Check if running in a terminal
    if (System.console() == null) {
      return false;
    }
    // Check for Windows (older versions don't support ANSI)
    String osName = System.getProperty("os.name", "").toLowerCase();
    if (osName.contains("windows")) {
      // Windows 10+ supports ANSI, but hard to detect reliably
      return false;
    }
    return true;
  }

  /**
   * Get color code for task status.
   *
   * @param status task status
   * @return color code (empty string if colors not supported)
   */
  private String getStatusColor(String status) {
    if (!SUPPORTS_COLOR) {
      return "";
    }
    switch (status) {
      case "RUNNING":
        return "\033[34m";
      case "COMPLETED":
        return "\033[32m";
      case "FAILED":
        return "\033[31m";
      case "CANCELLED":
        return "\033[33m";
      default:
        return "\033[37m";
    }
  }

  /**
   * Truncate description to fit in table.
   *
   * @param description task description
   * @param maxLength maximum length
   * @return truncated description
   */
  private String truncateDescription(String description, int maxLength) {
    if (description.length() <= maxLength) {
      return description;
    }
    return description.substring(0, maxLength - 3) + "...";
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
   * Extract and save memory from user message using LLM.
   *
   * @param userMessage the user message containing memory request
   */
  private void extractAndSaveMemory(String userMessage) {
    log.info("Extracting memory from user message");

    // Create a simple prompt to extract the fact
    String extractPrompt = String.format(
        "Extract the key information the user wants to remember from this message. "
            + "Return only a JSON object with 'key' and 'value' fields, nothing else.\n\n"
            + "Example:\n"
            + "Input: \"记住，这个问题的根因是连接池配置错误\"\n"
            + "Output: {\"key\": \"rootCause:connection-pool\", \"value\": \"连接池配置错误\"}\n\n"
            + "Input: \"%s\"\n"
            + "Output:",
        userMessage.replace("\"", "\\\"")
    );

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

  /**
   * Start interactive loop for user queries.
   */
  public void startInteractiveLoop() {
    System.out.println("🤖 AI Assistant is ready! (Type 'exit' to quit)");
    Scanner scanner = new Scanner(System.in);

    // Fetch tools from MCP
    if (!init()) {
      System.err.println(
          "[-] Warning: Failed to load tools from Arthas. "
          + "Some functionality may be limited.");
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

  /**
   * Close resources.
   */
  public void close() {
    mcpClient.close();
    provider.close();
  }

  /**
   * Add built-in tools to the tools configuration.
   *
   * @param toolsArray the tools array to add to
   */
  private void addBuiltInTools(ArrayNode toolsArray) {
    // create_async_task tool for long-running operations
    ObjectNode asyncTaskTool = mapper.createObjectNode();
    asyncTaskTool.put("type", "function");
    
    ObjectNode function = mapper.createObjectNode();
    function.put("name", "create_async_task");
    function.put("description", 
        "Create an async task for long-running operations. "
        + "Use this when the user wants to monitor/collect data over time "
        + "(e.g., watch a method N times, collect samples). "
        + "The task runs in background, user can check status with /tasks or cancel with /stop.");
    
    ObjectNode params = mapper.createObjectNode();
    params.put("type", "object");
    
    // task_type
    ObjectNode taskType = mapper.createObjectNode();
    taskType.put("type", "string");
    ArrayNode enumValues = mapper.createArrayNode();
    enumValues.add("watch_method");
    enumValues.add("collect_samples");
    taskType.set("enum", enumValues);
    taskType.put("description", "Type: watch_method (monitor calls) or collect_samples");
    
    ObjectNode properties = mapper.createObjectNode();
    properties.set("task_type", taskType);
    
    // description
    ObjectNode descProp = mapper.createObjectNode();
    descProp.put("type", "string");
    descProp.put("description", "Human-readable description of what this task does");
    properties.set("description", descProp);
    
    // class_pattern
    ObjectNode classPattern = mapper.createObjectNode();
    classPattern.put("type", "string");
    classPattern.put("description", "Class pattern for watch_method task (e.g. 'MathGame')");
    properties.set("class_pattern", classPattern);
    
    // method_pattern
    ObjectNode methodPattern = mapper.createObjectNode();
    methodPattern.put("type", "string");
    methodPattern.put("description", "Method pattern for watch_method task (e.g. 'run')");
    properties.set("method_pattern", methodPattern);
    
    // count
    ObjectNode countProp = mapper.createObjectNode();
    countProp.put("type", "integer");
    countProp.put("description", "Number of times to watch/collect (default: 10)");
    properties.set("count", countProp);
    
    // interval_ms
    ObjectNode intervalMs = mapper.createObjectNode();
    intervalMs.put("type", "integer");
    intervalMs.put("description", "Interval between watches in ms (default: 1000)");
    properties.set("interval_ms", intervalMs);
    
    // express
    ObjectNode expressProp = mapper.createObjectNode();
    expressProp.put("type", "string");
    expressProp.put("description", "Watch expression (default: '{params, returnObj, #cost}')");
    properties.set("express", expressProp);
    
    params.set("properties", properties);
    
    ArrayNode required = mapper.createArrayNode();
    required.add("task_type");
    required.add("description");
    params.set("required", required);
    
    function.set("parameters", params);
    asyncTaskTool.set("function", function);
    
    toolsArray.add(asyncTaskTool);
  }

  /**
   * Fetch tools from MCP server.
   *
   * @return array of tools
   */
  private ArrayNode fetchToolsFromMcp() {
    log.info("Fetching tools from Arthas MCP Server...");
    System.out.println("[*] Fetching tools from Arthas MCP Server...");
    
    Exception lastException = null;
    for (int attempt = 1; attempt <= maxRetries; attempt++) {
      try {
        JsonNode result = mcpClient.listTools().get(listToolsTimeoutSeconds, TimeUnit.SECONDS);
        JsonNode toolsList = result.get("tools");

        ArrayNode openAiTools = mapper.createArrayNode();

        if (toolsList != null && toolsList.isArray()) {
          for (JsonNode tool : toolsList) {
            ObjectNode aiTool = mapper.createObjectNode();
            aiTool.put("type", "function");
            ObjectNode function = mapper.createObjectNode();
            function.put("name", tool.get("name").asText());
            function.put("description", tool.get("description").asText());

            if (tool.has("inputSchema")) {
              function.set("parameters", tool.get("inputSchema"));
            } else {
              // Empty params
              ObjectNode params = mapper.createObjectNode();
              params.put("type", "object");
              params.set("properties", mapper.createObjectNode());
              function.set("parameters", params);
            }
            aiTool.set("function", function);
            openAiTools.add(aiTool);
          }
        }
        log.info("Successfully loaded {} tools from Arthas", openAiTools.size());
        System.out.println(String.format("[+] Loaded %d tools from Arthas.", openAiTools.size()));
        
        // Add built-in async task tool
        addBuiltInTools(openAiTools);
        
        return openAiTools;
      } catch (java.util.concurrent.TimeoutException e) {
        lastException = e;
        log.warn(
            "Attempt {}/{}: Timeout fetching tools",
            attempt, maxRetries);
        System.err.println(
            "[-] Attempt " + attempt + "/" + maxRetries + ": "
            + "Timeout fetching tools");
      } catch (Exception e) {
        lastException = e;
        log.warn("Attempt {}/{}: {}", attempt, maxRetries, e.getMessage());
        System.err.println("[-] Attempt " + attempt + "/" + maxRetries + ": " + e.getMessage());
      }
      
      // Wait before retry (except last attempt)
      if (attempt < maxRetries) {
        try {
          Thread.sleep(retryDelayMs);
        } catch (InterruptedException ie) {
          Thread.currentThread().interrupt();
          break;
        }
      }
    }
    
    log.error("Failed to fetch tools after {} attempts: {}", maxRetries, 
        lastException != null ? lastException.getMessage() : "unknown error");
    System.err.println("[-] Failed to fetch tools after " + maxRetries + " attempts: "
        + (lastException != null ? lastException.getMessage() : "unknown error"));
    return mapper.createArrayNode();
  }

  /**
   * Process AI response and handle tool calls.
   */
  private void processAiResponse() {
    int iteration = 0;
    while (iteration++ < maxIterations) {
      // Set iteration in context
      if (sessionContext != null) {
        sessionContext.setIteration(iteration);
      }
      
      long iterationStartTime = System.currentTimeMillis();
      log.debug("Iteration {} started", iteration);

      try {
        // Trim message history to prevent unbounded growth
        trimMessages();

        // Call LLM with timing
        long llmStartTime = System.currentTimeMillis();
        ObjectNode message = provider.chatCompletion(messages, toolsConfig);
        long llmDuration = System.currentTimeMillis() - llmStartTime;
        log.info("LLM call completed in {}ms", llmDuration);

        // Add assistant message to history
        messages.add(message);

        // Print text response if any
        if (message.hasNonNull("content")) {
          String assistantContent = message.get("content").asText();
          System.out.println("\n🤖 AI: " + assistantContent);
          
          // Save assistant message to session
          if (memoryManager != null) {
            memoryManager.addMessage("assistant", assistantContent);
          }
        }

        // Handle tool calls
        if (message.has("tool_calls")) {
          JsonNode toolCalls = message.get("tool_calls");
          int toolCallCount = toolCalls.size();
          log.info("AI requested {} tool call(s)", toolCallCount);

          for (JsonNode toolCall : toolCalls) {
            String functionName = toolCall.get("function").get("name").asText();
            String functionArgsStr = toolCall.get("function")
                .get("arguments").asText();

            // Log tool call details
            log.info("Tool call: {} with args: {}", functionName, functionArgsStr);
            System.out.println(
                "[*] Calling tool: " + functionName + " with args: " + functionArgsStr);

            ObjectNode arguments = (ObjectNode) mapper.readTree(functionArgsStr);

            // Handle built-in create_async_task tool
            String toolResultStr;
            final String toolCallId = toolCall.get("id").asText();
            if ("create_async_task".equals(functionName)) {
              toolResultStr = handleCreateAsyncTask(arguments);
              ObjectNode toolMsg = mapper.createObjectNode();
              toolMsg.put("role", "tool");
              toolMsg.put("tool_call_id", toolCallId);
              toolMsg.put("name", functionName);
              toolMsg.put("content", toolResultStr);
              messages.add(toolMsg);
              continue;
            }

            // Execute via MCP with timing
            long toolStartTime = System.currentTimeMillis();
            try {
              JsonNode mcpResult = mcpClient.callTool(functionName, arguments)
                  .get(toolCallTimeoutSeconds, TimeUnit.SECONDS);
              final long toolDuration = System.currentTimeMillis() - toolStartTime;

              // Extract text content from MCP result
              StringBuilder sb = new StringBuilder();
              if (mcpResult.has("content") && mcpResult.get("content").isArray()) {
                for (JsonNode content : mcpResult.get("content")) {
                  if (content.has("type") && "text".equals(content.get("type").asText())) {
                    sb.append(content.get("text").asText()).append("\n");
                  }
                }
              } else {
                sb.append(mcpResult.toString());
              }
              toolResultStr = sb.toString().trim();
              if (toolResultStr.isEmpty()) {
                toolResultStr = "Success (No output)";
              }

              // Log tool result
              log.info(
                  "Tool {} completed in {}ms, result length: {} chars",
                  functionName, toolDuration, toolResultStr.length());
            } catch (Exception e) {
              long toolDuration = System.currentTimeMillis() - toolStartTime;
              toolResultStr = "Error executing tool: " + e.getMessage();
              log.error(
                  "Tool {} failed after {}ms: {}",
                  functionName, toolDuration, e.getMessage());
            }

            System.out.println("[*] Tool result length: " + toolResultStr.length() + " chars");

            // Truncate tool result if too long to save tokens
            boolean wasTruncated = false;
            int originalLength = toolResultStr.length();
            if (toolResultStr.length() > maxToolResultLength) {
              String truncated = toolResultStr.substring(0, maxToolResultLength);
              toolResultStr = truncated + "\n... [TRUNCATED: result too long, showing first "
                  + maxToolResultLength + " chars of " + originalLength + "]";
              System.out.println("[!] Tool result truncated to " + maxToolResultLength + " chars");
              wasTruncated = true;
              log.warn("Tool result truncated: original={} chars, truncated={} chars", 
                  originalLength, maxToolResultLength);
            }

            // Add tool result to messages
            ObjectNode toolMsg = mapper.createObjectNode();
            toolMsg.put("role", "tool");
            toolMsg.put("tool_call_id", toolCallId);
            toolMsg.put("name", functionName);
            toolMsg.put("content", toolResultStr);
            messages.add(toolMsg);
          }

          long iterationDuration = System.currentTimeMillis() - iterationStartTime;
          log.debug("Iteration {} completed in {}ms with {} tool calls", 
              iteration, iterationDuration, toolCallCount);
          
          // Loop continues to send tool results back to AI
          continue;
        }

        // No tool calls, conversation turn ends
        long iterationDuration = System.currentTimeMillis() - iterationStartTime;
        log.info("Request completed in {} iterations, {}ms total", iteration, iterationDuration);
        break;
      } catch (IOException e) {
        // Check for authentication errors (non-retryable)
        String errorMessage = e.getMessage();
        if (errorMessage != null
            && (errorMessage.contains("401")
                || errorMessage.contains("403")
                || errorMessage.contains("auth"))) {
          log.error("Authentication failed: {}", errorMessage);
          System.err.println(
              "[-] Authentication failed: " + errorMessage + ". Check your API key.");
          break;
        }
        // Timeout or connection errors
        if (errorMessage != null
            && (errorMessage.contains("timeout")
                || errorMessage.contains("Timeout"))) {
          log.error("Request timeout: {}", errorMessage);
          System.err.println("[-] Request timeout. Please try again.");
          break;
        }
        // Other IO errors
        log.error("Request failed: {}", errorMessage, e);
        System.err.println("[-] Request failed: " + errorMessage);
        break;
      } catch (Exception e) {
        log.error("Unexpected error: {}", e.getMessage(), e);
        System.err.println("[-] Unexpected error: " + e.getMessage());
        break;
      }
    }

    if (iteration > maxIterations) {
      log.warn("Reached max iterations ({})", maxIterations);
      System.err.println(
          "[-] Reached max iterations (" + maxIterations + "), "
          + "stopping to prevent infinite loop.");
    }
  }

  /**
   * Handle create_async_task tool call.
   *
   * @param arguments tool arguments
   * @return result message
   */
  private String handleCreateAsyncTask(ObjectNode arguments) {
    try {
      String taskType = arguments.has("task_type") 
          ? arguments.get("task_type").asText() : "watch_method";
      String description = arguments.has("description") 
          ? arguments.get("description").asText() : "Async task";
      String classPattern = arguments.has("class_pattern") 
          ? arguments.get("class_pattern").asText() : null;
      String methodPattern = arguments.has("method_pattern") 
          ? arguments.get("method_pattern").asText() : null;
      int count = arguments.has("count") 
          ? arguments.get("count").asInt() : 10;
      long intervalMs = arguments.has("interval_ms") 
          ? arguments.get("interval_ms").asLong() : 1000L;
      String express = arguments.has("express") 
          ? arguments.get("express").asText() : "{params, returnObj, #cost}";

      // Create task
      Task task = taskManager.createTask(description);

      if ("watch_method".equals(taskType)) {
        if (classPattern == null || methodPattern == null) {
          return "Error: class_pattern and method_pattern are required for watch_method task";
        }

        // Execute watch method task
        executeWatchMethodTask(task, classPattern, methodPattern, count, intervalMs, express);

        return String.format(
            "Async task created successfully.\n"
            + "Task ID: %s\n"
            + "Description: %s\n"
            + "Type: %s\n"
            + "Target: %s.%s\n"
            + "Count: %d times\n"
            + "Interval: %dms\n\n"
            + "Use /tasks to check status or /stop %s to cancel.",
            task.getId(), description, taskType, classPattern, methodPattern, count, intervalMs, 
            task.getId());
      } else {
        return "Error: Unknown task type: " + taskType;
      }
    } catch (Exception e) {
      log.error("Failed to create async task", e);
      return "Error creating async task: " + e.getMessage();
    }
  }

  /**
   * Execute a watch method task.
   *
   * @param task task to execute
   * @param classPattern class pattern
   * @param methodPattern method pattern
   * @param count number of times to watch
   * @param intervalMs interval between watches
   * @param express watch expression
   */
  private void executeWatchMethodTask(final Task task, final String classPattern, 
      final String methodPattern, final int count, final long intervalMs, final String express) {
    taskExecutor.executeCustomTask(task, new Runnable() {
      @Override
      public void run() {
        try {
          StringBuilder result = new StringBuilder();
          result.append("Method watch task started\n");
          result.append("Watching: ").append(classPattern).append(".").append(methodPattern)
              .append(" for ").append(count).append(" times\n\n");

          for (int i = 1; i <= count; i++) {
            if (Thread.currentThread().isInterrupted()) {
              throw new InterruptedException("Task cancelled");
            }

            result.append("--- Watch #").append(i).append(" ---\n");

            // Call Arthas watch tool
            ObjectNode args = mapper.createObjectNode();
            args.put("classPattern", classPattern);
            args.put("methodPattern", methodPattern);
            args.put("express", express);
            args.put("condition", "");
            args.put("b", false);
            args.put("e", false);
            args.put("s", true);
            args.put("n", 1);

            try {
              JsonNode mcpResult = mcpClient.callTool("watch", args)
                  .get(30, TimeUnit.SECONDS);

              if (mcpResult.has("content") && mcpResult.get("content").isArray()) {
                for (JsonNode content : mcpResult.get("content")) {
                  if (content.has("type") && "text".equals(content.get("type").asText())) {
                    result.append(content.get("text").asText()).append("\n");
                  }
                }
              }
            } catch (Exception e) {
              result.append("Error: ").append(e.getMessage()).append("\n");
            }

            if (i < count) {
              result.append("\nWaiting ").append(intervalMs / 1000)
                  .append(" seconds for next watch...\n\n");
              Thread.sleep(intervalMs);
            }
          }

          result.append("\n--- Task completed ---\n");
          task.setResult(result.toString());
        } catch (InterruptedException e) {
          Thread.currentThread().interrupt();
        } catch (Exception e) {
          task.setErrorMessage("Task failed: " + e.getMessage());
          log.error("Watch method task failed", e);
        }
      }
    });
  }

  /**
   * Trim message history to prevent unbounded growth.
   * Keeps system prompt (first message) and recent messages up to maxMessages.
   */
  private void trimMessages() {
    while (messages.size() > maxMessages && messages.size() > 1) {
      // Remove oldest non-system message (index 1, since index 0 is system)
      messages.remove(1);
    }
  }

  /**
   * Clear conversation history (keep system prompt).
   */
  public void clearMessages() {
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
