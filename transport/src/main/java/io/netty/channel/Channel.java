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

    //==============获取子组件相关==============
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
     * 返回 ByteBuf 分配器
     */
    ByteBufAllocator alloc();

    /**
     * 返回 Unsafe 对象
     */
    Unsafe unsafe();

    /**
     * 返回 ChannelPipeline 对象，用于处理 Inbound 和 Outbound 事件的处理
     */
    ChannelPipeline pipeline();

    //==============状态相关相关==============

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


    //==============配置相关==============

    /**
     * 返回{@link Channel}的{@link ChannelMetadata}，其中描述了{@link Channel}的性质。
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



    //==============写缓存相关==============
    /**
     * Channel 的写缓存区 outbound 非 null 且可写时，返回 true
     */
    boolean isWritable();

    /**
     * Channel 的写缓存区 outbound 距离不可写还有多少字节数
     */
    long bytesBeforeUnwritable();

    /**
     * Channel 的写缓存区 outbound 距离可写还要多少字节数
     */
    long bytesBeforeWritable();


    /**
     * 读取操作，其内部负责将Channel感兴趣的事件注册到EventLoop中
     */
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
         * 将Channel注册到指定EventLoop，ChannelPromise负责在完成后通知
         */
        void register(EventLoop eventLoop, ChannelPromise promise);

        /**
         * 将SocketAddress绑定到Channel，ChannelPromise负责在完成后通知
         */
        void bind(SocketAddress localAddress, ChannelPromise promise);

        /**
         * 将Channel与给定的远程{@link remoteAddress}地址进行连接，ChannelPromise负责在完成后通知
         */
        void connect(SocketAddress remoteAddress, SocketAddress localAddress, ChannelPromise promise);

        /**
         * Channel断开连接，ChannelPromise负责在完成后通知
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
         * 将Channel从指定EventLoop注销，ChannelPromise负责在完成后通知
         */
        void deregister(ChannelPromise promise);

        /**
         * 开始一个读取操作，其内部负责将Channel感兴趣的事件注册到EventLoop中
         */
        void beginRead();

        /**
         * 写操作
         */
        void write(Object msg, ChannelPromise promise);

        /**
         * 刷新
         */
        void flush();

        /**
         * 返回一个可以被重用不进行通知的 Promise 对象
         */
        ChannelPromise voidPromise();

        /**
         * 返回 写入缓存队列
         */
        ChannelOutboundBuffer outboundBuffer();
    }
}
