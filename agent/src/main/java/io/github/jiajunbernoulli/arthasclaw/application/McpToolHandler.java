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
import io.github.jiajunbernoulli.arthasclaw.infrastructure.config.Config;
import io.github.jiajunbernoulli.arthasclaw.infrastructure.mcp.McpClient;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * McpToolHandler handles MCP tool discovery, configuration, and management.
 *
 * <p>This class is responsible for:
 * <ul>
 *   <li>Fetching tools from MCP server</li>
 *   <li>Converting MCP tools to OpenAI-compatible format</li>
 * </ul>
 */
public class McpToolHandler {
  private static final Logger log = LoggerFactory.getLogger(McpToolHandler.class);

  private final McpClient mcpClient;
  private final ObjectMapper mapper;
  private final int maxRetries;
  private final long listToolsTimeoutSeconds;
  private final long retryDelayMs;

  /**
   * Create McpToolHandler with dependencies.
   *
   * @param mcpClient MCP client
   * @param mapper JSON object mapper
   * @param config configuration
   */
  public McpToolHandler(McpClient mcpClient, ObjectMapper mapper, Config config) {
    this.mcpClient = mcpClient;
    this.mapper = mapper;
    Config.AgentConfig agentConfig = config.getAgent();
    this.maxRetries = agentConfig.getMaxRetries();
    this.listToolsTimeoutSeconds = agentConfig.getListToolsTimeoutSeconds();
    this.retryDelayMs = agentConfig.getRetryDelayMs();
  }

  /**
   * Fetch tools from MCP server.
   *
   * @return array of tools in OpenAI-compatible format
   */
  public ArrayNode fetchTools() {
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
            ObjectNode aiTool = convertToOpenAiFormat(tool);
            openAiTools.add(aiTool);
          }
        }
        log.info("Successfully loaded {} tools from Arthas", openAiTools.size());
        System.out.println(String.format("[+] Loaded %d tools from Arthas.", openAiTools.size()));

        return openAiTools;
      } catch (java.util.concurrent.TimeoutException e) {
        lastException = e;
        log.warn("Attempt {}/{}: Timeout fetching tools", attempt, maxRetries);
        System.err.println(
            "[-] Attempt " + attempt + "/" + maxRetries + ": " + "Timeout fetching tools");
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

    log.error(
        "Failed to fetch tools after {} attempts: {}",
        maxRetries,
        lastException != null ? lastException.getMessage() : "unknown error");
    System.err.println(
        "[-] Failed to fetch tools after "
            + maxRetries
            + " attempts: "
            + (lastException != null ? lastException.getMessage() : "unknown error"));
    return mapper.createArrayNode();
  }

  /**
   * Convert MCP tool format to OpenAI-compatible format.
   *
   * @param tool MCP tool node
   * @return OpenAI-compatible tool node
   */
  private ObjectNode convertToOpenAiFormat(JsonNode tool) {
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
    return aiTool;
  }
}
