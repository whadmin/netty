package io.netty.example.echo;

import io.netty.bootstrap.Bootstrap;
import io.netty.channel.*;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioSocketChannel;

public class SimpleClient {
    public static void main(String[] args) throws Exception {
        /** 实例化事件轮询处理器组 workerGroup 负责监听处理NioSocketChannel 读写请求 **/
        EventLoopGroup workerGroup = new NioEventLoopGroup(5);

        try {
            /** 构建Bootstrap 客户端启动引导程序类 **/
            Bootstrap b = new Bootstrap();
            /** 设置workerGroup **/
            b.group(workerGroup)
            /** 设置要被实例化的 Channel 的类 **/
            .channel(NioSocketChannel.class)
            /** 设置 Channel 的可选项 **/
            .option(ChannelOption.TCP_NODELAY, true)
            /** 设置事件处理器，由于当前是Bootstrap，负责监听处理NioSocketChannel 读写请求  **/
            .handler(new ChannelInitializer<SocketChannel>() {
                @Override
                public void initChannel(SocketChannel ch) throws Exception {
                    ChannelPipeline p = ch.pipeline();
                    p.addLast(new EchoClientHandler());
                }
            });
            /** 连接到指定远程地址指定端口 **/
            ChannelFuture f = b.connect("127.0.0.1", 8888).sync();
            /** 同步等待NioServerSocketChannel 关闭 **/
            f.channel().closeFuture().sync();
        } finally {
            workerGroup.shutdownGracefully();
        }
    }
}
