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
package io.github.jiajunbernoulli.arthasclaw.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * Configuration manager for ArthasClaw.
 * Loads settings from ~/.arthasclaw/config.yaml with sensible defaults.
 */
public class Config {

    private static final String HOME_DIR = System.getProperty("user.home");
    private static final String CONFIG_DIR = HOME_DIR + "/.arthasclaw";
    private static final String CONFIG_FILE = CONFIG_DIR + "/config.yaml";

    private AgentConfig agent = new AgentConfig();
    private LlmConfig llm = new LlmConfig();
    private McpConfig mcp = new McpConfig();

    public static class AgentConfig {
        private int maxIterations = 20;
        private int maxMessages = 50;
        private int maxRetries = 3;
        private int maxToolResultLength = 8000;
        private long listToolsTimeoutSeconds = 5;
        private long toolCallTimeoutSeconds = 30;
        private long retryDelayMs = 1000;

        public int getMaxIterations() { return maxIterations; }
        public void setMaxIterations(int maxIterations) { this.maxIterations = maxIterations; }

        public int getMaxMessages() { return maxMessages; }
        public void setMaxMessages(int maxMessages) { this.maxMessages = maxMessages; }

        public int getMaxRetries() { return maxRetries; }
        public void setMaxRetries(int maxRetries) { this.maxRetries = maxRetries; }

        public int getMaxToolResultLength() { return maxToolResultLength; }
        public void setMaxToolResultLength(int maxToolResultLength) { this.maxToolResultLength = maxToolResultLength; }

        public long getListToolsTimeoutSeconds() { return listToolsTimeoutSeconds; }
        public void setListToolsTimeoutSeconds(long listToolsTimeoutSeconds) { this.listToolsTimeoutSeconds = listToolsTimeoutSeconds; }

        public long getToolCallTimeoutSeconds() { return toolCallTimeoutSeconds; }
        public void setToolCallTimeoutSeconds(long toolCallTimeoutSeconds) { this.toolCallTimeoutSeconds = toolCallTimeoutSeconds; }

        public long getRetryDelayMs() { return retryDelayMs; }
        public void setRetryDelayMs(long retryDelayMs) { this.retryDelayMs = retryDelayMs; }
    }

    public static class LlmConfig {
        private String apiKey;
        private String baseUrl = "https://api.openai.com/v1";
        private String model = "gpt-4o-mini";
        private int timeoutSeconds = 60;
        private double temperature = 0.7;
        private int maxTokens = 4096;
        private double topP = 1.0;

        public String getApiKey() { return apiKey; }
        public void setApiKey(String apiKey) { this.apiKey = apiKey; }

        public String getBaseUrl() { return baseUrl; }
        public void setBaseUrl(String baseUrl) { this.baseUrl = baseUrl; }

        public String getModel() { return model; }
        public void setModel(String model) { this.model = model; }

        public int getTimeoutSeconds() { return timeoutSeconds; }
        public void setTimeoutSeconds(int timeoutSeconds) { this.timeoutSeconds = timeoutSeconds; }

        public double getTemperature() { return temperature; }
        public void setTemperature(double temperature) { this.temperature = temperature; }

        public int getMaxTokens() { return maxTokens; }
        public void setMaxTokens(int maxTokens) { this.maxTokens = maxTokens; }

        public double getTopP() { return topP; }
        public void setTopP(double topP) { this.topP = topP; }
    }

    public static class McpConfig {
        private int port = 8563;
        private String endpoint = "/mcp";
        private String arthasVersion = "4.1.8";
        private long connectTimeoutSeconds = 5;
        private long initializeTimeoutSeconds = 5;

        public int getPort() { return port; }
        public void setPort(int port) { this.port = port; }

        public String getEndpoint() { return endpoint; }
        public void setEndpoint(String endpoint) { this.endpoint = endpoint; }

        public String getArthasVersion() { return arthasVersion; }
        public void setArthasVersion(String arthasVersion) { this.arthasVersion = arthasVersion; }

        public long getConnectTimeoutSeconds() { return connectTimeoutSeconds; }
        public void setConnectTimeoutSeconds(long connectTimeoutSeconds) { this.connectTimeoutSeconds = connectTimeoutSeconds; }

        public long getInitializeTimeoutSeconds() { return initializeTimeoutSeconds; }
        public void setInitializeTimeoutSeconds(long initializeTimeoutSeconds) { this.initializeTimeoutSeconds = initializeTimeoutSeconds; }

        public String getBaseUrl() {
            return "http://localhost:" + port + endpoint;
        }
    }

    public AgentConfig getAgent() { return agent; }
    public void setAgent(AgentConfig agent) { this.agent = agent; }

    public LlmConfig getLlm() { return llm; }
    public void setLlm(LlmConfig llm) { this.llm = llm; }

    public McpConfig getMcp() { return mcp; }
    public void setMcp(McpConfig mcp) { this.mcp = mcp; }

    /**
     * Load configuration from file, creating default if not exists.
     *
     * @return loaded Config instance
     */
    public static Config load() {
        return load(CONFIG_FILE);
    }

    /**
     * Load configuration from specified path.
     *
     * @param configPath path to config file
     * @return loaded Config instance
     */
    public static Config load(String configPath) {
        ObjectMapper mapper = new ObjectMapper(new YAMLFactory());
        Path path = Paths.get(configPath);

        if (!Files.exists(path)) {
            System.out.println("[*] Config file not found, creating default: " + configPath);
            Config defaultConfig = new Config();
            defaultConfig.save(configPath);
            return defaultConfig;
        }

        try {
            Config config = mapper.readValue(path.toFile(), Config.class);
            System.out.println("[+] Loaded config from: " + configPath);
            return config;
        } catch (IOException e) {
            System.err.println("[-] Failed to load config: " + e.getMessage());
            System.err.println("[*] Using default configuration");
            return new Config();
        }
    }

    /**
     * Save configuration to file.
     *
     * @param configPath path to save config file
     */
    public void save(String configPath) {
        try {
            Path path = Paths.get(configPath);
            Files.createDirectories(path.getParent());

            // Write with comments
            StringBuilder sb = new StringBuilder();
            sb.append("# ArthasClaw Configuration\n");
            sb.append("# Edit this file to customize behavior\n\n");

            sb.append("# Agent loop settings\n");
            sb.append("agent:\n");
            sb.append("  max_iterations: ").append(agent.maxIterations).append("  # Maximum agent loop iterations\n");
            sb.append("  max_messages: ").append(agent.maxMessages).append("  # Maximum messages in conversation history\n");
            sb.append("  max_retries: ").append(agent.maxRetries).append("  # Maximum retries for MCP operations\n");
            sb.append("  max_tool_result_length: ").append(agent.maxToolResultLength).append("  # Truncate tool results to this length\n");
            sb.append("  list_tools_timeout_seconds: ").append(agent.listToolsTimeoutSeconds).append("\n");
            sb.append("  tool_call_timeout_seconds: ").append(agent.toolCallTimeoutSeconds).append("\n");
            sb.append("  retry_delay_ms: ").append(agent.retryDelayMs).append("\n\n");

            sb.append("# LLM settings\n");
            sb.append("llm:\n");
            sb.append("  # api_key: \"\"  # Set via environment variable OPENAI_API_KEY is recommended\n");
            sb.append("  base_url: \"").append(llm.baseUrl).append("\"\n");
            sb.append("  model: \"").append(llm.model).append("\"\n");
            sb.append("  timeout_seconds: ").append(llm.timeoutSeconds).append("\n");
            sb.append("  temperature: ").append(llm.temperature).append("  # 0.0 - 2.0, higher = more creative\n");
            sb.append("  max_tokens: ").append(llm.maxTokens).append("  # Maximum response tokens\n");
            sb.append("  top_p: ").append(llm.topP).append("  # 0.0 - 1.0, nucleus sampling\n\n");

            sb.append("# MCP (Arthas connection) settings\n");
            sb.append("mcp:\n");
            sb.append("  port: ").append(mcp.port).append("  # Arthas MCP server port\n");
            sb.append("  endpoint: \"").append(mcp.endpoint).append("\"\n");
            sb.append("  arthas_version: \"").append(mcp.arthasVersion).append("\"\n");
            sb.append("  connect_timeout_seconds: ").append(mcp.connectTimeoutSeconds).append("\n");
            sb.append("  initialize_timeout_seconds: ").append(mcp.initializeTimeoutSeconds).append("\n");

            try (FileWriter writer = new FileWriter(configPath)) {
                writer.write(sb.toString());
            }
            System.out.println("[+] Created default config: " + configPath);
        } catch (IOException e) {
            System.err.println("[-] Failed to save config: " + e.getMessage());
        }
    }

    /**
     * Get API key from config or environment variable.
     * Environment variable takes precedence.
     *
     * @return API key string
     */
    public String getEffectiveApiKey() {
        String envKey = System.getenv("OPENAI_API_KEY");
        if (envKey != null && !envKey.trim().isEmpty()) {
            System.out.println("[+] OPENAI_API_KEY loaded from environment");
            return envKey;
        }
        if (llm.getApiKey() != null && !llm.getApiKey().trim().isEmpty()) {
            System.out.println("[+] API key loaded from config file");
            return llm.getApiKey();
        }
        return null;
    }

    /**
     * Get base URL from config or environment variable.
     * Environment variable takes precedence.
     *
     * @return base URL string
     */
    public String getEffectiveBaseUrl() {
        String envUrl = System.getenv("OPENAI_BASE_URL");
        if (envUrl != null && !envUrl.trim().isEmpty()) {
            return envUrl;
        }
        return llm.getBaseUrl();
    }

    /**
     * Get model from config or environment variable.
     * Environment variable takes precedence.
     *
     * @return model string
     */
    public String getEffectiveModel() {
        String envModel = System.getenv("OPENAI_MODEL");
        if (envModel != null && !envModel.trim().isEmpty()) {
            return envModel;
        }
        return llm.getModel();
    }
}
