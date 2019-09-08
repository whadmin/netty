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
import io.netty.util.concurrent.DefaultEventExecutorGroup;
import io.netty.util.concurrent.EventExecutorGroup;

import java.net.SocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.SocketChannel;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.NoSuchElementException;


/**
 *
 */
public interface ChannelPipeline
        extends ChannelInboundInvoker, ChannelOutboundInvoker, Iterable<Entry<String, ChannelHandler>> {


    // ========== 添加 ChannelHandler 相关 ==========
    /**
     * 将指定名称name的ChannelHandler添加到Pipeline链表头部
     */
    ChannelPipeline addFirst(String name, ChannelHandler handler);

    /**
     * 将指定名称name的ChannelHandler添加到Pipeline链表头部
     * 从指定EventExecutorGroup中获取EventExecutor 作为执行器
     * 如果不指定从 Channel 所在的 EventLoop 作为执行器
     */
    ChannelPipeline addFirst(EventExecutorGroup group, String name, ChannelHandler handler);

    /**
     * 将指定名称name的ChannelHandler添加到Pipeline链表尾部，
     */
    ChannelPipeline addLast(String name, ChannelHandler handler);

    /**
     * 将指定名称name的ChannelHandler添加到Pipeline链表尾部，
     * 从指定EventExecutorGroup中获取EventExecutor 作为执行器
     * 如果不指定从 Channel 所在的 EventLoop 作为执行器
     */
    ChannelPipeline addLast(EventExecutorGroup group, String name, ChannelHandler handler);

    /**
     * 将指定名称name的ChannelHandler添加到Pipeline链表baseName节点的前面
     */
    ChannelPipeline addBefore(String baseName, String name, ChannelHandler handler);

    /**
     * 将指定名称name的ChannelHandler添加到Pipeline链表baseName节点的前面
     * 从指定EventExecutorGroup中获取EventExecutor 作为执行器
     * 如果不指定从 Channel 所在的 EventLoop 作为执行器
     */
    ChannelPipeline addBefore(EventExecutorGroup group, String baseName, String name, ChannelHandler handler);

    /**
     * 将指定名称name的ChannelHandler添加到Pipeline链表baseName节点的后面
     */
    ChannelPipeline addAfter(String baseName, String name, ChannelHandler handler);

    /**
     * 将指定名称name的ChannelHandler添加到Pipeline链表baseName节点的后面
     * 从指定EventExecutorGroup中获取EventExecutor 作为执行器
     * 如果不指定从 Channel 所在的 EventLoop 作为执行器
     */
    ChannelPipeline addAfter(EventExecutorGroup group, String baseName, String name, ChannelHandler handler);

    /**
     * 将ChannelHandler集合添加到Pipeline链表头部
     */
    ChannelPipeline addFirst(ChannelHandler... handlers);

    /**
     * 将ChannelHandler集合添加到Pipeline链表头部
     * 从指定EventExecutorGroup中获取EventExecutor 作为执行器
     * 如果不指定从 Channel 所在的 EventLoop 作为执行器
     */
    ChannelPipeline addFirst(EventExecutorGroup group, ChannelHandler... handlers);

    /**
     * 将ChannelHandler集合添加到Pipeline链表尾部
     */
    ChannelPipeline addLast(ChannelHandler... handlers);

    /**
     * 将ChannelHandler集合添加到Pipeline链表尾部
     * 从指定EventExecutorGroup中获取EventExecutor 作为执行器
     * 如果不指定从 Channel 所在的 EventLoop 作为执行器
     */
    ChannelPipeline addLast(EventExecutorGroup group, ChannelHandler... handlers);

    /**
     * 将ChannelHandler从Pipeline链表中删除
     */
    ChannelPipeline remove(ChannelHandler handler);

    /**
     * 将指定名称ChannelHandler从Pipeline链表中删除
     */
    ChannelHandler remove(String name);

    /**
     * 将指定类型handlerType从Pipeline链表中删除
     */
    <T extends ChannelHandler> T remove(Class<T> handlerType);

    /**
     * 删除Pipeline链表首节点
     */
    ChannelHandler removeFirst();

    /**
     * 删除Pipeline链表尾节点
     */
    ChannelHandler removeLast();

    /**
     * 使用新的ChannelHandler替换指定{@link ChannelHandler}。
     */
    ChannelPipeline replace(ChannelHandler oldHandler, String newName, ChannelHandler newHandler);

    /**
     * 使用新的ChannelHandler替换指定名称{@link ChannelHandler}。
     */
    ChannelHandler replace(String oldName, String newName, ChannelHandler newHandler);

    /**
     * 使用新的ChannelHandler替换指定类型{@link ChannelHandler}。
     */
    <T extends ChannelHandler> T replace(Class<T> oldHandlerType, String newName,
                                         ChannelHandler newHandler);

    /**
     * 获取链式头节点ChannelHandler
     */
    ChannelHandler first();

    /**
     * 获取链式头节点
     */
    ChannelHandlerContext firstContext();

    /**
     * 获取链式尾节点ChannelHandler
     */
    ChannelHandler last();

    /**
     * 获取链式尾节点
     */
    ChannelHandlerContext lastContext();

    /**
     * 获取指定名称ChannelHandler
     */
    ChannelHandler get(String name);

    /**
     * 获取指定类型ChannelHandler
     */
    <T extends ChannelHandler> T get(Class<T> handlerType);

    /**
     * 返回指定ChannelHandler对应的ChannelHandlerContext
     */
    ChannelHandlerContext context(ChannelHandler handler);

    /**
     * 返回指定名称ChannelHandler对应的ChannelHandlerContext
     */
    ChannelHandlerContext context(String name);

    /**
     * 返回指定类型ChannelHandler对应的ChannelHandlerContext
     */
    ChannelHandlerContext context(Class<? extends ChannelHandler> handlerType);

    /**
     * 返回关联的{@link Channel}。
     */
    Channel channel();

    /**
     * 返回所有ChannelHandler名称
     */
    List<String> names();

    /**
     * 将所有ChannelHandler转换为Map并返回
     */
    Map<String, ChannelHandler> toMap();

    @Override
    ChannelPipeline fireChannelRegistered();

    @Override
    ChannelPipeline fireChannelUnregistered();

    @Override
    ChannelPipeline fireChannelActive();

    @Override
    ChannelPipeline fireChannelInactive();

    @Override
    ChannelPipeline fireExceptionCaught(Throwable cause);

    @Override
    ChannelPipeline fireUserEventTriggered(Object event);

    @Override
    ChannelPipeline fireChannelRead(Object msg);

    @Override
    ChannelPipeline fireChannelReadComplete();

    @Override
    ChannelPipeline fireChannelWritabilityChanged();

    @Override
    ChannelPipeline flush();
}
