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

import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufAllocator;
import io.netty.channel.Channel;
import io.netty.channel.ChannelConfig;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelMetadata;
import io.netty.channel.ChannelOutboundBuffer;
import io.netty.channel.ChannelPipeline;
import io.netty.channel.FileRegion;
import io.netty.channel.RecvByteBufAllocator;
import io.netty.channel.internal.ChannelUtils;
import io.netty.channel.socket.ChannelInputShutdownEvent;
import io.netty.channel.socket.ChannelInputShutdownReadComplete;
import io.netty.channel.socket.SocketChannelConfig;
import io.netty.util.internal.StringUtil;

import java.io.IOException;
import java.nio.channels.SelectableChannel;
import java.nio.channels.SelectionKey;

import static io.netty.channel.internal.ChannelUtils.WRITE_STATUS_SNDBUF_FULL;


public abstract class AbstractNioByteChannel extends AbstractNioChannel {

    private static final ChannelMetadata METADATA = new ChannelMetadata(false, 16);

    private static final String EXPECTED_TYPES =
            " (expected: " + StringUtil.simpleClassName(ByteBuf.class) + ", " +
            StringUtil.simpleClassName(FileRegion.class) + ')';

    private final Runnable flushTask = new Runnable() {
        @Override
        public void run() {
            // Calling flush0 directly to ensure we not try to flush messages that were added via write(...) in the
            // meantime.
            ((AbstractNioUnsafe) unsafe()).flush0();
        }
    };

    private boolean inputClosedSeenErrorOnRead;

    /**
     * 实例化 AbstractNioByteChannel
     */
    protected AbstractNioByteChannel(Channel parent, SelectableChannel ch) {
        super(parent, ch, SelectionKey.OP_READ);
    }

    /**
     * 关闭通道的输入侧。
     */
    protected abstract ChannelFuture shutdownInput();

    protected boolean isInputShutdown0() {
        return false;
    }

    @Override
    protected AbstractNioUnsafe newUnsafe() {
        return new NioByteUnsafe();
    }

    @Override
    public ChannelMetadata metadata() {
        return METADATA;
    }

    final boolean shouldBreakReadReady(ChannelConfig config) {
        return isInputShutdown0() && (inputClosedSeenErrorOnRead || !isAllowHalfClosure(config));
    }

    private static boolean isAllowHalfClosure(ChannelConfig config) {
        return config instanceof SocketChannelConfig &&
                ((SocketChannelConfig) config).isAllowHalfClosure();
    }

    protected class NioByteUnsafe extends AbstractNioUnsafe {

        private void closeOnRead(ChannelPipeline pipeline) {
            if (!isInputShutdown0()) {
                if (isAllowHalfClosure(config())) {
                    shutdownInput();
                    pipeline.fireUserEventTriggered(ChannelInputShutdownEvent.INSTANCE);
                } else {
                    close(voidPromise());
                }
            } else {
                inputClosedSeenErrorOnRead = true;
                pipeline.fireUserEventTriggered(ChannelInputShutdownReadComplete.INSTANCE);
            }
        }

        private void handleReadException(ChannelPipeline pipeline, ByteBuf byteBuf, Throwable cause, boolean close,
                RecvByteBufAllocator.Handle allocHandle) {
            if (byteBuf != null) {
                /** 如果byteBuf有可以读取数据 **/
                if (byteBuf.isReadable()) {
                    /** 标识 readPending **/
                    readPending = false;
                    /**  触发 Channel read 事件传递给 pipeline 中。 **/
                    pipeline.fireChannelRead(byteBuf);
                } else {
                    /**  释放 ByteBuf 对象 **/
                    byteBuf.release();
                }
            }
            /** 设置allocHandle读取完成 **/
            allocHandle.readComplete();
            /** 触发 Channel readComplete 事件传递给pipeline 中。 **/
            pipeline.fireChannelReadComplete();
            /** 触发 exceptionCaught 事件传递给 pipeline 中。 **/
            pipeline.fireExceptionCaught(cause);
            if (close || cause instanceof IOException) {
                closeOnRead(pipeline);
            }
        }

        @Override
        public final void read() {
            final ChannelConfig config = config();
            /** 若 inputClosedSeenErrorOnRead = true ，移除对 SelectionKey.OP_READ 事件的感兴趣
             *  inputClosedSeenErrorOnRead 服务端处理客户端主动关闭连接后的标识 **/
            if (shouldBreakReadReady(config)) {
                clearReadPending();
                return;
            }
            final ChannelPipeline pipeline = pipeline();
            final ByteBufAllocator allocator = config.getAllocator();
            /** 获得 获得 RecvByteBufAllocator.Handle 对象。默认情况下，返回的是 AdaptiveRecvByteBufAllocator.HandleImpl 对象 **/
            final RecvByteBufAllocator.Handle allocHandle = recvBufAllocHandle();
            /** 重置 RecvByteBufAllocator.Handle 对象 **/
            allocHandle.reset(config);

            ByteBuf byteBuf = null;
            boolean close = false;
            try {
                /** while 循环 读取新的写入数据。 **/
                do {
                    /** 申请 ByteBuf 对象  **/
                    byteBuf = allocHandle.allocate(allocator);
                    /** doReadBytes 读取数据写入buf，返回值表示读取数据大小，如果返回值小于 0 时，标识连接被关闭*/
                    /** lastBytesRead 设置最后一次读取动作读取字节数，和总读取字节数 **/
                    allocHandle.lastBytesRead(doReadBytes(byteBuf));
                    /** 最后一次读取动作读取字节数未读取到数据 **/
                    if (allocHandle.lastBytesRead() <= 0) {
                        /** 释放 ByteBuf 对象  **/
                        byteBuf.release();
                        /**  置空 ByteBuf 对象  **/
                        byteBuf = null;
                        /**  如果最后读取的字节为小于 0 ，说明对端已经关闭  **/
                        close = allocHandle.lastBytesRead() < 0;
                        /** 如果Channel已经关闭，设置readPending标识为false **/
                        if (close) {
                            readPending = false;
                        }
                        /**  结束循环  **/
                        break;
                    }
                    /** 读取消息数量 + 1  **/
                    allocHandle.incMessagesRead(1);
                    readPending = false;
                    /** 触发 Channel read 事件 传递给pipeline **/
                    pipeline.fireChannelRead(byteBuf);
                    /** 置空 ByteBuf 对象 **/
                    byteBuf = null;
                }
                /** 判断是否循环是否继续，读取新的数据 **/
                while (allocHandle.continueReading());

                /**  allocHandle读取完成。 **/
                allocHandle.readComplete();

                /**  触发 Channel readComplete 传递给pipeline  **/
                pipeline.fireChannelReadComplete();

                /** 如果close标识为true关闭客户端的连接 **/
                if (close) {
                    closeOnRead(pipeline);
                }
            } catch (Throwable t) {
                /** 处理异常 **/
                handleReadException(pipeline, byteBuf, t, close, allocHandle);
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

    /**
     * 对于NIO流程来看，该方法被子类NioSocketChannel覆盖，对于NIO来说不会调用，可以忽略
     * 该方法会给NioUdtByteConnectorChannel 和 NioUdtByteRendezvousChannel 会使用到该方法
     */
    @Override
    protected void doWrite(ChannelOutboundBuffer in) throws Exception {
        int writeSpinCount = config().getWriteSpinCount();
        do {
            Object msg = in.current();
            if (msg == null) {
                clearOpWrite();
                return;
            }
            writeSpinCount -= doWriteInternal(in, msg);
        } while (writeSpinCount > 0);

        incompleteWrite(writeSpinCount < 0);
    }

    /**
     * 内部的数据为 FileRegion 写入
     */
    protected final int doWrite0(ChannelOutboundBuffer in) throws Exception {
        Object msg = in.current();
        if (msg == null) {
            // Directly return here so incompleteWrite(...) is not called.
            return 0;
        }
        return doWriteInternal(in, in.current());
    }

    /**
     * 内部的数据为 FileRegion 写入
     */
    private int doWriteInternal(ChannelOutboundBuffer in, Object msg) throws Exception {
        if (msg instanceof ByteBuf) {
            ByteBuf buf = (ByteBuf) msg;
            if (!buf.isReadable()) {
                in.remove();
                return 0;
            }

            final int localFlushedAmount = doWriteBytes(buf);
            if (localFlushedAmount > 0) {
                in.progress(localFlushedAmount);
                if (!buf.isReadable()) {
                    in.remove();
                }
                return 1;
            }
        } else if (msg instanceof FileRegion) {
            FileRegion region = (FileRegion) msg;
            if (region.transferred() >= region.count()) {
                in.remove();
                return 0;
            }

            long localFlushedAmount = doWriteFileRegion(region);
            if (localFlushedAmount > 0) {
                in.progress(localFlushedAmount);
                if (region.transferred() >= region.count()) {
                    in.remove();
                }
                return 1;
            }
        } else {
            throw new Error();
        }
        return WRITE_STATUS_SNDBUF_FULL;
    }



    @Override
    protected final Object filterOutboundMessage(Object msg) {
        /** 判断消息类型是否为 ByteBuf **/
        if (msg instanceof ByteBuf) {
            ByteBuf buf = (ByteBuf) msg;
            /** 如果ByteBuf 实现方式是堆外内存直接返回 **/
            if (buf.isDirect()) {
                return msg;
            }
            /** 如果ByteBuf实现不是堆外，需要进行创建封装 **/
            return newDirectBuffer(buf);
        }
        /** 判断消息类型是否为 FileRegion **/
        if (msg instanceof FileRegion) {
            return msg;
        }
        /** 不支持其他类型 **/
        throw new UnsupportedOperationException(
                "unsupported message type: " + StringUtil.simpleClassName(msg) + EXPECTED_TYPES);
    }

    protected final void incompleteWrite(boolean setOpWrite) {
        /**setOpWrite== true ，注册对 SelectionKey.OP_WRITE 事件感兴趣 **/
        if (setOpWrite) {
            /** 注册对 SelectionKey.OP_WRITE 事件感兴趣 **/
            setOpWrite();
        }
        /** setOpWrite==false ，取消对 SelectionKey.OP_WRITE 事件感兴趣 **/
        else {
            /** 取消对 SelectionKey.OP_WRITE 事件感兴趣  **/
            clearOpWrite();
            /** 异步任务提交立即发起下一次 flush 任务**/
            eventLoop().execute(flushTask);
        }
    }

    /**
     * 将FileRegion中数据写入Channel
     */
    protected abstract long doWriteFileRegion(FileRegion region) throws Exception;

    /**
     * 读取Channel数据写入ByteBuf
     */
    protected abstract int doReadBytes(ByteBuf buf) throws Exception;

    /**
     * 将ByteBuf中数据写入Channel
     */
    protected abstract int doWriteBytes(ByteBuf buf) throws Exception;

    /**
     * 设置Channel注册到Selector事件追加 SelectionKey.OP_WRITE
     */
    protected final void setOpWrite() {
        final SelectionKey key = selectionKey();
        if (!key.isValid()) {
            return;
        }
        final int interestOps = key.interestOps();
        if ((interestOps & SelectionKey.OP_WRITE) == 0) {
            key.interestOps(interestOps | SelectionKey.OP_WRITE);
        }
    }

    /**
     * 取消Channel注册到Selector SelectionKey.OP_WRITE 事件
     */
    protected final void clearOpWrite() {
        final SelectionKey key = selectionKey();
        if (!key.isValid()) {
            return;
        }
        final int interestOps = key.interestOps();
        if ((interestOps & SelectionKey.OP_WRITE) != 0) {
            key.interestOps(interestOps & ~SelectionKey.OP_WRITE);
        }
    }
}
