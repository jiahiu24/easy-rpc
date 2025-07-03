package rpc;

import io.netty.bootstrap.Bootstrap;
import io.netty.channel.*;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioSocketChannel;
import io.netty.handler.codec.serialization.ClassResolvers;
import io.netty.handler.codec.serialization.ObjectDecoder;
import io.netty.handler.codec.serialization.ObjectEncoder;
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
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;

/**
 * RpcClient类，现在使用Netty进行网络通信和ZooKeeper进行服务发现。
 * 改进了连接复用和请求ID匹配。
 */
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

    public RpcClient(String zkAddress) {
        this.zkAddress = zkAddress;
        connectZooKeeper();
        initNettyClient();
        // RpcClientHandler 需要知道如何处理所有Channel的响应，所以是单例
        this.rpcClientHandler = new RpcClientHandler(pendingRpcFutures);
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
                        if (serviceAddresses.isEmpty()) {
                            throw new RuntimeException("No service provider available for " + interfaceClass.getName() + ". Please check if service is running and registered.");
                        }

                        // 简单的负载均衡：随机选择一个可用的服务地址
                        String chosenAddress = serviceAddresses.get(ThreadLocalRandom.current().nextInt(serviceAddresses.size()));
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
                                                        .addLast(new ObjectEncoder())
                                                        .addLast(new ObjectDecoder(1024 * 1024, ClassResolvers.weakCachingConcurrentResolver(this.getClass().getClassLoader())))
                                                        .addLast(rpcClientHandler); // 使用单例Handler
                                            }
                                        });
                                ChannelFuture connectFuture = bootstrap.connect(host, port).sync();
                                Channel newChannel = connectFuture.channel();
                                // 监听Channel关闭事件，从缓存中移除
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
                            // 如果Channel不活跃，从缓存中移除并重新尝试建立
                            System.err.println("Cached channel to " + chosenAddress + " is not active. Re-establishing connection.");
                            cachedChannels.remove(chosenAddress); // 移除不活跃的通道
                            // 递归调用或者抛出异常让上层处理重试
                            // 注意：在生产环境中，这里可能需要更健壮的重试策略和熔断机制
                            return invoke(proxy, method, args); // 简单的重试机制
                        }

                        // 构建RPC请求
                        String requestId = java.util.UUID.randomUUID().toString();
                        RpcRequest request = new RpcRequest(
                                requestId,
                                interfaceClass.getName(),
                                method.getName(),
                                method.getParameterTypes(),
                                args
                        );

                        // 为本次请求创建RpcFuture，并放入map中
                        RpcFuture rpcFuture = new RpcFuture();
                        pendingRpcFutures.put(requestId, rpcFuture);

                        System.out.println("Sending request (ID: " + request.getRequestId() + ") to " + chosenAddress + ": " + request);

                        try {
                            // 使用已连接的channel发送请求
                            channel.writeAndFlush(request).sync(); // 异步发送，然后同步等待写入完成
                            System.out.println("Request sent. Waiting for response for ID: " + requestId);

                            // 等待结果，设置超时
                            RpcResponse response = rpcFuture.get(5, TimeUnit.SECONDS); // 阻塞等待响应
                            // 收到结果后移除，避免内存泄漏
                            pendingRpcFutures.remove(requestId);

                            if (response.isSuccess()) {
                                return response.getResult();
                            } else {
                                // 远程调用发生异常，包装为InvocationTargetException
                                throw new InvocationTargetException(response.getException());
                            }
                        } catch (Exception e) {
                            System.err.println("RPC call (ID: " + requestId + ") failed: " + e.getMessage());
                            e.printStackTrace();
                            pendingRpcFutures.remove(requestId); // 失败也移除
                            throw new RuntimeException("RPC call failed: " + e.getMessage(), e);
                        }
                    }
                });
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
        discoverService("service.Calculator");
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

//package rpc;
//
//import org.apache.zookeeper.WatchedEvent;
//import org.apache.zookeeper.Watcher;
//import org.apache.zookeeper.ZooKeeper;
//import org.apache.zookeeper.data.Stat; // 确保这一行存在且正确
//
//import java.io.*;
//import java.lang.reflect.*;
//import java.lang.reflect.Proxy;
//import java.net.*;
//import java.util.ArrayList;
//import java.util.List;
//import java.util.concurrent.CopyOnWriteArrayList; // 线程安全的列表
//import java.util.concurrent.ThreadLocalRandom; // 用于随机选择
//
///**
// * RpcClient类，现在使用ZooKeeper进行服务发现。
// * 它能够根据接口类和ZooKeeper地址动态查找服务提供者的地址。
// */
//public class RpcClient {
//    private String zkAddress; // ZooKeeper服务器地址
//    private ZooKeeper zk; // ZooKeeper客户端实例
//    private final String ZK_REGISTRY_PATH = "/rpc/services"; // ZooKeeper中服务注册的根路径
//
//    // 存储某个接口对应的所有服务地址列表，这里用CopyOnWriteArrayList保证线程安全
//    // 并且方便在watcher事件中更新
//    private volatile List<String> serviceAddresses = new CopyOnWriteArrayList<>();
//
//    public RpcClient(String zkAddress) {
//        this.zkAddress = zkAddress;
//        connectZooKeeper(); // 客户端启动时就连接ZooKeeper
//    }
//
//    /**
//     * 为给定的接口类创建一个动态代理，该代理能够通过ZooKeeper发现并调用远程服务。
//     *
//     * @param interfaceClass 远程服务接口的Class对象。
//     * @param <T> 接口类型。
//     * @return 接口的代理实例。
//     */
//    public <T> T getProxy(Class<T> interfaceClass) {
//        // 在创建代理时，尝试发现服务地址并设置Watcher
//        discoverService(interfaceClass.getName());
//
//        return (T) Proxy.newProxyInstance(
//                interfaceClass.getClassLoader(),
//                new Class<?>[]{interfaceClass},
//                (proxy, method, args) -> {
//                    // 如果没有可用的服务地址，则抛出异常
//                    if (serviceAddresses.isEmpty()) {
//                        throw new RuntimeException("No service provider available for " + interfaceClass.getName() + ". Please check if service is running and registered.");
//                    }
//
//                    // 简单的负载均衡：随机选择一个可用的服务地址
//                    String chosenAddress = serviceAddresses.get(ThreadLocalRandom.current().nextInt(serviceAddresses.size()));
//                    String[] addressParts = chosenAddress.split(":");
//                    String host = addressParts[0];
//                    int port = Integer.parseInt(addressParts[1]);
//
//                    System.out.println("Connecting to service " + interfaceClass.getName() + " at: " + host + ":" + port);
//
//                    // 建立与远程服务提供者的Socket连接
//                    Socket socket = new Socket(host, port);
//                    // 获取输出流和输入流
//                    // 注意：创建ObjectOutputStream需要在ObjectInputStream之前，
//                    // 否则可能导致死锁（ObjectInputStream构造函数会等待读取流头）
//                    ObjectOutputStream output = new ObjectOutputStream(socket.getOutputStream());
//                    ObjectInputStream input = new ObjectInputStream(socket.getInputStream());
//
//                    // --- 发送RPC调用请求信息给服务器 ---
//                    output.writeUTF(interfaceClass.getName());
//                    output.writeUTF(method.getName());
//                    output.writeObject(method.getParameterTypes());
//                    output.writeObject(args);
//                    output.flush(); // 确保所有数据都发送出去
//
//                    // --- 接收服务器返回的结果 ---
//                    Object result = input.readObject();
//
//                    // --- 关闭资源 ---
//                    // 在finally块中关闭资源是最佳实践，确保无论是否发生异常都能关闭
//                    // 但由于这里是代理方法，每次调用都会创建新的Socket，所以直接关闭也是可以的
//                    output.close();
//                    input.close();
//                    socket.close();
//                    System.out.println("RPC call completed, received result.");
//
//                    // 如果服务器返回的是异常对象，则客户端抛出该异常
//                    if (result instanceof Throwable) {
//                        throw new InvocationTargetException((Throwable) result);
//                    }
//                    return result;
//                });
//    }
//
//    // 连接ZooKeeper的方法
//    private void connectZooKeeper() {
//        try {
//            // Watcher用于处理ZooKeeper事件。这里设置了对子节点变化的监听。
//            zk = new ZooKeeper(zkAddress, 5000, new Watcher() {
//                @Override
//                public void process(WatchedEvent event) {
//                    if (event.getState() == Event.KeeperState.SyncConnected) {
//                        System.out.println("Client connected to ZooKeeper successfully!");
//                    } else if (event.getState() == Event.KeeperState.Disconnected) {
//                        System.err.println("Client disconnected from ZooKeeper! Attempting to reconnect...");
//                        // 实际应用中需要处理重连逻辑
//                    } else if (event.getType() == Event.EventType.NodeChildrenChanged) {
//                        // 当服务节点的子节点（即服务实例）发生变化时（服务上线或下线），Watcher会被触发
//                        System.out.println("Service instances changed, re-discovering services for path: " + event.getPath());
//                        // 重新发现服务。event.getPath()会是如 "/rpc/services/service.Calculator"
//                        // 我们需要从中提取出 "service.Calculator"
//                        String serviceInterfaceName = event.getPath().substring(ZK_REGISTRY_PATH.length() + 1);
//                        discoverService(serviceInterfaceName);
//                    }
//                }
//            });
//        } catch (IOException e) {
//            System.err.println("Client failed to connect to ZooKeeper: " + e.getMessage());
//            e.printStackTrace();
//        }
//    }
//
//    // 发现服务的方法，并设置Watcher监听子节点变化
//    private void discoverService(String serviceInterfaceName) {
//        try {
//            String servicePath = ZK_REGISTRY_PATH + "/" + serviceInterfaceName;
//            // 检查服务路径本身是否存在，如果不存在，则表示该服务未注册任何实例
//            Stat servicePathStat = zk.exists(servicePath, false);
//            if (servicePathStat == null) {
//                System.out.println("Service path does not exist for: " + servicePath);
//                this.serviceAddresses.clear(); // 清空地址列表
//                return;
//            }
//
//            // 获取指定服务路径下的所有子节点（即服务实例的注册信息）
//            // 并设置一个Watcher，当子节点列表发生变化时，Watcher会被触发
//            List<String> children = zk.getChildren(servicePath, true); // true表示设置watcher
//            List<String> addresses = new ArrayList<>();
//            for (String child : children) {
//                // 子节点的名字是 IP:Port-sequenceNumber，我们需要提取 IP:Port
//                String[] parts = child.split("-");
//                if (parts.length > 0) {
//                    addresses.add(parts[0]);
//                }
//            }
//            // 原子性地更新本地缓存的服务地址列表
//            this.serviceAddresses = new CopyOnWriteArrayList<>(addresses);
//            System.out.println("Discovered service addresses for " + serviceInterfaceName + ": " + serviceAddresses);
//        } catch (Exception e) {
//            System.err.println("Error discovering services from ZooKeeper: " + e.getMessage());
//            e.printStackTrace();
//        }
//    }
//
//    // 在客户端关闭时关闭ZooKeeper连接
//    public void closeZooKeeper() {
//        if (zk != null) {
//            try {
//                zk.close();
//                System.out.println("Client ZooKeeper connection closed.");
//            } catch (InterruptedException e) {
//                System.err.println("Error closing client ZooKeeper connection: " + e.getMessage());
//                // 重新设置中断状态
//                Thread.currentThread().interrupt();
//            }
//        }
//    }
//}


//package rpc;
//
//import java.io.*;
//import java.lang.reflect.*;
//import java.lang.reflect.Proxy;
//import java.net.*;
//
//public class RpcClient {
//    public <T> T getProxy(Class<T> interfaceClass, String host, int port) {
//        return (T) Proxy.newProxyInstance(
//                interfaceClass.getClassLoader(),
//                new Class<?>[]{interfaceClass},
//                (proxy, method, args) -> {
//                    Socket socket = new Socket(host, port);
//                    ObjectOutputStream output = new ObjectOutputStream(socket.getOutputStream());
//                    ObjectInputStream input = new ObjectInputStream(socket.getInputStream());
//
//                    output.writeUTF(interfaceClass.getName());
//                    output.writeUTF(method.getName());
//                    output.writeObject(method.getParameterTypes());
//                    output.writeObject(args);
//
//                    Object result = input.readObject();
//
//                    output.close();
//                    input.close();
//                    socket.close();
//
//                    return result;
//                });
//    }
//}
