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

package io.github.jiajunbernoulli.arthasclaw.domain.task;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.jiajunbernoulli.arthasclaw.infrastructure.mcp.McpClient;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * TaskExecutor handles the execution of asynchronous tasks, especially long-running ones.
 */
public class TaskExecutor {

  private static final Logger log = LoggerFactory.getLogger(TaskExecutor.class);
  private final McpClient mcpClient;
  private final TaskManager taskManager;
  private final ObjectMapper mapper = new ObjectMapper();

  /**
   * Create TaskExecutor with MCP client and TaskManager.
   *
   * @param mcpClient MCP client
   * @param taskManager task manager for executing tasks
   */
  public TaskExecutor(McpClient mcpClient, TaskManager taskManager) {
    this.mcpClient = mcpClient;
    this.taskManager = taskManager;
  }

  /**
   * Execute a method watch task.
   *
   * @param task task to execute
   * @param className class name
   * @param methodName method name
   * @param watchCount number of times to watch
   * @param intervalMs interval between watches
   */
  public void executeMethodWatchTask(Task task, String className, String methodName, 
      int watchCount, long intervalMs) {
    taskManager.startTask(task, () -> {
      try {
        StringBuilder result = new StringBuilder();
        result.append("Method watch task started\n");
        result.append("Watching: " + className + "." + methodName 
            + " for " + watchCount + " times\n\n");

        for (int i = 1; i <= watchCount; i++) {
          if (Thread.currentThread().isInterrupted()) {
            throw new InterruptedException("Task cancelled");
          }

          result.append("--- Watch #" + i + " ---");
          
          // Call Arthas watch tool
          ObjectNode args = mapper.createObjectNode();
          args.put("classPattern", className);
          args.put("methodPattern", methodName);
          args.put("express", "{target, returnObj, args}");
          args.put("condition", "");
          args.put("b", false); // before
          args.put("e", false); // exception
          args.put("s", true); // success
          args.put("n", 1); // number of times

          JsonNode mcpResult = mcpClient.callTool("watch", args)
              .get(30, TimeUnit.SECONDS);

          // Extract result
          if (mcpResult.has("content") && mcpResult.get("content").isArray()) {
            for (JsonNode content : mcpResult.get("content")) {
              if (content.has("type") && "text".equals(content.get("type").asText())) {
                result.append(content.get("text").asText()).append("\n");
              }
            }
          }

          if (i < watchCount) {
            result.append("\nWaiting " + (intervalMs / 1000) + " seconds for next watch...\n\n");
            Thread.sleep(intervalMs);
          }
        }

        result.append("\n--- Task completed ---");
        task.setResult(result.toString());
      } catch (Exception e) {
        if (e instanceof InterruptedException) {
          // Let TaskManager handle it
          Thread.currentThread().interrupt();
        } else {
          task.setErrorMessage("Task failed: " + e.getMessage());
          log.error("Method watch task failed", e);
        }
      }
    });
  }

  /**
   * Execute a custom long-running task.
   *
   * @param task task to execute
   * @param taskLogic custom task logic
   */
  public void executeCustomTask(Task task, Runnable taskLogic) {
    taskManager.startTask(task, taskLogic);
  }
}
