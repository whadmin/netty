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
import io.netty.channel.socket.ChannelOutputShutdownEvent;
import io.netty.channel.socket.ChannelOutputShutdownException;
import io.netty.util.DefaultAttributeMap;
import io.netty.util.ReferenceCountUtil;
import io.netty.util.internal.PlatformDependent;
import io.netty.util.internal.UnstableApi;
import io.netty.util.internal.logging.InternalLogger;
import io.netty.util.internal.logging.InternalLoggerFactory;

import java.io.IOException;
import java.net.ConnectException;
import java.net.InetSocketAddress;
import java.net.NoRouteToHostException;
import java.net.SocketAddress;
import java.net.SocketException;
import java.nio.channels.ClosedChannelException;
import java.nio.channels.NotYetConnectedException;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;

/**
 * A skeletal {@link Channel} implementation.
 */
public abstract class AbstractChannel extends DefaultAttributeMap implements Channel {

    private static final InternalLogger logger = InternalLoggerFactory.getInstance(AbstractChannel.class);

    /**
     * 父 Channel 对象
     */
    private final Channel parent;

    /**
     * Channel 编号
     */
    private final ChannelId id;

    /**
     * Unsafe 对象
     */
    private final Unsafe unsafe;

    /**
     * DefaultChannelPipeline 对象
     */
    private final DefaultChannelPipeline pipeline;

    /**
     * 不进行通知的 Promise 对象
     */
    private final VoidChannelPromise unsafeVoidPromise = new VoidChannelPromise(this, false);

    /**
     * Channel 关闭的 Future 对象
     */
    private final CloseFuture closeFuture = new CloseFuture(this);

    /**
     * 本地地址
     */
    private volatile SocketAddress localAddress;

    /**
     * 远程地址
     */
    private volatile SocketAddress remoteAddress;

    /**
     * 注册到的EventLoop
     */
    private volatile EventLoop eventLoop;

    /**
     * 是否已注册
     */
    private volatile boolean registered;
    private boolean closeInitiated;
    private Throwable initialCloseCause;

    private boolean strValActive;
    private String strVal;

    /**
     *  指定父Channel，实例化AbstractChannel
     */
    protected AbstractChannel(Channel parent) {
        //设置父 Channel 对象
        this.parent = parent;
        //设置新建Channel 编号
        id = newId();
        //设置unsafe
        unsafe = newUnsafe();
        //设置pipeline
        pipeline = newChannelPipeline();
    }

    /**
     * 指定父Channel，ChannelId来实例化AbstractChannel
     */
    protected AbstractChannel(Channel parent, ChannelId id) {
        this.parent = parent;
        this.id = id;
        unsafe = newUnsafe();
        pipeline = newChannelPipeline();
    }


    /**
     * 创建一个新{@link DefaultChannelId}实例并返回
     */
    protected ChannelId newId() {
        return DefaultChannelId.newInstance();
    }

    /**
     * 创建一个新{@link DefaultChannelPipeline}实例并返回
     */
    protected DefaultChannelPipeline newChannelPipeline() {
        return new DefaultChannelPipeline(this);
    }


    //==============获取子组件相关==============
    @Override
    public final ChannelId id() {
        return id;
    }

    @Override
    public Channel parent() {
        return parent;
    }

    @Override
    public ChannelPipeline pipeline() {
        return pipeline;
    }

    @Override
    public ByteBufAllocator alloc() {
        return config().getAllocator();
    }

    @Override
    public EventLoop eventLoop() {
        EventLoop eventLoop = this.eventLoop;
        if (eventLoop == null) {
            throw new IllegalStateException("channel not registered to an event loop");
        }
        return eventLoop;
    }

    @Override
    public Unsafe unsafe() {
        return unsafe;
    }


    //==============写缓存队列相关实现==============
    //写缓存是通过unsafe内部ChannelOutboundBuffer组件实现
    /**
     * Channel 是否可写
     * 当 Channel 的写缓存区 outbound 非 null 且可写时，返回 true
     */
    @Override
    public boolean isWritable() {
        ChannelOutboundBuffer buf = unsafe.outboundBuffer();
        /** 当 Channel 的写缓存区 outbound 非 null 且可写时，返回 true **/
        return buf != null && buf.isWritable();
    }

    /**
     * Channel 的写缓存区 outbound 距离不可写还有多少字节数
     */
    @Override
    public long bytesBeforeUnwritable() {
        ChannelOutboundBuffer buf = unsafe.outboundBuffer();
        return buf != null ? buf.bytesBeforeUnwritable() : 0;
    }

    /**
     *  Channel 的写缓存区 outbound 距离可写还要多少字节数
     */
    @Override
    public long bytesBeforeWritable() {
        ChannelOutboundBuffer buf = unsafe.outboundBuffer();
        return buf != null ? buf.bytesBeforeWritable() : Long.MAX_VALUE;
    }


    //==============配置相关==============


    /**
     * 返回本地地址 是通过从unsafe().localAddress()获取
     */
    @Override
    public SocketAddress localAddress() {
        SocketAddress localAddress = this.localAddress;
        if (localAddress == null) {
            try {
                this.localAddress = localAddress = unsafe().localAddress();
            } catch (Error e) {
                throw e;
            } catch (Throwable t) {
                return null;
            }
        }
        return localAddress;
    }


    @Deprecated
    protected void invalidateLocalAddress() {
        localAddress = null;
    }

    /**
     * 返回远程地址 是通过从unsafe().remoteAddress()获取
     */
    @Override
    public SocketAddress remoteAddress() {
        SocketAddress remoteAddress = this.remoteAddress;
        if (remoteAddress == null) {
            try {
                this.remoteAddress = remoteAddress = unsafe().remoteAddress();
            } catch (Error e) {
                throw e;
            } catch (Throwable t) {
                return null;
            }
        }
        return remoteAddress;
    }

    @Deprecated
    protected void invalidateRemoteAddress() {
        remoteAddress = null;
    }

    //==============状态相关相关==============
    @Override
    public boolean isRegistered() {
        return registered;
    }

    //==============ChannelOutboundInvoker实现==============
    @Override
    public ChannelFuture bind(SocketAddress localAddress) {
        return pipeline.bind(localAddress);
    }


    @Override
    public ChannelFuture bind(SocketAddress localAddress, ChannelPromise promise) {
        return pipeline.bind(localAddress, promise);
    }

    @Override
    public ChannelFuture connect(SocketAddress remoteAddress) {
        return pipeline.connect(remoteAddress);
    }

    @Override
    public ChannelFuture connect(SocketAddress remoteAddress, SocketAddress localAddress) {
        return pipeline.connect(remoteAddress, localAddress);
    }

    @Override
    public ChannelFuture connect(SocketAddress remoteAddress, ChannelPromise promise) {
        return pipeline.connect(remoteAddress, promise);
    }

    @Override
    public ChannelFuture connect(SocketAddress remoteAddress, SocketAddress localAddress, ChannelPromise promise) {
        return pipeline.connect(remoteAddress, localAddress, promise);
    }

    @Override
    public ChannelFuture disconnect() {
        return pipeline.disconnect();
    }

    @Override
    public ChannelFuture disconnect(ChannelPromise promise) {
        return pipeline.disconnect(promise);
    }

    @Override
    public ChannelFuture close() {
        return pipeline.close();
    }

    @Override
    public ChannelFuture close(ChannelPromise promise) {
        return pipeline.close(promise);
    }

    @Override
    public ChannelFuture deregister() {
        return pipeline.deregister();
    }

    @Override
    public ChannelFuture deregister(ChannelPromise promise) {
        return pipeline.deregister(promise);
    }

    @Override
    public Channel read() {
        pipeline.read();
        return this;
    }

    @Override
    public ChannelFuture write(Object msg) {
        return pipeline.write(msg);
    }

    @Override
    public ChannelFuture write(Object msg, ChannelPromise promise) {
        return pipeline.write(msg, promise);
    }

    @Override
    public ChannelFuture writeAndFlush(Object msg) {
        return pipeline.writeAndFlush(msg);
    }

    @Override
    public ChannelFuture writeAndFlush(Object msg, ChannelPromise promise) {
        return pipeline.writeAndFlush(msg, promise);
    }

    @Override
    public Channel flush() {
        pipeline.flush();
        return this;
    }


    //==============异步结果Future相关==============
    @Override
    public ChannelPromise newPromise() {
        return pipeline.newPromise();
    }

    @Override
    public ChannelProgressivePromise newProgressivePromise() {
        return pipeline.newProgressivePromise();
    }

    @Override
    public ChannelFuture newSucceededFuture() {
        return pipeline.newSucceededFuture();
    }

    @Override
    public ChannelFuture newFailedFuture(Throwable cause) {
        return pipeline.newFailedFuture(cause);
    }

    @Override
    public ChannelFuture closeFuture() {
        return closeFuture;
    }

    @Override
    public final ChannelPromise voidPromise() {
        return pipeline.voidPromise();
    }

    //==============模板方法==============
    /**
     * 模板方法，子类实现
     */
    protected abstract AbstractUnsafe newUnsafe();

    /**
     * 模板方法，如果给定的{@link EventLoop}与此实例兼容，则返回{@code true}。
     */
    protected abstract boolean isCompatible(EventLoop loop);

    /**
     * 模板方法，返回本地地址实现
     */
    protected abstract SocketAddress localAddress0();

    /**
     * 模板方法，返回本地地址实现
     */
    protected abstract SocketAddress remoteAddress0();

    /**
     * 模板方法，在{@link Channel}注册到{@link EventLoop}实现
     */
    protected void doRegister() throws Exception {
    }

    /**
     * 模板方法，{@link Channel} 绑定到指定地址实现
     */
    protected abstract void doBind(SocketAddress localAddress) throws Exception;

    /**
     * 模板方法，{@link Channel} 断开于远程地址的连接
     */
    protected abstract void doDisconnect() throws Exception;

    /**
     * 模板方法，关闭{@link Channel}
     */
    protected abstract void doClose() throws Exception;

    /**
     * 关闭{@link Channel}
     */
    @UnstableApi
    protected void doShutdownOutput() throws Exception {
        doClose();
    }

    /**
     * {@link Channel}从{@link EventLoop}中注销
     */
    protected void doDeregister() throws Exception {
        // NOOP
    }

    /**
     * @link Channel}向{@link EventLoop}注册感兴趣事件
     */
    protected abstract void doBeginRead() throws Exception;

    /**
     * 将给定缓冲区的内容刷新到远程对等方。
     */
    protected abstract void doWrite(ChannelOutboundBuffer in) throws Exception;

    //==============object相关实现==============
    @Override
    public final int hashCode() {
        return id.hashCode();
    }

    @Override
    public final boolean equals(Object o) {
        return this == o;
    }

    @Override
    public final int compareTo(Channel o) {
        if (this == o) {
            return 0;
        }
        return id().compareTo(o.id());
    }

    @Override
    public String toString() {
        boolean active = isActive();
        if (strValActive == active && strVal != null) {
            return strVal;
        }

        SocketAddress remoteAddr = remoteAddress();
        SocketAddress localAddr = localAddress();
        if (remoteAddr != null) {
            StringBuilder buf = new StringBuilder(96)
                .append("[id: 0x")
                .append(id.asShortText())
                .append(", L:")
                .append(localAddr)
                .append(active? " - " : " ! ")
                .append("R:")
                .append(remoteAddr)
                .append(']');
            strVal = buf.toString();
        } else if (localAddr != null) {
            StringBuilder buf = new StringBuilder(64)
                .append("[id: 0x")
                .append(id.asShortText())
                .append(", L:")
                .append(localAddr)
                .append(']');
            strVal = buf.toString();
        } else {
            StringBuilder buf = new StringBuilder(16)
                .append("[id: 0x")
                .append(id.asShortText())
                .append(']');
            strVal = buf.toString();
        }

        strValActive = active;
        return strVal;
    }



    protected abstract class AbstractUnsafe implements Unsafe {

        /** 缓冲队列 **/
        private volatile ChannelOutboundBuffer outboundBuffer = new ChannelOutboundBuffer(AbstractChannel.this);
        private RecvByteBufAllocator.Handle recvHandle;
        /** 用来标记当前正在执行刷新 **/
        private boolean inFlush0;
        /** 记录是否为首次注册  **/
        private boolean neverRegistered = true;

        private void assertEventLoop() {
            assert !registered || eventLoop.inEventLoop();
        }

        @Override
        public RecvByteBufAllocator.Handle recvBufAllocHandle() {
            if (recvHandle == null) {
                recvHandle = config().getRecvByteBufAllocator().newHandle();
            }
            return recvHandle;
        }

        /**
         * 返回写缓存队列
         */
        @Override
        public final ChannelOutboundBuffer outboundBuffer() {
            return outboundBuffer;
        }

        /**
         * 获取本地地址，调用AbstractChannel.localAddress0,这里是模板方法，交给子类实现
         */
        @Override
        public final SocketAddress localAddress() {
            return localAddress0();
        }

        /**
         * 获取远程地址，调用AbstractChannel.remoteAddress0,这里是模板方法，交给子类实现
         */
        @Override
        public final SocketAddress remoteAddress() {
            return remoteAddress0();
        }

        /**
         * 将Channel注册到指定EventLoop，ChannelPromise负责在完成后通知
         */
        @Override
        public final void register(EventLoop eventLoop, final ChannelPromise promise) {
            /**  校验传入的 eventLoop 非空 **/
            if (eventLoop == null) {
                throw new NullPointerException("eventLoop");
            }

            /** 校验未注册  **/
            if (isRegistered()) {
                promise.setFailure(new IllegalStateException("registered to an event loop already"));
                return;
            }

            /** 校验 Channel 和 eventLoop 匹配  **/
            if (!isCompatible(eventLoop)) {
                promise.setFailure(
                        new IllegalStateException("incompatible event loop type: " + eventLoop.getClass().getName()));
                return;
            }

            /** 设置 Channel 的 eventLoop 属性  **/
            AbstractChannel.this.eventLoop = eventLoop;

            /** 如果当前线程不是事件处理器工作线程register0 **/
            if (eventLoop.inEventLoop()) {
                register0(promise);
            } else {
                /** 使用事件处理器异步执行 **/
                try {
                    eventLoop.execute(new Runnable() {
                        @Override
                        public void run() {
                            register0(promise);
                        }
                    });
                } catch (Throwable t) {
                    //若调用 EventLoop#execute(Runnable) 方法发生异常，则进行处理  **/
                    logger.warn(
                            "Force-closing a channel whose registration task was not accepted by an event loop: {}",
                            AbstractChannel.this, t);
                    /** 强制关闭 Channel **/
                    closeForcibly();
                    /** closeFuture 设置Channel已经关闭，通知回调 **/
                    closeFuture.setClosed();
                    /** promise 设置发生该异常，通知回调 **/
                    safeSetFailure(promise, t);
                }
            }
        }

        private void register0(ChannelPromise promise) {
            try {
                /** 标记promise无法取消成功 且 ensureOpen确保 Channel 是打开的  **/
                if (!promise.setUncancellable() || !ensureOpen(promise)) {
                    return;
                }
                /** 记录是否为首次注册  **/
                boolean firstRegistration = neverRegistered;

                /** 调用AbstractChannel.localAddress0 实现注册，这里是模板方法，交给子类实现，Nio实现类为AbstractNioChannel
                 * **/
                doRegister();

                /** 标记neverRegistered  false **/
                neverRegistered = false;

                /** 标记 Channel 为已注册  **/
                registered = true;

                /**
                 * 执行pipeline中pendingHandlerCallbackHead链表上延时任务.
                   在将ChannelInitializer添加到Pipline时，由于多线程并发运行，此时Channel并未注册到EventLoop中，按照添加逻辑无法触发handlerAdded事件
                   因此会创建PendingHandlerAddedTask对象添加到Pipline中pendingHandlerCallbackHead链表中。在注册完毕后执行PendingHandlerAddedTask任务，
                   触发handlerAdded事件**/
                pipeline.invokeHandlerAddedIfNeeded();

                /**  promise 设置注册成功执行成功，回调通知 **/
                safeSetSuccess(promise);

                /**  触发，fireChannelRegistered 事件传递给pipeline  **/
                pipeline.fireChannelRegistered();

                /**  判断 Channel 是否被激活  **/
                if (isActive()) {
                    /**
                     *   如果是首次注册调用 触发fireChannelActive 事件传递给 pipeline.fireChannelActive
                     *  在其内部会将Channel感兴趣的事件给注册到EventLoop中，详细步骤如下：
                     *   1 pipeline.fireChannelActive最终会交给HeadContext.channelActive 处理。HeadContext.channelActive会判断channel.config().isAutoRead()是否为true
                     *   2 如果channel.config().isAutoRead()为true 调用 channel.read();，内部会传递给pipeline.read();
                     *   3 pipeline.read() 最终会交给HeadContext.read,其内部实现通过 unsafe.beginRead()，
                     *   4 unsafe.beginRead() 调用doBeginRead 最终将Channel感兴趣的事件给注册到EventLoop中
                     *   **/
                    if (firstRegistration) {
                        pipeline.fireChannelActive();
                    }
                    /** 如果不时首次注册，直接调用beginRead,将Channel感兴趣的事件给注册到EventLoop中 **/
                    else if (config().isAutoRead()) {
                        beginRead();
                    }
                }
            } catch (Throwable t) {
                /** 强制关闭 Channel **/
                closeForcibly();
                /** closeFuture 设置Channel已经关闭，通知回调 **/
                closeFuture.setClosed();
                /** promise 设置发生该异常，通知回调 **/
                safeSetFailure(promise, t);
            }
        }

        /**
         * 将SocketAddress绑定到Channel，ChannelPromise负责在完成后通知
         */
        @Override
        public final void bind(final SocketAddress localAddress, final ChannelPromise promise) {
            /** 判断是否在 EventLoop 的线程中。 **/
            assertEventLoop();

            /** 标记promise无法取消成功 且 ensureOpen确保 Channel 是打开的  **/
            if (!promise.setUncancellable() || !ensureOpen(promise)) {
                return;
            }

            // See: https://github.com/netty/netty/issues/576
            if (Boolean.TRUE.equals(config().getOption(ChannelOption.SO_BROADCAST)) &&
                localAddress instanceof InetSocketAddress &&
                !((InetSocketAddress) localAddress).getAddress().isAnyLocalAddress() &&
                !PlatformDependent.isWindows() && !PlatformDependent.maybeSuperUser()) {
                logger.warn(
                        "A non-root user can't receive a broadcast packet if the socket " +
                        "is not bound to a wildcard address; binding to a non-wildcard " +
                        "address (" + localAddress + ") anyway as requested.");
            }

            /**  获得 Channel 是否激活( active ) 这里是判断NioServerSocketChannel是否绑定 **/
            boolean wasActive = isActive();

            try {
                /** 调用AbstractChannel.doBind 实现绑定，这里是模板方法，交给子类实现，对于Nio来说实现为NioServerSocketChannel **/
                doBind(localAddress);
            } catch (Throwable t) {
                safeSetFailure(promise, t);
                closeIfClosed();
                return;
            }

            /** 如果Channel之前没有被激活，eventLoop异步触发 fireChannelActive事件，
             *  同时会将将Channel感兴趣的事件给注册到EventLoop中，详细步骤如下
             *   1 pipeline.fireChannelActive最终会交给HeadContext.channelActive 处理。HeadContext.channelActive会判断channel.config().isAutoRead()是否为true
             *   2 如果channel.config().isAutoRead()为true 调用 channel.read();，内部会传递给pipeline.read();
             *   3 pipeline.read() 最终会交给HeadContext.read,其内部实现通过 unsafe.beginRead()，
             *   4 unsafe.beginRead() 调用doBeginRead 最终将Channel感兴趣的事件给注册到EventLoop中
             *   **/
            if (!wasActive && isActive()) {
                invokeLater(new Runnable() {
                    @Override
                    public void run() {
                        pipeline.fireChannelActive();
                    }
                });
            }
            /** promise 设置执行成功，回调通知 **/
            safeSetSuccess(promise);
        }

        /**
         * Channel断开连接，ChannelPromise负责在完成后通知
         */
        @Override
        public final void disconnect(final ChannelPromise promise) {
            assertEventLoop();

            /** 标记promise无法取消失败返回 **/
            if (!promise.setUncancellable()) {
                return;
            }

            /** 获得 Channel 是否激活 **/
            boolean wasActive = isActive();
            try {
                /** 调用AbstractChannel.doDisconnect 实现关闭连接，这里是模板方法，交给子类实现
                 *  对于Nio 的子类实现为NioSocketChannel，最终会关闭Java原生NIO的Channel对象
                 *  **/
                doDisconnect();
            } catch (Throwable t) {
                /** promise 设置执行失败 **/
                safeSetFailure(promise, t);
                /** 关闭Channel,如果未关闭 **/
                closeIfClosed();
                return;
            }

            /** 如果Channel之前没有被激活，eventLoop异步触发 fireChannelInactive 事件，  **/
            if (wasActive && !isActive()) {
                invokeLater(new Runnable() {
                    @Override
                    public void run() {
                        pipeline.fireChannelInactive();
                    }
                });
            }
            /** promise 设置执行成功，回调通知 **/
            safeSetSuccess(promise);

            /** 关闭Channel,如果未关闭*/
            closeIfClosed(); // doDisconnect() might have closed the channel
        }

        /**
         * 强制关闭{@link Channel}，不通知
         */
        @Override
        public final void closeForcibly() {
            assertEventLoop();
            try {
                doClose();
            } catch (Exception e) {
                logger.warn("Failed to close a channel.", e);
            }
        }
        /**
         * 关闭Channel，ChannelPromise负责在操作完成后通知。
         */
        @Override
        public final void close(final ChannelPromise promise) {
            assertEventLoop();
            /** 实例化ClosedChannelException **/
            ClosedChannelException closedChannelException = new ClosedChannelException();
            close(promise, closedChannelException, closedChannelException, false);
        }

        private void close(final ChannelPromise promise, final Throwable cause,
                           final ClosedChannelException closeCause, final boolean notify) {
            /** 标记promise无法取消失败返回 **/
            if (!promise.setUncancellable()) {
                return;
            }

            /** 判断Channel是否已经执行关闭 **/
            if (closeInitiated) {
                /** 从closeFuture中查看Channel是否被关闭，如果已经关闭进入if **/
                if (closeFuture.isDone()) {
                    /** promise 设置执行成功，回调通知 **/
                    safeSetSuccess(promise);
                }
                /** 如果未关闭，判断promise类型不是VoidChannelPromise **/
                else if (!(promise instanceof VoidChannelPromise)) {
                    /** closeFuture设置监听器，在Channel被关闭后触发执行 **/
                    closeFuture.addListener(new ChannelFutureListener() {
                        @Override
                        public void operationComplete(ChannelFuture future) throws Exception {
                            /** promise 设置执行成功，回调通知 **/
                            promise.setSuccess();
                        }
                    });
                }
                return;
            }

            /** 标记Channel已经开始执行关闭 **/
            closeInitiated = true;

            /** 获得 Channel 是否激活 **/
            final boolean wasActive = isActive();
            /** 设置缓冲队列为null **/
            final ChannelOutboundBuffer outboundBuffer = this.outboundBuffer;
            this.outboundBuffer = null;

            /**
             * 准备关闭{@link Channel}
             * 如果此方法返回一个Executor，则必须使用Executor，并提交任务，任务工作是调用abstractchannel.doclose（）关闭Channel
             * 如果此方法返回一个null，则调用方线程调用AbstractChannel.docLose（）关闭Channel
             */
            Executor closeExecutor = prepareToClose();
            if (closeExecutor != null) {
                closeExecutor.execute(new Runnable() {
                    @Override
                    public void run() {
                        try {
                            /** 实现关闭Channel**/
                            doClose0(promise);
                        } finally {
                            /** 使用eventLoop处理异步任务 **/
                            invokeLater(new Runnable() {
                                @Override
                                public void run() {
                                    if (outboundBuffer != null) {
                                        /** 写入数据( 消息 )到对端失败，通知相应数据对应的 Promise 失败 **/
                                        outboundBuffer.failFlushed(cause, notify);
                                        /** 关闭缓冲队列 **/
                                        outboundBuffer.close(closeCause);
                                    }
                                    fireChannelInactiveAndDeregister(wasActive);
                                }
                            });
                        }
                    }
                });
            } else {
                try {
                    /** 实现关闭Channel**/
                    doClose0(promise);
                } finally {
                    if (outboundBuffer != null) {
                        /** 写入数据( 消息 )到对端失败，通知相应数据对应的 Promise 失败 **/
                        outboundBuffer.failFlushed(cause, notify);
                        /** 关闭缓冲队列 **/
                        outboundBuffer.close(closeCause);
                    }
                }
                /** 正在 flush 中，在 EventLoop 中执行执行取消注册，并触发 Channel Inactive 事件到 pipeline 中 **/
                if (inFlush0) {
                    invokeLater(new Runnable() {
                        @Override
                        public void run() {
                            fireChannelInactiveAndDeregister(wasActive);
                        }
                    });
                } else {
                    fireChannelInactiveAndDeregister(wasActive);
                }
            }
        }

        /** 实现关闭Channel**/
        private void doClose0(ChannelPromise promise) {
            try {
                /** 调用AbstractChannel.doClose 实现关闭Channel，这里是模板方法，交给子类实现 **/
                doClose();
                /** closeFuture 设置Channel已经关闭，通知回调 **/
                closeFuture.setClosed();
                /**  promise 设置执行成功   **/
                safeSetSuccess(promise);
            } catch (Throwable t) {
                closeFuture.setClosed();
                safeSetFailure(promise, t);
            }
        }

        /**
         * 执行取消注册，并触发 Channel Inactive 事件到 pipeline 中
         */
        private void fireChannelInactiveAndDeregister(final boolean wasActive) {
            deregister(voidPromise(), wasActive && !isActive());
        }


        /**
         * 将Channel从指定EventLoop注销，ChannelPromise负责在完成后通知
         */
        @Override
        public final void deregister(final ChannelPromise promise) {
            assertEventLoop();

            deregister(promise, false);
        }

        private void deregister(final ChannelPromise promise, final boolean fireChannelInactive) {

            /** 标记promise无法取消失败返回 **/
            if (!promise.setUncancellable()) {
                return;
            }

            /**  不处于已经注册状态，直接通知 Promise 取消注册成功。**/
            if (!registered) {
                safeSetSuccess(promise);
                return;
            }

            /**  使用eventLoop异步执行doDeregister 将{@link Channel}从{@link EventLoop}中注销  **/
            invokeLater(new Runnable() {
                @Override
                public void run() {
                    try {
                        /** 将{@link Channel}从{@link EventLoop}中注销 **/
                        doDeregister();
                    } catch (Throwable t) {
                        logger.warn("Unexpected exception occurred while deregistering a channel.", t);
                    } finally {
                        /**   触发 Channel Inactive 事件传递到 pipeline 中 **/
                        if (fireChannelInactive) {
                            pipeline.fireChannelInactive();
                        }
                        /**  设置注册状态为false,同时触发 Channel Unregistered 事件传递到 pipeline 中  **/
                        if (registered) {
                            registered = false;
                            pipeline.fireChannelUnregistered();
                        }
                        /**  promise 设置执行成功，回调通知 **/
                        safeSetSuccess(promise);
                    }
                }
            });
        }

        /**
         * 将Channel感兴趣的事件给注册到EventLoop中
         */
        @Override
        public final void beginRead() {
            assertEventLoop();

            /** 如果Channel没有被激活直接返回 **/
            if (!isActive()) {
                return;
            }

            try {
                /** 将Channel感兴趣的事件给注册到EventLoop中 **/
                doBeginRead();
            } catch (final Exception e) {
                /** 使用eventLoop() 异步触发 异常事件 **/
                invokeLater(new Runnable() {
                    @Override
                    public void run() {
                        pipeline.fireExceptionCaught(e);
                    }
                });
                /** 关闭Channl **/
                close(voidPromise());
            }
        }

        @Override
        public final void write(Object msg, ChannelPromise promise) {
            assertEventLoop();

            /** 获取缓冲队列 **/
            ChannelOutboundBuffer outboundBuffer = this.outboundBuffer;
            /** 内存队列为空，一般是 Channel 已经关闭 做一定处理后返回**/
            if (outboundBuffer == null) {
                /** promise 设置执行失败，回调通知 **/
                safeSetFailure(promise, newClosedChannelException(initialCloseCause));
                /** 释放释放消息( 数据 )相关的资源 **/
                ReferenceCountUtil.release(msg);
                return;
            }

            int size;
            try {
                /** 过滤写入的消息( 数据 ) 这里是模板方法，对于NIO来说实现类为 AbstractNioByteChannel **/
                msg = filterOutboundMessage(msg);
                /** 计算消息的长度 **/
                size = pipeline.estimatorHandle().size(msg);
                if (size < 0) {
                    size = 0;
                }
            } catch (Throwable t) {
                /** promise 设置执行失败，回调通知 **/
                safeSetFailure(promise, t);
                /** 释放释放消息( 数据 )相关的资源 **/
                ReferenceCountUtil.release(msg);
                /** 返回 **/
                return;
            }
            /** 将消息写入缓冲队列 **/
            outboundBuffer.addMessage(msg, size, promise);
        }

        @Override
        public final void flush() {
            assertEventLoop();

            /** 获取缓冲队列 **/
            ChannelOutboundBuffer outboundBuffer = this.outboundBuffer;
            /** 内存队列为空，一般是 Channel 已经关闭，直接返回 **/
            if (outboundBuffer == null) {
                return;
            }
            /** 缓冲队列开始进行flush标记 **/
            outboundBuffer.addFlush();
            /** 执行 flush **/
            flush0();
        }

        @SuppressWarnings("deprecation")
        protected void flush0() {
            /** 正在 flush 中，所以直接返回。 **/
            if (inFlush0) {
                return;
            }
            /** 内存队列为空，一般是 Channel 已经关闭，直接返回 **/
            final ChannelOutboundBuffer outboundBuffer = this.outboundBuffer;
            if (outboundBuffer == null || outboundBuffer.isEmpty()) {
                return;
            }
            /** 标记正在 flush 中 **/
            inFlush0 = true;

            /** Channel 未激活 **/
            if (!isActive()) {
                try {
                    /**  Channel 开打状态 **/
                    if (isOpen()) {
                        /** 通知 flush 失败异常 **/
                        outboundBuffer.failFlushed(new NotYetConnectedException(), true);
                    }
                    /**  Channel 已关闭 **/
                    else {
                        /** 表示结束 flush 操作 **/
                        outboundBuffer.failFlushed(newClosedChannelException(initialCloseCause), false);
                    }
                } finally {
                    inFlush0 = false;
                }
                return;
            }

            try {
                /** 调用AbstractChannel.doWrite 实现数据写入，这里是模板方法，交给子类实现 NIO实现类为 NioSocketChannel **/
                doWrite(outboundBuffer);
            } catch (Throwable t) {
                if (t instanceof IOException && config().isAutoClose()) {
                    initialCloseCause = t;
                    /** 关闭Channel， **/
                    close(voidPromise(), t, newClosedChannelException(t), false);
                } else {
                    try {
                        shutdownOutput(voidPromise(), t);
                    } catch (Throwable t2) {
                        initialCloseCause = t;
                        /** 关闭Channel， **/
                        close(voidPromise(), t2, newClosedChannelException(t), false);
                    }
                }
            } finally {
                /** 设置标记inFlush0=false **/
                inFlush0 = false;
            }
        }


        @UnstableApi
        public final void shutdownOutput(final ChannelPromise promise) {
            assertEventLoop();
            shutdownOutput(promise, null);
        }


        private void shutdownOutput(final ChannelPromise promise, Throwable cause) {
            if (!promise.setUncancellable()) {
                return;
            }

            final ChannelOutboundBuffer outboundBuffer = this.outboundBuffer;
            if (outboundBuffer == null) {
                promise.setFailure(new ClosedChannelException());
                return;
            }
            this.outboundBuffer = null; // Disallow adding any messages and flushes to outboundBuffer.

            final Throwable shutdownCause = cause == null ?
                    new ChannelOutputShutdownException("Channel output shutdown") :
                    new ChannelOutputShutdownException("Channel output shutdown", cause);
            Executor closeExecutor = prepareToClose();
            if (closeExecutor != null) {
                closeExecutor.execute(new Runnable() {
                    @Override
                    public void run() {
                        try {
                            // Execute the shutdown.
                            doShutdownOutput();
                            promise.setSuccess();
                        } catch (Throwable err) {
                            promise.setFailure(err);
                        } finally {
                            // Dispatch to the EventLoop
                            eventLoop().execute(new Runnable() {
                                @Override
                                public void run() {
                                    closeOutboundBufferForShutdown(pipeline, outboundBuffer, shutdownCause);
                                }
                            });
                        }
                    }
                });
            } else {
                try {
                    // Execute the shutdown.
                    doShutdownOutput();
                    promise.setSuccess();
                } catch (Throwable err) {
                    promise.setFailure(err);
                } finally {
                    closeOutboundBufferForShutdown(pipeline, outboundBuffer, shutdownCause);
                }
            }
        }

        private void closeOutboundBufferForShutdown(
                ChannelPipeline pipeline, ChannelOutboundBuffer buffer, Throwable cause) {
            buffer.failFlushed(cause, false);
            buffer.close(cause, true);
            pipeline.fireUserEventTriggered(ChannelOutputShutdownEvent.INSTANCE);
        }


        private ClosedChannelException newClosedChannelException(Throwable cause) {
            ClosedChannelException exception = new ClosedChannelException();
            if (cause != null) {
                exception.initCause(cause);
            }
            return exception;
        }

        @Override
        public final ChannelPromise voidPromise() {
            assertEventLoop();

            return unsafeVoidPromise;
        }

        /**
         * 确保 Channel 是打开的
         */
        protected final boolean ensureOpen(ChannelPromise promise) {
            if (isOpen()) {
                return true;
            }
            // 若未打开，回调通知 promise 异常
            safeSetFailure(promise, newClosedChannelException(initialCloseCause));
            return false;
        }

        /**
         * promise 设置执行成功，回调通知，如果设置失败打印日志
         */
        protected final void safeSetSuccess(ChannelPromise promise) {
            if (!(promise instanceof VoidChannelPromise) && !promise.trySuccess()) {
                logger.warn("Failed to mark a promise as success because it is done already: {}", promise);
            }
        }

        /**
         * promise 设置执行失败，回调通知，如果设置失败打印日志
         */
        protected final void safeSetFailure(ChannelPromise promise, Throwable cause) {
            if (!(promise instanceof VoidChannelPromise) && !promise.tryFailure(cause)) {
                logger.warn("Failed to mark a promise as failure because it's done already: {}", promise, cause);
            }
        }

        /**
         * 关闭Channel,如果未关闭
         */
        protected final void closeIfClosed() {
            if (isOpen()) {
                return;
            }
            close(voidPromise());
        }

        /**
         * 使用eventLoop处理异步任务
         */
        private void invokeLater(Runnable task) {
            try {
                eventLoop().execute(task);
            } catch (RejectedExecutionException e) {
                logger.warn("Can't invoke task later as EventLoop rejected it", e);
            }
        }


        protected final Throwable annotateConnectException(Throwable cause, SocketAddress remoteAddress) {
            if (cause instanceof ConnectException) {
                return new AnnotatedConnectException((ConnectException) cause, remoteAddress);
            }
            if (cause instanceof NoRouteToHostException) {
                return new AnnotatedNoRouteToHostException((NoRouteToHostException) cause, remoteAddress);
            }
            if (cause instanceof SocketException) {
                return new AnnotatedSocketException((SocketException) cause, remoteAddress);
            }

            return cause;
        }

        /**
         * 准备关闭{@link Channel}
         * 如果此方法返回一个Executor，则必须使用Executor，并提交任务，任务工作是调用abstractchannel.doclose（）关闭Channel
         * 如果此方法返回一个null，则调用方线程调用AbstractChannel.docLose（）关闭Channel
         */
        protected Executor prepareToClose() {
            return null;
        }
    }




    protected Object filterOutboundMessage(Object msg) throws Exception {
        return msg;
    }

    protected void validateFileRegion(DefaultFileRegion region, long position) throws IOException {
        DefaultFileRegion.validate(region, position);
    }

    static final class CloseFuture extends DefaultChannelPromise {

        CloseFuture(AbstractChannel ch) {
            super(ch);
        }

        @Override
        public ChannelPromise setSuccess() {
            throw new IllegalStateException();
        }

        @Override
        public ChannelPromise setFailure(Throwable cause) {
            throw new IllegalStateException();
        }

        @Override
        public boolean trySuccess() {
            throw new IllegalStateException();
        }

        @Override
        public boolean tryFailure(Throwable cause) {
            throw new IllegalStateException();
        }

        boolean setClosed() {
            return super.trySuccess();
        }
    }

    private static final class AnnotatedConnectException extends ConnectException {

        private static final long serialVersionUID = 3901958112696433556L;

        AnnotatedConnectException(ConnectException exception, SocketAddress remoteAddress) {
            super(exception.getMessage() + ": " + remoteAddress);
            initCause(exception);
        }

        @Override
        public Throwable fillInStackTrace() {
            return this;
        }
    }

    private static final class AnnotatedNoRouteToHostException extends NoRouteToHostException {

        private static final long serialVersionUID = -6801433937592080623L;

        AnnotatedNoRouteToHostException(NoRouteToHostException exception, SocketAddress remoteAddress) {
            super(exception.getMessage() + ": " + remoteAddress);
            initCause(exception);
        }

        @Override
        public Throwable fillInStackTrace() {
            return this;
        }
    }

    private static final class AnnotatedSocketException extends SocketException {

        private static final long serialVersionUID = 3896743275010454039L;

        AnnotatedSocketException(SocketException exception, SocketAddress remoteAddress) {
            super(exception.getMessage() + ": " + remoteAddress);
            initCause(exception);
        }

        @Override
        public Throwable fillInStackTrace() {
            return this;
        }
    }
}
