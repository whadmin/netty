package io.netty.example.echo;

import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.*;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;


public final class SimpleServer {

    public static void main(String[] args) throws Exception {
        /** 实例化事件轮询处理器组 bossGroup 负责监听监听连接请求创建NioSocketChannel **/
        EventLoopGroup bossGroup = new NioEventLoopGroup(5);
        /** 实例化事件轮询处理器组 workerGroup 负责监听处理NioSocketChannel 读写请求 **/
        EventLoopGroup workerGroup = new NioEventLoopGroup(5);
        try {
            /** 构建ServerBootstrap 服务端启动引导程序类 **/
            ServerBootstrap b = new ServerBootstrap();
            /** 设置bossGroup，workerGroup **/
            b.group(bossGroup, workerGroup)
                    /** 设置要被实例化的 Channel 的类 **/
                    .channel(NioServerSocketChannel.class)
                    /** 设置事件处理器，由于当前是ServerBootstrap，这里处理器用来处理连接请求  **/
                    .handler(new SimpleServerHandler())
                    /** 设置子事件处理器，处理NioSocketChannel 读写请求  **/
                    .childHandler(new ChannelInitializer<SocketChannel>() {
                        @Override
                        public void initChannel(SocketChannel ch) throws Exception {
                            ChannelPipeline p = ch.pipeline();
                            p.addLast(new SimpleServerHandler());
                        }
                    });
            /** 绑定到指定端口 **/
            ChannelFuture f = b.bind(8888).sync();
            /** 同步等待NioServerSocketChannel 关闭 **/
            f.channel().closeFuture().sync();
        } finally {
            /** 优雅的关闭bossGroup,workerGroup **/
            bossGroup.shutdownGracefully();
            workerGroup.shutdownGracefully();
        }
    }

    private static class SimpleServerHandler extends ChannelInboundHandlerAdapter {
        @Override
        public void channelActive(ChannelHandlerContext ctx) throws Exception {
            System.out.println("channelActive");
        }

        @Override
        public void channelRegistered(ChannelHandlerContext ctx) throws Exception {
            System.out.println("channelRegistered");
        }

        @Override
        public void handlerAdded(ChannelHandlerContext ctx) throws Exception {
            System.out.println("handlerAdded");
        }
    }
}
