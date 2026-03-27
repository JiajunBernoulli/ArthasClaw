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
package io.github.jiajunbernoulli.arthasclaw.cli;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import io.github.jiajunbernoulli.arthasclaw.config.Config;
import io.github.jiajunbernoulli.arthasclaw.skill.Skill;

import java.util.List;

/**
 * Helper class for TUI display formatting.
 * Contains ANSI color codes and display methods.
 */
public class DisplayHelper {

    // ANSI color codes
    public static final String RESET = "\u001B[0m";
    public static final String GREEN = "\u001B[32m";
    public static final String YELLOW = "\u001B[33m";
    public static final String BLUE = "\u001B[34m";
    public static final String CYAN = "\u001B[36m";
    public static final String RED = "\u001B[31m";

    // Directory paths
    public static final String HOME_DIR = System.getProperty("user.home");
    public static final String ARTHASCLAW_DIR = HOME_DIR + "/.arthasclaw";

    private DisplayHelper() {
        // Utility class
    }

    /**
     * Print welcome banner.
     */
    public static void printWelcome() {
        System.out.println();
        System.out.println(GREEN + "╔══════════════════════════════════════════════════════════╗" + RESET);
        System.out.println(GREEN + "║          🦞 ArthasClaw TUI - Java Diagnostic Tool         ║" + RESET);
        System.out.println(GREEN + "╚══════════════════════════════════════════════════════════╝" + RESET);
        System.out.println();
        System.out.println("Command modes:");
        System.out.println("  " + YELLOW + "<natural lang>" + RESET + "  - AI-powered diagnosis (default)");
        System.out.println("  " + YELLOW + "!<command>" + RESET + "     - Execute shell command (e.g., !ls -la)");
        System.out.println("  " + YELLOW + "$<command>" + RESET + "     - Execute Arthas command (e.g., $thread)");
        System.out.println("  " + YELLOW + "/<command>" + RESET + "     - System commands (e.g., /help, /quit, /skill)");
        System.out.println();
        System.out.println("Config: ~/.arthasclaw/config.yaml");
        System.out.println();
    }

    /**
     * Print help message.
     */
    public static void printHelp() {
        System.out.println();
        System.out.println(CYAN + "══════════════════════════════════════════════════════════" + RESET);
        System.out.println(CYAN + "                      Help                               " + RESET);
        System.out.println(CYAN + "══════════════════════════════════════════════════════════" + RESET);
        System.out.println();
        System.out.println("[System Commands] /<command>");
        System.out.println("  /help, /h, /?     Show this help");
        System.out.println("  /quit, /exit, /q  Exit the program");
        System.out.println("  /clear            Clear conversation history");
        System.out.println("  /tools            List available tools");
        System.out.println("  /history          Show conversation history");
        System.out.println("  /config           Show current configuration");
        System.out.println("  /version          Show version info");
        System.out.println();
        System.out.println("[Skill Commands] /skill <subcommand>");
        System.out.println("  /skill install <url|path>  Install a skill from URL or local file");
        System.out.println("  /skill list               List installed skills");
        System.out.println("  /skill show <name>        Show skill details");
        System.out.println("  /skill remove <name>      Remove a skill");
        System.out.println();
        System.out.println("[Shell Commands] !<command>");
        System.out.println("  !ls -la           List files in current directory");
        System.out.println("  !ps aux | grep java  Find Java processes");
        System.out.println("  !jstat -gc <pid>  View GC statistics");
        System.out.println();
        System.out.println("[Arthas Commands] $<command>");
        System.out.println("  $thread           View thread info");
        System.out.println("  $dashboard        View dashboard");
        System.out.println("  $jad <class>      Decompile class");
        System.out.println("  $watch <class> <method>  Watch method calls");
        System.out.println();
        System.out.println("[Natural Language] Just type your question");
        System.out.println("  What methods does MathGame have?");
        System.out.println("  Check for thread deadlock");
        System.out.println("  Analyze memory usage");
        System.out.println();
        System.out.println("[Configuration] Edit ~/.arthasclaw/config.yaml");
        System.out.println("  agent.max_iterations     - Max agent loop iterations");
        System.out.println("  agent.max_messages       - Max messages in history");
        System.out.println("  agent.max_tool_result_length - Truncate tool results");
        System.out.println("  llm.temperature          - LLM temperature (0.0-2.0)");
        System.out.println("  llm.max_tokens           - Max response tokens");
        System.out.println("  llm.top_p                - Nucleus sampling (0.0-1.0)");
        System.out.println();
    }

    /**
     * Print current configuration.
     */
    public static void printConfig(Config config) {
        System.out.println();
        System.out.println(CYAN + "Current Configuration:" + RESET);
        System.out.println();
        System.out.println("[Agent Settings]");
        System.out.println("  max_iterations:         " + config.getAgent().getMaxIterations());
        System.out.println("  max_messages:           " + config.getAgent().getMaxMessages());
        System.out.println("  max_retries:            " + config.getAgent().getMaxRetries());
        System.out.println("  max_tool_result_length: " + config.getAgent().getMaxToolResultLength());
        System.out.println("  list_tools_timeout:     " + config.getAgent().getListToolsTimeoutSeconds() + "s");
        System.out.println("  tool_call_timeout:      " + config.getAgent().getToolCallTimeoutSeconds() + "s");
        System.out.println("  retry_delay:            " + config.getAgent().getRetryDelayMs() + "ms");
        System.out.println();
        System.out.println("[LLM Settings]");
        System.out.println("  base_url:     " + config.getEffectiveBaseUrl());
        System.out.println("  model:        " + config.getEffectiveModel());
        System.out.println("  timeout:      " + config.getLlm().getTimeoutSeconds() + "s");
        System.out.println("  temperature:  " + config.getLlm().getTemperature());
        System.out.println("  max_tokens:   " + config.getLlm().getMaxTokens());
        System.out.println("  top_p:        " + config.getLlm().getTopP());
        System.out.println();
        System.out.println("[MCP Settings]");
        System.out.println("  port:                  " + config.getMcp().getPort());
        System.out.println("  endpoint:              " + config.getMcp().getEndpoint());
        System.out.println("  arthas_version:        " + config.getMcp().getArthasVersion());
        System.out.println("  connect_timeout:       " + config.getMcp().getConnectTimeoutSeconds() + "s");
        System.out.println("  initialize_timeout:    " + config.getMcp().getInitializeTimeoutSeconds() + "s");
        System.out.println();
        System.out.println("Config file: " + ARTHASCLAW_DIR + "/config.yaml");
        System.out.println();
    }

    /**
     * Print conversation history.
     */
    public static void printHistory(ArrayNode messages) {
        System.out.println();
        System.out.println(CYAN + "Conversation history (" + (messages.size() - 1) + " messages):" + RESET);
        System.out.println();
        for (int i = 1; i < messages.size(); i++) {
            JsonNode msg = messages.get(i);
            String role = msg.get("role").asText();
            String content = msg.has("content") ? msg.get("content").asText() : "[tool call]";

            // Truncate long content
            if (content.length() > 100) {
                content = content.substring(0, 97) + "...";
            }

            String roleIcon = "user".equals(role) ? "👤" : "assistant".equals(role) ? "🤖" : "🔧";
            System.out.println("  " + roleIcon + " " + role + ": " + content);
        }
        System.out.println();
    }

    /**
     * Print available tools.
     */
    public static void printTools(ArrayNode toolsConfig) {
        if (toolsConfig == null || toolsConfig.size() == 0) {
            System.out.println(YELLOW + "[!] No tools loaded" + RESET);
            return;
        }

        System.out.println();
        System.out.println(CYAN + "Available Arthas tools (" + toolsConfig.size() + "):" + RESET);
        System.out.println();

        int count = 0;
        for (JsonNode tool : toolsConfig) {
            if (tool.has("function")) {
                JsonNode func = tool.get("function");
                String name = func.get("name").asText();
                String desc = func.has("description") ? func.get("description").asText() : "";
                // Truncate long descriptions
                if (desc.length() > 50) {
                    desc = desc.substring(0, 47) + "...";
                }
                System.out.printf("  %-20s %s%n", YELLOW + name + RESET, desc);
                count++;
                if (count % 5 == 0 && count < toolsConfig.size()) {
                    System.out.println();
                }
            }
        }
        System.out.println();
    }

    /**
     * Print installed skills.
     */
    public static void printSkills(List<Skill> skills) {
        if (skills.isEmpty()) {
            System.out.println(YELLOW + "[!] No skills installed" + RESET);
            System.out.println("    Use /skill install <url|path> to install a skill");
            return;
        }

        System.out.println();
        System.out.println(CYAN + "Installed Skills (" + skills.size() + "):" + RESET);
        System.out.println();
        for (Skill skill : skills) {
            System.out.println(skill.getSummary());
        }
        System.out.println();
    }

    /**
     * Print skill details.
     */
    public static void printSkillDetails(Skill skill) {
        System.out.println();
        System.out.println(CYAN + "Skill Details:" + RESET);
        System.out.println(skill.getDetails());
    }

    /**
     * Print error message.
     */
    public static void printError(String message) {
        System.out.println(RED + message + RESET);
    }

    /**
     * Print success message.
     */
    public static void printSuccess(String message) {
        System.out.println(GREEN + message + RESET);
    }

    /**
     * Print info message.
     */
    public static void printInfo(String message) {
        System.out.println(BLUE + message + RESET);
    }

    /**
     * Print warning message.
     */
    public static void printWarning(String message) {
        System.out.println(YELLOW + message + RESET);
    }
}
