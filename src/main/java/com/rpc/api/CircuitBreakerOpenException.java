package com.rpc.api;

/**
 * RpcClient类，现在使用Netty进行网络通信和ZooKeeper进行服务发现。
 * 改进了连接复用和请求ID匹配。
 */

// CircuitBreakerOpenException 类定义
public class CircuitBreakerOpenException extends RuntimeException {
    public CircuitBreakerOpenException(String message) {
        super(message);
    }
}
