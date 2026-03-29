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

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * Represents a skill that can be installed and used by the AI agent.
 * A skill contains metadata and a prompt template that enhances
 * the agent's capabilities for specific tasks.
 *
 * Uses lazy loading: prompt content is loaded on demand, not at startup.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = true)
public class Skill {

    /** The skill name. */
    private String name;

    /** The skill description. */
    private String description;

    /** The skill version. */
    private String version;

    /** The skill author. */
    private String author;

    /** List of tools this skill uses. */
    private List<String> tools;

    /** The prompt content (lazy loaded). */
    private String prompt;

    /** Whether the prompt has been loaded. */
    private boolean promptLoaded;

    /** File path for lazy loading. */
    private String filePath;

    /** When this skill was installed. */
    private LocalDateTime installedAt;

    /** Source URL or path where skill was installed from. */
    private String source;

    /**
     * Default constructor.
     */
    public Skill() {
        this.tools = new ArrayList<>();
        this.installedAt = LocalDateTime.now();
    }

    /**
     * Get the skill name.
     *
     * @return the name
     */
    public String getName() {
        return name;
    }

    /**
     * Set the skill name.
     *
     * @param newName the name to set
     */
    public void setName(final String newName) {
        this.name = newName;
    }

    /**
     * Get the skill description.
     *
     * @return the description
     */
    public String getDescription() {
        return description;
    }

    /**
     * Set the skill description.
     *
     * @param newDescription the description to set
     */
    public void setDescription(final String newDescription) {
        this.description = newDescription;
    }

    /**
     * Get the skill version.
     *
     * @return the version
     */
    public String getVersion() {
        return version;
    }

    /**
     * Set the skill version.
     *
     * @param newVersion the version to set
     */
    public void setVersion(final String newVersion) {
        this.version = newVersion;
    }

    /**
     * Get the skill author.
     *
     * @return the author
     */
    public String getAuthor() {
        return author;
    }

    /**
     * Set the skill author.
     *
     * @param newAuthor the author to set
     */
    public void setAuthor(final String newAuthor) {
        this.author = newAuthor;
    }

    /**
     * Get the list of tools this skill uses.
     *
     * @return the tools list
     */
    public List<String> getTools() {
        return tools;
    }

    /**
     * Set the list of tools this skill uses.
     *
     * @param newTools the tools list to set
     */
    public void setTools(final List<String> newTools) {
        this.tools = newTools != null ? newTools : new ArrayList<>();
    }

    /**
     * Get the prompt content.
     *
     * @return the prompt
     */
    public String getPrompt() {
        return prompt;
    }

    /**
     * Set the prompt content.
     *
     * @param newPrompt the prompt to set
     */
    public void setPrompt(final String newPrompt) {
        this.prompt = newPrompt;
        this.promptLoaded = true;
    }

    /**
     * Get the file path for lazy loading.
     *
     * @return the file path
     */
    public String getFilePath() {
        return filePath;
    }

    /**
     * Set the file path for lazy loading.
     *
     * @param newFilePath the file path to set
     */
    public void setFilePath(final String newFilePath) {
        this.filePath = newFilePath;
    }

    /**
     * Get when this skill was installed.
     *
     * @return the installation timestamp
     */
    public LocalDateTime getInstalledAt() {
        return installedAt;
    }

    /**
     * Set when this skill was installed.
     *
     * @param newInstalledAt the installation timestamp
     */
    public void setInstalledAt(final LocalDateTime newInstalledAt) {
        this.installedAt = newInstalledAt;
    }

    /**
     * Get the source URL or path.
     *
     * @return the source
     */
    public String getSource() {
        return source;
    }

    /**
     * Set the source URL or path.
     *
     * @param newSource the source to set
     */
    public void setSource(final String newSource) {
        this.source = newSource;
    }

    /**
     * Check if the prompt has been loaded.
     *
     * @return true if prompt is loaded
     */
    @JsonIgnore
    public boolean isPromptLoaded() {
        return promptLoaded;
    }

    /**
     * Check if this skill has a prompt.
     *
     * @return true if prompt exists
     */
    @JsonIgnore
    public boolean hasPrompt() {
        return prompt != null && !prompt.trim().isEmpty();
    }

    /**
     * Check if this skill has tools.
     *
     * @return true if tools exist
     */
    @JsonIgnore
    public boolean hasTools() {
        return tools != null && !tools.isEmpty();
    }

    /**
     * Get a formatted summary of this skill for display.
     *
     * @return formatted summary string
     */
    @JsonIgnore
    public String getSummary() {
        return String.format("  %-20s %-8s %s",
                name != null ? name : "unnamed",
                version != null ? "v" + version : "v?",
                description != null ? description : "No description");
    }

    /**
     * Get detailed information about this skill.
     *
     * @return formatted details string
     */
    @JsonIgnore
    public String getDetails() {
        StringBuilder sb = new StringBuilder();
        sb.append("Name: ").append(name).append("\n");
        sb.append("Version: ")
                .append(version != null ? version : "N/A")
                .append("\n");
        sb.append("Author: ")
                .append(author != null ? author : "N/A")
                .append("\n");
        sb.append("Description: ")
                .append(description != null ? description : "N/A")
                .append("\n");
        sb.append("Source: ")
                .append(source != null ? source : "N/A")
                .append("\n");
        sb.append("Installed: ")
                .append(installedAt != null ? installedAt.toString() : "N/A")
                .append("\n");
        if (hasTools()) {
            sb.append("Tools: ")
                    .append(String.join(", ", tools))
                    .append("\n");
        }
        sb.append("Prompt loaded: ")
                .append(promptLoaded ? "Yes" : "No")
                .append("\n");
        if (hasPrompt()) {
            sb.append("\nPrompt:\n").append(prompt).append("\n");
        }
        return sb.toString();
    }

    /**
     * Get string representation.
     *
     * @return string representation
     */
    @Override
    public String toString() {
        return "Skill{"
                + "name='" + name + '\''
                + ", version='" + version + '\''
                + ", promptLoaded=" + promptLoaded
                + '}';
    }
}
