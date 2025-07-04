package com.rpc.api;

import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.MessageToByteEncoder;

/**
 * 自定义 RPC 编码器，使用 SPI 加载的序列化器。
 */
public class RpcEncoder extends MessageToByteEncoder {
    private final Serializer serializer;
    private final Class<?> genericClass;

    public RpcEncoder(Class<?> genericClass, Serializer serializer) {
        this.genericClass = genericClass;
        this.serializer = serializer;
    }

    @Override
    protected void encode(ChannelHandlerContext ctx, Object in, ByteBuf out) throws Exception {
        if (genericClass.isInstance(in)) {
            byte[] data = serializer.serialize(in);
            out.writeInt(data.length); // 先写入数据长度
            out.writeBytes(data);      // 再写入数据本身
        }
    }
}