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

import io.netty.util.concurrent.OrderedEventExecutor;

/**
 * io.netty.channel.EventLoop ，继承 OrderedEventExecutor 和 EventLoopGroup 接口，EventLoop 接口
 * 表示事件循环处理器，这里事件是注册到选择器中的事件
 * EventLoop 将会处理注册在其上的 Channel 的所有 IO 操作。
 * 通常，一个 EventLoop 上可以注册不只一个 Channel 。当然，这个也取决于具体的实现。
 */
public interface EventLoop extends OrderedEventExecutor, EventLoopGroup {

    /**
     * 覆写方法的返回类型为 EventLoopGroup 。
     */
    @Override
    EventLoopGroup parent();
}
