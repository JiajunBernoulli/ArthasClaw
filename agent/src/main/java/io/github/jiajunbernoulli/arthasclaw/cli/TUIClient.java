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
import io.github.jiajunbernoulli.arthasclaw.context.SessionContext;
import io.github.jiajunbernoulli.arthasclaw.controller.LoopAgent;
import io.github.jiajunbernoulli.arthasclaw.controller.providers.CompletionProvider;
import io.github.jiajunbernoulli.arthasclaw.controller.providers.OpenAICompletionProvider;
import io.github.jiajunbernoulli.arthasclaw.cli.bootstrap.BotArthas;
import io.github.jiajunbernoulli.arthasclaw.mcp.McpClient;
import io.github.jiajunbernoulli.arthasclaw.skill.SkillManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.concurrent.TimeUnit;

import org.jline.reader.LineReader;
import org.jline.reader.LineReaderBuilder;
import org.jline.terminal.Terminal;
import org.jline.terminal.TerminalBuilder;

import static io.github.jiajunbernoulli.arthasclaw.cli.DisplayHelper.*;

/**
 * TUI (Text User Interface) client for ArthasClaw.
 * Entry point and main loop for the interactive diagnostic tool.
 */
public class TUIClient {
    private static final Logger log = LoggerFactory.getLogger(TUIClient.class);
    
    private final CompletionProvider provider;
    private final McpClient mcpClient;
    private final LoopAgent loopAgent;
    private final SkillManager skillManager;
    private final Config config;
    private final ObjectMapper mapper = new ObjectMapper();
    private final CommandDispatcher dispatcher;
    private final BufferedReader reader;
    private Terminal terminal;
    private LineReader lineReader;
    private final SessionContext sessionContext;

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
        
        // Create session context
        this.sessionContext = new SessionContext();
        log.info("Session started: {}", sessionContext.getSessionId());
        
        this.loopAgent = new LoopAgent(provider, mcpClient, config);
        this.loopAgent.setSessionContext(this.sessionContext);
        
        this.skillManager = new SkillManager();
        this.dispatcher = new CommandDispatcher(loopAgent, mcpClient, skillManager, config, mapper);
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

    /**
     * Process a command string. Delegates to CommandDispatcher.
     * This method is used by tests.
     */
    void processCommand(String input) {
        dispatcher.processCommand(input);
    }

    public void start() {
        printWelcome();
        loopAgent.init();
        fetchToolsList();

        // Load skills into LoopAgent
        updateLoopAgentSkills();

        while (dispatcher.isRunning()) {
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

                dispatcher.processCommand(input.trim());

            } catch (IOException e) {
                printError("[-] IO Error: " + e.getMessage());
            } catch (Exception e) {
                printError("[-] Error: " + e.getMessage());
            }
        }

        cleanup();
    }

    /**
     * Initialize ArthasClaw directories in ~/.arthasclaw
     */
    private static void initDirectories() {
        String[][] dirs = {
                {ARTHASCLAW_DIR, "home"},
                {ARTHASCLAW_DIR + "/skills", "skills"},
                {ARTHASCLAW_DIR + "/memory", "memory"},
                {ARTHASCLAW_DIR + "/workspace", "workspace"},
                {ARTHASCLAW_DIR + "/logs", "logs"}
        };

        System.out.println("[*] Initializing ArthasClaw directories...");

        for (String[] dir : dirs) {
            Path path = Paths.get(dir[0]);
            if (!Files.exists(path)) {
                try {
                    Files.createDirectories(path);
                    System.out.println("[+] Created: ~/.arthasclaw/" + dir[1]);
                } catch (IOException e) {
                    System.err.println("[-] Failed to create directory: " + dir[0] + " - " + e.getMessage());
                }
            } else {
                System.out.println("[+] Found: ~/.arthasclaw/" + dir[1]);
            }
        }
    }

    /**
     * Get API key from config or prompt user.
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
     * Fetch tools list from MCP and update dispatcher.
     */
    private void fetchToolsList() {
        log.debug("Loading Arthas tools...");
        System.out.println("[*] Loading Arthas tools...");
        try {
            JsonNode result = mcpClient.listTools().get(
                    config.getAgent().getListToolsTimeoutSeconds(), TimeUnit.SECONDS);
            JsonNode toolsList = result.get("tools");

            ArrayNode toolsConfig = mapper.createArrayNode();

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
            dispatcher.setToolsConfig(toolsConfig);
            log.info("Loaded {} Arthas tools", toolsConfig.size());
            System.out.println(GREEN + "[+] Loaded " + toolsConfig.size() + " tools" + RESET);
        } catch (Exception e) {
            log.error("Failed to load tools: {}", e.getMessage(), e);
            System.err.println(RED + "[-] Failed to load tools: " + e.getMessage() + RESET);
        }
    }

    /**
     * Update LoopAgent with current skills.
     */
    private void updateLoopAgentSkills() {
        String combinedPrompt = skillManager.getCombinedPrompt();
        loopAgent.setSkillsPrompt(combinedPrompt);
    }

    /**
     * Cleanup resources on shutdown.
     */
    private void cleanup() {
        log.info("Session ended: {}", sessionContext.getSessionId());
        printInfo("[*] Shutting down...");
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
        // Close session context (clears MDC)
        sessionContext.close();
    }
}