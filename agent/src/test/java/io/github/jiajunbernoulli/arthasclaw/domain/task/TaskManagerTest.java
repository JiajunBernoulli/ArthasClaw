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

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for TaskManager.
 */
class TaskManagerTest {

  private TaskManager taskManager;

  @BeforeEach
  void setUp() {
    taskManager = new TaskManager();
  }

  @Test
  @DisplayName("createTask should create and store a task")
  void testCreateTask() {
    Task task = taskManager.createTask("Test task");
    
    assertNotNull(task);
    assertEquals("Test task", task.getDescription());
    assertEquals(1, taskManager.getTaskCount());
    assertEquals(task, taskManager.getTask(task.getId()));
  }

  @Test
  @DisplayName("getTask should return null for non-existent task")
  void testGetNonExistentTask() {
    assertNull(taskManager.getTask("non_existent_id"));
  }

  @Test
  @DisplayName("getAllTasks should return all tasks")
  void testGetAllTasks() {
    Task task1 = taskManager.createTask("Task 1");
    Task task2 = taskManager.createTask("Task 2");
    
    List<Task> tasks = taskManager.getAllTasks();
    
    assertEquals(2, tasks.size());
    assertTrue(tasks.contains(task1));
    assertTrue(tasks.contains(task2));
  }

  @Test
  @DisplayName("getAllTasks should return a copy of tasks list")
  void testGetAllTasksReturnsCopy() {
    taskManager.createTask("Task 1");
    
    List<Task> tasks = taskManager.getAllTasks();
    tasks.clear();
    
    assertEquals(1, taskManager.getTaskCount());
  }

  @Test
  @DisplayName("startTask should execute task asynchronously")
  void testStartTask() throws InterruptedException {
    Task task = taskManager.createTask("Async task");
    AtomicBoolean executed = new AtomicBoolean(false);
    CountDownLatch latch = new CountDownLatch(1);
    
    taskManager.startTask(task, () -> {
      executed.set(true);
      latch.countDown();
    });
    
    assertTrue(latch.await(5, TimeUnit.SECONDS));
    assertTrue(executed.get());
    assertEquals(Task.Status.COMPLETED, task.getStatus());
  }

  @Test
  @DisplayName("startTask should throw for null task")
  void testStartTaskNull() {
    assertThrows(IllegalArgumentException.class, () -> {
      taskManager.startTask(null, () -> {});
    });
  }

  @Test
  @DisplayName("startTask should handle task failure")
  void testStartTaskFailure() throws InterruptedException {
    Task task = taskManager.createTask("Failing task");
    CountDownLatch latch = new CountDownLatch(1);
    
    taskManager.startTask(task, () -> {
      latch.countDown();
      throw new RuntimeException("Test error");
    });
    
    assertTrue(latch.await(5, TimeUnit.SECONDS));
    
    // Wait for status update
    Thread.sleep(100);
    
    assertEquals(Task.Status.FAILED, task.getStatus());
    assertTrue(task.getErrorMessage().contains("Test error"));
  }

  @Test
  @DisplayName("cancelTask should cancel running task")
  void testCancelTask() throws InterruptedException {
    Task task = taskManager.createTask("Cancellable task");
    CountDownLatch startedLatch = new CountDownLatch(1);
    CountDownLatch finishLatch = new CountDownLatch(1);
    
    taskManager.startTask(task, () -> {
      startedLatch.countDown();
      try {
        Thread.sleep(10000);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
      }
      finishLatch.countDown();
    });
    
    assertTrue(startedLatch.await(5, TimeUnit.SECONDS));
    
    boolean cancelled = taskManager.cancelTask(task.getId());
    
    assertTrue(cancelled);
    assertEquals(Task.Status.CANCELLED, task.getStatus());
  }

  @Test
  @DisplayName("cancelTask should return false for non-existent task")
  void testCancelNonExistentTask() {
    assertFalse(taskManager.cancelTask("non_existent"));
  }

  @Test
  @DisplayName("cancelTask should return false for completed task")
  void testCancelCompletedTask() throws InterruptedException {
    Task task = taskManager.createTask("Completed task");
    CountDownLatch latch = new CountDownLatch(1);
    
    taskManager.startTask(task, latch::countDown);
    
    assertTrue(latch.await(5, TimeUnit.SECONDS));
    Thread.sleep(100);
    
    assertFalse(taskManager.cancelTask(task.getId()));
  }

  @Test
  @DisplayName("getRunningTasks should return only running tasks")
  void testGetRunningTasks() throws InterruptedException {
    Task task1 = taskManager.createTask("Running task");
    Task task2 = taskManager.createTask("Completed task");
    
    CountDownLatch latch1 = new CountDownLatch(1);
    CountDownLatch latch2 = new CountDownLatch(1);
    CountDownLatch runningLatch = new CountDownLatch(1);
    
    // Start task1 that will run longer
    taskManager.startTask(task1, () -> {
      runningLatch.countDown();
      try {
        Thread.sleep(5000);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
      }
      latch1.countDown();
    });
    
    assertTrue(runningLatch.await(5, TimeUnit.SECONDS));
    
    // Start task2 that completes quickly
    taskManager.startTask(task2, latch2::countDown);
    
    assertTrue(latch2.await(5, TimeUnit.SECONDS));
    Thread.sleep(100);
    
    List<Task> runningTasks = taskManager.getRunningTasks();
    
    assertEquals(1, runningTasks.size());
    assertEquals(task1, runningTasks.get(0));
    
    // Cleanup
    taskManager.cancelTask(task1.getId());
  }

  @Test
  @DisplayName("cleanupCompletedTasks should remove completed tasks")
  void testCleanupCompletedTasks() throws InterruptedException {
    Task task1 = taskManager.createTask("Task 1");
    Task task2 = taskManager.createTask("Task 2");
    
    CountDownLatch latch = new CountDownLatch(2);
    taskManager.startTask(task1, latch::countDown);
    taskManager.startTask(task2, latch::countDown);
    
    assertTrue(latch.await(5, TimeUnit.SECONDS));
    Thread.sleep(100);
    
    assertEquals(2, taskManager.getTaskCount());
    
    taskManager.cleanupCompletedTasks();
    
    assertEquals(0, taskManager.getTaskCount());
  }

  @Test
  @DisplayName("cleanupCompletedTasks should not remove running tasks")
  void testCleanupRunningTasks() throws InterruptedException {
    Task task1 = taskManager.createTask("Running task");
    Task task2 = taskManager.createTask("Completed task");
    
    CountDownLatch latch1 = new CountDownLatch(1);
    CountDownLatch latch2 = new CountDownLatch(1);
    CountDownLatch runningLatch = new CountDownLatch(1);
    
    taskManager.startTask(task1, () -> {
      runningLatch.countDown();
      try {
        Thread.sleep(5000);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
      }
      latch1.countDown();
    });
    
    assertTrue(runningLatch.await(5, TimeUnit.SECONDS));
    
    taskManager.startTask(task2, latch2::countDown);
    assertTrue(latch2.await(5, TimeUnit.SECONDS));
    Thread.sleep(100);
    
    assertEquals(2, taskManager.getTaskCount());
    
    taskManager.cleanupCompletedTasks();
    
    assertEquals(1, taskManager.getTaskCount());
    assertNotNull(taskManager.getTask(task1.getId()));
    
    // Cleanup
    taskManager.cancelTask(task1.getId());
  }

  @Test
  @DisplayName("createTask should throw when max task limit reached")
  void testMaxTaskLimit() {
    // Create MAX_TASKS tasks
    for (int i = 0; i < 100; i++) {
      taskManager.createTask("Task " + i);
    }
    
    // Next create should throw
    assertThrows(IllegalStateException.class, () -> {
      taskManager.createTask("Task 101");
    });
  }

  @Test
  @DisplayName("shutdown should stop all running tasks")
  void testShutdown() throws InterruptedException {
    Task task = taskManager.createTask("Running task");
    
    taskManager.startTask(task, () -> {
      try {
        Thread.sleep(10000);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
      }
    });
    
    // Wait for task to start
    Thread.sleep(100);
    
    taskManager.shutdown();
    
    // Give some time for shutdown to take effect
    Thread.sleep(200);
    
    // Task should be cancelled or completed
    assertTrue(task.getStatus() == Task.Status.CANCELLED 
        || task.getStatus() == Task.Status.COMPLETED);
  }
}
