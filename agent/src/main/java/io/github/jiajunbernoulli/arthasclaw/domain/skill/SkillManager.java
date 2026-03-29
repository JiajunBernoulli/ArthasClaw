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
package io.github.jiajunbernoulli.arthasclaw.domain.skill;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Manages skill lifecycle operations including installation,
 * listing, and removal.
 *
 * Skills are stored in ~/.arthasclaw/skills/
 * Uses lazy loading: metadata is loaded at startup,
 * prompt content is loaded on demand.
 */
public class SkillManager {

    /** User home directory. */
    private static final String HOME_DIR = System.getProperty("user.home");

    /** ArthasClaw directory. */
    private static final String ARTHASCLAW_DIR = HOME_DIR + "/.arthasclaw";

    /** Default skills directory. */
    private static final String DEFAULT_SKILLS_DIR =
            ARTHASCLAW_DIR + "/skills";

    /** HTTP connection timeout in milliseconds. */
    private static final int CONNECT_TIMEOUT = 10000;

    /** HTTP read timeout in milliseconds. */
    private static final int READ_TIMEOUT = 30000;

    /** Path to skills directory. */
    private final Path skillsPath;

    /** Parser for skill files. */
    private final SkillParser parser;

    /** Cache of loaded skills by name. */
    private final Map<String, Skill> skillCache;

    /**
     * Default constructor using ~/.arthasclaw/skills directory.
     */
    public SkillManager() {
        this(DEFAULT_SKILLS_DIR);
    }

    /**
     * Constructor with custom skills directory (for testing).
     *
     * @param skillsDirPath custom path to skills directory
     */
    public SkillManager(final String skillsDirPath) {
        this.skillsPath = Paths.get(skillsDirPath);
        this.parser = new SkillParser();
        this.skillCache = new ConcurrentHashMap<>();

        ensureSkillsDirectory();
        loadAllSkillMetadata();
    }

    /**
     * Ensure the skills directory exists.
     */
    private void ensureSkillsDirectory() {
        try {
            Files.createDirectories(skillsPath);
        } catch (IOException e) {
            throw new RuntimeException(
                    "Failed to create skills directory: " + skillsPath, e);
        }
    }

    /**
     * Load metadata from all skill files into cache (lazy loading).
     * Does not load prompt content.
     */
    private void loadAllSkillMetadata() {
        try (Stream<Path> files = Files.list(skillsPath)) {
            files.filter(this::isSkillFile)
                    .forEach(this::loadSkillMetadata);
        } catch (IOException e) {
            System.err.println(
                    "[-] Failed to load skills: " + e.getMessage());
        }
    }

    /**
     * Check if a file is a skill file.
     *
     * @param path the file path
     * @return true if it's a skill file
     */
    private boolean isSkillFile(final Path path) {
        String name = path.getFileName().toString().toLowerCase();
        return name.endsWith(".md")
                || name.endsWith(".yaml")
                || name.endsWith(".yml");
    }

    /**
     * Load skill metadata from a file.
     *
     * @param path the file path
     */
    private void loadSkillMetadata(final Path path) {
        try {
            Skill skill = parser.parseMetadata(path);
            if (skill.getName() != null && !skill.getName().isEmpty()) {
                skillCache.put(skill.getName(), skill);
            }
        } catch (IOException e) {
            System.err.println(
                    "[-] Failed to parse skill metadata: " + path
                    + " - " + e.getMessage());
        }
    }

    /**
     * Install a skill from a URL or local file path.
     *
     * @param source URL or local file path
     * @return the installed skill
     * @throws IOException if installation fails
     */
    public Skill install(final String source) throws IOException {
        String content;
        String defaultName;

        if (isUrl(source)) {
            content = downloadFromUrl(source);
            defaultName = extractNameFromUrl(source);
        } else {
            Path localPath = Paths.get(source);
            if (!Files.exists(localPath)) {
                throw new IOException("File not found: " + source);
            }
            content = new String(
                    Files.readAllBytes(localPath), StandardCharsets.UTF_8);
            defaultName = localPath.getFileName().toString()
                    .replaceAll("\\.(md|yaml|yml)$", "");
        }

        Skill skill = parser.parseFromString(content, defaultName);
        skill.setSource(source);
        skill.setInstalledAt(LocalDateTime.now());

        String filename = skill.getName() + ".md";
        Path targetPath = skillsPath.resolve(filename);
        Files.write(targetPath, content.getBytes(StandardCharsets.UTF_8));
        skill.setFilePath(targetPath.toString());

        skillCache.put(skill.getName(), skill);

        return skill;
    }

    /**
     * Remove an installed skill.
     *
     * @param name the skill name
     * @return true if removed, false if not found
     */
    public boolean remove(final String name) {
        Skill skill = skillCache.remove(name);
        if (skill == null) {
            return false;
        }

        try {
            Path filePath = Paths.get(skill.getFilePath());
            if (Files.exists(filePath)) {
                Files.delete(filePath);
            }
            return true;
        } catch (IOException e) {
            skillCache.put(name, skill);
            throw new RuntimeException(
                    "Failed to delete skill file: " + e.getMessage(), e);
        }
    }

    /**
     * Get a skill by name. Loads prompt on demand if not already loaded.
     *
     * @param name the skill name
     * @return Optional containing the skill if found
     */
    public Optional<Skill> get(final String name) {
        return Optional.ofNullable(skillCache.get(name));
    }

    /**
     * List all installed skills.
     *
     * @return list of all skills
     */
    public List<Skill> listAll() {
        return new ArrayList<>(skillCache.values());
    }

    /**
     * Get combined prompt from all skills with prompts.
     * Lazy loads prompt content on demand.
     *
     * @return combined prompt string
     */
    public String getCombinedPrompt() {
        StringBuilder combined = new StringBuilder();
        boolean first = true;

        for (Skill skill : skillCache.values()) {
            if (!skill.isPromptLoaded() && skill.getFilePath() != null) {
                try {
                    String prompt = parser.loadPrompt(
                            Paths.get(skill.getFilePath()));
                    if (prompt != null && !prompt.isEmpty()) {
                        skill.setPrompt(prompt);
                    }
                } catch (IOException e) {
                    System.err.println(
                            "[-] Failed to load prompt for skill: "
                            + skill.getName() + " - " + e.getMessage());
                    continue;
                }
            }

            if (skill.hasPrompt()) {
                if (!first) {
                    combined.append("\n\n---\n\n");
                }
                combined.append("## Skill: ")
                        .append(skill.getName())
                        .append("\n\n");
                combined.append(skill.getPrompt());
                first = false;
            }
        }

        return combined.toString();
    }

    /**
     * Get all unique tools from all skills.
     *
     * @return list of tool names
     */
    public List<String> getAllTools() {
        return skillCache.values().stream()
                .filter(Skill::hasTools)
                .flatMap(skill -> skill.getTools().stream())
                .distinct()
                .collect(Collectors.toList());
    }

    /**
     * Check if any skills are installed.
     *
     * @return true if at least one skill is installed
     */
    public boolean hasSkills() {
        return !skillCache.isEmpty();
    }

    /**
     * Get the count of installed skills.
     *
     * @return number of skills
     */
    public int count() {
        return skillCache.size();
    }

    /**
     * Check if a source string is a URL.
     *
     * @param source the source string
     * @return true if it's a URL
     */
    private boolean isUrl(final String source) {
        return source.startsWith("http://")
                || source.startsWith("https://");
    }

    /**
     * Download content from a URL.
     *
     * @param urlStr the URL string
     * @return the downloaded content
     * @throws IOException if download fails
     */
    private String downloadFromUrl(final String urlStr) throws IOException {
        URL url = new URL(urlStr);
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setRequestMethod("GET");
        conn.setConnectTimeout(CONNECT_TIMEOUT);
        conn.setReadTimeout(READ_TIMEOUT);

        int responseCode = conn.getResponseCode();
        if (responseCode != HttpURLConnection.HTTP_OK) {
            throw new IOException("HTTP error: " + responseCode);
        }

        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(conn.getInputStream()))) {
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                sb.append(line).append("\n");
            }
            return sb.toString();
        } finally {
            conn.disconnect();
        }
    }

    /**
     * Extract a skill name from a URL.
     *
     * @param url the URL string
     * @return the extracted name
     */
    private String extractNameFromUrl(final String url) {
        String path = url;
        int queryIndex = path.indexOf('?');
        if (queryIndex > 0) {
            path = path.substring(0, queryIndex);
        }
        int lastSlash = path.lastIndexOf('/');
        if (lastSlash >= 0) {
            path = path.substring(lastSlash + 1);
        }
        return path.replaceAll("\\.(md|yaml|yml)$", "");
    }
}
