package com.rpc.server;

// import com.rpc.api.LoggerInterceptor; // 移除此导入，因为现在是SPI自动加载


public class Server {
    public static void main(String[] args) {
        String zkAddress = "127.0.0.1:2080";
        int rpcPort = 8080;

        RpcServer rpcServer = new RpcServer(zkAddress);

        rpcServer.register(new CalculatorImpl());

        // 移除这行，拦截器现在通过 SPI 自动加载
        // rpcServer.addInterceptor(new LoggerInterceptor());

        try {
            rpcServer.start(rpcPort);
        } catch (Exception e) {
            System.err.println("RPC Server failed to start: " + e.getMessage());
            e.printStackTrace();
        } finally {
            rpcServer.closeZooKeeper();
        }
    }
}