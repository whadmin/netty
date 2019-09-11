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
package io.netty.bootstrap;

import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelPipeline;
import io.netty.channel.ChannelPromise;
import io.netty.channel.EventLoop;
import io.netty.channel.EventLoopGroup;
import io.netty.resolver.AddressResolver;
import io.netty.resolver.DefaultAddressResolverGroup;
import io.netty.resolver.NameResolver;
import io.netty.resolver.AddressResolverGroup;
import io.netty.util.concurrent.Future;
import io.netty.util.concurrent.FutureListener;
import io.netty.util.internal.ObjectUtil;
import io.netty.util.internal.logging.InternalLogger;
import io.netty.util.internal.logging.InternalLoggerFactory;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.util.Map;
import java.util.Map.Entry;

/**
 * A {@link Bootstrap} that makes it easy to bootstrap a {@link Channel} to use
 * for clients.
 *
 * <p>The {@link #bind()} methods are useful in combination with connectionless transports such as datagram (UDP).
 * For regular TCP connections, please use the provided {@link #connect()} methods.</p>
 */
public class Bootstrap extends AbstractBootstrap<Bootstrap, Channel> {

    private static final InternalLogger logger = InternalLoggerFactory.getInstance(Bootstrap.class);

    /**
     * 默认地址解析器对象
     */
    private static final AddressResolverGroup<?> DEFAULT_RESOLVER = DefaultAddressResolverGroup.INSTANCE;

    /**
     * 启动类配置对象
     */
    private final BootstrapConfig config = new BootstrapConfig(this);

    /**
     * 地址解析器对象
     */
    @SuppressWarnings("unchecked")
    private volatile AddressResolverGroup<SocketAddress> resolver =
            (AddressResolverGroup<SocketAddress>) DEFAULT_RESOLVER;

    /**
     * 连接地址
     */
    private volatile SocketAddress remoteAddress;

    public Bootstrap() { }

    private Bootstrap(Bootstrap bootstrap) {
        super(bootstrap);
        resolver = bootstrap.resolver;
        remoteAddress = bootstrap.remoteAddress;
    }

    /**
     * 设置地址解析器
     */
    @SuppressWarnings("unchecked")
    public Bootstrap resolver(AddressResolverGroup<?> resolver) {
        this.resolver = (AddressResolverGroup<SocketAddress>) (resolver == null ? DEFAULT_RESOLVER : resolver);
        return this;
    }

    /**
     * 设置连接地址
     */
    public Bootstrap remoteAddress(SocketAddress remoteAddress) {
        this.remoteAddress = remoteAddress;
        return this;
    }

    /**
     * 设置连接地址
     */
    public Bootstrap remoteAddress(String inetHost, int inetPort) {
        remoteAddress = InetSocketAddress.createUnresolved(inetHost, inetPort);
        return this;
    }

    /**
     * 设置连接地址
     */
    public Bootstrap remoteAddress(InetAddress inetHost, int inetPort) {
        remoteAddress = new InetSocketAddress(inetHost, inetPort);
        return this;
    }

    /**
     * Connect a {@link Channel} to the remote peer.
     */
    public ChannelFuture connect() {
        validate();
        SocketAddress remoteAddress = this.remoteAddress;
        if (remoteAddress == null) {
            throw new IllegalStateException("remoteAddress not set");
        }

        return doResolveAndConnect(remoteAddress, config.localAddress());
    }

    /**
     * Connect a {@link Channel} to the remote peer.
     */
    public ChannelFuture connect(String inetHost, int inetPort) {
        return connect(InetSocketAddress.createUnresolved(inetHost, inetPort));
    }

    /**
     * Connect a {@link Channel} to the remote peer.
     */
    public ChannelFuture connect(InetAddress inetHost, int inetPort) {
        return connect(new InetSocketAddress(inetHost, inetPort));
    }

    /**
     * Connect a {@link Channel} to the remote peer.
     */
    public ChannelFuture connect(SocketAddress remoteAddress) {
        ObjectUtil.checkNotNull(remoteAddress, "remoteAddress");
        validate();
        return doResolveAndConnect(remoteAddress, config.localAddress());
    }

    /**
     * Connect a {@link Channel} to the remote peer.
     */
    public ChannelFuture connect(SocketAddress remoteAddress, SocketAddress localAddress) {
        ObjectUtil.checkNotNull(remoteAddress, "remoteAddress");
        validate();
        return doResolveAndConnect(remoteAddress, localAddress);
    }

    /**
     * @see #connect()
     */
    private ChannelFuture doResolveAndConnect(final SocketAddress remoteAddress, final SocketAddress localAddress) {
        /**
         * initAndRegister
         * 1 使用channelFactory工厂实例化Channel
         * 2 初始化Channel
         * 3 从选择事件循环组EventLoopGroup选择一个事件循环EventLoop,将Channel注册到EventLoop(内部存在Se)
         * 因为注册是异步的过程，所以返回一个 ChannelFuture对象。ChannelFuture表示Channel异步{@link Channel} I/O操作的结果**/
        final ChannelFuture regFuture = initAndRegister();

        /** 从ChannelFuture获取绑定Channel **/
        final Channel channel = regFuture.channel();

        /** 将Channel注册到EventLoop异步操作完成，完成可能表示成功，失败，被取消**/
        if (regFuture.isDone()) {
            /** 如果没有成功直接返回 **/
            if (!regFuture.isSuccess()) {
                return regFuture;
            }
            /** 解析远程地址，并进行连接 **/
            return doResolveAndConnect0(channel, remoteAddress, localAddress, channel.newPromise());
        }
        /** 将hannel注册到EventLoop异步操作未完成**/
        else {
            /**
             * 创建一个PendingRegistrationPromise，PendingRegistrationPromise是对ChannelPromise简单扩展
             * 存在一个是否注册属性
             * 用来表示注册操作异步操作结果 **/
            final PendingRegistrationPromise promise = new PendingRegistrationPromise(channel);

            /** 给异步操作（将hannel注册到EventLoop操作）设置监听器，在注册完成做回调处理 **/
            regFuture.addListener(new ChannelFutureListener() {
                @Override
                public void operationComplete(ChannelFuture future) throws Exception {
                    /** 如果异步操作（将hannel注册到EventLoop操作）发生异常，设置promise（绑定异步操作失败）**/
                    Throwable cause = future.cause();
                    if (cause != null) {
                        promise.setFailure(cause);
                    } else {
                        /** 设置Channel已经绑定 **/
                        promise.registered();
                        /** 解析远程地址，并进行连接 **/
                        doResolveAndConnect0(channel, remoteAddress, localAddress, promise);
                    }
                }
            });
            return promise;
        }
    }

    /**
     * 解析远程地址，并进行连接
     */
    private ChannelFuture doResolveAndConnect0(final Channel channel, SocketAddress remoteAddress,
                                               final SocketAddress localAddress, final ChannelPromise promise) {
        try {
            /** 使用 resolver 解析远程地址。因为解析是异步的过程，所以返回一个 Future 对象 **/
            final EventLoop eventLoop = channel.eventLoop();
            final AddressResolver<SocketAddress> resolver = this.resolver.getResolver(eventLoop);

            if (!resolver.isSupported(remoteAddress) || resolver.isResolved(remoteAddress)) {
                doConnect(remoteAddress, localAddress, promise);
                return promise;
            }
            // 解析远程地址
            final Future<SocketAddress> resolveFuture = resolver.resolve(remoteAddress);

            /** 解析远程地址异步操作完成 **/
            if (resolveFuture.isDone()) {
                final Throwable resolveFailureCause = resolveFuture.cause();
                /** 如果异步操作（解析远程地址）发生异常**/
                if (resolveFailureCause != null) {
                    /** 关闭channel **/
                    channel.close();
                    /** 设置promise操作失败**/
                    promise.setFailure(resolveFailureCause);
                } else {
                    doConnect(resolveFuture.getNow(), localAddress, promise);
                }
                return promise;
            }
            /** 解析远程地址异步操作未完成,给异步操作（解析远程地址）设置监听器，在注册完成做回调处理 **/
            resolveFuture.addListener(new FutureListener<SocketAddress>() {
                @Override
                public void operationComplete(Future<SocketAddress> future) throws Exception {
                    if (future.cause() != null) {
                        channel.close();
                        promise.setFailure(future.cause());
                    } else {
                        doConnect(future.getNow(), localAddress, promise);
                    }
                }
            });
        } catch (Throwable cause) {
            /** 发送异常设置失败 **/
            promise.tryFailure(cause);
        }
        return promise;
    }

    /**
     * 连接远程地址
     */
    private static void doConnect(
            final SocketAddress remoteAddress, final SocketAddress localAddress, final ChannelPromise connectPromise) {
        //获取连接Channel
        final Channel channel = connectPromise.channel();
        //获取事件处理器异步执行远程连接
        channel.eventLoop().execute(new Runnable() {
            @Override
            public void run() {
                if (localAddress == null) {
                    channel.connect(remoteAddress, connectPromise);
                } else {
                    channel.connect(remoteAddress, localAddress, connectPromise);
                }
                //设置监听器，在远程连接操作完成后触发
                connectPromise.addListener(ChannelFutureListener.CLOSE_ON_FAILURE);
            }
        });
    }

    @Override
    @SuppressWarnings("unchecked")
    void init(Channel channel) {
        ChannelPipeline p = channel.pipeline();
        p.addLast(config.handler());

        setChannelOptions(channel, options0().entrySet().toArray(newOptionArray(0)), logger);
        setAttributes(channel, attrs0().entrySet().toArray(newAttrArray(0)));
    }

    /**
     * 校验配置是否正确
     */
    @Override
    public Bootstrap validate() {
        super.validate();
        if (config.handler() == null) {
            throw new IllegalStateException("handler not set");
        }
        return this;
    }

    /**
     * 克隆 Bootstrap 对象
     */
    @Override
    @SuppressWarnings("CloneDoesntCallSuperClone")
    public Bootstrap clone() {
        return new Bootstrap(this);
    }

    /**
     * 克隆 Bootstrap 对象，同时设置EventLoopGroup
     */
    public Bootstrap clone(EventLoopGroup group) {
        Bootstrap bs = new Bootstrap(this);
        bs.group = group;
        return bs;
    }

    /**
     * 返回启动类配置
     */
    @Override
    public final BootstrapConfig config() {
        return config;
    }

    /**
     * 返回连接地址
     */
    final SocketAddress remoteAddress() {
        return remoteAddress;
    }

    /**
     * 返回地址解析器
     */
    final AddressResolverGroup<?> resolver() {
        return resolver;
    }
}
