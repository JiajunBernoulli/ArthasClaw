/*
 * Copyright 2026 Jiajun Bernoulli (jiajunbernoulli@users.noreply.github.com)
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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.jiajunbernoulli.arthasclaw.domain.Provider;
import java.io.IOException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Unit tests for ContextWindowManager.
 */
@ExtendWith(MockitoExtension.class)
class ContextWindowManagerTest {

  @Mock
  private Provider mockProvider;

  private ObjectMapper mapper;
  private ContextWindowManager manager;

  // Test constants
  private static final int SUMMARY_THRESHOLD = 5;
  private static final int RECENT_COUNT = 2;
  private static final int MAX_MESSAGES = 10;

  @BeforeEach
  void setUp() {
    mapper = new ObjectMapper();
    manager = new ContextWindowManager(
        mockProvider, mapper,
        SUMMARY_THRESHOLD, RECENT_COUNT, MAX_MESSAGES);
  }

  @Test
  void manageContextDoesNothingForEmptyMessages() {
    ArrayNode messages = mapper.createArrayNode();
    manager.manageContext(messages);
    assertEquals(0, messages.size());
  }

  @Test
  void manageContextDoesNothingForOnlySystemPrompt() {
    ArrayNode messages = createMessagesWithSystem(0);
    assertEquals(1, messages.size());
    manager.manageContext(messages);
    assertEquals(1, messages.size());
  }

  @Test
  void manageContextDoesNothingBelowThreshold() {
    // 1 system + 3 user messages = 4 total, threshold is 5
    ArrayNode messages = createMessagesWithSystem(3);
    assertEquals(4, messages.size());
    manager.manageContext(messages);
    // Should remain unchanged
    assertEquals(4, messages.size());
  }

  @Test
  void manageContextTriggersSummarizationAtThreshold()
      throws IOException {
    // 1 system + 6 user/assistant messages, threshold = 5
    ArrayNode messages = createMessagesWithSystem(6);
    assertEquals(7, messages.size());

    // Mock LLM response for summarization
    ObjectNode summaryResponse = mapper.createObjectNode();
    summaryResponse.put("role", "assistant");
    summaryResponse.put("content",
        "User discussed thread analysis and memory issues.");
    when(mockProvider.chatCompletion(any(), any()))
        .thenReturn(summaryResponse);

    manager.manageContext(messages);

    // After summarization: system + summary + 2 recent = 4
    assertEquals(4, messages.size());
    // First message should be system
    assertEquals("system",
        messages.get(0).get("role").asText());
    // Second should be summary
    assertTrue(messages.get(1).get("content").asText()
        .startsWith("[Conversation Summary]"));
  }

  @Test
  void manageContextPreservesRecentMessages()
      throws IOException {
    // Create messages with identifiable content
    ArrayNode messages = mapper.createArrayNode();
    // System prompt
    ObjectNode sysMsg = mapper.createObjectNode();
    sysMsg.put("role", "system");
    sysMsg.put("content", "You are an assistant.");
    messages.add(sysMsg);

    // Add 8 user messages with numbered content
    for (int i = 1; i <= 8; i++) {
      ObjectNode userMsg = mapper.createObjectNode();
      userMsg.put("role", "user");
      userMsg.put("content", "Message number " + i);
      messages.add(userMsg);
    }

    // Mock summarization
    ObjectNode summaryResponse = mapper.createObjectNode();
    summaryResponse.put("role", "assistant");
    summaryResponse.put("content", "Summary of messages 1-6.");
    when(mockProvider.chatCompletion(any(), any()))
        .thenReturn(summaryResponse);

    manager.manageContext(messages);

    // Should keep system + summary + 2 recent messages
    assertEquals(4, messages.size());
    // Last two should be the most recent messages
    assertEquals("Message number 7",
        messages.get(2).get("content").asText());
    assertEquals("Message number 8",
        messages.get(3).get("content").asText());
  }

  @Test
  void manageContextFallsBackToHardTrimOnFailure()
      throws IOException {
    // Create more than maxMessages
    ArrayNode messages = createMessagesWithSystem(12);
    assertEquals(13, messages.size());

    // Mock LLM failure
    when(mockProvider.chatCompletion(any(), any()))
        .thenThrow(new IOException("LLM unavailable"));

    manager.manageContext(messages);

    // Should fall back to hard trim at maxMessages
    assertTrue(messages.size() <= MAX_MESSAGES);
  }

  @Test
  void hardTrimEnforcesMaxMessages() {
    // Create messages exceeding max
    ArrayNode messages = createMessagesWithSystem(15);
    assertEquals(16, messages.size());

    manager.hardTrim(messages);

    assertTrue(messages.size() <= MAX_MESSAGES);
    // System message should be preserved
    assertEquals("system",
        messages.get(0).get("role").asText());
  }

  @Test
  void summarizeOlderMessagesHandlesExistingSummary()
      throws IOException {
    ArrayNode messages = mapper.createArrayNode();

    // System prompt
    ObjectNode sysMsg = mapper.createObjectNode();
    sysMsg.put("role", "system");
    sysMsg.put("content", "You are an assistant.");
    messages.add(sysMsg);

    // Existing summary message
    ObjectNode existingSummary = mapper.createObjectNode();
    existingSummary.put("role", "system");
    existingSummary.put("content",
        "[Conversation Summary] Previous discussion about threads.");
    messages.add(existingSummary);

    // Add more messages after the summary
    for (int i = 1; i <= 6; i++) {
      ObjectNode userMsg = mapper.createObjectNode();
      userMsg.put("role", "user");
      userMsg.put("content", "New message " + i);
      messages.add(userMsg);
    }

    // Mock summarization
    ObjectNode summaryResponse = mapper.createObjectNode();
    summaryResponse.put("role", "assistant");
    summaryResponse.put("content",
        "Combined summary of threads and new messages.");
    when(mockProvider.chatCompletion(any(), any()))
        .thenReturn(summaryResponse);

    manager.summarizeOlderMessages(messages);

    // Should have system + new summary + 2 recent
    assertEquals(4, messages.size());
    assertTrue(messages.get(1).get("content").asText()
        .startsWith("[Conversation Summary]"));
  }

  @Test
  void manageContextWithNullProviderSkipsSummarization() {
    ContextWindowManager noProviderManager =
        new ContextWindowManager(
            null, mapper,
            SUMMARY_THRESHOLD, RECENT_COUNT, MAX_MESSAGES);

    ArrayNode messages = createMessagesWithSystem(12);
    assertEquals(13, messages.size());

    noProviderManager.manageContext(messages);

    // Should only do hard trim, no summarization
    assertTrue(messages.size() <= MAX_MESSAGES);
  }

  @Test
  void generateSummaryCallsProvider() throws IOException {
    ObjectNode response = mapper.createObjectNode();
    response.put("role", "assistant");
    response.put("content", "This is a test summary.");
    when(mockProvider.chatCompletion(any(), any()))
        .thenReturn(response);

    String summary = manager.generateSummary("user: hello\n");
    assertEquals("This is a test summary.", summary);
  }

  @Test
  void generateSummaryReturnsNullOnEmptyResponse()
      throws IOException {
    ObjectNode response = mapper.createObjectNode();
    response.put("role", "assistant");
    // No "content" field
    when(mockProvider.chatCompletion(any(), any()))
        .thenReturn(response);

    String summary = manager.generateSummary("user: hello\n");
    assertTrue(summary == null);
  }

  @Test
  void manageContextHandlesToolMessages() throws IOException {
    ArrayNode messages = mapper.createArrayNode();

    // System prompt
    ObjectNode sysMsg = mapper.createObjectNode();
    sysMsg.put("role", "system");
    sysMsg.put("content", "You are an assistant.");
    messages.add(sysMsg);

    // Mix of user, assistant, and tool messages
    for (int i = 0; i < 3; i++) {
      ObjectNode userMsg = mapper.createObjectNode();
      userMsg.put("role", "user");
      userMsg.put("content", "Analyze thread " + i);
      messages.add(userMsg);

      ObjectNode assistantMsg = mapper.createObjectNode();
      assistantMsg.put("role", "assistant");
      assistantMsg.put("content", "Calling thread tool...");
      messages.add(assistantMsg);

      ObjectNode toolMsg = mapper.createObjectNode();
      toolMsg.put("role", "tool");
      toolMsg.put("content", "Thread " + i + " result: OK");
      messages.add(toolMsg);
    }

    // 1 system + 9 conversation = 10 total, threshold = 5
    assertEquals(10, messages.size());

    ObjectNode summaryResponse = mapper.createObjectNode();
    summaryResponse.put("role", "assistant");
    summaryResponse.put("content",
        "Analyzed threads 0-2, all OK.");
    when(mockProvider.chatCompletion(any(), any()))
        .thenReturn(summaryResponse);

    manager.manageContext(messages);

    // system + summary + 2 recent
    assertEquals(4, messages.size());
    assertEquals("system",
        messages.get(0).get("role").asText());
  }

  @Test
  void gettersReturnCorrectValues() {
    assertEquals(SUMMARY_THRESHOLD,
        manager.getSummaryThreshold());
    assertEquals(RECENT_COUNT,
        manager.getRecentMessageCount());
  }

  // ==================== Helper Methods ====================

  /**
   * Create a messages array with a system prompt and N
   * alternating user/assistant messages.
   */
  private ArrayNode createMessagesWithSystem(int messageCount) {
    ArrayNode messages = mapper.createArrayNode();

    // System prompt
    ObjectNode sysMsg = mapper.createObjectNode();
    sysMsg.put("role", "system");
    sysMsg.put("content", "You are an assistant.");
    messages.add(sysMsg);

    // Add alternating user/assistant messages
    for (int i = 0; i < messageCount; i++) {
      ObjectNode msg = mapper.createObjectNode();
      if (i % 2 == 0) {
        msg.put("role", "user");
        msg.put("content", "User message " + i);
      } else {
        msg.put("role", "assistant");
        msg.put("content", "Assistant response " + i);
      }
      messages.add(msg);
    }

    return messages;
  }
}
