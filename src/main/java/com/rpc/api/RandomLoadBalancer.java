package com.rpc.api;

import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

/**
 * 随机负载均衡器实现。
 */
public class RandomLoadBalancer implements LoadBalancer {
    @Override
    public String select(List<String> serviceAddresses) {
        if (serviceAddresses == null || serviceAddresses.isEmpty()) {
            return null;
        }
        return serviceAddresses.get(ThreadLocalRandom.current().nextInt(serviceAddresses.size()));
    }
}