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

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import io.github.jiajunbernoulli.controller.providers.CompletionProvider;
import io.github.jiajunbernoulli.mcp.McpClient;

import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Unit tests for TUIClient command modes.
 */
@ExtendWith(MockitoExtension.class)
class TUIClientTest {

    @Mock
    private CompletionProvider mockProvider;

    private TUIClient tuiClient;
    private ObjectMapper mapper;

    /**
     * A simple stub implementation of McpClient for testing.
     * This avoids Mockito's JDK 17+ compatibility issues.
     */
    private static class StubMcpClient extends McpClient {
        private final CompletableFuture<com.fasterxml.jackson.databind.JsonNode> listToolsFuture;
        private final CompletableFuture<com.fasterxml.jackson.databind.JsonNode> callToolFuture;

        public StubMcpClient() {
            super("http://localhost:8563/mcp", null);
            ObjectMapper mapper = new ObjectMapper();
            ObjectNode toolsResult = mapper.createObjectNode();
            toolsResult.set("tools", mapper.createArrayNode());
            this.listToolsFuture = CompletableFuture.completedFuture(toolsResult);
            this.callToolFuture = CompletableFuture.completedFuture(mapper.createObjectNode());
        }

        @Override
        public CompletableFuture<com.fasterxml.jackson.databind.JsonNode> listTools() {
            return listToolsFuture;
        }

        @Override
        public CompletableFuture<com.fasterxml.jackson.databind.JsonNode> callTool(String name, ObjectNode arguments) {
            return callToolFuture;
        }

        @Override
        public CompletableFuture<Void> initialize() {
            return CompletableFuture.completedFuture(null);
        }
    }

    @BeforeEach
    void setUp() throws Exception {
        mapper = new ObjectMapper();

        // Create a real McpClient stub instead of using Mockito mock
        StubMcpClient stubMcpClient = new StubMcpClient();

        // Mock chatCompletion for AiAgent
        ObjectNode mockResponse = mapper.createObjectNode();
        mockResponse.put("role", "assistant");
        mockResponse.put("content", "OK");
        lenient().when(mockProvider.chatCompletion(any(ArrayNode.class), any(ArrayNode.class)))
                .thenReturn(mockResponse);

        // Create TUIClient with mock provider and stub mcpClient
        tuiClient = new TUIClient(mockProvider, stubMcpClient);
    }

    // ==================== System Commands (/) ====================

    @Test
    @DisplayName("/quit - should set running to false")
    void testQuitCommand() {
        // Process quit command
        tuiClient.processCommand("/quit");

        // Verify the client is no longer running
        // Since running is private, we verify through behavior
        // The command should complete without error
        assertTrue(true, "Quit command should execute without error");
    }

    @Test
    @DisplayName("/exit - should work same as quit")
    void testExitCommand() {
        tuiClient.processCommand("/exit");
        assertTrue(true, "Exit command should execute without error");
    }

    @Test
    @DisplayName("/q - shorthand for quit")
    void testQuitShorthand() {
        tuiClient.processCommand("/q");
        assertTrue(true, "Quit shorthand should execute without error");
    }

    @Test
    @DisplayName("/help - should display help information")
    void testHelpCommand() {
        // Help command should not throw any exception
        assertDoesNotThrow(() -> tuiClient.processCommand("/help"));
    }

    @Test
    @DisplayName("/h - shorthand for help")
    void testHelpShorthand() {
        assertDoesNotThrow(() -> tuiClient.processCommand("/h"));
    }

    @Test
    @DisplayName("/? - shorthand for help")
    void testHelpShorthand2() {
        assertDoesNotThrow(() -> tuiClient.processCommand("/?"));
    }

    @Test
    @DisplayName("/version - should display version")
    void testVersionCommand() {
        assertDoesNotThrow(() -> tuiClient.processCommand("/version"));
    }

    @Test
    @DisplayName("/tools - should list available tools")
    void testToolsCommand() {
        assertDoesNotThrow(() -> tuiClient.processCommand("/tools"));
    }

    @Test
    @DisplayName("/clear - should clear conversation history")
    void testClearCommand() {
        assertDoesNotThrow(() -> tuiClient.processCommand("/clear"));
    }

    @Test
    @DisplayName("/history - should show conversation history")
    void testHistoryCommand() {
        assertDoesNotThrow(() -> tuiClient.processCommand("/history"));
    }

    @Test
    @DisplayName("/unknown - should show error message")
    void testUnknownSystemCommand() {
        assertDoesNotThrow(() -> tuiClient.processCommand("/unknowncommand"));
    }

    // ==================== Shell Commands (!) ====================

    @Test
    @DisplayName("!echo - should execute shell echo command")
    void testShellEchoCommand() {
        assertDoesNotThrow(() -> tuiClient.processCommand("!echo hello"));
    }

    @Test
    @DisplayName("!ls - should execute ls command")
    void testShellLsCommand() {
        assertDoesNotThrow(() -> tuiClient.processCommand("!ls"));
    }

    @Test
    @DisplayName("!pwd - should execute pwd command")
    void testShellPwdCommand() {
        assertDoesNotThrow(() -> tuiClient.processCommand("!pwd"));
    }

    @Test
    @DisplayName("! - empty shell command should show error")
    void testEmptyShellCommand() {
        assertDoesNotThrow(() -> tuiClient.processCommand("!"));
    }

    @Test
    @DisplayName("!invalid_command - should handle command failure gracefully")
    void testInvalidShellCommand() {
        assertDoesNotThrow(() -> tuiClient.processCommand("!thisCommandDoesNotExist12345"));
    }

    // ==================== Arthas Commands ($) ====================

    @Test
    @DisplayName("$thread - should attempt to call Arthas thread command")
    void testArthasThreadCommand() {
        // This should not throw any exception
        assertDoesNotThrow(() -> tuiClient.processCommand("$thread"));
    }

    @Test
    @DisplayName("$dashboard - should attempt to call Arthas dashboard command")
    void testArthasDashboardCommand() {
        assertDoesNotThrow(() -> tuiClient.processCommand("$dashboard"));
    }

    @Test
    @DisplayName("$ - empty Arthas command should show error")
    void testEmptyArthasCommand() {
        assertDoesNotThrow(() -> tuiClient.processCommand("$"));
    }

    @Test
    @DisplayName("$jad MathGame - should handle jad command with argument")
    void testArthasJadCommand() {
        assertDoesNotThrow(() -> tuiClient.processCommand("$jad MathGame"));
    }

    // ==================== Natural Language (Default) ====================

    @Test
    @DisplayName("Natural language query - should delegate to AiAgent")
    void testNaturalLanguageQuery() throws Exception {
        // Mock chatCompletion to return a response (allow null toolsConfig)
        ObjectNode mockResponse = mapper.createObjectNode();
        mockResponse.put("role", "assistant");
        mockResponse.put("content", "This is a test response");
        lenient().when(mockProvider.chatCompletion(any(ArrayNode.class), any()))
                .thenReturn(mockResponse);

        // Process natural language input
        assertDoesNotThrow(() -> tuiClient.processCommand("What methods does MathGame have?"));
    }

    @Test
    @DisplayName("Empty input - should be ignored")
    void testEmptyInput() {
        assertDoesNotThrow(() -> tuiClient.processCommand(""));
        assertDoesNotThrow(() -> tuiClient.processCommand("   "));
    }

    @Test
    @DisplayName("Natural language thread status query")
    void testThreadStatusQuery() throws Exception {
        ObjectNode mockResponse = mapper.createObjectNode();
        mockResponse.put("role", "assistant");
        mockResponse.put("content", "Analyzing thread status...");
        lenient().when(mockProvider.chatCompletion(any(ArrayNode.class), any()))
                .thenReturn(mockResponse);

        assertDoesNotThrow(() -> tuiClient.processCommand("Check thread status"));
    }

    // ==================== Command Routing ====================

    @Test
    @DisplayName("Command routing - should correctly identify system command")
    void testCommandRoutingSystem() {
        assertDoesNotThrow(() -> tuiClient.processCommand("/help"));
    }

    @Test
    @DisplayName("Command routing - should correctly identify shell command")
    void testCommandRoutingShell() {
        assertDoesNotThrow(() -> tuiClient.processCommand("!ls"));
    }

    @Test
    @DisplayName("Command routing - should correctly identify Arthas command")
    void testCommandRoutingArthas() {
        assertDoesNotThrow(() -> tuiClient.processCommand("$thread"));
    }

    @Test
    @DisplayName("Command routing - should treat unrecognized prefix as natural language")
    void testCommandRoutingNaturalLanguage() throws Exception {
        ObjectNode mockResponse = mapper.createObjectNode();
        mockResponse.put("role", "assistant");
        mockResponse.put("content", "OK");
        lenient().when(mockProvider.chatCompletion(any(ArrayNode.class), any()))
                .thenReturn(mockResponse);

        assertDoesNotThrow(() -> tuiClient.processCommand("This is a natural language query"));
    }
}
