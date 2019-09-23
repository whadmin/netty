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
package io.netty.handler.codec;

import static io.netty.util.internal.ObjectUtil.checkPositive;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufAllocator;
import io.netty.buffer.CompositeByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelConfig;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.socket.ChannelInputShutdownEvent;
import io.netty.util.internal.StringUtil;

import java.util.List;

/**
 * {@link ChannelInboundHandlerAdapter} which decodes bytes in a stream-like fashion from one {@link ByteBuf} to an
 * other Message type.
 *
 * For example here is an implementation which reads all readable bytes from
 * the input {@link ByteBuf} and create a new {@link ByteBuf}.
 *
 * <pre>
 *     public class SquareDecoder extends {@link ByteToMessageDecoder} {
 *         {@code @Override}
 *         public void decode({@link ChannelHandlerContext} ctx, {@link ByteBuf} in, List&lt;Object&gt; out)
 *                 throws {@link Exception} {
 *             out.add(in.readBytes(in.readableBytes()));
 *         }
 *     }
 * </pre>
 *
 * <h3>Frame detection</h3>
 * <p>
 * Generally frame detection should be handled earlier in the pipeline by adding a
 * {@link DelimiterBasedFrameDecoder}, {@link FixedLengthFrameDecoder}, {@link LengthFieldBasedFrameDecoder},
 * or {@link LineBasedFrameDecoder}.
 * <p>
 * If a custom frame decoder is required, then one needs to be careful when implementing
 * one with {@link ByteToMessageDecoder}. Ensure there are enough bytes in the buffer for a
 * complete frame by checking {@link ByteBuf#readableBytes()}. If there are not enough bytes
 * for a complete frame, return without modifying the reader index to allow more bytes to arrive.
 * <p>
 * To check for complete frames without modifying the reader index, use methods like {@link ByteBuf#getInt(int)}.
 * One <strong>MUST</strong> use the reader index when using methods like {@link ByteBuf#getInt(int)}.
 * For example calling <tt>in.getInt(0)</tt> is assuming the frame starts at the beginning of the buffer, which
 * is not always the case. Use <tt>in.getInt(in.readerIndex())</tt> instead.
 * <h3>Pitfalls</h3>
 * <p>
 * Be aware that sub-classes of {@link ByteToMessageDecoder} <strong>MUST NOT</strong>
 * annotated with {@link @Sharable}.
 * <p>
 * Some methods such as {@link ByteBuf#readBytes(int)} will cause a memory leak if the returned buffer
 * is not released or added to the <tt>out</tt> {@link List}. Use derived buffers like {@link ByteBuf#readSlice(int)}
 * to avoid leaking memory.
 */
public abstract class ByteToMessageDecoder extends ChannelInboundHandlerAdapter {


    /**
     * 初始化状态
     */
    private static final byte STATE_INIT = 0;
    /**
     * 正在进行解码状态
     */
    private static final byte STATE_CALLING_CHILD_DECODE = 1;

    /**
     * ChannelHandle 被移除状态
     */
    private static final byte STATE_HANDLER_REMOVED_PENDING = 2;

    /**
     * 累积的 ByteBuf 对象
     */
    ByteBuf cumulation;

    /**
     * 累计器
     */
    private Cumulator cumulator = MERGE_CUMULATOR;

    /**
     * 设置为true后每个channelRead事件只解码出一个消息包
     */
    private boolean singleDecode;

    /**
     * 是否首次读取，即 {@link #cumulation} 为空
     */
    private boolean first;

    /**
     * 此标志用于确定当{@link channelconfig isautoread（）}为{@code false}时，
     * 是否需要调用{@link channelhandlercontext read（）}来消耗更多数据。
     */
    private boolean firedChannelRead;

    /**
     * 解码状态
     * 0 - 初始化
     * 1 - 调用 {@link #decode(ChannelHandlerContext, ByteBuf, List)} 方法中，正在进行解码
     * 2 - 准备移除
     */
    private byte decodeState = STATE_INIT;

    /**
     * 读取释放阀值
     */
    private int discardAfterReads = 16;

    /**
     * 已读取次数。
     * 再读取 {@link #discardAfterReads} 次数据后，如果无法全部解码完，则进行释放，避免 OOM
     */
    private int numReads;

    protected ByteToMessageDecoder() {
        ensureNotSharable();
    }


    public void setSingleDecode(boolean singleDecode) {
        this.singleDecode = singleDecode;
    }


    public boolean isSingleDecode() {
        return singleDecode;
    }


    public void setCumulator(Cumulator cumulator) {
        if (cumulator == null) {
            throw new NullPointerException("cumulator");
        }
        this.cumulator = cumulator;
    }


    public void setDiscardAfterReads(int discardAfterReads) {
        checkPositive(discardAfterReads, "discardAfterReads");
        this.discardAfterReads = discardAfterReads;
    }


    /**
     * 获得可读字节数。
     */
    protected int actualReadableBytes() {
        return internalBuffer().readableBytes();
    }


    /**
     * 获得 ByteBuf 对象
     */
    protected ByteBuf internalBuffer() {
        if (cumulation != null) {
            return cumulation;
        } else {
            return Unpooled.EMPTY_BUFFER;
        }
    }

    @Override
    public final void handlerRemoved(ChannelHandlerContext ctx) throws Exception {
        /** 如果当前状态处于正在解码状态，标记状态为当前对象 ChannelHandle 被移除状态**/
        if (decodeState == STATE_CALLING_CHILD_DECODE) {
            decodeState = STATE_HANDLER_REMOVED_PENDING;
            return;
        }
        ByteBuf buf = cumulation;
        if (buf != null) {
            cumulation = null;
            numReads = 0;
            /** 获取可读取的字节数 **/
            int readable = buf.readableBytes();
            /** 存在可读取的数据，读取数据到新的ByteBuf，触发Read，ReadComplete事件 **/
            if (readable > 0) {
                ByteBuf bytes = buf.readBytes(readable);
                buf.release();
                ctx.fireChannelRead(bytes);
                ctx.fireChannelReadComplete();
            } else {
                /** 释放cumulation **/
                buf.release();
            }
        }
        /** 执行移除逻辑 **/
        handlerRemoved0(ctx);
    }

    /**
     *  模板方法
     */
    protected void handlerRemoved0(ChannelHandlerContext ctx) throws Exception { }

    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
        /** 只对ByteBuf类型的消息做处理 **/
        if (msg instanceof ByteBuf) {
            /** 解码消息存储列表 **/
            CodecOutputList out = CodecOutputList.newInstance();
            try {
                ByteBuf data = (ByteBuf) msg;
                /** 通过判断cumulation是否为null来确定是否是首次读取， **/
                first = cumulation == null;
                /** 若首次，直接使用读取的 data **/
                if (first) {
                    cumulation = data;
                }
                /** 若非首次，将读取的 data ，累积到 cumulation 中 **/
                else {
                    cumulation = cumulator.cumulate(ctx.alloc(), cumulation, data);
                }
                /** 执行解码 **/
                callDecode(ctx, cumulation, out);
            } catch (DecoderException e) {
                /**  抛出 DecoderException 异常 **/
                throw e;
            } catch (Exception e) {
                /** 封装成 DecoderException 异常，抛出 **/
                throw new DecoderException(e);
            } finally {
                /**  cumulation 中所有数据被读取完，直接释放全部  **/
                if (cumulation != null && !cumulation.isReadable()) {
                    /**   重置 numReads 次数 **/
                    numReads = 0;
                    /**   释放 cumulation  **/
                    cumulation.release();
                    /**   置空 cumulation  **/
                    cumulation = null;
                }
                /**  读取次数到达 discardAfterReads 上限，释放部分的已读 **/
                else if (++ numReads >= discardAfterReads) {
                    /** 重置 numReads 次数 **/
                    numReads = 0;
                    /** 释放部分的已读 **/
                    discardSomeReadBytes();
                }

                /**  解码消息的数量 **/
                int size = out.size();

                firedChannelRead |= out.insertSinceRecycled();

                /** 触发 Channel Read 事件。 可能是多条消息 **/
                fireChannelRead(ctx, out, size);

                /**  回收 CodecOutputList 对象 **/
                out.recycle();
            }
        } else {
            /** 触发 Channel Read 事件到下一个节点 **/
            ctx.fireChannelRead(msg);
        }
    }

    /**
     *  触发 Channel Read 事件。可能是多条消息
     */
    static void fireChannelRead(ChannelHandlerContext ctx, List<Object> msgs, int numElements) {
        /** 如果是 CodecOutputList 类型，特殊优化 **/
        if (msgs instanceof CodecOutputList) {
            fireChannelRead(ctx, (CodecOutputList) msgs, numElements);
        } else {
            for (int i = 0; i < numElements; i++) {
                ctx.fireChannelRead(msgs.get(i));
            }
        }
    }

    /**
     * 触发 Channel Read 事件。可能是多条消息
     */
    static void fireChannelRead(ChannelHandlerContext ctx, CodecOutputList msgs, int numElements) {
        for (int i = 0; i < numElements; i ++) {
            /** getUnsafe 是自定义的方法，减少越界判断，效率更高 **/
            ctx.fireChannelRead(msgs.getUnsafe(i));
        }
    }

    @Override
    public void channelReadComplete(ChannelHandlerContext ctx) throws Exception {
        /** 重置 numReads **/
        numReads = 0;
        /** 释放部分的已读 **/
        discardSomeReadBytes();
        /** 未解码到消息，并且未开启自动读取，则再次发起读取，期望读取到更多数据，以便解码到消息 **/
        if (!firedChannelRead && !ctx.channel().config().isAutoRead()) {
            ctx.read();
        }
        /** 设置firedChannelRead **/
        firedChannelRead = false;
        /** 触发 Channel ReadComplete 事件到Pipline下一个节点 **/
        ctx.fireChannelReadComplete();
    }

    /**
     * 释放部分的已读
     */
    protected final void discardSomeReadBytes() {
        if (cumulation != null && !first && cumulation.refCnt() == 1) {
            cumulation.discardSomeReadBytes();
        }
    }

    @Override
    public void channelInactive(ChannelHandlerContext ctx) throws Exception {
        channelInputClosed(ctx, true);
    }

    /**
     * 处理 ChannelInputShutdownEvent 事件，即 Channel 关闭读取
     */
    @Override
    public void userEventTriggered(ChannelHandlerContext ctx, Object evt) throws Exception {
        if (evt instanceof ChannelInputShutdownEvent) {

            channelInputClosed(ctx, false);
        }
        super.userEventTriggered(ctx, evt);
    }

    private void channelInputClosed(ChannelHandlerContext ctx, boolean callChannelInactive) throws Exception {

        /** 创建 CodecOutputList 对象 **/
        CodecOutputList out = CodecOutputList.newInstance();
        try {
            /** 当 Channel 读取关闭时，执行解码剩余消息的逻辑 **/
            channelInputClosed(ctx, out);
        } catch (DecoderException e) {
            throw e;
        } catch (Exception e) {
            throw new DecoderException(e);
        } finally {
            try {
                /** 释放 cumulation **/
                if (cumulation != null) {
                    cumulation.release();
                    cumulation = null;
                }
                /** 触发 Channel Read 事件到下一个节点。可能是多条消息 **/
                int size = out.size();
                fireChannelRead(ctx, out, size);
                /**  如果有解码到消息，则触发 Channel ReadComplete 事件到下一个节点。 **/
                if (size > 0) {
                    ctx.fireChannelReadComplete();
                }
                /** 如果方法调用来源是 `#channelInactive(...)` ，则触发 Channel Inactive 事件到下一个节点 **/
                if (callChannelInactive) {
                    ctx.fireChannelInactive();
                }
            } finally {
                /** 回收 CodecOutputList 对象 **/
                out.recycle();
            }
        }
    }

    /**
     * Called when the input of the channel was closed which may be because it changed to inactive or because of
     * {@link ChannelInputShutdownEvent}.
     */
    void channelInputClosed(ChannelHandlerContext ctx, List<Object> out) throws Exception {
        if (cumulation != null) {
            callDecode(ctx, cumulation, out);
            decodeLast(ctx, cumulation, out);
        } else {
            decodeLast(ctx, Unpooled.EMPTY_BUFFER, out);
        }
    }

    /**
     * 执行解码。而解码的结果，会添加到 out 数组中
     */
    protected void callDecode(ChannelHandlerContext ctx, ByteBuf in, List<Object> out) {
        try {
            /** 循环读取，直到不可读 **/
            while (in.isReadable()) {
                /** 获取out 列表消息数量 **/
                int outSize = out.size();

                /** out列表非空，说明上一次循环解码有解码到消息 **/
                if (outSize > 0) {
                    /** 触发 Channel Read 事件  **/
                    fireChannelRead(ctx, out, outSize);
                    /** 清空 out 列表 **/
                    out.clear();

                    /** 如果当前用户主动删除ctx中Handel则跳过 **/
                    if (ctx.isRemoved()) {
                        break;
                    }
                    /** 重置outSize **/
                    outSize = 0;
                }

                /**  获取当前可读字节数  **/
                int oldInputLength = in.readableBytes();

                /** 执行解码。如果 Handler 准备移除，在解码完成后，进行移除。 **/
                decodeRemovalReentryProtection(ctx, in, out);

                /** 如果当前用户主动删除ctx中Handel则跳过 **/
                if (ctx.isRemoved()) {
                    break;
                }

                /** 整列判断 `out.size() == 0` 比较合适。因为，如果 `outSize > 0` 那段，已经清理了 out 。 **/
                if (outSize == out.size()) {
                    /** 如果未读取任何字节，结束循环 **/
                    if (oldInputLength == in.readableBytes()) {
                        break;
                    }
                    /** 如果可读字节发生变化，继续读取 **/
                    else {
                        continue;
                    }
                }

                /** 如果解码了消息，但是可读字节数未变，抛出 DecoderException 异常。说明，有问题。 **/
                if (oldInputLength == in.readableBytes()) {
                    throw new DecoderException(
                            StringUtil.simpleClassName(getClass()) +
                                    ".decode() did not read anything but decoded a message.");
                }

                /**  如果开启 singleDecode ，表示只解析一次，结束循环 **/
                if (isSingleDecode()) {
                    break;
                }
            }
        } catch (DecoderException e) {
            throw e;
        } catch (Exception cause) {
            throw new DecoderException(cause);
        }
    }

    /**
     * 解码模板方法，子类实现
     */
    protected abstract void decode(ChannelHandlerContext ctx, ByteBuf in, List<Object> out) throws Exception;

    /**
     * 执行解码。如果 Handler 准备移除，在解码完成后，进行移除
     */
    final void decodeRemovalReentryProtection(ChannelHandlerContext ctx, ByteBuf in, List<Object> out)
            throws Exception {
        /** 设置状态为 STATE_CALLING_CHILD_DECODE 标识当前正在进行解码 **/
        decodeState = STATE_CALLING_CHILD_DECODE;
        try {
            /** 执行解码 **/
            decode(ctx, in, out);
        } finally {
            /** 判断当前状态是否是准备移除 **/
            boolean removePending = decodeState == STATE_HANDLER_REMOVED_PENDING;
            /** 设置当前状态为初始化 **/
            decodeState = STATE_INIT;
            /** 移除当前 Handler **/
            if (removePending) {
                handlerRemoved(ctx);
            }
        }
    }

    /**
     * Is called one last time when the {@link ChannelHandlerContext} goes in-active. Which means the
     * {@link #channelInactive(ChannelHandlerContext)} was triggered.
     *
     * By default this will just call {@link #decode(ChannelHandlerContext, ByteBuf, List)} but sub-classes may
     * override this for some special cleanup operation.
     */
    protected void decodeLast(ChannelHandlerContext ctx, ByteBuf in, List<Object> out) throws Exception {
        if (in.isReadable()) {
            decodeRemovalReentryProtection(ctx, in, out);
        }
    }



    /**
     * ByteBuf 累积器接口
     */
    public interface Cumulator {
        /**
         * 将原有 cumulation 累加上新的 in ，返回“新”的 ByteBuf 对象，
         * 如果 in 过大，超过 cumulation 的空间上限，使用 alloc 进行扩容后再累加。
         *
         * @param alloc ByteBuf 分配器
         * @param cumulation ByteBuf 当前累积结果
         * @param in 当前读取( 输入 ) ByteBuf
         * @return ByteBuf 新的累积结果
         */
        ByteBuf cumulate(ByteBufAllocator alloc, ByteBuf cumulation, ByteBuf in);
    }

    /**
     * 不断使用老的 ByteBuf 累积。如果空间不够，扩容出新的 ByteBuf ，再继续进行累积
     */
    public static final Cumulator MERGE_CUMULATOR = new Cumulator() {
        @Override
        public ByteBuf cumulate(ByteBufAllocator alloc, ByteBuf cumulation, ByteBuf in) {
            try {
                final ByteBuf buffer;
                /** 判断是否进行扩容 三个条件，满足任意，需要进行扩容 **/
                /** 1 cumulation.writerIndex() > cumulation.maxCapacity() - in.readableBytes() ，
                 *  cumulation 可写如的容量小于in能否读取容量，需要扩容 **/
                /** 2 cumulation.refCnt() > 1 ，引用大于 1 ，说明用户使用了 ByteBuf#slice()#retain() 或 ByteBuf#duplicate()#retain() 方法，
                 * 使 refCnt 增加并且大于 1 。 **/
                /** 3 cumulation.isReadOnly() 只读，不可累加，所以需要改成可写 **/
                if (cumulation.writerIndex() > cumulation.maxCapacity() - in.readableBytes()
                        || cumulation.refCnt() > 1 || cumulation.isReadOnly()) {
                    /** 扩容，并返回新的，并赋值给 buffer **/
                    buffer = expandCumulation(alloc, cumulation, in.readableBytes());
                }
                /** 无需扩容 buffer 直接使用的 cumulation 对象 **/
                else {
                    buffer = cumulation;
                }
                /** 将in写入buffer **/
                buffer.writeBytes(in);
                return buffer;
            } finally {
                /** 释放 in **/
                in.release();
            }
        }
    };

    /**
     * 扩容
     */
    static ByteBuf expandCumulation(ByteBufAllocator alloc, ByteBuf cumulation, int readable) {
        /**  记录老的 ByteBuf 对象 **/
        ByteBuf oldCumulation = cumulation;
        /**  分配新的 ByteBuf 对象 **/
        cumulation = alloc.buffer(oldCumulation.readableBytes() + readable);
        /**  将老的数据，写入到新的 ByteBuf 对象 **/
        cumulation.writeBytes(oldCumulation);
        /**  释放老的 ByteBuf 对象 **/
        oldCumulation.release();
        /**  返回新的 ByteBuf 对象 **/
        return cumulation;
    }

    /**
     * 使用 CompositeByteBuf ，组合新输入的 ByteBuf 对象，从而避免内存拷贝
     * 相比 MERGE_CUMULATOR 来说：
     * 好处是，内存零拷贝
     * 坏处是，因为维护复杂索引，所以某些使用场景下，慢于 MERGE_CUMULATOR
     */
    public static final Cumulator COMPOSITE_CUMULATOR = new Cumulator() {
        @Override
        public ByteBuf cumulate(ByteBufAllocator alloc, ByteBuf cumulation, ByteBuf in) {
            ByteBuf buffer;
            try {
                /** 判断是否进行扩容  **/

                /** cumulation.refCnt() > 1 ，引用大于 1 ，说明用户使用了 ByteBuf#slice()#retain() 或 ByteBuf#duplicate()#retain() 方法，
                 * 使 refCnt 增加并且大于 1 。 **/
                if (cumulation.refCnt() > 1) {
                    /** 扩容，并返回新的，并赋值给 buffer **/
                    buffer = expandCumulation(alloc, cumulation, in.readableBytes());
                    buffer.writeBytes(in);
                } else {
                    CompositeByteBuf composite;
                    /** 判断cumulation类型是否是CompositeByteBuf，如果是直接使用 **/
                    if (cumulation instanceof CompositeByteBuf) {
                        composite = (CompositeByteBuf) cumulation;
                    }
                    /** 不是 CompositeByteBuf 类型，创建，并添加到其中 **/
                    else {
                        composite = alloc.compositeBuffer(Integer.MAX_VALUE);
                        composite.addComponent(true, cumulation);
                    }
                    /**  添加 in 到 composite 中  **/
                    composite.addComponent(true, in);
                    in = null;
                    /** 赋值给 buffer **/
                    buffer = composite;
                }
                /** 返回 buffer **/
                return buffer;
            } finally {
                /** 释放 in **/
                if (in != null) {
                    in.release();
                }
            }
        }
    };
}
