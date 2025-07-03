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

    public RpcServer(String zkAddress) {
        this.zkAddress = zkAddress;
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
    public void addInterceptor(RpcInterceptor interceptor) {
        this.interceptors.add(interceptor);
        System.out.println("Added RpcInterceptor: " + interceptor.getClass().getSimpleName());
    }

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
                                    .addLast(new ObjectDecoder(1024 * 1024, ClassResolvers.weakCachingConcurrentResolver(this.getClass().getClassLoader())))
                                    .addLast(new ObjectEncoder())
                                    // 将拦截器列表传递给 RpcServerHandler
                                    .addLast(new RpcServerHandler(serviceMap, interceptors)); // 修改这里
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
//package rpc;
//
//import org.apache.zookeeper.CreateMode;
//import org.apache.zookeeper.KeeperException;
//import org.apache.zookeeper.WatchedEvent;
//import org.apache.zookeeper.Watcher;
//import org.apache.zookeeper.ZooDefs;
//import org.apache.zookeeper.ZooKeeper;
//import org.apache.zookeeper.data.Stat; // 确保导入 Stat 类
//
//import java.io.*;
//import java.lang.reflect.Method;
//import java.net.*;
//import java.util.Map;
//import java.util.concurrent.ConcurrentHashMap;
//
///**
// * RpcServer类实现了远程过程调用（RPC）服务器，现在使用ZooKeeper进行服务注册。
// * 它允许客户端通过网络调用服务器上注册的服务方法，就像调用本地方法一样。
// */
//public class RpcServer {
//    private Map<String, Object> serviceMap = new ConcurrentHashMap<>();
//    private String zkAddress; // ZooKeeper服务器地址
//    private ZooKeeper zk; // ZooKeeper客户端实例
//    private final String ZK_REGISTRY_ROOT_PATH = "/rpc"; // ZooKeeper中RPC服务的根路径
//    private final String ZK_SERVICES_PATH = ZK_REGISTRY_ROOT_PATH + "/services"; // 服务注册的子路径
//
//    public RpcServer(String zkAddress) {
//        this.zkAddress = zkAddress;
//    }
//
//    /**
//     * 注册一个服务实例到RPC服务器。
//     * 服务器会根据服务实例实现的接口名称来索引该服务。
//     *
//     * @param service 要注册的服务实例对象。
//     */
//    public void register(Object service) {
//        Class<?>[] interfaces = service.getClass().getInterfaces();
//        for (Class<?> interfaze : interfaces) {
//            serviceMap.put(interfaze.getName(), service);
//            System.out.println("Service registered locally: " + interfaze.getName());
//        }
//    }
//
//    /**
//     * 启动RPC服务器，监听指定端口，并向ZooKeeper注册服务地址。
//     * 每个客户端连接都会在一个新的线程中处理。
//     *
//     * @param port 服务器监听的端口号。
//     * @throws IOException 如果在网络操作中发生I/O错误。
//     */
//    public void start(int port) throws IOException {
//        // 初始化ZooKeeper连接
//        connectZooKeeper();
//
//        ServerSocket serverSocket = new ServerSocket(port);
//        System.out.println("RPC Server started on port " + port + "...");
//
//        // 获取本机IP地址
//        String hostAddress = InetAddress.getLocalHost().getHostAddress();
//
//        try {
//            // --- 确保ZooKeeper中所有父路径都存在 ---
//
//            // 1. 确保 /rpc 根路径存在
//            Stat rpcRootStat = zk.exists(ZK_REGISTRY_ROOT_PATH, false);
//            if (rpcRootStat == null) {
//                zk.create(ZK_REGISTRY_ROOT_PATH, new byte[0], ZooDefs.Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT);
//                System.out.println("Created ZK root path: " + ZK_REGISTRY_ROOT_PATH);
//            }
//
//            // 2. 确保 /rpc/services 路径存在
//            Stat servicesPathStat = zk.exists(ZK_SERVICES_PATH, false);
//            if (servicesPathStat == null) {
//                zk.create(ZK_SERVICES_PATH, new byte[0], ZooDefs.Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT);
//                System.out.println("Created ZK services path: " + ZK_SERVICES_PATH);
//            }
//
//
//            // --- 遍历已注册的服务并将其详细地址注册到ZooKeeper ---
//            for (String interfaceName : serviceMap.keySet()) {
//                // 构建服务接口在ZooKeeper中的路径，例如 /rpc/services/service.Calculator
//                String serviceNodePath = ZK_SERVICES_PATH + "/" + interfaceName;
//                String serviceAddress = hostAddress + ":" + port; // 服务实例的IP:Port地址
//
//                // 3. 确保每个服务接口的路径存在 (例如 /rpc/services/service.Calculator)
//                Stat serviceStat = zk.exists(serviceNodePath, false);
//                if (serviceStat == null) {
//                    zk.create(serviceNodePath, new byte[0], ZooDefs.Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT);
//                    System.out.println("Created ZK individual service path: " + serviceNodePath);
//                }
//
//                // 在ZooKeeper中注册服务实例信息为临时有序节点。
//                // 临时节点：当创建该节点的客户端会话结束（断开连接或会话超时）时，该节点会自动被删除。
//                // 有序节点：ZooKeeper会在节点名后面追加一个递增的序列号，确保名称唯一。
//                // 节点路径示例：/rpc/services/service.Calculator/192.168.1.100:8080-0000000001
//                String nodeName = serviceNodePath + "/" + serviceAddress + "-";
//                String createdPath = zk.create(nodeName, new byte[0], ZooDefs.Ids.OPEN_ACL_UNSAFE, CreateMode.EPHEMERAL_SEQUENTIAL);
//                System.out.println("Service registered on ZooKeeper: " + createdPath);
//            }
//        } catch (KeeperException | InterruptedException e) { // 捕获KeeperException和InterruptedException
//            System.err.println("Failed to register service to ZooKeeper: " + e.getMessage());
//            e.printStackTrace();
//            // 注册失败时，关闭ZooKeeper连接并退出，避免服务器在无法注册服务的情况下运行
//            closeZooKeeper(); // 调用改进后的关闭方法
//            return;
//        }
//
//
//        // 无限循环，等待并处理客户端连接
//        while (true) {
//            Socket socket = serverSocket.accept();
//            System.out.println("Client connected: " + socket.getInetAddress());
//
//            // 为每个新的客户端连接创建一个独立的线程来处理请求
//            new Thread(() -> {
//                ObjectInputStream input = null;
//                ObjectOutputStream output = null;
//                try {
//                    // 获取输入输出流
//                    // 重要：先创建输出流，以确保客户端可以先读取到响应
//                    output = new ObjectOutputStream(socket.getOutputStream());
//                    input = new ObjectInputStream(socket.getInputStream());
//
//
//                    // --- 接收客户端的RPC调用请求信息 ---
//                    String interfaceName = input.readUTF();
//                    String methodName = input.readUTF();
//                    Class<?>[] parameterTypes = (Class<?>[]) input.readObject();
//                    Object[] arguments = (Object[]) input.readObject();
//
//                    System.out.println("Received RPC call: interface=" + interfaceName + ", method=" + methodName + " from " + socket.getInetAddress());
//
//                    // --- 执行本地服务方法 ---
//                    Object service = serviceMap.get(interfaceName);
//                    if (service == null) {
//                        throw new ClassNotFoundException("No service found for interface: " + interfaceName);
//                    }
//
//                    Method method = service.getClass().getMethod(methodName, parameterTypes);
//                    Object result = method.invoke(service, arguments); // 动态调用方法
//
//                    // --- 返回结果给客户端 ---
//                    output.writeObject(result);
//                    System.out.println("RPC call completed, result sent back.");
//
//                } catch (Exception e) {
//                    System.err.println("Error processing RPC call: " + e.getMessage());
//                    e.printStackTrace();
//                    try {
//                        if (output != null) {
//                            // 将异常信息作为结果发送回客户端，让客户端知道调用失败
//                            output.writeObject(e);
//                        }
//                    } catch (IOException ioException) {
//                        System.err.println("Error sending exception back to client: " + ioException.getMessage());
//                    }
//                } finally {
//                    // 确保资源被关闭，无论是否发生异常
//                    try {
//                        if (input != null) input.close();
//                        if (output != null) output.close();
//                        if (socket != null) socket.close();
//                        System.out.println("Client disconnected: " + socket.getInetAddress());
//                    } catch (IOException e) {
//                        System.err.println("Error closing resources: " + e.getMessage());
//                    }
//                }
//            }).start();
//        }
//    }
//
//    // 连接ZooKeeper的方法
//    private void connectZooKeeper() {
//        try {
//            // Watcher用于处理ZooKeeper事件，这里简单实现，实际应用中会更复杂
//            zk = new ZooKeeper(zkAddress, 5000, new Watcher() {
//                @Override
//                public void process(WatchedEvent event) {
//                    if (event.getState() == Event.KeeperState.SyncConnected) {
//                        System.out.println("Connected to ZooKeeper successfully!");
//                    } else if (event.getState() == Event.KeeperState.Disconnected) {
//                        System.err.println("Disconnected from ZooKeeper! Attempting to reconnect...");
//                        // 实际应用中需要处理重连逻辑，例如使用RetryPolicy
//                    }
//                    // 其他事件类型（如SessionExpired）也需要处理
//                }
//            });
//        } catch (IOException e) {
//            System.err.println("Failed to connect to ZooKeeper: " + e.getMessage());
//            e.printStackTrace();
//        }
//    }
//
//    /**
//     * 在服务器关闭时关闭ZooKeeper连接。
//     * 处理了 InterruptedException，并重新设置线程中断状态。
//     */
//    public void closeZooKeeper() {
//        if (zk != null) {
//            try {
//                zk.close();
//                System.out.println("ZooKeeper connection closed.");
//            } catch (InterruptedException e) {
//                System.err.println("Error closing ZooKeeper connection: " + e.getMessage());
//                // 重新设置中断状态，这是处理InterruptedException的推荐做法
//                Thread.currentThread().interrupt();
//            }
//        }
//    }
//}

//package rpc;
//
//import java.io.*; // 引入输入输出流相关的类
//import java.lang.reflect.Method; // 引入Java反射机制中的Method类，用于动态调用方法
//import java.net.*; // 引入网络编程相关的类，如ServerSocket和Socket
//import java.util.Map; // 引入Map接口，用于存储服务
//import java.util.concurrent.*; // 引入并发包，特别是ConcurrentHashMap
//
///**
// * RpcServer类实现了远程过程调用（RPC）服务器。
// * 它允许客户端通过网络调用服务器上注册的服务方法，就像调用本地方法一样。
// */
//public class RpcServer {
//    // serviceMap用于存储已注册的服务实例。
//    // 键(Key)是服务的接口全限定名（如"com.example.MyService"），
//    // 值(Value)是实现该接口的服务实例对象。
//    // 使用ConcurrentHashMap保证在多线程环境下的线程安全。
//    private Map<String, Object> serviceMap = new ConcurrentHashMap<>();
//
//    /**
//     * 注册一个服务实例到RPC服务器。
//     * 服务器会根据服务实例实现的接口名称来索引该服务。
//     *
//     * @param service 要注册的服务实例对象。
//     */
//    public void register(Object service) {
//        // 获取服务实例所实现的所有接口。
//        // RPC通常是面向接口编程的，客户端通过接口来调用服务。
//        Class<?>[] interfaces = service.getClass().getInterfaces();
//        // 遍历服务实例实现的所有接口
//        for (Class<?> interfaze : interfaces) {
//            // 将每个接口的全限定名作为键，服务实例本身作为值，放入serviceMap中。
//            // 这样，客户端可以通过任何一个注册接口的全限定名找到对应的服务。
//            serviceMap.put(interfaze.getName(), service);
//        }
//    }
//
//    /**
//     * 启动RPC服务器，监听指定端口，并开始接受客户端连接。
//     * 每个客户端连接都会在一个新的线程中处理，以支持并发请求。
//     *
//     * @param port 服务器监听的端口号。
//     * @throws IOException 如果在网络操作中发生I/O错误。
//     */
//    public void start(int port) throws IOException {
//        // 创建一个ServerSocket，它将监听指定端口，等待客户端连接。
//        // 这是服务器端网络通信的入口点。
//        ServerSocket serverSocket = new ServerSocket(port);
//        System.out.println("RPC Server started on port " + port + "...");
//
//        // 服务器会一直运行，无限循环地等待并接受客户端连接。
//        while (true) {
//            // serverSocket.accept()会阻塞，直到有客户端连接到服务器。
//            // 一旦有客户端连接，它就会返回一个Socket对象，代表了服务器和客户端之间的一个连接。
//            Socket socket = serverSocket.accept();
//            System.out.println("Client connected: " + socket.getInetAddress());
//
//            // 为每个新的客户端连接创建一个独立的线程来处理其请求。
//            // 这样做是为了能够同时处理多个客户端请求，避免阻塞主线程，提高并发能力。
//            new Thread(() -> {
//                // 在新线程中处理客户端请求的逻辑
//                ObjectInputStream input = null; // 用于从客户端读取数据
//                ObjectOutputStream output = null; // 用于向客户端写入数据
//                try {
//                    // 从客户端Socket获取输入流，并包装成ObjectInputStream。
//                    // 这允许服务器反序列化客户端发送的对象。
//                    input = new ObjectInputStream(socket.getInputStream());
//                    // 从客户端Socket获取输出流，并包装成ObjectOutputStream。
//                    // 这允许服务器序列化对象并发送给客户端。
//                    output = new ObjectOutputStream(socket.getOutputStream());
//
//                    // --- 接收客户端的RPC调用请求信息 ---
//                    // 1. 读取客户端想要调用的服务的接口全限定名
//                    String interfaceName = input.readUTF();
//                    // 2. 读取客户端想要调用的方法名
//                    String methodName = input.readUTF();
//                    // 3. 读取客户端调用方法所需的参数类型数组（用于精确匹配方法，因为方法可能重载）
//                    Class<?>[] parameterTypes = (Class<?>[]) input.readObject();
//                    // 4. 读取客户端调用方法时传入的实际参数值数组
//                    Object[] arguments = (Object[]) input.readObject();
//
//                    System.out.println("Received RPC call: interface=" + interfaceName + ", method=" + methodName);
//
//                    // --- 执行本地服务方法 ---
//                    // 1. 根据客户端传入的接口名，从serviceMap中查找对应的已注册服务实例。
//                    Object service = serviceMap.get(interfaceName);
//                    if (service == null) {
//                        throw new ClassNotFoundException("No service found for interface: " + interfaceName);
//                    }
//
//                    // 2. 使用Java反射机制，根据方法名和参数类型，获取到对应的Method对象。
//                    Method method = service.getClass().getMethod(methodName, parameterTypes);
//                    // 3. 再次使用反射机制，调用（执行）找到的Method对象。
//                    //    传入服务实例(service)作为方法的调用者，以及客户端提供的实际参数(arguments)。
//                    Object result = method.invoke(service, arguments);
//
//                    // --- 返回结果给客户端 ---
//                    // 将方法执行的结果序列化并通过输出流发送回客户端。
//                    output.writeObject(result);
//                    System.out.println("RPC call completed, result sent back.");
//
//                } catch (Exception e) {
//                    // 捕获处理过程中可能发生的任何异常（如网络问题、服务未找到、方法调用失败等）。
//                    // 在生产环境中，这里通常会有更完善的异常处理、错误码返回和日志记录。
//                    System.err.println("Error processing RPC call: " + e.getMessage());
//                    e.printStackTrace();
//                    try {
//                        // 尝试将异常信息写回客户端，让客户端知道调用失败
//                        if (output != null) {
//                            output.writeObject(e);
//                        }
//                    } catch (IOException ioException) {
//                        System.err.println("Error sending exception back to client: " + ioException.getMessage());
//                    }
//                } finally {
//                    // 确保资源被关闭，无论是否发生异常。
//                    try {
//                        if (input != null) input.close();
//                        if (output != null) output.close();
//                        if (socket != null) socket.close();
//                        System.out.println("Client disconnected: " + socket.getInetAddress());
//                    } catch (IOException e) {
//                        System.err.println("Error closing resources: " + e.getMessage());
//                    }
//                }
//            }).start(); // 启动新创建的线程
//        }
//    }
//}