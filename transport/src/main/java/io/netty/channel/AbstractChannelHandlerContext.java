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

import io.netty.buffer.ByteBufAllocator;
import io.netty.util.Attribute;
import io.netty.util.AttributeKey;
import io.netty.util.Recycler;
import io.netty.util.ReferenceCountUtil;
import io.netty.util.ResourceLeakHint;
import io.netty.util.concurrent.EventExecutor;
import io.netty.util.concurrent.OrderedEventExecutor;
import io.netty.util.internal.PromiseNotificationUtil;
import io.netty.util.internal.ThrowableUtil;
import io.netty.util.internal.ObjectUtil;
import io.netty.util.internal.StringUtil;
import io.netty.util.internal.SystemPropertyUtil;
import io.netty.util.internal.logging.InternalLogger;
import io.netty.util.internal.logging.InternalLoggerFactory;

import java.net.SocketAddress;
import java.util.concurrent.atomic.AtomicIntegerFieldUpdater;

import static io.netty.channel.ChannelHandlerMask.MASK_BIND;
import static io.netty.channel.ChannelHandlerMask.MASK_CHANNEL_ACTIVE;
import static io.netty.channel.ChannelHandlerMask.MASK_CHANNEL_INACTIVE;
import static io.netty.channel.ChannelHandlerMask.MASK_CHANNEL_READ;
import static io.netty.channel.ChannelHandlerMask.MASK_CHANNEL_READ_COMPLETE;
import static io.netty.channel.ChannelHandlerMask.MASK_CHANNEL_REGISTERED;
import static io.netty.channel.ChannelHandlerMask.MASK_CHANNEL_UNREGISTERED;
import static io.netty.channel.ChannelHandlerMask.MASK_CHANNEL_WRITABILITY_CHANGED;
import static io.netty.channel.ChannelHandlerMask.MASK_CLOSE;
import static io.netty.channel.ChannelHandlerMask.MASK_CONNECT;
import static io.netty.channel.ChannelHandlerMask.MASK_DEREGISTER;
import static io.netty.channel.ChannelHandlerMask.MASK_DISCONNECT;
import static io.netty.channel.ChannelHandlerMask.MASK_EXCEPTION_CAUGHT;
import static io.netty.channel.ChannelHandlerMask.MASK_FLUSH;
import static io.netty.channel.ChannelHandlerMask.MASK_READ;
import static io.netty.channel.ChannelHandlerMask.MASK_USER_EVENT_TRIGGERED;
import static io.netty.channel.ChannelHandlerMask.MASK_WRITE;
import static io.netty.channel.ChannelHandlerMask.mask;

abstract class AbstractChannelHandlerContext implements ChannelHandlerContext, ResourceLeakHint {

    private static final InternalLogger logger = InternalLoggerFactory.getInstance(AbstractChannelHandlerContext.class);

    /**
     * {@link #handlerState} 的原子更新器
     */
    private static final AtomicIntegerFieldUpdater<AbstractChannelHandlerContext> HANDLER_STATE_UPDATER =
            AtomicIntegerFieldUpdater.newUpdater(AbstractChannelHandlerContext.class, "handlerState");

    /**
     * ChannelHandler处理器状态 添加准备中
     */
    private static final int ADD_PENDING = 1;

    /**
     * ChannelHandler处理器状态 已添加
     */
    private static final int ADD_COMPLETE = 2;

    /**
     * ChannelHandler处理器状态 已移除
     */
    private static final int REMOVE_COMPLETE = 3;

    /**
     * ChannelHandler处理器状态 初始化
     */
    private static final int INIT = 0;

    /**
     * 处理器状态
     */
    private volatile int handlerState = INIT;

    /**
     * 上一个节点
     */
    volatile AbstractChannelHandlerContext next;

    /**
     * 下一个节点
     */
    volatile AbstractChannelHandlerContext prev;

    /**
     * 所属 pipeline
     */
    private final DefaultChannelPipeline pipeline;

    /**
     * 名字
     */
    private final String name;

    /**
     * 是否使用有序的 EventExecutor ( {@link #executor} )，即 OrderedEventExecutor
     */
    private final boolean ordered;


    /**
     * 通过二进制描述handler类型，详细参考 {@link #ChannelHandlerMask}
     */
    private final int executionMask;

    /**
     * EventExecutor 对象
     */
    final EventExecutor executor;

    /**
     * 成功的 Promise 对象
     */
    private ChannelFuture succeededFuture;

    /**
     * Lazily实例化的任务，用于向具有不同执行程序的处理程序触发事件
     */
    private Tasks invokeTasks;



    AbstractChannelHandlerContext(DefaultChannelPipeline pipeline, EventExecutor executor,
                                  String name, Class<? extends ChannelHandler> handlerClass) {
        this.name = ObjectUtil.checkNotNull(name, "name");
        //设置pipeline
        this.pipeline = pipeline;
        //设置executor
        this.executor = executor;
        //计算executionMask
        this.executionMask = mask(handlerClass);
        //是否使用有序的 EventExecutor
        ordered = executor == null || executor instanceof OrderedEventExecutor;
    }

    @Override
    public Channel channel() {
        return pipeline.channel();
    }

    @Override
    public ChannelPipeline pipeline() {
        return pipeline;
    }

    @Override
    public ByteBufAllocator alloc() {
        return channel().config().getAllocator();
    }

    /**
     * 获取事件处理器
     */
    @Override
    public EventExecutor executor() {
        //如果未设置事件处理器，则使用 Channel 的 EventLoop 作为执行器。
        if (executor == null) {
            return channel().eventLoop();
        } else {
            return executor;
        }
    }

    @Override
    public String name() {
        return name;
    }

    @Override
    public ChannelHandlerContext fireChannelRegistered() {
        invokeChannelRegistered(findContextInbound(MASK_CHANNEL_REGISTERED));
        return this;
    }

    static void invokeChannelRegistered(final AbstractChannelHandlerContext next) {
        EventExecutor executor = next.executor();
        if (executor.inEventLoop()) {
            next.invokeChannelRegistered();
        } else {
            executor.execute(new Runnable() {
                @Override
                public void run() {
                    next.invokeChannelRegistered();
                }
            });
        }
    }

    private void invokeChannelRegistered() {
        if (invokeHandler()) {
            try {
                ((ChannelInboundHandler) handler()).channelRegistered(this);
            } catch (Throwable t) {
                notifyHandlerException(t);
            }
        } else {
            fireChannelRegistered();
        }
    }

    @Override
    public ChannelHandlerContext fireChannelUnregistered() {
        invokeChannelUnregistered(findContextInbound(MASK_CHANNEL_UNREGISTERED));
        return this;
    }

    static void invokeChannelUnregistered(final AbstractChannelHandlerContext next) {
        EventExecutor executor = next.executor();
        if (executor.inEventLoop()) {
            next.invokeChannelUnregistered();
        } else {
            executor.execute(new Runnable() {
                @Override
                public void run() {
                    next.invokeChannelUnregistered();
                }
            });
        }
    }

    private void invokeChannelUnregistered() {
        if (invokeHandler()) {
            try {
                ((ChannelInboundHandler) handler()).channelUnregistered(this);
            } catch (Throwable t) {
                notifyHandlerException(t);
            }
        } else {
            fireChannelUnregistered();
        }
    }

    @Override
    public ChannelHandlerContext fireChannelActive() {
        invokeChannelActive(findContextInbound(MASK_CHANNEL_ACTIVE));
        return this;
    }

    static void invokeChannelActive(final AbstractChannelHandlerContext next) {
        EventExecutor executor = next.executor();
        if (executor.inEventLoop()) {
            next.invokeChannelActive();
        } else {
            executor.execute(new Runnable() {
                @Override
                public void run() {
                    next.invokeChannelActive();
                }
            });
        }
    }

    private void invokeChannelActive() {
        if (invokeHandler()) {
            try {
                ((ChannelInboundHandler) handler()).channelActive(this);
            } catch (Throwable t) {
                notifyHandlerException(t);
            }
        } else {
            fireChannelActive();
        }
    }

    @Override
    public ChannelHandlerContext fireChannelInactive() {
        invokeChannelInactive(findContextInbound(MASK_CHANNEL_INACTIVE));
        return this;
    }

    static void invokeChannelInactive(final AbstractChannelHandlerContext next) {
        EventExecutor executor = next.executor();
        if (executor.inEventLoop()) {
            next.invokeChannelInactive();
        } else {
            executor.execute(new Runnable() {
                @Override
                public void run() {
                    next.invokeChannelInactive();
                }
            });
        }
    }

    private void invokeChannelInactive() {
        if (invokeHandler()) {
            try {
                ((ChannelInboundHandler) handler()).channelInactive(this);
            } catch (Throwable t) {
                notifyHandlerException(t);
            }
        } else {
            fireChannelInactive();
        }
    }

    @Override
    public ChannelHandlerContext fireExceptionCaught(final Throwable cause) {
        invokeExceptionCaught(findContextInbound(MASK_EXCEPTION_CAUGHT), cause);
        return this;
    }

    static void invokeExceptionCaught(final AbstractChannelHandlerContext next, final Throwable cause) {
        ObjectUtil.checkNotNull(cause, "cause");
        EventExecutor executor = next.executor();
        if (executor.inEventLoop()) {
            next.invokeExceptionCaught(cause);
        } else {
            try {
                executor.execute(new Runnable() {
                    @Override
                    public void run() {
                        next.invokeExceptionCaught(cause);
                    }
                });
            } catch (Throwable t) {
                if (logger.isWarnEnabled()) {
                    logger.warn("Failed to submit an exceptionCaught() event.", t);
                    logger.warn("The exceptionCaught() event that was failed to submit was:", cause);
                }
            }
        }
    }

    private void invokeExceptionCaught(final Throwable cause) {
        if (invokeHandler()) {
            try {
                handler().exceptionCaught(this, cause);
            } catch (Throwable error) {
                if (logger.isDebugEnabled()) {
                    logger.debug(
                        "An exception {}" +
                        "was thrown by a user handler's exceptionCaught() " +
                        "method while handling the following exception:",
                        ThrowableUtil.stackTraceToString(error), cause);
                } else if (logger.isWarnEnabled()) {
                    logger.warn(
                        "An exception '{}' [enable DEBUG level for full stacktrace] " +
                        "was thrown by a user handler's exceptionCaught() " +
                        "method while handling the following exception:", error, cause);
                }
            }
        } else {
            fireExceptionCaught(cause);
        }
    }

    @Override
    public ChannelHandlerContext fireUserEventTriggered(final Object event) {
        invokeUserEventTriggered(findContextInbound(MASK_USER_EVENT_TRIGGERED), event);
        return this;
    }

    static void invokeUserEventTriggered(final AbstractChannelHandlerContext next, final Object event) {
        ObjectUtil.checkNotNull(event, "event");
        EventExecutor executor = next.executor();
        if (executor.inEventLoop()) {
            next.invokeUserEventTriggered(event);
        } else {
            executor.execute(new Runnable() {
                @Override
                public void run() {
                    next.invokeUserEventTriggered(event);
                }
            });
        }
    }

    private void invokeUserEventTriggered(Object event) {
        if (invokeHandler()) {
            try {
                ((ChannelInboundHandler) handler()).userEventTriggered(this, event);
            } catch (Throwable t) {
                notifyHandlerException(t);
            }
        } else {
            fireUserEventTriggered(event);
        }
    }

    @Override
    public ChannelHandlerContext fireChannelRead(final Object msg) {
        invokeChannelRead(findContextInbound(MASK_CHANNEL_READ), msg);
        return this;
    }

    static void invokeChannelRead(final AbstractChannelHandlerContext next, Object msg) {
        final Object m = next.pipeline.touch(ObjectUtil.checkNotNull(msg, "msg"), next);
        EventExecutor executor = next.executor();
        if (executor.inEventLoop()) {
            next.invokeChannelRead(m);
        } else {
            executor.execute(new Runnable() {
                @Override
                public void run() {
                    next.invokeChannelRead(m);
                }
            });
        }
    }

    private void invokeChannelRead(Object msg) {
        if (invokeHandler()) {
            try {
                ((ChannelInboundHandler) handler()).channelRead(this, msg);
            } catch (Throwable t) {
                notifyHandlerException(t);
            }
        } else {
            fireChannelRead(msg);
        }
    }

    @Override
    public ChannelHandlerContext fireChannelReadComplete() {
        invokeChannelReadComplete(findContextInbound(MASK_CHANNEL_READ_COMPLETE));
        return this;
    }

    static void invokeChannelReadComplete(final AbstractChannelHandlerContext next) {
        EventExecutor executor = next.executor();
        if (executor.inEventLoop()) {
            next.invokeChannelReadComplete();
        } else {
            Tasks tasks = next.invokeTasks;
            if (tasks == null) {
                next.invokeTasks = tasks = new Tasks(next);
            }
            executor.execute(tasks.invokeChannelReadCompleteTask);
        }
    }

    private void invokeChannelReadComplete() {
        if (invokeHandler()) {
            try {
                ((ChannelInboundHandler) handler()).channelReadComplete(this);
            } catch (Throwable t) {
                notifyHandlerException(t);
            }
        } else {
            fireChannelReadComplete();
        }
    }

    @Override
    public ChannelHandlerContext fireChannelWritabilityChanged() {
        invokeChannelWritabilityChanged(findContextInbound(MASK_CHANNEL_WRITABILITY_CHANGED));
        return this;
    }

    static void invokeChannelWritabilityChanged(final AbstractChannelHandlerContext next) {
        EventExecutor executor = next.executor();
        if (executor.inEventLoop()) {
            next.invokeChannelWritabilityChanged();
        } else {
            Tasks tasks = next.invokeTasks;
            if (tasks == null) {
                next.invokeTasks = tasks = new Tasks(next);
            }
            executor.execute(tasks.invokeChannelWritableStateChangedTask);
        }
    }

    private void invokeChannelWritabilityChanged() {
        if (invokeHandler()) {
            try {
                ((ChannelInboundHandler) handler()).channelWritabilityChanged(this);
            } catch (Throwable t) {
                notifyHandlerException(t);
            }
        } else {
            fireChannelWritabilityChanged();
        }
    }

    @Override
    public ChannelFuture bind(SocketAddress localAddress) {
        return bind(localAddress, newPromise());
    }

    @Override
    public ChannelFuture connect(SocketAddress remoteAddress) {
        return connect(remoteAddress, newPromise());
    }

    @Override
    public ChannelFuture connect(SocketAddress remoteAddress, SocketAddress localAddress) {
        return connect(remoteAddress, localAddress, newPromise());
    }

    @Override
    public ChannelFuture disconnect() {
        return disconnect(newPromise());
    }

    @Override
    public ChannelFuture close() {
        return close(newPromise());
    }

    @Override
    public ChannelFuture deregister() {
        return deregister(newPromise());
    }

    @Override
    public ChannelFuture bind(final SocketAddress localAddress, final ChannelPromise promise) {
        if (localAddress == null) {
            throw new NullPointerException("localAddress");
        }
        // 判断是否为合法的 Promise 对象
        if (isNotValidPromise(promise, false)) {
            return promise;
        }

        // 获得下一个Outbound ，且包含Bind实现节点
        final AbstractChannelHandlerContext next = findContextOutbound(MASK_BIND);
        // 获取事件处理器
        EventExecutor executor = next.executor();
        if (executor.inEventLoop()) {
            //同步调用next节点invokeBind方法
            next.invokeBind(localAddress, promise);
        } else {
            //使用事件处理器异步执行 next.invokeBind
            safeExecute(executor, new Runnable() {
                @Override
                public void run() {
                    next.invokeBind(localAddress, promise);
                }
            }, promise, null);
        }
        return promise;
    }

    private void invokeBind(SocketAddress localAddress, ChannelPromise promise) {
        // 判断是否符合的 ChannelHandler
        if (invokeHandler()) {
            try {
                // 调用该 ChannelHandler 的 bind 方法，处理 bind 事件
                // 如果节点的数据类型为 DefaultChannelHandlerContext 类。那么他的处理器的类型会是 ChannelOutboundHandler
                // 在ChannelHandler 只有通过执行ctx.bind(remoteAddress, localAddress, promise);才能向前一点传递
                ((ChannelOutboundHandler) handler()).bind(this, localAddress, promise);
            } catch (Throwable t) {
                notifyOutboundHandlerException(t, promise);
            }
        } else {
            // 当前节点ChannelHandler不符合要求
            // 跳过，传播 Outbound 事件给下一个节点
            bind(localAddress, promise);
        }
    }

    @Override
    public ChannelFuture connect(SocketAddress remoteAddress, ChannelPromise promise) {
        return connect(remoteAddress, null, promise);
    }

    @Override
    public ChannelFuture connect(
            final SocketAddress remoteAddress, final SocketAddress localAddress, final ChannelPromise promise) {

        if (remoteAddress == null) {
            throw new NullPointerException("remoteAddress");
        }
        // 判断是否为合法的 Promise 对象
        if (isNotValidPromise(promise, false)) {
            return promise;
        }
        // 获得下一个Outbound ，且包含connect实现节点
        final AbstractChannelHandlerContext next = findContextOutbound(MASK_CONNECT);
        // 获取事件处理器
        EventExecutor executor = next.executor();
        // 判断当前线程是否是事件处理器工作线程
        if (executor.inEventLoop()) {
            //同步调用next节点invokeConnect方法
            next.invokeConnect(remoteAddress, localAddress, promise);
        } else {
            //使用事件处理器异步执行 next.invokeConnect
            safeExecute(executor, new Runnable() {
                @Override
                public void run() {
                    next.invokeConnect(remoteAddress, localAddress, promise);
                }
            }, promise, null);
        }
        return promise;
    }

    private void invokeConnect(SocketAddress remoteAddress, SocketAddress localAddress, ChannelPromise promise) {
        // 判断是否符合的 ChannelHandler
        if (invokeHandler()) {
            try {
                // 调用该 ChannelHandler 的 connect 方法，处理 bind 事件
                // 节点的数据类型为 DefaultChannelHandlerContext 类。那么他的处理器的类型会是 ChannelOutboundHandler
                // 在ChannelHandler 只有通过执行ctx.connect(remoteAddress, localAddress, promise);才能向前一点传递
                ((ChannelOutboundHandler) handler()).connect(this, remoteAddress, localAddress, promise);
            } catch (Throwable t) {
                //如果发生异常 通知 Outbound 事件的传播，发生异常
                notifyOutboundHandlerException(t, promise);
            }
        } else {
            // 当前节点ChannelHandler不符合要求
            // 跳过，传播 Outbound 事件给下一个节点
            connect(remoteAddress, localAddress, promise);
        }
    }

    @Override
    public ChannelFuture disconnect(final ChannelPromise promise) {
        if (!channel().metadata().hasDisconnect()) {
            // Translate disconnect to close if the channel has no notion of disconnect-reconnect.
            // So far, UDP/IP is the only transport that has such behavior.
            return close(promise);
        }
        if (isNotValidPromise(promise, false)) {
            // cancelled
            return promise;
        }

        final AbstractChannelHandlerContext next = findContextOutbound(MASK_DISCONNECT);
        EventExecutor executor = next.executor();
        if (executor.inEventLoop()) {
            next.invokeDisconnect(promise);
        } else {
            safeExecute(executor, new Runnable() {
                @Override
                public void run() {
                    next.invokeDisconnect(promise);
                }
            }, promise, null);
        }
        return promise;
    }

    private void invokeDisconnect(ChannelPromise promise) {
        if (invokeHandler()) {
            try {
                ((ChannelOutboundHandler) handler()).disconnect(this, promise);
            } catch (Throwable t) {
                notifyOutboundHandlerException(t, promise);
            }
        } else {
            disconnect(promise);
        }
    }

    @Override
    public ChannelFuture close(final ChannelPromise promise) {
        if (isNotValidPromise(promise, false)) {
            // cancelled
            return promise;
        }

        final AbstractChannelHandlerContext next = findContextOutbound(MASK_CLOSE);
        EventExecutor executor = next.executor();
        if (executor.inEventLoop()) {
            next.invokeClose(promise);
        } else {
            safeExecute(executor, new Runnable() {
                @Override
                public void run() {
                    next.invokeClose(promise);
                }
            }, promise, null);
        }

        return promise;
    }

    private void invokeClose(ChannelPromise promise) {
        if (invokeHandler()) {
            try {
                ((ChannelOutboundHandler) handler()).close(this, promise);
            } catch (Throwable t) {
                notifyOutboundHandlerException(t, promise);
            }
        } else {
            close(promise);
        }
    }

    @Override
    public ChannelFuture deregister(final ChannelPromise promise) {
        if (isNotValidPromise(promise, false)) {
            // cancelled
            return promise;
        }

        final AbstractChannelHandlerContext next = findContextOutbound(MASK_DEREGISTER);
        EventExecutor executor = next.executor();
        if (executor.inEventLoop()) {
            next.invokeDeregister(promise);
        } else {
            safeExecute(executor, new Runnable() {
                @Override
                public void run() {
                    next.invokeDeregister(promise);
                }
            }, promise, null);
        }

        return promise;
    }

    private void invokeDeregister(ChannelPromise promise) {
        if (invokeHandler()) {
            try {
                ((ChannelOutboundHandler) handler()).deregister(this, promise);
            } catch (Throwable t) {
                notifyOutboundHandlerException(t, promise);
            }
        } else {
            deregister(promise);
        }
    }

    @Override
    public ChannelHandlerContext read() {
        final AbstractChannelHandlerContext next = findContextOutbound(MASK_READ);
        EventExecutor executor = next.executor();
        if (executor.inEventLoop()) {
            next.invokeRead();
        } else {
            Tasks tasks = next.invokeTasks;
            if (tasks == null) {
                next.invokeTasks = tasks = new Tasks(next);
            }
            executor.execute(tasks.invokeReadTask);
        }

        return this;
    }

    private void invokeRead() {
        if (invokeHandler()) {
            try {
                ((ChannelOutboundHandler) handler()).read(this);
            } catch (Throwable t) {
                notifyHandlerException(t);
            }
        } else {
            read();
        }
    }

    @Override
    public ChannelFuture write(Object msg) {
        return write(msg, newPromise());
    }

    @Override
    public ChannelFuture write(final Object msg, final ChannelPromise promise) {
        write(msg, false, promise);

        return promise;
    }

    private void invokeWrite(Object msg, ChannelPromise promise) {
        if (invokeHandler()) {
            invokeWrite0(msg, promise);
        } else {
            write(msg, promise);
        }
    }

    private void invokeWrite0(Object msg, ChannelPromise promise) {
        try {
            ((ChannelOutboundHandler) handler()).write(this, msg, promise);
        } catch (Throwable t) {
            notifyOutboundHandlerException(t, promise);
        }
    }

    @Override
    public ChannelHandlerContext flush() {
        final AbstractChannelHandlerContext next = findContextOutbound(MASK_FLUSH);
        EventExecutor executor = next.executor();
        if (executor.inEventLoop()) {
            next.invokeFlush();
        } else {
            Tasks tasks = next.invokeTasks;
            if (tasks == null) {
                next.invokeTasks = tasks = new Tasks(next);
            }
            safeExecute(executor, tasks.invokeFlushTask, channel().voidPromise(), null);
        }

        return this;
    }

    private void invokeFlush() {
        if (invokeHandler()) {
            invokeFlush0();
        } else {
            flush();
        }
    }

    private void invokeFlush0() {
        try {
            ((ChannelOutboundHandler) handler()).flush(this);
        } catch (Throwable t) {
            notifyHandlerException(t);
        }
    }

    @Override
    public ChannelFuture writeAndFlush(Object msg, ChannelPromise promise) {
        write(msg, true, promise);
        return promise;
    }

    private void invokeWriteAndFlush(Object msg, ChannelPromise promise) {
        if (invokeHandler()) {
            invokeWrite0(msg, promise);
            invokeFlush0();
        } else {
            writeAndFlush(msg, promise);
        }
    }

    private void write(Object msg, boolean flush, ChannelPromise promise) {
        /** 消息( 数据 )为空，抛出异常 **/
        ObjectUtil.checkNotNull(msg, "msg");
        try {
            /** 判断是否为合法的 Promise 对象 **/
            if (isNotValidPromise(promise, true)) {
                /**  释放消息( 数据 )相关的资源 **/
                ReferenceCountUtil.release(msg);
                /**  返回 **/
                return;
            }
        } catch (RuntimeException e) {
            /**  若发生异常，释放消息( 数据 )相关的资源 **/
            ReferenceCountUtil.release(msg);
            /**  抛出该异常 **/
            throw e;
        }

        /** 获得下一个 Outbound 节点 **/
        final AbstractChannelHandlerContext next = findContextOutbound(flush ?
                (MASK_WRITE | MASK_FLUSH) : MASK_WRITE);

        /** 记录 Record 记录  **/
        final Object m = pipeline.touch(msg, next);
        EventExecutor executor = next.executor();
        if (executor.inEventLoop()) {
            /** 执行 writeAndFlush 事件到下一个节点 **/
            if (flush) {
                next.invokeWriteAndFlush(m, promise);
            }
            /** 执行 write 事件到下一个节点  **/
            else {
                next.invokeWrite(m, promise);
            }
        } else {
            final AbstractWriteTask task;
            /** 创建 writeAndFlush 任务  **/
            if (flush) {
                task = WriteAndFlushTask.newInstance(next, m, promise);
            }
            /** 创建 write 任务 **/
            else {
                task = WriteTask.newInstance(next, m, promise);
            }
            /** 提交到 EventLoop 的线程中，执行该任务 **/
            if (!safeExecute(executor, task, promise, m)) {
                // We failed to submit the AbstractWriteTask. We need to cancel it so we decrement the pending bytes
                // and put it back in the Recycler for re-use later.
                //
                // See https://github.com/netty/netty/issues/8343.
                task.cancel();
            }
        }
    }

    @Override
    public ChannelFuture writeAndFlush(Object msg) {
        return writeAndFlush(msg, newPromise());
    }

    private static void notifyOutboundHandlerException(Throwable cause, ChannelPromise promise) {
        // Only log if the given promise is not of type VoidChannelPromise as tryFailure(...) is expected to return
        // false.
        PromiseNotificationUtil.tryFailure(promise, cause, promise instanceof VoidChannelPromise ? null : logger);
    }

    private void notifyHandlerException(Throwable cause) {
        if (inExceptionCaught(cause)) {
            if (logger.isWarnEnabled()) {
                logger.warn(
                        "An exception was thrown by a user handler " +
                                "while handling an exceptionCaught event", cause);
            }
            return;
        }

        invokeExceptionCaught(cause);
    }

    private static boolean inExceptionCaught(Throwable cause) {
        do {
            StackTraceElement[] trace = cause.getStackTrace();
            if (trace != null) {
                for (StackTraceElement t : trace) {
                    if (t == null) {
                        break;
                    }
                    if ("exceptionCaught".equals(t.getMethodName())) {
                        return true;
                    }
                }
            }

            cause = cause.getCause();
        } while (cause != null);

        return false;
    }

    @Override
    public ChannelPromise newPromise() {
        return new DefaultChannelPromise(channel(), executor());
    }

    @Override
    public ChannelProgressivePromise newProgressivePromise() {
        return new DefaultChannelProgressivePromise(channel(), executor());
    }

    @Override
    public ChannelFuture newSucceededFuture() {
        ChannelFuture succeededFuture = this.succeededFuture;
        if (succeededFuture == null) {
            this.succeededFuture = succeededFuture = new SucceededChannelFuture(channel(), executor());
        }
        return succeededFuture;
    }

    @Override
    public ChannelFuture newFailedFuture(Throwable cause) {
        return new FailedChannelFuture(channel(), executor(), cause);
    }

    private boolean isNotValidPromise(ChannelPromise promise, boolean allowVoidPromise) {
        //promise为null
        if (promise == null) {
            throw new NullPointerException("promise");
        }
        // Promise 已经完成
        if (promise.isDone()) {
            // See https://github.com/netty/netty/issues/2349
            if (promise.isCancelled()) {
                return true;
            }
            throw new IllegalArgumentException("promise already done: " + promise);
        }

        // Channel 不匹配
        if (promise.channel() != channel()) {
            throw new IllegalArgumentException(String.format(
                    "promise.channel does not match: %s (expected: %s)", promise.channel(), channel()));
        }
        // promise 的类型为 DefaultChannelPromise
        if (promise.getClass() == DefaultChannelPromise.class) {
            return false;
        }

        // 禁止 VoidChannelPromise
        if (!allowVoidPromise && promise instanceof VoidChannelPromise) {
            throw new IllegalArgumentException(
                    StringUtil.simpleClassName(VoidChannelPromise.class) + " not allowed for this operation");
        }

        // 禁止 CloseFuture
        if (promise instanceof AbstractChannel.CloseFuture) {
            throw new IllegalArgumentException(
                    StringUtil.simpleClassName(AbstractChannel.CloseFuture.class) + " not allowed in a pipeline");
        }
        return false;
    }

    /**
     * 获得下一个 Inbound 节点
     */
    private AbstractChannelHandlerContext findContextInbound(int mask) {
        AbstractChannelHandlerContext ctx = this;
        do {
            ctx = ctx.next;
        } while ((ctx.executionMask & mask) == 0);
        return ctx;
    }

    /**
     * 获得下一个 Outbound 节点
     */
    private AbstractChannelHandlerContext findContextOutbound(int mask) {
        AbstractChannelHandlerContext ctx = this;
        do {
            ctx = ctx.prev;
        } while ((ctx.executionMask & mask) == 0);
        return ctx;
    }

    @Override
    public ChannelPromise voidPromise() {
        return channel().voidPromise();
    }


    /**
     * 设置ChannelHandler处理器状态为已移除
     */
    final void setRemoved() {
        handlerState = REMOVE_COMPLETE;
    }

    /**
     * 使用循环 + CAS 保证多线程下的安全变更ChannelHandler处理器状态为已添加
     */
    final boolean setAddComplete() {
        for (;;) {
            int oldState = handlerState;
            if (oldState == REMOVE_COMPLETE) {
                return false;
            }
            if (HANDLER_STATE_UPDATER.compareAndSet(this, oldState, ADD_COMPLETE)) {
                return true;
            }
        }
    }

    /**
     * 使用CAS变更ChannelHandler处理器状态为添加准备中，返回是否更新成功
     */
    final void setAddPending() {
        boolean updated = HANDLER_STATE_UPDATER.compareAndSet(this, INIT, ADD_PENDING);
        assert updated;
    }


    /**
     * 回调 ChannelHandler.callHandlerAdded 添加完成( added )事件
     */
    final void callHandlerAdded() throws Exception {
        //变更ChannelHandler处理器状态为已添加
        if (setAddComplete()) {
            handler().handlerAdded(this);
        }
    }

    /**
     * 回调 ChannelHandler.handlerRemoved 移除完成( removed )事件
     */
    final void callHandlerRemoved() throws Exception {
        try {
            //判断ChannelHandler处理器状态是否为已添加
            if (handlerState == ADD_COMPLETE) {
                //回调 ChannelHandler.handlerRemoved
                handler().handlerRemoved(this);
            }
        } finally {
            //设置ChannelHandler处理器状态为已移除
            setRemoved();
        }
    }

    /**
     * 尽最大努力检测是否还调用了{@link ChannelHandler＃handlerAdded（ChannelHandlerContext）}。,
     * 如果没有返回{@code false}并且如果被调用或无法检测返回{@code true}。
     */
    private boolean invokeHandler() {
        int handlerState = this.handlerState;
        return handlerState == ADD_COMPLETE || (!ordered && handlerState == ADD_PENDING);
    }

    @Override
    public boolean isRemoved() {
        return handlerState == REMOVE_COMPLETE;
    }

    @Override
    public <T> Attribute<T> attr(AttributeKey<T> key) {
        return channel().attr(key);
    }

    @Override
    public <T> boolean hasAttr(AttributeKey<T> key) {
        return channel().hasAttr(key);
    }

    /**
     * 使用事件处理器异步执行任务 runnable
     */
    private static boolean safeExecute(EventExecutor executor, Runnable runnable, ChannelPromise promise, Object msg) {
        try {
            //将任务提交给事件处理器执行
            executor.execute(runnable);
            return true;
        } catch (Throwable cause) {
            try {
                // 发生异常，回调通知 promise 相关的异常
                promise.setFailure(cause);
            } finally {
                // 释放 msg 相关的资源
                if (msg != null) {
                    ReferenceCountUtil.release(msg);
                }
            }
            return false;
        }
    }

    @Override
    public String toHintString() {
        return '\'' + name + "' will handle the message from this point.";
    }

    @Override
    public String toString() {
        return StringUtil.simpleClassName(ChannelHandlerContext.class) + '(' + name + ", " + channel() + ')';
    }

    abstract static class AbstractWriteTask implements Runnable {

        private static final boolean ESTIMATE_TASK_SIZE_ON_SUBMIT =
                SystemPropertyUtil.getBoolean("io.netty.transport.estimateSizeOnSubmit", true);

        // Assuming a 64-bit JVM, 16 bytes object header, 3 reference fields and one int field, plus alignment
        private static final int WRITE_TASK_OVERHEAD =
                SystemPropertyUtil.getInt("io.netty.transport.writeTaskSizeOverhead", 48);

        private final Recycler.Handle<AbstractWriteTask> handle;
        private AbstractChannelHandlerContext ctx;
        private Object msg;
        private ChannelPromise promise;
        private int size;

        @SuppressWarnings("unchecked")
        private AbstractWriteTask(Recycler.Handle<? extends AbstractWriteTask> handle) {
            this.handle = (Recycler.Handle<AbstractWriteTask>) handle;
        }

        protected static void init(AbstractWriteTask task, AbstractChannelHandlerContext ctx,
                                   Object msg, ChannelPromise promise) {
            task.ctx = ctx;
            task.msg = msg;
            task.promise = promise;

            if (ESTIMATE_TASK_SIZE_ON_SUBMIT) {
                task.size = ctx.pipeline.estimatorHandle().size(msg) + WRITE_TASK_OVERHEAD;
                ctx.pipeline.incrementPendingOutboundBytes(task.size);
            } else {
                task.size = 0;
            }
        }

        @Override
        public final void run() {
            try {
                decrementPendingOutboundBytes();
                write(ctx, msg, promise);
            } finally {
                recycle();
            }
        }

        void cancel() {
            try {
                decrementPendingOutboundBytes();
            } finally {
                recycle();
            }
        }

        private void decrementPendingOutboundBytes() {
            if (ESTIMATE_TASK_SIZE_ON_SUBMIT) {
                ctx.pipeline.decrementPendingOutboundBytes(size);
            }
        }

        private void recycle() {
            // Set to null so the GC can collect them directly
            ctx = null;
            msg = null;
            promise = null;
            handle.recycle(this);
        }

        protected void write(AbstractChannelHandlerContext ctx, Object msg, ChannelPromise promise) {
            ctx.invokeWrite(msg, promise);
        }
    }

    static final class WriteTask extends AbstractWriteTask implements SingleThreadEventLoop.NonWakeupRunnable {

        private static final Recycler<WriteTask> RECYCLER = new Recycler<WriteTask>() {
            @Override
            protected WriteTask newObject(Handle<WriteTask> handle) {
                return new WriteTask(handle);
            }
        };

        static WriteTask newInstance(
                AbstractChannelHandlerContext ctx, Object msg, ChannelPromise promise) {
            WriteTask task = RECYCLER.get();
            init(task, ctx, msg, promise);
            return task;
        }

        private WriteTask(Recycler.Handle<WriteTask> handle) {
            super(handle);
        }
    }

    static final class WriteAndFlushTask extends AbstractWriteTask {

        private static final Recycler<WriteAndFlushTask> RECYCLER = new Recycler<WriteAndFlushTask>() {
            @Override
            protected WriteAndFlushTask newObject(Handle<WriteAndFlushTask> handle) {
                return new WriteAndFlushTask(handle);
            }
        };

        static WriteAndFlushTask newInstance(
                AbstractChannelHandlerContext ctx, Object msg,  ChannelPromise promise) {
            WriteAndFlushTask task = RECYCLER.get();
            init(task, ctx, msg, promise);
            return task;
        }

        private WriteAndFlushTask(Recycler.Handle<WriteAndFlushTask> handle) {
            super(handle);
        }

        @Override
        public void write(AbstractChannelHandlerContext ctx, Object msg, ChannelPromise promise) {
            super.write(ctx, msg, promise);
            ctx.invokeFlush();
        }
    }

    private static final class Tasks {
        private final AbstractChannelHandlerContext next;
        private final Runnable invokeChannelReadCompleteTask = new Runnable() {
            @Override
            public void run() {
                next.invokeChannelReadComplete();
            }
        };
        private final Runnable invokeReadTask = new Runnable() {
            @Override
            public void run() {
                next.invokeRead();
            }
        };
        private final Runnable invokeChannelWritableStateChangedTask = new Runnable() {
            @Override
            public void run() {
                next.invokeChannelWritabilityChanged();
            }
        };
        private final Runnable invokeFlushTask = new Runnable() {
            @Override
            public void run() {
                next.invokeFlush();
            }
        };

        Tasks(AbstractChannelHandlerContext next) {
            this.next = next;
        }
    }
}
