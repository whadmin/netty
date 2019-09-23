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
import static io.netty.util.internal.ObjectUtil.checkPositiveOrZero;

import java.nio.ByteOrder;
import java.util.List;

import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.serialization.ObjectDecoder;

/**
 * A decoder that splits the received {@link ByteBuf}s dynamically by the
 * value of the length field in the message.  It is particularly useful when you
 * decode a binary message which has an integer header field that represents the
 * length of the message body or the whole message.
 * <p>
 * {@link LengthFieldBasedFrameDecoder} has many configuration parameters so
 * that it can decode any message with a length field, which is often seen in
 * proprietary client-server protocols. Here are some example that will give
 * you the basic idea on which option does what.
 *
 * <h3>2 bytes length field at offset 0, do not strip header</h3>
 *
 * The value of the length field in this example is <tt>12 (0x0C)</tt> which
 * represents the length of "HELLO, WORLD".  By default, the decoder assumes
 * that the length field represents the number of the bytes that follows the
 * length field.  Therefore, it can be decoded with the simplistic parameter
 * combination.
 * <pre>
 * <b>lengthFieldOffset</b>   = <b>0</b>
 * <b>lengthFieldLength</b>   = <b>2</b>
 * lengthAdjustment    = 0
 * initialBytesToStrip = 0 (= do not strip header)
 *
 * BEFORE DECODE (14 bytes)         AFTER DECODE (14 bytes)
 * +--------+----------------+      +--------+----------------+
 * | Length | Actual Content |----->| Length | Actual Content |
 * | 0x000C | "HELLO, WORLD" |      | 0x000C | "HELLO, WORLD" |
 * +--------+----------------+      +--------+----------------+
 * </pre>
 *
 * <h3>2 bytes length field at offset 0, strip header</h3>
 *
 * Because we can get the length of the content by calling
 * {@link ByteBuf#readableBytes()}, you might want to strip the length
 * field by specifying <tt>initialBytesToStrip</tt>.  In this example, we
 * specified <tt>2</tt>, that is same with the length of the length field, to
 * strip the first two bytes.
 * <pre>
 * lengthFieldOffset   = 0
 * lengthFieldLength   = 2
 * lengthAdjustment    = 0
 * <b>initialBytesToStrip</b> = <b>2</b> (= the length of the Length field)
 *
 * BEFORE DECODE (14 bytes)         AFTER DECODE (12 bytes)
 * +--------+----------------+      +----------------+
 * | Length | Actual Content |----->| Actual Content |
 * | 0x000C | "HELLO, WORLD" |      | "HELLO, WORLD" |
 * +--------+----------------+      +----------------+
 * </pre>
 *
 * <h3>2 bytes length field at offset 0, do not strip header, the length field
 *     represents the length of the whole message</h3>
 *
 * In most cases, the length field represents the length of the message body
 * only, as shown in the previous examples.  However, in some protocols, the
 * length field represents the length of the whole message, including the
 * message header.  In such a case, we specify a non-zero
 * <tt>lengthAdjustment</tt>.  Because the length value in this example message
 * is always greater than the body length by <tt>2</tt>, we specify <tt>-2</tt>
 * as <tt>lengthAdjustment</tt> for compensation.
 * <pre>
 * lengthFieldOffset   =  0
 * lengthFieldLength   =  2
 * <b>lengthAdjustment</b>    = <b>-2</b> (= the length of the Length field)
 * initialBytesToStrip =  0
 *
 * BEFORE DECODE (14 bytes)         AFTER DECODE (14 bytes)
 * +--------+----------------+      +--------+----------------+
 * | Length | Actual Content |----->| Length | Actual Content |
 * | 0x000E | "HELLO, WORLD" |      | 0x000E | "HELLO, WORLD" |
 * +--------+----------------+      +--------+----------------+
 * </pre>
 *
 * <h3>3 bytes length field at the end of 5 bytes header, do not strip header</h3>
 *
 * The following message is a simple variation of the first example.  An extra
 * header value is prepended to the message.  <tt>lengthAdjustment</tt> is zero
 * again because the decoder always takes the length of the prepended data into
 * account during frame length calculation.
 * <pre>
 * <b>lengthFieldOffset</b>   = <b>2</b> (= the length of Header 1)
 * <b>lengthFieldLength</b>   = <b>3</b>
 * lengthAdjustment    = 0
 * initialBytesToStrip = 0
 *
 * BEFORE DECODE (17 bytes)                      AFTER DECODE (17 bytes)
 * +----------+----------+----------------+      +----------+----------+----------------+
 * | Header 1 |  Length  | Actual Content |----->| Header 1 |  Length  | Actual Content |
 * |  0xCAFE  | 0x00000C | "HELLO, WORLD" |      |  0xCAFE  | 0x00000C | "HELLO, WORLD" |
 * +----------+----------+----------------+      +----------+----------+----------------+
 * </pre>
 *
 * <h3>3 bytes length field at the beginning of 5 bytes header, do not strip header</h3>
 *
 * This is an advanced example that shows the case where there is an extra
 * header between the length field and the message body.  You have to specify a
 * positive <tt>lengthAdjustment</tt> so that the decoder counts the extra
 * header into the frame length calculation.
 * <pre>
 * lengthFieldOffset   = 0
 * lengthFieldLength   = 3
 * <b>lengthAdjustment</b>    = <b>2</b> (= the length of Header 1)
 * initialBytesToStrip = 0
 *
 * BEFORE DECODE (17 bytes)                      AFTER DECODE (17 bytes)
 * +----------+----------+----------------+      +----------+----------+----------------+
 * |  Length  | Header 1 | Actual Content |----->|  Length  | Header 1 | Actual Content |
 * | 0x00000C |  0xCAFE  | "HELLO, WORLD" |      | 0x00000C |  0xCAFE  | "HELLO, WORLD" |
 * +----------+----------+----------------+      +----------+----------+----------------+
 * </pre>
 *
 * <h3>2 bytes length field at offset 1 in the middle of 4 bytes header,
 *     strip the first header field and the length field</h3>
 *
 * This is a combination of all the examples above.  There are the prepended
 * header before the length field and the extra header after the length field.
 * The prepended header affects the <tt>lengthFieldOffset</tt> and the extra
 * header affects the <tt>lengthAdjustment</tt>.  We also specified a non-zero
 * <tt>initialBytesToStrip</tt> to strip the length field and the prepended
 * header from the frame.  If you don't want to strip the prepended header, you
 * could specify <tt>0</tt> for <tt>initialBytesToSkip</tt>.
 * <pre>
 * lengthFieldOffset   = 1 (= the length of HDR1)
 * lengthFieldLength   = 2
 * <b>lengthAdjustment</b>    = <b>1</b> (= the length of HDR2)
 * <b>initialBytesToStrip</b> = <b>3</b> (= the length of HDR1 + LEN)
 *
 * BEFORE DECODE (16 bytes)                       AFTER DECODE (13 bytes)
 * +------+--------+------+----------------+      +------+----------------+
 * | HDR1 | Length | HDR2 | Actual Content |----->| HDR2 | Actual Content |
 * | 0xCA | 0x000C | 0xFE | "HELLO, WORLD" |      | 0xFE | "HELLO, WORLD" |
 * +------+--------+------+----------------+      +------+----------------+
 * </pre>
 *
 * <h3>2 bytes length field at offset 1 in the middle of 4 bytes header,
 *     strip the first header field and the length field, the length field
 *     represents the length of the whole message</h3>
 *
 * Let's give another twist to the previous example.  The only difference from
 * the previous example is that the length field represents the length of the
 * whole message instead of the message body, just like the third example.
 * We have to count the length of HDR1 and Length into <tt>lengthAdjustment</tt>.
 * Please note that we don't need to take the length of HDR2 into account
 * because the length field already includes the whole header length.
 * <pre>
 * lengthFieldOffset   =  1
 * lengthFieldLength   =  2
 * <b>lengthAdjustment</b>    = <b>-3</b> (= the length of HDR1 + LEN, negative)
 * <b>initialBytesToStrip</b> = <b> 3</b>
 *
 * BEFORE DECODE (16 bytes)                       AFTER DECODE (13 bytes)
 * +------+--------+------+----------------+      +------+----------------+
 * | HDR1 | Length | HDR2 | Actual Content |----->| HDR2 | Actual Content |
 * | 0xCA | 0x0010 | 0xFE | "HELLO, WORLD" |      | 0xFE | "HELLO, WORLD" |
 * +------+--------+------+----------------+      +------+----------------+
 * </pre>
 * @see LengthFieldPrepender
 */
public class LengthFieldBasedFrameDecoder extends ByteToMessageDecoder {

    private final ByteOrder byteOrder;

    /**
     * 单个数据包最大的长度，超出包的最大长度会做一些特殊处理
     */
    private final int maxFrameLength;

    /**
     * 长度字段开始偏移量,即在一个数据包中长度字段开始的具体下标位置
     */
    private final int lengthFieldOffset;

    /**
     * 表示长度字段的具体字节数
     */
    private final int lengthFieldLength;

    /**
     * 长度字段结束偏移量,即在一个数据包中长度字段结束的具体下标位置
     */
    private final int lengthFieldEndOffset;

    /**
     * 是一个长度调节量，用于包长度包含了协议表头
     */
    private final int lengthAdjustment;

    /**
     * 表示一个完整的数据包,传递给下一个节点，跳过忽略的字节大小
     */
    private final int initialBytesToStrip;

    /**
     * 是否快速失败
     */
    private final boolean failFast;


    /**
     * 是否进入丢弃模式
     */
    private boolean discardingTooLongFrame;

    /**
     * 最近一次超过maxFrameLength，数据包的长度
     */
    private long tooLongFrameLength;

    /**
     * 当前读取ByteBuf 需要丢弃的字节数
     */
    private long bytesToDiscard;

    /**
     * 实例化 LengthFieldBasedFrameDecoder
     */
    public LengthFieldBasedFrameDecoder(
            int maxFrameLength,
            int lengthFieldOffset, int lengthFieldLength) {
        this(maxFrameLength, lengthFieldOffset, lengthFieldLength, 0, 0);
    }

    /**
     * 实例化 LengthFieldBasedFrameDecoder
     */
    public LengthFieldBasedFrameDecoder(
            int maxFrameLength,
            int lengthFieldOffset, int lengthFieldLength,
            int lengthAdjustment, int initialBytesToStrip) {
        this(
                maxFrameLength,
                lengthFieldOffset, lengthFieldLength, lengthAdjustment,
                initialBytesToStrip, true);
    }

    /**
     * 实例化 LengthFieldBasedFrameDecoder
     */
    public LengthFieldBasedFrameDecoder(
            int maxFrameLength, int lengthFieldOffset, int lengthFieldLength,
            int lengthAdjustment, int initialBytesToStrip, boolean failFast) {
        this(
                ByteOrder.BIG_ENDIAN, maxFrameLength, lengthFieldOffset, lengthFieldLength,
                lengthAdjustment, initialBytesToStrip, failFast);
    }

    /**
     * 实例化 LengthFieldBasedFrameDecoder
     */
    public LengthFieldBasedFrameDecoder(
            ByteOrder byteOrder, int maxFrameLength, int lengthFieldOffset, int lengthFieldLength,
            int lengthAdjustment, int initialBytesToStrip, boolean failFast) {
        if (byteOrder == null) {
            throw new NullPointerException("byteOrder");
        }

        checkPositive(maxFrameLength, "maxFrameLength");

        checkPositiveOrZero(lengthFieldOffset, "lengthFieldOffset");

        checkPositiveOrZero(initialBytesToStrip, "initialBytesToStrip");

        if (lengthFieldOffset > maxFrameLength - lengthFieldLength) {
            throw new IllegalArgumentException(
                    "maxFrameLength (" + maxFrameLength + ") " +
                    "must be equal to or greater than " +
                    "lengthFieldOffset (" + lengthFieldOffset + ") + " +
                    "lengthFieldLength (" + lengthFieldLength + ").");
        }

        this.byteOrder = byteOrder;
        this.maxFrameLength = maxFrameLength;
        this.lengthFieldOffset = lengthFieldOffset;
        this.lengthFieldLength = lengthFieldLength;
        this.lengthAdjustment = lengthAdjustment;
        lengthFieldEndOffset = lengthFieldOffset + lengthFieldLength;
        this.initialBytesToStrip = initialBytesToStrip;
        this.failFast = failFast;
    }

    /**
     * 实现父类ByteToMessageDecoder 模板方法，对字节流基于指定消息边界方式，并将解码的消息放入out列表中
     */
    @Override
    protected final void decode(ChannelHandlerContext ctx, ByteBuf in, List<Object> out) throws Exception {
        Object decoded = decode(ctx, in);
        if (decoded != null) {
            out.add(decoded);
        }
    }

    protected Object decode(ChannelHandlerContext ctx, ByteBuf in) throws Exception {
        /** 1 获取需要待拆包的包大小  **/
        if (discardingTooLongFrame) {
            discardingTooLongFrame(in);
        }

        /** 如果当前可读字节还未达到长度长度域的偏移，那说明肯定是读不到长度域的，直接不读 **/
        if (in.readableBytes() < lengthFieldEndOffset) {
            return null;
        }

        /**  拿到长度字段的实际字节偏移位置 **/
        int actualLengthFieldOffset = in.readerIndex() + lengthFieldOffset;

        /**  拿到实际包长度字段具体值，这里长度值只包含包数据的长度 (未调整过的)**/
        long frameLength = getUnadjustedFrameLength(in, actualLengthFieldOffset, lengthFieldLength, byteOrder);

        /**  如果拿到的包数据的长度为负数，直接跳过长度域并抛出异常 **/
        if (frameLength < 0) {
            failOnNegativeLengthField(in, frameLength, lengthFieldEndOffset);
        }

        /**  计算包长度值 （经过调整的），这里调整值通常表示长度字段后除了包数据外还有表头数据，调整值未表头数据大小 **/
        frameLength += lengthAdjustment + lengthFieldEndOffset;

        /**  整个数据包的长度还没有长度域长，直接抛出异常 **/
        if (frameLength < lengthFieldEndOffset) {
            failOnFrameLengthLessThanLengthFieldEndOffset(in, frameLength, lengthFieldEndOffset);
        }

        /** 数据包长度超出最大包长度，进入丢弃模式 **/
        if (frameLength > maxFrameLength) {
            exceededFrameLength(in, frameLength);
            return null;
        }

        /** 当前in 能否读取完整数据包，如果不能直接返回 **/
        int frameLengthInt = (int) frameLength;
        if (in.readableBytes() < frameLengthInt) {
            return null;
        }

        /** 跳过的字节不能大于数据包的长度，否则就抛出 CorruptedFrameException 的异常 **/
        if (initialBytesToStrip > frameLengthInt) {
            failOnFrameLengthLessThanInitialBytesToStrip(in, frameLength, initialBytesToStrip);
        }

        /** 跳过initialBytesToStrip字节大小 **/
        in.skipBytes(initialBytesToStrip);

        /** 记录readerIndex **/
        int readerIndex = in.readerIndex();
        /** 计算排除掉忽略后读取数据包长度**/
        int actualFrameLength = frameLengthInt - initialBytesToStrip;
        /** 读取数据包 **/
        ByteBuf frame = extractFrame(ctx, in, readerIndex, actualFrameLength);
        /** 设置readerIndex **/
        in.readerIndex(readerIndex + actualFrameLength);
        return frame;
    }

    /**
     * 为了丢弃当前数据包，进入丢弃模式
     */
    private void exceededFrameLength(ByteBuf in, long frameLength) {
        /**
         * 计算得出当前丢弃包数据是否完整包含在ByteBuf in中，
         * 如果不是，计算下一个ByteBuf in中当前包数据占用的字节大小，即需要丢弃的字节大小
         */
        long discard = frameLength - in.readableBytes();

        /** 记录最近一次超过最大长度的数据包大小**/
        tooLongFrameLength = frameLength;

        /** 丢弃包数据完整包含在ByteBuf in中 **/
        if (discard < 0) {
            /** in直接跳过frameLength字节长度 **/
            in.skipBytes((int) frameLength);
        }
        /** 丢弃包数据数据在当前ByteBuf in中只包含一部分，其他部分包含在下一个ByteBuf in中 **/
        else {
            /** 进入丢弃模式 **/
            discardingTooLongFrame = true;
            /** 保存下一个ByteBuf in中当前包数据占用的字节大小，即需要丢弃的字节大小**/
            bytesToDiscard = discard;
            /** in忽略跳过所有字节 **/
            in.skipBytes(in.readableBytes());
        }
        failIfNecessary(true);
    }



    /**
     * 读取buffer指定数据，并返回
     */
    protected ByteBuf extractFrame(ChannelHandlerContext ctx, ByteBuf buffer, int index, int length) {
        return buffer.retainedSlice(index, length);
    }

    private void discardingTooLongFrame(ByteBuf in) {
        long bytesToDiscard = this.bytesToDiscard;
        int localBytesToDiscard = (int) Math.min(bytesToDiscard, in.readableBytes());
        in.skipBytes(localBytesToDiscard);
        bytesToDiscard -= localBytesToDiscard;
        this.bytesToDiscard = bytesToDiscard;

        failIfNecessary(false);
    }

    private void failIfNecessary(boolean firstDetectionOfTooLongFrame) {
        /** 下一个ByteBuf in中，需要丢弃的字节大小为0 **/
        if (bytesToDiscard == 0) {
            /** 重置tooLongFrameLength **/
            long tooLongFrameLength = this.tooLongFrameLength;
            this.tooLongFrameLength = 0;
            /** 设置不在进入丢弃模式 **/
            discardingTooLongFrame = false;
            /** 如果没有设置快速失败，或者设置了快速失败并且是第一次检测到大包错误，抛出异常，让handler去处理 **/
            if (!failFast || firstDetectionOfTooLongFrame) {
                fail(tooLongFrameLength);
            }
        } else {
            /**  如果设置了快速失败，并且是第一次检测到打包错误，抛出异常，让handler去处理 **/
            if (failFast && firstDetectionOfTooLongFrame) {
                fail(tooLongFrameLength);
            }
        }
    }



    private void fail(long frameLength) {
        if (frameLength > 0) {
            throw new TooLongFrameException(
                    "Adjusted frame length exceeds " + maxFrameLength +
                            ": " + frameLength + " - discarded");
        } else {
            throw new TooLongFrameException(
                    "Adjusted frame length exceeds " + maxFrameLength +
                            " - discarding");
        }
    }

    /**
     * 直接跳过长度域并抛出异常
     */
    private static void failOnNegativeLengthField(ByteBuf in, long frameLength, int lengthFieldEndOffset) {
        in.skipBytes(lengthFieldEndOffset);
        throw new CorruptedFrameException(
           "negative pre-adjustment length field: " + frameLength);
    }

    /**
     * 整个数据包的长度还没有长度域长，直接抛出异常
     */
    private static void failOnFrameLengthLessThanLengthFieldEndOffset(ByteBuf in,
                                                                      long frameLength,
                                                                      int lengthFieldEndOffset) {
        in.skipBytes(lengthFieldEndOffset);
        throw new CorruptedFrameException(
           "Adjusted frame length (" + frameLength + ") is less " +
              "than lengthFieldEndOffset: " + lengthFieldEndOffset);
    }


    /**
     * 跳过的字节不能大于数据包的长度，否则就抛出 CorruptedFrameException 的异常
     * **/
    private static void failOnFrameLengthLessThanInitialBytesToStrip(ByteBuf in,
                                                                     long frameLength,
                                                                     int initialBytesToStrip) {
        in.skipBytes((int) frameLength);
        throw new CorruptedFrameException(
           "Adjusted frame length (" + frameLength + ") is less " +
              "than initialBytesToStrip: " + initialBytesToStrip);
    }



    /**
     * 读取数据包长度字段的具体值，并返回
     *
     * @throws DecoderException if failed to decode the specified region
     */
    protected long getUnadjustedFrameLength(ByteBuf buf, int offset, int length, ByteOrder order) {
        buf = buf.order(order);
        long frameLength;
        switch (length) {
        case 1:
            frameLength = buf.getUnsignedByte(offset);
            break;
        case 2:
            frameLength = buf.getUnsignedShort(offset);
            break;
        case 3:
            frameLength = buf.getUnsignedMedium(offset);
            break;
        case 4:
            frameLength = buf.getUnsignedInt(offset);
            break;
        case 8:
            frameLength = buf.getLong(offset);
            break;
        default:
            throw new DecoderException(
                    "unsupported lengthFieldLength: " + lengthFieldLength + " (expected: 1, 2, 3, 4, or 8)");
        }
        return frameLength;
    }


}
