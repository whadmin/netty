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
package io.netty.channel.nio;

import io.netty.channel.Channel;
import io.netty.channel.ChannelConfig;
import io.netty.channel.ChannelOutboundBuffer;
import io.netty.channel.ChannelPipeline;
import io.netty.channel.RecvByteBufAllocator;
import io.netty.channel.ServerChannel;

import java.io.IOException;
import java.net.PortUnreachableException;
import java.nio.channels.SelectableChannel;
import java.nio.channels.SelectionKey;
import java.util.ArrayList;
import java.util.List;

/**
 * {@link AbstractNioChannel} base class for {@link Channel}s that operate on messages.
 */
public abstract class AbstractNioMessageChannel extends AbstractNioChannel {
    boolean inputShutdown;

    /**
     * @see AbstractNioChannel#AbstractNioChannel(Channel, SelectableChannel, int)
     */
    protected AbstractNioMessageChannel(Channel parent, SelectableChannel ch, int readInterestOp) {
        super(parent, ch, readInterestOp);
    }

    @Override
    protected AbstractNioUnsafe newUnsafe() {
        return new NioMessageUnsafe();
    }

    @Override
    protected void doBeginRead() throws Exception {
        if (inputShutdown) {
            return;
        }
        super.doBeginRead();
    }

    private final class NioMessageUnsafe extends AbstractNioUnsafe {

        private final List<Object> readBuf = new ArrayList<Object>();

        @Override
        public void read() {
            assert eventLoop().inEventLoop();
            final ChannelConfig config = config();
            final ChannelPipeline pipeline = pipeline();
            /** 获得 获得 RecvByteBufAllocator.Handle 对象。默认情况下，返回的是 AdaptiveRecvByteBufAllocator.HandleImpl 对象 **/
            final RecvByteBufAllocator.Handle allocHandle = unsafe().recvBufAllocHandle();
            /** 重置 RecvByteBufAllocator.Handle 对象 **/
            allocHandle.reset(config);

            boolean closed = false;
            Throwable exception = null;
            try {
                try {
                    /** while 循环 “读取”新的客户端连接连入 **/
                    do {
                        /** 读取客户端的连接NioSocketChannel放入readBuf列表中，并返回列表中NioSocketChannel对象个数 **/
                        int localRead = doReadMessages(readBuf);
                        /** localRead==0 表示没有获取到客户端的连接NioSocketChannel对象，结束 **/
                        if (localRead == 0) {
                            break;
                        }
                        /** localRead < 0 读取客户端的连接发生异常 NioServerSocketChannel实现doReadMessages不存在此清空，可忽略 **/
                        if (localRead < 0) {
                            /**  标记关闭  **/
                            closed = true;
                            break;
                        }
                        /** 读取消息数量 + localRead  Nio实现类为MaxMessageHandle **/
                        allocHandle.incMessagesRead(localRead);
                    }
                    /** 判断是否循环是否继续，读取( 接受 )新的客户端连接 **/
                    while (allocHandle.continueReading());
                } catch (Throwable t) {
                    /** 读取过程中发生异常，记录该异常到 exception 中 **/
                    exception = t;
                }

                /**  获取readBuf列表中获得 连接NioSocketChannel 对象的数量  **/
                int size = readBuf.size();
                /**  遍历readBuf列表，触发 Read 事件，对于NioServerSocketChannel 默认pipeline处理连接请求ChannelHannel链表如下
                 *   HeadContext -->  ServerBootstrapAcceptor  --> tailContext
                 * **/
                for (int i = 0; i < size; i ++) {
                    readPending = false;
                    pipeline.fireChannelRead(readBuf.get(i));
                }
                /**   清空 readBuf 数组 **/
                readBuf.clear();
                /**  设置allocHandle 读取完成  **/
                allocHandle.readComplete();
                /**   触发 Channel ReadComplete 事件传递给 pipeline 。 **/
                pipeline.fireChannelReadComplete();

                /**  判断是否发生异常  **/
                if (exception != null) {
                    /** 判断是否要关闭 **/
                    closed = closeOnReadError(exception);
                    /** 触发 exceptionCaught 事件传递给 pipeline 中。 **/
                    pipeline.fireExceptionCaught(exception);
                }
                /** 判断是否要关闭 **/
                if (closed) {
                    /** 标识读取关闭,设置后调用doBeginRead() 会直接返回，不在做处理**/
                    inputShutdown = true;
                    /** 如果SelectableChannel 没有关闭 **/
                    if (isOpen()) {
                        /** 关闭Channel **/
                        close(voidPromise());
                    }
                }
            } finally {
                /**  检查是否有尚未处理的 byteBuf。如果不存在且config配置不是自动读取，则移除对“读”事件的感兴趣 **/
                if (!readPending && !config.isAutoRead()) {
                    /** 移除对“读”事件的感兴趣
                     *  对于 NioServerSocketChannel 的 “读“事件就是 SelectionKey.OP_ACCEPT
                     *  对于 NioSocketChannel 的 “读“事件就是 SelectionKey.OP_READ **/
                    removeReadOp();
                }
            }
        }
    }

    @Override
    protected void doWrite(ChannelOutboundBuffer in) throws Exception {
        final SelectionKey key = selectionKey();
        final int interestOps = key.interestOps();

        for (;;) {
            Object msg = in.current();
            if (msg == null) {
                // Wrote all messages.
                if ((interestOps & SelectionKey.OP_WRITE) != 0) {
                    key.interestOps(interestOps & ~SelectionKey.OP_WRITE);
                }
                break;
            }
            try {
                boolean done = false;
                for (int i = config().getWriteSpinCount() - 1; i >= 0; i--) {
                    if (doWriteMessage(msg, in)) {
                        done = true;
                        break;
                    }
                }

                if (done) {
                    in.remove();
                } else {
                    // Did not write all messages.
                    if ((interestOps & SelectionKey.OP_WRITE) == 0) {
                        key.interestOps(interestOps | SelectionKey.OP_WRITE);
                    }
                    break;
                }
            } catch (Exception e) {
                if (continueOnWriteError()) {
                    in.remove(e);
                } else {
                    throw e;
                }
            }
        }
    }

    /**
     * Returns {@code true} if we should continue the write loop on a write error.
     */
    protected boolean continueOnWriteError() {
        return false;
    }

    protected boolean closeOnReadError(Throwable cause) {
        if (!isActive()) {
            // If the channel is not active anymore for whatever reason we should not try to continue reading.
            return true;
        }
        if (cause instanceof PortUnreachableException) {
            return false;
        }
        if (cause instanceof IOException) {
            // ServerChannel should not be closed even on IOException because it can often continue
            // accepting incoming connections. (e.g. too many open files)
            return !(this instanceof ServerChannel);
        }
        return true;
    }

    /**
     * 读取客户端的连接NioSocketChannel放入readBuf列表中
     */
    protected abstract int doReadMessages(List<Object> buf) throws Exception;

    /**
     * 将消息写入底层{@link java.nio.channels.Channel}。

     */
    protected abstract boolean doWriteMessage(Object msg, ChannelOutboundBuffer in) throws Exception;
}
