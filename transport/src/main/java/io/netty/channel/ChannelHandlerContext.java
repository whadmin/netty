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
import io.netty.util.Attribute;
import io.netty.util.AttributeKey;
import io.netty.util.AttributeMap;
import io.netty.util.concurrent.EventExecutor;

import java.nio.channels.Channels;


/**
 * io.netty.channel.ChannelHandlerContext ，
 * 继承 ChannelInboundInvoker、ChannelOutboundInvoker、AttributeMap 接口，ChannelHandler Context( 上下文 )接口，作为 ChannelPipeline 中的节点
 */
public interface ChannelHandlerContext extends AttributeMap, ChannelInboundInvoker, ChannelOutboundInvoker {

    // ========== Context 相关 ==========
    /**
     * 返回绑定到{@link ChannelHandlerContext}的{@link Channel}
     */
    Channel channel();

    /**
     * 返回用于执行任意任务的{@link EventExecutor}。
     */
    EventExecutor executor();

    /**
     * 返回名称
     */
    String name();

    /**
     * 绑定此{@link ChannelHandlerContext}的{@link ChannelHandler}
     */
    ChannelHandler handler();

    /**
     * 当前对象ChannelHandler状态是否为 REMOVE_COMPLETE
     */
    boolean isRemoved();

    /**
     * 返回关联的{@link ChannelPipeline}
     */
    ChannelPipeline pipeline();

    // ========== ChannelInboundInvoker 相关 ==========

    @Override
    ChannelHandlerContext fireChannelRegistered();

    @Override
    ChannelHandlerContext fireChannelUnregistered();

    @Override
    ChannelHandlerContext fireChannelActive();

    @Override
    ChannelHandlerContext fireChannelInactive();

    @Override
    ChannelHandlerContext fireExceptionCaught(Throwable cause);

    @Override
    ChannelHandlerContext fireUserEventTriggered(Object evt);

    @Override
    ChannelHandlerContext fireChannelRead(Object msg);

    @Override
    ChannelHandlerContext fireChannelReadComplete();

    @Override
    ChannelHandlerContext fireChannelWritabilityChanged();

    // ========== ChannelOutboundInvoker 相关 ==========
    @Override
    ChannelHandlerContext read();

    @Override
    ChannelHandlerContext flush();

    // ========== ByteBuf 相关 ==========
    /**
     * 返回ByteBufAllocator将用于分配ByteBuf
     */
    ByteBufAllocator alloc();

    // ========== AttributeMap 相关 ==========

    @Deprecated
    @Override
    <T> Attribute<T> attr(AttributeKey<T> key);


    @Deprecated
    @Override
    <T> boolean hasAttr(AttributeKey<T> key);
}
