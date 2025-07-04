package com.rpc.api;

/**
 * 熔断器接口，定义了熔断器的核心行为。
 */
public interface CircuitBreaker {

    /**
     * 在执行业务逻辑之前调用，检查是否允许请求通过。
     * @return 如果允许请求通过则返回 true，否则返回 false。
     */
    boolean allowRequest();

    /**
     * 业务逻辑成功执行后调用，通知熔断器成功事件。
     */
    void recordSuccess();

    /**
     * 业务逻辑失败后调用，通知熔断器失败事件。
     */
    void recordFailure();

    /**
     * 获取当前熔断器的状态。
     * @return 当前状态。
     */
    CircuitBreakerState getState();

    /**
     * （可选）获取熔断器的名称或唯一标识。
     * @return 熔断器名称。
     */
    String getName();
}