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
package io.github.jiajunbernoulli.arthasclaw.infrastructure.llm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.jiajunbernoulli.arthasclaw.domain.CompletionProvider;
import io.github.jiajunbernoulli.arthasclaw.infrastructure.config.Config;
import java.io.IOException;
import java.util.concurrent.TimeUnit;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

/**
 * OpenAI API compatible completion provider.
 */
public class OpenAICompletionProvider implements CompletionProvider {

    /** Chat completions endpoint. */
    private static final String CHAT_COMPLETIONS = "/chat/completions";

    /** Default base URL. */
    private static final String DEFAULT_BASE_URL =
            "https://api.openai.com/v1";

    /** Default model. */
    private static final String DEFAULT_MODEL = "gpt-4o-mini";

    /** Default timeout in seconds. */
    private static final int DEFAULT_TIMEOUT = 60;

    /** Default temperature. */
    private static final double DEFAULT_TEMPERATURE = 0.7;

    /** Default max tokens. */
    private static final int DEFAULT_MAX_TOKENS = 4096;

    /** Default top-p. */
    private static final double DEFAULT_TOP_P = 1.0;

    /** API key for authentication. */
    private final String apiKey;

    /** Model name. */
    private final String model;

    /** Base URL for API. */
    private final String baseUrl;

    /** Request timeout in seconds. */
    private final int timeoutSeconds;

    /** Temperature for response generation. */
    private final double temperature;

    /** Maximum tokens in response. */
    private final int maxTokens;

    /** Top-p sampling parameter. */
    private final double topP;

    /** HTTP client. */
    private final OkHttpClient httpClient;

    /** JSON object mapper. */
    private final ObjectMapper mapper = new ObjectMapper();

    /**
     * Create provider from LLM config.
     *
     * @param llmConfig the LLM configuration
     */
    public OpenAICompletionProvider(final Config.LlmConfig llmConfig) {
        this.apiKey = llmConfig.getApiKey();
        this.model = llmConfig.getModel() != null
                ? llmConfig.getModel()
                : DEFAULT_MODEL;
        this.timeoutSeconds = llmConfig.getTimeoutSeconds();
        this.temperature = llmConfig.getTemperature();
        this.maxTokens = llmConfig.getMaxTokens();
        this.topP = llmConfig.getTopP();

        String tempUrl = llmConfig.getBaseUrl() != null
                ? llmConfig.getBaseUrl()
                : DEFAULT_BASE_URL;
        if (!tempUrl.endsWith(CHAT_COMPLETIONS)) {
            if (tempUrl.endsWith("/")) {
                tempUrl += "chat/completions";
            } else {
                tempUrl += CHAT_COMPLETIONS;
            }
            System.out.println(
                    "[DEBUG] Adjusted API URL to: " + tempUrl);
        }
        this.baseUrl = tempUrl;

        this.httpClient = new OkHttpClient.Builder()
                .readTimeout(timeoutSeconds, TimeUnit.SECONDS)
                .build();
    }

    /**
     * Legacy constructor for backward compatibility.
     *
     * @param key   API key
     * @param name  model name
     * @param url   base URL
     */
    public OpenAICompletionProvider(
            final String key,
            final String name,
            final String url) {
        this.apiKey = key;
        this.model = name != null ? name : DEFAULT_MODEL;
        this.timeoutSeconds = DEFAULT_TIMEOUT;
        this.temperature = DEFAULT_TEMPERATURE;
        this.maxTokens = DEFAULT_MAX_TOKENS;
        this.topP = DEFAULT_TOP_P;

        String tempUrl = url != null ? url : DEFAULT_BASE_URL;
        if (!tempUrl.endsWith(CHAT_COMPLETIONS)) {
            if (tempUrl.endsWith("/")) {
                tempUrl += "chat/completions";
            } else {
                tempUrl += CHAT_COMPLETIONS;
            }
            System.out.println(
                    "[DEBUG] Adjusted API URL to: " + tempUrl);
        }
        this.baseUrl = tempUrl;

        this.httpClient = new OkHttpClient.Builder()
                .readTimeout(timeoutSeconds, TimeUnit.SECONDS)
                .build();
    }

    /**
     * Send a chat completion request.
     *
     * @param messages     the conversation messages
     * @param toolsConfig  the available tools configuration
     * @return the response message node
     * @throws IOException if request fails
     */
    @Override
    public ObjectNode chatCompletion(
            final ArrayNode messages,
            final ArrayNode toolsConfig) throws IOException {
        if (messages == null || messages.size() == 0) {
            throw new IllegalArgumentException(
                    "Messages cannot be null or empty");
        }

        ArrayNode validMessages = mapper.createArrayNode();
        for (JsonNode msg : messages) {
            if (msg.hasNonNull("content")
                    && !msg.get("content").asText().trim().isEmpty()) {
                validMessages.add(msg);
            } else if (msg.has("role")
                    && "system".equals(msg.get("role").asText())) {
                validMessages.add(msg);
            }
        }

        if (validMessages.size() == 0) {
            throw new IllegalArgumentException(
                    "No valid messages to send (all content is null or empty)");
        }

        ObjectNode requestBody = mapper.createObjectNode();
        requestBody.put("model", model);
        requestBody.set("messages", validMessages);

        requestBody.put("temperature", temperature);
        requestBody.put("max_tokens", maxTokens);
        requestBody.put("top_p", topP);

        if (toolsConfig != null && toolsConfig.size() > 0) {
            requestBody.set("tools", toolsConfig);
        }

        String json = mapper.writeValueAsString(requestBody);
        Request request = new Request.Builder()
                .url(baseUrl)
                .header("Authorization", "Bearer " + apiKey)
                .post(RequestBody.create(
                        json, MediaType.parse("application/json")))
                .build();

        try (Response response = httpClient.newCall(request).execute()) {
            String responseBody = response.body() != null
                    ? response.body().string()
                    : "";
            if (!response.isSuccessful()) {
                throw new IOException(
                        "API Error: " + response.code() + " "
                        + response.message() + " - " + responseBody);
            }

            JsonNode responseJson = mapper.readTree(responseBody);
            return (ObjectNode) responseJson.get("choices")
                    .get(0)
                    .get("message");
        }
    }

    /**
     * Close and cleanup resources.
     */
    @Override
    public void close() {
        httpClient.dispatcher().executorService().shutdown();
        httpClient.connectionPool().evictAll();
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
     * Get base URL.
     *
     * @return base URL
     */
    public String getBaseUrl() {
        return baseUrl;
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
     * Get max tokens.
     *
     * @return max tokens
     */
    public int getMaxTokens() {
        return maxTokens;
    }

    /**
     * Get top-p.
     *
     * @return top-p
     */
    public double getTopP() {
        return topP;
    }
}
