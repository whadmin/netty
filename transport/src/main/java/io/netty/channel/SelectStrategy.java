/*
 * Copyright 2016 The Netty Project
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

import io.netty.util.IntSupplier;

/**
 * 选择( select )策略接口
 */
public interface SelectStrategy {

    /**
     * 表示使用阻塞 select 的策略。
     */
    int SELECT = -1;
    /**
     * 表示需要进行重试的策略。
     */
    int CONTINUE = -2;
    /**
     * 表示要在不阻塞的情况下轮询新事件的IO循环。
     */
    int BUSY_WAIT = -3;

    /**
     * 接口方法有 3 种返回的情况：
     * SELECT，-1 ，表示使用阻塞 select 的策略。
     * CONTINUE，-2，表示需要进行重试的策略。实际上，默认情况下，不会返回 CONTINUE 的策略。
     * >= 0 ，表示不需要 select ，目前已经有可以执行的任务了。
     */
    int calculateStrategy(IntSupplier selectSupplier, boolean hasTasks) throws Exception;
}
