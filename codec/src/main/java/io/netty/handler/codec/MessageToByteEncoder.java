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
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelOutboundHandler;
import io.netty.channel.ChannelOutboundHandlerAdapter;
import io.netty.channel.ChannelPipeline;
import io.netty.channel.ChannelPromise;
import io.netty.util.ReferenceCountUtil;
import io.netty.util.internal.TypeParameterMatcher;


/**
 * 负责将消息编码成字节，支持匹配指定类型的消息
 *
 *
 * Example implementation which encodes {@link Integer}s to a {@link ByteBuf}.
 *
 * <pre>
 *     public class IntegerEncoder extends {@link MessageToByteEncoder}&lt;{@link Integer}&gt; {
 *         {@code @Override}
 *         public void encode({@link ChannelHandlerContext} ctx, {@link Integer} msg, {@link ByteBuf} out)
 *                 throws {@link Exception} {
 *             out.writeInt(msg);
 *         }
 *     }
 * </pre>
 */
public abstract class MessageToByteEncoder<I> extends ChannelOutboundHandlerAdapter {

    /**
     * 类型匹配器
     */
    private final TypeParameterMatcher matcher;

    /**
     * 是否偏向使用 Direct 内存
     */
    private final boolean preferDirect;

    /**
     * 实例化MessageToByteEncoder
     */
    protected MessageToByteEncoder() {
        this(true);
    }

    /**
     * 实例化MessageToByteEncoder
     */
    protected MessageToByteEncoder(Class<? extends I> outboundMessageType) {
        this(outboundMessageType, true);
    }

    /**
     * 实例化MessageToByteEncoder
     */
    protected MessageToByteEncoder(boolean preferDirect) {
        matcher = TypeParameterMatcher.find(this, MessageToByteEncoder.class, "I");
        this.preferDirect = preferDirect;
    }

    /**
     * 实例化MessageToByteEncoder
     */
    protected MessageToByteEncoder(Class<? extends I> outboundMessageType, boolean preferDirect) {
        matcher = TypeParameterMatcher.get(outboundMessageType);
        this.preferDirect = preferDirect;
    }

    /**
     * 判断消息是否匹配
     */
    public boolean acceptOutboundMessage(Object msg) throws Exception {
        return matcher.match(msg);
    }

    @Override
    public void write(ChannelHandlerContext ctx, Object msg, ChannelPromise promise) throws Exception {
        ByteBuf buf = null;
        try {
            /** 判断是否为匹配的消息 **/
            if (acceptOutboundMessage(msg)) {
                /** 获得消息 **/
                @SuppressWarnings("unchecked")
                I cast = (I) msg;
                /** 申请 buf **/
                buf = allocateBuffer(ctx, cast, preferDirect);
                try {
                    /** 执行编码，将编码后字节数据写入buf **/
                    encode(ctx, cast, buf);
                } finally {
                    /** 释放 msg **/
                    ReferenceCountUtil.release(cast);
                }

                /** buf 可读，说明有编码到数据 **/
                if (buf.isReadable()) {
                    /** 写入 buf 到下一个节点 **/
                    ctx.write(buf, promise);
                } else {
                    /** 释放 buf **/
                    buf.release();
                    /** 写入 EMPTY_BUFFER 到下一个节点，为了 promise 的回调 **/
                    ctx.write(Unpooled.EMPTY_BUFFER, promise);
                }

                /** 置空 buf **/
                buf = null;
            } else {
                /**  提交 write 事件给下一个节点  **/
                ctx.write(msg, promise);
            }
        } catch (EncoderException e) {
            throw e;
        } catch (Throwable e) {
            throw new EncoderException(e);
        } finally {
            /** 释放 buf **/
            if (buf != null) {
                buf.release();
            }
        }
    }

    /**
     * 申请 buf
     */
    protected ByteBuf allocateBuffer(ChannelHandlerContext ctx, @SuppressWarnings("unused") I msg,
                               boolean preferDirect) throws Exception {
        if (preferDirect) {
            return ctx.alloc().ioBuffer();
        } else {
            return ctx.alloc().heapBuffer();
        }
    }

    /**
     * 执行编码
     */
    protected abstract void encode(ChannelHandlerContext ctx, I msg, ByteBuf out) throws Exception;

    protected boolean isPreferDirect() {
        return preferDirect;
    }
}
