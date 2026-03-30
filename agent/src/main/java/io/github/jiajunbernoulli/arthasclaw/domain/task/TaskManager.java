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

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * TaskManager handles the creation, tracking, and management of asynchronous tasks.
 */
public class TaskManager {

  private static final Logger log = LoggerFactory.getLogger(TaskManager.class);
  private static final int MAX_TASKS = 100;
  private static final int MAX_CONCURRENT_TASKS = 10;

  private final Map<String, Task> tasks = new ConcurrentHashMap<>();
  private final ExecutorService executor;
  private final AtomicInteger threadCounter = new AtomicInteger(0);

  /**
   * Create TaskManager with default thread pool.
   */
  public TaskManager() {
    this.executor = Executors.newCachedThreadPool(new ThreadFactory() {
      @Override
      public Thread newThread(Runnable r) {
        Thread t = new Thread(r, "task-worker-" + threadCounter.incrementAndGet());
        t.setDaemon(true);
        return t;
      }
    });
  }

  /**
   * Create a new task with the given description.
   *
   * @param description task description
   * @return created task
   * @throws IllegalStateException if max task limit is reached
   */
  public Task createTask(String description) {
    // Auto cleanup when approaching limit
    if (tasks.size() >= MAX_TASKS * 0.8) {
      cleanupCompletedTasks();
    }
    
    // Check limit
    if (tasks.size() >= MAX_TASKS) {
      throw new IllegalStateException("Maximum task limit reached (" + MAX_TASKS + ")");
    }
    
    Task task = new Task(description);
    tasks.put(task.getId(), task);
    log.info("Created task: {}", task);
    return task;
  }

  /**
   * Start a task asynchronously.
   *
   * @param task task to start
   * @param taskRunnable task logic
   */
  public void startTask(Task task, Runnable taskRunnable) {
    if (task == null) {
      throw new IllegalArgumentException("Task cannot be null");
    }

    executor.submit(() -> {
      try {
        task.setStatus(Task.Status.RUNNING);
        task.setTaskThread(Thread.currentThread());
        log.info("Started task: {}", task.getId());
        
        taskRunnable.run();
        
        if (task.getStatus() == Task.Status.RUNNING) {
          task.setStatus(Task.Status.COMPLETED);
        }
        log.info("Task finished: {} with status {}", task.getId(), task.getStatus());
      } catch (Exception e) {
        if (e instanceof InterruptedException) {
          if (task.getStatus() != Task.Status.CANCELLED) {
            task.setStatus(Task.Status.CANCELLED);
            task.setErrorMessage("Task interrupted");
          }
          log.info("Task cancelled: {}", task.getId());
        } else {
          task.setStatus(Task.Status.FAILED);
          task.setErrorMessage(e.getMessage());
          log.error("Task failed: {}", task.getId(), e);
        }
      }
    });
  }

  /**
   * Get all tasks.
   *
   * @return list of all tasks
   */
  public List<Task> getAllTasks() {
    return new ArrayList<>(tasks.values());
  }

  /**
   * Get task by ID.
   *
   * @param taskId task ID
   * @return task or null if not found
   */
  public Task getTask(String taskId) {
    return tasks.get(taskId);
  }

  /**
   * Cancel a task by ID.
   *
   * @param taskId task ID
   * @return true if task was cancelled, false if task not found
   */
  public boolean cancelTask(String taskId) {
    Task task = tasks.get(taskId);
    if (task == null) {
      return false;
    }

    if (task.getStatus() == Task.Status.RUNNING || task.getStatus() == Task.Status.PENDING) {
      task.cancel();
      log.info("Cancelled task: {}", taskId);
      return true;
    }

    return false;
  }

  /**
   * Remove completed tasks to clean up memory.
   */
  public void cleanupCompletedTasks() {
    List<String> completedTaskIds = new ArrayList<>();
    for (Task task : tasks.values()) {
      if (task.getStatus() == Task.Status.COMPLETED 
          || task.getStatus() == Task.Status.FAILED 
          || task.getStatus() == Task.Status.CANCELLED) {
        completedTaskIds.add(task.getId());
      }
    }

    for (String taskId : completedTaskIds) {
      tasks.remove(taskId);
      log.debug("Cleaned up task: {}", taskId);
    }
  }

  /**
   * Get the number of tasks.
   *
   * @return task count
   */
  public int getTaskCount() {
    return tasks.size();
  }

  /**
   * Get running tasks.
   *
   * @return list of running tasks
   */
  public List<Task> getRunningTasks() {
    List<Task> runningTasks = new ArrayList<>();
    for (Task task : tasks.values()) {
      if (task.getStatus() == Task.Status.RUNNING) {
        runningTasks.add(task);
      }
    }
    return runningTasks;
  }

  /**
   * Shutdown the task manager and executor.
   * Attempts to stop all running tasks.
   */
  public void shutdown() {
    // Cancel all running tasks
    for (Task task : tasks.values()) {
      if (task.getStatus() == Task.Status.RUNNING || task.getStatus() == Task.Status.PENDING) {
        task.cancel(1000);
      }
    }
    
    executor.shutdownNow();
    log.info("TaskManager shutdown complete");
  }
}
