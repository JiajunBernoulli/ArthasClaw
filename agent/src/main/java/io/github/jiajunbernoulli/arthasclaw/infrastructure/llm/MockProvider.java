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

package io.github.jiajunbernoulli.arthasclaw.infrastructure.llm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.jiajunbernoulli.arthasclaw.domain.Provider;
import java.io.IOException;

/**
 * Local mock provider for testing without external API calls.
 */
public class MockProvider implements Provider {

  private final ObjectMapper mapper = new ObjectMapper();

  @Override
  public ObjectNode chatCompletion(ArrayNode messages, ArrayNode toolsConfig) throws IOException {
    // Get the last user message
    String userMessage = "";
    for (int i = messages.size() - 1; i >= 0; i--) {
      JsonNode msg = messages.get(i);
      if ("user".equals(msg.get("role").asText())) {
        userMessage = msg.get("content").asText();
        break;
      }
    }

    ObjectNode response = mapper.createObjectNode();
    response.put("role", "assistant");

    // Simple mock response
    String mockContent = "[Mock] I received your message: \"" + userMessage + "\". "
        + "This is a local mock response. Use OpenAIProvider for real AI responses.";
    response.put("content", mockContent);

    String truncatedMessage = userMessage.substring(0, Math.min(50, userMessage.length()));
    System.out.println("[MOCK] Returning mock response for: " + truncatedMessage + "...");

    return response;
  }

  @Override
  public void close() {
    // No resources to cleanup
  }
}
