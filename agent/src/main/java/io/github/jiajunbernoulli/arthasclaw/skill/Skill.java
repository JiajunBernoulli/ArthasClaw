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

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;

import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * Represents a skill that can be installed and used by the AI agent.
 * A skill contains a prompt template and optional tool configurations
 * that enhance the agent's capabilities for specific tasks.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = true)
public class Skill {

    private String name;
    private String description;
    private String version;
    private String author;
    private List<String> tools;
    private String prompt;
    private boolean enabled;

    // Metadata
    private LocalDateTime installedAt;
    private String source;
    private String filePath;

    public Skill() {
        this.tools = new ArrayList<>();
        this.enabled = true;
        this.installedAt = LocalDateTime.now();
    }

    // Getters and Setters

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public String getVersion() {
        return version;
    }

    public void setVersion(String version) {
        this.version = version;
    }

    public String getAuthor() {
        return author;
    }

    public void setAuthor(String author) {
        this.author = author;
    }

    public List<String> getTools() {
        return tools;
    }

    public void setTools(List<String> tools) {
        this.tools = tools != null ? tools : new ArrayList<>();
    }

    public String getPrompt() {
        return prompt;
    }

    public void setPrompt(String prompt) {
        this.prompt = prompt;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public LocalDateTime getInstalledAt() {
        return installedAt;
    }

    public void setInstalledAt(LocalDateTime installedAt) {
        this.installedAt = installedAt;
    }

    public String getSource() {
        return source;
    }

    public void setSource(String source) {
        this.source = source;
    }

    public String getFilePath() {
        return filePath;
    }

    public void setFilePath(String filePath) {
        this.filePath = filePath;
    }

    // Convenience methods

    @JsonIgnore
    public boolean hasPrompt() {
        return prompt != null && !prompt.trim().isEmpty();
    }

    @JsonIgnore
    public boolean hasTools() {
        return tools != null && !tools.isEmpty();
    }

    /**
     * Get a formatted summary of this skill for display.
     */
    @JsonIgnore
    public String getSummary() {
        StringBuilder sb = new StringBuilder();
        sb.append(String.format("  %s %-20s %-8s %s",
                enabled ? "✓" : "✗",
                name != null ? name : "unnamed",
                version != null ? "v" + version : "v?",
                description != null ? description : "No description"));
        return sb.toString();
    }

    /**
     * Get detailed information about this skill.
     */
    @JsonIgnore
    public String getDetails() {
        StringBuilder sb = new StringBuilder();
        sb.append("Name: ").append(name).append("\n");
        sb.append("Version: ").append(version != null ? version : "N/A").append("\n");
        sb.append("Author: ").append(author != null ? author : "N/A").append("\n");
        sb.append("Description: ").append(description != null ? description : "N/A").append("\n");
        sb.append("Status: ").append(enabled ? "Enabled" : "Disabled").append("\n");
        sb.append("Source: ").append(source != null ? source : "N/A").append("\n");
        sb.append("Installed: ").append(installedAt != null ? installedAt.toString() : "N/A").append("\n");
        if (hasTools()) {
            sb.append("Tools: ").append(String.join(", ", tools)).append("\n");
        }
        if (hasPrompt()) {
            sb.append("\nPrompt:\n").append(prompt).append("\n");
        }
        return sb.toString();
    }

    @Override
    public String toString() {
        return "Skill{" +
                "name='" + name + '\'' +
                ", version='" + version + '\'' +
                ", enabled=" + enabled +
                '}';
    }
}
