package com.rpc.api;

import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.ByteToMessageDecoder;

import java.util.List;

/**
 * 自定义 RPC 解码器，使用 SPI 加载的序列化器。
 */
public class RpcDecoder extends ByteToMessageDecoder {
    private final Serializer serializer;
    private final Class<?> genericClass;

    public RpcDecoder(Class<?> genericClass, Serializer serializer) {
        this.genericClass = genericClass;
        this.serializer = serializer;
    }

    @Override
    protected void decode(ChannelHandlerContext ctx, ByteBuf in, List<Object> out) throws Exception {
        if (in.readableBytes() < 4) { // 至少要能读取到数据长度
            return;
        }
        in.markReaderIndex(); // 标记当前读取位置

        int dataLength = in.readInt(); // 读取数据长度
        if (dataLength < 0) { // 防止恶意数据
            ctx.close();
            return;
        }

        if (in.readableBytes() < dataLength) { // 如果可读字节不足一个完整的数据包
            in.resetReaderIndex(); // 重置读取位置，等待更多数据
            return;
        }

        byte[] data = new byte[dataLength];
        in.readBytes(data); // 读取数据

        Object obj = serializer.deserialize(data, genericClass);
        out.add(obj);
    }
}