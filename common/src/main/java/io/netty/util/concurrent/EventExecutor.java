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
package io.netty.util.concurrent;

/**
 * 事件处理器，内部通过工作线程来处理异步任务，
 * 工作线程通常会在事件循环中不断获取事件来处理
 */
public interface EventExecutor extends EventExecutorGroup {

    /**
     * 返回当前对象引用
     */
    @Override
    EventExecutor next();

    /**
     * 返回{@link EventExecutorGroup}，它是{@link EventExecutor}的parent，
     */
    EventExecutorGroup parent();

    /**
     * 当前线程是否是事件处理器的工作线程
     */
    boolean inEventLoop();

    /**
     * 判断指定线程是否是事件处理器的工作线程
     */
    boolean inEventLoop(Thread thread);

    /**
     * 返回一个新的{@link Promise}。
     */
    <V> Promise<V> newPromise();

    /**
     * 返回一个新的{@link ProgressivePromise}.
     * 相对于{@link Promise}可以指示异步操作的的进度。
     */
    <V> ProgressivePromise<V> newProgressivePromise();

    /**
     * 返回一个新的{@link SucceededFuture}.表示异步操作完成并成功
     */
    <V> Future<V> newSucceededFuture(V result);

    /**
     * 返回一个新的{@link FailedFuture}.表示异步操作完成并失败
     */
    <V> Future<V> newFailedFuture(Throwable cause);
}
