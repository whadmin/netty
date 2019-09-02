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

import io.netty.util.internal.logging.InternalLogger;
import io.netty.util.internal.logging.InternalLoggerFactory;

import java.util.Collection;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.AbstractExecutorService;
import java.util.concurrent.Callable;
import java.util.concurrent.RunnableFuture;
import java.util.concurrent.TimeUnit;

/**
 * {@link EventExecutor}实现的抽象基类。
 */
public abstract class AbstractEventExecutor extends AbstractExecutorService implements EventExecutor {

    private static final InternalLogger logger = InternalLoggerFactory.getInstance(AbstractEventExecutor.class);

    static final long DEFAULT_SHUTDOWN_QUIET_PERIOD = 2;
    static final long DEFAULT_SHUTDOWN_TIMEOUT = 15;

    /**
     * 管理事件处理器分组
     */
    private final EventExecutorGroup parent;


    /** 兼容实现EventExecutorGroup接口的集合 **/
    private final Collection<EventExecutor> selfCollection = Collections.<EventExecutor>singleton(this);

    /**
     * 实例化AbstractEventExecutor
     */
    protected AbstractEventExecutor() {
        this(null);
    }

    /**
     * 实例化AbstractEventExecutor指定EventExecutorGroup
     */
    protected AbstractEventExecutor(EventExecutorGroup parent) {
        this.parent = parent;
    }

    /**
     * 返回关联EventExecutorGroup
     */
    @Override
    public EventExecutorGroup parent() {
        return parent;
    }

    /**
     * 获取当前对象作为事件处理器返回
     */
    @Override
    public EventExecutor next() {
        return this;
    }

    /**
     * 判断指定线程是否是事件处理器的工作线程
     */
    @Override
    public boolean inEventLoop() {
        return inEventLoop(Thread.currentThread());
    }

    /**
     * 返回selfCollection集合迭代器
     */
    @Override
    public Iterator<EventExecutor> iterator() {
        return selfCollection.iterator();
    }

    /**
     * 优雅的关闭事件处理器
     */
    @Override
    public Future<?> shutdownGracefully() {
        return shutdownGracefully(DEFAULT_SHUTDOWN_QUIET_PERIOD, DEFAULT_SHUTDOWN_TIMEOUT, TimeUnit.SECONDS);
    }

    /**
     * 关闭事件处理器，已废弃
     */
    @Override
    @Deprecated
    public abstract void shutdown();

    /**
     * 立刻关闭事件处理器，已废弃
     */
    @Override
    @Deprecated
    public List<Runnable> shutdownNow() {
        shutdown();
        return Collections.emptyList();
    }

    /**
     * 返回一个新的{@link Promise}。
     */
    @Override
    public <V> Promise<V> newPromise() {
        return new DefaultPromise<V>(this);
    }

    /**
     * 返回一个新的{@link ProgressivePromise}.
     * 相对于{@link Promise}可以指示异步操作的的进度。
     */
    @Override
    public <V> ProgressivePromise<V> newProgressivePromise() {
        return new DefaultProgressivePromise<V>(this);
    }

    /**
     * 返回一个新的{@link SucceededFuture}.表示异步操作完成并成功
     */
    @Override
    public <V> Future<V> newSucceededFuture(V result) {
        return new SucceededFuture<V>(this, result);
    }

    /**
     * 返回一个新的{@link FailedFuture}.表示异步操作完成并失败
     */
    @Override
    public <V> Future<V> newFailedFuture(Throwable cause) {
        return new FailedFuture<V>(this, cause);
    }

    /** 提交任务  **/
    @Override
    public Future<?> submit(Runnable task) {
        return (Future<?>) super.submit(task);
    }

    @Override
    public <T> Future<T> submit(Runnable task, T result) {
        return (Future<T>) super.submit(task, result);
    }

    @Override
    public <T> Future<T> submit(Callable<T> task) {
        return (Future<T>) super.submit(task);
    }

    /** 创建PromiseTask **/
    @Override
    protected final <T> RunnableFuture<T> newTaskFor(Runnable runnable, T value) {
        return new PromiseTask<T>(this, runnable, value);
    }

    @Override
    protected final <T> RunnableFuture<T> newTaskFor(Callable<T> callable) {
        return new PromiseTask<T>(this, callable);
    }

    /** 重写定时任务接口 **/
    @Override
    public ScheduledFuture<?> schedule(Runnable command, long delay,
                                       TimeUnit unit) {
        throw new UnsupportedOperationException();
    }

    @Override
    public <V> ScheduledFuture<V> schedule(Callable<V> callable, long delay, TimeUnit unit) {
        throw new UnsupportedOperationException();
    }

    @Override
    public ScheduledFuture<?> scheduleAtFixedRate(Runnable command, long initialDelay, long period, TimeUnit unit) {
        throw new UnsupportedOperationException();
    }

    @Override
    public ScheduledFuture<?> scheduleWithFixedDelay(Runnable command, long initialDelay, long delay, TimeUnit unit) {
        throw new UnsupportedOperationException();
    }

    /**
     * 执行异步任务
     */
    protected static void safeExecute(Runnable task) {
        try {
            task.run();
        } catch (Throwable t) {
            logger.warn("A task raised an exception. Task: {}", task, t);
        }
    }
}
