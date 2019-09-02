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

/**
 * 特殊的{@link Future}，可以参与监视异步任务，并设置返回结果
 */
public interface Promise<V> extends Future<V> {

    /**
     * 标记异步操作成功，并通知所有监听器触发回调，如果成功返回当前对象
     * 如果异常操作已经成功或失败会抛出 {@link IllegalStateException}.
     */
    Promise<V> setSuccess(V result);

    /**
     * 标记异步操作成功，如果成功返回true,如果异常操作已经成功或失败返回false
     */
    boolean trySuccess(V result);

    /**
     * 标记异步操作发生异常，并通知所有监听器触发回调，如果成功返回当前对象
     * 如果异常操作已经成功或失败会抛出 {@link IllegalStateException}.
     */
    Promise<V> setFailure(Throwable cause);

    /**
     * 标记异步操作发生异常，如果成功返回true,如果异常操作已经成功或失败返回false
     */
    boolean tryFailure(Throwable cause);

    /**
     * 标记异步操作无法取消
     * 如果当前异步操作成功会标记成功返回true,如果标记前以取消返回false
     */
    boolean setUncancellable();

    @Override
    Promise<V> addListener(GenericFutureListener<? extends Future<? super V>> listener);

    @Override
    Promise<V> addListeners(GenericFutureListener<? extends Future<? super V>>... listeners);

    @Override
    Promise<V> removeListener(GenericFutureListener<? extends Future<? super V>> listener);

    @Override
    Promise<V> removeListeners(GenericFutureListener<? extends Future<? super V>>... listeners);

    @Override
    Promise<V> await() throws InterruptedException;

    @Override
    Promise<V> awaitUninterruptibly();

    @Override
    Promise<V> sync() throws InterruptedException;

    @Override
    Promise<V> syncUninterruptibly();
}
