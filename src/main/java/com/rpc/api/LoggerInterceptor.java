package com.rpc.api; // 确保包名正确

/**
 * 这是一个简单的 RPC 拦截器，用于打印请求和响应的日志。
 * 实现了 RpcInterceptor 接口。
 */
public class LoggerInterceptor implements RpcInterceptor {

    @Override
    public boolean preHandle(RpcRequest request) {
        System.out.println("[LoggerInterceptor] Pre-handle: Received request ID: " + request.getRequestId() +
                ", Interface: " + request.getInterfaceName() +
                ", Method: " + request.getMethodName());
        // 返回 true 表示继续处理请求
        return true;
    }

    @Override
    public void postHandle(RpcRequest request, RpcResponse response, Throwable exception) {
        System.out.println("[LoggerInterceptor] Post-handle: Finished processing request ID: " + request.getRequestId());
        if (response != null) {
            if (response.isSuccess()) {
                System.out.println("[LoggerInterceptor] Post-handle: Response successful, result: " + response.getResult());
            } else {
                System.err.println("[LoggerInterceptor] Post-handle: Response failed, exception: " + response.getException().getMessage());
            }
        } else if (exception != null) {
            System.err.println("[LoggerInterceptor] Post-handle: Method execution threw exception: " + exception.getMessage());
        }
    }

    @Override
    public void afterCompletion(RpcRequest request, RpcResponse response, Throwable exception) {
        System.out.println("[LoggerInterceptor] After completion: Request ID " + request.getRequestId() + " processing finished.");
        // 通常用于资源清理等
    }
}