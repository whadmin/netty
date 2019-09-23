package io.netty.example.codec.byteToMessage.lineBasedFrame;

import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.*;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.handler.codec.LineBasedFrameDecoder;
import io.netty.handler.codec.string.StringDecoder;


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
                    /** 设置子事件处理器，处理NioSocketChannel 读写请求  **/
                    .childHandler(new ChannelInitializer<SocketChannel>() {
                        @Override
                        public void initChannel(SocketChannel ch) throws Exception {
                            ChannelPipeline p = ch.pipeline();
                            p.addLast(new LineBasedFrameDecoder(Integer.MAX_VALUE));
                            p.addLast(new StringDecoder());
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

        private int counter;

        @Override
        public void channelRead(ChannelHandlerContext ctx, Object msg)
                throws Exception {
            String body = (String) msg;
            System.out.println("This is " + ++counter + " times receive client : ["
                    + body + "]");
        }

        @Override
        public void channelReadComplete(ChannelHandlerContext ctx) throws Exception {
            System.out.println("fireChannelReadComplete");
            ctx.fireChannelReadComplete();
        }
    }
}
