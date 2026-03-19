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
package io.github.jiajunbernoulli.cli;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.jiajunbernoulli.controller.LoopAgent;
import io.github.jiajunbernoulli.controller.providers.CompletionProvider;
import io.github.jiajunbernoulli.controller.providers.OpenAICompletionProvider;
import io.github.jiajunbernoulli.cli.bootstrap.BotArthas;
import io.github.jiajunbernoulli.mcp.McpClient;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.util.concurrent.TimeUnit;

/**
 * TUI (Text User Interface) client for ArthasClaw.
 * Supports four command modes:
 * - Default: Natural language query (handled by AI)
 * - ! prefix: Execute shell command
 * - $ prefix: Execute Arthas command via MCP
 * - / prefix: System commands (quit, help, etc.)
 */
public class TUIClient {
    private final CompletionProvider provider;
    private final McpClient mcpClient;
    private final LoopAgent loopAgent;
    private final ObjectMapper mapper = new ObjectMapper();
    private ArrayNode toolsConfig;
    private boolean running = true;
    private final BufferedReader reader;

    // ANSI color codes
    private static final String RESET = "\u001B[0m";
    private static final String GREEN = "\u001B[32m";
    private static final String YELLOW = "\u001B[33m";
    private static final String BLUE = "\u001B[34m";
    private static final String CYAN = "\u001B[36m";
    private static final String RED = "\u001B[31m";

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
            // 1. Attach Arthas via BotArthas bootstrap
            BotArthas arthas = new BotArthas(pid);
            McpClient mcpClient = arthas.attach();

            // 2. Print ready message
            System.out.println("\n=================================================");
            System.out.println("🚀 Agent is ready!");
            System.out.println("=================================================\n");

            // 3. Setup AI provider from environment
            String apiKey = System.getenv("OPENAI_API_KEY");
            if (apiKey == null || apiKey.trim().isEmpty()) {
                System.err.println("[-] OPENAI_API_KEY environment variable is not set.");
                System.err.println("[-] Please set it before running, e.g.: export OPENAI_API_KEY=sk-xxx");
                System.exit(1);
            }

            String baseUrl = System.getenv("OPENAI_BASE_URL");
            if (baseUrl == null || baseUrl.trim().isEmpty()) {
                baseUrl = "https://api.openai.com/v1/chat/completions";
            }

            String model = System.getenv("OPENAI_MODEL");
            if (model == null || model.trim().isEmpty()) {
                model = "gpt-4o-mini";
            }

            // 4. Create provider and start TUI
            CompletionProvider provider = new OpenAICompletionProvider(apiKey, model, baseUrl);
            TUIClient tui = new TUIClient(provider, mcpClient);
            tui.start();

        } catch (Exception e) {
            System.err.println("[-] Error: " + e.getMessage());
            e.printStackTrace();
            System.exit(1);
        }
    }

    public TUIClient(CompletionProvider provider, McpClient mcpClient) {
        this.provider = provider;
        this.mcpClient = mcpClient;
        this.loopAgent = new LoopAgent(provider, mcpClient);
        this.reader = new BufferedReader(new InputStreamReader(System.in));
    }

    public void start() {
        printWelcome();
        loopAgent.init();
        fetchToolsList();

        while (running) {
            try {
                System.out.print("\n" + CYAN + "arthas> " + RESET);
                String input = reader.readLine();

                if (input == null || input.trim().isEmpty()) {
                    continue;
                }

                processCommand(input.trim());

            } catch (IOException e) {
                System.err.println(RED + "[-] IO Error: " + e.getMessage() + RESET);
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
        System.out.println("命令模式:");
        System.out.println("  " + YELLOW + "<自然语言>" + RESET + "  - AI 智能诊断 (默认)");
        System.out.println("  " + YELLOW + "!<命令>" + RESET + "    - 执行 Shell 命令 (例: !ls -la)");
        System.out.println("  " + YELLOW + "$<命令>" + RESET + "    - 执行 Arthas 命令 (例: $thread)");
        System.out.println("  " + YELLOW + "/<命令>" + RESET + "    - 系统命令 (例: /help, /quit)");
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
     * Handle system commands (/help, /quit, /clear, /tools)
     */
    private void handleSystemCommand(String cmd) {
        String[] parts = cmd.split("\\s+", 2);
        String command = parts[0].toLowerCase();
        String args = parts.length > 1 ? parts[1] : "";

        switch (command) {
            case "quit":
            case "exit":
            case "q":
                System.out.println(BLUE + "[*] 再见!" + RESET);
                running = false;
                break;

            case "help":
            case "h":
            case "?":
                printHelp();
                break;

            case "clear":
                loopAgent.clearMessages();
                System.out.println(BLUE + "[*] 对话历史已清空" + RESET);
                break;

            case "tools":
                listTools();
                break;

            case "history":
                showHistory();
                break;

            case "version":
                System.out.println("ArthasClaw TUI v1.0.0");
                break;

            default:
                System.out.println(RED + "[-] 未知系统命令: /" + command + RESET);
                System.out.println(YELLOW + "    输入 /help 查看可用命令" + RESET);
        }
    }

    private void printHelp() {
        System.out.println();
        System.out.println(CYAN + "════════════════════════════════════════════════════════" + RESET);
        System.out.println(CYAN + "                      帮助信息                          " + RESET);
        System.out.println(CYAN + "════════════════════════════════════════════════════════" + RESET);
        System.out.println();
        System.out.println("【系统命令】 /<命令>");
        System.out.println("  /help, /h, /?    显示帮助信息");
        System.out.println("  /quit, /exit, /q  退出程序");
        System.out.println("  /clear            清空对话历史");
        System.out.println("  /tools            列出可用工具");
        System.out.println("  /history          查看对话历史");
        System.out.println("  /version          显示版本信息");
        System.out.println();
        System.out.println("【Shell 命令】 !<命令>");
        System.out.println("  !ls -la           列出当前目录文件");
        System.out.println("  !ps aux | grep java  查找 Java 进程");
        System.out.println("  !jstat -gc <pid>  查看 GC 统计信息");
        System.out.println();
        System.out.println("【Arthas 命令】 $<命令>");
        System.out.println("  $thread           查看线程信息");
        System.out.println("  $dashboard        查看仪表盘");
        System.out.println("  $jad <class>      反编译类");
        System.out.println("  $watch <class> <method>  观察方法调用");
        System.out.println();
        System.out.println("【自然语言】 直接输入问题");
        System.out.println("  MathGame有哪些方法?");
        System.out.println("  查看线程死锁情况");
        System.out.println("  分析内存使用情况");
        System.out.println();
    }

    private void listTools() {
        if (toolsConfig == null || toolsConfig.size() == 0) {
            System.out.println(YELLOW + "[!] 未加载工具" + RESET);
            return;
        }

        System.out.println();
        System.out.println(CYAN + "可用 Arthas 工具 (" + toolsConfig.size() + " 个):" + RESET);
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
        System.out.println(CYAN + "对话历史 (" + (messages.size() - 1) + " 条):" + RESET);
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
            System.out.println(RED + "[-] 请输入要执行的 Shell 命令" + RESET);
            return;
        }

        System.out.println(YELLOW + "[Shell] 执行: " + cmd + RESET);
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
                System.out.println(GREEN + "[+] 命令执行完成 (exit code: " + exitCode + ")" + RESET);
            } else {
                System.out.println(RED + "[-] 命令执行失败 (exit code: " + exitCode + ")" + RESET);
            }

        } catch (Exception e) {
            System.out.println(RED + "[-] 执行失败: " + e.getMessage() + RESET);
        }
    }

    /**
     * Handle Arthas commands ($cmd)
     */
    private void handleArthasCommand(String cmd) {
        if (cmd.isEmpty()) {
            System.out.println(RED + "[-] 请输入要执行的 Arthas 命令" + RESET);
            return;
        }

        System.out.println(YELLOW + "[Arthas] 执行: " + cmd + RESET);
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
            JsonNode mcpResult = mcpClient.callTool(command, arguments).get(30, TimeUnit.SECONDS);

            // Extract and print result
            String resultStr = extractMcpResult(mcpResult);
            System.out.println(resultStr);
            System.out.println();
            System.out.println(GREEN + "[+] Arthas 命令执行完成" + RESET);

        } catch (Exception e) {
            // If direct tool call fails, try using AI to interpret
            System.out.println(YELLOW + "[*] 直接执行失败，尝试通过 AI 执行..." + RESET);
            handleNaturalLanguage("执行 Arthas 命令: " + cmd);
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
        System.out.println("[*] 加载 Arthas 工具...");
        try {
            JsonNode result = mcpClient.listTools().get(5, TimeUnit.SECONDS);
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
            System.out.println(GREEN + "[+] 已加载 " + toolsConfig.size() + " 个工具" + RESET);
        } catch (Exception e) {
            System.err.println(RED + "[-] 加载工具失败: " + e.getMessage() + RESET);
            toolsConfig = mapper.createArrayNode();
        }
    }

    private void cleanup() {
        System.out.println(BLUE + "[*] 正在关闭..." + RESET);
        loopAgent.close();
        try {
            reader.close();
        } catch (IOException e) {
            // Ignore
        }
    }
}
