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
package io.github.jiajunbernoulli.arthasclaw.skill;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
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
 * Manages skill lifecycle operations including installation, listing,
 * enabling/disabling, and removal. Skills are stored in ~/.arthasclaw/skills/
 */
public class SkillManager {

    private static final String HOME_DIR = System.getProperty("user.home");
    private static final String ARTHASCLAW_DIR = HOME_DIR + "/.arthasclaw";
    private static final String DEFAULT_SKILLS_DIR = ARTHASCLAW_DIR + "/skills";

    private final Path skillsPath;
    private final SkillParser parser;
    private final ObjectMapper jsonMapper;
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
    public SkillManager(String skillsDirPath) {
        this.skillsPath = Paths.get(skillsDirPath);
        this.parser = new SkillParser();
        this.jsonMapper = new ObjectMapper();
        this.jsonMapper.enable(SerializationFeature.INDENT_OUTPUT);
        this.skillCache = new ConcurrentHashMap<>();

        ensureSkillsDirectory();
        loadAllSkills();
    }

    /**
     * Ensure the skills directory exists.
     */
    private void ensureSkillsDirectory() {
        try {
            Files.createDirectories(skillsPath);
        } catch (IOException e) {
            throw new RuntimeException("Failed to create skills directory: " + skillsPath, e);
        }
    }

    /**
     * Load all skills from the skills directory into cache.
     */
    private void loadAllSkills() {
        try (Stream<Path> files = Files.list(skillsPath)) {
            files.filter(this::isSkillFile)
                    .forEach(this::loadSkillFromFile);
        } catch (IOException e) {
            System.err.println("[-] Failed to load skills: " + e.getMessage());
        }
    }

    private boolean isSkillFile(Path path) {
        String name = path.getFileName().toString().toLowerCase();
        return name.endsWith(".md") || name.endsWith(".yaml") || name.endsWith(".yml");
    }

    private void loadSkillFromFile(Path path) {
        try {
            Skill skill = parser.parse(path);
            if (skill.getName() != null && !skill.getName().isEmpty()) {
                skillCache.put(skill.getName(), skill);
            }
        } catch (IOException e) {
            System.err.println("[-] Failed to parse skill: " + path + " - " + e.getMessage());
        }
    }

    /**
     * Install a skill from a URL or local file path.
     *
     * @param source URL or local file path
     * @return the installed skill
     * @throws IOException if installation fails
     */
    public Skill install(String source) throws IOException {
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
            content = new String(Files.readAllBytes(localPath), "UTF-8");
            defaultName = localPath.getFileName().toString()
                    .replaceAll("\\.(md|yaml|yml)$", "");
        }

        Skill skill = parser.parseFromString(content, defaultName);
        skill.setSource(source);
        skill.setInstalledAt(LocalDateTime.now());

        // Save to skills directory
        String filename = skill.getName() + ".md";
        Path targetPath = skillsPath.resolve(filename);
        Files.write(targetPath, content.getBytes("UTF-8"));
        skill.setFilePath(targetPath.toString());

        // Update cache
        skillCache.put(skill.getName(), skill);

        return skill;
    }

    /**
     * Remove an installed skill.
     *
     * @param name the skill name
     * @return true if removed, false if not found
     */
    public boolean remove(String name) {
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
            // Re-add to cache if deletion failed
            skillCache.put(name, skill);
            throw new RuntimeException("Failed to delete skill file: " + e.getMessage(), e);
        }
    }

    /**
     * Enable a disabled skill.
     *
     * @param name the skill name
     * @return true if enabled, false if not found
     */
    public boolean enable(String name) {
        Skill skill = skillCache.get(name);
        if (skill == null) {
            return false;
        }
        skill.setEnabled(true);
        return true;
    }

    /**
     * Disable an enabled skill.
     *
     * @param name the skill name
     * @return true if disabled, false if not found
     */
    public boolean disable(String name) {
        Skill skill = skillCache.get(name);
        if (skill == null) {
            return false;
        }
        skill.setEnabled(false);
        return true;
    }

    /**
     * Get a skill by name.
     *
     * @param name the skill name
     * @return Optional containing the skill if found
     */
    public Optional<Skill> get(String name) {
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
     * List only enabled skills.
     *
     * @return list of enabled skills
     */
    public List<Skill> listEnabled() {
        return skillCache.values().stream()
                .filter(Skill::isEnabled)
                .collect(Collectors.toList());
    }

    /**
     * Get combined prompt from all enabled skills.
     *
     * @return combined prompt string
     */
    public String getCombinedPrompt() {
        return skillCache.values().stream()
                .filter(Skill::isEnabled)
                .filter(Skill::hasPrompt)
                .map(skill -> "## Skill: " + skill.getName() + "\n\n" + skill.getPrompt())
                .collect(Collectors.joining("\n\n---\n\n"));
    }

    /**
     * Get all unique tools from enabled skills.
     *
     * @return list of tool names
     */
    public List<String> getEnabledTools() {
        return skillCache.values().stream()
                .filter(Skill::isEnabled)
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

    // Helper methods

    private boolean isUrl(String source) {
        return source.startsWith("http://") || source.startsWith("https://");
    }

    private String downloadFromUrl(String urlStr) throws IOException {
        URL url = new URL(urlStr);
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setRequestMethod("GET");
        conn.setConnectTimeout(10000);
        conn.setReadTimeout(30000);

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

    private String extractNameFromUrl(String url) {
        String path = url;
        // Remove query parameters
        int queryIndex = path.indexOf('?');
        if (queryIndex > 0) {
            path = path.substring(0, queryIndex);
        }
        // Get last segment
        int lastSlash = path.lastIndexOf('/');
        if (lastSlash >= 0) {
            path = path.substring(lastSlash + 1);
        }
        // Remove extension
        return path.replaceAll("\\.(md|yaml|yml)$", "");
    }
}
