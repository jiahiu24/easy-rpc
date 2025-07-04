package com.rpc.client;

import com.rpc.api.*;
import io.netty.bootstrap.Bootstrap;
import io.netty.channel.*;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioSocketChannel;
import org.apache.zookeeper.WatchedEvent;
import org.apache.zookeeper.Watcher;
import org.apache.zookeeper.ZooKeeper;
import org.apache.zookeeper.data.Stat; // 导入 Stat 类

import java.io.IOException;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import java.util.Map; // 导入 Map
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;
import java.util.ServiceLoader;
// 导入 Guava Retrying 相关的类
import com.github.rholder.retry.*; // 导入所有 Retrying 相关的类
import com.google.common.base.Predicates; // 用于定义重试条件

public class RpcClient {
    private String zkAddress;
    private ZooKeeper zk;
    private final String ZK_REGISTRY_ROOT_PATH = "/rpc";
    private final String ZK_SERVICES_PATH = ZK_REGISTRY_ROOT_PATH + "/services";

    private volatile List<String> serviceAddresses = new CopyOnWriteArrayList<>();
    private EventLoopGroup workerGroup;
    private ConcurrentHashMap<String, RpcFuture> pendingRpcFutures = new ConcurrentHashMap<>();

    // 客户端 Channel 缓存，每个服务地址对应一个 Channel
    private ConcurrentHashMap<String, Channel> cachedChannels = new ConcurrentHashMap<>();
    private final RpcClientHandler rpcClientHandler; // 单例Handler，处理所有Channel的响应
    private Serializer serializer;
    private LoadBalancer loadBalancer;
    //新增：重试服务的白名单/黑名单或其他策略配置
    //这里简单地用一个Set来表示哪些接口需要重试
    private final ConcurrentHashMap<String, Retryer<RpcResponse>> retryerMap = new ConcurrentHashMap<>();
    private final List<String> retryableServices = new CopyOnWriteArrayList<>(); // 可以配置哪些服务需要重试
    private final long RETRY_INTERVAL_MS = 100; // 重试间隔
    private final int MAX_RETRY_ATTEMPTS = 3;   // 最大重试次数

    // 新增：熔断器相关配置和存储
    private final ConcurrentHashMap<String, CircuitBreaker> circuitBreakerMap = new ConcurrentHashMap<>();
    private final int FAILURE_THRESHOLD = 5;      // 连续失败次数达到这个值，熔断器打开
    private final long OPEN_TIMEOUT_MS = 5000;    // 熔断器打开后持续的时间（冷却时间）
    private final int HALF_OPEN_TEST_ATTEMPTS = 1; // 半开状态下允许尝试的成功请求次数

    public RpcClient(String zkAddress) {
        this.zkAddress = zkAddress;
        connectZooKeeper();
        loadSerializer(); // 新增：加载序列化器
        loadLoadBalancer();
        initNettyClient();
        this.rpcClientHandler = new RpcClientHandler(pendingRpcFutures);

        // 初始化重试策略
        // 添加需要重试的服务接口名
        retryableServices.add("com.rpc.server.Calculator");

        // 这里以 Calculator 为例，您可以根据实际需求动态创建
        circuitBreakerMap.put("com.rpc.server.Calculator", new DefaultCircuitBreaker(
                "com.rpc.server.Calculator", FAILURE_THRESHOLD, OPEN_TIMEOUT_MS, HALF_OPEN_TEST_ATTEMPTS));

        System.out.println("RpcClient initialized.");
    }

    private void loadLoadBalancer() {
        ServiceLoader<LoadBalancer> loader = ServiceLoader.load(LoadBalancer.class);
        for (LoadBalancer lb : loader) {
            this.loadBalancer = lb;
            System.out.println("[SPI] Loaded LoadBalancer: " + lb.getClass().getSimpleName());
            break; // 只加载一个
        }
        if (this.loadBalancer == null) {
            // 如果没有找到，可以使用默认的随机负载均衡
            this.loadBalancer = new RandomLoadBalancer();
            System.out.println("[SPI] No LoadBalancer implementation found via SPI, using default RandomLoadBalancer.");
        }
    }

    private void loadSerializer() {
        ServiceLoader<Serializer> loader = ServiceLoader.load(Serializer.class);
        for (Serializer s : loader) {
            this.serializer = s;
            System.out.println("[SPI] Loaded Serializer: " + s.getClass().getSimpleName());
            break; // 只加载一个
        }
        if (this.serializer == null) {
            throw new RuntimeException("No Serializer implementation found via SPI!");
        }
    }

    private void initNettyClient() {
        workerGroup = new NioEventLoopGroup();
    }

    public <T> T getProxy(Class<T> interfaceClass) {
        discoverService(interfaceClass.getName());

        return (T) Proxy.newProxyInstance(
                interfaceClass.getClassLoader(),
                new Class<?>[]{interfaceClass},
                new InvocationHandler() {
                    @Override
                    public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
                        final String serviceInterfaceName = interfaceClass.getName();

                        // 获取或创建对应服务的熔断器
                        CircuitBreaker circuitBreaker = circuitBreakerMap.computeIfAbsent(serviceInterfaceName, k ->
                                new DefaultCircuitBreaker(k, FAILURE_THRESHOLD, OPEN_TIMEOUT_MS, HALF_OPEN_TEST_ATTEMPTS));

                        // ==== 熔断器前置检查 ====
                        if (!circuitBreaker.allowRequest()) {
                            // 熔断器处于 OPEN 状态，或半开状态下不被允许通过
                            System.out.println("Circuit Breaker [" + serviceInterfaceName + "] is " + circuitBreaker.getState() + ". Request blocked.");
                            throw new CircuitBreakerOpenException("Circuit Breaker for " + serviceInterfaceName + " is OPEN.");
                        }

                        RpcResponse response = null;
                        Throwable rpcCallException = null; // 用于捕获 performRpcCall 抛出的异常

                        try {
                            // 决定是否使用重试
                            if (retryableServices.contains(serviceInterfaceName)) {
                                Retryer<RpcResponse> retryer = retryerMap.computeIfAbsent(serviceInterfaceName, k ->
                                        RetryerBuilder.<RpcResponse>newBuilder()
                                                .retryIfExceptionOfType(RuntimeException.class)
                                                .retryIfResult(Predicates.isNull())
                                                .withWaitStrategy(WaitStrategies.fixedWait(RETRY_INTERVAL_MS, TimeUnit.MILLISECONDS))
                                                .withStopStrategy(StopStrategies.stopAfterAttempt(MAX_RETRY_ATTEMPTS))
                                                .build());
                                try {
                                    response = retryer.call(() -> {
                                        try {
                                            return performRpcCall(serviceInterfaceName, method, args);
                                        } catch (Throwable e) {
                                            throw new RuntimeException(e);
                                        }
                                    });
                                } catch (RetryException e) {
                                    // 所有重试都失败了
                                    System.err.println("RPC call (service: " + serviceInterfaceName + ", method: " + method.getName() + ") failed after multiple retries: " + e.getMessage());
                                    // 检查最终的失败原因，如果是InvocationTargetException，取出原始异常
                                    rpcCallException = e.getLastFailedAttempt().getExceptionCause(); // <-- 修正这里的 'has'
                                }
                            } else {
                                // 对于不需要重试的服务，直接进行调用
                                response = performRpcCall(serviceInterfaceName, method, args);
                            }

                            // 如果 response 为 null 且 rpcCallException 也为 null，说明有些未处理的异常
                            if (response == null && rpcCallException == null) {
                                rpcCallException = new RuntimeException("Unknown RPC call failure for " + serviceInterfaceName + "." + method.getName());
                            }

                            if (rpcCallException == null) { // RPC 调用成功
                                circuitBreaker.recordSuccess();
                                // 如果返回了 RpcResponse 但其 internal success 标志为 false，也视为失败
                                if (!response.isSuccess()) {
                                    circuitBreaker.recordFailure();
                                    throw new InvocationTargetException(response.getException());
                                }
                                return response.getResult(); // 返回实际的结果
                            } else { // RPC 调用失败 ( سواء是重试后仍失败，还是直接调用失败 )
                                circuitBreaker.recordFailure();
                                throw rpcCallException; // 抛出原始异常
                            }

                        } catch (Throwable e) {
                            // 捕获所有从 performRpcCall 或重试器中抛出的异常
                            // 如果是 CircuitBreakerOpenException，就不再记录失败
                            if (!(e instanceof CircuitBreakerOpenException)) {
                                circuitBreaker.recordFailure(); // 记录失败，如果熔断器未打开
                            }
                            throw e; // 重新抛出给客户端
                        }
                    }
                });
    }

    /**
     * 执行实际的 RPC 调用逻辑，提取出来以便重试器调用。
     * @param serviceInterfaceName 接口名称
     * @param method 调用的方法
     * @param args 方法参数
     * @return RpcResponse
     * @throws Throwable 如果 RPC 调用失败或远程方法抛出异常
     */
    private RpcResponse performRpcCall(String serviceInterfaceName, Method method, Object[] args) throws Throwable {
        if (serviceAddresses.isEmpty()) {
            throw new RuntimeException("No service provider available for " + serviceInterfaceName + ". Please check if service is running and registered.");
        }

        // 使用负载均衡器选择一个可用的服务地址
        String chosenAddress = loadBalancer.select(serviceAddresses);
        if (chosenAddress == null) {
            throw new RuntimeException("LoadBalancer failed to select a service address.");
        }

        String[] addressParts = chosenAddress.split(":");
        String host = addressParts[0];
        int port = Integer.parseInt(addressParts[1]);
        // 尝试从缓存获取或建立Channel
        Channel channel = cachedChannels.computeIfAbsent(chosenAddress, k -> {
            try {
                System.out.println("Establishing new channel to " + chosenAddress);
                Bootstrap bootstrap = new Bootstrap();
                bootstrap.group(workerGroup)
                        .channel(NioSocketChannel.class)
                        .option(ChannelOption.TCP_NODELAY, true)
                        .handler(new ChannelInitializer<SocketChannel>() {
                            @Override
                            protected void initChannel(SocketChannel ch) throws Exception {
                                ch.pipeline()
                                        .addLast(new RpcEncoder(RpcRequest.class, serializer))
                                        .addLast(new RpcDecoder(RpcResponse.class, serializer))
                                        .addLast(rpcClientHandler);
                            }
                        });
                ChannelFuture connectFuture = bootstrap.connect(host, port).sync();
                Channel newChannel = connectFuture.channel();
                newChannel.closeFuture().addListener((ChannelFutureListener) f -> {
                    System.out.println("Channel to " + k + " closed, removing from cache.");
                    cachedChannels.remove(k);
                });
                return newChannel;
            } catch (Exception e) {
                System.err.println("Failed to establish channel to " + chosenAddress + ": " + e.getMessage());
                throw new RuntimeException("Failed to connect to RPC server", e);
            }
        });

        // 确保Channel是活跃的
        if (!channel.isActive()) {
            System.err.println("Cached channel to " + chosenAddress + " is not active. Re-establishing connection.");
            cachedChannels.remove(chosenAddress); // 移除不活跃的通道
            // 如果通道不活跃，直接抛出异常，让重试机制或上层处理
            throw new RuntimeException("Channel to " + chosenAddress + " is not active.");
        }

        // 构建RPC请求
        String requestId = java.util.UUID.randomUUID().toString();
        RpcRequest request = new RpcRequest(
                requestId,
                serviceInterfaceName,
                method.getName(),
                method.getParameterTypes(),
                args
        );

        // 为本次请求创建RpcFuture，并放入map中
        RpcFuture rpcFuture = new RpcFuture();
        pendingRpcFutures.put(requestId, rpcFuture);

        System.out.println("Sending request (ID: " + request.getRequestId() + ") to " + chosenAddress + ": " + request);

        try {
            channel.writeAndFlush(request).sync();
            System.out.println("Request sent. Waiting for response for ID: " + requestId);

            RpcResponse response = rpcFuture.get(5, TimeUnit.SECONDS);
            pendingRpcFutures.remove(requestId); // 收到结果或超时后移除

            if (response.isSuccess()) {
                return response; // 返回完整的 RpcResponse
            } else {
                // 远程调用发生异常，包装为 InvocationTargetException
                throw new InvocationTargetException(response.getException());
            }
        } catch (Exception e) {
            System.err.println("RPC call (ID: " + requestId + ") failed: " + e.getMessage());
            e.printStackTrace();
            pendingRpcFutures.remove(requestId); // 失败也移除
            throw e; // 重新抛出异常，让重试器捕获
        }
    }

    private void connectZooKeeper() {
        try {
            zk = new ZooKeeper(zkAddress, 5000, new Watcher() {
                @Override
                public void process(WatchedEvent event) {
                    if (event.getState() == Event.KeeperState.SyncConnected) {
                        System.out.println("Client connected to ZooKeeper successfully!");
                    } else if (event.getState() == Event.KeeperState.Disconnected) {
                        System.err.println("Client disconnected from ZooKeeper!");
                    }
                    // 在连接状态变化时重新发现服务
                    if (event.getType() == Event.EventType.NodeChildrenChanged ||
                            event.getState() == Event.KeeperState.SyncConnected) {
                        // 重新发现所有服务，因为可能有服务上线或下线
                        // 这里的实现简化，实际生产可能需要更精细的服务列表管理
                        updateServiceAddressesFromZk();
                    }
                }
            });
        } catch (IOException e) {
            System.err.println("Failed to connect to ZooKeeper: " + e.getMessage());
            e.printStackTrace();
            throw new RuntimeException("Failed to connect to ZooKeeper", e);
        }
    }

    private void discoverService(String serviceInterfaceName) {
        String servicePath = ZK_SERVICES_PATH + "/" + serviceInterfaceName; // 使用 ZK_SERVICES_PATH
        try {
            Stat servicePathStat = zk.exists(servicePath, false); // 导入 Stat
            if (servicePathStat == null) {
                System.out.println("Service path does not exist for: " + serviceInterfaceName + ". Waiting for service registration.");
                this.serviceAddresses.clear(); // 清空地址列表
                return;
            }

            // 获取指定服务下的所有子节点（服务提供者的地址信息）
            // 并设置一个 Watcher，当子节点列表发生变化时，会触发Watcher
            List<String> children = zk.getChildren(servicePath, true); // Watcher会触发
            List<String> addresses = new ArrayList<>();
            for (String child : children) {
                // 子节点的名字是 IP:Port-sequenceNumber，我们需要提取 IP:Port
                // 例如：192.168.1.100:8080-0000000001
                String[] parts = child.split("-");
                if (parts.length > 0) {
                    addresses.add(parts[0]);
                }
            }
            if (!addresses.isEmpty()) {
                this.serviceAddresses = new CopyOnWriteArrayList<>(addresses);
                System.out.println("Discovered service addresses for " + serviceInterfaceName + ": " + this.serviceAddresses);
            } else {
                this.serviceAddresses.clear();
                System.out.println("No service providers found for " + serviceInterfaceName);
            }

        } catch (Exception e) {
            System.err.println("Error discovering service " + serviceInterfaceName + ": " + e.getMessage());
            e.printStackTrace();
        }
    }

    // 辅助方法，用于ZooKeeper连接状态变化时更新所有服务地址
    private void updateServiceAddressesFromZk() {
        // 这是一个简化的实现，实际中可能需要维护一个已发现服务接口的列表
        // 然后对每个接口调用 discoverService
        // 例如，如果 Calculator 是唯一一个服务接口：
        discoverService("com.rpc.server.Calculator");
        // 如果有多个服务，这里需要迭代所有已知的服务接口名称
    }


    /**
     * 在客户端关闭时关闭ZooKeeper连接和Netty资源。
     */
    public void closeZooKeeper() {
        if (zk != null) {
            try {
                zk.close();
                System.out.println("Client ZooKeeper connection closed.");
            } catch (InterruptedException e) {
                System.err.println("Error closing client ZooKeeper connection: " + e.getMessage());
                Thread.currentThread().interrupt();
            }
        }
        if (workerGroup != null) {
            workerGroup.shutdownGracefully();
            System.out.println("Netty client worker group shut down.");
        }
        // 关闭所有缓存的Channel
        cachedChannels.values().forEach(Channel::close);
        cachedChannels.clear();
    }

    /**
     * Netty RPC 客户端处理器，用于处理入站的 RPC 响应。
     * 现在它会根据响应中的请求ID来匹配RpcFuture。
     */
    @ChannelHandler.Sharable // 确保可以共享
    private static class RpcClientHandler extends SimpleChannelInboundHandler<RpcResponse> {
        private final ConcurrentHashMap<String, RpcFuture> pendingRpcFutures;

        public RpcClientHandler(ConcurrentHashMap<String, RpcFuture> pendingRpcFutures) {
            this.pendingRpcFutures = pendingRpcFutures;
        }

        @Override
        protected void channelRead0(ChannelHandlerContext ctx, RpcResponse response) throws Exception {
            String requestId = response.getRequestId();
            if (requestId != null) {
                RpcFuture rpcFuture = pendingRpcFutures.get(requestId);
                if (rpcFuture != null) {
                    System.out.println("Received response for requestId: " + requestId);
                    rpcFuture.setResponse(response); // 设置响应并唤醒等待线程
                } else {
                    System.err.println("Received response for unknown requestId: " + requestId + " on channel " + ctx.channel().remoteAddress());
                }
            } else {
                System.err.println("Received RPC response with null requestId from channel " + ctx.channel().remoteAddress());
            }
        }

        @Override
        public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
            System.err.println("RPC Client Handler caught exception on channel: " + ctx.channel().remoteAddress() + ", error: " + cause.getMessage());
            cause.printStackTrace();
            ctx.close(); // 发生异常时关闭连接

            // 当通道异常关闭时，通知所有等待在这个通道上的Future，它们将失败
            // 这是一个粗略的处理，更精确的做法是维护每个通道上的pending request
            // 这里为了简化，直接遍历所有待处理的future。
            // 更好的方式是在RpcFuture中包含一个ChannelId或类似的标识，
            // 这样在Channel关闭时，只关闭其上挂起的Future。
            for (Map.Entry<String, RpcFuture> entry : pendingRpcFutures.entrySet()) {
                // 仅当Future的请求可能通过此通道发出时才通知
                // 简化处理：通知所有，因为无法确定具体是哪个请求的通道
                if (!entry.getValue().isDone()) { // 避免重复设置
                    entry.getValue().setException(new RuntimeException("Netty channel exception for request " + entry.getKey(), cause));
                }
            }
            // pendingRpcFutures.clear(); // 不在这里清空，因为可能还有其他通道正常
        }
    }

    /**
     * RpcFuture类用于异步获取RPC调用的结果。
     */
    private static class RpcFuture {
        private RpcResponse response;
        private final ReentrantLock lock = new ReentrantLock();
        private final Condition condition = lock.newCondition();
        private Throwable exception;
        private volatile boolean done = false; // 用于标识是否已完成

        public RpcResponse get(long timeout, TimeUnit unit) throws InterruptedException, java.util.concurrent.TimeoutException {
            lock.lock();
            try {
                if (done) { // 如果已经完成（无论是成功还是异常），直接返回
                    if (exception != null) {
                        throw new RuntimeException("RPC Future failed", exception);
                    }
                    return response;
                }

                boolean success = condition.await(timeout, unit);
                if (!success) { // 超时
                    this.exception = new java.util.concurrent.TimeoutException("RPC call timed out");
                    done = true;
                    throw (java.util.concurrent.TimeoutException) exception;
                }

                // 被唤醒后再次检查状态
                if (exception != null) {
                    throw new RuntimeException("RPC Future failed after being woken up", exception);
                }
                if (response != null) {
                    return response;
                }
                throw new RuntimeException("Unknown error: RPC Future did not receive response or exception within timeout.");

            } finally {
                lock.unlock();
            }
        }

        public void setResponse(RpcResponse response) {
            lock.lock();
            try {
                this.response = response;
                this.done = true;
                condition.signalAll();
            } finally {
                lock.unlock();
            }
        }

        public void setException(Throwable exception) {
            lock.lock();
            try {
                this.exception = exception;
                this.done = true;
                condition.signalAll();
            } finally {
                lock.unlock();
            }
        }

        public boolean isDone() {
            lock.lock();
            try {
                return done;
            } finally {
                lock.unlock();
            }
        }
    }
}
