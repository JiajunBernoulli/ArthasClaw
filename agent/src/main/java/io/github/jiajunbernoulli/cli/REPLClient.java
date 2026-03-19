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
package io.github.jiajunbernoulli.cli;

import io.github.jiajunbernoulli.controller.LoopAgent;
import io.github.jiajunbernoulli.controller.providers.CompletionProvider;
import io.github.jiajunbernoulli.controller.providers.OpenAICompletionProvider;
import io.github.jiajunbernoulli.cli.bootstrap.BotArthas;
import io.github.jiajunbernoulli.mcp.McpClient;

/**
 * Simple REPL CLI entry point for ArthasClaw.
 * Attaches Arthas to a target JVM and starts a simple interactive session using LoopAgent.
 * 
 * This class is decoupled from TUIClient - both independently use BotArthas
 * to connect to Arthas MCP Server.
 */
public class REPLClient {
    public static void main(String[] args) {
        if (args.length < 1) {
            System.err.println("Usage: java -jar bot-agent.jar <PID>");
            System.exit(1);
        }

        String pid = args[0];

        try {
            // 1. Attach Arthas via BotArthas bootstrap
            BotArthas arthas = new BotArthas(pid);
            McpClient mcpClient = arthas.attach();

            // 2. Print ready message
            System.out.println("\n=================================================");
            System.out.println("🚀 ArthasClaw REPL is ready!");
            System.out.println("=================================================\n");

            // 3. Setup AI provider from environment
            String apiKey = System.getenv("OPENAI_API_KEY");
            if (apiKey == null || apiKey.trim().isEmpty()) {
                System.err.println("[-] OPENAI_API_KEY environment variable is not set.");
                System.err.println("[-] Please set it before running, e.g.: export OPENAI_API_KEY=sk-xxx");
                System.exit(1);
            }

            String baseUrl = System.getenv("OPENAI_BASE_URL");
            if (baseUrl == null || baseUrl.trim().isEmpty()) {
                baseUrl = "https://api.openai.com/v1/chat/completions";
            }

            String model = System.getenv("OPENAI_MODEL");
            if (model == null || model.trim().isEmpty()) {
                model = "gpt-4o-mini";
            }

            // 4. Create provider and start simple REPL using LoopAgent
            CompletionProvider provider = new OpenAICompletionProvider(apiKey, model, baseUrl);
            LoopAgent agent = new LoopAgent(provider, mcpClient);
            agent.startInteractiveLoop();

        } catch (Exception e) {
            System.err.println("[-] Error: " + e.getMessage());
            e.printStackTrace();
            System.exit(1);
        }
    }
}