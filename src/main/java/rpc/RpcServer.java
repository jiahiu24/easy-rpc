package rpc;

import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.*;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.handler.codec.serialization.ClassResolvers;
import io.netty.handler.codec.serialization.ObjectDecoder;
import io.netty.handler.codec.serialization.ObjectEncoder;
import org.apache.zookeeper.*;
import org.apache.zookeeper.data.Stat;

import java.io.IOException;
import java.lang.reflect.Method;
import java.net.InetAddress;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.ArrayList; // 新增导入
import java.util.List;     // 新增导入
import java.util.ServiceLoader;

/**
 * RpcServer类实现了远程过程调用（RPC）服务器，现在使用Netty进行网络通信，
 * 并使用ZooKeeper进行服务注册。支持拦截器。
 */
public class RpcServer {
    private Map<String, Object> serviceMap = new ConcurrentHashMap<>();
    private String zkAddress;
    private ZooKeeper zk;
    private final String ZK_REGISTRY_ROOT_PATH = "/rpc";
    private final String ZK_SERVICES_PATH = ZK_REGISTRY_ROOT_PATH + "/services";

    private EventLoopGroup bossGroup = null;
    private EventLoopGroup workerGroup = null;

    private List<RpcInterceptor> interceptors = new ArrayList<>(); // 新增：拦截器列表
    private Serializer serializer; // 新增：当前使用的序列化器
    // 构造函数中加载拦截器
    public RpcServer(String zkAddress) {
        this.zkAddress = zkAddress;
        // 通过 SPI 加载所有 RpcInterceptor 实现
        loadInterceptors();
        loadSerializer();
    }
    private void loadSerializer() {
        ServiceLoader<Serializer> loader = ServiceLoader.load(Serializer.class);
        // 这里为了简化，我们只加载第一个发现的序列化器
        // 生产环境中可能需要更复杂的逻辑来选择或配置
        for (Serializer s : loader) {
            this.serializer = s;
            System.out.println("[SPI] Loaded Serializer: " + s.getClass().getSimpleName());
            break; // 只加载一个
        }
        if (this.serializer == null) {
            throw new RuntimeException("No Serializer implementation found via SPI!");
        }
    }

    private void loadInterceptors() {
        ServiceLoader<RpcInterceptor> loader = ServiceLoader.load(RpcInterceptor.class);
        for (RpcInterceptor interceptor : loader) {
            this.interceptors.add(interceptor);
            System.out.println("[SPI] Loaded RpcInterceptor: " + interceptor.getClass().getSimpleName());
        }
        if (this.interceptors.isEmpty()) {
            System.out.println("[SPI] No RpcInterceptor implementations found via SPI.");
        }
    }

    public void register(Object service) {
        Class<?>[] interfaces = service.getClass().getInterfaces();
        for (Class<?> interfaze : interfaces) {
            serviceMap.put(interfaze.getName(), service);
            System.out.println("Service registered locally: " + interfaze.getName());
        }
    }

    /**
     * 新增方法：添加拦截器
     * 拦截器将按照添加的顺序执行 preHandle 和 afterCompletion。
     * postHandle 的执行顺序不依赖 preHandle 的顺序。
     */
//    public void addInterceptor(RpcInterceptor interceptor) {
//        this.interceptors.add(interceptor);
//        System.out.println("Added RpcInterceptor: " + interceptor.getClass().getSimpleName());
//    }

    public void start(int port) throws Exception {
        connectZooKeeper();
        String hostAddress = InetAddress.getLocalHost().getHostAddress();
        final String serviceAddress = hostAddress + ":" + port;

        try {
            registerServiceOnZooKeeper(serviceAddress);

            bossGroup = new NioEventLoopGroup();
            workerGroup = new NioEventLoopGroup();

            ServerBootstrap bootstrap = new ServerBootstrap();
            bootstrap.group(bossGroup, workerGroup)
                    .channel(NioServerSocketChannel.class)
                    .childHandler(new ChannelInitializer<SocketChannel>() {
                        @Override
                        protected void initChannel(SocketChannel ch) {
                            ch.pipeline()
                                    // 确保解码器读取的字节数足够大，防止OutOfMemoryError，但也要避免过大
                                    .addLast(new RpcDecoder(RpcRequest.class, serializer)) // 使用加载的序列化器
                                    .addLast(new RpcEncoder(RpcResponse.class, serializer)) // 使用加载的序列化器
                                    .addLast(new RpcServerHandler(serviceMap, interceptors));
                        }
                    })
                    .option(ChannelOption.SO_BACKLOG, 128)
                    .childOption(ChannelOption.SO_KEEPALIVE, true);

            ChannelFuture future = bootstrap.bind(port).sync();
            System.out.println("RPC Server (Netty) started on port " + port + "...");
            future.channel().closeFuture().sync();

        } finally {
            if (bossGroup != null) {
                bossGroup.shutdownGracefully();
            }
            if (workerGroup != null) {
                workerGroup.shutdownGracefully();
            }
            System.out.println("Netty RPC Server shut down.");
            closeZooKeeper();
        }
    }

    private void connectZooKeeper() {
        try {
            zk = new ZooKeeper(zkAddress, 5000, new Watcher() {
                @Override
                public void process(WatchedEvent event) {
                    if (event.getState() == Event.KeeperState.SyncConnected) {
                        System.out.println("Connected to ZooKeeper successfully!");
                    } else if (event.getState() == Event.KeeperState.Disconnected) {
                        System.err.println("Disconnected from ZooKeeper! Attempting to reconnect...");
                    }
                }
            });
        } catch (IOException e) {
            System.err.println("Failed to connect to ZooKeeper: " + e.getMessage());
            e.printStackTrace();
            throw new RuntimeException("Failed to connect to ZooKeeper", e);
        }
    }

    private void registerServiceOnZooKeeper(String serviceAddress) throws KeeperException, InterruptedException {
        Stat rpcRootStat = zk.exists(ZK_REGISTRY_ROOT_PATH, false);
        if (rpcRootStat == null) {
            zk.create(ZK_REGISTRY_ROOT_PATH, new byte[0], ZooDefs.Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT);
            System.out.println("Created ZK root path: " + ZK_REGISTRY_ROOT_PATH);
        }

        Stat servicesPathStat = zk.exists(ZK_SERVICES_PATH, false);
        if (servicesPathStat == null) {
            zk.create(ZK_SERVICES_PATH, new byte[0], ZooDefs.Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT);
            System.out.println("Created ZK services path: " + ZK_SERVICES_PATH);
        }

        for (String interfaceName : serviceMap.keySet()) {
            String serviceNodePath = ZK_SERVICES_PATH + "/" + interfaceName;
            Stat serviceStat = zk.exists(serviceNodePath, false);
            if (serviceStat == null) {
                zk.create(serviceNodePath, new byte[0], ZooDefs.Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT);
                System.out.println("Created ZK individual service path: " + serviceNodePath);
            }

            String nodeName = serviceNodePath + "/" + serviceAddress + "-";
            String createdPath = zk.create(nodeName, new byte[0], ZooDefs.Ids.OPEN_ACL_UNSAFE, CreateMode.EPHEMERAL_SEQUENTIAL);
            System.out.println("Service registered on ZooKeeper: " + createdPath);
        }
    }

    public void closeZooKeeper() {
        if (zk != null) {
            try {
                zk.close();
                System.out.println("ZooKeeper connection closed.");
            } catch (InterruptedException e) {
                System.err.println("Error closing ZooKeeper connection: " + e.getMessage());
                Thread.currentThread().interrupt();
            }
        }
    }

    /**
     * Netty RPC 服务器处理器，用于处理入站的 RPC 请求。
     * 现在它会执行拦截器链。
     */
    @ChannelHandler.Sharable
    private class RpcServerHandler extends SimpleChannelInboundHandler<RpcRequest> {
        private final Map<String, Object> serviceMap;
        private final List<RpcInterceptor> interceptors; // 新增：拦截器列表

        public RpcServerHandler(Map<String, Object> serviceMap, List<RpcInterceptor> interceptors) {
            this.serviceMap = serviceMap;
            this.interceptors = interceptors; // 初始化拦截器列表
        }

        @Override
        protected void channelRead0(ChannelHandlerContext ctx, RpcRequest request) throws Exception {
            System.out.println("Received RPC request: " + request.toString());

            RpcResponse response = new RpcResponse();
            response.setRequestId(request.getRequestId());

            Throwable methodExecutionException = null; // 用于捕获服务方法执行中的异常

            // 1. 执行 preHandle 拦截器
            boolean continued = true;
            for (RpcInterceptor interceptor : interceptors) {
                if (!interceptor.preHandle(request)) {
                    continued = false;
                    // 如果某个拦截器返回 false，则中断后续处理
                    System.out.println("[Interceptor Chain] Request " + request.getRequestId() + " interrupted by preHandle.");
                    // 可以设置一个特定的错误响应
                    response.setException(new RuntimeException("Request intercepted and denied by " + interceptor.getClass().getSimpleName()));
                    break;
                }
            }

            if (continued) {
                try {
                    Object service = serviceMap.get(request.getInterfaceName());
                    if (service == null) {
                        throw new ClassNotFoundException("No service found for interface: " + request.getInterfaceName());
                    }

                    Method method = service.getClass().getMethod(request.getMethodName(), request.getParameterTypes());
                    Object result = method.invoke(service, request.getArguments());

                    response.setResult(result);
                    System.out.println("RPC call processed, result: " + result);
                } catch (Exception e) {
                    methodExecutionException = e; // 捕获服务方法执行中的异常
                    System.err.println("Error processing RPC call for request: " + request.toString() + ", error: " + e.getMessage());
                    e.printStackTrace();
                    response.setException(e);
                }
            }

            // 2. 执行 postHandle 拦截器 (无论请求是否被中断或是否发生异常)
            for (RpcInterceptor interceptor : interceptors) {
                interceptor.postHandle(request, response, methodExecutionException);
            }

            // 将响应写回客户端
            ctx.writeAndFlush(response);

            // 3. 执行 afterCompletion 拦截器
            for (RpcInterceptor interceptor : interceptors) {
                interceptor.afterCompletion(request, response, methodExecutionException);
            }
        }

        @Override
        public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
            System.err.println("RPC Server Handler caught exception: " + cause.getMessage());
            cause.printStackTrace();
            ctx.close();
            // 注意：这里的异常捕获是Netty层面的，如果preHandle/postHandle内部抛异常，也会被这里捕获
            // 但是在这里通常无法准确关联到具体的RpcRequest，因为可能在消息解码前就发生
            // 因此拦截器内部的异常处理也需要健壮
        }
    }
}