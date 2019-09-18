/*
 * Copyright 2016 The Netty Project
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

import io.netty.util.concurrent.EventExecutor;
import io.netty.util.concurrent.FutureListener;

import java.net.ConnectException;
import java.net.SocketAddress;

public interface ChannelOutboundInvoker {

    /**
     * 绑定到给定SocketAddress并返回ChannelFuture
     */
    ChannelFuture bind(SocketAddress localAddress);

    /**
     * 绑定到给定SocketAddress并返回ChannelFuture，同时传入ChannelPromise,
     * ChannelPromise 可以参与异步操作，有它监听异步操作设置异步操作result
     */
    ChannelFuture bind(SocketAddress localAddress, ChannelPromise promise);

    /**
     * 连接到给定SocketAddress并返回ChannelFuture在操作完成后通知
     */
    ChannelFuture connect(SocketAddress remoteAddress);

    /**
     * 指定本地地址连接到给定SocketAddress并返回ChannelFuture在操作完成后通知
     */
    ChannelFuture connect(SocketAddress remoteAddress, SocketAddress localAddress);

    /**
     * 连接到给定SocketAddress并返回ChannelFuture在操作完成后通知，同时传入ChannelPromise,
     * ChannelPromise 可以参与异步操作，有它监听异步操作设置异步操作result
     */
    ChannelFuture connect(SocketAddress remoteAddress, ChannelPromise promise);

    /**
     * 指定本地地址连接到给定SocketAddress并返回ChannelFuture在操作完成后通知，同时传入ChannelPromise,
     * ChannelPromise 可以参与异步操作，有它监听异步操作设置异步操作result
     */
    ChannelFuture connect(SocketAddress remoteAddress, SocketAddress localAddress, ChannelPromise promise);

    /**
     * 断开与远程对等方的连接，并返回ChannelFuture在操作完成后通知操作
     */
    ChannelFuture disconnect();

    /**
     * 断开与远程对等方的连接，并返回ChannelFuture在操作完成后通知操作,同时传入ChannelPromise,
     * ChannelPromise 可以参与异步操作，有它监听异步操作设置异步操作result
     */
    ChannelFuture disconnect(ChannelPromise promise);

    /**
     * 关闭Channel并返回ChannelFuture在操作完成后通知
     */
    ChannelFuture close();

    /**
     * 关闭Channel并返回ChannelFuture在操作完成后通知，同时传入ChannelPromise,
     * ChannelPromise 可以参与异步操作，有它监听异步操作设置异步操作result
     */
    ChannelFuture close(ChannelPromise promise);

    /**
     * 将当前Channel从{@link EventExecutor}中注销并返回ChannelFuture在操作完成后通知
     */
    ChannelFuture deregister();

    /**
     * 将当前Channel从{@link EventExecutor}中注销并返回ChannelFuture在操作完成后通知
     * 同时传入ChannelPromise,ChannelPromise 可以参与异步操作，有它监听异步操作设置异步操作result
     */
    ChannelFuture deregister(ChannelPromise promise);


    /**
     * Channel缓冲区读取数据，负责Channel向注册EventLoop注册自己感兴趣的事件
     */
    ChannelOutboundInvoker read();

    /**
     * 从Channel缓冲区写入数据，
     */
    ChannelFuture write(Object msg);

    /**
     * 从Channel缓冲区写入数据，同时传入ChannelPromise,
     * ChannelPromise 可以参与异步操作，有它监听异步操作设置异步操作result
     */
    ChannelFuture write(Object msg, ChannelPromise promise);

    /**
     * 刷新
     */
    ChannelOutboundInvoker flush();

    /**
     * 从Channel缓冲区写入数据并刷新，同时传入ChannelPromise,
     * ChannelPromise 可以参与异步操作，有它监听异步操作设置异步操作result
     */
    ChannelFuture writeAndFlush(Object msg, ChannelPromise promise);

    /**
     * 从Channel缓冲区写入数据并刷新
     */
    ChannelFuture writeAndFlush(Object msg);

    /**
     * 返回一个新的{@link ChannelPromise}.
     */
    ChannelPromise newPromise();

    /**
     * 返回一个新的 {@link ChannelProgressivePromise}
     */
    ChannelProgressivePromise newProgressivePromise();

    /**
     * 创建一个ChannelFuture已标记为已成功
     */
    ChannelFuture newSucceededFuture();

    /**
     * 创建一个ChannelFuture标记为已失败
     */
    ChannelFuture newFailedFuture(Throwable cause);

    /**
     * 返回一个特殊的ChannelPromise，可以重复用于不同的操作
     */
    ChannelPromise voidPromise();
}
