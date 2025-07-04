package com.rpc.api;

import java.io.Serializable;

/**
 * RPC 响应对象，用于封装服务器的调用结果或异常，现在包含请求ID。
 */
public class RpcResponse implements Serializable {
    private static final long serialVersionUID = 2L; // 序列化版本UID，改变了结构，最好增加

    private String requestId;   // 新增：对应的请求ID
    private Object result;
    private Throwable exception;

    // Getter 和 Setter 方法
    public String getRequestId() {
        return requestId;
    }

    public void setRequestId(String requestId) {
        this.requestId = requestId;
    }

    public Object getResult() {
        return result;
    }

    public void setResult(Object result) {
        this.result = result;
    }

    public Throwable getException() {
        return exception;
    }

    public void setException(Throwable exception) {
        this.exception = exception;
    }

    // 判断是否成功
    public boolean isSuccess() {
        return exception == null;
    }

    @Override
    public String toString() {
        return "RpcResponse{" +
                "requestId='" + requestId + '\'' +
                ", result=" + result +
                ", exception=" + exception +
                '}';
    }
}