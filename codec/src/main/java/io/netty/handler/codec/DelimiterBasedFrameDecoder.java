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
import io.netty.channel.ChannelHandlerContext;

import java.util.List;

/**
 * 基于指定分隔符进行粘包拆包处理的
 *
 * <h3>Predefined delimiters</h3>
 * <p>
 * {@link Delimiters} defines frequently used delimiters for convenience' sake.
 *
 * <h3>Specifying more than one delimiter</h3>
 * <p>
 * {@link DelimiterBasedFrameDecoder} allows you to specify more than one
 * delimiter.  If more than one delimiter is found in the buffer, it chooses
 * the delimiter which produces the shortest frame.  For example, if you have
 * the following data in the buffer:
 * <pre>
 * +--------------+
 * | ABC\nDEF\r\n |
 * +--------------+
 * </pre>
 * a {@link DelimiterBasedFrameDecoder}({@link Delimiters#lineDelimiter() Delimiters.lineDelimiter()})
 * will choose {@code '\n'} as the first delimiter and produce two frames:
 * <pre>
 * +-----+-----+
 * | ABC | DEF |
 * +-----+-----+
 * </pre>
 * rather than incorrectly choosing {@code '\r\n'} as the first delimiter:
 * <pre>
 * +----------+
 * | ABC\nDEF |
 * +----------+
 * </pre>
 */
public class DelimiterBasedFrameDecoder extends ByteToMessageDecoder {

    /**
     * 描述分隔符ByteBuf数组
     */
    private final ByteBuf[] delimiters;

    /**
     * 单个数据包最大的长度
     */
    private final int maxFrameLength;

    /**
     * 解码后的消息是否去除分隔符。
     */
    private final boolean stripDelimiter;

    /**
     * 是否快速失败
     * 当 true 时，未找到消息，但是超过最大长度，则马上触发 Exception 到下一个节点
     * 当 false 时，未找到消息，但是超过最大长度，需要匹配到一条消息后，再触发 Exception 到下一个节点
     */
    private final boolean failFast;

    /**
     * 是否进入丢弃模式
     */
    private boolean discardingTooLongFrame;

    private int tooLongFrameLength;

    /** 仅在使用“ \ n”和“ \ r \ n”作为分隔符进行解码时设置.  */
    private final LineBasedFrameDecoder lineBasedDecoder;

    /**
     * 实例化 DelimiterBasedFrameDecoder
     */
    public DelimiterBasedFrameDecoder(int maxFrameLength, ByteBuf delimiter) {
        this(maxFrameLength, true, delimiter);
    }

    /**
     * 实例化 DelimiterBasedFrameDecoder
     */
    public DelimiterBasedFrameDecoder(
            int maxFrameLength, boolean stripDelimiter, ByteBuf delimiter) {
        this(maxFrameLength, stripDelimiter, true, delimiter);
    }

    /**
     * 实例化 DelimiterBasedFrameDecoder
     */
    public DelimiterBasedFrameDecoder(
            int maxFrameLength, boolean stripDelimiter, boolean failFast,
            ByteBuf delimiter) {
        this(maxFrameLength, stripDelimiter, failFast, new ByteBuf[] {
                delimiter.slice(delimiter.readerIndex(), delimiter.readableBytes())});
    }

    /**
     * 实例化 DelimiterBasedFrameDecoder
     */
    public DelimiterBasedFrameDecoder(int maxFrameLength, ByteBuf... delimiters) {
        this(maxFrameLength, true, delimiters);
    }

    /**
     * 实例化 DelimiterBasedFrameDecoder
     */
    public DelimiterBasedFrameDecoder(
            int maxFrameLength, boolean stripDelimiter, ByteBuf... delimiters) {
        this(maxFrameLength, stripDelimiter, true, delimiters);
    }

    /**
     * 实例化 DelimiterBasedFrameDecoder
     */
    public DelimiterBasedFrameDecoder(
            int maxFrameLength, boolean stripDelimiter, boolean failFast, ByteBuf... delimiters) {
        validateMaxFrameLength(maxFrameLength);
        if (delimiters == null) {
            throw new NullPointerException("delimiters");
        }
        if (delimiters.length == 0) {
            throw new IllegalArgumentException("empty delimiters");
        }
        /** 如果使用“ \ n”和“ \ r \ n”换行作为包数据分隔符。 且当前类是DelimiterBasedFrameDecoder子类 */
        if (isLineBased(delimiters) && !isSubclass()) {
            /** 实例化LineBasedFrameDecoder，作为换行符进行粘包拆包处理器 **/
            lineBasedDecoder = new LineBasedFrameDecoder(maxFrameLength, stripDelimiter, failFast);
            /** 设置分隔符 delimiters 为null，使用lineBasedDecoder进行粘包拆包处理 **/
            this.delimiters = null;
        }
        /** 初始化delimiters进行粘包拆包处理 **/
        else {
            this.delimiters = new ByteBuf[delimiters.length];
            for (int i = 0; i < delimiters.length; i ++) {
                ByteBuf d = delimiters[i];
                validateDelimiter(d);
                this.delimiters[i] = d.slice(d.readerIndex(), d.readableBytes());
            }
            lineBasedDecoder = null;
        }
        this.maxFrameLength = maxFrameLength;
        this.stripDelimiter = stripDelimiter;
        this.failFast = failFast;
    }

    /** 是否使用“ \ n”和“ \ r \ n”换行作为包数据分隔符。  */
    private static boolean isLineBased(final ByteBuf[] delimiters) {
        if (delimiters.length != 2) {
            return false;
        }
        ByteBuf a = delimiters[0];
        ByteBuf b = delimiters[1];
        if (a.capacity() < b.capacity()) {
            a = delimiters[1];
            b = delimiters[0];
        }
        return a.capacity() == 2 && b.capacity() == 1
                && a.getByte(0) == '\r' && a.getByte(1) == '\n'
                && b.getByte(0) == '\n';
    }

    /**
     * 前类是DelimiterBasedFrameDecoder子类
     */
    private boolean isSubclass() {
        return getClass() != DelimiterBasedFrameDecoder.class;
    }

    /**
     * 实现父类ByteToMessageDecoder 模板方法，对字节流基于分隔符解码，并将解码的消息放入out列表中
     */
    @Override
    protected final void decode(ChannelHandlerContext ctx, ByteBuf in, List<Object> out) throws Exception {
        Object decoded = decode(ctx, in);
        if (decoded != null) {
            out.add(decoded);
        }
    }

    /**
     *
     */
    protected Object decode(ChannelHandlerContext ctx, ByteBuf buffer) throws Exception {
        if (lineBasedDecoder != null) {
            return lineBasedDecoder.decode(ctx, buffer);
        }
        // Try all delimiters and choose the delimiter which yields the shortest frame.
        int minFrameLength = Integer.MAX_VALUE;
        ByteBuf minDelim = null;

        /** 遍历所有分隔符，判断是否在buffer是否存在分隔符 **/
        for (ByteBuf delim: delimiters) {
            /** 返回buffer中出现分隔符的起始位置*/
            int frameLength = indexOf(buffer, delim);
            /** 如果匹配到分隔符 **/
            if (frameLength >= 0 && frameLength < minFrameLength) {
                /** 记录分隔符的起始位置 **/
                minFrameLength = frameLength;
                /** 记录分隔符到变量 minDelim **/
                minDelim = delim;
            }
        }

        /** 匹配到分隔符 **/
        if (minDelim != null) {
            int minDelimLength = minDelim.capacity();
            ByteBuf frame;

            /** 如果进入丢弃模式 **/
            if (discardingTooLongFrame) {
                /** 设置不在进入丢弃模式 **/
                discardingTooLongFrame = false;
                /** 跳过minFrameLength + minDelimLength个字节，这些字节信息为上个丢弃包中的数据 **/
                buffer.skipBytes(minFrameLength + minDelimLength);

                /** 重置tooLongFrameLength **/
                int tooLongFrameLength = this.tooLongFrameLength;
                this.tooLongFrameLength = 0;

                /** 如果设置快速失败为false,抛出异常 **/
                if (!failFast) {
                    fail(tooLongFrameLength);
                }
                return null;
            }

            /** 分隔符的起始位置超过最大长度 **/
            if (minFrameLength > maxFrameLength) {
                /** buffer 跳过（丢弃）minFrameLength+ minDelimLength 个字节**/
                buffer.skipBytes(minFrameLength + minDelimLength);
                /** 超过包最大长度，失败，抛出异常 **/
                fail(minFrameLength);
                return null;
            }
            /** 根据是否去除分隔符解码buffer frame**/
            if (stripDelimiter) {
                frame = buffer.readRetainedSlice(minFrameLength);
                buffer.skipBytes(minDelimLength);
            } else {
                frame = buffer.readRetainedSlice(minFrameLength + minDelimLength);
            }
            /** 返回buffer frame **/
            return frame;
        }
        /** 未匹配到分隔符 **/
        else {
            /** 如果未进入丢弃模式 **/
            if (!discardingTooLongFrame) {
                /** 如果buffer中可读取字节数大于最大包长度，即包长度超过最大包长度 **/
                if (buffer.readableBytes() > maxFrameLength) {
                    /** 丢弃buffer中数据 **/
                    tooLongFrameLength = buffer.readableBytes();
                    buffer.skipBytes(buffer.readableBytes());
                    /** 进入丢弃模式 **/
                    discardingTooLongFrame = true;
                    /** 如果设置快速失败为tue,抛出异常 **/
                    if (failFast) {
                        fail(tooLongFrameLength);
                    }
                }
            }
            /** 如果进入丢弃模式 **/
            else {
                /** 丢弃buffer中数据 **/
                tooLongFrameLength += buffer.readableBytes();
                buffer.skipBytes(buffer.readableBytes());
            }
            /** 返回null **/
            return null;
        }
    }

    /**
     * 超过包最大长度，失败，抛出异常
     */
    private void fail(long frameLength) {
        if (frameLength > 0) {
            throw new TooLongFrameException(
                            "frame length exceeds " + maxFrameLength +
                            ": " + frameLength + " - discarded");
        } else {
            throw new TooLongFrameException(
                            "frame length exceeds " + maxFrameLength +
                            " - discarding");
        }
    }

    /**
     * 返回haystack是否包含needle对应的匹配符，返回匹配needle在haystack中所在的位置
     */
    private static int indexOf(ByteBuf haystack, ByteBuf needle) {
        for (int i = haystack.readerIndex(); i < haystack.writerIndex(); i ++) {
            int haystackIndex = i;
            int needleIndex;
            for (needleIndex = 0; needleIndex < needle.capacity(); needleIndex ++) {
                if (haystack.getByte(haystackIndex) != needle.getByte(needleIndex)) {
                    break;
                } else {
                    haystackIndex ++;
                    if (haystackIndex == haystack.writerIndex() &&
                        needleIndex != needle.capacity() - 1) {
                        return -1;
                    }
                }
            }

            if (needleIndex == needle.capacity()) {
                // Found the needle from the haystack!
                return i - haystack.readerIndex();
            }
        }
        return -1;
    }

    private static void validateDelimiter(ByteBuf delimiter) {
        if (delimiter == null) {
            throw new NullPointerException("delimiter");
        }
        if (!delimiter.isReadable()) {
            throw new IllegalArgumentException("empty delimiter");
        }
    }

    private static void validateMaxFrameLength(int maxFrameLength) {
        checkPositive(maxFrameLength, "maxFrameLength");
    }
}
