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
package io.netty.channel;

import io.netty.util.Attribute;
import io.netty.util.AttributeKey;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Inherited;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 公共接口ChannelHandler
 * 作为一个处理程序，处理I / O事件或拦截I / O操作，并将其转发到{@link ChannelPipeline}中的下一个处理程序。
 *
 * <h3>子类型</h3>
 * <p>
 * ChannelHandler 本身并没有提供很多方法，但你通常必须实现它的一个子类型
 *
 * ChannelInboundHandler 处理通知I / O事件，
 * ChannelOutboundHandler 处理请求I / O操作。
 *
 * 或者，为方便起见，提供了以下适配器类：

 * {@link ChannelInboundHandlerAdapter} 处理通知I / O事件
 * {@link ChannelOutboundHandlerAdapter} 处理请求I / O操作。
 * {@link ChannelDuplexHandler} 处理通知I / O事件 请求I / O操作

 * <h3>ChannelHandlerContext</h3>
 * <p>
 * {@link ChannelHandler}提供了一个{@link ChannelHandlerContext}对象。{@link ChannelHandler}
 * 应该通过上下文对象与它所属的{@link ChannelPipeline}交互。使用context对象，{@link ChannelHandler}可以向上下游传递事件、
 * 动态修改管道或存储特定于处理程序的信息
 */
public interface ChannelHandler {

    /**
     * 处理 handlerAdded 通知事件，ChannelHandler添加到channelpipeline时触发调用
     */
    void handlerAdded(ChannelHandlerContext ctx) throws Exception;

    /**
     * 处理 handlerRemoved 通知事件，ChannelHandler从channelpipeline删除时触发调用
     */
    void handlerRemoved(ChannelHandlerContext ctx) throws Exception;

    /**
     * 处理 exceptionCaught 通知事件，发生异常时触发调用
     */
    @Deprecated
    void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) throws Exception;

    /**
     * 指示可以多次将带批注的{@link ChannelHandler}的同一实例添加到一个或多个{@link ChannelPipeline}中，而无竞争条件。
     */
    @Inherited
    @Documented
    @Target(ElementType.TYPE)
    @Retention(RetentionPolicy.RUNTIME)
    @interface Sharable {
    }
}
