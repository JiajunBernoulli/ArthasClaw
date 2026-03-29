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
package io.github.jiajunbernoulli.arthasclaw.infrastructure.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
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

    /** User home directory. */
    private static final String HOME_DIR = System.getProperty("user.home");

    /** ArthasClaw config directory. */
    private static final String CONFIG_DIR = HOME_DIR + "/.arthasclaw";

    /** Config file path. */
    private static final String CONFIG_FILE = CONFIG_DIR + "/config.yaml";

    /** Default max iterations. */
    private static final int DEFAULT_MAX_ITERATIONS = 20;

    /** Default max messages. */
    private static final int DEFAULT_MAX_MESSAGES = 50;

    /** Default max retries. */
    private static final int DEFAULT_MAX_RETRIES = 3;

    /** Default max tool result length. */
    private static final int DEFAULT_MAX_TOOL_RESULT_LENGTH = 8000;

    /** Default list tools timeout in seconds. */
    private static final long DEFAULT_LIST_TOOLS_TIMEOUT = 5L;

    /** Default tool call timeout in seconds. */
    private static final long DEFAULT_TOOL_CALL_TIMEOUT = 30L;

    /** Default retry delay in milliseconds. */
    private static final long DEFAULT_RETRY_DELAY = 1000L;

    /** Default LLM timeout in seconds. */
    private static final int DEFAULT_LLM_TIMEOUT = 60;

    /** Default temperature. */
    private static final double DEFAULT_TEMPERATURE = 0.7;

    /** Default max tokens. */
    private static final int DEFAULT_MAX_TOKENS = 4096;

    /** Default MCP port. */
    private static final int DEFAULT_MCP_PORT = 8563;

    /** Default connect timeout in seconds. */
    private static final long DEFAULT_CONNECT_TIMEOUT = 5L;

    /** Default initialize timeout in seconds. */
    private static final long DEFAULT_INIT_TIMEOUT = 5L;

    /** Agent configuration. */
    private AgentConfig agent = new AgentConfig();

    /** LLM configuration. */
    private LlmConfig llm = new LlmConfig();

    /** MCP configuration. */
    private McpConfig mcp = new McpConfig();

    /**
     * Agent configuration settings.
     */
    public static class AgentConfig {

        /** Maximum agent loop iterations. */
        private int maxIterations = DEFAULT_MAX_ITERATIONS;

        /** Maximum messages in conversation history. */
        private int maxMessages = DEFAULT_MAX_MESSAGES;

        /** Maximum retries for MCP operations. */
        private int maxRetries = DEFAULT_MAX_RETRIES;

        /** Maximum tool result length before truncation. */
        private int maxToolResultLength = DEFAULT_MAX_TOOL_RESULT_LENGTH;

        /** Timeout for listTools MCP call in seconds. */
        private long listToolsTimeoutSeconds = DEFAULT_LIST_TOOLS_TIMEOUT;

        /** Timeout for tool call MCP operations in seconds. */
        private long toolCallTimeoutSeconds = DEFAULT_TOOL_CALL_TIMEOUT;

        /** Delay between retry attempts in milliseconds. */
        private long retryDelayMs = DEFAULT_RETRY_DELAY;

        /**
         * Get max iterations.
         *
         * @return max iterations
         */
        public int getMaxIterations() {
            return maxIterations;
        }

        /**
         * Set max iterations.
         *
         * @param newMaxIterations the value
         */
        public void setMaxIterations(final int newMaxIterations) {
            this.maxIterations = newMaxIterations;
        }

        /**
         * Get max messages.
         *
         * @return max messages
         */
        public int getMaxMessages() {
            return maxMessages;
        }

        /**
         * Set max messages.
         *
         * @param newMaxMessages the value
         */
        public void setMaxMessages(final int newMaxMessages) {
            this.maxMessages = newMaxMessages;
        }

        /**
         * Get max retries.
         *
         * @return max retries
         */
        public int getMaxRetries() {
            return maxRetries;
        }

        /**
         * Set max retries.
         *
         * @param newMaxRetries the value
         */
        public void setMaxRetries(final int newMaxRetries) {
            this.maxRetries = newMaxRetries;
        }

        /**
         * Get max tool result length.
         *
         * @return max tool result length
         */
        public int getMaxToolResultLength() {
            return maxToolResultLength;
        }

        /**
         * Set max tool result length.
         *
         * @param newMaxToolResultLength the value
         */
        public void setMaxToolResultLength(
                final int newMaxToolResultLength) {
            this.maxToolResultLength = newMaxToolResultLength;
        }

        /**
         * Get list tools timeout in seconds.
         *
         * @return timeout seconds
         */
        public long getListToolsTimeoutSeconds() {
            return listToolsTimeoutSeconds;
        }

        /**
         * Set list tools timeout in seconds.
         *
         * @param newTimeoutSeconds the value
         */
        public void setListToolsTimeoutSeconds(
                final long newTimeoutSeconds) {
            this.listToolsTimeoutSeconds = newTimeoutSeconds;
        }

        /**
         * Get tool call timeout in seconds.
         *
         * @return timeout seconds
         */
        public long getToolCallTimeoutSeconds() {
            return toolCallTimeoutSeconds;
        }

        /**
         * Set tool call timeout in seconds.
         *
         * @param newTimeoutSeconds the value
         */
        public void setToolCallTimeoutSeconds(
                final long newTimeoutSeconds) {
            this.toolCallTimeoutSeconds = newTimeoutSeconds;
        }

        /**
         * Get retry delay in milliseconds.
         *
         * @return retry delay ms
         */
        public long getRetryDelayMs() {
            return retryDelayMs;
        }

        /**
         * Set retry delay in milliseconds.
         *
         * @param newRetryDelayMs the value
         */
        public void setRetryDelayMs(final long newRetryDelayMs) {
            this.retryDelayMs = newRetryDelayMs;
        }
    }

    /**
     * LLM configuration settings.
     */
    public static class LlmConfig {

        /** API key for authentication. */
        private String apiKey;

        /** Base URL for API. */
        private String baseUrl = "https://api.openai.com/v1";

        /** Model name. */
        private String model = "gpt-4o-mini";

        /** Request timeout in seconds. */
        private int timeoutSeconds = DEFAULT_LLM_TIMEOUT;

        /** Temperature for response generation. */
        private double temperature = DEFAULT_TEMPERATURE;

        /** Maximum tokens in response. */
        private int maxTokens = DEFAULT_MAX_TOKENS;

        /** Top-p sampling parameter. */
        private double topP = 1.0;

        /**
         * Get API key.
         *
         * @return API key
         */
        public String getApiKey() {
            return apiKey;
        }

        /**
         * Set API key.
         *
         * @param newApiKey the API key
         */
        public void setApiKey(final String newApiKey) {
            this.apiKey = newApiKey;
        }

        /**
         * Get base URL.
         *
         * @return base URL
         */
        public String getBaseUrl() {
            return baseUrl;
        }

        /**
         * Set base URL.
         *
         * @param newBaseUrl the base URL
         */
        public void setBaseUrl(final String newBaseUrl) {
            this.baseUrl = newBaseUrl;
        }

        /**
         * Get model name.
         *
         * @return model name
         */
        public String getModel() {
            return model;
        }

        /**
         * Set model name.
         *
         * @param newModel the model name
         */
        public void setModel(final String newModel) {
            this.model = newModel;
        }

        /**
         * Get timeout in seconds.
         *
         * @return timeout seconds
         */
        public int getTimeoutSeconds() {
            return timeoutSeconds;
        }

        /**
         * Set timeout in seconds.
         *
         * @param newTimeoutSeconds the timeout
         */
        public void setTimeoutSeconds(final int newTimeoutSeconds) {
            this.timeoutSeconds = newTimeoutSeconds;
        }

        /**
         * Get temperature.
         *
         * @return temperature
         */
        public double getTemperature() {
            return temperature;
        }

        /**
         * Set temperature.
         *
         * @param newTemperature the temperature
         */
        public void setTemperature(final double newTemperature) {
            this.temperature = newTemperature;
        }

        /**
         * Get max tokens.
         *
         * @return max tokens
         */
        public int getMaxTokens() {
            return maxTokens;
        }

        /**
         * Set max tokens.
         *
         * @param newMaxTokens the max tokens
         */
        public void setMaxTokens(final int newMaxTokens) {
            this.maxTokens = newMaxTokens;
        }

        /**
         * Get top-p.
         *
         * @return top-p
         */
        public double getTopP() {
            return topP;
        }

        /**
         * Set top-p.
         *
         * @param newTopP the top-p
         */
        public void setTopP(final double newTopP) {
            this.topP = newTopP;
        }
    }

    /**
     * MCP (Arthas connection) configuration settings.
     */
    public static class McpConfig {

        /** MCP server port. */
        private int port = DEFAULT_MCP_PORT;

        /** MCP endpoint path. */
        private String endpoint = "/mcp";

        /** Arthas version. */
        private String arthasVersion = "4.1.8";

        /** Connection timeout in seconds. */
        private long connectTimeoutSeconds = DEFAULT_CONNECT_TIMEOUT;

        /** Initialize timeout in seconds. */
        private long initializeTimeoutSeconds = 5;

        /**
         * Get port.
         *
         * @return port
         */
        public int getPort() {
            return port;
        }

        /**
         * Set port.
         *
         * @param newPort the port
         */
        public void setPort(final int newPort) {
            this.port = newPort;
        }

        /**
         * Get endpoint.
         *
         * @return endpoint
         */
        public String getEndpoint() {
            return endpoint;
        }

        /**
         * Set endpoint.
         *
         * @param newEndpoint the endpoint
         */
        public void setEndpoint(final String newEndpoint) {
            this.endpoint = newEndpoint;
        }

        /**
         * Get Arthas version.
         *
         * @return Arthas version
         */
        public String getArthasVersion() {
            return arthasVersion;
        }

        /**
         * Set Arthas version.
         *
         * @param newArthasVersion the version
         */
        public void setArthasVersion(final String newArthasVersion) {
            this.arthasVersion = newArthasVersion;
        }

        /**
         * Get connect timeout in seconds.
         *
         * @return connect timeout
         */
        public long getConnectTimeoutSeconds() {
            return connectTimeoutSeconds;
        }

        /**
         * Set connect timeout in seconds.
         *
         * @param newTimeoutSeconds the timeout
         */
        public void setConnectTimeoutSeconds(
                final long newTimeoutSeconds) {
            this.connectTimeoutSeconds = newTimeoutSeconds;
        }

        /**
         * Get initialize timeout in seconds.
         *
         * @return initialize timeout
         */
        public long getInitializeTimeoutSeconds() {
            return initializeTimeoutSeconds;
        }

        /**
         * Set initialize timeout in seconds.
         *
         * @param newTimeoutSeconds the timeout
         */
        public void setInitializeTimeoutSeconds(
                final long newTimeoutSeconds) {
            this.initializeTimeoutSeconds = newTimeoutSeconds;
        }

        /**
         * Get base URL for MCP server.
         *
         * @return base URL
         */
        public String getBaseUrl() {
            return "http://localhost:" + port + endpoint;
        }
    }

    /**
     * Get agent configuration.
     *
     * @return agent config
     */
    public AgentConfig getAgent() {
        return agent;
    }

    /**
     * Set agent configuration.
     *
     * @param newAgent the agent config
     */
    public void setAgent(final AgentConfig newAgent) {
        this.agent = newAgent;
    }

    /**
     * Get LLM configuration.
     *
     * @return LLM config
     */
    public LlmConfig getLlm() {
        return llm;
    }

    /**
     * Set LLM configuration.
     *
     * @param newLlm the LLM config
     */
    public void setLlm(final LlmConfig newLlm) {
        this.llm = newLlm;
    }

    /**
     * Get MCP configuration.
     *
     * @return MCP config
     */
    public McpConfig getMcp() {
        return mcp;
    }

    /**
     * Set MCP configuration.
     *
     * @param newMcp the MCP config
     */
    public void setMcp(final McpConfig newMcp) {
        this.mcp = newMcp;
    }

    /**
     * Load configuration from default file, creating default if not exists.
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
    public static Config load(final String configPath) {
        ObjectMapper mapper = new ObjectMapper(new YAMLFactory());
        Path path = Paths.get(configPath);

        if (!Files.exists(path)) {
            System.out.println(
                    "[*] Config file not found, creating default: "
                    + configPath);
            Config defaultConfig = new Config();
            defaultConfig.save(configPath);
            return defaultConfig;
        }

        try {
            Config config = mapper.readValue(path.toFile(), Config.class);
            System.out.println("[+] Loaded config from: " + configPath);
            return config;
        } catch (IOException e) {
            System.err.println(
                    "[-] Failed to load config: " + e.getMessage());
            System.err.println("[*] Using default configuration");
            return new Config();
        }
    }

    /**
     * Save configuration to file.
     *
     * @param configPath path to save config file
     */
    public void save(final String configPath) {
        try {
            Path path = Paths.get(configPath);
            Files.createDirectories(path.getParent());

            StringBuilder sb = new StringBuilder();
            sb.append("# ArthasClaw Configuration\n");
            sb.append("# Edit this file to customize behavior\n\n");

            sb.append("# Agent loop settings\n");
            sb.append("agent:\n");
            sb.append("  max_iterations: ")
                    .append(agent.maxIterations)
                    .append("  # Maximum agent loop iterations\n");
            sb.append("  max_messages: ")
                    .append(agent.maxMessages)
                    .append("  # Maximum messages in history\n");
            sb.append("  max_retries: ")
                    .append(agent.maxRetries)
                    .append("  # Maximum retries for MCP ops\n");
            sb.append("  max_tool_result_length: ")
                    .append(agent.maxToolResultLength)
                    .append("  # Truncate tool results\n");
            sb.append("  list_tools_timeout_seconds: ")
                    .append(agent.listToolsTimeoutSeconds).append("\n");
            sb.append("  tool_call_timeout_seconds: ")
                    .append(agent.toolCallTimeoutSeconds).append("\n");
            sb.append("  retry_delay_ms: ")
                    .append(agent.retryDelayMs).append("\n\n");

            sb.append("# LLM settings\n");
            sb.append("llm:\n");
            sb.append("  # api_key: \"\"  # Use env var OPENAI_API_KEY\n");
            sb.append("  base_url: \"").append(llm.baseUrl).append("\"\n");
            sb.append("  model: \"").append(llm.model).append("\"\n");
            sb.append("  timeout_seconds: ")
                    .append(llm.timeoutSeconds).append("\n");
            sb.append("  temperature: ")
                    .append(llm.temperature)
                    .append("  # 0.0 - 2.0\n");
            sb.append("  max_tokens: ")
                    .append(llm.maxTokens)
                    .append("  # Maximum response tokens\n");
            sb.append("  top_p: ")
                    .append(llm.topP)
                    .append("  # 0.0 - 1.0\n\n");

            sb.append("# MCP (Arthas connection) settings\n");
            sb.append("mcp:\n");
            sb.append("  port: ")
                    .append(mcp.port)
                    .append("  # Arthas MCP server port\n");
            sb.append("  endpoint: \"").append(mcp.endpoint).append("\"\n");
            sb.append("  arthas_version: \"")
                    .append(mcp.arthasVersion).append("\"\n");
            sb.append("  connect_timeout_seconds: ")
                    .append(mcp.connectTimeoutSeconds).append("\n");
            sb.append("  initialize_timeout_seconds: ")
                    .append(mcp.initializeTimeoutSeconds).append("\n");

            try (FileWriter writer = new FileWriter(configPath)) {
                writer.write(sb.toString());
            }
            System.out.println("[+] Created default config: " + configPath);
        } catch (IOException e) {
            System.err.println(
                    "[-] Failed to save config: " + e.getMessage());
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
            System.out.println("[+] OPENAI_API_KEY loaded from env");
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
