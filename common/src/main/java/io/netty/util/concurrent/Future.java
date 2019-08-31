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
package io.netty.util.concurrent;

import java.util.concurrent.CancellationException;
import java.util.concurrent.TimeUnit;


/**
 * 表示异步操作的结果,优化JDK Future
 *
 * 1 异步事件状态重新做了定义
 *
 * 已完成	成功	isDone()==true 且 isSuccess()=true
 * 已完成	失败	isDone()==true 且 cause()!=null
 * 已完成	用户取消	isDone()==true 且 isCancelled()=true
 * 尚未完成		isDone()==false
 *
 * <pre>
 *                                      +---------------------------+
 *                                      | Completed successfully    |
 *                                      +---------------------------+
 *                                 +---->      isDone() = true      |
 * +--------------------------+    |    |   isSuccess() = true      |
 * |        Uncompleted       |    |    +===========================+
 * +--------------------------+    |    | Completed with failure    |
 * |      isDone() = false    |    |    +---------------------------+
 * |   isSuccess() = false    |----+---->      isDone() = true      |
 * | isCancelled() = false    |    |    |       cause() = non-null  |
 * |       cause() = null     |    |    +===========================+
 * +--------------------------+    |    | Completed by cancellation |
 *                                 |    +---------------------------+
 *                                 +---->      isDone() = true      |
 *                                      | isCancelled() = true      |
 *                                      +---------------------------+
 * </pre>
 * 2 添加异步回调监听器
 *
 */
public interface Future<V> extends java.util.concurrent.Future<V> {

    /** 当且仅当异步操作成功完成时，才返回{@code true}。**/
    boolean isSuccess();

    /** 当且仅当异步操作可以取消时，才返回{@code true}。**/
    boolean isCancellable();

    /** 如果异步操作失败，则返回异步操作失败的原因。 **/
    Throwable cause();

    /** 将指定的侦听器添加到Future，当异常操作{@link #isDone()}  完成则会立即通知指定的侦听器触发回调操作 **/
    Future<V> addListener(GenericFutureListener<? extends Future<? super V>> listener);

    /** 将指定的侦听器添加到Future，当异常操作{@link #isDone()}  完成则会立即通知指定的侦听器触发回调操作 **/
    Future<V> addListeners(GenericFutureListener<? extends Future<? super V>>... listeners);

    /** 删除指定侦听器 **/
    Future<V> removeListener(GenericFutureListener<? extends Future<? super V>> listener);

    /** 删除指定侦听器 **/
    Future<V> removeListeners(GenericFutureListener<? extends Future<? super V>>... listeners);

    /**
     * 等待异步操作完成{@link #isDone()},
     * 如果异步操作由于发生异常而完成，调用{@link #cause()}获取异常信息，并抛出异常
     * 当前线程响应中断，抛出异常**/
    Future<V> sync() throws InterruptedException;

    /**
     * 等待异步操作完成{@link #isDone()},
     * 如果异步操作由于发生异常而完成，调用{@link #cause()}获取异常信息，并抛出异常
     * 当前线程不响应中断，抛出异常 **/
    Future<V> syncUninterruptibly();

    /**
     * 等待异步操作完成{@link #isDone()},
     * 当前线程响应中断，抛出异常
     * 如果异步操作由于发生异常而完成，可以调用{@link #cause()}获取异常信息 **/
    Future<V> await() throws InterruptedException;

    /**
     * 等待异步操作完成{@link #isDone()},
     * 当前线程不响应中断，抛出异常
     * 如果异步操作由于发生异常而完成，可以调用{@link #cause()}获取异常信息**/
    Future<V> awaitUninterruptibly();

    /**
     * 在超时时间内，等待异步操作完成，如果异常操作在超时时间完成返回true，否则返回false
     * 当前线程响应中断，抛出异常
     */
    boolean await(long timeout, TimeUnit unit) throws InterruptedException;

    /**
     * 在超时时间内(默认单位毫秒)，等待异步操作完成，如果异常操作在超时时间完成返回true，否则返回false
     * 当前线程响应中断，抛出异常
     */
    boolean await(long timeoutMillis) throws InterruptedException;

    /**
     * 在超时时间内，等待异步操作完成，如果异常操作在超时时间完成返回true，否则返回false
     * 当前线程不响应中断，抛出异常
     */
    boolean awaitUninterruptibly(long timeout, TimeUnit unit);

    /**
     * 在超时时间内(默认单位毫秒)，等待异步操作完成，如果异常操作在超时时间完成返回true，否则返回false
     * 当前线程不响应中断，抛出异常
     */
    boolean awaitUninterruptibly(long timeoutMillis);

    /**
     * 返回结果而不阻塞。如果未来还没有完成，这将返回{@code null}.
     */
    V getNow();

    /** 取消异常操作 */
    @Override
    boolean cancel(boolean mayInterruptIfRunning);
}
