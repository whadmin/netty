/*
 * Copyright 2013 The Netty Project
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

import io.netty.util.ReferenceCountUtil;
import io.netty.util.internal.TypeParameterMatcher;

/**
 * {@link ChannelInboundHandlerAdapter} 只允许显式处理特定类型的消息。
 *
 * 例如，这里是一个只处理{@link String}消息的实现。子类只需要重写channelRead0
 *
 * <pre>
 *     public class StringHandler extends
 *              SimpleChannelInboundHandler<String> {
 *
 *           @Override
 *          protected void channelRead0(ChannelHandlerContext ctx, String message)
 *                  throws Exception {
 *              System.out.println(message);
 *          }
 *      }
 * </pre>
 *
 * 请注意，根据构造函数参数的不同，它将通过将所有已处理的消息传递给{@link referencecountutil release（object）}来释放这些消息。
 * 在这种情况下，如果将对象传递给{@link channelpipeline}中的下一个处理程序，则可能需要使用{@link referencecountutil retain（object）}。
 *
 */
public abstract class SimpleChannelInboundHandler<I> extends ChannelInboundHandlerAdapter {

    /**
     * 消息类型匹配器，matcher 的实现类为 ReflectiveMatcher
     */
    private final TypeParameterMatcher matcher;

    /**
     * 使用完消息，是否自动释放
     */
    private final boolean autoRelease;

    /**
     * 实例化 SimpleChannelInboundHandler
     * autoRelease默认为true
     * 消息的类型通过子类定义泛型获取
     */
    protected SimpleChannelInboundHandler() {
        this(true);
    }

    /**
     * 实例化 SimpleChannelInboundHandler 设置是否自动释放
     * autoRelease默认为true
     * 消息的类型通过子类定义泛型获取
     */
    protected SimpleChannelInboundHandler(boolean autoRelease) {
        /** 通过TypeParameterMatcher 获取子类定义的消息类型，并转换为TypeParameterMatcher  **/
        matcher = TypeParameterMatcher.find(this, SimpleChannelInboundHandler.class, "I");
        this.autoRelease = autoRelease;
    }

    /**
     * 实例化 SimpleChannelInboundHandler
     * 直接设置消息的类型
     * 自动释放默认为true
     */
    protected SimpleChannelInboundHandler(Class<? extends I> inboundMessageType) {
        this(inboundMessageType, true);
    }

    /**
     * 实例化 SimpleChannelInboundHandler 设置类型匹配器名称以及是否自动释放
     * 直接设置消息的类型
     * 自动释放默认为true
     */
    protected SimpleChannelInboundHandler(Class<? extends I> inboundMessageType, boolean autoRelease) {
        matcher = TypeParameterMatcher.get(inboundMessageType);
        this.autoRelease = autoRelease;
    }

    /**
     * 判断是否为匹配的消息
     */
    public boolean acceptInboundMessage(Object msg) throws Exception {
        return matcher.match(msg);
    }

    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
        /** 是否要释放消息 **/
        boolean release = true;
        try {
            /** 判断是否为匹配的消息 **/
            if (acceptInboundMessage(msg)) {
                @SuppressWarnings("unchecked")
                I imsg = (I) msg;
                /** 实现读取消息 **/
                channelRead0(ctx, imsg);
            } else {
                /** 不需要释放消息 **/
                release = false;
                /** 触发 Channel Read 到下一个节点 **/
                ctx.fireChannelRead(msg);
            }
        } finally {
            /** 判断，是否要释放消息 **/
            if (autoRelease && release) {
                ReferenceCountUtil.release(msg);
            }
        }
    }

    /**
     * 模板方法，实现读取消息
     */
    protected abstract void channelRead0(ChannelHandlerContext ctx, I msg) throws Exception;
}
