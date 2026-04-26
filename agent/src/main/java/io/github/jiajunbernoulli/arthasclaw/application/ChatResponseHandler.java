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
import io.github.jiajunbernoulli.arthasclaw.infrastructure.config.Config;
import io.github.jiajunbernoulli.arthasclaw.infrastructure.mcp.McpClient;
import io.github.jiajunbernoulli.arthasclaw.infrastructure.memory.MemoryManager;
import java.io.IOException;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * ChatResponseHandler handles chat responses from LLM and manages tool calls.
 *
 * <p>This class is responsible for:
 * <ul>
 *   <li>Processing AI responses in a loop</li>
 *   <li>Managing tool calls and their execution</li>
 *   <li>Handling message history trimming</li>
 *   <li>Coordinating with MCP client for tool execution</li>
 * </ul>
 */
public class ChatResponseHandler {
  private static final Logger log = LoggerFactory.getLogger(ChatResponseHandler.class);

  private final Provider provider;
  private final McpClient mcpClient;
  private final ObjectMapper mapper;
  private MemoryManager memoryManager;

  // Configuration values
  private final int maxIterations;
  private final int maxMessages;
  private final int maxToolResultLength;
  private final long toolCallTimeoutSeconds;

  /**
   * Callback interface for response handling events.
   */
  public interface ResponseCallback {
    /**
     * Called when AI produces text content.
     *
     * @param content the text content
     */
    void onTextResponse(String content);

    /**
     * Called when a tool is being invoked.
     *
     * @param toolName tool name
     * @param arguments tool arguments
     */
    void onToolInvoking(String toolName, String arguments);

    /**
     * Called when tool result is received.
     *
     * @param resultLength length of the result
     * @param wasTruncated whether result was truncated
     */
    void onToolResult(int resultLength, boolean wasTruncated);
  }

  /**
   * Create ChatResponseHandler with dependencies.
   *
   * @param provider completion provider
   * @param mcpClient MCP client
   * @param mapper JSON object mapper
   * @param taskCommandHandler task command handler
   * @param memoryManager memory manager (can be null)
   * @param config configuration
   */
  public ChatResponseHandler(
      Provider provider,
      McpClient mcpClient,
      ObjectMapper mapper,
      MemoryManager memoryManager,
      Config config) {
    this.provider = provider;
    this.mcpClient = mcpClient;
    this.mapper = mapper;
    this.memoryManager = memoryManager;

    Config.AgentConfig agentConfig = config.getAgent();
    this.maxIterations = agentConfig.getMaxIterations();
    this.maxMessages = agentConfig.getMaxMessages();
    this.maxToolResultLength = agentConfig.getMaxToolResultLength();
    this.toolCallTimeoutSeconds = agentConfig.getToolCallTimeoutSeconds();
  }

  /**
   * Process AI response and handle tool calls.
   *
   * @param messages message history
   * @param toolsConfig tools configuration
   * @param callback response callback for events
   * @throws IOException if processing fails
   */
  public void processResponse(ArrayNode messages, ArrayNode toolsConfig, ResponseCallback callback)
      throws IOException {
    int iteration = 0;
    while (iteration++ < maxIterations) {
      long iterationStartTime = System.currentTimeMillis();
      log.debug("Iteration {} started", iteration);

      try {
        // Trim message history to prevent unbounded growth
        trimMessages(messages);

        // Call LLM with timing
        long llmStartTime = System.currentTimeMillis();
        ObjectNode message = provider.chatCompletion(messages, toolsConfig);
        long llmDuration = System.currentTimeMillis() - llmStartTime;
        log.info("LLM call completed in {}ms", llmDuration);

        // Add assistant message to history
        messages.add(message);

        // Handle text response if any
        if (message.hasNonNull("content")) {
          String assistantContent = message.get("content").asText();
          callback.onTextResponse(assistantContent);

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
            String functionArgsStr = toolCall.get("function").get("arguments").asText();

            // Log tool call details
            log.info("Tool call: {} with args: {}", functionName, functionArgsStr);
            callback.onToolInvoking(functionName, functionArgsStr);

            ObjectNode arguments = (ObjectNode) mapper.readTree(functionArgsStr);

            // Execute via MCP with timing
            String toolResultStr;
            final String toolCallId = toolCall.get("id").asText();
            long toolStartTime = System.currentTimeMillis();
            try {
              JsonNode mcpResult =
                  mcpClient.callTool(functionName, arguments)
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
                  functionName,
                  toolDuration,
                  toolResultStr.length());
            } catch (Exception e) {
              long toolDuration = System.currentTimeMillis() - toolStartTime;
              toolResultStr = "Error executing tool: " + e.getMessage();
              log.error(
                  "Tool {} failed after {}ms: {}", functionName, toolDuration, e.getMessage());
            }

            // Truncate tool result if too long to save tokens
            boolean wasTruncated = false;
            int originalLength = toolResultStr.length();
            if (toolResultStr.length() > maxToolResultLength) {
              String truncated = toolResultStr.substring(0, maxToolResultLength);
              toolResultStr =
                  truncated
                      + "\n... [TRUNCATED: result too long, showing first "
                      + maxToolResultLength
                      + " chars of "
                      + originalLength
                      + "]";
              log.warn(
                  "Tool result truncated: original={} chars, truncated={} chars",
                  originalLength,
                  maxToolResultLength);
              wasTruncated = true;
            }

            callback.onToolResult(toolResultStr.length(), wasTruncated);

            // Add tool result to messages
            ObjectNode toolMsg = mapper.createObjectNode();
            toolMsg.put("role", "tool");
            toolMsg.put("tool_call_id", toolCallId);
            toolMsg.put("name", functionName);
            toolMsg.put("content", toolResultStr);
            messages.add(toolMsg);
          }

          long iterationDuration = System.currentTimeMillis() - iterationStartTime;
          log.debug(
              "Iteration {} completed in {}ms with {} tool calls",
              iteration,
              iterationDuration,
              toolCallCount);

          // Loop continues to send tool results back to AI
          continue;
        }

        // No tool calls, conversation turn ends
        long iterationDuration = System.currentTimeMillis() - iterationStartTime;
        log.info("Request completed in {} iterations, {}ms total", iteration, iterationDuration);
        break;
      } catch (IOException e) {
        handleIOException(e);
        break;
      } catch (Exception e) {
        log.error("Unexpected error: {}", e.getMessage(), e);
        throw new IOException("Unexpected error: " + e.getMessage(), e);
      }
    }

    if (iteration > maxIterations) {
      log.warn("Reached max iterations ({})", maxIterations);
      throw new IOException(
          "Reached max iterations (" + maxIterations + "), stopping to prevent infinite loop.");
    }
  }

  /**
   * Handle IOException with specific error types.
   *
   * @param e the IOException
   * @throws IOException re-thrown with specific message
   */
  private void handleIOException(IOException e) throws IOException {
    String errorMessage = e.getMessage();

    // Check for authentication errors (non-retryable)
    if (errorMessage != null
        && (errorMessage.contains("401")
            || errorMessage.contains("403")
            || errorMessage.contains("auth"))) {
      log.error("Authentication failed: {}", errorMessage);
      throw new IOException("Authentication failed: " + errorMessage + ". Check your API key.", e);
    }

    // Timeout or connection errors
    if (errorMessage != null
        && (errorMessage.contains("timeout") || errorMessage.contains("Timeout"))) {
      log.error("Request timeout: {}", errorMessage);
      throw new IOException("Request timeout. Please try again.", e);
    }

    // Other IO errors
    log.error("Request failed: {}", errorMessage, e);
    throw new IOException("Request failed: " + errorMessage, e);
  }

  /**
   * Trim message history to prevent unbounded growth. Keeps system prompt (first message) and
   * recent messages up to maxMessages.
   *
   * @param messages the messages array to trim
   */
  public void trimMessages(ArrayNode messages) {
    while (messages.size() > maxMessages && messages.size() > 1) {
      // Remove oldest non-system message (index 1, since index 0 is system)
      messages.remove(1);
    }
  }

  /**
   * Set the memory manager.
   *
   * @param memoryManager the memory manager
   */
  public void setMemoryManager(MemoryManager memoryManager) {
    this.memoryManager = memoryManager;
  }
}
