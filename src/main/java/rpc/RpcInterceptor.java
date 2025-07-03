package rpc;

/**
 * RPC 服务器端拦截器接口。
 * 允许在实际服务方法调用之前和之后执行自定义逻辑。
 */
public interface RpcInterceptor {

    /**
     * 在服务方法调用之前执行拦截逻辑。
     * 如果返回 false，则中断请求处理，不继续调用后续拦截器和目标服务方法。
     *
     * @param request 接收到的 RPC 请求
     * @return 如果继续处理请求返回 true，否则返回 false
     */
    boolean preHandle(RpcRequest request);

    /**
     * 在服务方法调用之后执行拦截逻辑（无论成功或失败）。
     *
     * @param request 原始 RPC 请求
     * @param response RPC 响应（可能包含结果或异常）
     * @param exception 如果方法调用抛出异常，则为该异常，否则为 null
     */
    void postHandle(RpcRequest request, RpcResponse response, Throwable exception);

    /**
     * 在处理请求完成（包括视图渲染，如果有的话）之后执行。
     * 主要用于资源清理等操作。
     *
     * @param request 原始 RPC 请求
     * @param response RPC 响应（可能包含结果或异常）
     * @param exception 如果方法调用抛出异常，则为该异常，否则为 null
     */
    void afterCompletion(RpcRequest request, RpcResponse response, Throwable exception);
}