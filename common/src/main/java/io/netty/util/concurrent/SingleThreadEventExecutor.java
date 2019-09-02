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

import io.netty.util.internal.ObjectUtil;
import io.netty.util.internal.PlatformDependent;
import io.netty.util.internal.SystemPropertyUtil;
import io.netty.util.internal.ThreadExecutorMap;
import io.netty.util.internal.UnstableApi;
import io.netty.util.internal.logging.InternalLogger;
import io.netty.util.internal.logging.InternalLoggerFactory;

import java.lang.Thread.State;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicIntegerFieldUpdater;
import java.util.concurrent.atomic.AtomicReferenceFieldUpdater;

/**
 * 单线程事件处理器，负责处理提交异步任务。
 * */
public abstract class SingleThreadEventExecutor extends AbstractScheduledEventExecutor implements OrderedEventExecutor {

    static final int DEFAULT_MAX_PENDING_EXECUTOR_TASKS = Math.max(16,
            SystemPropertyUtil.getInt("io.netty.eventexecutor.maxPendingTasks", Integer.MAX_VALUE));

    private static final InternalLogger logger =
            InternalLoggerFactory.getInstance(SingleThreadEventExecutor.class);

    /** 没有启动 **/
    private static final int ST_NOT_STARTED = 1;
    /** 启动 **/
    private static final int ST_STARTED = 2;
    /** 正在关闭 **/
    private static final int ST_SHUTTING_DOWN = 3;
    /** 关闭 **/
    private static final int ST_SHUTDOWN = 4;
    /** 终止 **/
    private static final int ST_TERMINATED = 5;

    private static final Runnable WAKEUP_TASK = new Runnable() {
        @Override
        public void run() {
            // Do nothing.
        }
    };
    private static final Runnable NOOP_TASK = new Runnable() {
        @Override
        public void run() {
            // Do nothing.
        }
    };

    /** 原子更新state字段处理器 **/
    private static final AtomicIntegerFieldUpdater<SingleThreadEventExecutor> STATE_UPDATER =
            AtomicIntegerFieldUpdater.newUpdater(SingleThreadEventExecutor.class, "state");

    /** 原子更新threadProperties字段处理器 **/
    private static final AtomicReferenceFieldUpdater<SingleThreadEventExecutor, ThreadProperties> PROPERTIES_UPDATER =
            AtomicReferenceFieldUpdater.newUpdater(
                    SingleThreadEventExecutor.class, ThreadProperties.class, "threadProperties");

    /** 任务队列  **/
    private final Queue<Runnable> taskQueue;
    /** 任务队列最大容量  **/
    private final int maxPendingTasks;

    /** work线程 **/
    private volatile Thread thread;
    /** 创建work线程的执行器 **/
    private final Executor executor;
    /** work线程属性 **/
    @SuppressWarnings("unused")
    private volatile ThreadProperties threadProperties;
    /** work线程是否被中断 */
    private volatile boolean interrupted;

    /** 处理单线程事件处理器关闭时闭锁，阻塞其他线程等待work线程停止 **/
    private final CountDownLatch threadLock = new CountDownLatch(1);

    /** 处理任务处理器关闭时清理线程集合**/
    private final Set<Runnable> shutdownHooks = new LinkedHashSet<Runnable>();

    /** 是否只能在添加任务时，添加WAKEUP_TASK任务到任务队列，唤醒阻塞的work线程**/
    private final boolean addTaskWakesUp;

    /** 拒绝策略 **/
    private final RejectedExecutionHandler rejectedExecutionHandler;

    /** 最后执行任务时间*/
    private long lastExecutionTime;

    /** 事件处理器状态 **/
    @SuppressWarnings({ "FieldMayBeFinal", "unused" })
    private volatile int state = ST_NOT_STARTED;

    /**
     *  事件处理器关闭时关闭时静默时间。
     *  静默时间看为一段观察期，在此期间如果没有任务执行，可以顺利关闭如果此期间有任务执行，执行完后立即进入下一个观察期继续观察；
     *  如果连续多个观察期一直有任务执行， 那么累计时间超过gracefulShutdownTimeout截止时间强制关闭 **/
    private volatile long gracefulShutdownQuietPeriod;

    /** 事件处理器关闭时的静默时间 **/
    private volatile long gracefulShutdownTimeout;

    /** 事件处理器处于正在关闭状态时，记录开始关闭的开始时间，作为衡量静默时间的标准 **/
    private long gracefulShutdownStartTime;

    /** 事件处理器关闭时结果 **/
    private final Promise<?> terminationFuture = new DefaultPromise<Void>(GlobalEventExecutor.INSTANCE);

    /**
     * 实例化单线程事件处理器
     * @param parent            所属{@link EventExecutorGroup}
     * @param threadFactory     创建单个work线程的工厂
     * @param addTaskWakesUp    @code true如果且仅当调用@link addtask（runnable）将唤醒执行器线程时
     */
    protected SingleThreadEventExecutor(
            EventExecutorGroup parent, ThreadFactory threadFactory, boolean addTaskWakesUp) {
        this(parent, new ThreadPerTaskExecutor(threadFactory), addTaskWakesUp);
    }

    /**
     * 实例化单线程事件处理器指定如下参数
     * @param parent            所属{@link EventExecutorGroup}
     * @param threadFactory     创建单个work线程的工厂
     * @param addTaskWakesUp    如果设置为true，调用{@link addTask(Runnable)}时，将添加WAKEUP_TASK任务，保证work线程从阻塞队列中释放
     * @param maxPendingTasks   任务队列最大容量，超过后新添加任务将被拒绝
     * @param rejectedHandler   拒绝策略
     */
    protected SingleThreadEventExecutor(
            EventExecutorGroup parent, ThreadFactory threadFactory,
            boolean addTaskWakesUp, int maxPendingTasks, RejectedExecutionHandler rejectedHandler) {
        this(parent, new ThreadPerTaskExecutor(threadFactory), addTaskWakesUp, maxPendingTasks, rejectedHandler);
    }

    /**
     * 实例化单线程事件处理器指定如下参数
     * @param parent            所属{@link EventExecutorGroup}
     * @param executor          创建单个work线程的执行器
     * @param addTaskWakesUp    如果设置为true，调用{@link addTask(Runnable)}时，将添加WAKEUP_TASK任务，保证work线程从阻塞队列中释放
     */
    protected SingleThreadEventExecutor(EventExecutorGroup parent, Executor executor, boolean addTaskWakesUp) {
        this(parent, executor, addTaskWakesUp, DEFAULT_MAX_PENDING_EXECUTOR_TASKS, RejectedExecutionHandlers.reject());
    }

    /**
     * 实例化单线程事件处理器指定如下参数
     * @param parent            所属{@link EventExecutorGroup}
     * @param executor          创建单个work线程的执行器
     * @param addTaskWakesUp    如果设置为true，调用{@link addTask(Runnable)}时，将添加WAKEUP_TASK任务，保证work线程从阻塞队列中释放
     * @param maxPendingTasks   任务队列最大容量，超过后新添加任务将被拒绝
     * @param rejectedHandler   拒绝策略
     */
    protected SingleThreadEventExecutor(EventExecutorGroup parent, Executor executor,
                                        boolean addTaskWakesUp, int maxPendingTasks,
                                        RejectedExecutionHandler rejectedHandler) {
        super(parent);
        this.addTaskWakesUp = addTaskWakesUp;
        this.maxPendingTasks = Math.max(16, maxPendingTasks);
        this.executor = ThreadExecutorMap.apply(executor, this);
        taskQueue = newTaskQueue(this.maxPendingTasks);
        rejectedExecutionHandler = ObjectUtil.checkNotNull(rejectedHandler, "rejectedHandler");
    }

    /**
     * 实例化单线程事件处理器指定如下参数
     * @param parent            所属{@link EventExecutorGroup}
     * @param executor          创建单个work线程的执行器
     * @param addTaskWakesUp    如果设置为true，调用{@link addTask(Runnable)}时，将添加WAKEUP_TASK任务，保证work线程从阻塞队列中释放
     * @param taskQueue         任务队列
     * @param rejectedHandler   拒绝策略
     */
    protected SingleThreadEventExecutor(EventExecutorGroup parent, Executor executor,
                                        boolean addTaskWakesUp, Queue<Runnable> taskQueue,
                                        RejectedExecutionHandler rejectedHandler) {
        super(parent);
        this.addTaskWakesUp = addTaskWakesUp;
        this.maxPendingTasks = DEFAULT_MAX_PENDING_EXECUTOR_TASKS;
        this.executor = ThreadExecutorMap.apply(executor, this);
        this.taskQueue = ObjectUtil.checkNotNull(taskQueue, "taskQueue");
        rejectedExecutionHandler = ObjectUtil.checkNotNull(rejectedHandler, "rejectedHandler");
    }

    /**
     * 在计划的任务提交之前从任意非@link eventexecutor线程调用。返回@code true如果应立即唤醒@link eventsexecutor线程以处理计划任务（如果尚未唤醒）
     */
    protected boolean beforeScheduledTaskSubmitted(long deadlineNanos) {
        return true;
    }

    /**
     * 请参阅@link beforescheduledtasksubmitted（long）。仅在该方法返回false之后调用。
     */
    protected boolean afterScheduledTaskSubmitted(long deadlineNanos) {
        return true;
    }

    /**
     * @deprecated 请使用并覆盖{@link #newTaskQueue（int）}。
     */
    @Deprecated
    protected Queue<Runnable> newTaskQueue() {
        return newTaskQueue(maxPendingTasks);
    }

    /**
     * 创建一个指定容量的任务队列，这里默认实现是LinkedBlockingQueue
     */
    protected Queue<Runnable> newTaskQueue(int maxPendingTasks) {
        return new LinkedBlockingQueue<Runnable>(maxPendingTasks);
    }

    /**
     * 中断当前运行的work线程{@link Thread}。
     */
    protected void interruptThread() {
        Thread currentThread = thread;
        if (currentThread == null) {
            interrupted = true;
        } else {
            currentThread.interrupt();
        }
    }

    /** 获取任务队列中第一个非 WAKEUP_TASK任务**/
    protected Runnable pollTask() {
        assert inEventLoop();
        return pollTaskFrom(taskQueue);
    }

    /** 获取任务队列中第一个非 WAKEUP_TASK任务 **/
    protected static Runnable pollTaskFrom(Queue<Runnable> taskQueue) {
        for (;;) {
            Runnable task = taskQueue.poll();
            if (task != WAKEUP_TASK) {
                return task;
            }
        }
    }

    /**
     * 从任务队列中取出下一个{@link Runnable}，
     * 如果任务队列不存在但计划任务队列存在任务，会将计划任务队列中计划任务添加到任务队列，之后返回
     */
    protected Runnable takeTask() {
        assert inEventLoop();
        /** 任务队列必须是阻塞队列 **/
        if (!(taskQueue instanceof BlockingQueue)) {
            throw new UnsupportedOperationException();
        }

        BlockingQueue<Runnable> taskQueue = (BlockingQueue<Runnable>) this.taskQueue;
        for (;;) {
            ScheduledFutureTask<?> scheduledTask = peekScheduledTask();
            /** 如果计划任务队列不存在任务 **/
            if (scheduledTask == null) {
                Runnable task = null;
                try {
                    task = taskQueue.take();
                    if (task == WAKEUP_TASK) {
                        task = null;
                    }
                } catch (InterruptedException e) {
                    // Ignore
                }
                return task;
            }
            /** 如果计划任务队列存在任务 **/
            else {
                long delayNanos = scheduledTask.delayNanos();
                Runnable task = null;
                /** 如果计划任务无法执行，从任务队列获取任务，设置超时时间
                 *  **/
                if (delayNanos > 0) {
                    try {
                        task = taskQueue.poll(delayNanos, TimeUnit.NANOSECONDS);
                    } catch (InterruptedException e) {
                        // Waken up.
                        return null;
                    }
                }
                /**
                 * 如果计划任务可以执行，以当前时间为基准，获取延时队列中所有可以执行的任务，添加到任务队列中
                 * 从任务队列获取任务
                 * **/
                if (task == null) {
                    fetchFromScheduledTaskQueue();
                    task = taskQueue.poll();
                }

                if (task != null) {
                    return task;
                }
            }
        }
    }

    /**
     * 以当前时间为基准，获取延时队列中所有可以执行的任务，添加到任务队列中，如果添加失败重新添加到延时任务队列
     * 如果添加成功一个返回true
     */
    private boolean fetchFromScheduledTaskQueue() {
        if (scheduledTaskQueue == null || scheduledTaskQueue.isEmpty()) {
            return true;
        }
        long nanoTime = AbstractScheduledEventExecutor.nanoTime();
        for (;;) {
            Runnable scheduledTask = pollScheduledTask(nanoTime);
            if (scheduledTask == null) {
                return true;
            }
            if (!taskQueue.offer(scheduledTask)) {
                scheduledTaskQueue.add((ScheduledFutureTask<?>) scheduledTask);
                return false;
            }
        }
    }

    /**
     * 以当前时间为基准，获取计划任务队列中所有可以执行的任务，执行，
     * 如果执行至少一个返回true
     */
    private boolean executeExpiredScheduledTasks() {
        if (scheduledTaskQueue == null || scheduledTaskQueue.isEmpty()) {
            return false;
        }
        /** 获取System.nanoTime() - START_TIME; START_TIME表示启动时间**/
        long nanoTime = AbstractScheduledEventExecutor.nanoTime();
        /** 获取nanoTime内可以执行的延时任务**/
        Runnable scheduledTask = pollScheduledTask(nanoTime);
        if (scheduledTask == null) {
            return false;
        }
        do {
            /** 执行给定延时任务{@link Runnable} **/
            safeExecute(scheduledTask);
        } while ((scheduledTask = pollScheduledTask(nanoTime)) != null);
        return true;
    }

    /**
     * 从任务队列获取一个任务，获取任务不从队列删除
     */
    protected Runnable peekTask() {
        assert inEventLoop();
        return taskQueue.peek();
    }

    /**
     * 任务队列是否为空
     */
    protected boolean hasTasks() {
        assert inEventLoop();
        return !taskQueue.isEmpty();
    }

    /**
     * 返回任务队列理的任务数。
     */
    public int pendingTasks() {
        return taskQueue.size();
    }

    /**
     * 将任务添加到任务队列，下面情况会触发拒绝策略
     * 1 单线程事件处理器被关闭
     * 2 任务队列容量超过限制
     */
    protected void addTask(Runnable task) {
        if (task == null) {
            throw new NullPointerException("task");
        }
        /** 将任务添加到任务队列 **/
        if (!offerTask(task)) {
            reject(task);
        }
    }

    /**
     * 将任务添加到任务队列，下面情况会失败
     * 1 单线程事件处理器被关闭
     * 2 任务队列容量超过限制
     */
    final boolean offerTask(Runnable task) {
        if (isShutdown()) {
            reject();
        }
        return taskQueue.offer(task);
    }

    /**
     * 将任务从任务队列删除
     */
    protected boolean removeTask(Runnable task) {
        if (task == null) {
            throw new NullPointerException("task");
        }
        return taskQueue.remove(task);
    }

    /**
     * 执行所有可以执行的计划任务，和任务队列任务
     */
    protected boolean runAllTasks() {
        assert inEventLoop();
        boolean fetchedAll;
        boolean ranAtLeastOne = false;

        do {
            // 以当前时间为基准，获取延时队列中所有可以执行的任务，添加到任务队列中
            fetchedAll = fetchFromScheduledTaskQueue();
            // 执行任务队列中所有任务
            if (runAllTasksFrom(taskQueue)) {
                ranAtLeastOne = true;
            }
        } while (!fetchedAll);

        // 设置最后执行任务时间
        if (ranAtLeastOne) {
            lastExecutionTime = ScheduledFutureTask.nanoTime();
        }
        // 执行模板方法
        afterRunningAllTasks();
        return ranAtLeastOne;
    }

    /**
     * 从任务队列和计划任务队列中执行指定数量的任务
     */
    protected final boolean runScheduledAndExecutorTasks(final int maxDrainAttempts) {
        assert inEventLoop();
        boolean ranAtLeastOneTask;
        int drainAttempt = 0;
        do {
            // We must run the taskQueue tasks first, because the scheduled tasks from outside the EventLoop are queued
            // here because the taskQueue is thread safe and the scheduledTaskQueue is not thread safe.
            ranAtLeastOneTask = runExistingTasksFrom(taskQueue) | executeExpiredScheduledTasks();
        } while (ranAtLeastOneTask && ++drainAttempt < maxDrainAttempts);

        if (drainAttempt > 0) {
            lastExecutionTime = ScheduledFutureTask.nanoTime();
        }
        afterRunningAllTasks();

        return drainAttempt > 0;
    }

    /**
     * 执行任务队列中所有任务
     */
    protected final boolean runAllTasksFrom(Queue<Runnable> taskQueue) {
        // 获取任务队列中第一个非 WAKEUP_TASK任务
        Runnable task = pollTaskFrom(taskQueue);
        if (task == null) {
            return false;
        }
        for (;;) {
            // 执行任务
            safeExecute(task);
            // 获取任务队列中第一个非 WAKEUP_TASK任务
            task = pollTaskFrom(taskQueue);
            if (task == null) {
                return true;
            }
        }
    }

    /**
     * 执行指定任务队列所有任务
     */
    private boolean runExistingTasksFrom(Queue<Runnable> taskQueue) {
        //获取任务队列中第一个非 WAKEUP_TASK任务
        Runnable task = pollTaskFrom(taskQueue);
        if (task == null) {
            return false;
        }
        int remaining = Math.min(maxPendingTasks, taskQueue.size());
        safeExecute(task);
        while (remaining-- > 0 && (task = taskQueue.poll()) != null) {
            safeExecute(task);
        }
        return true;
    }

    /**
     * 在指定的时间，执行所有任务队列和计划任务队列中可执行的任务
     */
    protected boolean runAllTasks(long timeoutNanos) {
        //以当前时间为基准，获取延时队列中所有可以执行的任务，添加到任务队列中
        fetchFromScheduledTaskQueue();
        //获取任务队列中第一个非 WAKEUP_TASK任务
        Runnable task = pollTask();
        if (task == null) {
            afterRunningAllTasks();
            return false;
        }

        /** 在指定的时间，执行所有任务队列和计划任务队列中可执行的任务**/
        final long deadline = ScheduledFutureTask.nanoTime() + timeoutNanos;
        long runTasks = 0;
        long lastExecutionTime;
        for (;;) {
            safeExecute(task);

            runTasks ++;

            if ((runTasks & 0x3F) == 0) {
                lastExecutionTime = ScheduledFutureTask.nanoTime();
                if (lastExecutionTime >= deadline) {
                    break;
                }
            }

            task = pollTask();
            if (task == null) {
                lastExecutionTime = ScheduledFutureTask.nanoTime();
                break;
            }
        }

        afterRunningAllTasks();
        this.lastExecutionTime = lastExecutionTime;
        return true;
    }

    /**
     * 模板方法
     */
    @UnstableApi
    protected void afterRunningAllTasks() { }

    /**
     * 获取计划任务队列中第一个计划任务，并返回执行剩余时间
     */
    protected long delayNanos(long currentTimeNanos) {
        // 获取计划任务队列中第一个计划任务，但并不从计划任务队列中删除
        ScheduledFutureTask<?> scheduledTask = peekScheduledTask();
        if (scheduledTask == null) {
            return SCHEDULE_PURGE_INTERVAL;
        }
        // 返回计划任务的剩余时间
        return scheduledTask.delayNanos(currentTimeNanos);
    }

    /**
     * 获取计划任务队列中第一个计划任务，并返回执行触发时间
     */
    @UnstableApi
    protected long deadlineNanos() {
        // 获取计划任务队列中第一个计划任务，但并不从计划任务队列中删除
        ScheduledFutureTask<?> scheduledTask = peekScheduledTask();
        if (scheduledTask == null) {
            return nanoTime() + SCHEDULE_PURGE_INTERVAL;
        }
        // 返回计划任务的触发时间
        return scheduledTask.deadlineNanos();
    }

    /**
     * 更新work线程最后执行任务时间
     */
    protected void updateLastExecutionTime() {
        lastExecutionTime = ScheduledFutureTask.nanoTime();
    }

    /**
     * 执行模板方法
     */
    protected abstract void run();

    /**
     * 清理模板方法
     */
    protected void cleanup() {
        // NOOP
    }

    /**
     * 1 如果事件处理器处于正在关闭状态，工作队列任务已经执行完毕，
     * 此时work由于无法从任务队列获取线程而阻塞于阻塞状态，未保证work线程正常退出
     * 给任务队列添加一个WAKEUP_TASK线程，保证工作线程从获取任务队列的阻塞中被唤醒
     *
     * 2 调用当前方法的线程是work线程，表示work线程并没有阻塞，没必要给任务队列添加一个WAKEUP_TASK线程
     */
    protected void wakeup(boolean inEventLoop) {
        if (!inEventLoop || state == ST_SHUTTING_DOWN) {
            taskQueue.offer(WAKEUP_TASK);
        }
    }

    /**
     * 向任务队列添加特殊的任务，当此任务在任务队列中，work线程不会添加WAKEUP_TASK线程到任务来释放work线程阻塞
     */
    @Override
    final void executeScheduledRunnable(final Runnable runnable, boolean isAddition, long deadlineNanos) {
        if (isAddition && beforeScheduledTaskSubmitted(deadlineNanos)) {
            super.executeScheduledRunnable(runnable, isAddition, deadlineNanos);
        } else {
            super.executeScheduledRunnable(new NonWakeupRunnable() {
                @Override
                public void run() {
                    runnable.run();
                }
            }, isAddition, deadlineNanos);
            if (isAddition && afterScheduledTaskSubmitted(deadlineNanos)) {
                wakeup(false);
            }
        }
    }

    /**
     * 当前线程是否是工作线程
     */
    @Override
    public boolean inEventLoop(Thread thread) {
        return thread == this.thread;
    }

    /**
     * 添加一个shutdown清理任务线程，负责在单线程事件处理器被关闭时则清理工作
     */
    public void addShutdownHook(final Runnable task) {
        if (inEventLoop()) {
            shutdownHooks.add(task);
        } else {
            execute(new Runnable() {
                @Override
                public void run() {
                    shutdownHooks.add(task);
                }
            });
        }
    }

    /**
     * 删除一个shutdown清理任务线程，负责在单线程事件处理器被关闭时则清理工作
     */
    public void removeShutdownHook(final Runnable task) {
        if (inEventLoop()) {
            shutdownHooks.remove(task);
        } else {
            execute(new Runnable() {
                @Override
                public void run() {
                    shutdownHooks.remove(task);
                }
            });
        }
    }

    /**
     * 执行所有清理任务任务，只要执行一个返回true
     */
    private boolean runShutdownHooks() {
        boolean ran = false;
        // Note shutdown hooks can add / remove shutdown hooks.
        while (!shutdownHooks.isEmpty()) {
            List<Runnable> copy = new ArrayList<Runnable>(shutdownHooks);
            shutdownHooks.clear();
            for (Runnable task: copy) {
                try {
                    task.run();
                } catch (Throwable t) {
                    logger.warn("Shutdown hook raised an exception.", t);
                } finally {
                    ran = true;
                }
            }
        }

        if (ran) {
            lastExecutionTime = ScheduledFutureTask.nanoTime();
        }

        return ran;
    }

    /**
     * 优雅的关闭单线程事件处理器
     * @param quietPeriod 静默时间
     * @param timeout     等待事件处理器关闭的最大静默时间
     * @param unit        时间单位
     * @return
     */
    @Override
    public Future<?> shutdownGracefully(long quietPeriod, long timeout, TimeUnit unit) {

        /**
         * 1 确保 quietPeriod、unit的为有效值，即『quietPeriod >= 0』、『unit != null』。同时，确保timeout、quietPeriod之间的正确性，
         * 即『quietPeriod <= timeout』。
         * **/
        if (quietPeriod < 0) {
            throw new IllegalArgumentException("quietPeriod: " + quietPeriod + " (expected >= 0)");
        }
        if (timeout < quietPeriod) {
            throw new IllegalArgumentException(
                    "timeout: " + timeout + " (expected >= quietPeriod (" + quietPeriod + "))");
        }
        if (unit == null) {
            throw new NullPointerException("unit");
        }

        /** 2 如果事件处理器已经执行过关闭操作了，返回terminationFuture关闭结果给客户端 **/
        if (isShuttingDown()) {
            return terminationFuture();
        }
        boolean inEventLoop = inEventLoop();
        boolean wakeup;
        int oldState;
        /** 3 使用自旋锁(『自旋 + CAS』)的方式修改事件处理器状态ST_SHUTTING_DOWN **/
        for (;;) {
            //如果已经执行关闭，返回terminationFuture
            if (isShuttingDown()) {
                return terminationFuture();
            }
            int newState;
            wakeup = true;
            oldState = state;
            // 将newState设置为ST_SHUTTING_DOWN
            if (inEventLoop) {
                newState = ST_SHUTTING_DOWN;
            } else {
                switch (oldState) {
                    case ST_NOT_STARTED:
                    case ST_STARTED:
                        newState = ST_SHUTTING_DOWN;
                        break;
                    default:
                        newState = oldState;
                        wakeup = false;
                }
            }
            // 使用CAS设置新状态newState
            if (STATE_UPDATER.compareAndSet(this, oldState, newState)) {
                break;
            }
        }
        /**
         * 4 设置成员变量gracefulShutdownQuietPeriod、gracefulShutdownTimeout
         * gracefulShutdownQuietPeriod 表示静默时间
         * gracefulShutdownTimeout 表示等待关闭事件处理器的最大时间
         *  **/
        gracefulShutdownQuietPeriod = unit.toNanos(quietPeriod);
        gracefulShutdownTimeout = unit.toNanos(timeout);

        if (ensureThreadStarted(oldState)) {
            return terminationFuture;
        }
        /** 给任务队列添加一个WAKEUP_TASK线程，保证工作线程从任务队列从阻塞中释放 **/
        if (wakeup) {
            wakeup(inEventLoop);
        }

        return terminationFuture();
    }

    @Override
    public Future<?> terminationFuture() {
        return terminationFuture;
    }

    @Override
    @Deprecated
    public void shutdown() {
        if (isShutdown()) {
            return;
        }

        boolean inEventLoop = inEventLoop();
        boolean wakeup;
        int oldState;
        for (;;) {
            if (isShuttingDown()) {
                return;
            }
            int newState;
            wakeup = true;
            oldState = state;
            if (inEventLoop) {
                newState = ST_SHUTDOWN;
            } else {
                switch (oldState) {
                    case ST_NOT_STARTED:
                    case ST_STARTED:
                    case ST_SHUTTING_DOWN:
                        newState = ST_SHUTDOWN;
                        break;
                    default:
                        newState = oldState;
                        wakeup = false;
                }
            }
            if (STATE_UPDATER.compareAndSet(this, oldState, newState)) {
                break;
            }
        }

        if (ensureThreadStarted(oldState)) {
            return;
        }

        if (wakeup) {
            wakeup(inEventLoop);
        }
    }

    @Override
    public boolean isShuttingDown() {
        return state >= ST_SHUTTING_DOWN;
    }

    @Override
    public boolean isShutdown() {
        return state >= ST_SHUTDOWN;
    }

    @Override
    public boolean isTerminated() {
        return state == ST_TERMINATED;
    }

    /**
     * 每次事件循环中确认work线程能否从循环中退出，对应事件处理器关闭的场景。
     */
    protected boolean confirmShutdown() {
        //如果事件处理器状态为启动中，或未启动直接返回false
        if (!isShuttingDown()) {
            return false;
        }
        //如果当前线程不是事件处理器工作线程抛出异常
        if (!inEventLoop()) {
            throw new IllegalStateException("must be invoked from an event loop");
        }
        /** 进入到此说明已经调用了shutdown()或shutdownGracefully()方法，准备开始关闭事件处理器 **/

        //取消所有计划任务。
        cancelScheduledTasks();

        // 记录开始关闭的开始时间
        if (gracefulShutdownStartTime == 0) {
            gracefulShutdownStartTime = ScheduledFutureTask.nanoTime();
        }

        // runAllTasks()执行完成所有任务队列和计划任务队列中的任务，如果执行完成一个任务返回true
        // runAllTasks()返回true，执行所有shutdownHook任务，如果执行完成一个shutdownHook任务返回true
        if (runAllTasks() || runShutdownHooks()) {
            // 如果事件处理器以及关闭返回true
            if (isShutdown()) {
                return true;
            }
            // 如果未设置静默时间直接返回true,对于shutdown()没有设置静默时间这里返回
            if (gracefulShutdownQuietPeriod == 0) {
                return true;
            }

            // 如果事件处理器处于正在关闭状态，工作队列任务已经执行完毕，
            // 此时work由于无法从任务队列获取线程而阻塞于阻塞状态，未保证work线程正常退出
            // 给任务队列添加一个WAKEUP_TASK线程，保证工作线程从获取任务队列的阻塞中被唤醒
            wakeup(true);

            //由于静默时间大于0，因此还需要在在静默时间内观察是否有新的任务提交。
            return false;
        }
        // 获取运行时间 **/
        final long nanoTime = ScheduledFutureTask.nanoTime();

        // 如果静默时间超过gracefulShutdownTimeout返回true **/
        if (isShutdown() || nanoTime - gracefulShutdownStartTime > gracefulShutdownTimeout) {
            return true;
        }

        // 如果在静默时间内有新任务提交，休眠一段时间，返回true **/
        if (nanoTime - lastExecutionTime <= gracefulShutdownQuietPeriod) {
            wakeup(true);
            try {
                Thread.sleep(100);
            } catch (InterruptedException e) {
            }
            return false;
        }
        // 如果在静默时间内有没有新任务提交，返回true
        return true;
    }

    /**
     * 超时等待程事件处理器被终止关闭。
     */
    @Override
    public boolean awaitTermination(long timeout, TimeUnit unit) throws InterruptedException {
        if (unit == null) {
            throw new NullPointerException("unit");
        }
        if (inEventLoop()) {
            throw new IllegalStateException("cannot await termination of the current thread");
        }
        threadLock.await(timeout, unit);
        return isTerminated();
    }

    /**
     * 异步处理任务
     */
    @Override
    public void execute(Runnable task) {
        if (task == null) {
            throw new NullPointerException("task");
        }
        // 判断当前线程是work线程 **/
        boolean inEventLoop = inEventLoop();
        /**  任务添加到任务队列中  **/
        addTask(task);
        if (!inEventLoop) {
            /**  启动工作线程 **/
            startThread();
            /**  进入到此说明事件处理器正常退出**/

            //   如果单线程事件处理器关闭
            if (isShutdown()) {
                boolean reject = false;
                try {
                    //  将任务从任务队列删除
                    if (removeTask(task)) {
                        reject = true;
                    }
                } catch (UnsupportedOperationException e) {
                }
                //  抛出RejectedExecutionException异常
                if (reject) {
                    reject();
                }
            }
        }
        /**
         * 进入到此说明事件处理器已经关闭了
         * 在次判断是否需要向任务队列添加WAKEUP_TASK任务，需要满足以下条件
         * 1 判断当前获取任务类类型，如果不是NonWakeupRunnable,NonWakeupRunnable表示需要放进任务队列中不需要立即执行的任务
         * 2 且addTaskWakesUp为false
         * **/
        if (!addTaskWakesUp && wakesUpForTask(task)) {
            wakeup(inEventLoop);
        }
    }

    @Override
    public <T> T invokeAny(Collection<? extends Callable<T>> tasks) throws InterruptedException, ExecutionException {
        throwIfInEventLoop("invokeAny");
        return super.invokeAny(tasks);
    }

    @Override
    public <T> T invokeAny(Collection<? extends Callable<T>> tasks, long timeout, TimeUnit unit)
            throws InterruptedException, ExecutionException, TimeoutException {
        throwIfInEventLoop("invokeAny");
        return super.invokeAny(tasks, timeout, unit);
    }

    @Override
    public <T> List<java.util.concurrent.Future<T>> invokeAll(Collection<? extends Callable<T>> tasks)
            throws InterruptedException {
        throwIfInEventLoop("invokeAll");
        return super.invokeAll(tasks);
    }

    @Override
    public <T> List<java.util.concurrent.Future<T>> invokeAll(
            Collection<? extends Callable<T>> tasks, long timeout, TimeUnit unit) throws InterruptedException {
        throwIfInEventLoop("invokeAll");
        return super.invokeAll(tasks, timeout, unit);
    }

    private void throwIfInEventLoop(String method) {
        if (inEventLoop()) {
            throw new RejectedExecutionException("Calling " + method + " from within the EventLoop is not allowed");
        }
    }


    public final ThreadProperties threadProperties() {
        ThreadProperties threadProperties = this.threadProperties;
        if (threadProperties == null) {
            Thread thread = this.thread;
            if (thread == null) {
                assert !inEventLoop();
                submit(NOOP_TASK).syncUninterruptibly();
                thread = this.thread;
                assert thread != null;
            }

            threadProperties = new DefaultThreadProperties(thread);
            if (!PROPERTIES_UPDATER.compareAndSet(this, null, threadProperties)) {
                threadProperties = this.threadProperties;
            }
        }

        return threadProperties;
    }

    /**
     * 表示需要放进任务队列中不需要立即执行的任务
     */
    protected interface NonWakeupRunnable extends Runnable { }

    /**
     * 判断当前任务类不是NonWakeupRunnable,NonWakeupRunnable表示需要放进任务队列中不需要立即执行的任务
     */
    protected boolean wakesUpForTask(Runnable task) {
        return !(task instanceof NonWakeupRunnable);
    }


    /** 拒绝新任务 **/
    protected static void reject() {
        throw new RejectedExecutionException("event executor terminated");
    }

    /**
     * 拒绝新任务
     */
    protected final void reject(Runnable task) {
        rejectedExecutionHandler.rejected(task, this);
    }

    // ScheduledExecutorService implementation

    private static final long SCHEDULE_PURGE_INTERVAL = TimeUnit.SECONDS.toNanos(1);

    /**
     * 启动工作线程，只有传入参数为ST_NOT_STARTED才启动
     */
    private void startThread() {
        // 如果状态未启动，设置状态为已穷
        if (state == ST_NOT_STARTED) {
            if (STATE_UPDATER.compareAndSet(this, ST_NOT_STARTED, ST_STARTED)) {
                boolean success = false;
                try {
                    //启动工作线程
                    doStartThread();
                    success = true;
                } finally {
                    //如果不是正常退出，重新设置状态为未启动
                    if (!success) {
                        STATE_UPDATER.compareAndSet(this, ST_STARTED, ST_NOT_STARTED);
                    }
                }
            }
        }
    }

    /**
     * 启动工作线程，只有传入参数为ST_NOT_STARTED才启动
     */
    private boolean ensureThreadStarted(int oldState) {
        //只有传入参数为ST_NOT_STARTED才启动
        if (oldState == ST_NOT_STARTED) {
            try {
                //启动工作线程
                doStartThread();
            } catch (Throwable cause) {
                // 如果发送异常设置状态为ST_TERMINATED
                STATE_UPDATER.set(this, ST_TERMINATED);
                terminationFuture.tryFailure(cause);

                if (!(cause instanceof Exception)) {
                    // Also rethrow as it may be an OOME for example
                    PlatformDependent.throwException(cause);
                }
                return true;
            }
        }
        return false;
    }

    /**
     * 启动工作线程
     */
    private void doStartThread() {
        // 重置work 线程。
        assert thread == null;
        /** 1 通过ThreadPerTaskExecutor.execute(Runnable)方法来创建并启动执行任务的唯一线程 **/
        executor.execute(new Runnable() {
            @Override
            public void run() {
                thread = Thread.currentThread();
                // 是否需要标记work线程终止
                if (interrupted) {
                    thread.interrupt();
                }
                // 判断work线程是否时正常从事件循环中退出
                boolean success = false;
                // 更新最后执行任务时间
                updateLastExecutionTime();
                try {
                    /** 2 执行run模板方法，子类提实现事件循环操作 **/
                    SingleThreadEventExecutor.this.run();
                    //设置work正常退出
                    success = true;
                } catch (Throwable t) {
                    logger.warn("Unexpected exception from an event executor: ", t);
                } finally {
                    // 使用CAS+循环将当前状态设置为’ST_SHUTTING_DOWN’
                    for (;;) {
                        int oldState = state;
                        if (oldState >= ST_SHUTTING_DOWN || STATE_UPDATER.compareAndSet(
                                SingleThreadEventExecutor.this, oldState, ST_SHUTTING_DOWN)) {
                            break;
                        }
                    }
                    // 如果work是正常退出事件序号，但是并非通过confirmShutdown()方法导致退出，
                    // 那么就打印一个错误日志，告知当前的EventExecutor的实现是由问题的
                    if (success && gracefulShutdownStartTime == 0) {
                        if (logger.isErrorEnabled()) {
                            logger.error("Buggy " + EventExecutor.class.getSimpleName() + " implementation; " +
                                    SingleThreadEventExecutor.class.getSimpleName() + ".confirmShutdown() must " +
                                    "be called before run() implementation terminates.");
                        }
                    }

                    try {
                        // 再次调用一次『confirmShutdown()』，以确保所有的NioEventLoop中taskQueue中所有的任务以及用户自定义的所有shutdownHook也都执行了
                        for (;;) {
                            if (confirmShutdown()) {
                                break;
                            }
                        }
                    } finally {
                        try {
                            // 子类实现清理模板方法
                            cleanup();
                        } finally {
                            /** See https://github.com/netty/netty/issues/6596.  **/
                            FastThreadLocal.removeAll();
                            //   跟新时间处理器状态ST_TERMINATED
                            STATE_UPDATER.set(SingleThreadEventExecutor.this, ST_TERMINATED);
                            //   设置awaitTermination方法从阻塞中释放
                            threadLock.countDown();
                            if (logger.isWarnEnabled() && !taskQueue.isEmpty()) {
                                logger.warn("An event executor terminated with " +
                                        "non-empty task queue (" + taskQueue.size() + ')');
                            }
                            //  设置事件处理器关闭时结果为成功
                            terminationFuture.setSuccess(null);
                        }
                    }
                }
            }
        });
    }

    private static final class DefaultThreadProperties implements ThreadProperties {
        private final Thread t;

        DefaultThreadProperties(Thread t) {
            this.t = t;
        }

        @Override
        public State state() {
            return t.getState();
        }

        @Override
        public int priority() {
            return t.getPriority();
        }

        @Override
        public boolean isInterrupted() {
            return t.isInterrupted();
        }

        @Override
        public boolean isDaemon() {
            return t.isDaemon();
        }

        @Override
        public String name() {
            return t.getName();
        }

        @Override
        public long id() {
            return t.getId();
        }

        @Override
        public StackTraceElement[] stackTrace() {
            return t.getStackTrace();
        }

        @Override
        public boolean isAlive() {
            return t.isAlive();
        }
    }
}
