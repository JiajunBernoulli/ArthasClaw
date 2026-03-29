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
package io.github.jiajunbernoulli.arthasclaw.application;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.jiajunbernoulli.arthasclaw.domain.CompletionProvider;
import io.github.jiajunbernoulli.arthasclaw.infrastructure.config.Config;
import io.github.jiajunbernoulli.arthasclaw.infrastructure.mcp.McpClient;
import io.github.jiajunbernoulli.arthasclaw.infrastructure.memory.MemoryManager;
import java.io.IOException;
import java.util.Scanner;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Main agent loop that handles AI-powered diagnostic conversations.
 * Manages the interaction between user queries, LLM responses,
 * and MCP tool calls.
 */
public class LoopAgent {

    /** Logger instance. */
    private static final Logger LOG =
            LoggerFactory.getLogger(LoopAgent.class);

    /** Base system prompt for the AI assistant. */
    private static final String BASE_SYSTEM_PROMPT =
            "You are an expert Java diagnostic assistant. "
            + "You have access to Arthas tools via MCP. "
            + "Use the provided tools to inspect and diagnose "
            + "the Java application.\n\n"
            + "Language Rule: Always reply in the same language "
            + "that the user used to ask the question. "
            + "- If the input is Chinese, output Chinese. "
            + "- If the input is English, output English. "
            + "- Do not output translations unless explicitly asked.";

    /** Maximum length for truncated user input in logs. */
    private static final int MAX_INPUT_LOG_LENGTH = 200;

    /** Maximum length for truncated tool result. */
    private static final int DEFAULT_MAX_TOOL_RESULT_LENGTH = 8000;

    /** Completion provider for LLM calls. */
    private final CompletionProvider provider;

    /** JSON object mapper. */
    private final ObjectMapper mapper = new ObjectMapper();

    /** MCP client for tool execution. */
    private final McpClient mcpClient;

    /** Conversation message history. */
    private final ArrayNode messages;

    /** Tools configuration from MCP. */
    private ArrayNode toolsConfig;

    /** Combined skills prompt. */
    private String skillsPrompt;

    /** Session context for logging. */
    private SessionContext sessionContext;

    /** Memory manager for fact storage. */
    private MemoryManager memoryManager;

    /** Maximum agent loop iterations. */
    private final int maxIterations;

    /** Maximum messages in conversation history. */
    private final int maxMessages;

    /** Maximum retries for MCP operations. */
    private final int maxRetries;

    /** Maximum tool result length before truncation. */
    private final int maxToolResultLength;

    /** Timeout for listTools MCP call in seconds. */
    private final long listToolsTimeoutSeconds;

    /** Timeout for tool call MCP operations in seconds. */
    private final long toolCallTimeoutSeconds;

    /** Delay between retry attempts in milliseconds. */
    private final long retryDelayMs;

    /**
     * Create LoopAgent with configuration.
     *
     * @param compProvider completion provider for LLM calls
     * @param client       MCP client for tool execution
     * @param appConfig    application configuration
     */
    public LoopAgent(
            final CompletionProvider compProvider,
            final McpClient client,
            final Config appConfig) {
        this.provider = compProvider;
        this.mcpClient = client;
        this.messages = mapper.createArrayNode();
        this.skillsPrompt = "";

        Config.AgentConfig agentConfig = appConfig.getAgent();
        this.maxIterations = agentConfig.getMaxIterations();
        this.maxMessages = agentConfig.getMaxMessages();
        this.maxRetries = agentConfig.getMaxRetries();
        this.maxToolResultLength = agentConfig.getMaxToolResultLength();
        this.listToolsTimeoutSeconds =
                agentConfig.getListToolsTimeoutSeconds();
        this.toolCallTimeoutSeconds =
                agentConfig.getToolCallTimeoutSeconds();
        this.retryDelayMs = agentConfig.getRetryDelayMs();

        updateSystemMessage();
    }

    /**
     * Legacy constructor for backward compatibility.
     *
     * @param compProvider completion provider
     * @param client       MCP client
     */
    public LoopAgent(
            final CompletionProvider compProvider,
            final McpClient client) {
        this(compProvider, client, new Config());
    }

    /**
     * Update the system message with skills prompt.
     */
    private void updateSystemMessage() {
        if (messages.size() > 0
                && "system".equals(messages.get(0).get("role").asText())) {
            messages.remove(0);
        }

        StringBuilder systemContent =
                new StringBuilder(BASE_SYSTEM_PROMPT);
        if (skillsPrompt != null && !skillsPrompt.isEmpty()) {
            systemContent.append("\n\n---\n\n")
                    .append("# Installed Skills\n\n")
                    .append(skillsPrompt);
        }

        ObjectNode sysMsg = mapper.createObjectNode();
        sysMsg.put("role", "system");
        sysMsg.put("content", systemContent.toString());
        messages.insert(0, sysMsg);
    }

    /**
     * Set the combined skills prompt.
     * This will update the system message.
     *
     * @param newSkillsPrompt the combined prompt from enabled skills
     */
    public void setSkillsPrompt(final String newSkillsPrompt) {
        this.skillsPrompt =
                newSkillsPrompt != null ? newSkillsPrompt : "";
        updateSystemMessage();
    }

    /**
     * Initialize the agent by fetching tools from MCP.
     * Should be called before processing queries.
     *
     * @return true if initialization succeeded, false otherwise
     */
    public boolean init() {
        this.toolsConfig = fetchToolsFromMcp();
        return toolsConfig != null && toolsConfig.size() > 0;
    }

    /**
     * Set the session context for this agent.
     *
     * @param newSessionContext the session context
     */
    public void setSessionContext(
            final SessionContext newSessionContext) {
        this.sessionContext = newSessionContext;
    }

    /**
     * Set the memory manager for this agent.
     *
     * @param newMemoryManager the memory manager
     */
    public void setMemoryManager(
            final MemoryManager newMemoryManager) {
        this.memoryManager = newMemoryManager;
    }

    /**
     * Process a single query and return the response.
     *
     * @param input the user query
     */
    public void processQuery(final String input) {
        if (input == null || input.trim().isEmpty()) {
            return;
        }

        String requestId = "-";
        if (sessionContext != null) {
            requestId = sessionContext.startRequest();
        }

        String truncatedInput = input.length() > MAX_INPUT_LOG_LENGTH
                ? input.substring(0, MAX_INPUT_LOG_LENGTH) + "..."
                : input;
        LOG.info("[{}] User query: {}", requestId, truncatedInput);

        boolean shouldExtractMemory = memoryManager != null
                && memoryManager.shouldExtractMemory(input);
        String userMessageForMemory = input;

        ObjectNode userMsg = mapper.createObjectNode();
        userMsg.put("role", "user");
        userMsg.put("content", input);
        messages.add(userMsg);

        if (memoryManager != null) {
            memoryManager.addMessage("user", input);
        }

        try {
            processAiResponse();

            if (shouldExtractMemory) {
                extractAndSaveMemory(userMessageForMemory);
            }
        } finally {
            if (sessionContext != null) {
                sessionContext.endRequest();
            }
            LOG.debug("[{}] Request completed", requestId);
        }
    }

    /**
     * Extract and save memory from user message using LLM.
     *
     * @param userMessage the user message containing memory request
     */
    private void extractAndSaveMemory(final String userMessage) {
        LOG.info("Extracting memory from user message");

        String extractPrompt = String.format(
            "Extract the key information the user wants to remember "
            + "from this message. Return only a JSON object with "
            + "'key' and 'value' fields, nothing else.\n\n"
            + "Example:\n"
            + "Input: \"记住，这个问题的根因是连接池配置错误\"\n"
            + "Output: {\"key\": \"rootCause:connection-pool\", "
            + "\"value\": \"连接池配置错误\"}\n\n"
            + "Input: \"%s\"\n"
            + "Output:",
            userMessage.replace("\"", "\\\"")
        );

        try {
            ArrayNode extractMessages = mapper.createArrayNode();
            ObjectNode extractMsg = mapper.createObjectNode();
            extractMsg.put("role", "user");
            extractMsg.put("content", extractPrompt);
            extractMessages.add(extractMsg);

            ObjectNode response =
                    provider.chatCompletion(extractMessages, null);
            if (response.hasNonNull("content")) {
                String content = response.get("content").asText().trim();

                if (content.startsWith("{")) {
                    JsonNode factJson = mapper.readTree(content);
                    String key = factJson.has("key")
                            ? factJson.get("key").asText()
                            : "user-note";
                    String value = factJson.has("value")
                            ? factJson.get("value").asText()
                            : userMessage;

                    memoryManager.addFact(key, value);
                    LOG.info("Memory saved: key={}, value={}", key, value);
                    System.out.println(
                            "[Memory] Saved: " + key + " = " + value);
                }
            }
        } catch (Exception e) {
            LOG.error("Failed to extract memory: {}", e.getMessage());
            if (memoryManager != null) {
                memoryManager.addFact("user-note", userMessage);
            }
        }
    }

    /**
     * Start the interactive loop for user input.
     */
    public void startInteractiveLoop() {
        System.out.println(
                "AI Assistant is ready! (Type 'exit' to quit)");
        Scanner scanner = new Scanner(System.in);

        if (!init()) {
            System.err.println(
                    "[-] Warning: Failed to load tools from Arthas. "
                    + "Some functionality may be limited.");
        }

        while (true) {
            System.out.print("\n> ");
            String input = scanner.nextLine().trim();
            if (input.equalsIgnoreCase("exit")
                    || input.equalsIgnoreCase("quit")) {
                break;
            }
            if (input.isEmpty()) {
                continue;
            }

            processQuery(input);
        }

        close();
    }

    /**
     * Close resources.
     */
    public void close() {
        mcpClient.close();
        provider.close();
    }

    /**
     * Fetch tools from MCP server.
     *
     * @return array of tool configurations
     */
    private ArrayNode fetchToolsFromMcp() {
        LOG.info("Fetching tools from Arthas MCP Server...");
        System.out.println("[*] Fetching tools from Arthas MCP Server...");

        Exception lastException = null;
        for (int attempt = 1; attempt <= maxRetries; attempt++) {
            try {
                JsonNode result = mcpClient.listTools()
                        .get(listToolsTimeoutSeconds, TimeUnit.SECONDS);
                JsonNode toolsList = result.get("tools");

                ArrayNode openAiTools = mapper.createArrayNode();

                if (toolsList != null && toolsList.isArray()) {
                    for (JsonNode tool : toolsList) {
                        ObjectNode aiTool = mapper.createObjectNode();
                        aiTool.put("type", "function");
                        ObjectNode function =
                                mapper.createObjectNode();
                        function.put("name",
                                tool.get("name").asText());
                        function.put("description",
                                tool.get("description").asText());

                        if (tool.has("inputSchema")) {
                            function.set("parameters",
                                    tool.get("inputSchema"));
                        } else {
                            ObjectNode params =
                                    mapper.createObjectNode();
                            params.put("type", "object");
                            params.set("properties",
                                    mapper.createObjectNode());
                            function.set("parameters", params);
                        }
                        aiTool.set("function", function);
                        openAiTools.add(aiTool);
                    }
                }
                LOG.info("Successfully loaded {} tools from Arthas",
                        openAiTools.size());
                System.out.println(
                        "[+] Loaded " + openAiTools.size()
                        + " tools from Arthas.");
                return openAiTools;
            } catch (java.util.concurrent.TimeoutException e) {
                lastException = e;
                LOG.warn("Attempt {}/{}: Timeout fetching tools",
                        attempt, maxRetries);
                System.err.println(
                        "[-] Attempt " + attempt + "/" + maxRetries
                        + ": Timeout fetching tools");
            } catch (Exception e) {
                lastException = e;
                LOG.warn("Attempt {}/{}: {}",
                        attempt, maxRetries, e.getMessage());
                System.err.println(
                        "[-] Attempt " + attempt + "/" + maxRetries
                        + ": " + e.getMessage());
            }

            if (attempt < maxRetries) {
                try {
                    Thread.sleep(retryDelayMs);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }

        LOG.error("Failed to fetch tools after {} attempts: {}",
                maxRetries,
                lastException != null
                        ? lastException.getMessage()
                        : "unknown error");
        System.err.println(
                "[-] Failed to fetch tools after " + maxRetries
                + " attempts: "
                + (lastException != null
                        ? lastException.getMessage()
                        : "unknown error"));
        return mapper.createArrayNode();
    }

    /**
     * Process AI response and handle tool calls.
     */
    private void processAiResponse() {
        int iteration = 0;
        while (iteration++ < maxIterations) {
            if (sessionContext != null) {
                sessionContext.setIteration(iteration);
            }

            long iterationStartTime = System.currentTimeMillis();
            LOG.debug("Iteration {} started", iteration);

            try {
                trimMessages();

                long llmStartTime = System.currentTimeMillis();
                ObjectNode message =
                        provider.chatCompletion(messages, toolsConfig);
                long llmDuration =
                        System.currentTimeMillis() - llmStartTime;
                LOG.info("LLM call completed in {}ms", llmDuration);

                messages.add(message);

                if (message.hasNonNull("content")) {
                    String assistantContent =
                            message.get("content").asText();
                    System.out.println(
                            "\nAI: " + assistantContent);

                    if (memoryManager != null) {
                        memoryManager.addMessage(
                                "assistant", assistantContent);
                    }
                }

                if (message.has("tool_calls")) {
                    JsonNode toolCalls = message.get("tool_calls");
                    int toolCallCount = toolCalls.size();
                    LOG.info("AI requested {} tool call(s)",
                            toolCallCount);

                    for (JsonNode toolCall : toolCalls) {
                        String toolCallId =
                                toolCall.get("id").asText();
                        String functionName = toolCall.get("function")
                                .get("name").asText();
                        String functionArgsStr = toolCall
                                .get("function").get("arguments")
                                .asText();

                        LOG.info("Tool call: {} with args: {}",
                                functionName, functionArgsStr);
                        System.out.println(
                                "[*] Calling tool: " + functionName
                                + " with args: " + functionArgsStr);

                        ObjectNode arguments = (ObjectNode) mapper
                                .readTree(functionArgsStr);

                        String toolResultStr;
                        long toolStartTime =
                                System.currentTimeMillis();
                        try {
                            JsonNode mcpResult = mcpClient
                                    .callTool(functionName, arguments)
                                    .get(toolCallTimeoutSeconds,
                                            TimeUnit.SECONDS);
                            long toolDuration =
                                    System.currentTimeMillis()
                                    - toolStartTime;

                            StringBuilder sb = new StringBuilder();
                            if (mcpResult.has("content")
                                    && mcpResult.get("content")
                                            .isArray()) {
                                for (JsonNode content
                                        : mcpResult.get("content")) {
                                    if (content.has("type")
                                            && "text".equals(
                                                content.get("type")
                                                    .asText())) {
                                        sb.append(content.get("text")
                                                .asText())
                                          .append("\n");
                                    }
                                }
                            } else {
                                sb.append(mcpResult.toString());
                            }
                            toolResultStr = sb.toString().trim();
                            if (toolResultStr.isEmpty()) {
                                toolResultStr =
                                        "Success (No output)";
                            }

                            LOG.info(
                                "Tool {} completed in {}ms, "
                                + "result length: {} chars",
                                functionName, toolDuration,
                                toolResultStr.length());
                        } catch (Exception e) {
                            long toolDuration =
                                    System.currentTimeMillis()
                                    - toolStartTime;
                            toolResultStr = "Error executing tool: "
                                    + e.getMessage();
                            LOG.error(
                                "Tool {} failed after {}ms: {}",
                                functionName, toolDuration,
                                e.getMessage());
                        }

                        System.out.println(
                                "[*] Tool result length: "
                                + toolResultStr.length() + " chars");

                        int originalLength = toolResultStr.length();
                        if (toolResultStr.length()
                                > maxToolResultLength) {
                            String truncated = toolResultStr
                                    .substring(0, maxToolResultLength);
                            toolResultStr = truncated
                                    + "\n... [TRUNCATED: result too long, "
                                    + "showing first "
                                    + maxToolResultLength
                                    + " chars of " + originalLength + "]";
                            System.out.println(
                                    "[!] Tool result truncated to "
                                    + maxToolResultLength + " chars");
                            LOG.warn(
                                "Tool result truncated: original={} chars, "
                                + "truncated={} chars",
                                originalLength, maxToolResultLength);
                        }

                        ObjectNode toolMsg =
                                mapper.createObjectNode();
                        toolMsg.put("role", "tool");
                        toolMsg.put("tool_call_id", toolCallId);
                        toolMsg.put("name", functionName);
                        toolMsg.put("content", toolResultStr);
                        messages.add(toolMsg);
                    }

                    long iterationDuration =
                            System.currentTimeMillis()
                            - iterationStartTime;
                    LOG.debug(
                        "Iteration {} completed in {}ms with {} tool calls",
                        iteration, iterationDuration, toolCallCount);

                    continue;
                }

                long iterationDuration =
                        System.currentTimeMillis() - iterationStartTime;
                LOG.info("Request completed in {} iterations, "
                        + "{}ms total",
                        iteration, iterationDuration);
                break;
            } catch (IOException e) {
                String errorMessage = e.getMessage();
                if (errorMessage != null
                        && (errorMessage.contains("401")
                            || errorMessage.contains("403")
                            || errorMessage.contains("auth"))) {
                    LOG.error("Authentication failed: {}",
                            errorMessage);
                    System.err.println(
                            "[-] Authentication failed: " + errorMessage
                            + ". Check your API key.");
                    break;
                }
                if (errorMessage != null
                        && (errorMessage.contains("timeout")
                            || errorMessage.contains("Timeout"))) {
                    LOG.error("Request timeout: {}", errorMessage);
                    System.err.println(
                            "[-] Request timeout. Please try again.");
                    break;
                }
                LOG.error("Request failed: {}", errorMessage, e);
                System.err.println(
                        "[-] Request failed: " + errorMessage);
                break;
            } catch (Exception e) {
                LOG.error("Unexpected error: {}",
                        e.getMessage(), e);
                System.err.println(
                        "[-] Unexpected error: " + e.getMessage());
                break;
            }
        }

        if (iteration > maxIterations) {
            LOG.warn("Reached max iterations ({})", maxIterations);
            System.err.println(
                    "[-] Reached max iterations (" + maxIterations
                    + "), stopping to prevent infinite loop.");
        }
    }

    /**
     * Trim message history to prevent unbounded growth.
     * Keeps system prompt (first message) and recent messages.
     */
    private void trimMessages() {
        while (messages.size() > maxMessages && messages.size() > 1) {
            messages.remove(1);
        }
    }

    /**
     * Clear conversation history (keep system prompt).
     */
    public void clearMessages() {
        while (messages.size() > 1) {
            messages.remove(1);
        }
    }

    /**
     * Get conversation messages for display.
     *
     * @return the messages array
     */
    public ArrayNode getMessages() {
        return messages;
    }
}
