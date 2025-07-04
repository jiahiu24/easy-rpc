package com.rpc.api;

/**
 * 定义通用的序列化器接口。
 * 允许框架使用不同的序列化实现（如Java内置、Kryo、Protobuf等）。
 */
public interface Serializer {
    /**
     * 将对象序列化为字节数组。
     * @param obj 要序列化的对象
     * @return 序列化后的字节数组
     * @throws Exception 序列化过程中可能发生的异常
     */
    byte[] serialize(Object obj) throws Exception;

    /**
     * 将字节数组反序列化为指定类型的对象。
     * @param bytes 字节数组
     * @param clazz 目标对象的Class
     * @param <T> 目标类型
     * @return 反序列化后的对象
     * @throws Exception 反序列化过程中可能发生的异常
     */
    <T> T deserialize(byte[] bytes, Class<T> clazz) throws Exception;
}