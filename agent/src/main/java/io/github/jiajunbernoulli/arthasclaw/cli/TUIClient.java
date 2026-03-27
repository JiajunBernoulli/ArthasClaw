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
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.jiajunbernoulli.arthasclaw.config.Config;
import io.github.jiajunbernoulli.arthasclaw.controller.LoopAgent;
import io.github.jiajunbernoulli.arthasclaw.controller.providers.CompletionProvider;
import io.github.jiajunbernoulli.arthasclaw.controller.providers.OpenAICompletionProvider;
import io.github.jiajunbernoulli.arthasclaw.cli.bootstrap.BotArthas;
import io.github.jiajunbernoulli.arthasclaw.mcp.McpClient;
import io.github.jiajunbernoulli.arthasclaw.skill.Skill;
import io.github.jiajunbernoulli.arthasclaw.skill.SkillManager;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.concurrent.TimeUnit;

import org.jline.reader.LineReader;
import org.jline.reader.LineReaderBuilder;
import org.jline.terminal.Terminal;
import org.jline.terminal.TerminalBuilder;

/**
 * TUI (Text User Interface) client for ArthasClaw.
 * Supports four command modes:
 * - Default: Natural language query (handled by AI)
 * - ! prefix: Execute shell command
 * - $ prefix: Execute Arthas command via MCP
 * - / prefix: System commands (quit, help, skill, etc.)
 */
public class TUIClient {
    private final CompletionProvider provider;
    private final McpClient mcpClient;
    private final LoopAgent loopAgent;
    private final SkillManager skillManager;
    private final Config config;
    private final ObjectMapper mapper = new ObjectMapper();
    private ArrayNode toolsConfig;
    private boolean running = true;
    private final BufferedReader reader;
    private Terminal terminal;
    private LineReader lineReader;

    // ANSI color codes
    private static final String RESET = "\u001B[0m";
    private static final String GREEN = "\u001B[32m";
    private static final String YELLOW = "\u001B[33m";
    private static final String BLUE = "\u001B[34m";
    private static final String CYAN = "\u001B[36m";
    private static final String RED = "\u001B[31m";

    // Directory paths
    private static final String HOME_DIR = System.getProperty("user.home");
    private static final String ARTHASCLAW_DIR = HOME_DIR + "/.arthasclaw";
    private static final String SKILLS_DIR = ARTHASCLAW_DIR + "/skills";
    private static final String MEMORY_DIR = ARTHASCLAW_DIR + "/memory";
    private static final String WORKSPACE_DIR = ARTHASCLAW_DIR + "/workspace";
    private static final String LOGS_DIR = ARTHASCLAW_DIR + "/logs";

    /**
     * Initialize ArthasClaw directories in ~/.arthasclaw
     * Creates: skills, memory, workspace, logs
     */
    private static void initDirectories() {
        String[] dirs = {ARTHASCLAW_DIR, SKILLS_DIR, MEMORY_DIR, WORKSPACE_DIR, LOGS_DIR};
        String[] names = {"home", "skills", "memory", "workspace", "logs"};

        System.out.println("[*] Initializing ArthasClaw directories...");

        for (int i = 0; i < dirs.length; i++) {
            Path path = Paths.get(dirs[i]);
            if (!Files.exists(path)) {
                try {
                    Files.createDirectories(path);
                    System.out.println("[+] Created: ~/.arthasclaw/" + names[i]);
                } catch (IOException e) {
                    System.err.println("[-] Failed to create directory: " + dirs[i] + " - " + e.getMessage());
                }
            } else {
                System.out.println("[+] Found: ~/.arthasclaw/" + names[i]);
            }
        }
    }

    /**
     * Get API key from config or prompt user.
     *
     * @param config the configuration
     * @return API key string
     */
    private static String getApiKey(Config config) {
        // First try environment variable
        String apiKey = config.getEffectiveApiKey();
        if (apiKey != null && !apiKey.trim().isEmpty()) {
            return apiKey;
        }

        // Prompt user for input
        System.out.print("Enter OPENAI_API_KEY: ");
        java.io.Console console = System.console();

        if (console != null) {
            char[] chars = console.readPassword();
            return chars != null ? new String(chars) : null;
        }

        // No console available
        System.err.println();
        System.err.println("[-] Cannot read from terminal. Please set OPENAI_API_KEY environment variable");
        System.err.println("    Or add 'api_key' to ~/.arthasclaw/config.yaml");
        System.exit(1);
        return null;
    }

    /**
     * Main entry point for TUI client.
     * Attaches Arthas to target JVM and starts interactive session.
     */
    public static void main(String[] args) {
        if (args.length < 1) {
            System.err.println("Usage: java -jar bot-agent.jar <PID>");
            System.exit(1);
        }

        String pid = args[0];

        try {
            // 1. Initialize directories
            initDirectories();

            // 2. Load configuration
            Config config = Config.load();

            // 3. Attach Arthas via BotArthas bootstrap
            BotArthas arthas = new BotArthas(pid, config);
            McpClient mcpClient = arthas.attach();

            // 4. Print ready message
            System.out.println("\n=================================================");
            System.out.println("🚀 Agent is ready!");
            System.out.println("=================================================\n");

            // 5. Get API key and update config
            String apiKey = getApiKey(config);
            config.getLlm().setApiKey(apiKey);

            // Override with environment variables if set
            String envBaseUrl = System.getenv("OPENAI_BASE_URL");
            if (envBaseUrl != null && !envBaseUrl.trim().isEmpty()) {
                config.getLlm().setBaseUrl(envBaseUrl);
            }
            String envModel = System.getenv("OPENAI_MODEL");
            if (envModel != null && !envModel.trim().isEmpty()) {
                config.getLlm().setModel(envModel);
            }

            // 6. Create provider and start TUI
            CompletionProvider provider = new OpenAICompletionProvider(config.getLlm());
            TUIClient tui = new TUIClient(provider, mcpClient, config);
            tui.start();

        } catch (Exception e) {
            System.err.println("[-] Error: " + e.getMessage());
            e.printStackTrace();
            System.exit(1);
        }
    }

    public TUIClient(CompletionProvider provider, McpClient mcpClient, Config config) {
        this.provider = provider;
        this.mcpClient = mcpClient;
        this.config = config;
        this.loopAgent = new LoopAgent(provider, mcpClient, config);
        this.skillManager = new SkillManager();
        this.reader = new BufferedReader(new InputStreamReader(System.in));

        try {
            this.terminal = TerminalBuilder.builder()
                    .name("ArthasClaw")
                    .build();
            this.lineReader = LineReaderBuilder.builder()
                    .terminal(terminal)
                    .option(LineReader.Option.HISTORY_IGNORE_DUPS, true)
                    .option(LineReader.Option.HISTORY_IGNORE_SPACE, true)
                    .build();
        } catch (IOException e) {
            System.err.println("[-] Failed to initialize terminal: " + e.getMessage());
            this.terminal = null;
            this.lineReader = null;
        }
    }

    /**
     * Legacy constructor for backward compatibility.
     */
    public TUIClient(CompletionProvider provider, McpClient mcpClient) {
        this(provider, mcpClient, new Config());
    }

    public void start() {
        printWelcome();
        loopAgent.init();
        fetchToolsList();

        // Load skills into LoopAgent
        updateLoopAgentSkills();

        while (running) {
            try {
                String prompt = "\n" + CYAN + "arthasclaw> " + RESET;
                String input;

                if (lineReader != null) {
                    // Use JLine for advanced line editing (arrow keys, Ctrl+A/E, history)
                    input = lineReader.readLine(prompt);
                } else {
                    // Fallback to basic BufferedReader
                    System.out.print(prompt);
                    input = reader.readLine();
                }

                if (input == null || input.trim().isEmpty()) {
                    continue;
                }

                // Add to JLine history if available
                if (lineReader != null) {
                    lineReader.getHistory().add(input.trim());
                }

                processCommand(input.trim());

            } catch (IOException e) {
                System.err.println(RED + "[-] IO Error: " + e.getMessage() + RESET);
            } catch (Exception e) {
                System.err.println(RED + "[-] Error: " + e.getMessage() + RESET);
            }
        }

        cleanup();
    }

    private void printWelcome() {
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

    void processCommand(String input) {
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
     * Handle system commands (/help, /quit, /clear, /tools, /skill)
     */
    private void handleSystemCommand(String cmd) {
        String[] parts = cmd.split("\\s+", 2);
        String command = parts[0].toLowerCase();
        String args = parts.length > 1 ? parts[1] : "";

        switch (command) {
            case "quit":
            case "exit":
            case "q":
                System.out.println(BLUE + "[*] Goodbye!" + RESET);
                running = false;
                break;

            case "help":
            case "h":
            case "?":
                printHelp();
                break;

            case "clear":
                loopAgent.clearMessages();
                System.out.println(BLUE + "[*] Conversation history cleared" + RESET);
                break;

            case "tools":
                listTools();
                break;

            case "history":
                showHistory();
                break;

            case "config":
                showConfig();
                break;

            case "version":
                System.out.println("ArthasClaw TUI v1.0.0");
                break;

            case "skill":
                handleSkillCommand(args);
                break;

            default:
                System.out.println(RED + "[-] Unknown system command: /" + command + RESET);
                System.out.println(YELLOW + "    Type /help to see available commands" + RESET);
        }
    }

    private void printHelp() {
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

    private void showConfig() {
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
     * Handle skill commands (/skill install|list|show|remove)
     */
    private void handleSkillCommand(String args) {
        if (args.isEmpty()) {
            System.out.println(RED + "[-] Usage: /skill <install|list|show|remove> [args]" + RESET);
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
                System.out.println(RED + "[-] Unknown skill command: " + subCommand + RESET);
                System.out.println(YELLOW + "    Available: install, list, show, remove" + RESET);
        }
    }

    private void installSkill(String source) {
        if (source.isEmpty()) {
            System.out.println(RED + "[-] Usage: /skill install <url|path>" + RESET);
            return;
        }

        System.out.println(YELLOW + "[*] Installing skill from: " + source + RESET);
        try {
            Skill skill = skillManager.install(source);
            System.out.println(GREEN + "[+] Skill installed: " + skill.getName() +
                    (skill.getVersion() != null ? " v" + skill.getVersion() : "") + RESET);
            if (skill.getDescription() != null) {
                System.out.println("    Description: " + skill.getDescription());
            }
            // Update LoopAgent with new skills
            updateLoopAgentSkills();
        } catch (Exception e) {
            System.out.println(RED + "[-] Failed to install skill: " + e.getMessage() + RESET);
        }
    }

    private void listSkills() {
        List<Skill> skills = skillManager.listAll();
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

    private void showSkill(String name) {
        if (name.isEmpty()) {
            System.out.println(RED + "[-] Usage: /skill show <name>" + RESET);
            return;
        }

        skillManager.get(name).ifPresentOrElse(
                skill -> {
                    System.out.println();
                    System.out.println(CYAN + "Skill Details:" + RESET);
                    System.out.println(skill.getDetails());
                },
                () -> System.out.println(RED + "[-] Skill not found: " + name + RESET)
        );
    }

    private void removeSkill(String name) {
        if (name.isEmpty()) {
            System.out.println(RED + "[-] Usage: /skill remove <name>" + RESET);
            return;
        }

        try {
            if (skillManager.remove(name)) {
                System.out.println(GREEN + "[+] Skill removed: " + name + RESET);
                updateLoopAgentSkills();
            } else {
                System.out.println(RED + "[-] Skill not found: " + name + RESET);
            }
        } catch (Exception e) {
            System.out.println(RED + "[-] Failed to remove skill: " + e.getMessage() + RESET);
        }
    }

    /**
     * Update LoopAgent with current skills.
     */
    private void updateLoopAgentSkills() {
        String combinedPrompt = skillManager.getCombinedPrompt();
        loopAgent.setSkillsPrompt(combinedPrompt);
    }

    private void listTools() {
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

    private void showHistory() {
        ArrayNode messages = loopAgent.getMessages();
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
     * Handle shell commands (!cmd)
     */
    private void handleShellCommand(String cmd) {
        if (cmd.isEmpty()) {
            System.out.println(RED + "[-] Please enter a shell command to execute" + RESET);
            return;
        }

        System.out.println(YELLOW + "[Shell] Executing: " + cmd + RESET);
        System.out.println();

        try {
            ProcessBuilder pb = new ProcessBuilder("bash", "-c", cmd);
            pb.redirectErrorStream(true);
            Process process = pb.start();

            BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()));
            String line;
            while ((line = reader.readLine()) != null) {
                System.out.println("  " + line);
            }

            int exitCode = process.waitFor();
            System.out.println();
            if (exitCode == 0) {
                System.out.println(GREEN + "[+] Command completed (exit code: " + exitCode + ")" + RESET);
            } else {
                System.out.println(RED + "[-] Command failed (exit code: " + exitCode + ")" + RESET);
            }

        } catch (Exception e) {
            System.out.println(RED + "[-] Execution failed: " + e.getMessage() + RESET);
        }
    }

    /**
     * Handle Arthas commands ($cmd)
     */
    private void handleArthasCommand(String cmd) {
        if (cmd.isEmpty()) {
            System.out.println(RED + "[-] Please enter an Arthas command to execute" + RESET);
            return;
        }

        System.out.println(YELLOW + "[Arthas] Executing: " + cmd + RESET);
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
            System.out.println(GREEN + "[+] Arthas command completed" + RESET);

        } catch (Exception e) {
            // If direct tool call fails, try using AI to interpret
            System.out.println(YELLOW + "[*] Direct execution failed, trying via AI..." + RESET);
            handleNaturalLanguage("Execute Arthas command: " + cmd);
        }
    }

    /**
     * Handle natural language queries (default mode)
     */
    private void handleNaturalLanguage(String input) {
        if (input.isEmpty()) {
            return;
        }
        // Delegate to LoopAgent for processing
        loopAgent.processQuery(input);
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

    private void fetchToolsList() {
        System.out.println("[*] Loading Arthas tools...");
        try {
            JsonNode result = mcpClient.listTools().get(
                    config.getAgent().getListToolsTimeoutSeconds(), TimeUnit.SECONDS);
            JsonNode toolsList = result.get("tools");

            toolsConfig = mapper.createArrayNode();

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
                        ObjectNode params = mapper.createObjectNode();
                        params.put("type", "object");
                        params.set("properties", mapper.createObjectNode());
                        function.set("parameters", params);
                    }
                    aiTool.set("function", function);
                    toolsConfig.add(aiTool);
                }
            }
            System.out.println(GREEN + "[+] Loaded " + toolsConfig.size() + " tools" + RESET);
        } catch (Exception e) {
            System.err.println(RED + "[-] Failed to load tools: " + e.getMessage() + RESET);
            toolsConfig = mapper.createArrayNode();
        }
    }

    private void cleanup() {
        System.out.println(BLUE + "[*] Shutting down..." + RESET);
        loopAgent.close();
        try {
            reader.close();
        } catch (IOException e) {
            // Ignore
        }
        if (terminal != null) {
            try {
                terminal.close();
            } catch (IOException e) {
                // Ignore
            }
        }
    }
}
