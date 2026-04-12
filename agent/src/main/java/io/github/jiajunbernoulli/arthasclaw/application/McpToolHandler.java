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
 *   <li>Adding built-in tools (e.g., create_async_task)</li>
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

        // Add built-in async task tool
        addBuiltInTools(openAiTools);

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
    function.put(
        "description",
        "Create an async task for long-running operations. "
            + "Use this when the user wants to monitor/collect data over time "
            + "(e.g., watch a method N times, collect samples). "
            + "The task runs in background, user can check status with /tasks "
            + "or cancel with /stop.");

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

    // get_task_result tool for retrieving async task results
    ObjectNode getTaskResultTool = mapper.createObjectNode();
    getTaskResultTool.put("type", "function");

    ObjectNode getTaskFunction = mapper.createObjectNode();
    getTaskFunction.put("name", "get_task_result");
    getTaskFunction.put(
        "description",
        "Get the status and result of an async task. "
            + "Use this to check if a task is completed and retrieve its result. "
            + "If the task is still running, call this again later.");

    ObjectNode getTaskParams = mapper.createObjectNode();
    getTaskParams.put("type", "object");

    ObjectNode getTaskProperties = mapper.createObjectNode();
    ObjectNode taskIdProp = mapper.createObjectNode();
    taskIdProp.put("type", "string");
    taskIdProp.put("description", "The ID of the task to query (e.g., 'task_abc123')");
    getTaskProperties.set("task_id", taskIdProp);

    getTaskParams.set("properties", getTaskProperties);

    ArrayNode getTaskRequired = mapper.createArrayNode();
    getTaskRequired.add("task_id");
    getTaskParams.set("required", getTaskRequired);

    getTaskFunction.set("parameters", getTaskParams);
    getTaskResultTool.set("function", getTaskFunction);

    toolsArray.add(getTaskResultTool);
  }
}
