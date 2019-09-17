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
package io.netty.handler.timeout;

import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.Channel;
import io.netty.channel.Channel.Unsafe;
import io.netty.channel.ChannelDuplexHandler;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelOutboundBuffer;
import io.netty.channel.ChannelPromise;

import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * 当 Channel 的读或者写空闲时间太长时，将会触发一个 IdleStateEvent 事件
 */
public class IdleStateHandler extends ChannelDuplexHandler {

    /**
     * 最小的超时时间，单位：纳秒
     */
    private static final long MIN_TIMEOUT_NANOS = TimeUnit.MILLISECONDS.toNanos(1);

    //==================Read 空闲相关属性==================
    /**
     * 配置的读空闲时间，单位：纳秒
     */
    private final long readerIdleTimeNanos;

    /**
     * 读空闲的定时检测任务
     */
    private ScheduledFuture<?> readerIdleTimeout;

    /**
     * 最后读时间
     */
    private long lastReadTime;

    /**
     * 是否首次读空闲
     */
    private boolean firstReaderIdleEvent = true;

    /**
     * 是否正在读取
     */
    private boolean reading;

    //==================Write 空闲相关属性==================

    /**
     * 配置的写空闲时间，单位：纳秒
     */
    private final long writerIdleTimeNanos;

    /**
     * 写空闲的定时检测任务
     */
    private ScheduledFuture<?> writerIdleTimeout;

    /**
     * 最后写时间
     */
    private long lastWriteTime;

    /**
     * 写入任务监听器
     */
    private final ChannelFutureListener writeListener = new ChannelFutureListener() {
        @Override
        public void operationComplete(ChannelFuture future) throws Exception {
            lastWriteTime = ticksInNanos();
            firstWriterIdleEvent = firstAllIdleEvent = true;
        }
    };

    /**
     * 是否首次写空闲
     */
    private boolean firstWriterIdleEvent = true;

    //==================ChannelOutboundBuffer 相关属性==================

    /**
     * 是否考虑出站时较慢的情况。默认值是false（不考虑）
     */
    private final boolean observeOutput;

    /**
     * 第一条准备 flash 到对端的消息( {@link ChannelOutboundBuffer#current()} )的 HashCode
     */
    private int lastMessageHashCode;

    /**
     * 最后检测到 {@link ChannelOutboundBuffer} 发生变化的时间
     */
    private long lastChangeCheckTimeStamp;

    /**
     * 总共等待 flush 到对端的内存大小( {@link ChannelOutboundBuffer#totalPendingWriteBytes()} )
     */
    private long lastPendingWriteBytes;

    //==================ALL 空闲相关属性==================

    /**
     * All 空闲时间定时检测任务，单位：纳秒
     */
    private ScheduledFuture<?> allIdleTimeout;

    /**
     * 配置的All( 读或写任一空闲时间 )，单位：纳秒
     */
    private final long allIdleTimeNanos;

    /**
     * 是否首次 All 空闲
     */
    private boolean firstAllIdleEvent = true;

    /**
     * 状态
     * 0 - none ，未初始化
     * 1 - initialized ，已经初始化
     * 2 - destroyed ，已经销毁
     */
    private byte state;



    private long lastFlushProgress;

    /**
     * 实例化一个新的{@link IdleStateEvent}实例。
     */
    public IdleStateHandler(
            int readerIdleTimeSeconds,
            int writerIdleTimeSeconds,
            int allIdleTimeSeconds) {

        this(readerIdleTimeSeconds, writerIdleTimeSeconds, allIdleTimeSeconds,
             TimeUnit.SECONDS);
    }

    /**
     * 实例化一个新的{@link IdleStateEvent}实例。
     */
    public IdleStateHandler(
            long readerIdleTime, long writerIdleTime, long allIdleTime,
            TimeUnit unit) {
        this(false, readerIdleTime, writerIdleTime, allIdleTime, unit);
    }

    /**
     * 实例化一个新的{@link IdleStateEvent}实例。
     */
    public IdleStateHandler(boolean observeOutput,
            long readerIdleTime, long writerIdleTime, long allIdleTime,
            TimeUnit unit) {
        if (unit == null) {
            throw new NullPointerException("unit");
        }

        this.observeOutput = observeOutput;

        if (readerIdleTime <= 0) {
            readerIdleTimeNanos = 0;
        } else {
            readerIdleTimeNanos = Math.max(unit.toNanos(readerIdleTime), MIN_TIMEOUT_NANOS);
        }
        if (writerIdleTime <= 0) {
            writerIdleTimeNanos = 0;
        } else {
            writerIdleTimeNanos = Math.max(unit.toNanos(writerIdleTime), MIN_TIMEOUT_NANOS);
        }
        if (allIdleTime <= 0) {
            allIdleTimeNanos = 0;
        } else {
            allIdleTimeNanos = Math.max(unit.toNanos(allIdleTime), MIN_TIMEOUT_NANOS);
        }
    }

    /**
     * 返回实例此类以毫秒为单位时给出的readerIdleTime。
     */
    public long getReaderIdleTimeInMillis() {
        return TimeUnit.NANOSECONDS.toMillis(readerIdleTimeNanos);
    }

    /**
     * 返回实例此类时给出的writerIdleTime，以毫秒为单位。
     */
    public long getWriterIdleTimeInMillis() {
        return TimeUnit.NANOSECONDS.toMillis(writerIdleTimeNanos);
    }

    /**
     * 返回实例此类时给出的allIdleTime，以毫秒为单位。
     */
    public long getAllIdleTimeInMillis() {
        return TimeUnit.NANOSECONDS.toMillis(allIdleTimeNanos);
    }

    /**
     * ChannelHandle动态添加pipline
     */
    @Override
    public void handlerAdded(ChannelHandlerContext ctx) throws Exception {
        if (ctx.channel().isActive() && ctx.channel().isRegistered()) {
            /** 初始化 **/
            initialize(ctx);
        } else {
        }
    }

    /**
     * ChannelHandle动态从pipline删除
     */
    @Override
    public void handlerRemoved(ChannelHandlerContext ctx) throws Exception {
        /** 销毁 **/
        destroy();
    }

    /**
     * 触发 Registere事件 （channel注册到Eventloop）
     */
    @Override
    public void channelRegistered(ChannelHandlerContext ctx) throws Exception {
        if (ctx.channel().isActive()) {
            /** 初始化 **/
            initialize(ctx);
        }
        super.channelRegistered(ctx);
    }

    /**
     * 当客户端与服务端成功建立连接后，Channel 被激活,触发Active事件
     */
    @Override
    public void channelActive(ChannelHandlerContext ctx) throws Exception {
        /** 初始化 **/
        initialize(ctx);
        super.channelActive(ctx);
    }

    /**
     * 当客户端与服务端成功建立连接被关闭，Channel 被销毁,触发Inactive事件
     */
    @Override
    public void channelInactive(ChannelHandlerContext ctx) throws Exception {
        /** 销毁 **/
        destroy();
        super.channelInactive(ctx);
    }

    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
        if (readerIdleTimeNanos > 0 || allIdleTimeNanos > 0) {
            reading = true;
            firstReaderIdleEvent = firstAllIdleEvent = true;
        }
        ctx.fireChannelRead(msg);
    }

    @Override
    public void channelReadComplete(ChannelHandlerContext ctx) throws Exception {
        if ((readerIdleTimeNanos > 0 || allIdleTimeNanos > 0) && reading) {
            lastReadTime = ticksInNanos();
            reading = false;
        }
        ctx.fireChannelReadComplete();
    }

    @Override
    public void write(ChannelHandlerContext ctx, Object msg, ChannelPromise promise) throws Exception {
        // Allow writing with void promise if handler is only configured for read timeout events.
        if (writerIdleTimeNanos > 0 || allIdleTimeNanos > 0) {
            ctx.write(msg, promise.unvoid()).addListener(writeListener);
        } else {
            ctx.write(msg, promise);
        }
    }

    /**
     * 初始化
     */
    private void initialize(ChannelHandlerContext ctx) {
        /** 校验状态，避免因为 #destroy() 方法在
        // #initialize(ChannelHandlerContext ctx) 方法，执行之前。**/
        switch (state) {
        case 1:
        case 2:
            return;
        }

        /** 标记为已初始化 **/
        state = 1;
        /** 初始化 “监控出站数据属性” **/
        initOutputChanged(ctx);


        /** 初始相应的定时任务  **/
        lastReadTime = lastWriteTime = ticksInNanos();
        if (readerIdleTimeNanos > 0) {
            readerIdleTimeout = schedule(ctx, new ReaderIdleTimeoutTask(ctx),
                    readerIdleTimeNanos, TimeUnit.NANOSECONDS);
        }
        if (writerIdleTimeNanos > 0) {
            writerIdleTimeout = schedule(ctx, new WriterIdleTimeoutTask(ctx),
                    writerIdleTimeNanos, TimeUnit.NANOSECONDS);
        }
        if (allIdleTimeNanos > 0) {
            allIdleTimeout = schedule(ctx, new AllIdleTimeoutTask(ctx),
                    allIdleTimeNanos, TimeUnit.NANOSECONDS);
        }
    }

    /**
     * 返回当前时间
     */
    long ticksInNanos() {
        return System.nanoTime();
    }

    /**
     * This method is visible for testing!
     */
    ScheduledFuture<?> schedule(ChannelHandlerContext ctx, Runnable task, long delay, TimeUnit unit) {
        return ctx.executor().schedule(task, delay, unit);
    }

    /**
     * 销毁
     */
    private void destroy() {
        /** 标记为销毁 **/
        state = 2;

        if (readerIdleTimeout != null) {
            readerIdleTimeout.cancel(false);
            readerIdleTimeout = null;
        }
        if (writerIdleTimeout != null) {
            writerIdleTimeout.cancel(false);
            writerIdleTimeout = null;
        }
        if (allIdleTimeout != null) {
            allIdleTimeout.cancel(false);
            allIdleTimeout = null;
        }
    }

    /**
     * Is called when an {@link IdleStateEvent} should be fired. This implementation calls
     * {@link ChannelHandlerContext#fireUserEventTriggered(Object)}.
     */
    protected void channelIdle(ChannelHandlerContext ctx, IdleStateEvent evt) throws Exception {
        ctx.fireUserEventTriggered(evt);
    }

    /**
     * Returns a {@link IdleStateEvent}.
     */
    protected IdleStateEvent newIdleStateEvent(IdleState state, boolean first) {
        switch (state) {
            case ALL_IDLE:
                return first ? IdleStateEvent.FIRST_ALL_IDLE_STATE_EVENT : IdleStateEvent.ALL_IDLE_STATE_EVENT;
            case READER_IDLE:
                return first ? IdleStateEvent.FIRST_READER_IDLE_STATE_EVENT : IdleStateEvent.READER_IDLE_STATE_EVENT;
            case WRITER_IDLE:
                return first ? IdleStateEvent.FIRST_WRITER_IDLE_STATE_EVENT : IdleStateEvent.WRITER_IDLE_STATE_EVENT;
            default:
                throw new IllegalArgumentException("Unhandled: state=" + state + ", first=" + first);
        }
    }

    /**
     * 初始化 ChannelOutboundBuffer 相关属性
     */
    private void initOutputChanged(ChannelHandlerContext ctx) {
        /** 是否考虑出站时较慢的情况 **/
        if (observeOutput) {
            Channel channel = ctx.channel();
            Unsafe unsafe = channel.unsafe();
            ChannelOutboundBuffer buf = unsafe.outboundBuffer();

            if (buf != null) {
                /**  记录第一条准备 flash 到对端的消息的 HashCode **/
                lastMessageHashCode = System.identityHashCode(buf.current());
                /** 记录总共等待 flush 到对端的内存大小 **/
                lastPendingWriteBytes = buf.totalPendingWriteBytes();
                lastFlushProgress = buf.currentProgress();
            }
        }
    }

    /**

     * https://github.com/netty/netty/issues/6150
     */
    private boolean hasOutputChanged(ChannelHandlerContext ctx, boolean first) {
        if (observeOutput) {

            // 如果最后一次写的时间和上一次记录的时间不一样，说明写操作进行过了，则更新此值
            if (lastChangeCheckTimeStamp != lastWriteTime) {
                lastChangeCheckTimeStamp = lastWriteTime;

                // 但如果，在这个方法的调用间隙修改的，就仍然不触发事件
                if (!first) {
                    return true;
                }
            }

            Channel channel = ctx.channel();
            Unsafe unsafe = channel.unsafe();
            ChannelOutboundBuffer buf = unsafe.outboundBuffer();

            // 如果出站区有数据
            if (buf != null) {
                // 拿到出站缓冲区的 对象 hashcode
                int messageHashCode = System.identityHashCode(buf.current());
                // 拿到这个 缓冲区的 所有字节
                long pendingWriteBytes = buf.totalPendingWriteBytes();
                // 如果和之前的不相等，或者字节数不同，说明，输出有变化，将 "最后一个缓冲区引用" 和 “剩余字节数” 刷新
                if (messageHashCode != lastMessageHashCode || pendingWriteBytes != lastPendingWriteBytes) {
                    lastMessageHashCode = messageHashCode;
                    lastPendingWriteBytes = pendingWriteBytes;
                    // 如果写操作没有进行过，则任务写的慢，不触发空闲事件
                    if (!first) {
                        return true;
                    }
                }

                long flushProgress = buf.currentProgress();
                if (flushProgress != lastFlushProgress) {
                    lastFlushProgress = flushProgress;

                    if (!first) {
                        return true;
                    }
                }
            }
        }

        return false;
    }

    private abstract static class AbstractIdleTask implements Runnable {

        private final ChannelHandlerContext ctx;

        AbstractIdleTask(ChannelHandlerContext ctx) {
            this.ctx = ctx;
        }

        @Override
        public void run() {
            /**  <1> 忽略未打开的 Channel  **/
            if (!ctx.channel().isOpen()) {
                return;
            }
            /** 执行，模板方法，不同子类进行不同的实现  **/
            run(ctx);
        }

        protected abstract void run(ChannelHandlerContext ctx);
    }

    private final class ReaderIdleTimeoutTask extends AbstractIdleTask {

        ReaderIdleTimeoutTask(ChannelHandlerContext ctx) {
            super(ctx);
        }

        @Override
        protected void run(ChannelHandlerContext ctx) {
            /**  获取读空闲时间 **/
            long nextDelay = readerIdleTimeNanos;

            /** reading 为 false 时，意味着并未进行读取，计算读空闲时间还有多久超时nextDelay **/
            if (!reading) {
                nextDelay -= ticksInNanos() - lastReadTime;
            }

            /** 检测到读空闲时间超时 **/
            if (nextDelay <= 0) {
                /** 添加一个新的 ReaderIdleTimeoutTask 定时任务。其中，延迟时间为 readerIdleTimeNanos 用来触发下次检测**/
                readerIdleTimeout = schedule(ctx, this, readerIdleTimeNanos, TimeUnit.NANOSECONDS);

                /** 获得当前是否首次检测到读空闲 **/
                boolean first = firstReaderIdleEvent;
                /** 标记 firstReaderIdleEvent 为 false 。也就说，下次检测到空闲，就非首次了 **/
                firstReaderIdleEvent = false;

                try {
                    /** 创建创建读空闲事件。 **/
                    IdleStateEvent event = newIdleStateEvent(IdleState.READER_IDLE, first);
                    /** 调用 #channelIdle 方法，在 pipeline 中，触发 UserEvent 事件。 **/

                    channelIdle(ctx, event);
                } catch (Throwable t) {
                    /** 如果发生异常，触发 Exception Caught 事件到下一个节点，处理异常 **/
                    ctx.fireExceptionCaught(t);
                }
            } else {
                /** 添加一个新的 ReaderIdleTimeoutTask 定时任务。其中，延迟时间为 nextDelay 用来触发下次检测**/
                readerIdleTimeout = schedule(ctx, this, nextDelay, TimeUnit.NANOSECONDS);
            }
        }
    }

    private final class WriterIdleTimeoutTask extends AbstractIdleTask {

        WriterIdleTimeoutTask(ChannelHandlerContext ctx) {
            super(ctx);
        }

        @Override
        protected void run(ChannelHandlerContext ctx) {
            /** 最后写时间 **/
            long lastWriteTime = IdleStateHandler.this.lastWriteTime;
            /** 计算写空闲时间还有多久超时nextDelay **/
            long nextDelay = writerIdleTimeNanos - (ticksInNanos() - lastWriteTime);

            /** 检测到写空闲时间超时 **/
            if (nextDelay <= 0) {
                /** 添加一个新的 WriterIdleTimeoutTask 定时任务。其中，延迟时间为 writerIdleTimeNanos 用来触发下次检测**/
                writerIdleTimeout = schedule(ctx, this, writerIdleTimeNanos, TimeUnit.NANOSECONDS);

                /** 获得当前是否首次检测到写空闲 **/
                boolean first = firstWriterIdleEvent;
                /** 标记 firstWriterIdleEvent 为 false 。也就说，下次检测到空闲，就非首次了 **/
                firstWriterIdleEvent = false;

                try {
                    /** 判断 ChannelOutboundBuffer 是否发生变化 **/
                    if (hasOutputChanged(ctx, first)) {
                        return;
                    }
                    /**  创建写空闲事件  **/
                    IdleStateEvent event = newIdleStateEvent(IdleState.WRITER_IDLE, first);
                    /** 调用 #channelIdle 方法，在 pipeline 中，触发 UserEvent 事件。 **/
                    channelIdle(ctx, event);
                } catch (Throwable t) {
                    ctx.fireExceptionCaught(t);
                }
            } else {
                /** 添加一个新的 WriterIdleTimeoutTask 定时任务。其中，延迟时间为 nextDelay 用来触发下次检测**/
                writerIdleTimeout = schedule(ctx, this, nextDelay, TimeUnit.NANOSECONDS);
            }
        }
    }

    private final class AllIdleTimeoutTask extends AbstractIdleTask {

        AllIdleTimeoutTask(ChannelHandlerContext ctx) {
            super(ctx);
        }

        @Override
        protected void run(ChannelHandlerContext ctx) {
            /** 读或写任一空闲时间 **/
            long nextDelay = allIdleTimeNanos;

            /** reading 为 false 时，意味着并未进行读取，计算读写空闲时间还有多久超时nextDelay **/
            if (!reading) {
                nextDelay -= ticksInNanos() - Math.max(lastReadTime, lastWriteTime);
            }

            /** 检测到读写空闲时间超时 **/
            if (nextDelay <= 0) {
                /** 添加一个新的 AllIdleTimeoutTask 定时任务。其中，延迟时间为 allIdleTimeNanos 用来触发下次检测**/
                allIdleTimeout = schedule(ctx, this, allIdleTimeNanos, TimeUnit.NANOSECONDS);

                /** 获得当前是否首次检测到读写空闲 **/
                boolean first = firstAllIdleEvent;
                /** 标记 firstWriterIdleEvent 为 false 。也就说，下次检测到空闲，就非首次了 **/
                firstAllIdleEvent = false;

                try {
                    /** 判断 ChannelOutboundBuffer 是否发生变化 **/
                    if (hasOutputChanged(ctx, first)) {
                        return;
                    }
                    /**  创建读写空闲事件  **/
                    IdleStateEvent event = newIdleStateEvent(IdleState.ALL_IDLE, first);
                    /** 调用 #channelIdle 方法，在 pipeline 中，触发 UserEvent 事件。 **/
                    channelIdle(ctx, event);
                } catch (Throwable t) {
                    ctx.fireExceptionCaught(t);
                }
            } else {
                /** 添加一个新的 AllIdleTimeoutTask 定时任务。其中，延迟时间为 nextDelay 用来触发下次检测**/
                allIdleTimeout = schedule(ctx, this, nextDelay, TimeUnit.NANOSECONDS);
            }
        }
    }
}
