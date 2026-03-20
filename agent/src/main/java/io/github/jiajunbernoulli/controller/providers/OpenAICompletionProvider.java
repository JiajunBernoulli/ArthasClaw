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
package io.github.jiajunbernoulli.controller.providers;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import okhttp3.*;

import java.io.IOException;
import java.util.concurrent.TimeUnit;

/**
 * OpenAI API compatible completion provider.
 */
public class OpenAICompletionProvider implements CompletionProvider {

    private final String apiKey;
    private final String model;
    private final String baseUrl;
    private final OkHttpClient httpClient;
    private final ObjectMapper mapper = new ObjectMapper();

    public OpenAICompletionProvider(String apiKey, String model, String baseUrl) {
        this.apiKey = apiKey;
        this.model = model != null ? model : "gpt-4o-mini";

        String tempUrl = baseUrl != null ? baseUrl : "https://api.openai.com/v1/chat/completions";
        if (!tempUrl.endsWith("/chat/completions")) {
            if (tempUrl.endsWith("/")) {
                tempUrl += "chat/completions";
            } else {
                tempUrl += "/chat/completions";
            }
            System.out.println("[DEBUG] Adjusted API URL to: " + tempUrl);
        }
        this.baseUrl = tempUrl;

        this.httpClient = new OkHttpClient.Builder()
                .readTimeout(60, TimeUnit.SECONDS)
                .build();
    }

    @Override
    public ObjectNode chatCompletion(ArrayNode messages, ArrayNode toolsConfig) throws IOException {
        // Validate messages before sending to LLM
        if (messages == null || messages.size() == 0) {
            throw new IllegalArgumentException("Messages cannot be null or empty");
        }

        // Filter out invalid messages (null content)
        ArrayNode validMessages = mapper.createArrayNode();
        for (JsonNode msg : messages) {
            if (msg.hasNonNull("content") && !msg.get("content").asText().trim().isEmpty()) {
                validMessages.add(msg);
            } else if (msg.has("role") && "system".equals(msg.get("role").asText())) {
                // Keep system messages even if content is empty
                validMessages.add(msg);
            }
            // Skip messages with null/empty content (non-system)
        }

        if (validMessages.size() == 0) {
            throw new IllegalArgumentException("No valid messages to send (all content is null or empty)");
        }

        ObjectNode requestBody = mapper.createObjectNode();
        requestBody.put("model", model);
        requestBody.set("messages", validMessages);

        if (toolsConfig != null && toolsConfig.size() > 0) {
            requestBody.set("tools", toolsConfig);
        }

        String json = mapper.writeValueAsString(requestBody);
        Request request = new Request.Builder()
                .url(baseUrl)
                .header("Authorization", "Bearer " + apiKey)
                .post(RequestBody.create(json, MediaType.parse("application/json")))
                .build();

        try (Response response = httpClient.newCall(request).execute()) {
            String responseBody = response.body() != null ? response.body().string() : "";
            if (!response.isSuccessful()) {
                throw new IOException("API Error: " + response.code() + " " + response.message() + " - " + responseBody);
            }

            JsonNode responseJson = mapper.readTree(responseBody);
            return (ObjectNode) responseJson.get("choices").get(0).get("message");
        }
    }

    @Override
    public void close() {
        httpClient.dispatcher().executorService().shutdown();
        httpClient.connectionPool().evictAll();
    }

    public String getModel() {
        return model;
    }

    public String getBaseUrl() {
        return baseUrl;
    }
}
