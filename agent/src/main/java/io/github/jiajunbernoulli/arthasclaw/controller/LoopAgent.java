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
package io.github.jiajunbernoulli.arthasclaw.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.jiajunbernoulli.arthasclaw.config.Config;
import io.github.jiajunbernoulli.arthasclaw.context.SessionContext;
import io.github.jiajunbernoulli.arthasclaw.controller.providers.CompletionProvider;
import io.github.jiajunbernoulli.arthasclaw.mcp.McpClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.Scanner;
import java.util.concurrent.TimeUnit;

public class LoopAgent {
    private static final Logger log = LoggerFactory.getLogger(LoopAgent.class);
    
    private final CompletionProvider provider;
    private final ObjectMapper mapper = new ObjectMapper();
    private final McpClient mcpClient;
    private final ArrayNode messages;
    private ArrayNode toolsConfig;
    private String skillsPrompt;
    private SessionContext sessionContext;

    // Configuration values
    private final int maxIterations;
    private final int maxMessages;
    private final int maxRetries;
    private final int maxToolResultLength;
    private final long listToolsTimeoutSeconds;
    private final long toolCallTimeoutSeconds;
    private final long retryDelayMs;

    private static final String BASE_SYSTEM_PROMPT = "You are an expert Java diagnostic assistant. You have access to Arthas tools via MCP. Use the provided tools to inspect and diagnose the Java application.\n\nLanguage Rule: Always reply in the same language that the user used to ask the question. - If the input is Chinese, output Chinese. - If the input is English, output English. - Do not output translations unless explicitly asked.";

    /**
     * Create LoopAgent with configuration.
     *
     * @param provider  completion provider
     * @param mcpClient MCP client
     * @param config    configuration
     */
    public LoopAgent(CompletionProvider provider, McpClient mcpClient, Config config) {
        this.provider = provider;
        this.mcpClient = mcpClient;
        this.messages = mapper.createArrayNode();
        this.skillsPrompt = "";

        // Load configuration
        Config.AgentConfig agentConfig = config.getAgent();
        this.maxIterations = agentConfig.getMaxIterations();
        this.maxMessages = agentConfig.getMaxMessages();
        this.maxRetries = agentConfig.getMaxRetries();
        this.maxToolResultLength = agentConfig.getMaxToolResultLength();
        this.listToolsTimeoutSeconds = agentConfig.getListToolsTimeoutSeconds();
        this.toolCallTimeoutSeconds = agentConfig.getToolCallTimeoutSeconds();
        this.retryDelayMs = agentConfig.getRetryDelayMs();

        // System prompt
        updateSystemMessage();
    }

    /**
     * Legacy constructor for backward compatibility with default configuration.
     *
     * @param provider  completion provider
     * @param mcpClient MCP client
     */
    public LoopAgent(CompletionProvider provider, McpClient mcpClient) {
        this(provider, mcpClient, new Config());
    }

    /**
     * Update the system message with skills prompt.
     */
    private void updateSystemMessage() {
        // Remove existing system message if present
        if (messages.size() > 0 && "system".equals(messages.get(0).get("role").asText())) {
            messages.remove(0);
        }

        // Build system message with skills
        StringBuilder systemContent = new StringBuilder(BASE_SYSTEM_PROMPT);
        if (skillsPrompt != null && !skillsPrompt.isEmpty()) {
            systemContent.append("\n\n---\n\n# Installed Skills\n\n").append(skillsPrompt);
        }

        ObjectNode sysMsg = mapper.createObjectNode();
        sysMsg.put("role", "system");
        sysMsg.put("content", systemContent.toString());
        messages.insert(0, sysMsg);
    }

    /**
     * Set the combined skills prompt. This will update the system message.
     *
     * @param skillsPrompt the combined prompt from enabled skills
     */
    public void setSkillsPrompt(String skillsPrompt) {
        this.skillsPrompt = skillsPrompt != null ? skillsPrompt : "";
        updateSystemMessage();
    }

    /**
     * Initialize the agent by fetching tools from MCP.
     * Should be called before processing queries.
     * @return true if initialization succeeded, false otherwise
     */
    public boolean init() {
        this.toolsConfig = fetchToolsFromMcp();
        return toolsConfig != null && toolsConfig.size() > 0;
    }

    /**
     * Set the session context for this agent.
     * 
     * @param sessionContext the session context
     */
    public void setSessionContext(SessionContext sessionContext) {
        this.sessionContext = sessionContext;
    }

    /**
     * Process a single query and return the response.
     * @param input the user query
     */
    public void processQuery(String input) {
        if (input == null || input.trim().isEmpty()) {
            return;
        }

        // Start request context
        String requestId = "-";
        if (sessionContext != null) {
            requestId = sessionContext.startRequest();
        }

        // Log user input (truncate if too long)
        String truncatedInput = input.length() > 200 ? input.substring(0, 200) + "..." : input;
        log.info("[{}] User query: {}", requestId, truncatedInput);

        ObjectNode userMsg = mapper.createObjectNode();
        userMsg.put("role", "user");
        userMsg.put("content", input);
        messages.add(userMsg);

        try {
            processAiResponse();
        } finally {
            // End request context
            if (sessionContext != null) {
                sessionContext.endRequest();
            }
            log.debug("[{}] Request completed", requestId);
        }
    }

    public void startInteractiveLoop() {
        System.out.println("🤖 AI Assistant is ready! (Type 'exit' to quit)");
        Scanner scanner = new Scanner(System.in);

        // Fetch tools from MCP
        if (!init()) {
            System.err.println("[-] Warning: Failed to load tools from Arthas. Some functionality may be limited.");
        }

        while (true) {
            System.out.print("\n> ");
            String input = scanner.nextLine().trim();
            if (input.equalsIgnoreCase("exit") || input.equalsIgnoreCase("quit")) {
                break;
            }
            if (input.isEmpty()) continue;

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

    private ArrayNode fetchToolsFromMcp() {
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
                        openAiTools.add(aiTool);
                    }
                }
                log.info("Successfully loaded {} tools from Arthas", openAiTools.size());
                System.out.println("[+] Loaded " + openAiTools.size() + " tools from Arthas.");
                return openAiTools;
            } catch (java.util.concurrent.TimeoutException e) {
                lastException = e;
                log.warn("Attempt {}/{}: Timeout fetching tools", attempt, maxRetries);
                System.err.println("[-] Attempt " + attempt + "/" + maxRetries + ": Timeout fetching tools");
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
        
        log.error("Failed to fetch tools after {} attempts: {}", maxRetries, 
                lastException != null ? lastException.getMessage() : "unknown error");
        System.err.println("[-] Failed to fetch tools after " + maxRetries + " attempts: " + 
            (lastException != null ? lastException.getMessage() : "unknown error"));
        return mapper.createArrayNode();
    }

    private void processAiResponse() {
        int iteration = 0;
        while (iteration++ < maxIterations) {
            // Set iteration in context
            if (sessionContext != null) {
                sessionContext.setIteration(iteration);
            }
            
            long iterationStartTime = System.currentTimeMillis();
            log.debug("Iteration {} started", iteration);

            try {
                // Trim message history to prevent unbounded growth
                trimMessages();

                // Call LLM with timing
                long llmStartTime = System.currentTimeMillis();
                ObjectNode message = provider.chatCompletion(messages, toolsConfig);
                long llmDuration = System.currentTimeMillis() - llmStartTime;
                log.info("LLM call completed in {}ms", llmDuration);

                // Add assistant message to history
                messages.add(message);

                // Print text response if any
                if (message.hasNonNull("content")) {
                    System.out.println("\n🤖 AI: " + message.get("content").asText());
                }

                // Handle tool calls
                if (message.has("tool_calls")) {
                    JsonNode toolCalls = message.get("tool_calls");
                    int toolCallCount = toolCalls.size();
                    log.info("AI requested {} tool call(s)", toolCallCount);

                    for (JsonNode toolCall : toolCalls) {
                        String toolCallId = toolCall.get("id").asText();
                        String functionName = toolCall.get("function").get("name").asText();
                        String functionArgsStr = toolCall.get("function").get("arguments").asText();

                        // Log tool call details
                        log.info("Tool call: {} with args: {}", functionName, functionArgsStr);
                        System.out.println("[*] Calling tool: " + functionName + " with args: " + functionArgsStr);

                        ObjectNode arguments = (ObjectNode) mapper.readTree(functionArgsStr);

                        // Execute via MCP with timing
                        String toolResultStr;
                        long toolStartTime = System.currentTimeMillis();
                        try {
                            JsonNode mcpResult = mcpClient.callTool(functionName, arguments).get(toolCallTimeoutSeconds, TimeUnit.SECONDS);
                            long toolDuration = System.currentTimeMillis() - toolStartTime;

                            // Extract text content from MCP result
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
                            toolResultStr = sb.toString().trim();
                            if (toolResultStr.isEmpty()) toolResultStr = "Success (No output)";

                            // Log tool result
                            log.info("Tool {} completed in {}ms, result length: {} chars", 
                                    functionName, toolDuration, toolResultStr.length());
                        } catch (Exception e) {
                            long toolDuration = System.currentTimeMillis() - toolStartTime;
                            toolResultStr = "Error executing tool: " + e.getMessage();
                            log.error("Tool {} failed after {}ms: {}", functionName, toolDuration, e.getMessage());
                        }

                        System.out.println("[*] Tool result length: " + toolResultStr.length() + " chars");

                        // Truncate tool result if too long to save tokens
                        boolean wasTruncated = false;
                        int originalLength = toolResultStr.length();
                        if (toolResultStr.length() > maxToolResultLength) {
                            String truncated = toolResultStr.substring(0, maxToolResultLength);
                            toolResultStr = truncated + "\n... [TRUNCATED: result too long, showing first " + maxToolResultLength + " chars of " + originalLength + "]";
                            System.out.println("[!] Tool result truncated to " + maxToolResultLength + " chars");
                            wasTruncated = true;
                            log.warn("Tool result truncated: original={} chars, truncated={} chars", originalLength, maxToolResultLength);
                        }

                        // Add tool result to messages
                        ObjectNode toolMsg = mapper.createObjectNode();
                        toolMsg.put("role", "tool");
                        toolMsg.put("tool_call_id", toolCallId);
                        toolMsg.put("name", functionName);
                        toolMsg.put("content", toolResultStr);
                        messages.add(toolMsg);
                    }

                    long iterationDuration = System.currentTimeMillis() - iterationStartTime;
                    log.debug("Iteration {} completed in {}ms with {} tool calls", iteration, iterationDuration, toolCallCount);
                    
                    // Loop continues to send tool results back to AI
                    continue;
                }

                // No tool calls, conversation turn ends
                long iterationDuration = System.currentTimeMillis() - iterationStartTime;
                log.info("Request completed in {} iterations, {}ms total", iteration, iterationDuration);
                break;
            } catch (IOException e) {
                // Check for authentication errors (non-retryable)
                String errorMessage = e.getMessage();
                if (errorMessage != null && (errorMessage.contains("401") || errorMessage.contains("403") || errorMessage.contains("auth"))) {
                    log.error("Authentication failed: {}", errorMessage);
                    System.err.println("[-] Authentication failed: " + errorMessage + ". Check your API key.");
                    break;
                }
                // Timeout or connection errors
                if (errorMessage != null && (errorMessage.contains("timeout") || errorMessage.contains("Timeout"))) {
                    log.error("Request timeout: {}", errorMessage);
                    System.err.println("[-] Request timeout. Please try again.");
                    break;
                }
                // Other IO errors
                log.error("Request failed: {}", errorMessage, e);
                System.err.println("[-] Request failed: " + errorMessage);
                break;
            } catch (Exception e) {
                log.error("Unexpected error: {}", e.getMessage(), e);
                System.err.println("[-] Unexpected error: " + e.getMessage());
                break;
            }
        }

        if (iteration > maxIterations) {
            log.warn("Reached max iterations ({})", maxIterations);
            System.err.println("[-] Reached max iterations (" + maxIterations + "), stopping to prevent infinite loop.");
        }
    }

    /**
     * Trim message history to prevent unbounded growth.
     * Keeps system prompt (first message) and recent messages up to maxMessages.
     */
    private void trimMessages() {
        while (messages.size() > maxMessages && messages.size() > 1) {
            // Remove oldest non-system message (index 1, since index 0 is system)
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
     * @return the messages array
     */
    public ArrayNode getMessages() {
        return messages;
    }
}