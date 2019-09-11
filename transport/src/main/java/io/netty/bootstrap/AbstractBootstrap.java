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
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelOption;
import io.netty.channel.ChannelPromise;
import io.netty.channel.DefaultChannelPromise;
import io.netty.channel.EventLoop;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.ReflectiveChannelFactory;
import io.netty.util.AttributeKey;
import io.netty.util.concurrent.EventExecutor;
import io.netty.util.concurrent.GlobalEventExecutor;
import io.netty.util.internal.ObjectUtil;
import io.netty.util.internal.SocketUtils;
import io.netty.util.internal.StringUtil;
import io.netty.util.internal.logging.InternalLogger;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * {@link AbstractBootstrap}是一个帮助类，可以很容易地引导{@link Channel}.
 * 它支持方法链，以提供一种简单的方法来配置{@link AbstractBootstrap}。
 */
public abstract class AbstractBootstrap<B extends AbstractBootstrap<B, C>, C extends Channel> implements Cloneable {

    /**
     * 事件循环组,用于处理客户端的 SocketChannel 的数据读写事件
     */
    volatile EventLoopGroup group;

    /**
     * Channel 工厂，用于创建 Channel 对象。
     */
    private volatile ChannelFactory<? extends C> channelFactory;

    /**
     * 本地地址
     */
    private volatile SocketAddress localAddress;

    /**
     * Channel选项集合
     */
    private final Map<ChannelOption<?>, Object> options = new ConcurrentHashMap<ChannelOption<?>, Object>();

    /**
     * 属性集合
     */
    private final Map<AttributeKey<?>, Object> attrs = new ConcurrentHashMap<AttributeKey<?>, Object>();

    /**
     * 事件处理器
     * 对于ServerBootstrap来说处理网络连接请求
     * 对于Bootstrap来说处理网络读写请求
     */
    private volatile ChannelHandler handler;

    /**
     * 实例化AbstractBootstrap
     */
    AbstractBootstrap() {
    }


    /**
     * 实例化AbstractBootstrap
     */
    AbstractBootstrap(AbstractBootstrap<B, C> bootstrap) {
        group = bootstrap.group;
        channelFactory = bootstrap.channelFactory;
        handler = bootstrap.handler;
        localAddress = bootstrap.localAddress;
        options.putAll(bootstrap.options);
        attrs.putAll(bootstrap.attrs);
    }

    /**
     * 方法链函数，返回自身
     */
    @SuppressWarnings("unchecked")
    private B self() {
        return (B) this;
    }


    // ========== 设置 EventLoopGroup ==========
    /**
     * 设置 EventLoopGroup
     */
    public B group(EventLoopGroup group) {
        ObjectUtil.checkNotNull(group, "group");
        if (this.group != null) {
            throw new IllegalStateException("group set already");
        }
        this.group = group;
        return self();
    }


    // ========== 设置要被实例化的 Channel 的类,并创建一个实例化Channel类的工厂 ==========
    /**
     * 设置要被实例化的 Channel 的类,并创建一个实例化Channel类的工厂ReflectiveChannelFactory
     */
    public B channel(Class<? extends C> channelClass) {
        return channelFactory(new ReflectiveChannelFactory<C>(
                ObjectUtil.checkNotNull(channelClass, "channelClass")
        ));
    }

    /**
     * 设置指定创建指定channel类的工厂
     */
    @SuppressWarnings({"unchecked", "deprecation"})
    public B channelFactory(io.netty.channel.ChannelFactory<? extends C> channelFactory) {
        return channelFactory((ChannelFactory<C>) channelFactory);
    }


    @Deprecated
    public B channelFactory(ChannelFactory<? extends C> channelFactory) {
        ObjectUtil.checkNotNull(channelFactory, "channelFactory");
        if (this.channelFactory != null) {
            throw new IllegalStateException("channelFactory set already");
        }
        this.channelFactory = channelFactory;
        return self();
    }

    // ========== 设置创建 Channel 的本地地址 ==========
    /**
     * 设置创建 Channel 的本地地址
     */
    public B localAddress(int inetPort) {
        return localAddress(new InetSocketAddress(inetPort));
    }

    /**
     * 设置创建 Channel 的本地地址
     */
    public B localAddress(String inetHost, int inetPort) {
        return localAddress(SocketUtils.socketAddress(inetHost, inetPort));
    }

    /**
     * 设置创建 Channel 的本地地址
     */
    public B localAddress(InetAddress inetHost, int inetPort) {
        return localAddress(new InetSocketAddress(inetHost, inetPort));
    }

    /**
     * 设置创建 Channel 的本地地址
     */
    public B localAddress(SocketAddress localAddress) {
        this.localAddress = localAddress;
        return self();
    }

    final SocketAddress localAddress() {
        return localAddress;
    }

    // ========== 设置创建 Channel 的可选项 ==========
    /**
     * 设置创建 Channel 的可选项{@link ChannelOption}
     */
    public <T> B option(ChannelOption<T> option, T value) {
        ObjectUtil.checkNotNull(option, "option");
        if (value == null) {
            options.remove(option);
        } else {
            options.put(option, value);
        }
        return self();
    }

    final Map<ChannelOption<?>, Object> options() {
        return copiedMap(options);
    }

    static <K, V> Map<K, V> copiedMap(Map<K, V> map) {
        if (map.isEmpty()) {
            return Collections.emptyMap();
        }
        return Collections.unmodifiableMap(new HashMap<K, V>(map));
    }

    final Map<ChannelOption<?>, Object> options0() {
        return options;
    }

    // ========== 设置创建 Channel 的属性 ==========
    /**
     * 设置创建 Channel 的属性
     */
    public <T> B attr(AttributeKey<T> key, T value) {
        ObjectUtil.checkNotNull(key, "key");
        if (value == null) {
            attrs.remove(key);
        } else {
            attrs.put(key, value);
        }
        return self();
    }




    // ========== 创建 Channel 并绑定指定端口 ==========
    public ChannelFuture bind() {
        //验证所有参数
        validate();
        SocketAddress localAddress = this.localAddress;
        if (localAddress == null) {
            throw new IllegalStateException("localAddress not set");
        }
        return doBind(localAddress);
    }

    public ChannelFuture bind(int inetPort) {
        //通过端口号创建一个 InetSocketAddress，然后继续bind
        return bind(new InetSocketAddress(inetPort));
    }


    public ChannelFuture bind(String inetHost, int inetPort) {
        return bind(SocketUtils.socketAddress(inetHost, inetPort));
    }


    public ChannelFuture bind(InetAddress inetHost, int inetPort) {
        return bind(new InetSocketAddress(inetHost, inetPort));
    }


    public ChannelFuture bind(SocketAddress localAddress) {
        // 验证所有参数  /
        validate();
        // 绑定到指定localAddress
        return doBind(ObjectUtil.checkNotNull(localAddress, "localAddress"));
    }


    /**
     * 创建一个新的{@link Channel}并绑定指定SocketAddress
     */
    private ChannelFuture doBind(final SocketAddress localAddress) {
        /**
         * initAndRegister
         * 1 使用channelFactory工厂实例化Channel
         * 2 初始化Channel
         * 3 从选择事件循环组EventLoopGroup选择一个事件循环EventLoop,将Channel注册到EventLoop(内部存在Se)
         *
         * 因为注册是异步的过程，所以返回一个 ChannelFuture对象。ChannelFuture表示Channel异步{@link Channel} I/O操作的结果**/
        final ChannelFuture regFuture = initAndRegister();

        /** 从ChannelFuture获取绑定Channel **/
        final Channel channel = regFuture.channel();

        /** 如果将hannel注册到EventLoop操作发生异常，直接返回 **/
        if (regFuture.cause() != null) {
            return regFuture;
        }

        /** 判断Channel注册到EventLoop异步操作是否完成，这里完成可能表示成功，失败，或者被取消**/
        if (regFuture.isDone()) {
            /**
             * 创建一个ChannelPromise，ChannelPromise可以理解未特殊ChannelFuture
             * ChannelFuture 并不参与异步操作，只提供了等待通知
             * ChannelPromise 通常参与异步操作，通过ChannelPromise监听异步操作中流程，并设置异步操作最终状态
             * **/
            ChannelPromise promise = channel.newPromise();

            /** 使用事件循环EventLoop异步处理服务端绑定，promise负责监听绑定操作全过程并操作异步处理最终状态 **/
            doBind0(regFuture, channel, localAddress, promise);
            return promise;
        }
        /** hannel注册到EventLoop异步操作未完成**/
        else {
            /**
             * 创建一个PendingRegistrationPromise，PendingRegistrationPromise是对ChannelPromise简单扩展
             * 存在一个是否注册属性
             * 用来表示注册操作异步操作结果 **/
            final PendingRegistrationPromise promise = new PendingRegistrationPromise(channel);

            /** 给regFuture（将hannel注册到EventLoop操作）设置监听器，在hannel注册到EventLoop完成时触发 **/
            regFuture.addListener(new ChannelFutureListener() {
                @Override
                public void operationComplete(ChannelFuture future) throws Exception {
                    /** 如果hannel注册到EventLoop操作发生异常，使用promise设置异步操作最终状态发生异常**/
                    Throwable cause = future.cause();
                    if (cause != null) {
                        promise.setFailure(cause);
                    } else {
                        /** 在promise中设置Channel已经绑定 **/
                        promise.registered();
                        /** 使用事件循环处理器EventLoop异步处理服务端绑定doBind0，promise负责监听绑定操作全过程记录结果并操作异步处理最终状态 **/
                        doBind0(regFuture, channel, localAddress, promise);
                    }
                }
            });
            return promise;
        }
    }

    final ChannelFuture initAndRegister() {
        Channel channel = null;
        try {
            /** 使用channelFactory 创建 channelClass指定 channel 对象 **/
            channel = channelFactory.newChannel();
            /** 初始化 Channel   **/
            init(channel);
        } catch (Throwable t) {
            /** 如果发生异常 **/
            /** 判断Channel对象是否已经创建 **/
            if (channel != null) {
                /**  强制关闭 Channel  **/
                channel.unsafe().closeForcibly();
                /** 返回DefaultChannelPromise **/
                return new DefaultChannelPromise(channel, GlobalEventExecutor.INSTANCE).setFailure(t);
            }
            /** 返回带异常的 DefaultChannelPromise 对象。
            因为创建 Channel 对象失败，所以需要创建一个 FailedChannel 对象，设置到 DefaultChannelPromise 中才可以返回 **/
            return new DefaultChannelPromise(new FailedChannel(), GlobalEventExecutor.INSTANCE).setFailure(t);
        }

        /** 首先获得 EventLoopGroup 对象，后调用 EventLoopGroup#register(Channel) 方法，注册 Channel 到 EventLoopGroup 中。
            实际在方法内部，EventLoopGroup 会分配一个 EventLoop 对象，将 Channel 注册到其上  **/
        ChannelFuture regFuture = config().group().register(channel);
        if (regFuture.cause() != null) {
            /** 若发生异常，并且 Channel 已经注册成功，则调用 #close() 方法，正常关闭 Channel  **/
            if (channel.isRegistered()) {
                channel.close();
            }
            /** 若发生异常，并且 Channel 并未注册成功，则调用 #closeForcibly() 方法，强制关闭 Channel  **/
            else {
                channel.unsafe().closeForcibly();
            }
        }
        return regFuture;
    }

    abstract void init(Channel channel) throws Exception;

    private static void doBind0(
            final ChannelFuture regFuture, final Channel channel,
            final SocketAddress localAddress, final ChannelPromise promise) {

        /** 使用事件循环EventLoop异步处理服务端绑定 **/
        channel.eventLoop().execute(new Runnable() {
            @Override
            public void run() {
                /**
                 * 将hannel注册到EventLoop异步操作成功，调用channel.bind绑定
                 *
                 * channel.bind
                 * channel.bingd绑定操作会从pipeline尾部TailContext开始处理，一直遍历到HeadContext,最后交给unsafe
                 *
                 * 同时注册ChannelFutureListener.CLOSE_ON_FAILURE监听器，在绑定处理完成后判断是否成功，如果失败关闭Channel
                 * **/
                if (regFuture.isSuccess()) {
                    channel.bind(localAddress, promise).addListener(ChannelFutureListener.CLOSE_ON_FAILURE);
                }
                /** 将hannel注册到EventLoop异步操作失败，promise负责写入绑定操作失败原因是由于注册失败 **/
                else {
                    promise.setFailure(regFuture.cause());
                }
            }
        });
    }

    /**
     * 设置事件处理器,需要注意的是
     * 对于ServerBootstrap来说处理网络连接请求
     * 对于Bootstrap来说处理网络读写请求
     */
    public B handler(ChannelHandler handler) {
        this.handler = ObjectUtil.checkNotNull(handler, "handler");
        return self();
    }


    @Deprecated
    public final EventLoopGroup group() {
        return group;
    }





    final Map<AttributeKey<?>, Object> attrs0() {
        return attrs;
    }



    @SuppressWarnings("deprecation")
    final ChannelFactory<? extends C> channelFactory() {
        return channelFactory;
    }

    final ChannelHandler handler() {
        return handler;
    }


    final Map<AttributeKey<?>, Object> attrs() {
        return copiedMap(attrs);
    }

    static void setAttributes(Channel channel, Map.Entry<AttributeKey<?>, Object>[] attrs) {
        for (Map.Entry<AttributeKey<?>, Object> e : attrs) {
            @SuppressWarnings("unchecked")
            AttributeKey<Object> key = (AttributeKey<Object>) e.getKey();
            channel.attr(key).set(e.getValue());
        }
    }

    static void setChannelOptions(
            Channel channel, Map.Entry<ChannelOption<?>, Object>[] options, InternalLogger logger) {
        for (Map.Entry<ChannelOption<?>, Object> e : options) {
            setChannelOption(channel, e.getKey(), e.getValue(), logger);
        }
    }

    @SuppressWarnings("unchecked")
    static Map.Entry<AttributeKey<?>, Object>[] newAttrArray(int size) {
        return new Map.Entry[size];
    }

    @SuppressWarnings("unchecked")
    static Map.Entry<ChannelOption<?>, Object>[] newOptionArray(int size) {
        return new Map.Entry[size];
    }

    @SuppressWarnings("unchecked")
    private static void setChannelOption(
            Channel channel, ChannelOption<?> option, Object value, InternalLogger logger) {
        try {
            if (!channel.config().setOption((ChannelOption<Object>) option, value)) {
                logger.warn("Unknown channel option '{}' for channel '{}'", option, channel);
            }
        } catch (Throwable t) {
            logger.warn(
                    "Failed to set channel option '{}' with value '{}' for channel '{}'", option, value, channel, t);
        }
    }

    @Override
    public String toString() {
        StringBuilder buf = new StringBuilder()
                .append(StringUtil.simpleClassName(this))
                .append('(').append(config()).append(')');
        return buf.toString();
    }

    static final class PendingRegistrationPromise extends DefaultChannelPromise {

        private volatile boolean registered;

        PendingRegistrationPromise(Channel channel) {
            super(channel);
        }

        void registered() {
            registered = true;
        }

        @Override
        protected EventExecutor executor() {
            if (registered) {
                return super.executor();
            }
            return GlobalEventExecutor.INSTANCE;
        }
    }

    /**
     * 返回当前 AbstractBootstrap 的配置对象
     */
    public abstract AbstractBootstrapConfig<B, C> config();

    /**
     * 验证所有参数
     */
    public B validate() {
        if (group == null) {
            throw new IllegalStateException("group not set");
        }
        if (channelFactory == null) {
            throw new IllegalStateException("channel or channelFactory not set");
        }
        return self();
    }

    /**
     * 克隆一个 AbstractBootstrap 对象
     */
    @Override
    @SuppressWarnings("CloneDoesntDeclareCloneNotSupportedException")
    public abstract B clone();


    /**
     * 1 使用channelFactory工厂实例化Channel
     * 2 初始化Channel
     * 3 将Channel注册到EventLoop
     * 因为注册是异步的过程，所以返回一个 ChannelFuture对象。ChannelFuture表示Channel异步{@link Channel} I/O操作的结果**/
    public ChannelFuture register() {
        validate();
        return initAndRegister();
    }
}
