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

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.jiajunbernoulli.arthasclaw.domain.Provider;
import io.github.jiajunbernoulli.arthasclaw.infrastructure.config.Config;
import java.io.IOException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Manages conversation context window with automatic sliding window
 * and LLM-based summary compression.
 *
 * <p>When the message count exceeds a configurable threshold, older
 * messages (excluding the system prompt) are summarized into a single
 * compact summary message using the LLM. The most recent messages
 * are preserved to maintain conversational continuity.
 *
 * <p>This prevents unbounded token growth while retaining essential
 * context from earlier in the conversation.
 *
 * <p>The summarization flow:
 * <ol>
 *   <li>Check if message count exceeds the summary threshold</li>
 *   <li>Extract older messages (between system prompt and recent window)</li>
 *   <li>Send them to LLM for summarization</li>
 *   <li>Replace older messages with a single summary message</li>
 *   <li>Keep system prompt at index 0 and recent messages intact</li>
 * </ol>
 */
public class ContextWindowManager {

  private static final Logger log =
      LoggerFactory.getLogger(ContextWindowManager.class);

  private static final String SUMMARY_ROLE = "system";
  private static final String SUMMARY_PREFIX = "[Conversation Summary] ";

  private static final int MAX_SUMMARY_INPUT_CHARS = 12000;

  private static final String SUMMARY_PROMPT =
      "You are a conversation summarizer. Summarize the following "
          + "conversation messages into a concise paragraph that captures "
          + "the key topics discussed, important findings, tool results, "
          + "and any conclusions reached. Keep it under 300 words. "
          + "Focus on factual information and diagnostic results.\n\n"
          + "Messages to summarize:\n";

  private final Provider provider;
  private final ObjectMapper mapper;
  private final int summaryThreshold;
  private final int recentMessageCount;
  private final int maxMessages;

  /**
   * Create a ContextWindowManager with configuration.
   *
   * @param provider LLM provider for generating summaries
   * @param mapper JSON object mapper
   * @param config agent configuration
   */
  public ContextWindowManager(
      Provider provider, ObjectMapper mapper, Config config) {
    this.provider = provider;
    this.mapper = mapper;

    Config.AgentConfig agentConfig = config.getAgent();
    this.summaryThreshold = agentConfig.getContextSummaryThreshold();
    this.recentMessageCount = agentConfig.getContextRecentCount();
    this.maxMessages = agentConfig.getMaxMessages();
  }

  /**
   * Create a ContextWindowManager with explicit parameters.
   * Useful for testing.
   *
   * @param provider LLM provider for generating summaries
   * @param mapper JSON object mapper
   * @param summaryThreshold message count to trigger summarization
   * @param recentMessageCount number of recent messages to keep
   * @param maxMessages hard cap on total message count
   */
  public ContextWindowManager(
      Provider provider,
      ObjectMapper mapper,
      int summaryThreshold,
      int recentMessageCount,
      int maxMessages) {
    this.provider = provider;
    this.mapper = mapper;
    this.summaryThreshold = summaryThreshold;
    this.recentMessageCount = recentMessageCount;
    this.maxMessages = maxMessages;
  }

  /**
   * Manage the context window by applying summarization when needed,
   * then falling back to simple trimming as a safety net.
   *
   * <p>This method should be called before each LLM request to ensure
   * the message array stays within token limits.
   *
   * @param messages the conversation messages array (index 0 = system prompt)
   */
  public void manageContext(ArrayNode messages) {
    if (messages == null || messages.size() <= 1) {
      return;
    }

    // Count non-system messages
    int nonSystemCount = messages.size() - 1;

    // Phase 1: Try LLM summarization if threshold exceeded
    if (nonSystemCount >= summaryThreshold && provider != null) {
      log.info(
          "Context window threshold reached ({}/{}),"
              + " triggering summarization",
          nonSystemCount, summaryThreshold);
      try {
        summarizeOlderMessages(messages);
      } catch (Exception ex) {
        log.warn(
            "Summarization failed, falling back to simple trim: {}",
            ex.getMessage());
      }
    }

    // Phase 2: Hard trim as safety net (always enforce maxMessages)
    hardTrim(messages);
  }

  /**
   * Summarize older messages and replace them with a single summary.
   *
   * <p>Structure after summarization:
   * [system_prompt, summary_message, recent_msg_1, ..., recent_msg_N]
   *
   * @param messages the conversation messages array
   * @throws IOException if LLM call fails
   */
  void summarizeOlderMessages(ArrayNode messages) throws IOException {
    int totalSize = messages.size();
    // Messages layout: [0: system] [1..end-recentCount: old] [end-recentCount+1..end: recent]
    int keepFromEnd = Math.min(recentMessageCount, totalSize - 1);
    int oldStart = 1; // First non-system message
    int oldEnd = totalSize - keepFromEnd; // Exclusive

    if (oldEnd <= oldStart) {
      log.debug("Not enough old messages to summarize");
      return;
    }

    // Check if the first old message is already a summary
    // (avoid re-summarizing summaries too aggressively)
    JsonNode firstOld = messages.get(oldStart);
    boolean hasPreviousSummary = false;
    if (SUMMARY_ROLE.equals(firstOld.path("role").asText())
        && firstOld.path("content").asText()
            .startsWith(SUMMARY_PREFIX)) {
      hasPreviousSummary = true;
    }

    // Build text from old messages for summarization
    StringBuilder oldConversation = new StringBuilder();
    if (hasPreviousSummary) {
      // Include previous summary content as context
      String prevSummary = firstOld.path("content").asText();
      oldConversation.append("Previous summary:\n")
          .append(prevSummary).append("\n\n")
          .append("New messages since last summary:\n");
      oldStart = 2; // Skip the previous summary message
      if (oldEnd <= oldStart) {
        log.debug("Only summary exists in old region, skipping");
        return;
      }
    }

    for (int i = oldStart; i < oldEnd; i++) {
      JsonNode msg = messages.get(i);
      String role = msg.path("role").asText();
      String content = msg.path("content").asText("");

      // Skip empty content
      if (content.isEmpty()) {
        continue;
      }

      // Truncate very long tool results for summary input
      if ("tool".equals(role) && content.length() > 500) {
        content = content.substring(0, 500) + "... [truncated]";
      }

      // Truncate individual messages to keep summary prompt size bounded
      if (content.length() > 1000) {
        content = content.substring(0, 1000) + "... [truncated]";
      }

      String entry = role + ": " + content + "\n";
      // Stop appending if we would exceed the max summary input size
      if (oldConversation.length() + entry.length() > MAX_SUMMARY_INPUT_CHARS) {
        oldConversation.append("... [additional messages truncated for summary]\n");
        break;
      }
      oldConversation.append(entry);
    }

    String conversationText = oldConversation.toString();
    if (conversationText.trim().isEmpty()) {
      log.debug("No substantive content to summarize");
      return;
    }

    // Call LLM to generate summary
    String summary = generateSummary(conversationText);
    if (summary == null || summary.trim().isEmpty()) {
      log.warn("LLM returned empty summary, skipping compression");
      return;
    }

    // Build the summary message
    ObjectNode summaryMsg = mapper.createObjectNode();
    summaryMsg.put("role", SUMMARY_ROLE);
    summaryMsg.put("content", SUMMARY_PREFIX + summary);

    // Calculate how many messages to remove
    // Remove from index 1 up to (oldEnd - 1) inclusive,
    // which always includes the previous summary when hasPreviousSummary is true.
    int removeCount = oldEnd - 1;
    for (int i = 0; i < removeCount; i++) {
      messages.remove(1);
    }

    // Insert summary at index 1 (after system prompt)
    messages.insert(1, summaryMsg);

    log.info(
        "Context compressed: removed {} messages, inserted summary"
            + " ({} chars). Total messages now: {}",
        removeCount, summary.length(), messages.size());
  }

  /**
   * Generate a summary of the conversation text using LLM.
   *
   * @param conversationText the formatted conversation to summarize
   * @return summary string, or null if generation fails
   * @throws IOException if LLM call fails
   */
  String generateSummary(String conversationText) throws IOException {
    ArrayNode summaryMessages = mapper.createArrayNode();
    ObjectNode promptMsg = mapper.createObjectNode();
    promptMsg.put("role", "user");
    promptMsg.put("content", SUMMARY_PROMPT + conversationText);
    summaryMessages.add(promptMsg);

    ObjectNode response = provider.chatCompletion(summaryMessages, null);
    if (response != null && response.hasNonNull("content")) {
      String summary = response.get("content").asText().trim();
      if (!summary.isEmpty()) {
        return summary;
      }
      log.warn("LLM summary response content is empty");
    } else {
      log.warn("LLM summary response missing content field (response={})",
          response == null ? "null" : response.toPrettyString());
    }
    return null;
  }

  /**
   * Hard trim messages to enforce the maximum message count.
   * This is a safety net in case summarization fails or is disabled.
   * Removes oldest non-system messages from index 1.
   *
   * @param messages the conversation messages array
   */
  void hardTrim(ArrayNode messages) {
    while (messages.size() > maxMessages && messages.size() > 1) {
      // Skip index 1 if it's a summary message
      if (messages.size() > 2) {
        JsonNode candidate = messages.get(1);
        boolean isSummary = SUMMARY_ROLE.equals(
            candidate.path("role").asText())
            && candidate.path("content").asText()
                .startsWith(SUMMARY_PREFIX);
        if (isSummary && messages.size() > 2) {
          // Remove from index 2 instead to preserve summary
          messages.remove(2);
          continue;
        }
      }
      messages.remove(1);
    }
  }

  /**
   * Get the summary threshold.
   *
   * @return the threshold that triggers summarization
   */
  public int getSummaryThreshold() {
    return summaryThreshold;
  }

  /**
   * Get the recent message count to preserve.
   *
   * @return number of recent messages kept after summarization
   */
  public int getRecentMessageCount() {
    return recentMessageCount;
  }
}
