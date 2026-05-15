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

package io.github.jiajunbernoulli.arthasclaw.application;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.slf4j.MDC;

/**
 * Manages session and request context for logging and tracing.
 * Uses SLF4J MDC (Mapped Diagnostic Context) to inject IDs into log messages.
 * 
 * <p>Session ID format: sess_yyyyMMdd_HHmmss_xxx (timestamp + 3-char random suffix)
 * Request ID format: req_XXX (3-digit sequential number per session)
 */
public class SessionContext {

  private static final String SESSION_ID_KEY = "sessionId";
  private static final String REQUEST_ID_KEY = "requestId";
  private static final String ITERATION_KEY = "iteration";

  private final String sessionId;
  private final AtomicInteger requestCounter = new AtomicInteger(0);

  /**
   * Create a new session context with a generated session ID.
   */
  public SessionContext() {
    // Generate session ID: sess_yyyyMMdd_HHmmss_xxx
    // Format: timestamp + 3-char random suffix for uniqueness
    String timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss"));
    String randomSuffix = UUID.randomUUID().toString().substring(0, 3);
    this.sessionId = "sess_" + timestamp + "_" + randomSuffix;
    
    // Set in MDC
    MDC.put(SESSION_ID_KEY, sessionId);
    MDC.put(REQUEST_ID_KEY, "-");
    MDC.put(ITERATION_KEY, "-");
  }

  /**
   * Get the session ID.
   *
   * @return session ID string (e.g., "sess_20240115_103045_a1b")
   */
  public String getSessionId() {
    return sessionId;
  }

  /**
   * Start a new request and return the request ID.
   * Updates MDC with the new request ID.
   *
   * @return request ID string (e.g., "req_001")
   */
  public String startRequest() {
    int requestNum = requestCounter.incrementAndGet();
    String requestId = String.format("req_%03d", requestNum);
    MDC.put(REQUEST_ID_KEY, requestId);
    MDC.put(ITERATION_KEY, "-");
    return requestId;
  }

  /**
   * Set the current iteration number in MDC.
   *
   * @param iteration the iteration number (1-based)
   */
  public void setIteration(int iteration) {
    MDC.put(ITERATION_KEY, String.format("%02d", iteration));
  }

  /**
   * Clear the current request context from MDC.
   * Should be called after a request completes.
   */
  public void endRequest() {
    MDC.put(REQUEST_ID_KEY, "-");
    MDC.put(ITERATION_KEY, "-");
  }

  /**
   * Clear all context from MDC.
   * Should be called when the session ends.
   */
  public void close() {
    MDC.clear();
  }

  /**
   * Get the current request ID from MDC.
   *
   * @return current request ID or "-" if not in a request
   */
  public static String getCurrentRequestId() {
    String requestId = MDC.get(REQUEST_ID_KEY);
    return requestId != null ? requestId : "-";
  }

  /**
   * Get the current session ID from MDC.
   *
   * @return current session ID or "-" if not in a session
   */
  public static String getCurrentSessionId() {
    String sessionId = MDC.get(SESSION_ID_KEY);
    return sessionId != null ? sessionId : "-";
  }

  @Override
  public String toString() {
    return String.format("SessionContext[%s, request=%s, iteration=%s]", 
            sessionId, 
            MDC.get(REQUEST_ID_KEY), 
            MDC.get(ITERATION_KEY));
  }
}
