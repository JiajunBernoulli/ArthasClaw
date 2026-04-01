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
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.jiajunbernoulli.arthasclaw.domain.task.Task;
import io.github.jiajunbernoulli.arthasclaw.domain.task.TaskExecutor;
import io.github.jiajunbernoulli.arthasclaw.domain.task.TaskManager;
import io.github.jiajunbernoulli.arthasclaw.infrastructure.mcp.McpClient;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * TaskCommandHandler handles all task-related commands and operations.
 * 
 * <p>This class provides shared functionality for:
 * <ul>
 *   <li>Listing tasks</li>
 *   <li>Cancelling tasks</li>
 *   <li>Creating async tasks via tool calls</li>
 * </ul>
 */
public class TaskCommandHandler {
  private static final Logger log = LoggerFactory.getLogger(TaskCommandHandler.class);
  
  private static final boolean SUPPORTS_COLOR = detectColorSupport();
  
  private final TaskManager taskManager;
  private final TaskExecutor taskExecutor;
  private final McpClient mcpClient;
  private final ObjectMapper mapper;

  /**
   * Create TaskCommandHandler with dependencies.
   *
   * @param taskManager task manager
   * @param taskExecutor task executor
   * @param mcpClient MCP client
   * @param mapper JSON object mapper
   */
  public TaskCommandHandler(
      TaskManager taskManager, 
      TaskExecutor taskExecutor, 
      McpClient mcpClient, 
      ObjectMapper mapper) {
    this.taskManager = taskManager;
    this.taskExecutor = taskExecutor;
    this.mcpClient = mcpClient;
    this.mapper = mapper;
  }

  /**
   * List all tasks.
   */
  public void listTasks() {
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
   * @return true if task was cancelled
   */
  public boolean cancelTask(String taskId) {
    if (taskId == null || taskId.isEmpty()) {
      System.out.println("❌ Usage: /stop <taskId>");
      return false;
    }

    boolean cancelled = taskManager.cancelTask(taskId);
    if (cancelled) {
      System.out.println("✅ Task cancelled: " + taskId);
    } else {
      System.out.println("❌ Task not found or cannot be cancelled: " + taskId);
    }
    return cancelled;
  }

  /**
   * Handle create_async_task tool call.
   *
   * @param arguments tool arguments
   * @return result message
   */
  public String handleCreateAsyncTask(ObjectNode arguments) {
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

      Task task = taskManager.createTask(description);

      if ("watch_method".equals(taskType)) {
        if (classPattern == null || methodPattern == null) {
          return "Error: class_pattern and method_pattern are required for watch_method task";
        }

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
  private void executeWatchMethodTask(
      final Task task, 
      final String classPattern, 
      final String methodPattern, 
      final int count, 
      final long intervalMs, 
      final String express) {
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
   * Detect if the terminal supports ANSI color codes.
   *
   * @return true if colors are supported
   */
  private static boolean detectColorSupport() {
    if (System.console() == null) {
      return false;
    }
    String osName = System.getProperty("os.name", "").toLowerCase();
    if (osName.contains("windows")) {
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
}
