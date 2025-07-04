package com.rpc.client;

import com.rpc.server.Calculator;

public class Client {
    public static void main(String[] args) throws Exception { // 更改方法签名，以抛出异常
        String zkAddress = "localhost:2080";

        RpcClient rpcClient = new RpcClient(zkAddress);

        try {
            Calculator calculator = rpcClient.getProxy(Calculator.class);

            int result = calculator.add(1, 2);
            System.out.println("RPC Call 1 - Result: " + result);

            int result2 = calculator.add(5, 5);
            System.out.println("RPC Call 2 - Result: " + result2);

            int result3 = calculator.add(10, 20);
            System.out.println("RPC Call 3 - Result: " + result3);

        } catch (RuntimeException e) {
            System.err.println("Client Runtime Error: " + e.getMessage());
            e.printStackTrace();
        } catch (Exception e) {
            System.err.println("An unexpected error occurred in client: " + e.getMessage());
            e.printStackTrace();
        } finally {
            rpcClient.closeZooKeeper();
            System.out.println("Client application shutting down.");
        }
    }
}