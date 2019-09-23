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

import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.util.ByteProcessor;

import java.util.List;

/**
 * 基于换行来进行消息粘包拆包处理的。
 * 它会处理 "\n" 和 "\r\n" 两种换行符。
 */
public class LineBasedFrameDecoder extends ByteToMessageDecoder {

    /** 一条消息的最大长度  */
    private final int maxLength;

    /**
     * 是否快速失败
     * 当 true 时，未找到消息，但是超过最大长度，则马上触发 Exception 到下一个节点
     * 当 false 时，未找到消息，但是超过最大长度，需要匹配到一条消息后，再触发 Exception 到下一个节点
     * */
    private final boolean failFast;

    /**
     * 是否过滤掉换行分隔符。
     * 如果为 true ，解码的消息不包含换行符。
     */
    private final boolean stripDelimiter;

    /**
     * 是否处于废弃模式
     * 如果为 true ，说明解析超过最大长度( maxLength )，结果还是找不到换行符
     */
    private boolean discarding;

    /**
     * 废弃的字节数
     */
    private int discardedBytes;

    /**
     * 最后扫描的位置
     */
    private int offset;

    /**
     * 实例化LineBasedFrameDecoder，设置最大长度
     */
    public LineBasedFrameDecoder(final int maxLength) {
        this(maxLength, true, false);
    }

    /**
     * 实例化LineBasedFrameDecoder，设置最大长度,是否过滤掉换行分隔符,是否处于废弃模式
     */
    public LineBasedFrameDecoder(final int maxLength, final boolean stripDelimiter, final boolean failFast) {
        this.maxLength = maxLength;
        this.failFast = failFast;
        this.stripDelimiter = stripDelimiter;
    }

    /**
     * 实现父类ByteToMessageDecoder 模板方法，对字节流基于换行解码，并将解码的消息放入out列表中
     */
    @Override
    protected final void decode(ChannelHandlerContext ctx, ByteBuf in, List<Object> out) throws Exception {
        Object decoded = decode(ctx, in);
        if (decoded != null) {
            out.add(decoded);
        }
    }

    /**
     * 执行解码
     */
    protected Object decode(ChannelHandlerContext ctx, ByteBuf buffer) throws Exception {
        /** 获得换行符的位置 **/
        final int eol = findEndOfLine(buffer);
        /** 未处于废弃模式 **/
        if (!discarding) {
            /** 找到换行位置 **/
            if (eol >= 0) {
                final ByteBuf frame;
                /** 计算可以读取的长度 **/
                final int length = eol - buffer.readerIndex();
                /** 计算换行符长度，对于"\r\n"长度为2， "\n" 长度为1  **/
                final int delimLength = buffer.getByte(eol) == '\r'? 2 : 1;

                /** 可读取的长度超过最大长度 **/
                if (length > maxLength) {
                    /**  设置新的readerIndex **/
                    buffer.readerIndex(eol + delimLength);
                    /**  触发 Exception 事件传递到下一个节点 **/
                    fail(ctx, length);
                    /** 返回 null ，即未解码到消息 **/
                    return null;
                }

                /**
                 * 判断是否过滤掉换行分隔符。
                 * 解码出一条消息。设置到frame
                 * **/
                if (stripDelimiter) {
                    frame = buffer.readRetainedSlice(length);
                    buffer.skipBytes(delimLength);
                } else {
                    frame = buffer.readRetainedSlice(length + delimLength);
                }
                /** 返回解码的消息 **/
                return frame;
            }
            /** 未找到换行符 **/
            else {
                /** 获取能够读取最大字节数 **/
                final int length = buffer.readableBytes();
                /** 可读取的长度超过最大长度 **/
                if (length > maxLength) {
                    /**  记录 discardedBytes **/
                    discardedBytes = length;
                    /**  跳到写入位置 **/
                    buffer.readerIndex(buffer.writerIndex());
                    /**  标记 discarding 为废弃模式 **/
                    discarding = true;
                    /**  重置 offset **/
                    offset = 0;
                    /**  如果快速失败，则触发 Exception 到下一个节点 **/
                    if (failFast) {
                        fail(ctx, "over " + discardedBytes);
                    }
                }
                return null;
            }
        }
        /** 处于废弃模式 **/
        else {
            /** 找到换行位置 **/
            if (eol >= 0) {
                /** 计算可以读取的长度 **/
                final int length = discardedBytes + eol - buffer.readerIndex();
                /** 计算换行符长度，对于"\r\n"长度为2， "\n" 长度为1  **/
                final int delimLength = buffer.getByte(eol) == '\r'? 2 : 1;
                /**  设置新的readerIndex **/
                buffer.readerIndex(eol + delimLength);
                /**  记录 discardedBytes **/
                discardedBytes = 0;
                /** 设置 discarding 不为废弃模式 **/
                discarding = false;
                /** 如果不为快速失败，则触发 Exception 到下一个节点 **/
                if (!failFast) {
                    fail(ctx, length);
                }
            }
            /** 未找到换行位置 **/
            else {
                /**  增加 discardedBytes  **/
                discardedBytes += buffer.readableBytes();
                /**  跳到写入位置  **/
                buffer.readerIndex(buffer.writerIndex());
                /**  重置 offset **/
                offset = 0;
            }
            return null;
        }
    }

    /**
     * 触发 Exception 事件 传递到下一个节点
     */
    private void fail(final ChannelHandlerContext ctx, int length) {
        fail(ctx, String.valueOf(length));
    }

    /**
     * 触发 Exception 事件 传递到下一个节点
     */
    private void fail(final ChannelHandlerContext ctx, String length) {
        ctx.fireExceptionCaught(
                new TooLongFrameException(
                        "frame length (" + length + ") exceeds the allowed maximum (" + maxLength + ')'));
    }

    /**
     * 获得换行符的位置
     */
    private int findEndOfLine(final ByteBuf buffer) {
        int totalLength = buffer.readableBytes();
        /** 从buffer.readerIndex() + offset 位置开始查找换行出现的位置 **/
        int i = buffer.forEachByte(buffer.readerIndex() + offset, totalLength - offset, ByteProcessor.FIND_LF);
        /**  i >= 0 表示找到换行出现的位置 **/
        if (i >= 0) {
            /** 重置 offset  **/
            offset = 0;
            /** 如果前一个字节位 `\r` ，说明找到的是 `\r\n` 换行，换行找到位置-1  **/
            if (i > 0 && buffer.getByte(i - 1) == '\r') {
                i--;
            }
        }
        /** 未找到，记录 offset **/
        else {
            offset = totalLength;
        }
        /** 返回位置i**/
        return i;
    }
}
