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
 * 基于固定长度消息进行粘包拆包处理的。
 *
 * 如果下是固定长度为 3 的数据流解码：
 *
 * 原始数据包
 * <pre>
 * +---+----+------+----+
 * | A | BC | DEFG | HI |
 * +---+----+------+----+
 * </pre>
 *
 * 解码后
 * <pre>
 * +-----+-----+-----+
 * | ABC | DEF | GHI |
 * +-----+-----+-----+
 * </pre>
 */
public class FixedLengthFrameDecoder extends ByteToMessageDecoder {


    /**
     * 固定长度大小
     */
    private final int frameLength;

    /**
     * 实例化FixedLengthFrameDecoder，设置固定长度大小
     */
    public FixedLengthFrameDecoder(int frameLength) {
        /** 检查frameLength 长度不能不小等于0 **/
        checkPositive(frameLength, "frameLength");
        /** 设置frameLength **/
        this.frameLength = frameLength;
    }


    /**
     * 实现父类ByteToMessageDecoder 模板方法，对字节流基于固定长度解码，并将解码的消息放入out列表中
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
    protected Object decode(
            @SuppressWarnings("UnusedParameters") ChannelHandlerContext ctx, ByteBuf in) throws Exception {
        /** 可读字节不够 frameLength 长度，无法解码出消息。 **/
        if (in.readableBytes() < frameLength) {
            return null;
        }
        /** 可读字节足够 frameLength 长度，解码出一条消息。**/
        else {
            return in.readRetainedSlice(frameLength);
        }
    }
}
