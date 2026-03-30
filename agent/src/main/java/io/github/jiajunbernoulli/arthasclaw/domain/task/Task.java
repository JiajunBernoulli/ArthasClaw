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

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Task model class representing an asynchronous task.
 */
public class Task {

  /**
   * Task status enum.
   */
  public enum Status {
    PENDING,
    RUNNING,
    COMPLETED,
    FAILED,
    CANCELLED
  }

  private final String id;
  private final String description;
  private final LocalDateTime createdAt;
  private volatile LocalDateTime updatedAt;
  private volatile Status status;
  private volatile String result;
  private volatile String errorMessage;
  private volatile Thread taskThread;

  /**
   * Create a new task with the given description.
   *
   * @param description task description
   */
  public Task(String description) {
    this.id = "task_" + UUID.randomUUID().toString().substring(0, 8);
    this.description = description;
    this.createdAt = LocalDateTime.now();
    this.updatedAt = LocalDateTime.now();
    this.status = Status.PENDING;
    this.result = null;
    this.errorMessage = null;
    this.taskThread = null;
  }

  /**
   * Get task ID.
   *
   * @return task ID
   */
  public String getId() {
    return id;
  }

  /**
   * Get task description.
   *
   * @return task description
   */
  public String getDescription() {
    return description;
  }

  /**
   * Get task creation time.
   *
   * @return creation time
   */
  public LocalDateTime getCreatedAt() {
    return createdAt;
  }

  /**
   * Get task last update time.
   *
   * @return update time
   */
  public LocalDateTime getUpdatedAt() {
    return updatedAt;
  }

  /**
   * Get task status.
   *
   * @return task status
   */
  public Status getStatus() {
    return status;
  }

  /**
   * Set task status.
   *
   * @param status new status
   */
  public void setStatus(Status status) {
    this.status = status;
    this.updatedAt = LocalDateTime.now();
  }

  /**
   * Get task result.
   *
   * @return task result
   */
  public String getResult() {
    return result;
  }

  /**
   * Set task result.
   *
   * @param result task result
   */
  public void setResult(String result) {
    this.result = result;
    this.updatedAt = LocalDateTime.now();
  }

  /**
   * Get error message.
   *
   * @return error message
   */
  public String getErrorMessage() {
    return errorMessage;
  }

  /**
   * Set error message.
   *
   * @param errorMessage error message
   */
  public void setErrorMessage(String errorMessage) {
    this.errorMessage = errorMessage;
    this.updatedAt = LocalDateTime.now();
  }

  /**
   * Get task thread.
   *
   * @return task thread
   */
  public Thread getTaskThread() {
    return taskThread;
  }

  /**
   * Set task thread.
   *
   * @param taskThread task thread
   */
  public void setTaskThread(Thread taskThread) {
    this.taskThread = taskThread;
  }

  /**
   * Cancel the task.
   *
   * @param timeoutMs maximum time to wait for thread termination (0 = no wait)
   * @return true if task was cancelled, false if already completed
   */
  public boolean cancel(long timeoutMs) {
    if (status == Status.COMPLETED || status == Status.FAILED) {
      return false;
    }
    
    Thread thread = taskThread;
    if (thread != null && thread.isAlive()) {
      thread.interrupt();
      if (timeoutMs > 0) {
        try {
          thread.join(timeoutMs);
        } catch (InterruptedException e) {
          Thread.currentThread().interrupt();
        }
      }
    }
    
    if (status != Status.COMPLETED && status != Status.FAILED) {
      setStatus(Status.CANCELLED);
      return true;
    }
    return false;
  }

  /**
   * Cancel the task without waiting.
   */
  public void cancel() {
    cancel(0);
  }

  @Override
  public String toString() {
    return String.format("Task{id='%s', description='%s', status=%s, createdAt=%s, updatedAt=%s}",
        id, description, status, createdAt, updatedAt);
  }
}
