package io.github.jiajunbernoulli.arthasclaw.examples;

import java.lang.management.ManagementFactory;

/**
 * Deadlock Demo - A simple example demonstrating thread deadlock.
 * 
 * This program creates two threads that each try to acquire two locks
 * in different orders, resulting in a classic deadlock scenario.
 * 
 * Usage:
 *   1. Compile: javac DeadlockDemo.java
 *   2. Run: java -cp . io.github.jiajunbernoulli.arthasclaw.examples.DeadlockDemo
 *   3. Use ArthasClaw to attach and diagnose the deadlock
 */
public class DeadlockDemo {

    private static final Object lockA = new Object();
    private static final Object lockB = new Object();

    /**
     * Get current process PID (Java 8 compatible)
     */
    private static String getPid() {
        String name = ManagementFactory.getRuntimeMXBean().getName();
        return name.split("@")[0];
    }

    public static void main(String[] args) {
        System.out.println("=== Deadlock Demo ===");
        System.out.println("PID: " + getPid());
        System.out.println("Starting deadlock simulation...\n");

        // Thread 1: acquires lockA first, then tries to acquire lockB
        Thread thread1 = new Thread(() -> {
            synchronized (lockA) {
                System.out.println("[Thread-1] Acquired lockA");
                try {
                    Thread.sleep(100); // Give Thread-2 time to acquire lockB
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                System.out.println("[Thread-1] Trying to acquire lockB...");
                synchronized (lockB) {
                    System.out.println("[Thread-1] Acquired lockB - This should not happen!");
                }
            }
        }, "DeadlockThread-1");

        // Thread 2: acquires lockB first, then tries to acquire lockA
        Thread thread2 = new Thread(() -> {
            synchronized (lockB) {
                System.out.println("[Thread-2] Acquired lockB");
                try {
                    Thread.sleep(100); // Give Thread-1 time to acquire lockA
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                System.out.println("[Thread-2] Trying to acquire lockA...");
                synchronized (lockA) {
                    System.out.println("[Thread-2] Acquired lockA - This should not happen!");
                }
            }
        }, "DeadlockThread-2");

        // Start both threads
        thread1.start();
        thread2.start();

        System.out.println("\nBoth threads started. They should be deadlocked now.");
        System.out.println("Use ArthasClaw to attach to PID: " + getPid());
        System.out.println("Then ask: 'Check for thread deadlock' or use /skill to install deadlock-analyzer skill\n");

        // Keep the main thread alive
        try {
            thread1.join();
            thread2.join();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        System.out.println("Program completed (should not reach here if deadlock occurs)");
    }
}
