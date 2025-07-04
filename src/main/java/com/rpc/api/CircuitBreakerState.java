package com.rpc.api;

/**
 * 熔断器的状态枚举。
 * CLOSED: 关闭状态，正常运行。
 * OPEN: 打开状态，阻止所有请求。
 * HALF_OPEN: 半开状态，允许少量请求尝试。
 */
public enum CircuitBreakerState {
    CLOSED,
    OPEN,
    HALF_OPEN
}
