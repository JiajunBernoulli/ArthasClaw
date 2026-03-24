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

    private static final Pattern FRONT_MATTER_PATTERN = Pattern.compile(
            "^---\\s*\\n([\\s\\S]*?)\\n---\\s*\\n([\\s\\S]*)$"
    );

    private final ObjectMapper yamlMapper;

    public SkillParser() {
        this.yamlMapper = new ObjectMapper(new YAMLFactory());
    }

    /**
     * Parse a skill file from the given path.
     *
     * @param path the path to the skill file
     * @return the parsed Skill object
     * @throws IOException if parsing fails
     */
    public Skill parse(Path path) throws IOException {
        String content = new String(Files.readAllBytes(path), StandardCharsets.UTF_8);

        // Try front matter format first
        Matcher matcher = FRONT_MATTER_PATTERN.matcher(content);
        if (matcher.matches()) {
            String yamlPart = matcher.group(1);
            String promptPart = matcher.group(2).trim();

            Skill skill = parseYaml(yamlPart);
            if (!promptPart.isEmpty()) {
                skill.setPrompt(promptPart);
            }
            skill.setFilePath(path.toString());
            return skill;
        }

        // Try pure YAML format
        if (content.trim().startsWith("name:") || content.trim().startsWith("-")) {
            Skill skill = parseYaml(content);
            skill.setFilePath(path.toString());
            return skill;
        }

        // Treat as pure prompt (no metadata)
        Skill skill = new Skill();
        // Derive name from filename
        String filename = path.getFileName().toString();
        skill.setName(filename.replaceAll("\\.(md|yaml|yml)$", ""));
        skill.setPrompt(content.trim());
        skill.setFilePath(path.toString());
        return skill;
    }

    /**
     * Parse skill content from a string.
     *
     * @param content the skill file content
     * @param defaultName default name to use if not specified in content
     * @return the parsed Skill object
     * @throws IOException if parsing fails
     */
    public Skill parseFromString(String content, String defaultName) throws IOException {
        Matcher matcher = FRONT_MATTER_PATTERN.matcher(content);
        if (matcher.matches()) {
            String yamlPart = matcher.group(1);
            String promptPart = matcher.group(2).trim();

            Skill skill = parseYaml(yamlPart);
            if (!promptPart.isEmpty()) {
                skill.setPrompt(promptPart);
            }
            if (skill.getName() == null || skill.getName().isEmpty()) {
                skill.setName(defaultName);
            }
            return skill;
        }

        // Try pure YAML
        if (content.trim().startsWith("name:")) {
            Skill skill = parseYaml(content);
            if (skill.getName() == null || skill.getName().isEmpty()) {
                skill.setName(defaultName);
            }
            return skill;
        }

        // Pure prompt
        Skill skill = new Skill();
        skill.setName(defaultName);
        skill.setPrompt(content.trim());
        return skill;
    }

    private Skill parseYaml(String yaml) throws IOException {
        SkillMetadata metadata = yamlMapper.readValue(yaml, SkillMetadata.class);
        Skill skill = new Skill();
        skill.setName(metadata.name);
        skill.setDescription(metadata.description);
        skill.setVersion(metadata.version);
        skill.setAuthor(metadata.author);
        skill.setTools(metadata.tools != null ? metadata.tools : new ArrayList<>());
        if (metadata.prompt != null) {
            skill.setPrompt(metadata.prompt);
        }
        return skill;
    }

    /**
     * Internal class for YAML deserialization.
     */
    private static class SkillMetadata {
        public String name;
        public String description;
        public String version;
        public String author;
        public List<String> tools;
        public String prompt;
    }
}
