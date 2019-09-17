/*
 * Copyright 2012 The Netty Project
 *
 * The Netty Project licenses this file to you under the Apache License,
 * version 2.0 (the "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at:
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */
package io.netty.channel;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufAllocator;
import io.netty.channel.socket.DatagramChannel;
import io.netty.channel.socket.DatagramPacket;
import io.netty.channel.socket.ServerSocketChannel;
import io.netty.channel.socket.SocketChannel;
import io.netty.util.AttributeMap;

import java.net.InetSocketAddress;
import java.net.SocketAddress;


/**
 */
public interface Channel extends AttributeMap, ChannelOutboundInvoker, Comparable<Channel> {

    /**
     * 返回ChannelId
     */
    ChannelId id();

    /**
     * 返回Channel 注册到的 EventLoop
     */
    EventLoop eventLoop();

    /**
     * 返回父 Channel 对象
     */
    Channel parent();

    /**
     * 返回  Channel 配置参数
     */
    ChannelConfig config();

    /**
     * Channel 是否打开。
     * true 表示 Channel 可用
     * false 表示 Channel 已关闭，不可用
     */
    boolean isOpen();

    /**
     * Channel 是否注册
     * true 表示 Channel 已注册到 EventLoop 上
     * false 表示 Channel 未注册到 EventLoop 上
     */
    boolean isRegistered();

    /**
     * Channel 是否激活
     * 对于服务端 ServerSocketChannel ，true 表示 Channel 已经绑定到端口上，可提供服务
     * 对于客户端 SocketChannel ，true 表示 Channel 连接到远程服务器
     */
    boolean isActive();

    /**
     * Return the {@link ChannelMetadata} of the {@link Channel} which describe the nature of the {@link Channel}.
     */
    ChannelMetadata metadata();

    /**
     * 返回本地地址
     */
    SocketAddress localAddress();

    /**
     * 返回远端地址
     */
    SocketAddress remoteAddress();



    /**
     * Channel 是否可写
     */
    boolean isWritable();

    /**
     * 获得距离不可写还有多少字节数
     */
    long bytesBeforeUnwritable();

    /**
     * 获得距离可写还要多少字节数
     */
    long bytesBeforeWritable();

    /**
     * 返回 Unsafe 对象
     */
    Unsafe unsafe();

    /**
     * 返回 ChannelPipeline 对象，用于处理 Inbound 和 Outbound 事件的处理
     */
    ChannelPipeline pipeline();

    /**
     * 返回 ByteBuf 分配器
     */
    ByteBufAllocator alloc();

    @Override
    Channel read();

    @Override
    Channel flush();

    /**
     * Channel 关闭的 Future 对象
     */
    ChannelFuture closeFuture();


    interface Unsafe {

        /**
         * 返回ByteBuf 分配器的处理器
         */
        RecvByteBufAllocator.Handle recvBufAllocHandle();

        /**
         * 返回本地地址
         */
        SocketAddress localAddress();

        /**
         * 返回远端地址
         */
        SocketAddress remoteAddress();

        /**
         * Register the {@link Channel} of the {@link ChannelPromise} and notify
         * the {@link ChannelFuture} once the registration was complete.
         */
        void register(EventLoop eventLoop, ChannelPromise promise);

        /**
         * Bind the {@link SocketAddress} to the {@link Channel} of the {@link ChannelPromise} and notify
         * it once its done.
         */
        void bind(SocketAddress localAddress, ChannelPromise promise);

        /**
         * Connect the {@link Channel} of the given {@link ChannelFuture} with the given remote {@link SocketAddress}.
         * If a specific local {@link SocketAddress} should be used it need to be given as argument. Otherwise just
         * pass {@code null} to it.
         *
         * The {@link ChannelPromise} will get notified once the connect operation was complete.
         */
        void connect(SocketAddress remoteAddress, SocketAddress localAddress, ChannelPromise promise);

        /**
         * Disconnect the {@link Channel} of the {@link ChannelFuture} and notify the {@link ChannelPromise} once the
         * operation was complete.
         */
        void disconnect(ChannelPromise promise);

        /**
         * 关闭Channel，ChannelPromise负责在操作完成后通知。
         */
        void close(ChannelPromise promise);

        /**
         * 关闭Channel，不触发任何事件
         */
        void closeForcibly();

        /**
         * Deregister the {@link Channel} of the {@link ChannelPromise} from {@link EventLoop} and notify the
         * {@link ChannelPromise} once the operation was complete.
         */
        void deregister(ChannelPromise promise);

        /**
         * Schedules a read operation that fills the inbound buffer of the first {@link ChannelInboundHandler} in the
         * {@link ChannelPipeline}.  If there's already a pending read operation, this method does nothing.
         */
        void beginRead();

        /**
         * Schedules a write operation.
         */
        void write(Object msg, ChannelPromise promise);

        /**
         * Flush out all write operations scheduled via {@link #write(Object, ChannelPromise)}.
         */
        void flush();

        /**
         * Return a special ChannelPromise which can be reused and passed to the operations in {@link Unsafe}.
         * It will never be notified of a success or error and so is only a placeholder for operations
         * that take a {@link ChannelPromise} as argument but for which you not want to get notified.
         */
        ChannelPromise voidPromise();

        /**
         * Returns the {@link ChannelOutboundBuffer} of the {@link Channel} where the pending write requests are stored.
         */
        ChannelOutboundBuffer outboundBuffer();
    }
}
