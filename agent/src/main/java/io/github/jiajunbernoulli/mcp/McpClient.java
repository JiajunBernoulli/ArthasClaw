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
package io.github.jiajunbernoulli.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import okhttp3.*;
import okhttp3.sse.EventSource;
import okhttp3.sse.EventSourceListener;
import okhttp3.sse.EventSources;

import java.io.IOException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

public class McpClient {
 private final String baseUrl;
 private final String mcpPassword;
 private final OkHttpClient client;
 private final ObjectMapper mapper = new ObjectMapper();
 private String sessionId;
 private String postUrl;
 private EventSource sse;

 private final AtomicInteger messageIdGenerator = new AtomicInteger(1);
 private final ConcurrentHashMap<Integer, CompletableFuture<JsonNode>> pendingRequests = new ConcurrentHashMap<>();

 public McpClient(String url, String mcpPassword) {
 this.baseUrl = url;
 this.mcpPassword = mcpPassword;
 this.client = new OkHttpClient.Builder()
 .readTimeout(0, TimeUnit.MILLISECONDS) // SSE needs 0 timeout
 .build();
 }

 public CompletableFuture<Void> connect() {
 CompletableFuture<Void> future = new CompletableFuture<>();

 System.out.println("[*] Initiating MCP connection...");

 // According to Arthas MCP implementation, the client must first send a valid POST request
 // (like an initialize message) with Accept: application/json.
 // The server will create a session and return it in the header `mcp-session-id`.
 // Then, the client connects to SSE using that session ID.

 // However, the standard streamable http protocol might be that the client first sends an initialize request!
 // Let's do the initial POST manually to get the session ID.

 ObjectNode params = mapper.createObjectNode();
 params.put("protocolVersion", "2024-11-05");
 params.withObject("/clientInfo").put("name", "JavaBotAgent").put("version", "1.0.0");
 params.withObject("/capabilities").withObject("/tools");

 ObjectNode request = mapper.createObjectNode();
 request.put("jsonrpc", "2.0");
 request.put("id", messageIdGenerator.getAndIncrement());
 request.put("method", "initialize");
 request.set("params", params);

 try {
 String json = mapper.writeValueAsString(request);
 RequestBody body = RequestBody.create(json, MediaType.parse("application/json"));
 Request.Builder reqBuilder = new Request.Builder()
 .url(baseUrl)
 .post(body)
 .addHeader("Accept", "application/json, text/event-stream");

 if (mcpPassword != null && !mcpPassword.isEmpty()) {
 reqBuilder.addHeader("Authorization", "Bearer " + mcpPassword);
 }

 client.newCall(reqBuilder.build()).enqueue(new Callback() {
 @Override
 public void onFailure(Call call, IOException e) {
 future.completeExceptionally(e);
 }

 @Override
 public void onResponse(Call call, Response response) throws IOException {
 if (!response.isSuccessful()) {
 future.completeExceptionally(new RuntimeException("Initial POST failed: " + response.code() + " " + response.body().string()));
 return;
 }

 String serverSessionId = response.header("mcp-session-id");
 if (serverSessionId == null) {
 future.completeExceptionally(new RuntimeException("No mcp-session-id returned in initial response"));
 return;
 }

 sessionId = serverSessionId;
 postUrl = baseUrl;

 System.out.println("[+] Received MCP session ID: " + sessionId);

 // The server already replied to the initialize request in the POST response.
 // Now we can establish the SSE connection for future notifications.
 establishSse(future);
 }
 });

 } catch (Exception e) {
 future.completeExceptionally(e);
 }

 return future;
 }

 private void establishSse(CompletableFuture<Void> future) {
 Request.Builder requestBuilder = new Request.Builder()
 .url(baseUrl)
 .addHeader("Accept", "text/event-stream")
 .addHeader("mcp-session-id", this.sessionId);

 if (mcpPassword != null && !mcpPassword.isEmpty()) {
 requestBuilder.addHeader("Authorization", "Bearer " + mcpPassword);
 }

 EventSource.Factory factory = EventSources.createFactory(client);
 sse = factory.newEventSource(requestBuilder.build(), new EventSourceListener() {
 @Override
 public void onEvent(EventSource eventSource, String id, String type, String data) {
 try {
 if ("endpoint".equals(type)) {
 postUrl = baseUrl + data;
 if (!data.startsWith("http") && !data.startsWith("/")) {
 postUrl = baseUrl + "?" + data;
 } else if (data.startsWith("/")) {
 postUrl = baseUrl.substring(0, baseUrl.indexOf("/mcp")) + data;
 } else {
 postUrl = data;
 }
 } else if ("message".equals(type)) {
 JsonNode json = mapper.readTree(data);
 if (json.has("id")) {
 int msgId = json.get("id").asInt();
 CompletableFuture<JsonNode> reqFuture = pendingRequests.remove(msgId);
 if (reqFuture != null) {
 if (json.has("error")) {
 reqFuture.completeExceptionally(new RuntimeException(json.get("error").toString()));
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
 public void onOpen(EventSource eventSource, Response response) {
 // SSE connected successfully
 // Complete the initial connect future
 if (!future.isDone()) {
 future.complete(null);
 }
 }

 @Override
 public void onFailure(EventSource eventSource, Throwable t, Response response) {
 if (!future.isDone()) {
 future.completeExceptionally(t != null ? t : new RuntimeException("SSE Connection failed"));
 }
 }
 });
 }

 public CompletableFuture<JsonNode> sendRequest(String method, ObjectNode params) {
 if (postUrl == null || sessionId == null) {
 CompletableFuture<JsonNode> err = new CompletableFuture<>();
 err.completeExceptionally(new IllegalStateException("Not connected. Endpoint or session unknown."));
 return err;
 }

 int id = messageIdGenerator.getAndIncrement();
 ObjectNode request = mapper.createObjectNode();
 request.put("jsonrpc", "2.0");
 request.put("id", id);
 request.put("method", method);
 if (params != null) {
 request.set("params", params);
 }

 CompletableFuture<JsonNode> future = new CompletableFuture<>();
 pendingRequests.put(id, future);

 try {
 String json = mapper.writeValueAsString(request);
 RequestBody body = RequestBody.create(json, MediaType.parse("application/json"));
 Request.Builder requestBuilder = new Request.Builder()
 .url(postUrl)
 .addHeader("mcp-session-id", this.sessionId)
 .addHeader("Accept", "application/json, text/event-stream") // For POST responses
 .post(body);

 if (mcpPassword != null && !mcpPassword.isEmpty()) {
 requestBuilder.addHeader("Authorization", "Bearer " + mcpPassword);
 }

 client.newCall(requestBuilder.build()).enqueue(new Callback() {
 @Override
 public void onFailure(Call call, IOException e) {
 pendingRequests.remove(id);
 future.completeExceptionally(e);
 }

 @Override
 public void onResponse(Call call, Response response) throws IOException {
 if (!response.isSuccessful()) {
 pendingRequests.remove(id);
 future.completeExceptionally(new RuntimeException("HTTP POST failed: " + response.code() + " " + response.body().string()));
 return;
 }

 // In the HTTP transport, the response might be returned directly in the POST
 // It seems Arthas returns the response in SSE format in the POST body
 String responseBody = response.body().string();
 if (responseBody != null && !responseBody.trim().isEmpty()) {

 // Try parsing as standard JSON first
 try {
 JsonNode jsonNode = mapper.readTree(responseBody);
 if (jsonNode.has("id") && jsonNode.get("id").asInt() == id) {
 pendingRequests.remove(id);
 if (jsonNode.has("error")) {
 future.completeExceptionally(new RuntimeException(jsonNode.get("error").toString()));
 } else {
 future.complete(jsonNode.get("result"));
 }
 return;
 }
 } catch (Exception e) {
 // Not JSON, try parsing as SSE
 String[] lines = responseBody.split("\n");
 for (String line : lines) {
 if (line.startsWith("data:")) {
 String data = line.substring(5).trim();
 try {
 JsonNode jsonNode = mapper.readTree(data);
 if (jsonNode.has("id") && jsonNode.get("id").asInt() == id) {
 pendingRequests.remove(id);
 if (jsonNode.has("error")) {
 future.completeExceptionally(new RuntimeException(jsonNode.get("error").toString()));
 } else {
 future.complete(jsonNode.get("result"));
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
 // Otherwise, we wait for it via SSE (if it wasn't in the response body)
 }
 });
 } catch (Exception e) {
 pendingRequests.remove(id);
 future.completeExceptionally(e);
 }

 return future;
 }

 public CompletableFuture<Void> sendNotification(String method, ObjectNode params) {
 if (postUrl == null || sessionId == null) {
 CompletableFuture<Void> err = new CompletableFuture<>();
 err.completeExceptionally(new IllegalStateException("Not connected. Endpoint or session unknown."));
 return err;
 }

 ObjectNode request = mapper.createObjectNode();
 request.put("jsonrpc", "2.0");
 // No ID for notifications
 request.put("method", method);
 if (params != null) {
 request.set("params", params);
 }

 CompletableFuture<Void> future = new CompletableFuture<>();

 try {
 String json = mapper.writeValueAsString(request);
 RequestBody body = RequestBody.create(json, MediaType.parse("application/json"));
 Request.Builder requestBuilder = new Request.Builder()
 .url(postUrl)
 .addHeader("mcp-session-id", this.sessionId)
 .addHeader("Accept", "application/json, text/event-stream") // For POST responses
 .post(body);

 if (mcpPassword != null && !mcpPassword.isEmpty()) {
 requestBuilder.addHeader("Authorization", "Bearer " + mcpPassword);
 }

 client.newCall(requestBuilder.build()).enqueue(new Callback() {
 @Override
 public void onFailure(Call call, IOException e) {
 future.completeExceptionally(e);
 }

 @Override
 public void onResponse(Call call, Response response) throws IOException {
 if (!response.isSuccessful()) {
 future.completeExceptionally(new RuntimeException("HTTP POST notification failed: " + response.code() + " " + response.body().string()));
 return;
 }
 // Notifications don't expect a result, just successful transmission
 future.complete(null);
 }
 });
 } catch (Exception e) {
 future.completeExceptionally(e);
 }

 return future;
 }

 public CompletableFuture<Void> initialize() {
 // Since we already sent initialize in connect() to get the session ID,
 // we just need to send notifications/initialized here
 return sendNotification("notifications/initialized", null);
 }

 public CompletableFuture<JsonNode> listTools() {
 return sendRequest("tools/list", null);
 }

 public CompletableFuture<JsonNode> callTool(String name, ObjectNode arguments) {
 ObjectNode params = mapper.createObjectNode();
 params.put("name", name);
 params.set("arguments", arguments);
 return sendRequest("tools/call", params);
 }

 public void close() {
 if (sse != null) {
 sse.cancel();
 }
 client.dispatcher().executorService().shutdown();
 client.connectionPool().evictAll();
 }
}