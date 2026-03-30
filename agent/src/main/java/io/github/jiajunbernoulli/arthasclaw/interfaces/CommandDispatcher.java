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

package io.github.jiajunbernoulli.arthasclaw.interfaces;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.jiajunbernoulli.arthasclaw.application.LoopAgent;
import io.github.jiajunbernoulli.arthasclaw.domain.skill.Skill;
import io.github.jiajunbernoulli.arthasclaw.domain.skill.SkillManager;
import io.github.jiajunbernoulli.arthasclaw.domain.task.Task;
import io.github.jiajunbernoulli.arthasclaw.domain.task.TaskManager;
import io.github.jiajunbernoulli.arthasclaw.infrastructure.config.Config;
import io.github.jiajunbernoulli.arthasclaw.infrastructure.mcp.McpClient;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.util.Optional;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Dispatches and handles user commands in the TUI.
 * Supports four command modes:
 * - Default: Natural language query (handled by AI)
 * - ! prefix: Execute shell command
 * - $ prefix: Execute Arthas command via MCP
 * - / prefix: System commands (quit, help, skill, etc.)
 */
public class CommandDispatcher {

  private final LoopAgent loopAgent;
  private final McpClient mcpClient;
  private final SkillManager skillManager;
  private final Config config;
  private final ObjectMapper mapper;
  private ArrayNode toolsConfig;
  private boolean running = true;

  /**
   * Constructs a CommandDispatcher with the given dependencies.
   *
   * @param loopAgent the loop agent for processing natural language queries
   * @param mcpClient the MCP client for executing Arthas commands
   * @param skillManager the skill manager for managing skills
   * @param config the configuration for the agent
   * @param mapper the JSON object mapper
   */
  public CommandDispatcher(LoopAgent loopAgent, McpClient mcpClient, SkillManager skillManager, 
                           Config config, ObjectMapper mapper) {
    this.loopAgent = loopAgent;
    this.mcpClient = mcpClient;
    this.skillManager = skillManager;
    this.config = config;
    this.mapper = mapper;
  }

  /**
   * Check if the dispatcher is still running.
   */
  public boolean isRunning() {
    return running;
  }

  /**
   * Process a user command.
   */
  public void processCommand(String input) {
    if (input.startsWith("/")) {
      handleSystemCommand(input.substring(1));
    } else if (input.startsWith("!")) {
      handleShellCommand(input.substring(1));
    } else if (input.startsWith("$")) {
      handleArthasCommand(input.substring(1));
    } else {
      handleNaturalLanguage(input);
    }
  }

  /**
   * Set tools configuration.
   */
  public void setToolsConfig(ArrayNode toolsConfig) {
    this.toolsConfig = toolsConfig;
  }

  /**
   * Get tools configuration.
   */
  public ArrayNode getToolsConfig() {
    return toolsConfig;
  }

  // ==================== System Commands ====================

  private void handleSystemCommand(String cmd) {
    String[] parts = cmd.split("\\s+", 2);
    String command = parts[0].toLowerCase();
    String args = parts.length > 1 ? parts[1] : "";

    switch (command) {
      case "quit":
      case "exit":
      case "q":
        DisplayHelper.printInfo("[*] Goodbye!");
        running = false;
        break;

      case "help":
      case "h":
      case "?":
        DisplayHelper.printHelp();
        break;

      case "clear":
        loopAgent.clearMessages();
        DisplayHelper.printInfo("[*] Conversation history cleared");
        break;

      case "tools":
        DisplayHelper.printTools(toolsConfig);
        break;

      case "history":
        DisplayHelper.printHistory(loopAgent.getMessages());
        break;

      case "config":
        DisplayHelper.printConfig(config);
        break;

      case "version":
        System.out.println("ArthasClaw TUI v1.0.0");
        break;

      case "skill":
        handleSkillCommand(args);
        break;

      case "tasks":
        listTasks();
        break;

      case "stop":
        cancelTask(args.trim());
        break;

      default:
        DisplayHelper.printError("[-] Unknown system command: /" + command);
        DisplayHelper.printWarning("    Type /help to see available commands");
    }
  }

  // ==================== Task Commands ====================

  private void listTasks() {
    TaskManager taskManager = loopAgent.getTaskManager();
    if (taskManager.getTaskCount() == 0) {
      System.out.println("📋 No tasks found");
      return;
    }

    System.out.println("📋 Task List:");
    System.out.println("-----------------------------------------------------------------------");
    System.out.println("Task ID      | Description              | Status    | Updated At");
    System.out.println("-----------------------------------------------------------------------");

    for (Task task : taskManager.getAllTasks()) {
      String status = task.getStatus().toString();
      String updatedAt = task.getUpdatedAt().toString().substring(0, 19);
      String description = task.getDescription();
      if (description.length() > 25) {
        description = description.substring(0, 22) + "...";
      }
      System.out.printf("%-12s | %-25s | %-9s | %s%n",
          task.getId(), description, status, updatedAt);
    }

    System.out.println("-----------------------------------------------------------------------");
  }

  private void cancelTask(String taskId) {
    if (taskId.isEmpty()) {
      DisplayHelper.printError("[-] Usage: /stop <taskId>");
      return;
    }

    TaskManager taskManager = loopAgent.getTaskManager();
    boolean cancelled = taskManager.cancelTask(taskId);
    if (cancelled) {
      DisplayHelper.printSuccess("[+] Task cancelled: " + taskId);
    } else {
      DisplayHelper.printError("[-] Task not found or cannot be cancelled: " + taskId);
    }
  }

  // ==================== Skill Commands ====================

  private void handleSkillCommand(String args) {
    if (args.isEmpty()) {
      DisplayHelper.printError("[-] Usage: /skill <install|list|show|remove> [args]");
      return;
    }

    String[] parts = args.split("\\s+", 2);
    String subCommand = parts[0].toLowerCase();
    String subArgs = parts.length > 1 ? parts[1] : "";

    switch (subCommand) {
      case "install":
        installSkill(subArgs);
        break;

      case "list":
      case "ls":
        listSkills();
        break;

      case "show":
        showSkill(subArgs);
        break;

      case "remove":
      case "rm":
        removeSkill(subArgs);
        break;

      default:
        DisplayHelper.printError("[-] Unknown skill command: " + subCommand);
        DisplayHelper.printWarning("    Available: install, list, show, remove");
    }
  }

  private void installSkill(String source) {
    if (source.isEmpty()) {
      DisplayHelper.printError("[-] Usage: /skill install <url|path>");
      return;
    }

    DisplayHelper.printWarning("[*] Installing skill from: " + source);
    try {
      Skill skill = skillManager.install(source);
      DisplayHelper.printSuccess("[+] Skill installed: " + skill.getName()
          + (skill.getVersion() != null ? " v" + skill.getVersion() : ""));
      if (skill.getDescription() != null) {
        System.out.println("    Description: " + skill.getDescription());
      }
      updateLoopAgentSkills();
    } catch (Exception e) {
      DisplayHelper.printError("[-] Failed to install skill: " + e.getMessage());
    }
  }

  private void listSkills() {
    List<Skill> skills = skillManager.listAll();
    DisplayHelper.printSkills(skills);
  }

  private void showSkill(String name) {
    if (name.isEmpty()) {
      DisplayHelper.printError("[-] Usage: /skill show <name>");
      return;
    }

    Optional<Skill> skill = skillManager.get(name);
    if (skill.isPresent()) {
      DisplayHelper.printSkillDetails(skill.get());
    } else {
      DisplayHelper.printError("[-] Skill not found: " + name);
    }
  }

  private void removeSkill(String name) {
    if (name.isEmpty()) {
      DisplayHelper.printError("[-] Usage: /skill remove <name>");
      return;
    }

    try {
      if (skillManager.remove(name)) {
        DisplayHelper.printSuccess("[+] Skill removed: " + name);
        updateLoopAgentSkills();
      } else {
        DisplayHelper.printError("[-] Skill not found: " + name);
      }
    } catch (Exception e) {
      DisplayHelper.printError("[-] Failed to remove skill: " + e.getMessage());
    }
  }

  private void updateLoopAgentSkills() {
    String combinedPrompt = skillManager.getCombinedPrompt();
    loopAgent.setSkillsPrompt(combinedPrompt);
  }

  // ==================== Shell Commands ====================

  private void handleShellCommand(String cmd) {
    if (cmd.isEmpty()) {
      DisplayHelper.printError("[-] Please enter a shell command to execute");
      return;
    }

    DisplayHelper.printWarning("[Shell] Executing: " + cmd);
    System.out.println();

    try {
      ProcessBuilder pb = new ProcessBuilder("bash", "-c", cmd);
      pb.redirectErrorStream(true);
      Process process = pb.start();

      BufferedReader reader = new BufferedReader(
          new InputStreamReader(process.getInputStream()));
      String line;
      while ((line = reader.readLine()) != null) {
        System.out.println("  " + line);
      }

      int exitCode = process.waitFor();
      System.out.println();
      if (exitCode == 0) {
        DisplayHelper.printSuccess("[+] Command completed (exit code: " + exitCode + ")");
      } else {
        DisplayHelper.printError("[-] Command failed (exit code: " + exitCode + ")");
      }

    } catch (Exception e) {
      DisplayHelper.printError("[-] Execution failed: " + e.getMessage());
    }
  }

  // ==================== Arthas Commands ====================

  private void handleArthasCommand(String cmd) {
    if (cmd.isEmpty()) {
      DisplayHelper.printError("[-] Please enter an Arthas command to execute");
      return;
    }

    DisplayHelper.printWarning("[Arthas] Executing: " + cmd);
    System.out.println();

    try {
      // Parse command and arguments
      String[] parts = cmd.split("\\s+", 2);
      String command = parts[0];
      String args = parts.length > 1 ? parts[1] : "";

      // Build MCP request
      ObjectNode arguments = mapper.createObjectNode();
      arguments.put("command", command);
      if (!args.isEmpty()) {
        // Parse args into individual parameters
        String[] argParts = args.split("\\s+");
        for (int i = 0; i < argParts.length; i++) {
          arguments.put("arg" + i, argParts[i]);
        }
      }

      // Try to call via MCP - use the command as tool name
      JsonNode mcpResult = mcpClient.callTool(command, arguments).get(
          config.getAgent().getToolCallTimeoutSeconds(), TimeUnit.SECONDS);

      // Extract and print result
      String resultStr = extractMcpResult(mcpResult);
      System.out.println(resultStr);
      System.out.println();
      DisplayHelper.printSuccess("[+] Arthas command completed");

    } catch (Exception e) {
      // If direct tool call fails, try using AI to interpret
      DisplayHelper.printWarning("[-] Direct execution failed, trying via AI...");
      handleNaturalLanguage("Execute Arthas command: " + cmd);
    }
  }

  private String extractMcpResult(JsonNode mcpResult) {
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
    String result = sb.toString().trim();
    return result.isEmpty() ? "Success (No output)" : result;
  }

  // ==================== Natural Language ====================

  private void handleNaturalLanguage(String input) {
    if (input.isEmpty()) {
      return;
    }
    // Delegate to LoopAgent for processing
    loopAgent.processQuery(input);
  }
}
