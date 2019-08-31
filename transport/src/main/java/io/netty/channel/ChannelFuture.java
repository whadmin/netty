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

import io.netty.bootstrap.Bootstrap;
import io.netty.util.concurrent.BlockingOperationException;
import io.netty.util.concurrent.Future;
import io.netty.util.concurrent.GenericFutureListener;

import java.util.concurrent.TimeUnit;


/**
 * 异步{@link Channel} I / O操作的结果
 *
 * 1 netty中的所有I/O操作都是异步的。这意味着任何I/O操作都将立即返回，不用等待I/O操作完成。
 * 所有I/O操作都将立即返回一个@link channelfuture实例，它提供有关 I/O操作的结果或状态。
 * 当I/O操作开始时，将创建一个新的ChannelFuture对象。新的ChannelFuture最初是没有完成的——既没有成功，也没有失败，也没有取消。
 * 因为I/O操作尚未完成。如果I/O操作成功完成、失败或取消，则未来将标记为已完成，并带有更具体的信息，例如故障原因。
 * <pre>
 *                                      +---------------------------+
 *                                      | Completed successfully    |
 *                                      +---------------------------+
 *                                 +---->      isDone() = true      |
 * +--------------------------+    |    |   isSuccess() = true      |
 * |        Uncompleted       |    |    +===========================+
 * +--------------------------+    |    | Completed with failure    |
 * |      isDone() = false    |    |    +---------------------------+
 * |   isSuccess() = false    |----+---->      isDone() = true      |
 * | isCancelled() = false    |    |    |       cause() = non-null  |
 * |       cause() = null     |    |    +===========================+
 * +--------------------------+    |    | Completed by cancellation |
 *                                 |    +---------------------------+
 *                                 +---->      isDone() = true      |
 *                                      | isCancelled() = true      |
 *                                      +---------------------------+
 * </pre>
 * 2 ChannelFuture 提供了各种方法来检查I/O操作是否已完成，等待完成，并检索I/O操作的结果。
 *  它还允许您添加@link channelfuturelistener，以便在I/O操作完成时收到通知。
 *
 * 3 @link channelhandler中的事件处理程序方法通常由I/O线程调用。如下图
 *
 *   channel.read --> Pipline --> ChannelHandlerContext -->channelhandler
 *
 *   channel.read 通过NioEventLoop 实现异步处理并通过调用返回ChannelFuture对象{@link #await()}方法等待channelhandler处理完成，
 *   如果channelhandler内获取ChannelFuture对象调用{@link #await()}则会发生死锁。@link blockingoperationexception

 */
public interface ChannelFuture extends Future<Void> {

    /**
     * 返回与此future关联的I / O操作发生的通道。
     */
    Channel channel();

    @Override
    ChannelFuture addListener(GenericFutureListener<? extends Future<? super Void>> listener);

    @Override
    ChannelFuture addListeners(GenericFutureListener<? extends Future<? super Void>>... listeners);

    @Override
    ChannelFuture removeListener(GenericFutureListener<? extends Future<? super Void>> listener);

    @Override
    ChannelFuture removeListeners(GenericFutureListener<? extends Future<? super Void>>... listeners);

    @Override
    ChannelFuture sync() throws InterruptedException;

    @Override
    ChannelFuture syncUninterruptibly();

    @Override
    ChannelFuture await() throws InterruptedException;

    @Override
    ChannelFuture awaitUninterruptibly();

    /**
     * Returns {@code true} if this {@link ChannelFuture} is a void future and so not allow to call any of the
     * following methods:
     * <ul>
     *     <li>{@link #addListener(GenericFutureListener)}</li>
     *     <li>{@link #addListeners(GenericFutureListener[])}</li>
     *     <li>{@link #await()}</li>
     *     <li>{@link #await(long, TimeUnit)} ()}</li>
     *     <li>{@link #await(long)} ()}</li>
     *     <li>{@link #awaitUninterruptibly()}</li>
     *     <li>{@link #sync()}</li>
     *     <li>{@link #syncUninterruptibly()}</li>
     * </ul>
     */
    boolean isVoid();
}
