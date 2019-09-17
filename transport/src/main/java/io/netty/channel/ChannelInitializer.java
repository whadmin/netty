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
import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.ChannelHandler.Sharable;
import io.netty.util.internal.logging.InternalLogger;
import io.netty.util.internal.logging.InternalLoggerFactory;

import java.util.Collections;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 一个特殊的{@link ChannelInboundHandler}，它提供了initChannel模板方法，在Channel注册到{@link EventLoop}后触发。
 *
 * 通常设置到ServerBootstrap {@link Bootstrap#handler(ChannelHandler)}，{@link ServerBootstrap#childHandler(ChannelHandler)}
 * 或者 Bootstrap {@link Bootstrap#handler(ChannelHandler)}
 *
 * <pre>
 * public class MyChannelInitializer extends {@link ChannelInitializer} {
 *     public void initChannel({@link Channel} channel) {
 *         channel.pipeline().addLast("myHandler", new MyHandler());
 *     }
 * }
 * {@link ServerBootstrap} bootstrap = ...;
 * ...
 * bootstrap.childHandler(new MyChannelInitializer());
 * ...
 * </pre>
 * 此类标记为{@link Sharable} 表示ChannelInitializer可以作为ChannelHandler重复注册到Pipline中
 */
@Sharable
public abstract class ChannelInitializer<C extends Channel> extends ChannelInboundHandlerAdapter {

    private static final InternalLogger logger = InternalLoggerFactory.getInstance(ChannelInitializer.class);


    /**
     * 由于 ChannelInitializer 可以在 Bootstrap/ServerBootstrap 的所有Pipline中共享，
     * 所以我们用一个ConcurrentMap作为存储共享ChannelInitializer容器
     */
    private final Set<ChannelHandlerContext> initMap = Collections.newSetFromMap(
            new ConcurrentHashMap<ChannelHandlerContext, Boolean>());

    /**
     * 模板方法
     */
    protected abstract void initChannel(C ch) throws Exception;


    /**
     * 初始化，ChannelInitializer添加到Pipline，且Channel注册到{@link EventLoop}后触发
     */
    @SuppressWarnings("unchecked")
    private boolean initChannel(ChannelHandlerContext ctx) throws Exception {
        /** 将ChannelInitializer对应ChannelHandlerContext添加到initMap  解决并发问题**/
        if (initMap.add(ctx)) {
            try {
                /** 调用模板 **/
                initChannel((C) ctx.channel());
            } catch (Throwable cause) {
                /** 发生异常时，执行异常处理 **/
                exceptionCaught(ctx, cause);
            } finally {
                /** ChannelInitializer 初始化完毕 从 pipeline 移除 **/
                ChannelPipeline pipeline = ctx.pipeline();
                if (pipeline.context(this) != null) {
                    pipeline.remove(this);
                }
            }
            /** 初始化成功 **/
            return true;
        }
        /**  初始化失败 **/
        return false;
    }

    @Override
    @SuppressWarnings("unchecked")
    public final void channelRegistered(ChannelHandlerContext ctx) throws Exception {
        // Normally this method will never be called as handlerAdded(...) should call initChannel(...) and remove
        // the handler.
        if (initChannel(ctx)) {
            // we called initChannel(...) so we need to call now pipeline.fireChannelRegistered() to ensure we not
            // miss an event.
            ctx.pipeline().fireChannelRegistered();

            // We are done with init the Channel, removing all the state for the Channel now.
            removeState(ctx);
        } else {
            // Called initChannel(...) before which is the expected behavior, so just forward the event.
            ctx.fireChannelRegistered();
        }
    }

    /**
     * 从通过记录和关闭{@link Channel}来处理{@link Throwable}。, 子类可以覆盖它。
     */
    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) throws Exception {
        if (logger.isWarnEnabled()) {
            logger.warn("Failed to initialize a channel. Closing: " + ctx.channel(), cause);
        }
        ctx.close();
    }

    /**
     * {@inheritDoc} If override this method ensure you call super!
     */
    @Override
    public void handlerAdded(ChannelHandlerContext ctx) throws Exception {
        if (ctx.channel().isRegistered()) {
            if (initChannel(ctx)) {
                removeState(ctx);
            }
        }
    }

    @Override
    public void handlerRemoved(ChannelHandlerContext ctx) throws Exception {
        initMap.remove(ctx);
    }



    private void removeState(final ChannelHandlerContext ctx) {
        // The removal may happen in an async fashion if the EventExecutor we use does something funky.
        if (ctx.isRemoved()) {
            initMap.remove(ctx);
        } else {
            // The context is not removed yet which is most likely the case because a custom EventExecutor is used.
            // Let's schedule it on the EventExecutor to give it some more time to be completed in case it is offloaded.
            ctx.executor().execute(new Runnable() {
                @Override
                public void run() {
                    initMap.remove(ctx);
                }
            });
        }
    }
}
