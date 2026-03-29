/*
 * Copyright © 2026 Jiajun Bernoulli
 * (jiajunbernoulli@users.noreply.github.com)
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
import io.github.jiajunbernoulli.arthasclaw.infrastructure.config.Config;
import io.github.jiajunbernoulli.arthasclaw.infrastructure.mcp.McpClient;
import java.io.BufferedReader;
import java.io.InputStreamReader;
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

    /** Agent loop for processing queries. */
    private final LoopAgent loopAgent;

    /** MCP client for tool execution. */
    private final McpClient mcpClient;

    /** Skill manager for handling skills. */
    private final SkillManager skillManager;

    /** Application configuration. */
    private final Config config;

    /** JSON object mapper. */
    private final ObjectMapper mapper;

    /** Tools configuration from MCP. */
    private ArrayNode toolsConfig;

    /** Whether the dispatcher is still running. */
    private boolean running = true;

    /**
     * Create a new CommandDispatcher.
     *
     * @param agent        the loop agent
     * @param client       the MCP client
     * @param skills       the skill manager
     * @param cfg          the configuration
     * @param objectMapper the JSON mapper
     */
    public CommandDispatcher(
            final LoopAgent agent,
            final McpClient client,
            final SkillManager skills,
            final Config cfg,
            final ObjectMapper objectMapper) {
        this.loopAgent = agent;
        this.mcpClient = client;
        this.skillManager = skills;
        this.config = cfg;
        this.mapper = objectMapper;
    }

    /**
     * Check if the dispatcher is still running.
     *
     * @return true if running
     */
    public boolean isRunning() {
        return running;
    }

    /**
     * Process a user command.
     *
     * @param input the user input
     */
    public void processCommand(final String input) {
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
     *
     * @param newToolsConfig the tools configuration array
     */
    public void setToolsConfig(final ArrayNode newToolsConfig) {
        this.toolsConfig = newToolsConfig;
    }

    /**
     * Get tools configuration.
     *
     * @return the tools configuration array
     */
    public ArrayNode getToolsConfig() {
        return toolsConfig;
    }

    // ==================== System Commands ====================

    /**
     * Handle system commands starting with /.
     *
     * @param cmd the command string (without /)
     */
    private void handleSystemCommand(final String cmd) {
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

            default:
                DisplayHelper.printError(
                        "[-] Unknown system command: /" + command);
                DisplayHelper.printWarning(
                        "    Type /help to see available commands");
        }
    }

    // ==================== Skill Commands ====================

    /**
     * Handle skill subcommands.
     *
     * @param args the skill subcommand and arguments
     */
    private void handleSkillCommand(final String args) {
        if (args.isEmpty()) {
            DisplayHelper.printError(
                    "[-] Usage: /skill <install|list|show|remove> [args]");
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
                DisplayHelper.printError(
                        "[-] Unknown skill command: " + subCommand);
                DisplayHelper.printWarning(
                        "    Available: install, list, show, remove");
        }
    }

    /**
     * Install a skill from source.
     *
     * @param source the source URL or path
     */
    private void installSkill(final String source) {
        if (source.isEmpty()) {
            DisplayHelper.printError("[-] Usage: /skill install <url|path>");
            return;
        }

        DisplayHelper.printWarning("[*] Installing skill from: " + source);
        try {
            Skill skill = skillManager.install(source);
            String versionInfo = skill.getVersion() != null
                    ? " v" + skill.getVersion()
                    : "";
            DisplayHelper.printSuccess(
                    "[+] Skill installed: " + skill.getName() + versionInfo);
            if (skill.getDescription() != null) {
                System.out.println(
                        "    Description: " + skill.getDescription());
            }
            updateLoopAgentSkills();
        } catch (Exception e) {
            DisplayHelper.printError(
                    "[-] Failed to install skill: " + e.getMessage());
        }
    }

    /**
     * List all installed skills.
     */
    private void listSkills() {
        List<Skill> skills = skillManager.listAll();
        DisplayHelper.printSkills(skills);
    }

    /**
     * Show details of a skill.
     *
     * @param name the skill name
     */
    private void showSkill(final String name) {
        if (name.isEmpty()) {
            DisplayHelper.printError("[-] Usage: /skill show <name>");
            return;
        }

        skillManager.get(name).ifPresentOrElse(
                DisplayHelper::printSkillDetails,
                () -> DisplayHelper.printError(
                        "[-] Skill not found: " + name)
        );
    }

    /**
     * Remove a skill.
     *
     * @param name the skill name
     */
    private void removeSkill(final String name) {
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
            DisplayHelper.printError(
                    "[-] Failed to remove skill: " + e.getMessage());
        }
    }

    /**
     * Update LoopAgent with current skills.
     */
    private void updateLoopAgentSkills() {
        String combinedPrompt = skillManager.getCombinedPrompt();
        loopAgent.setSkillsPrompt(combinedPrompt);
    }

    // ==================== Shell Commands ====================

    /**
     * Handle shell commands starting with !.
     *
     * @param cmd the shell command
     */
    private void handleShellCommand(final String cmd) {
        if (cmd.isEmpty()) {
            DisplayHelper.printError(
                    "[-] Please enter a shell command to execute");
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
                DisplayHelper.printSuccess(
                        "[+] Command completed (exit code: " + exitCode + ")");
            } else {
                DisplayHelper.printError(
                        "[-] Command failed (exit code: " + exitCode + ")");
            }

        } catch (Exception e) {
            DisplayHelper.printError(
                    "[-] Execution failed: " + e.getMessage());
        }
    }

    // ==================== Arthas Commands ====================

    /**
     * Handle Arthas commands starting with $.
     *
     * @param cmd the Arthas command
     */
    private void handleArthasCommand(final String cmd) {
        if (cmd.isEmpty()) {
            DisplayHelper.printError(
                    "[-] Please enter an Arthas command to execute");
            return;
        }

        DisplayHelper.printWarning("[Arthas] Executing: " + cmd);
        System.out.println();

        try {
            String[] parts = cmd.split("\\s+", 2);
            String command = parts[0];
            String args = parts.length > 1 ? parts[1] : "";

            ObjectNode arguments = mapper.createObjectNode();
            arguments.put("command", command);
            if (!args.isEmpty()) {
                String[] argParts = args.split("\\s+");
                for (int i = 0; i < argParts.length; i++) {
                    arguments.put("arg" + i, argParts[i]);
                }
            }

            JsonNode mcpResult = mcpClient.callTool(command, arguments)
                    .get(config.getAgent().getToolCallTimeoutSeconds(),
                            TimeUnit.SECONDS);

            String resultStr = extractMcpResult(mcpResult);
            System.out.println(resultStr);
            System.out.println();
            DisplayHelper.printSuccess("[+] Arthas command completed");

        } catch (Exception e) {
            DisplayHelper.printWarning(
                    "[-] Direct execution failed, trying via AI...");
            handleNaturalLanguage("Execute Arthas command: " + cmd);
        }
    }

    /**
     * Extract text result from MCP response.
     *
     * @param mcpResult the MCP result node
     * @return extracted text
     */
    private String extractMcpResult(final JsonNode mcpResult) {
        StringBuilder sb = new StringBuilder();
        if (mcpResult.has("content")
                && mcpResult.get("content").isArray()) {
            for (JsonNode content : mcpResult.get("content")) {
                if (content.has("type")
                        && "text".equals(content.get("type").asText())) {
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

    /**
     * Handle natural language input.
     *
     * @param input the user input
     */
    private void handleNaturalLanguage(final String input) {
        if (input.isEmpty()) {
            return;
        }
        loopAgent.processQuery(input);
    }
}
