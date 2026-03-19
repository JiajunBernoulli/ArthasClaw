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
package io.github.jiajunbernoulli.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.jiajunbernoulli.controller.providers.CompletionProvider;
import io.github.jiajunbernoulli.mcp.McpClient;

import java.util.Scanner;
import java.util.concurrent.TimeUnit;

public class LoopAgent {
    private final CompletionProvider provider;
    private final ObjectMapper mapper = new ObjectMapper();
    private final McpClient mcpClient;
    private final ArrayNode messages;
    private ArrayNode toolsConfig;

    public LoopAgent(CompletionProvider provider, McpClient mcpClient) {
        this.provider = provider;
        this.mcpClient = mcpClient;
        this.messages = mapper.createArrayNode();

        // System prompt
        ObjectNode sysMsg = mapper.createObjectNode();
        sysMsg.put("role", "system");
        sysMsg.put("content", "You are an expert Java diagnostic assistant. You have access to Arthas tools via MCP. Use the provided tools to inspect and diagnose the Java application. Respond in Chinese.");
        messages.add(sysMsg);
    }

    /**
     * Initialize the agent by fetching tools from MCP.
     * Should be called before processing queries.
     */
    public void init() {
        this.toolsConfig = fetchToolsFromMcp();
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
        init();

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
        try {
            JsonNode result = mcpClient.listTools().get(5, TimeUnit.SECONDS);
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
        } catch (Exception e) {
            System.err.println("[-] Failed to fetch tools: " + e.getMessage());
            return mapper.createArrayNode();
        }
    }

    private void processAiResponse() {
        while (true) {
            try {
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
                            JsonNode mcpResult = mcpClient.callTool(functionName, arguments).get(30, TimeUnit.SECONDS);

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
            } catch (Exception e) {
                System.err.println("[-] Request failed: " + e.getMessage());
                break;
            }
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
