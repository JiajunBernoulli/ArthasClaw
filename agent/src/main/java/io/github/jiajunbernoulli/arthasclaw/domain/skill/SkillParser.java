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

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Parser for skill files. Supports two formats:
 * 1. YAML front matter with markdown body (recommended)
 * 2. Pure YAML file
 *
 * Uses lazy loading: parseMetadata() only reads YAML header,
 * loadPrompt() reads the full prompt content on demand.
 *
 * Example skill file with front matter:
 * <pre>
 * ---
 * name: thread-analyzer
 * description: Analyze thread issues and deadlocks
 * version: 1.0.0
 * author: jiajunbernoulli
 * tools:
 *   - thread
 *   - thread -n 5
 * ---
 * You are a thread analysis expert...
 * </pre>
 */
public class SkillParser {

    /** Pattern for YAML front matter. */
    private static final Pattern FRONT_MATTER_PATTERN = Pattern.compile(
            "^---\\s*\\n([\\s\\S]*?)\\n---\\s*\\n([\\s\\S]*)$"
    );

    /** YAML object mapper. */
    private final ObjectMapper yamlMapper;

    /**
     * Create a new SkillParser.
     */
    public SkillParser() {
        this.yamlMapper = new ObjectMapper(new YAMLFactory());
    }

    /**
     * Parse only metadata from a skill file (lazy loading).
     * Does not load the prompt content.
     *
     * @param path the path to the skill file
     * @return the Skill object with metadata only
     * @throws IOException if parsing fails
     */
    public Skill parseMetadata(final Path path) throws IOException {
        String content = new String(
                Files.readAllBytes(path), StandardCharsets.UTF_8);
        String filename = path.getFileName().toString()
                .replaceAll("\\.(md|yaml|yml)$", "");

        Matcher matcher = FRONT_MATTER_PATTERN.matcher(content);
        if (matcher.matches()) {
            String yamlPart = matcher.group(1);
            Skill skill = parseYamlMetadata(yamlPart);
            if (skill.getName() == null || skill.getName().isEmpty()) {
                skill.setName(filename);
            }
            skill.setFilePath(path.toString());
            return skill;
        }

        if (content.trim().startsWith("name:")
                || content.trim().startsWith("-")) {
            Skill skill = parseYamlMetadata(content);
            if (skill.getName() == null || skill.getName().isEmpty()) {
                skill.setName(filename);
            }
            skill.setFilePath(path.toString());
            return skill;
        }

        Skill skill = new Skill();
        skill.setName(filename);
        skill.setFilePath(path.toString());
        return skill;
    }

    /**
     * Load the prompt content from a skill file.
     *
     * @param path the path to the skill file
     * @return the prompt content, or null if not found
     * @throws IOException if reading fails
     */
    public String loadPrompt(final Path path) throws IOException {
        String content = new String(
                Files.readAllBytes(path), StandardCharsets.UTF_8);

        Matcher matcher = FRONT_MATTER_PATTERN.matcher(content);
        if (matcher.matches()) {
            String promptPart = matcher.group(2).trim();
            return promptPart.isEmpty() ? null : promptPart;
        }

        if (content.trim().startsWith("name:")) {
            SkillMetadata metadata = yamlMapper.readValue(
                    content, SkillMetadata.class);
            return metadata.getPrompt();
        }

        return content.trim().isEmpty() ? null : content.trim();
    }

    /**
     * Parse a skill file fully (metadata + prompt).
     *
     * @param path the path to the skill file
     * @return the parsed Skill object
     * @throws IOException if parsing fails
     */
    public Skill parse(final Path path) throws IOException {
        String content = new String(
                Files.readAllBytes(path), StandardCharsets.UTF_8);
        String filename = path.getFileName().toString()
                .replaceAll("\\.(md|yaml|yml)$", "");

        Matcher matcher = FRONT_MATTER_PATTERN.matcher(content);
        if (matcher.matches()) {
            String yamlPart = matcher.group(1);
            String promptPart = matcher.group(2).trim();

            Skill skill = parseYamlMetadata(yamlPart);
            if (!promptPart.isEmpty()) {
                skill.setPrompt(promptPart);
            }
            if (skill.getName() == null || skill.getName().isEmpty()) {
                skill.setName(filename);
            }
            skill.setFilePath(path.toString());
            return skill;
        }

        if (content.trim().startsWith("name:")
                || content.trim().startsWith("-")) {
            Skill skill = parseYamlMetadata(content);
            if (skill.getName() == null || skill.getName().isEmpty()) {
                skill.setName(filename);
            }
            skill.setFilePath(path.toString());
            return skill;
        }

        Skill skill = new Skill();
        skill.setName(filename);
        skill.setPrompt(content.trim());
        skill.setFilePath(path.toString());
        return skill;
    }

    /**
     * Parse skill content from a string.
     *
     * @param content     the skill file content
     * @param defaultName default name to use if not specified
     * @return the parsed Skill object
     * @throws IOException if parsing fails
     */
    public Skill parseFromString(
            final String content,
            final String defaultName) throws IOException {
        Matcher matcher = FRONT_MATTER_PATTERN.matcher(content);
        if (matcher.matches()) {
            String yamlPart = matcher.group(1);
            String promptPart = matcher.group(2).trim();

            Skill skill = parseYamlMetadata(yamlPart);
            if (!promptPart.isEmpty()) {
                skill.setPrompt(promptPart);
            }
            if (skill.getName() == null || skill.getName().isEmpty()) {
                skill.setName(defaultName);
            }
            return skill;
        }

        if (content.trim().startsWith("name:")) {
            Skill skill = parseYamlMetadata(content);
            if (skill.getName() == null || skill.getName().isEmpty()) {
                skill.setName(defaultName);
            }
            return skill;
        }

        Skill skill = new Skill();
        skill.setName(defaultName);
        skill.setPrompt(content.trim());
        return skill;
    }

    /**
     * Parse YAML metadata into a Skill object.
     *
     * @param yaml the YAML string
     * @return the parsed Skill object
     * @throws IOException if parsing fails
     */
    private Skill parseYamlMetadata(final String yaml) throws IOException {
        SkillMetadata metadata = yamlMapper.readValue(
                yaml, SkillMetadata.class);
        Skill skill = new Skill();
        skill.setName(metadata.getName());
        skill.setDescription(metadata.getDescription());
        skill.setVersion(metadata.getVersion());
        skill.setAuthor(metadata.getAuthor());
        skill.setTools(metadata.getTools() != null
                ? metadata.getTools()
                : new ArrayList<>());
        if (metadata.getPrompt() != null) {
            skill.setPrompt(metadata.getPrompt());
        }
        return skill;
    }

    /**
     * Internal class for YAML deserialization.
     */
    private static final class SkillMetadata {

        /** Skill name. */
        private String name;

        /** Skill description. */
        private String description;

        /** Skill version. */
        private String version;

        /** Skill author. */
        private String author;

        /** List of tools used by the skill. */
        private List<String> tools;

        /** Skill prompt content. */
        private String prompt;

        /**
         * Get name.
         *
         * @return name
         */
        public String getName() {
            return name;
        }

        /**
         * Get description.
         *
         * @return description
         */
        public String getDescription() {
            return description;
        }

        /**
         * Get version.
         *
         * @return version
         */
        public String getVersion() {
            return version;
        }

        /**
         * Get author.
         *
         * @return author
         */
        public String getAuthor() {
            return author;
        }

        /**
         * Get tools list.
         *
         * @return tools list
         */
        public List<String> getTools() {
            return tools;
        }

        /**
         * Get prompt.
         *
         * @return prompt
         */
        public String getPrompt() {
            return prompt;
        }
    }
}
