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

public interface ChannelInboundInvoker {

    /**
     * {@link Channel}已在其{@link EventLoop}注册。
     */
    ChannelInboundInvoker fireChannelRegistered();

    /**
     *{@link Channel}已从其{@link EventLoop}取消注册。
     */
    ChannelInboundInvoker fireChannelUnregistered();

    /**
     * A Channel现在处于活动状态，这意味着它已连接。
     */
    ChannelInboundInvoker fireChannelActive();

    /**
     * A Channel现在处于非活动状态，这意味着它已关闭。
     */
    ChannelInboundInvoker fireChannelInactive();

    /**
     * A Channel收到Throwable操作
     */
    ChannelInboundInvoker fireExceptionCaught(Throwable cause);

    /**
     * A Channel收到用户定义的事件
     */
    ChannelInboundInvoker fireUserEventTriggered(Object event);

    /**
     * Channel接收到的消息
     */
    ChannelInboundInvoker fireChannelRead(Object msg);

    /**
     * 触发ChannelInboundHandler.channelReadComplete(ChannelHandlerContext) 事件到下一个ChannelInboundHandler中ChannelPipeline。
     */
    ChannelInboundInvoker fireChannelReadComplete();

    /**
     * 触发ChannelInboundHandler.channelWritabilityChanged(ChannelHandlerContext) 事件到下一个ChannelInboundHandler中ChannelPipeline。
     */
    ChannelInboundInvoker fireChannelWritabilityChanged();
}
