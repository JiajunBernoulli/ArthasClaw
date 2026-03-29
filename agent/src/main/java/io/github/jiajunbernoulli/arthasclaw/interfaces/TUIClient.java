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
import io.github.jiajunbernoulli.arthasclaw.application.SessionContext;
import io.github.jiajunbernoulli.arthasclaw.domain.CompletionProvider;
import io.github.jiajunbernoulli.arthasclaw.domain.skill.SkillManager;
import io.github.jiajunbernoulli.arthasclaw.infrastructure.config.Config;
import io.github.jiajunbernoulli.arthasclaw.infrastructure.llm.OpenAICompletionProvider;
import io.github.jiajunbernoulli.arthasclaw.infrastructure.mcp.McpClient;
import io.github.jiajunbernoulli.arthasclaw.infrastructure.memory.MemoryManager;
import io.github.jiajunbernoulli.arthasclaw.interfaces.bootstrap.BotArthas;
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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * TUI (Text User Interface) client for ArthasClaw.
 * Entry point and main loop for the interactive diagnostic tool.
 */
public class TUIClient {

    /** Logger instance. */
    private static final Logger LOG =
            LoggerFactory.getLogger(TUIClient.class);

    /** Number of arguments required. */
    private static final int REQUIRED_ARGS = 1;

    /** Maximum messages for summary generation. */
    private static final int MAX_SUMMARY_MESSAGES = 20;

    /** Maximum summary length. */
    private static final int MAX_SUMMARY_LENGTH = 100;

    /** Maximum truncated summary length. */
    private static final int MAX_TRUNCATED_SUMMARY = 97;

    /** Maximum first user message length for summary. */
    private static final int MAX_FIRST_MSG_LENGTH = 80;

    /** Maximum first user message truncated length. */
    private static final int MAX_FIRST_MSG_TRUNCATED = 77;

    /** Completion provider for LLM calls. */
    private final CompletionProvider provider;

    /** MCP client for tool execution. */
    private final McpClient mcpClient;

    /** Agent loop for processing queries. */
    private final LoopAgent loopAgent;

    /** Skill manager for handling skills. */
    private final SkillManager skillManager;

    /** Application configuration. */
    private final Config config;

    /** JSON object mapper. */
    private final ObjectMapper mapper = new ObjectMapper();

    /** Command dispatcher for handling user input. */
    private final CommandDispatcher dispatcher;

    /** Buffered reader for user input. */
    private final BufferedReader reader;

    /** JLine terminal for advanced line editing. */
    private Terminal terminal;

    /** JLine line reader for input history. */
    private LineReader lineReader;

    /** Session context for logging. */
    private final SessionContext sessionContext;

    /** Memory manager for session storage. */
    private final MemoryManager memoryManager;

    /**
     * Main entry point for TUI client.
     * Attaches Arthas to target JVM and starts interactive session.
     *
     * @param args command line arguments, expects PID as first arg
     */
    public static void main(final String[] args) {
        if (args.length < REQUIRED_ARGS) {
            System.err.println("Usage: java -jar bot-agent.jar <PID>");
            System.exit(1);
        }

        String pid = args[0];

        try {
            initDirectories();

            Config config = Config.load();

            BotArthas arthas = new BotArthas(pid, config);
            McpClient mcpClient = arthas.attach();

            System.out.println();
            System.out.println(
                    "=================================================");
            System.out.println("Agent is ready!");
            System.out.println(
                    "=================================================");
            System.out.println();

            String apiKey = getApiKey(config);
            config.getLlm().setApiKey(apiKey);

            String envBaseUrl = System.getenv("OPENAI_BASE_URL");
            if (envBaseUrl != null && !envBaseUrl.trim().isEmpty()) {
                config.getLlm().setBaseUrl(envBaseUrl);
            }
            String envModel = System.getenv("OPENAI_MODEL");
            if (envModel != null && !envModel.trim().isEmpty()) {
                config.getLlm().setModel(envModel);
            }

            CompletionProvider provider =
                    new OpenAICompletionProvider(config.getLlm());
            TUIClient tui =
                    new TUIClient(provider, mcpClient, config);
            tui.start();

        } catch (Exception e) {
            System.err.println("[-] Error: " + e.getMessage());
            e.printStackTrace();
            System.exit(1);
        }
    }

    /**
     * Create a new TUI client.
     *
     * @param compProvider completion provider for LLM calls
     * @param client       MCP client for tool execution
     * @param appConfig    application configuration
     */
    public TUIClient(
            final CompletionProvider compProvider,
            final McpClient client,
            final Config appConfig) {
        this.provider = compProvider;
        this.mcpClient = client;
        this.config = appConfig;

        this.sessionContext = new SessionContext();
        LOG.info("Session started: {}", sessionContext.getSessionId());

        this.memoryManager = new MemoryManager();
        this.memoryManager.startSession(sessionContext.getSessionId());

        this.loopAgent = new LoopAgent(provider, mcpClient, config);
        this.loopAgent.setSessionContext(this.sessionContext);
        this.loopAgent.setMemoryManager(this.memoryManager);

        this.skillManager = new SkillManager();
        this.dispatcher = new CommandDispatcher(
                loopAgent, mcpClient, skillManager, config, mapper);
        this.reader = new BufferedReader(
                new InputStreamReader(System.in));

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
            System.err.println(
                    "[-] Failed to initialize terminal: " + e.getMessage());
            this.terminal = null;
            this.lineReader = null;
        }
    }

    /**
     * Legacy constructor for backward compatibility.
     *
     * @param compProvider completion provider
     * @param client       MCP client
     */
    public TUIClient(
            final CompletionProvider compProvider,
            final McpClient client) {
        this(compProvider, client, new Config());
    }

    /**
     * Process a command string.
     * Delegates to CommandDispatcher. Used by tests.
     *
     * @param input the user input command
     */
    void processCommand(final String input) {
        dispatcher.processCommand(input);
    }

    /**
     * Start the interactive TUI loop.
     */
    public void start() {
        DisplayHelper.printWelcome();
        loopAgent.init();
        fetchToolsList();

        updateLoopAgentSkills();

        while (dispatcher.isRunning()) {
            try {
                String prompt = "\n" + DisplayHelper.CYAN
                        + "arthasclaw> " + DisplayHelper.RESET;
                String input;

                if (lineReader != null) {
                    input = lineReader.readLine(prompt);
                } else {
                    System.out.print(prompt);
                    input = reader.readLine();
                }

                if (input == null || input.trim().isEmpty()) {
                    continue;
                }

                if (lineReader != null) {
                    lineReader.getHistory().add(input.trim());
                }

                dispatcher.processCommand(input.trim());

            } catch (IOException e) {
                DisplayHelper.printError(
                        "[-] IO Error: " + e.getMessage());
            } catch (Exception e) {
                DisplayHelper.printError(
                        "[-] Error: " + e.getMessage());
            }
        }

        cleanup();
    }

    /**
     * Initialize ArthasClaw directories in ~/.arthasclaw.
     */
    private static void initDirectories() {
        String[][] dirs = {
                {DisplayHelper.ARTHASCLAW_DIR, "home"},
                {DisplayHelper.ARTHASCLAW_DIR + "/skills", "skills"},
                {DisplayHelper.ARTHASCLAW_DIR + "/sessions", "sessions"},
                {DisplayHelper.ARTHASCLAW_DIR + "/memory", "memory"},
                {DisplayHelper.ARTHASCLAW_DIR + "/workspace", "workspace"},
                {DisplayHelper.ARTHASCLAW_DIR + "/logs", "logs"}
        };

        System.out.println(
                "[*] Initializing ArthasClaw directories...");

        for (String[] dir : dirs) {
            Path path = Paths.get(dir[0]);
            if (!Files.exists(path)) {
                try {
                    Files.createDirectories(path);
                    System.out.println(
                            "[+] Created: ~/.arthasclaw/" + dir[1]);
                } catch (IOException e) {
                    System.err.println(
                            "[-] Failed to create directory: " + dir[0]
                            + " - " + e.getMessage());
                }
            } else {
                System.out.println(
                        "[+] Found: ~/.arthasclaw/" + dir[1]);
            }
        }
    }

    /**
     * Get API key from config or prompt user.
     *
     * @param config the configuration
     * @return the API key
     */
    private static String getApiKey(final Config config) {
        String apiKey = config.getEffectiveApiKey();
        if (apiKey != null && !apiKey.trim().isEmpty()) {
            return apiKey;
        }

        System.out.print("Enter OPENAI_API_KEY: ");
        java.io.Console console = System.console();

        if (console != null) {
            char[] chars = console.readPassword();
            return chars != null ? new String(chars) : null;
        }

        System.err.println();
        System.err.println(
                "[-] Cannot read from terminal. "
                + "Please set OPENAI_API_KEY environment variable");
        System.err.println(
                "    Or add 'api_key' to ~/.arthasclaw/config.yaml");
        System.exit(1);
        return null;
    }

    /**
     * Fetch tools list from MCP and update dispatcher.
     */
    private void fetchToolsList() {
        LOG.debug("Loading Arthas tools...");
        System.out.println("[*] Loading Arthas tools...");
        try {
            JsonNode result = mcpClient.listTools().get(
                    config.getAgent().getListToolsTimeoutSeconds(),
                    TimeUnit.SECONDS);
            JsonNode toolsList = result.get("tools");

            ArrayNode toolsConfig = mapper.createArrayNode();

            if (toolsList != null && toolsList.isArray()) {
                for (JsonNode tool : toolsList) {
                    ObjectNode aiTool = mapper.createObjectNode();
                    aiTool.put("type", "function");
                    ObjectNode function = mapper.createObjectNode();
                    function.put("name", tool.get("name").asText());
                    function.put("description",
                            tool.get("description").asText());

                    if (tool.has("inputSchema")) {
                        function.set("parameters",
                                tool.get("inputSchema"));
                    } else {
                        ObjectNode params = mapper.createObjectNode();
                        params.put("type", "object");
                        params.set("properties",
                                mapper.createObjectNode());
                        function.set("parameters", params);
                    }
                    aiTool.set("function", function);
                    toolsConfig.add(aiTool);
                }
            }
            dispatcher.setToolsConfig(toolsConfig);
            LOG.info("Loaded {} Arthas tools", toolsConfig.size());
            System.out.println(DisplayHelper.GREEN + "[+] Loaded "
                    + toolsConfig.size() + " tools" + DisplayHelper.RESET);
        } catch (Exception e) {
            LOG.error("Failed to load tools: {}", e.getMessage(), e);
            System.err.println(DisplayHelper.RED
                    + "[-] Failed to load tools: " + e.getMessage()
                    + DisplayHelper.RESET);
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
        LOG.info("Session ended: {}", sessionContext.getSessionId());
        DisplayHelper.printInfo("[*] Shutting down...");

        if (memoryManager != null) {
            String summary = generateSessionSummary();
            if (summary == null) {
                summary = getFirstUserMessage();
            }
            memoryManager.endSession(summary);
            if (summary != null) {
                LOG.info("Session summary: {}", summary);
            }
        }

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
        sessionContext.close();
    }

    /**
     * Generate a one-line summary of the session using LLM.
     *
     * @return session summary or null if generation fails
     */
    private String generateSessionSummary() {
        try {
            ArrayNode messages = loopAgent.getMessages();
            if (messages.size() <= 1) {
                return "No conversation";
            }

            StringBuilder conversation = new StringBuilder();
            for (int i = 1; i < messages.size()
                    && i < MAX_SUMMARY_MESSAGES; i++) {
                JsonNode msg = messages.get(i);
                String role = msg.get("role").asText();
                String content = msg.has("content")
                        ? msg.get("content").asText()
                        : "";
                if (content.length() > MAX_SUMMARY_LENGTH) {
                    content = content.substring(
                            0, MAX_SUMMARY_LENGTH) + "...";
                }
                conversation.append(role).append(": ")
                        .append(content).append("\n");
            }

            String summaryPrompt = String.format(
                "Summarize this diagnostic session in ONE short sentence "
                + "(max 80 chars).\n\n"
                + "Conversation:\n%s\n"
                + "Summary:",
                conversation.toString()
            );

            ObjectNode summaryMsg = mapper.createObjectNode();
            summaryMsg.put("role", "user");
            summaryMsg.put("content", summaryPrompt);
            ArrayNode summaryMessages = mapper.createArrayNode();
            summaryMessages.add(summaryMsg);

            ObjectNode response =
                    provider.chatCompletion(summaryMessages, null);
            if (response.hasNonNull("content")) {
                String summary = response.get("content").asText().trim();
                if (summary.length() > MAX_SUMMARY_LENGTH) {
                    summary = summary.substring(
                            0, MAX_TRUNCATED_SUMMARY) + "...";
                }
                return summary;
            }
        } catch (Exception e) {
            LOG.warn("Failed to generate session summary: {}",
                    e.getMessage());
        }
        return null;
    }

    /**
     * Get the first user message from the conversation.
     * Used as fallback when LLM summary generation fails.
     *
     * @return truncated first user message or null
     */
    private String getFirstUserMessage() {
        try {
            ArrayNode messages = loopAgent.getMessages();
            for (int i = 1; i < messages.size(); i++) {
                JsonNode msg = messages.get(i);
                if ("user".equals(msg.get("role").asText())) {
                    String content = msg.has("content")
                            ? msg.get("content").asText()
                            : "";
                    if (!content.isEmpty()) {
                        if (content.length() > MAX_FIRST_MSG_LENGTH) {
                            content = content.substring(
                                    0, MAX_FIRST_MSG_TRUNCATED) + "...";
                        }
                        return "User query: " + content;
                    }
                }
            }
        } catch (Exception e) {
            LOG.debug("Failed to get first user message: {}",
                    e.getMessage());
        }
        return null;
    }
}
