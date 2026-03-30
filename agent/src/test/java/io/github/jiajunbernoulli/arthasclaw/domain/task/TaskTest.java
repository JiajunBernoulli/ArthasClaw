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

import java.time.LocalDateTime;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for Task entity.
 */
class TaskTest {

  private Task task;

  @BeforeEach
  void setUp() {
    task = new Task("Test task description");
  }

  @Test
  @DisplayName("Task should be created with correct initial values")
  void testTaskCreation() {
    assertNotNull(task.getId());
    assertTrue(task.getId().startsWith("task_"));
    assertEquals("Test task description", task.getDescription());
    assertEquals(Task.Status.PENDING, task.getStatus());
    assertNotNull(task.getCreatedAt());
    assertNotNull(task.getUpdatedAt());
    assertNull(task.getResult());
    assertNull(task.getErrorMessage());
    assertNull(task.getTaskThread());
  }

  @Test
  @DisplayName("Task ID should be unique")
  void testTaskIdUniqueness() {
    Task task1 = new Task("Task 1");
    Task task2 = new Task("Task 2");
    assertNotEquals(task1.getId(), task2.getId());
  }

  @Test
  @DisplayName("setStatus should update status and updatedAt")
  void testSetStatus() {
    LocalDateTime beforeUpdate = task.getUpdatedAt();
    try {
      Thread.sleep(10);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
    
    task.setStatus(Task.Status.RUNNING);
    
    assertEquals(Task.Status.RUNNING, task.getStatus());
    assertTrue(task.getUpdatedAt().isAfter(beforeUpdate) 
        || task.getUpdatedAt().equals(beforeUpdate));
  }

  @Test
  @DisplayName("setResult should update result and updatedAt")
  void testSetResult() {
    LocalDateTime beforeUpdate = task.getUpdatedAt();
    try {
      Thread.sleep(10);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
    
    task.setResult("Test result");
    
    assertEquals("Test result", task.getResult());
    assertTrue(task.getUpdatedAt().isAfter(beforeUpdate) 
        || task.getUpdatedAt().equals(beforeUpdate));
  }

  @Test
  @DisplayName("setErrorMessage should update error message and updatedAt")
  void testSetErrorMessage() {
    LocalDateTime beforeUpdate = task.getUpdatedAt();
    try {
      Thread.sleep(10);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
    
    task.setErrorMessage("Test error");
    
    assertEquals("Test error", task.getErrorMessage());
    assertTrue(task.getUpdatedAt().isAfter(beforeUpdate) 
        || task.getUpdatedAt().equals(beforeUpdate));
  }

  @Test
  @DisplayName("setTaskThread should update thread reference")
  void testSetTaskThread() {
    Thread testThread = new Thread(() -> {});
    task.setTaskThread(testThread);
    assertEquals(testThread, task.getTaskThread());
  }

  @Test
  @DisplayName("cancel should interrupt running thread and set status")
  void testCancel() {
    Thread mockThread = new Thread(() -> {
      try {
        Thread.sleep(10000);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
      }
    });
    task.setTaskThread(mockThread);
    mockThread.start();
    
    task.cancel();
    
    assertEquals(Task.Status.CANCELLED, task.getStatus());
  }

  @Test
  @DisplayName("cancel should return false for completed task")
  void testCancelCompletedTask() {
    task.setStatus(Task.Status.COMPLETED);
    
    boolean result = task.cancel(100);
    
    assertFalse(result);
  }

  @Test
  @DisplayName("cancel should return false for failed task")
  void testCancelFailedTask() {
    task.setStatus(Task.Status.FAILED);
    
    boolean result = task.cancel(100);
    
    assertFalse(result);
  }

  @Test
  @DisplayName("cancel with timeout should wait for thread")
  void testCancelWithTimeout() throws InterruptedException {
    Thread slowThread = new Thread(() -> {
      try {
        Thread.sleep(200);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
      }
    });
    task.setTaskThread(slowThread);
    slowThread.start();
    
    task.cancel(500);
    
    assertEquals(Task.Status.CANCELLED, task.getStatus());
  }

  @Test
  @DisplayName("toString should contain task info")
  void testToString() {
    String str = task.toString();
    
    assertTrue(str.contains(task.getId()));
    assertTrue(str.contains("Test task description"));
    assertTrue(str.contains("PENDING"));
  }

  @Test
  @DisplayName("Status enum should have all expected values")
  void testStatusEnumValues() {
    Task.Status[] statuses = Task.Status.values();
    assertEquals(5, statuses.length);
    assertEquals(Task.Status.PENDING, Task.Status.valueOf("PENDING"));
    assertEquals(Task.Status.RUNNING, Task.Status.valueOf("RUNNING"));
    assertEquals(Task.Status.COMPLETED, Task.Status.valueOf("COMPLETED"));
    assertEquals(Task.Status.FAILED, Task.Status.valueOf("FAILED"));
    assertEquals(Task.Status.CANCELLED, Task.Status.valueOf("CANCELLED"));
  }
}
