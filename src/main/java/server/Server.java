package server;

import service.Calculator;
import service.CalculatorImpl;
import rpc.RpcServer;

public class Server {
    public static void main(String[] args) throws Exception { // 更改方法签名，以抛出异常
        String zkAddress = "localhost:2080";

        Calculator calculator = new CalculatorImpl();
        RpcServer rpcServer = new RpcServer(zkAddress);
        rpcServer.register(calculator);

        try {
            rpcServer.start(8080);
        } finally {
            rpcServer.closeZooKeeper();
            System.out.println("Server application shutting down.");
        }
    }
}