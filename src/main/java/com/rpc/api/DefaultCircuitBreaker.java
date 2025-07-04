package com.rpc.api;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference; // 导入 AtomicReference

/**
 * 默认的熔断器实现。
 * 包含 CLOSED, OPEN, HALF_OPEN 三种状态。
 */
public class DefaultCircuitBreaker implements CircuitBreaker {

    private final String name; // 熔断器名称，通常是服务接口名或服务分组名
    private final AtomicReference<CircuitBreakerState> stateRef; // 使用 AtomicReference 保证状态转换的线程安全

    // CLOSED -> OPEN 阈值
    private final int failureThreshold; // 连续失败次数阈值
    private AtomicInteger failureCount; // 当前连续失败次数

    // OPEN -> HALF_OPEN 冷却时间
    private final long openTimeoutMs; // OPEN状态持续的时间（毫秒）
    private AtomicLong lastFailureTime; // 上次失败的时间戳，用于计算冷却时间

    // HALF_OPEN -> CLOSED/OPEN 尝试次数
    private final int halfOpenTestAttempts; // 半开状态下允许尝试的成功请求次数
    private AtomicInteger halfOpenSuccessCount; // 半开状态下已成功的请求次数

    public DefaultCircuitBreaker(String name, int failureThreshold, long openTimeoutMs, int halfOpenTestAttempts) {
        this.name = name;
        this.failureThreshold = failureThreshold;
        this.openTimeoutMs = openTimeoutMs;
        this.halfOpenTestAttempts = halfOpenTestAttempts;

        this.stateRef = new AtomicReference<>(CircuitBreakerState.CLOSED); // 初始化为 CLOSED 状态
        this.failureCount = new AtomicInteger(0);
        this.lastFailureTime = new AtomicLong(0);
        this.halfOpenSuccessCount = new AtomicInteger(0);

        System.out.println("Circuit Breaker [" + name + "] initialized to CLOSED. Threshold: " + failureThreshold + ", Timeout: " + openTimeoutMs + "ms, Half-Open Attempts: " + halfOpenTestAttempts);
    }

    @Override
    public String getName() {
        return name;
    }

    @Override
    public CircuitBreakerState getState() {
        return stateRef.get(); // 获取当前状态
    }

    @Override
    public boolean allowRequest() {
        CircuitBreakerState currentState = stateRef.get();
        if (currentState == CircuitBreakerState.CLOSED) {
            // 在关闭状态下，始终允许请求
            return true;
        } else if (currentState == CircuitBreakerState.OPEN) {
            // 在打开状态下，检查是否到了尝试半开的时间
            long currentTime = System.currentTimeMillis();
            if (currentTime - lastFailureTime.get() > openTimeoutMs) {
                // 冷却时间已过，尝试进入半开状态
                if (stateRef.compareAndSet(CircuitBreakerState.OPEN, CircuitBreakerState.HALF_OPEN)) {
                    System.out.println("Circuit Breaker [" + name + "] transitioned from OPEN to HALF_OPEN.");
                    halfOpenSuccessCount.set(0); // 重置半开状态的成功计数
                    return true; // 允许第一个请求通过进行探测
                } else {
                    // 另一个线程已经将状态设为HALF_OPEN，或者状态已变，继续阻止
                    return false;
                }
            } else {
                // 冷却时间未过，继续阻止请求
                return false;
            }
        } else if (currentState == CircuitBreakerState.HALF_OPEN) {
            // 在半开状态下，只允许少数请求通过。
            // 这里我们简化为，如果当前状态是HALF_OPEN就允许请求通过，
            // 具体的计数和转换在 recordSuccess/recordFailure 中处理。
            // 生产级熔断器可能会在这里引入一个信号量来严格控制通过的请求数量。
            return true;
        }
        return false; // 默认不应该到达这里
    }

    @Override
    public void recordSuccess() {
        CircuitBreakerState currentState = stateRef.get();
        if (currentState == CircuitBreakerState.CLOSED) {
            // 成功时，重置失败计数
            failureCount.set(0);
        } else if (currentState == CircuitBreakerState.HALF_OPEN) {
            int currentSuccesses = halfOpenSuccessCount.incrementAndGet();
            if (currentSuccesses >= halfOpenTestAttempts) {
                // 半开状态下，探测成功达到阈值，切换到关闭状态
                if (stateRef.compareAndSet(CircuitBreakerState.HALF_OPEN, CircuitBreakerState.CLOSED)) {
                    System.out.println("Circuit Breaker [" + name + "] transitioned from HALF_OPEN to CLOSED (success).");
                    failureCount.set(0); // 成功关闭后重置失败计数
                }
            }
        }
    }

    @Override
    public void recordFailure() {
        CircuitBreakerState currentState = stateRef.get();
        if (currentState == CircuitBreakerState.CLOSED) {
            int currentFailures = failureCount.incrementAndGet();
            if (currentFailures >= failureThreshold) {
                // 连续失败次数达到阈值，切换到打开状态
                if (stateRef.compareAndSet(CircuitBreakerState.CLOSED, CircuitBreakerState.OPEN)) {
                    System.out.println("Circuit Breaker [" + name + "] transitioned from CLOSED to OPEN (failure threshold reached).");
                    lastFailureTime.set(System.currentTimeMillis()); // 记录失败时间
                }
            }
        } else if (currentState == CircuitBreakerState.HALF_OPEN) {
            // 半开状态下，探测失败，重新切换到打开状态
            if (stateRef.compareAndSet(CircuitBreakerState.HALF_OPEN, CircuitBreakerState.OPEN)) {
                System.out.println("Circuit Breaker [" + name + "] transitioned from HALF_OPEN to OPEN (probe failed).");
                lastFailureTime.set(System.currentTimeMillis()); // 重新记录失败时间
            }
        }
    }
}