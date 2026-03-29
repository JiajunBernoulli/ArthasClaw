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
package io.github.jiajunbernoulli.arthasclaw.infrastructure.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.sse.EventSource;
import okhttp3.sse.EventSourceListener;
import okhttp3.sse.EventSources;

/**
 * MCP (Model Context Protocol) client for communicating with Arthas.
 * Implements the HTTP transport protocol for MCP.
 */
public class McpClient {

    /** MCP protocol version. */
    private static final String PROTOCOL_VERSION = "2024-11-05";

    /** JSON-RPC version. */
    private static final String JSON_RPC_VERSION = "2.0";

    /** Base URL for MCP server. */
    private final String baseUrl;

    /** Password for MCP authentication. */
    private final String mcpPassword;

    /** HTTP client for requests. */
    private final OkHttpClient client;

    /** JSON object mapper. */
    private final ObjectMapper mapper = new ObjectMapper();

    /** Session ID from MCP server. */
    private String sessionId;

    /** URL for POST requests. */
    private String postUrl;

    /** SSE event source. */
    private EventSource sse;

    /** Generator for message IDs. */
    private final AtomicInteger messageIdGenerator = new AtomicInteger(1);

    /** Map of pending requests by ID. */
    private final ConcurrentHashMap<Integer, CompletableFuture<JsonNode>>
            pendingRequests = new ConcurrentHashMap<>();

    /**
     * Create a new MCP client.
     *
     * @param url         the MCP server URL
     * @param password    the MCP password (can be null)
     */
    public McpClient(final String url, final String password) {
        this.baseUrl = url;
        this.mcpPassword = password;
        this.client = new OkHttpClient.Builder()
                .readTimeout(0, TimeUnit.MILLISECONDS)
                .build();
    }

    /**
     * Connect to the MCP server.
     *
     * @return future that completes when connected
     */
    public CompletableFuture<Void> connect() {
        CompletableFuture<Void> future = new CompletableFuture<>();

        System.out.println("[*] Initiating MCP connection...");

        ObjectNode params = mapper.createObjectNode();
        params.put("protocolVersion", PROTOCOL_VERSION);
        params.withObject("/clientInfo").put("name", "JavaBotAgent");
        params.withObject("/clientInfo").put("version", "1.0.0");
        params.withObject("/capabilities").withObject("/tools");

        ObjectNode request = mapper.createObjectNode();
        request.put("jsonrpc", JSON_RPC_VERSION);
        request.put("id", messageIdGenerator.getAndIncrement());
        request.put("method", "initialize");
        request.set("params", params);

        try {
            String json = mapper.writeValueAsString(request);
            RequestBody body = RequestBody.create(
                    json, MediaType.parse("application/json"));
            Request.Builder reqBuilder = new Request.Builder()
                    .url(baseUrl)
                    .post(body)
                    .addHeader("Accept", "application/json, text/event-stream");

            if (mcpPassword != null && !mcpPassword.isEmpty()) {
                reqBuilder.addHeader(
                        "Authorization", "Bearer " + mcpPassword);
            }

            client.newCall(reqBuilder.build()).enqueue(new Callback() {
                @Override
                public void onFailure(
                        final Call call,
                        final IOException e) {
                    future.completeExceptionally(e);
                }

                @Override
                public void onResponse(
                        final Call call,
                        final Response response) throws IOException {
                    if (!response.isSuccessful()) {
                        String bodyStr = response.body() != null
                                ? response.body().string() : "";
                        future.completeExceptionally(
                                new RuntimeException(
                                        "Initial POST failed: "
                                        + response.code() + " " + bodyStr));
                        return;
                    }

                    String serverSessionId =
                            response.header("mcp-session-id");
                    if (serverSessionId == null) {
                        future.completeExceptionally(
                                new RuntimeException(
                                "No mcp-session-id in response"));
                        return;
                    }

                    sessionId = serverSessionId;
                    postUrl = baseUrl;

                    System.out.println(
                            "[+] Received MCP session ID: " + sessionId);

                    establishSse(future);
                }
            });

        } catch (Exception e) {
            future.completeExceptionally(e);
        }

        return future;
    }

    /**
     * Establish SSE connection.
     *
     * @param future the future to complete when connected
     */
    private void establishSse(final CompletableFuture<Void> future) {
        Request.Builder requestBuilder = new Request.Builder()
                .url(baseUrl)
                .addHeader("Accept", "text/event-stream")
                .addHeader("mcp-session-id", this.sessionId);

        if (mcpPassword != null && !mcpPassword.isEmpty()) {
            requestBuilder.addHeader(
                    "Authorization", "Bearer " + mcpPassword);
        }

        EventSource.Factory factory = EventSources.createFactory(client);
        sse = factory.newEventSource(
                requestBuilder.build(), new EventSourceListener() {
            @Override
            public void onEvent(
                    final EventSource eventSource,
                    final String id,
                    final String type,
                    final String data) {
                try {
                    if ("endpoint".equals(type)) {
                        postUrl = baseUrl + data;
                        if (!data.startsWith("http")
                                && !data.startsWith("/")) {
                            postUrl = baseUrl + "?" + data;
                        } else if (data.startsWith("/")) {
                            int idx = baseUrl.indexOf("/mcp");
                            postUrl = baseUrl.substring(0, idx) + data;
                        } else {
                            postUrl = data;
                        }
                    } else if ("message".equals(type)) {
                        JsonNode json = mapper.readTree(data);
                        if (json.has("id")) {
                            int msgId = json.get("id").asInt();
                            CompletableFuture<JsonNode> reqFuture =
                                    pendingRequests.remove(msgId);
                            if (reqFuture != null) {
                                if (json.has("error")) {
                                    reqFuture.completeExceptionally(
                                            new RuntimeException(
                                                    json.get("error")
                                                            .toString()));
                                } else {
                                    reqFuture.complete(json.get("result"));
                                }
                            }
                        }
                    }
                } catch (Exception e) {
                    e.printStackTrace();
                }
            }

            @Override
            public void onOpen(
                    final EventSource eventSource,
                    final Response response) {
                if (!future.isDone()) {
                    future.complete(null);
                }
            }

            @Override
            public void onFailure(
                    final EventSource eventSource,
                    final Throwable t,
                    final Response response) {
                if (!future.isDone()) {
                    future.completeExceptionally(t != null
                            ? t
                            : new RuntimeException("SSE Connection failed"));
                }
            }
        });
    }

    /**
     * Send a JSON-RPC request.
     *
     * @param method the method name
     * @param params the parameters (can be null)
     * @return future with the result
     */
    public CompletableFuture<JsonNode> sendRequest(
            final String method,
            final ObjectNode params) {
        if (postUrl == null || sessionId == null) {
            CompletableFuture<JsonNode> err = new CompletableFuture<>();
            err.completeExceptionally(
                    new IllegalStateException(
                            "Not connected. Endpoint or session unknown."));
            return err;
        }

        int id = messageIdGenerator.getAndIncrement();
        ObjectNode request = mapper.createObjectNode();
        request.put("jsonrpc", JSON_RPC_VERSION);
        request.put("id", id);
        request.put("method", method);
        if (params != null) {
            request.set("params", params);
        }

        CompletableFuture<JsonNode> future = new CompletableFuture<>();
        pendingRequests.put(id, future);

        try {
            String json = mapper.writeValueAsString(request);
            RequestBody body = RequestBody.create(
                    json, MediaType.parse("application/json"));
            Request.Builder requestBuilder = new Request.Builder()
                    .url(postUrl)
                    .addHeader("mcp-session-id", this.sessionId)
                    .addHeader(
                            "Accept", "application/json, text/event-stream")
                    .post(body);

            if (mcpPassword != null && !mcpPassword.isEmpty()) {
                requestBuilder.addHeader(
                        "Authorization", "Bearer " + mcpPassword);
            }

            client.newCall(requestBuilder.build()).enqueue(new Callback() {
                @Override
                public void onFailure(
                        final Call call,
                        final IOException e) {
                    pendingRequests.remove(id);
                    future.completeExceptionally(e);
                }

                @Override
                public void onResponse(
                        final Call call,
                        final Response response) throws IOException {
                    if (!response.isSuccessful()) {
                        pendingRequests.remove(id);
                        String bodyStr = response.body() != null
                                ? response.body().string() : "";
                        future.completeExceptionally(
                                new RuntimeException(
                                        "HTTP POST failed: "
                                        + response.code() + " " + bodyStr));
                        return;
                    }

                    String responseBody = response.body() != null
                            ? response.body().string() : "";
                    if (responseBody != null
                            && !responseBody.trim().isEmpty()) {
                        try {
                            JsonNode jsonNode =
                                    mapper.readTree(responseBody);
                            if (jsonNode.has("id")
                                    && jsonNode.get("id").asInt() == id) {
                                pendingRequests.remove(id);
                                if (jsonNode.has("error")) {
                                    future.completeExceptionally(
                                            new RuntimeException(
                                                    jsonNode.get("error")
                                                            .toString()));
                                } else {
                                    future.complete(jsonNode.get("result"));
                                }
                                return;
                            }
                        } catch (Exception e) {
                            String[] lines = responseBody.split("\n");
                            for (String line : lines) {
                                if (line.startsWith("data:")) {
                                    String data = line.substring(5).trim();
                                    try {
                                        JsonNode jsonNode =
                                                mapper.readTree(data);
                                        if (jsonNode.has("id")
                                                && jsonNode.get("id")
                                                        .asInt() == id) {
                                            pendingRequests.remove(id);
                                            if (jsonNode.has("error")) {
                                                String errStr = jsonNode
                                                        .get("error")
                                                        .toString();
                                                future
                                                    .completeExceptionally(
                                                        new RuntimeException(
                                                                errStr));
                                            } else {
                                                future.complete(
                                                        jsonNode.get("result")
                                                );
                                            }
                                            return;
                                        }
                                    } catch (Exception ex) {
                                        // Ignore malformed data
                                    }
                                }
                            }
                        }
                    }
                }
            });
        } catch (Exception e) {
            pendingRequests.remove(id);
            future.completeExceptionally(e);
        }

        return future;
    }

    /**
     * Send a JSON-RPC notification.
     *
     * @param method the method name
     * @param params the parameters (can be null)
     * @return future that completes when sent
     */
    public CompletableFuture<Void> sendNotification(
            final String method,
            final ObjectNode params) {
        if (postUrl == null || sessionId == null) {
            CompletableFuture<Void> err = new CompletableFuture<>();
            err.completeExceptionally(
                    new IllegalStateException(
                            "Not connected. Endpoint or session unknown."));
            return err;
        }

        ObjectNode request = mapper.createObjectNode();
        request.put("jsonrpc", JSON_RPC_VERSION);
        request.put("method", method);
        if (params != null) {
            request.set("params", params);
        }

        CompletableFuture<Void> future = new CompletableFuture<>();

        try {
            String json = mapper.writeValueAsString(request);
            RequestBody body = RequestBody.create(
                    json, MediaType.parse("application/json"));
            Request.Builder requestBuilder = new Request.Builder()
                    .url(postUrl)
                    .addHeader("mcp-session-id", this.sessionId)
                    .addHeader(
                            "Accept", "application/json, text/event-stream")
                    .post(body);

            if (mcpPassword != null && !mcpPassword.isEmpty()) {
                requestBuilder.addHeader(
                        "Authorization", "Bearer " + mcpPassword);
            }

            client.newCall(requestBuilder.build()).enqueue(new Callback() {
                @Override
                public void onFailure(
                        final Call call,
                        final IOException e) {
                    future.completeExceptionally(e);
                }

                @Override
                public void onResponse(
                        final Call call,
                        final Response response) throws IOException {
                    if (!response.isSuccessful()) {
                        String bodyStr = response.body() != null
                                ? response.body().string() : "";
                        future.completeExceptionally(
                                new RuntimeException(
                                        "HTTP POST notification failed: "
                                        + response.code() + " " + bodyStr));
                        return;
                    }
                    future.complete(null);
                }
            });
        } catch (Exception e) {
            future.completeExceptionally(e);
        }

        return future;
    }

    /**
     * Initialize the MCP connection.
     *
     * @return future that completes when initialized
     */
    public CompletableFuture<Void> initialize() {
        return sendNotification("notifications/initialized", null);
    }

    /**
     * List available tools from MCP server.
     *
     * @return future with the tools list
     */
    public CompletableFuture<JsonNode> listTools() {
        return sendRequest("tools/list", null);
    }

    /**
     * Call a tool on the MCP server.
     *
     * @param name      the tool name
     * @param arguments the tool arguments
     * @return future with the tool result
     */
    public CompletableFuture<JsonNode> callTool(
            final String name,
            final ObjectNode arguments) {
        ObjectNode params = mapper.createObjectNode();
        params.put("name", name);
        params.set("arguments", arguments);
        return sendRequest("tools/call", params);
    }

    /**
     * Close the MCP client and release resources.
     */
    public void close() {
        if (sse != null) {
            sse.cancel();
        }
        client.dispatcher().executorService().shutdown();
        client.connectionPool().evictAll();
    }
}
