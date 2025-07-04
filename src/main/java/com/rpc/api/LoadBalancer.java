package com.rpc.api;

import java.util.List;

/**
 * 负载均衡器接口。
 * 定义了从多个服务地址中选择一个地址的策略。
 */
public interface LoadBalancer {
    /**
     * 从提供的服务地址列表中选择一个地址。
     * @param serviceAddresses 可用的服务地址列表 (例如：["127.0.0.1:8080", "127.0.0.1:8081"])
     * @return 选择的服务地址，如果没有可用地址则返回null或抛出异常
     */
    String select(List<String> serviceAddresses);
}