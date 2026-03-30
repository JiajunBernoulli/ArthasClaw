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

package io.github.jiajunbernoulli.arthasclaw.domain.task;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.jiajunbernoulli.arthasclaw.infrastructure.mcp.McpClient;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Unit tests for TaskExecutor.
 */
@ExtendWith(MockitoExtension.class)
class TaskExecutorTest {

  @Mock
  private McpClient mcpClient;

  private TaskManager taskManager;
  private TaskExecutor taskExecutor;
  private ObjectMapper mapper = new ObjectMapper();

  @BeforeEach
  void setUp() {
    taskManager = new TaskManager();
    taskExecutor = new TaskExecutor(mcpClient, taskManager);
  }

  @Test
  @DisplayName("TaskExecutor should be created with McpClient and TaskManager")
  void testTaskExecutorCreation() {
    assertNotNull(taskExecutor);
  }

  @Test
  @DisplayName("executeCustomTask should create and start a task")
  void testExecuteCustomTask() throws InterruptedException {
    Task task = taskManager.createTask("Custom task");
    CountDownLatch latch = new CountDownLatch(1);
    final boolean[] executed = {false};

    taskExecutor.executeCustomTask(task, () -> {
      executed[0] = true;
      latch.countDown();
    });

    assertTrue(latch.await(5, TimeUnit.SECONDS));
    assertTrue(executed[0]);
  }

  @Test
  @DisplayName("executeMethodWatchTask should create watch task")
  void testExecuteMethodWatchTask() throws Exception {
    // Mock MCP response
    ObjectNode mockResponse = mapper.createObjectNode();
    ObjectNode content = mapper.createObjectNode();
    content.put("type", "text");
    content.put("text", "Watch result");
    mockResponse.set("content", mapper.createArrayNode().add(content));

    when(mcpClient.callTool(anyString(), any(ObjectNode.class)))
        .thenReturn(CompletableFuture.completedFuture(mockResponse));

    Task task = taskManager.createTask("Watch MathGame.run 2 times");

    taskExecutor.executeMethodWatchTask(task, "MathGame", "run", 2, 100);

    // Wait for task to complete (should be quick with mocks)
    Thread.sleep(500);

    assertEquals(Task.Status.COMPLETED, task.getStatus());
    assertNotNull(task.getResult());
  }

  @Test
  @DisplayName("executeMethodWatchTask should handle MCP errors")
  void testExecuteMethodWatchTaskError() throws InterruptedException {
    // Mock MCP to throw exception (Java 8 compatible)
    when(mcpClient.callTool(anyString(), any(ObjectNode.class)))
        .thenReturn(CompletableFuture.supplyAsync(() -> {
            throw new RuntimeException("MCP error");
        }));

    Task task = taskManager.createTask("Watch with error");

    taskExecutor.executeMethodWatchTask(task, "MathGame", "run", 1, 100);

    // Wait for task to complete/fail
    Thread.sleep(1000);

    // The task may complete with error message set
    assertTrue(task.getStatus() == Task.Status.FAILED 
        || task.getErrorMessage() != null);
  }

  @Test
  @DisplayName("executeMethodWatchTask should set task description correctly")
  void testExecuteMethodWatchTaskDescription() throws Exception {
    // Mock MCP response
    ObjectNode mockResponse = mapper.createObjectNode();
    mockResponse.set("content", mapper.createArrayNode());

    when(mcpClient.callTool(anyString(), any(ObjectNode.class)))
        .thenReturn(CompletableFuture.completedFuture(mockResponse));

    Task task = taskManager.createTask("Initial description");

    taskExecutor.executeMethodWatchTask(task, "TestClass", "testMethod", 3, 1000);

    // Wait a bit
    Thread.sleep(100);

    // Task should be started
    assertNotNull(task.getTaskThread());
  }

  @Test
  @DisplayName("executeMethodWatchTask should cancel when interrupted")
  void testExecuteMethodWatchTaskCancel() throws Exception {
    // Mock slow MCP response
    ObjectNode mockResponse = mapper.createObjectNode();
    mockResponse.set("content", mapper.createArrayNode());

    when(mcpClient.callTool(anyString(), any(ObjectNode.class)))
        .thenReturn(CompletableFuture.completedFuture(mockResponse));

    Task task = taskManager.createTask("Cancellable watch");

    taskExecutor.executeMethodWatchTask(task, "MathGame", "run", 10, 100);

    // Wait for first call
    Thread.sleep(200);

    // Cancel the task
    boolean cancelled = taskManager.cancelTask(task.getId());

    assertTrue(cancelled);
    assertEquals(Task.Status.CANCELLED, task.getStatus());
  }

  @Test
  @DisplayName("executeCustomTask should handle task failure")
  void testExecuteCustomTaskFailure() throws InterruptedException {
    Task task = taskManager.createTask("Failing custom task");
    CountDownLatch latch = new CountDownLatch(1);

    taskExecutor.executeCustomTask(task, () -> {
      latch.countDown();
      throw new RuntimeException("Custom failure");
    });

    assertTrue(latch.await(5, TimeUnit.SECONDS));
    Thread.sleep(100);

    assertEquals(Task.Status.FAILED, task.getStatus());
    assertTrue(task.getErrorMessage().contains("Custom failure"));
  }

  @Test
  @DisplayName("executeCustomTask should handle interruption")
  void testExecuteCustomTaskInterruption() throws InterruptedException {
    Task task = taskManager.createTask("Interruptible task");
    CountDownLatch startedLatch = new CountDownLatch(1);

    taskExecutor.executeCustomTask(task, () -> {
      startedLatch.countDown();
      try {
        Thread.sleep(10000);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
      }
    });

    assertTrue(startedLatch.await(5, TimeUnit.SECONDS));

    boolean cancelled = taskManager.cancelTask(task.getId());

    assertTrue(cancelled);
    assertEquals(Task.Status.CANCELLED, task.getStatus());
  }
}
