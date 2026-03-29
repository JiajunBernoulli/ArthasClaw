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
package io.github.jiajunbernoulli.arthasclaw.infrastructure.memory;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Manages session history and memory (facts) storage.
 *
 * Sessions are stored in ~/.arthasclaw/sessions/sess_xxx.json
 * Memory (facts) are stored in ~/.arthasclaw/memory/facts.json
 */
public class MemoryManager {

    /** Logger instance. */
    private static final Logger LOG =
            LoggerFactory.getLogger(MemoryManager.class);

    /** User home directory. */
    private static final String HOME_DIR = System.getProperty("user.home");

    /** ArthasClaw directory. */
    private static final String ARTHASCLAW_DIR = HOME_DIR + "/.arthasclaw";

    /** Sessions directory. */
    private static final String SESSIONS_DIR =
            ARTHASCLAW_DIR + "/sessions";

    /** Memory directory. */
    private static final String MEMORY_DIR =
            ARTHASCLAW_DIR + "/memory";

    /** Facts file path. */
    private static final String FACTS_FILE =
            MEMORY_DIR + "/facts.json";

    /** Pattern for memory-related keywords. */
    private static final Pattern MEMORY_KEYWORDS = Pattern.compile(
            "(记住|记得|别忘了|不要忘记|记一下|mark|remember|note|"
            + "don't forget)",
            Pattern.CASE_INSENSITIVE
    );

    /** Milliseconds per day. */
    private static final long MILLIS_PER_DAY = 24L * 60 * 60 * 1000;

    /** Maximum truncate length for logging. */
    private static final int MAX_TRUNCATE_LENGTH = 50;

    /** JSON object mapper. */
    private final ObjectMapper mapper;

    /** Path to sessions directory. */
    private final Path sessionsDir;

    /** Path to memory directory. */
    private final Path memoryDir;

    /** Path to facts file. */
    private final Path factsFile;

    /** Current session ID. */
    private String currentSessionId;

    /** Current session file path. */
    private Path currentSessionFile;

    /** Current session JSON object. */
    private ObjectNode currentSession;

    /** Current session messages array. */
    private ArrayNode sessionMessages;

    /**
     * Create a new MemoryManager.
     */
    public MemoryManager() {
        this.mapper = new ObjectMapper();
        this.sessionsDir = Paths.get(SESSIONS_DIR);
        this.memoryDir = Paths.get(MEMORY_DIR);
        this.factsFile = Paths.get(FACTS_FILE);

        ensureDirectories();
    }

    /**
     * Ensure required directories exist.
     */
    private void ensureDirectories() {
        try {
            Files.createDirectories(sessionsDir);
            Files.createDirectories(memoryDir);
        } catch (IOException e) {
            LOG.error("Failed to create directories: {}", e.getMessage());
        }
    }

    // ==================== Session Management ====================

    /**
     * Start a new session.
     *
     * @param sessionId the session ID (e.g., "sess_a1b2c3d4")
     */
    public void startSession(final String sessionId) {
        this.currentSessionId = sessionId;
        this.currentSessionFile = sessionsDir.resolve(sessionId + ".json");

        currentSession = mapper.createObjectNode();
        currentSession.put("sessionId", sessionId);
        currentSession.put("startedAt", Instant.now().toString());
        currentSession.put("endedAt", (String) null);
        currentSession.put("summary", (String) null);
        sessionMessages = mapper.createArrayNode();
        currentSession.set("messages", sessionMessages);

        saveSession();

        LOG.info("Session started: {}", sessionId);
    }

    /**
     * Add a message to the current session.
     *
     * @param role    the message role (user, assistant, tool)
     * @param content the message content
     */
    public void addMessage(
            final String role,
            final String content) {
        if (currentSession == null || sessionMessages == null) {
            LOG.warn("No active session, cannot add message");
            return;
        }

        ObjectNode message = mapper.createObjectNode();
        message.put("role", role);
        message.put("content", content);
        message.put("timestamp", Instant.now().toString());

        sessionMessages.add(message);

        saveSession();

        LOG.debug("Message added to session: role={}, length={}",
                role, content.length());
    }

    /**
     * End the current session with an optional summary.
     *
     * @param summary the session summary (can be null)
     */
    public void endSession(final String summary) {
        if (currentSession == null) {
            return;
        }

        currentSession.put("endedAt", Instant.now().toString());
        if (summary != null && !summary.isEmpty()) {
            currentSession.put("summary", summary);
        }

        saveSession();

        LOG.info("Session ended: {}, messages={}",
                currentSessionId, sessionMessages.size());

        currentSession = null;
        sessionMessages = null;
        currentSessionId = null;
        currentSessionFile = null;
    }

    /**
     * Save current session to file.
     */
    private void saveSession() {
        if (currentSessionFile == null) {
            return;
        }

        try {
            mapper.writerWithDefaultPrettyPrinter()
                    .writeValue(currentSessionFile.toFile(), currentSession);
        } catch (IOException e) {
            LOG.error("Failed to save session: {}", e.getMessage());
        }
    }

    /**
     * Get the current session ID.
     *
     * @return current session ID
     */
    public String getCurrentSessionId() {
        return currentSessionId;
    }

    // ==================== Memory (Facts) Management ====================

    /**
     * Check if the user message contains memory-related keywords.
     *
     * @param userMessage the user message
     * @return true if the message should trigger memory extraction
     */
    public boolean shouldExtractMemory(final String userMessage) {
        if (userMessage == null || userMessage.isEmpty()) {
            return false;
        }
        return MEMORY_KEYWORDS.matcher(userMessage).find();
    }

    /**
     * Add a fact to memory.
     * If a fact with the same key exists, it will be updated.
     *
     * @param key   the fact key (e.g., "rootCause:thread-deadlock")
     * @param value the fact value
     * @return the fact ID (new or existing)
     */
    public synchronized String addFact(
            final String key,
            final String value) {
        ObjectNode facts = loadFacts();
        ArrayNode factsArray = facts.has("facts")
                ? (ArrayNode) facts.get("facts")
                : mapper.createArrayNode();

        for (int i = 0; i < factsArray.size(); i++) {
            JsonNode existingFact = factsArray.get(i);
            if (existingFact.has("key")
                    && key.equals(existingFact.get("key").asText())) {
                ObjectNode updatedFact = (ObjectNode) existingFact;
                updatedFact.put("value", value);
                updatedFact.put("updatedAt", Instant.now().toString());
                factsArray.set(i, updatedFact);
                facts.set("facts", factsArray);
                saveFacts(facts);
                LOG.info("Fact updated: key={}, value={}",
                        key, truncate(value, MAX_TRUNCATE_LENGTH));
                return existingFact.get("id").asText();
            }
        }

        String factId = UUID.randomUUID().toString().substring(0, 8);
        ObjectNode fact = mapper.createObjectNode();
        fact.put("id", factId);
        fact.put("category", "conclusion");
        fact.put("key", key);
        fact.put("value", value);
        fact.put("createdAt", Instant.now().toString());

        factsArray.add(fact);
        facts.set("facts", factsArray);

        saveFacts(facts);

        LOG.info("Fact saved: key={}, value={}",
                key, truncate(value, MAX_TRUNCATE_LENGTH));
        return factId;
    }

    /**
     * Load all facts from file.
     *
     * @return the facts object
     */
    private ObjectNode loadFacts() {
        if (!Files.exists(factsFile)) {
            ObjectNode empty = mapper.createObjectNode();
            empty.set("facts", mapper.createArrayNode());
            return empty;
        }

        try {
            return (ObjectNode) mapper.readTree(factsFile.toFile());
        } catch (IOException e) {
            LOG.error("Failed to load facts: {}", e.getMessage());
            ObjectNode empty = mapper.createObjectNode();
            empty.set("facts", mapper.createArrayNode());
            return empty;
        }
    }

    /**
     * Save facts to file.
     *
     * @param facts the facts object to save
     */
    private void saveFacts(final ObjectNode facts) {
        try {
            mapper.writerWithDefaultPrettyPrinter()
                    .writeValue(factsFile.toFile(), facts);
        } catch (IOException e) {
            LOG.error("Failed to save facts: {}", e.getMessage());
        }
    }

    /**
     * Get all facts as a formatted string for context injection.
     *
     * @param maxLength maximum length of the returned string
     * @return formatted facts string
     */
    public String getFactsContext(final int maxLength) {
        ObjectNode facts = loadFacts();
        if (!facts.has("facts") || facts.get("facts").size() == 0) {
            return "";
        }

        StringBuilder sb = new StringBuilder();
        sb.append("## Previous Diagnostic Conclusions\n\n");

        ArrayNode factsArray = (ArrayNode) facts.get("facts");
        for (int i = 0; i < factsArray.size(); i++) {
            JsonNode fact = factsArray.get(i);
            sb.append("- ")
              .append(fact.get("key").asText())
              .append(": ")
              .append(fact.get("value").asText())
              .append("\n");
        }

        String result = sb.toString();
        if (result.length() > maxLength) {
            result = result.substring(0, maxLength) + "\n... (truncated)";
        }

        return result;
    }

    /**
     * Get recent sessions for context (limited by count).
     *
     * @param count maximum number of sessions to load
     * @return formatted sessions string
     */
    public String getRecentSessionsContext(final int count) {
        try {
            List<Path> sessionFiles = new ArrayList<>();
            Files.list(sessionsDir)
                    .filter(p -> p.toString().endsWith(".json"))
                    .sorted((a, b) -> {
                        try {
                            return Files.getLastModifiedTime(b)
                                    .compareTo(Files.getLastModifiedTime(a));
                        } catch (IOException e) {
                            return 0;
                        }
                    })
                    .limit(count)
                    .forEach(sessionFiles::add);

            if (sessionFiles.isEmpty()) {
                return "";
            }

            StringBuilder sb = new StringBuilder();
            sb.append("## Recent Sessions\n\n");

            for (Path file : sessionFiles) {
                try {
                    ObjectNode session = (ObjectNode) mapper
                            .readTree(file.toFile());
                    String sessionId = session.get("sessionId").asText();
                    String summary = session.has("summary")
                            ? session.get("summary").asText()
                            : "No summary";

                    String date = "Unknown date";
                    if (session.has("startedAt")) {
                        try {
                            Instant instant = Instant.parse(
                                    session.get("startedAt").asText());
                            date = LocalDateTime.ofInstant(
                                    instant, ZoneId.systemDefault())
                                    .format(DateTimeFormatter.ofPattern(
                                            "yyyy-MM-dd HH:mm"));
                        } catch (Exception ignored) {
                            // Ignore parsing errors
                        }
                    }

                    sb.append("- ")
                      .append(date)
                      .append(": ")
                      .append(summary)
                      .append("\n");
                } catch (Exception ignored) {
                    // Ignore parsing errors
                }
            }

            return sb.toString();
        } catch (IOException e) {
            LOG.error("Failed to load recent sessions: {}", e.getMessage());
            return "";
        }
    }

    // ==================== Cleanup ====================

    /**
     * Clean up old sessions older than specified days.
     *
     * @param daysToKeep number of days to keep
     */
    public void cleanupOldSessions(final int daysToKeep) {
        long cutoffTime = System.currentTimeMillis()
                - (daysToKeep * MILLIS_PER_DAY);

        try {
            Files.list(sessionsDir)
                    .filter(p -> p.toString().endsWith(".json"))
                    .filter(p -> {
                        try {
                            return Files.getLastModifiedTime(p)
                                    .toMillis() < cutoffTime;
                        } catch (IOException e) {
                            return false;
                        }
                    })
                    .forEach(p -> {
                        try {
                            Files.delete(p);
                            LOG.debug(
                                "Deleted old session: {}",
                                p.getFileName());
                        } catch (IOException e) {
                            LOG.warn(
                                "Failed to delete old session: {}", p);
                        }
                    });
        } catch (IOException e) {
            LOG.error("Failed to cleanup old sessions: {}", e.getMessage());
        }
    }

    // ==================== Helpers ====================

    /**
     * Truncate a string to a maximum length.
     *
     * @param str    the string to truncate
     * @param maxLen maximum length
     * @return truncated string
     */
    private String truncate(final String str, final int maxLen) {
        if (str == null) {
            return "";
        }
        return str.length() > maxLen
                ? str.substring(0, maxLen) + "..."
                : str;
    }
}
