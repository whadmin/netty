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

/**
 * {@link ChannelHandler} which adds callbacks for state changes. This allows the user
 * to hook in to state changes easily.
 */
public interface ChannelInboundHandler extends ChannelHandler {

    /**
     * 处理 channelRegistered 通知事件 {@link Channel}注册到{@link EventLoop}时触发调用
     */
    void channelRegistered(ChannelHandlerContext ctx) throws Exception;

    /**
     * 处理 channelUnregistered 通知事件 {@link Channel}从{@link EventLoop}中注销时触发调用
     */
    void channelUnregistered(ChannelHandlerContext ctx) throws Exception;

    /**
     * 处理 channelActive 通知事件 表示{@link Channel}已被激活
     * 对于服务端 ServerSocketChannel ，true 表示 Channel 已经绑定到端口上，可提供服务
     * 对于客户端 SocketChannel ，true 表示 Channel 连接到远程服务器
     */
    void channelActive(ChannelHandlerContext ctx) throws Exception;

    /**
     * 处理 channelInactive 通知事件 表示{@link Channel}取消激活
     */
    void channelInactive(ChannelHandlerContext ctx) throws Exception;

    /**
     * 处理 channelRead 通知事件
     */
    void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception;

    /**
     * Invoked when the last message read by the current read operation has been consumed by
     * {@link #channelRead(ChannelHandlerContext, Object)}.  If {@link ChannelOption#AUTO_READ} is off, no further
     * attempt to read an inbound data from the current {@link Channel} will be made until
     * {@link ChannelHandlerContext#read()} is called.
     */
    void channelReadComplete(ChannelHandlerContext ctx) throws Exception;

    /**
     * Gets called if an user event was triggered.
     */
    void userEventTriggered(ChannelHandlerContext ctx, Object evt) throws Exception;

    /**
     * Gets called once the writable state of a {@link Channel} changed. You can check the state with
     * {@link Channel#isWritable()}.
     */
    void channelWritabilityChanged(ChannelHandlerContext ctx) throws Exception;

    /**
     * 处理 exceptionCaught 通知事件，发生异常时触发调用
     */
    @Override
    @SuppressWarnings("deprecation")
    void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) throws Exception;
}
