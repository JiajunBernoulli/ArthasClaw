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
package io.github.jiajunbernoulli.arthasclaw.infrastructure.llm;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import io.github.jiajunbernoulli.arthasclaw.domain.Provider;

import java.io.IOException;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for Provider interface using MockProvider.
 */
class ProviderTest {

    private Provider provider;
    private ObjectMapper mapper;

    @BeforeEach
    void setUp() {
        provider = new MockProvider();
        mapper = new ObjectMapper();
    }

    @AfterEach
    void tearDown() {
        provider.close();
    }

    @Test
    @DisplayName("Should return response with assistant role")
    void testResponseRole() throws IOException {
        ArrayNode messages = createMessages("Check memory usage");
        ObjectNode response = provider.chatCompletion(messages, null);

        assertEquals("assistant", response.get("role").asText());
    }

    @Test
    @DisplayName("Should echo user message in mock response")
    void testEchoUserMessage() throws IOException {
        String userMessage = "Analyze heap memory";
        ArrayNode messages = createMessages(userMessage);
        ObjectNode response = provider.chatCompletion(messages, null);

        String content = response.get("content").asText();
        assertTrue(content.contains(userMessage));
    }

    @Test
    @DisplayName("Should handle memory analysis query")
    void testMemoryAnalysisQuery() throws IOException {
        String query = "Check JVM memory usage";
        ArrayNode messages = createMessages(query);
        ObjectNode response = provider.chatCompletion(messages, null);

        assertNotNull(response.get("content"));
        assertTrue(response.get("content").asText().length() > 0);
    }

    @Test
    @DisplayName("Should handle heap dump query")
    void testHeapDumpQuery() throws IOException {
        String query = "Generate heap dump file";
        ArrayNode messages = createMessages(query);
        ObjectNode response = provider.chatCompletion(messages, null);

        String content = response.get("content").asText();
        assertTrue(content.contains("[Mock]"));
    }

    @Test
    @DisplayName("Should handle thread analysis query")
    void testThreadAnalysisQuery() throws IOException {
        String query = "Check thread status";
        ArrayNode messages = createMessages(query);
        ObjectNode response = provider.chatCompletion(messages, null);

        assertNotNull(response.get("role"));
        assertEquals("assistant", response.get("role").asText());
    }

    @Test
    @DisplayName("Should handle GC analysis query")
    void testGCAnalysisQuery() throws IOException {
        String query = "Analyze GC logs";
        ArrayNode messages = createMessages(query);
        ObjectNode response = provider.chatCompletion(messages, null);

        assertTrue(response.has("content"));
        String content = response.get("content").asText();
        assertTrue(content.contains(query));
    }

    @Test
    @DisplayName("Should handle classloader query")
    void testClassloaderQuery() throws IOException {
        String query = "Check classloader info";
        ArrayNode messages = createMessages(query);
        ObjectNode response = provider.chatCompletion(messages, null);

        assertNotNull(response);
        assertTrue(response.has("content"));
    }

    @Test
    @DisplayName("Should work with multiple messages in conversation")
    void testMultipleMessages() throws IOException {
        ArrayNode messages = mapper.createArrayNode();

        ObjectNode sysMsg = mapper.createObjectNode();
        sysMsg.put("role", "system");
        sysMsg.put("content", "You are a JVM diagnostic assistant.");
        messages.add(sysMsg);

        ObjectNode userMsg1 = mapper.createObjectNode();
        userMsg1.put("role", "user");
        userMsg1.put("content", "Check memory");
        messages.add(userMsg1);

        ObjectNode assistantMsg = mapper.createObjectNode();
        assistantMsg.put("role", "assistant");
        assistantMsg.put("content", "Memory usage is normal");
        messages.add(assistantMsg);

        ObjectNode userMsg2 = mapper.createObjectNode();
        userMsg2.put("role", "user");
        userMsg2.put("content", "Analyze heap memory in detail");
        messages.add(userMsg2);

        ObjectNode response = provider.chatCompletion(messages, null);

        String content = response.get("content").asText();
        assertTrue(content.contains("Analyze heap memory in detail"));
    }

    @Test
    @DisplayName("Should handle tools config parameter (even if unused in mock)")
    void testWithToolsConfig() throws IOException {
        ArrayNode messages = createMessages("Use arthas tools for analysis");
        ArrayNode toolsConfig = createMockToolsConfig();

        ObjectNode response = provider.chatCompletion(messages, toolsConfig);

        assertNotNull(response);
        assertEquals("assistant", response.get("role").asText());
    }

    @Test
    @DisplayName("Provider should be reusable for multiple calls")
    void testMultipleCalls() throws IOException {
        ArrayNode messages1 = createMessages("First query");
        ObjectNode response1 = provider.chatCompletion(messages1, null);
        assertTrue(response1.get("content").asText().contains("First query"));

        ArrayNode messages2 = createMessages("Second query");
        ObjectNode response2 = provider.chatCompletion(messages2, null);
        assertTrue(response2.get("content").asText().contains("Second query"));
    }

    // Helper methods

    private ArrayNode createMessages(String userContent) {
        ArrayNode messages = mapper.createArrayNode();

        ObjectNode sysMsg = mapper.createObjectNode();
        sysMsg.put("role", "system");
        sysMsg.put("content", "You are a JVM diagnostic assistant.");
        messages.add(sysMsg);

        ObjectNode userMsg = mapper.createObjectNode();
        userMsg.put("role", "user");
        userMsg.put("content", userContent);
        messages.add(userMsg);

        return messages;
    }

    private ArrayNode createMockToolsConfig() {
        ArrayNode tools = mapper.createArrayNode();

        ObjectNode tool = mapper.createObjectNode();
        tool.put("type", "function");

        ObjectNode function = mapper.createObjectNode();
        function.put("name", "memory");
        function.put("description", "Check JVM memory info");

        ObjectNode params = mapper.createObjectNode();
        params.put("type", "object");
        function.set("parameters", params);

        tool.set("function", function);
        tools.add(tool);

        return tools;
    }
}
