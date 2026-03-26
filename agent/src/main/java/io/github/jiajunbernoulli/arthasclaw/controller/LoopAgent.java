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
import io.github.jiajunbernoulli.arthasclaw.controller.providers.CompletionProvider;
import io.github.jiajunbernoulli.arthasclaw.mcp.McpClient;

import java.io.IOException;
import java.util.Scanner;
import java.util.concurrent.TimeUnit;

public class LoopAgent {
    private final CompletionProvider provider;
    private final ObjectMapper mapper = new ObjectMapper();
    private final McpClient mcpClient;
    private final ArrayNode messages;
    private ArrayNode toolsConfig;
    private String skillsPrompt;

    private static final String BASE_SYSTEM_PROMPT = "You are an expert Java diagnostic assistant. You have access to Arthas tools via MCP. Use the provided tools to inspect and diagnose the Java application.\n\nLanguage Rule: Always reply in the same language that the user used to ask the question. - If the input is Chinese, output Chinese. - If the input is English, output English. - Do not output translations unless explicitly asked.";

    // Limits to prevent infinite loops and unbounded message growth
    // Configurable via JVM system properties with sensible defaults
    private static final int MAX_ITERATIONS = Integer.getInteger("ARTHASCLAW_MAX_ITERATIONS", 20);
    private static final int MAX_MESSAGES = Integer.getInteger("ARTHASCLAW_MAX_MESSAGES", 50);
    private static final int MAX_RETRIES = Integer.getInteger("ARTHASCLAW_MAX_RETRIES", 3);
    private static final long LIST_TOOLS_TIMEOUT_SECONDS = Long.getLong("ARTHASCLAW_LIST_TOOLS_TIMEOUT", 5);
    private static final long TOOL_CALL_TIMEOUT_SECONDS = Long.getLong("ARTHASCLAW_TOOL_CALL_TIMEOUT", 30);
    private static final long RETRY_DELAY_MS = Long.getLong("ARTHASCLAW_RETRY_DELAY_MS", 1000);

    public LoopAgent(CompletionProvider provider, McpClient mcpClient) {
        this.provider = provider;
        this.mcpClient = mcpClient;
        this.messages = mapper.createArrayNode();
        this.skillsPrompt = "";

        // System prompt
        updateSystemMessage();
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
     * Process a single query and return the response.
     * @param input the user query
     */
    public void processQuery(String input) {
        if (input == null || input.trim().isEmpty()) {
            return;
        }

        ObjectNode userMsg = mapper.createObjectNode();
        userMsg.put("role", "user");
        userMsg.put("content", input);
        messages.add(userMsg);

        processAiResponse();
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
        System.out.println("[*] Fetching tools from Arthas MCP Server...");
        
        Exception lastException = null;
        for (int attempt = 1; attempt <= MAX_RETRIES; attempt++) {
            try {
                JsonNode result = mcpClient.listTools().get(LIST_TOOLS_TIMEOUT_SECONDS, TimeUnit.SECONDS);
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
                System.out.println("[+] Loaded " + openAiTools.size() + " tools from Arthas.");
                return openAiTools;
            } catch (java.util.concurrent.TimeoutException e) {
                lastException = e;
                System.err.println("[-] Attempt " + attempt + "/" + MAX_RETRIES + ": Timeout fetching tools");
            } catch (Exception e) {
                lastException = e;
                System.err.println("[-] Attempt " + attempt + "/" + MAX_RETRIES + ": " + e.getMessage());
            }
            
            // Wait before retry (except last attempt)
            if (attempt < MAX_RETRIES) {
                try {
                    Thread.sleep(RETRY_DELAY_MS);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }
        
        System.err.println("[-] Failed to fetch tools after " + MAX_RETRIES + " attempts: " + 
            (lastException != null ? lastException.getMessage() : "unknown error"));
        return mapper.createArrayNode();
    }

    private void processAiResponse() {
        int iteration = 0;
        while (iteration++ < MAX_ITERATIONS) {
            try {
                // Trim message history to prevent unbounded growth
                trimMessages();

                ObjectNode message = provider.chatCompletion(messages, toolsConfig);

                // Add assistant message to history
                messages.add(message);

                // Print text response if any
                if (message.hasNonNull("content")) {
                    System.out.println("\n🤖 AI: " + message.get("content").asText());
                }

                // Handle tool calls
                if (message.has("tool_calls")) {
                    JsonNode toolCalls = message.get("tool_calls");
                    for (JsonNode toolCall : toolCalls) {
                        String toolCallId = toolCall.get("id").asText();
                        String functionName = toolCall.get("function").get("name").asText();
                        String functionArgsStr = toolCall.get("function").get("arguments").asText();

                        System.out.println("[*] Calling tool: " + functionName + " with args: " + functionArgsStr);

                        ObjectNode arguments = (ObjectNode) mapper.readTree(functionArgsStr);

                        // Execute via MCP
                        String toolResultStr;
                        try {
                            JsonNode mcpResult = mcpClient.callTool(functionName, arguments).get(TOOL_CALL_TIMEOUT_SECONDS, TimeUnit.SECONDS);

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
                        } catch (Exception e) {
                            toolResultStr = "Error executing tool: " + e.getMessage();
                        }

                        System.out.println("[*] Tool result length: " + toolResultStr.length() + " chars");

                        // Add tool result to messages
                        ObjectNode toolMsg = mapper.createObjectNode();
                        toolMsg.put("role", "tool");
                        toolMsg.put("tool_call_id", toolCallId);
                        toolMsg.put("name", functionName);
                        toolMsg.put("content", toolResultStr);
                        messages.add(toolMsg);
                    }
                    // Loop continues to send tool results back to AI
                    continue;
                }

                // No tool calls, conversation turn ends
                break;
            } catch (IOException e) {
                // Check for authentication errors (non-retryable)
                String message = e.getMessage();
                if (message != null && (message.contains("401") || message.contains("403") || message.contains("auth"))) {
                    System.err.println("[-] Authentication failed: " + message + ". Check your API key.");
                    break;
                }
                // Timeout or connection errors
                if (message != null && (message.contains("timeout") || message.contains("Timeout"))) {
                    System.err.println("[-] Request timeout. Please try again.");
                    break;
                }
                // Other IO errors
                System.err.println("[-] Request failed: " + message);
                break;
            } catch (Exception e) {
                System.err.println("[-] Unexpected error: " + e.getMessage());
                break;
            }
        }

        if (iteration > MAX_ITERATIONS) {
            System.err.println("[-] Reached max iterations (" + MAX_ITERATIONS + "), stopping to prevent infinite loop.");
        }
    }

    /**
     * Trim message history to prevent unbounded growth.
     * Keeps system prompt (first message) and recent messages up to MAX_MESSAGES.
     */
    private void trimMessages() {
        while (messages.size() > MAX_MESSAGES && messages.size() > 1) {
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
