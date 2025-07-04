package com.rpc.api;

import java.io.Serializable;
import java.util.Arrays;

/**
 * RPC 请求对象，用于封装客户端的调用信息，现在包含请求ID。
 */
public class RpcRequest implements Serializable {
    private static final long serialVersionUID = 2L; // 序列化版本UID，改变了结构，最好增加

    private String requestId;     // 新增：唯一请求ID
    private String interfaceName;
    private String methodName;
    private Class<?>[] parameterTypes;
    private Object[] arguments;

    // 新增构造方法以包含requestId
    public RpcRequest(String requestId, String interfaceName, String methodName, Class<?>[] parameterTypes, Object[] arguments) {
        this.requestId = requestId;
        this.interfaceName = interfaceName;
        this.methodName = methodName;
        this.parameterTypes = parameterTypes;
        this.arguments = arguments;
    }

    // Getter 和 Setter 方法
    public String getRequestId() {
        return requestId;
    }

    public void setRequestId(String requestId) { // 允许设置请求ID
        this.requestId = requestId;
    }

    public String getInterfaceName() {
        return interfaceName;
    }

    public String getMethodName() {
        return methodName;
    }

    public Class<?>[] getParameterTypes() {
        return parameterTypes;
    }

    public Object[] getArguments() {
        return arguments;
    }

    @Override
    public String toString() {
        return "RpcRequest{" +
                "requestId='" + requestId + '\'' +
                ", interfaceName='" + interfaceName + '\'' +
                ", methodName='" + methodName + '\'' +
                ", parameterTypes=" + Arrays.toString(parameterTypes) +
                ", arguments=" + Arrays.toString(arguments) +
                '}';
    }
}