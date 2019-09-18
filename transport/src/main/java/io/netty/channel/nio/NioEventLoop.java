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
package io.netty.channel.nio;

import io.netty.channel.Channel;
import io.netty.channel.ChannelException;
import io.netty.channel.EventLoop;
import io.netty.channel.EventLoopException;
import io.netty.channel.EventLoopTaskQueueFactory;
import io.netty.channel.SelectStrategy;
import io.netty.channel.SingleThreadEventLoop;
import io.netty.util.IntSupplier;
import io.netty.util.concurrent.RejectedExecutionHandler;
import io.netty.util.internal.PlatformDependent;
import io.netty.util.internal.ReflectionUtil;
import io.netty.util.internal.SystemPropertyUtil;
import io.netty.util.internal.logging.InternalLogger;
import io.netty.util.internal.logging.InternalLoggerFactory;

import java.io.IOException;
import java.lang.reflect.Field;
import java.nio.channels.CancelledKeyException;
import java.nio.channels.SelectableChannel;
import java.nio.channels.Selector;
import java.nio.channels.SelectionKey;

import java.nio.channels.spi.SelectorProvider;
import java.security.AccessController;
import java.security.PrivilegedAction;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Iterator;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * {@link SingleThreadEventLoop} implementation which register the {@link Channel}'s to a
 * {@link Selector} and so does the multi-plexing of these in the event loop.
 *
 */
public final class NioEventLoop extends SingleThreadEventLoop {

    private static final InternalLogger logger = InternalLoggerFactory.getInstance(NioEventLoop.class);


    /**
     * TODO 1007 NioEventLoop cancel
     */
    private static final int CLEANUP_INTERVAL = 256; // XXX Hard-coded value, but won't need customization.

    /**
     * 是否禁用 SelectionKey 的优化，默认开启
     */
    private static final boolean DISABLE_KEY_SET_OPTIMIZATION =
            SystemPropertyUtil.getBoolean("io.netty.noKeySetOptimization", false);

    /**
     * 少于该 N 值，不开启空轮询重建新的 Selector 对象的功能
     */
    private static final int MIN_PREMATURE_SELECTOR_RETURNS = 3;

    /**
     * NIO Selector 空轮询该 N 次后，重建新的 Selector 对象，用以解决 JDK NIO 的 epoll 空轮询 Bug 。
     */
    private static final int SELECTOR_AUTO_REBUILD_THRESHOLD;

    private final IntSupplier selectNowSupplier = new IntSupplier() {
        @Override
        public int get() throws Exception {
            //立即( 无阻塞 )返回 Channel 新增的感兴趣的就绪 IO 事件数量
            return selectNow();
        }
    };


    static {
        /**
         * 解决 Selector#open() 方法，发生 NullPointException 异常。
         * 细解析，见 http://bugs.sun.com/view_bug.do?bug_id=6427854 和 https://github.com/netty/netty/issues/203
         */
        final String key = "sun.nio.ch.bugLevel";
        final String bugLevel = SystemPropertyUtil.get(key);
        if (bugLevel == null) {
            try {
                AccessController.doPrivileged(new PrivilegedAction<Void>() {
                    @Override
                    public Void run() {
                        System.setProperty(key, "");
                        return null;
                    }
                });
            } catch (final SecurityException e) {
                logger.debug("Unable to get/set System Property: " + key, e);
            }
        }

        /** 初始化 SELECTOR_AUTO_REBUILD_THRESHOLD 属性 **/
        int selectorAutoRebuildThreshold = SystemPropertyUtil.getInt("io.netty.selectorAutoRebuildThreshold", 512);
        if (selectorAutoRebuildThreshold < MIN_PREMATURE_SELECTOR_RETURNS) {
            selectorAutoRebuildThreshold = 0;
        }
        SELECTOR_AUTO_REBUILD_THRESHOLD = selectorAutoRebuildThreshold;

        if (logger.isDebugEnabled()) {
            logger.debug("-Dio.netty.noKeySetOptimization: {}", DISABLE_KEY_SET_OPTIMIZATION);
            logger.debug("-Dio.netty.selectorAutoRebuildThreshold: {}", SELECTOR_AUTO_REBUILD_THRESHOLD);
        }
    }

    /**
     * 包装的 Selector 对象，经过优化，
     * 将属性设置成SelectedSelectionKeySet
     * Selector对象中selectedKeys,publicSelectedKey原始属性类型为hashSet需要O(lgn)的时间复杂度将 SelectionKey 塞到 set
     * 优化Selector将selectedKeys,publicSelectedKey属性设置成SelectedSelectionKeySet对象，只需要O(1)的时间复杂度就能将 SelectionKey 塞到 set*/
    private Selector selector;
    /**  未包装的 Selector 对象 **/
    private Selector unwrappedSelector;
    /**  注册的 SelectionKey 集合。Netty 自己实现，经过优化 **/
    private SelectedSelectionKeySet selectedKeys;
    /**  SelectorProvider 对象，用于创建 Selector 对象 **/
    private final SelectorProvider provider;
    /**  唤醒标记。因为唤醒方法 {@link Selector#wakeup()} 开销比较大，通过该标识，减少调用。 **/
    private final AtomicBoolean wakenUp = new AtomicBoolean();
    private volatile long nextWakeupTime = Long.MAX_VALUE;
    /**   Select 策略 **/
    private final SelectStrategy selectStrategy;
    /**  处理 Channel 的就绪的 IO 事件，占处理任务的总时间的比例 **/
    private volatile int ioRatio = 50;
    /**  取消 SelectionKey 的数量 **/
    private int cancelledKeys;
    /**  是否需要再次 select Selector 对象 **/
    private boolean needsToSelectAgain;

    NioEventLoop(NioEventLoopGroup parent, Executor executor, SelectorProvider selectorProvider,
                 SelectStrategy strategy, RejectedExecutionHandler rejectedExecutionHandler,
                 EventLoopTaskQueueFactory queueFactory) {
        /** 实例化父类SingleThreadEventLoop **/
        super(parent, executor, false, newTaskQueue(queueFactory), newTaskQueue(queueFactory),
                rejectedExecutionHandler);
        if (selectorProvider == null) {
            throw new NullPointerException("selectorProvider");
        }
        if (strategy == null) {
            throw new NullPointerException("selectStrategy");
        }
        provider = selectorProvider;
        final SelectorTuple selectorTuple = openSelector();
        selector = selectorTuple.selector;
        unwrappedSelector = selectorTuple.unwrappedSelector;
        selectStrategy = strategy;
    }

    /**
     * 创建任务队列
     */
    private static Queue<Runnable> newTaskQueue(
            EventLoopTaskQueueFactory queueFactory) {
        if (queueFactory == null) {
            return newTaskQueue0(DEFAULT_MAX_PENDING_TASKS);
        }
        return queueFactory.newTaskQueue(DEFAULT_MAX_PENDING_TASKS);
    }

    /**
     * 创建 mpsc 队列
     * mpsc 是 multiple producers and a single consumer 的缩写。
     * mpsc 是对多线程生产任务，单线程消费任务的消费，恰好符合 NioEventLoop 的情况。
     */
    private static Queue<Runnable> newTaskQueue0(int maxPendingTasks) {
        // This event loop never calls takeTask()
        return maxPendingTasks == Integer.MAX_VALUE ? PlatformDependent.<Runnable>newMpscQueue()
                : PlatformDependent.<Runnable>newMpscQueue(maxPendingTasks);
    }

    private static final class SelectorTuple {
        final Selector unwrappedSelector;
        final Selector selector;

        SelectorTuple(Selector unwrappedSelector) {
            this.unwrappedSelector = unwrappedSelector;
            this.selector = unwrappedSelector;
        }

        SelectorTuple(Selector unwrappedSelector, Selector selector) {
            this.unwrappedSelector = unwrappedSelector;
            this.selector = selector;
        }
    }

    /**
     * 创建 Selector 对象
     * Netty对Selector进行了简单的优化，通过反射将Selector对象中selectedKeys,publicSelectedKey属性设置成SelectedSelectionKeySet
     */
    private SelectorTuple openSelector() {
        // 创建 Selector 对象，作为 unwrappedSelector
        final Selector unwrappedSelector;
        try {
            unwrappedSelector = provider.openSelector();
        } catch (IOException e) {
            throw new ChannelException("failed to open a new selector", e);
        }
        // 禁用 SelectionKey 的优化，则直接返回 SelectorTuple 对象。即，selector 也使用 unwrappedSelector
        if (DISABLE_KEY_SET_OPTIMIZATION) {
            return new SelectorTuple(unwrappedSelector);
        }

        // 获得 SelectorImpl 类
        Object maybeSelectorImplClass = AccessController.doPrivileged(new PrivilegedAction<Object>() {
            @Override
            public Object run() {
                try {
                    return Class.forName(
                            "sun.nio.ch.SelectorImpl",
                            false,
                            PlatformDependent.getSystemClassLoader());
                } catch (Throwable cause) {
                    return cause;
                }
            }
        });
        // 获得 SelectorImpl 类失败，则直接返回 SelectorTuple 对象。即，selector 也使用 unwrappedSelector 。
        if (!(maybeSelectorImplClass instanceof Class) ||
            !((Class<?>) maybeSelectorImplClass).isAssignableFrom(unwrappedSelector.getClass())) {
            if (maybeSelectorImplClass instanceof Throwable) {
                Throwable t = (Throwable) maybeSelectorImplClass;
                logger.trace("failed to instrument a special java.util.Set into: {}", unwrappedSelector, t);
            }
            return new SelectorTuple(unwrappedSelector);
        }

        // 创建 SelectedSelectionKeySet 对象
        final Class<?> selectorImplClass = (Class<?>) maybeSelectorImplClass;
        final SelectedSelectionKeySet selectedKeySet = new SelectedSelectionKeySet();


        // 设置 SelectedSelectionKeySet 对象到 unwrappedSelector 中
        Object maybeException = AccessController.doPrivileged(new PrivilegedAction<Object>() {
            @Override
            public Object run() {
                try {
                    // 获得 "selectedKeys" "publicSelectedKeys" 的 Field
                    Field selectedKeysField = selectorImplClass.getDeclaredField("selectedKeys");
                    Field publicSelectedKeysField = selectorImplClass.getDeclaredField("publicSelectedKeys");

                    if (PlatformDependent.javaVersion() >= 9 && PlatformDependent.hasUnsafe()) {
                        long selectedKeysFieldOffset = PlatformDependent.objectFieldOffset(selectedKeysField);
                        long publicSelectedKeysFieldOffset =
                                PlatformDependent.objectFieldOffset(publicSelectedKeysField);

                        if (selectedKeysFieldOffset != -1 && publicSelectedKeysFieldOffset != -1) {
                            PlatformDependent.putObject(
                                    unwrappedSelector, selectedKeysFieldOffset, selectedKeySet);
                            PlatformDependent.putObject(
                                    unwrappedSelector, publicSelectedKeysFieldOffset, selectedKeySet);
                            return null;
                        }
                    }

                    // 设置 Field 可访问
                    Throwable cause = ReflectionUtil.trySetAccessible(selectedKeysField, true);
                    if (cause != null) {
                        return cause;
                    }
                    cause = ReflectionUtil.trySetAccessible(publicSelectedKeysField, true);
                    if (cause != null) {
                        return cause;
                    }
                    // 设置 SelectedSelectionKeySet 对象到 unwrappedSelector 的 Field 中
                    selectedKeysField.set(unwrappedSelector, selectedKeySet);
                    publicSelectedKeysField.set(unwrappedSelector, selectedKeySet);
                    return null;
                } catch (NoSuchFieldException e) {
                    return e;
                } catch (IllegalAccessException e) {
                    return e;
                }
            }
        });

        // 设置 SelectedSelectionKeySet 对象到 unwrappedSelector 中失败，则直接返回 SelectorTuple 对象。即，selector 也使用 unwrappedSelector 。
        if (maybeException instanceof Exception) {
            selectedKeys = null;
            Exception e = (Exception) maybeException;
            logger.trace("failed to instrument a special java.util.Set into: {}", unwrappedSelector, e);
            return new SelectorTuple(unwrappedSelector);
        }

        // 设置 SelectedSelectionKeySet 对象到 selectedKeys 中
        selectedKeys = selectedKeySet;


        // 创建 SelectedSelectionKeySetSelector 对象
        // 创建 SelectorTuple 对象。即，selector 也使用 SelectedSelectionKeySetSelector 对象。
        logger.trace("instrumented a special java.util.Set into: {}", unwrappedSelector);
        return new SelectorTuple(unwrappedSelector,
                                 new SelectedSelectionKeySetSelector(unwrappedSelector, selectedKeySet));
    }

    /**
     * Returns the {@link SelectorProvider} used by this {@link NioEventLoop} to obtain the {@link Selector}.
     */
    public SelectorProvider selectorProvider() {
        return provider;
    }

    @Override
    protected Queue<Runnable> newTaskQueue(int maxPendingTasks) {
        return newTaskQueue0(maxPendingTasks);
    }



    /**
     * 注册 Java NIO Channel ( 不一定需要通过 Netty 创建的 Channel )到 Selector 上，相当于说，也注册到了 EventLoop 上。
     */
    public void register(final SelectableChannel ch, final int interestOps, final NioTask<?> task) {
        if (ch == null) {
            throw new NullPointerException("ch");
        }
        if (interestOps == 0) {
            throw new IllegalArgumentException("interestOps must be non-zero.");
        }
        if ((interestOps & ~ch.validOps()) != 0) {
            throw new IllegalArgumentException(
                    "invalid interestOps: " + interestOps + "(validOps: " + ch.validOps() + ')');
        }
        if (task == null) {
            throw new NullPointerException("task");
        }

        if (isShutdown()) {
            throw new IllegalStateException("event loop shut down");
        }

        if (inEventLoop()) {
            register0(ch, interestOps, task);
        } else {
            try {
                submit(new Runnable() {
                    @Override
                    public void run() {
                        register0(ch, interestOps, task);
                    }
                }).sync();
            } catch (InterruptedException ignore) {
                Thread.currentThread().interrupt();
            }
        }
    }

    private void register0(SelectableChannel ch, int interestOps, NioTask<?> task) {
        try {
            ch.register(unwrappedSelector, interestOps, task);
        } catch (Exception e) {
            throw new EventLoopException("failed to register a channel", e);
        }
    }


    public int getIoRatio() {
        return ioRatio;
    }

    public void setIoRatio(int ioRatio) {
        if (ioRatio <= 0 || ioRatio > 100) {
            throw new IllegalArgumentException("ioRatio: " + ioRatio + " (expected: 0 < ioRatio <= 100)");
        }
        this.ioRatio = ioRatio;
    }

    /**
     * 重建 Selector 对象
     * 该方法用于 NIO Selector 发生 epoll bug 时，重建 Selector 对象。
     */
    public void rebuildSelector() {
        if (!inEventLoop()) {
            execute(new Runnable() {
                @Override
                public void run() {
                    rebuildSelector0();
                }
            });
            return;
        }
        rebuildSelector0();
    }

    /**
     * 重建 Selector 对象
     */
    @Override
    public int registeredChannels() {
        return selector.keys().size() - cancelledKeys;
    }

    /**
     * 主要是需要将老的 Selector 对象的“数据”复制到新的 Selector 对象上，并关闭老的 Selector 对象。
     */
    private void rebuildSelector0() {
        final Selector oldSelector = selector;
        final SelectorTuple newSelectorTuple;

        if (oldSelector == null) {
            return;
        }

        // 创建新的 Selector 对象
        try {
            newSelectorTuple = openSelector();
        } catch (Exception e) {
            logger.warn("Failed to create a new Selector.", e);
            return;
        }


        int nChannels = 0;
        // 将注册在 原始Selector上的所有 Channel ，注册到新创建 Selector 对象上
        for (SelectionKey key: oldSelector.keys()) {
            Object a = key.attachment();
            try {
                //校验 SelectionKey 有效，并且 Java NIO Channel 并未注册在新的 Selector 对象上。
                if (!key.isValid() || key.channel().keyFor(newSelectorTuple.unwrappedSelector) != null) {
                    continue;
                }
                //获取原始注册事件
                int interestOps = key.interestOps();
                //调用 SelectionKey#cancel() 方法，取消老的 SelectionKey 。
                key.cancel();
                //将 Java NIO Channel 注册到新的 Selector 对象上，返回新的 SelectionKey 对象
                SelectionKey newKey = key.channel().register(newSelectorTuple.unwrappedSelector, interestOps, a);
                //修改 Channel 的 selectionKey 指向新的 SelectionKey 对象
                if (a instanceof AbstractNioChannel) {
                    ((AbstractNioChannel) a).selectionKey = newKey;
                }
                nChannels ++;
            }
            //当发生异常时候，根据不同的 SelectionKey 的 attachment 来判断处理方式：
            catch (Exception e) {
                logger.warn("Failed to re-register a Channel to the new Selector.", e);
                //当 attachment 是 Netty NIO Channel 时，调用 Unsafe#close(ChannelPromise promise) 方法，关闭发生异常的 Channel 。
                if (a instanceof AbstractNioChannel) {
                    AbstractNioChannel ch = (AbstractNioChannel) a;
                    ch.unsafe().close(ch.unsafe().voidPromise());
                }
                //当 attachment 是 Netty NioTask 时，调用 #invokeChannelUnregistered(NioTask<SelectableChannel> task,
                // SelectionKey k, Throwable cause) 方法，通知 Channel 取消注册
                else {
                    @SuppressWarnings("unchecked")
                    NioTask<SelectableChannel> task = (NioTask<SelectableChannel>) a;
                    invokeChannelUnregistered(task, key, e);
                }
            }
        }

        //修改 selector 和 unwrappedSelector 指向新的 Selector 对象。
        selector = newSelectorTuple.selector;
        unwrappedSelector = newSelectorTuple.unwrappedSelector;

        try {
            //调用 Selector#close() 方法，关闭老的 Selector 对象
            oldSelector.close();
        } catch (Throwable t) {
            if (logger.isWarnEnabled()) {
                logger.warn("Failed to close the old Selector.", t);
            }
        }

        if (logger.isInfoEnabled()) {
            logger.info("Migrated " + nChannels + " channel(s) to the new Selector.");
        }
    }

    @Override
    protected void run() {
        for (;;) {
            try {
                try {
                    //调用 SelectStrategy#calculateStrategy(IntSupplier selectSupplier, boolean hasTasks) 方法，
                    //在参数hasTasks()中获取任务队列中是否存在执行的任务,
                    // 如果存在任务调用selector.selectNow()不阻塞并并返回是否存在事件变更
                    // 如果不存在任务，返回-1 进入下面select(wakenUp.getAndSet(false));
                    switch (selectStrategy.calculateStrategy(selectNowSupplier, hasTasks())) {
                    // 默认实现下，不存在这个情况。
                    case SelectStrategy.CONTINUE:
                        continue;
                    // 默认实现下，不存在这个情况。
                    case SelectStrategy.BUSY_WAIT:
                    //如果不存在任务时进入
                    case SelectStrategy.SELECT:
                        //每次调用select前都会重置 wakeUp 标识为 false ，并返回修改前的值
                        //调用 #select(boolean oldWakeUp) 方法，获取IO线程或者又新任务提交，又或者延时任务需要被触发返回
                        select(wakenUp.getAndSet(false));


                        //1）在 wakenUp.getAndSet(false) 和 #select(boolean oldWakeUp) 之间，在标识 wakeUp 设置为 false 时，在 #select(boolean oldWakeUp) 方法中，正在调用 Selector#select(...) 方法，处于阻塞中。
                        //2）此时，有另外的线程调用了 #wakeup() 方法，会将标记 wakeUp 设置为 true ，并唤醒 Selector#select(...) 方法的阻塞等待。
                        //3）标识 wakeUp 为 true ，所以再有另外的线程调用 #wakeup() 方法，都无法唤醒 Selector#select(...) 。为什么呢？因为 #wakeup() 的 CAS 修改 false => true 会失败，导致无法调用 Selector#wakeup() 方法。
                        //解决方式：所以在 #select(boolean oldWakeUp) 执行完后，增加了【第 41 至 44 行】来解决
                        if (wakenUp.get()) {
                            selector.wakeup();
                        }
                        // fall through
                    default:
                    }
                } catch (IOException e) {
                    //重建 Selector 对象
                    rebuildSelector0();
                    handleLoopException(e);
                    continue;
                }

                cancelledKeys = 0;
                needsToSelectAgain = false;
                final int ioRatio = this.ioRatio;
                //ioRatio 为 100 ，则不考虑时间占比的分配。
                if (ioRatio == 100) {
                    try {
                        //调用 #processSelectedKeys() 方法，处理 Channel 感兴趣的就绪 IO 事件
                        processSelectedKeys();
                    } finally {
                        // 执行所有可以执行的计划任务，和任务队列任务
                        runAllTasks();
                    }
                }
                //ioRatio 为 < 100 ，则考虑时间占比的分配
                else {
                    final long ioStartTime = System.nanoTime();
                    try {
                        processSelectedKeys();
                    } finally {
                        // 在指定的时间，执行所有任务队列和计划任务队列中可执行的任务
                        final long ioTime = System.nanoTime() - ioStartTime;
                        runAllTasks(ioTime * (100 - ioRatio) / ioRatio);
                    }
                }
            } catch (Throwable t) {
                handleLoopException(t);
            }
            // 即使循环处理引发异常，也始终处理关闭。
            try {
                if (isShuttingDown()) {
                    //关闭所有注册到选择器中AbstractNioChannel
                    closeAll();
                    //每次事件循环中确认work线程能否从循环中退出，对应事件处理器关闭的场景。
                    if (confirmShutdown()) {
                        return;
                    }
                }
            } catch (Throwable t) {
                handleLoopException(t);
            }
        }
    }

    private static void handleLoopException(Throwable t) {
        logger.warn("Unexpected exception in the selector loop.", t);

        // Prevent possible consecutive immediate failures that lead to
        // excessive CPU consumption.
        try {
            Thread.sleep(1000);
        } catch (InterruptedException e) {
            // Ignore.
        }
    }

    /**
     * 调用 #processSelectedKeys() 方法，处理 Channel 新增就绪的 IO 事件
     */
    private void processSelectedKeys() {
        //当 selectedKeys 非空，意味着使用优化的 SelectedSelectionKeySetSelector ，所以调用 #processSelectedKeysOptimized() 方
        if (selectedKeys != null) {
            processSelectedKeysOptimized();
        } else {
            processSelectedKeysPlain(selector.selectedKeys());
        }
    }

    @Override
    protected void cleanup() {
        try {
            selector.close();
        } catch (IOException e) {
            logger.warn("Failed to close a selector.", e);
        }
    }

    void cancel(SelectionKey key) {
        key.cancel();
        cancelledKeys ++;
        if (cancelledKeys >= CLEANUP_INTERVAL) {
            cancelledKeys = 0;
            needsToSelectAgain = true;
        }
    }

    @Override
    protected Runnable pollTask() {
        Runnable task = super.pollTask();
        if (needsToSelectAgain) {
            selectAgain();
        }
        return task;
    }

    /**
     * 基于 Java NIO 原生 Selecotr ，处理 Channel 新增就绪的 IO 事件
     */
    private void processSelectedKeysPlain(Set<SelectionKey> selectedKeys) {
        // check if the set is empty and if so just return to not create garbage by
        // creating a new Iterator every time even if there is nothing to process.
        // See https://github.com/netty/netty/issues/597
        if (selectedKeys.isEmpty()) {
            return;
        }

        // 遍历 SelectionKey 迭代器
        Iterator<SelectionKey> i = selectedKeys.iterator();
        for (;;) {
            // 获得 SelectionKey 对象
            final SelectionKey k = i.next();
            final Object a = k.attachment();
            // 从迭代器中移除
            i.remove();

            // 处理一个 Channel 就绪的 IO 事件
            if (a instanceof AbstractNioChannel) {
                processSelectedKey(k, (AbstractNioChannel) a);
            }
            // 使用 NioTask 处理一个 Channel 就绪的 IO 事件
            else {
                @SuppressWarnings("unchecked")
                NioTask<SelectableChannel> task = (NioTask<SelectableChannel>) a;
                processSelectedKey(k, task);
            }

            //// 无下一个节点，结束
            if (!i.hasNext()) {
                break;
            }

            //TODO 1007 NioEventLoop cancel 方法
            if (needsToSelectAgain) {
                selectAgain();
                selectedKeys = selector.selectedKeys();

                // Create the iterator again to avoid ConcurrentModificationException
                if (selectedKeys.isEmpty()) {
                    break;
                } else {
                    i = selectedKeys.iterator();
                }
            }
        }
    }

    /**
     * 基于 Netty SelectedSelectionKeySetSelector ，处理 Channel 新增就绪的 IO 事件
     */
    private void processSelectedKeysOptimized() {
        for (int i = 0; i < selectedKeys.size; ++i) {
            final SelectionKey k = selectedKeys.keys[i];
            // 置空，原因见 https://github.com/netty/netty/issues/2363 。
            selectedKeys.keys[i] = null;

            final Object a = k.attachment();
            //当 attachment 是 Netty NIO Channel 时，
            //调用 #processSelectedKey(SelectionKey k, AbstractNioChannel ch) 方法，处理一个 Channel 就绪的 IO 事件
            if (a instanceof AbstractNioChannel) {
                processSelectedKey(k, (AbstractNioChannel) a);
            }
            //当 attachment 是 Netty NioTask 时，
            //调用 #processSelectedKey(SelectionKey k, NioTask<SelectableChannel> task) 方法，使用 NioTask 处理一个 Channel 的 IO 事件
            else {
                @SuppressWarnings("unchecked")
                NioTask<SelectableChannel> task = (NioTask<SelectableChannel>) a;
                processSelectedKey(k, task);
            }

            if (needsToSelectAgain) {
                // null输出数组中的条目，以便在Channel关闭后允许GC'ed
                // 原因见 https://github.com/netty/netty/issues/2363
                selectedKeys.reset(i + 1);

                selectAgain();
                i = -1;
            }
        }
    }

    /**
     * 处理一个 Channel 就绪的 IO 事件
     */
    private void processSelectedKey(SelectionKey k, AbstractNioChannel ch) {
        final AbstractNioChannel.NioUnsafe unsafe = ch.unsafe();
        // 如果 SelectionKey 是不合法的，则关闭 Channel
        if (!k.isValid()) {
            final EventLoop eventLoop;
            try {
                eventLoop = ch.eventLoop();
            } catch (Throwable ignored) {
                // If the channel implementation throws an exception because there is no event loop, we ignore this
                // because we are only trying to determine if ch is registered to this event loop and thus has authority
                // to close ch.
                return;
            }
            // Only close ch if ch is still registered to this EventLoop. ch could have deregistered from the event loop
            // and thus the SelectionKey could be cancelled as part of the deregistration process, but the channel is
            // still healthy and should not be closed.
            // See https://github.com/netty/netty/issues/5125
            if (eventLoop != this || eventLoop == null) {
                return;
            }
            // close the channel if the key is not valid anymore
            unsafe.close(unsafe.voidPromise());
            return;
        }

        try {
            // 获得就绪的 IO 事件的 ops
            int readyOps = k.readyOps();
            // OP_CONNECT 事件就绪
            if ((readyOps & SelectionKey.OP_CONNECT) != 0) {
                // 移除对 OP_CONNECT 感兴趣
                int ops = k.interestOps();
                ops &= ~SelectionKey.OP_CONNECT;
                k.interestOps(ops);
                // 完成连接
                unsafe.finishConnect();
            }

            // OP_WRITE 事件就绪
            if ((readyOps & SelectionKey.OP_WRITE) != 0) {
                // 向 Channel 写入数据
                ch.unsafe().forceFlush();
            }

            // SelectionKey.OP_READ 或 SelectionKey.OP_ACCEPT 就绪
            // readyOps == 0 是对 JDK Bug 的处理，防止空的死循环
            if ((readyOps & (SelectionKey.OP_READ | SelectionKey.OP_ACCEPT)) != 0 || readyOps == 0) {
                // 使用 unsafe 读取数据,unsafe 属于 Channel 内部子组件 不同的 Channel 类对应不同的 Unsafe 实现类 对于不同unsafe实现读取含义是不同的
                // 对于 NioServerSocketChannel ，Unsafe 的实现类为 NioMessageUnsafe，用来处理建立连接  OP_ACCEPT
                // 对于 NioSocketChannel，Unsafe 的实现类为 NioSocketChannelUnsafe，用来处理读取数据  OP_READ
                unsafe.read();
            }
        } catch (CancelledKeyException ignored) {
            // 发生异常，关闭 Channel
            unsafe.close(unsafe.voidPromise());
        }
    }

    /**
     * 通过NioTask处理IO事件，
     * 实际上，NioTask 在 Netty 自身中并未有相关的实现类，因此这里只是扩展可以忽略
     */
    private static void processSelectedKey(SelectionKey k, NioTask<SelectableChannel> task) {
        // 未执行
        int state = 0;
        try {
            // 调用 NioTask 的 Channel 就绪事件
            task.channelReady(k.channel(), k);
            // 执行成功
            state = 1;
        } catch (Exception e) {
            // SelectionKey 取消
            k.cancel();
            // 执行 Channel 取消注册
            invokeChannelUnregistered(task, k, e);
            // 执行异常
            state = 2;
        } finally {
            switch (state) {
            case 0:
                // SelectionKey 取消
                k.cancel();
                // 执行 Channel 取消注册
                invokeChannelUnregistered(task, k, null);
                break;
            case 1:
                // SelectionKey 不合法，则执行 Channel 取消注册
                if (!k.isValid()) {
                    invokeChannelUnregistered(task, k, null);
                }
                break;
            }
        }
    }

    /**
     * 关闭所有注册到选择器中AbstractNioChannel
     */
    private void closeAll() {
        selectAgain();
        Set<SelectionKey> keys = selector.keys();
        Collection<AbstractNioChannel> channels = new ArrayList<AbstractNioChannel>(keys.size());
        for (SelectionKey k: keys) {
            Object a = k.attachment();
            if (a instanceof AbstractNioChannel) {
                channels.add((AbstractNioChannel) a);
            } else {
                k.cancel();
                @SuppressWarnings("unchecked")
                NioTask<SelectableChannel> task = (NioTask<SelectableChannel>) a;
                invokeChannelUnregistered(task, k, null);
            }
        }

        for (AbstractNioChannel ch: channels) {
            ch.unsafe().close(ch.unsafe().voidPromise());
        }
    }

    /**
     * 执行 Channel 取消注册
     */
    private static void invokeChannelUnregistered(NioTask<SelectableChannel> task, SelectionKey k, Throwable cause) {
        try {
            task.channelUnregistered(k.channel(), cause);
        } catch (Exception e) {
            logger.warn("Unexpected exception while running NioTask.channelUnregistered()", e);
        }
    }

    /**
     * <1> 处，因为 NioEventLoop 的线程阻塞，主要是调用 Selector#select(long timeout) 方法，
     * 阻塞等待有 Channel 感兴趣的 IO 事件，或者超时。所以需要调用 Selector#wakeup() 方法，进行唤醒 Selector 。
     * <2> 处，因为 Selector#wakeup() 方法的唤醒操作是开销比较大的操作，并且每次重复调用相当于重复唤醒。
     * 所以，通过 wakenUp 属性，通过 CAS 修改 false => true ，保证有且仅有进行一次唤醒。
     */
    @Override
    protected void wakeup(boolean inEventLoop) {
        if (!inEventLoop && wakenUp.compareAndSet(false, true)) {
            selector.wakeup();
        }
    }

    @Override
    protected boolean beforeScheduledTaskSubmitted(long deadlineNanos) {
        return deadlineNanos < nextWakeupTime;
    }

    @Override
    protected boolean afterScheduledTaskSubmitted(long deadlineNanos) {
        return deadlineNanos < nextWakeupTime;
    }

    Selector unwrappedSelector() {
        return unwrappedSelector;
    }

    int selectNow() throws IOException {
        try {
            //调用 Selector#selectorNow() 方法，立即( 无阻塞 )返回 Channel 新增的感兴趣的就绪 IO 事件数量
            return selector.selectNow();
        } finally {
            // 若唤醒标识 wakeup 为 true 时，调用 Selector#wakeup() 方法，唤醒 Selector 。因为 <1> 处的 Selector#selectorNow()
            // 会使用我们对 Selector 的唤醒，所以需要进行复原。有一个冷知道，可能有胖友不知道：
            if (wakenUp.get()) {
                selector.wakeup();
            }
        }
    }

    private void select(boolean oldWakenUp) throws IOException {
        // 记录下 Selector 对象
        Selector selector = this.selector;
        try {
            // select 计数器
            int selectCnt = 0;
            // 记录当前时间，单位：纳秒
            long currentTimeNanos = System.nanoTime();
            // 获取计划任务队列中第一个计划任务并计算截止时间，单位：纳秒
            long selectDeadLineNanos = currentTimeNanos + delayNanos(currentTimeNanos);

            long normalizedDeadlineNanos = selectDeadLineNanos - initialNanoTime();
            if (nextWakeupTime != normalizedDeadlineNanos) {
                nextWakeupTime = normalizedDeadlineNanos;
            }
            /**
             * select操作也是一个for循环，何时退出
             * 1.定时任务截止事时间快到了，selectCnt重置为1，中断本次轮询
             *
             * 2.轮询过程中发现有任务加入，selectCnt重置为1，中断本次轮询，
             *
             * 3 阻塞式select操作等待IO，这里的超时正好是1中快到的时间，selectCnt+1
             *
             * 4 判断能否从for循环中退出，这里可以归为几种情况
             *   4.1 步骤3返回是由于IO事件到达
             *   4.2 步骤3超时返回，且任务队列中存在新的任务或计划任务队列存在新的任务
             *   4.3 其他线程新任务时会wakenUp标识设置为true,同时调用wakeup()方法导致步骤3返回
             *   4.4 原始就为true,表示当前select不轮询了。
             *
             *
             * 解决jdk的nio bug
             * 关于该bug的描述见 http://bugs.java.com/bugdatabase/view_bug.do?bug_id=6595055)
             *
             * 该bug会导致Selector一直空轮询，最终导致cpu 100%，nio server不可用，严格意义上来说，netty没有解决jdk的bug，而是通过一种方式来巧妙地避开了这个bug
             * 1 在进入循环前记录时间currentTimeNanos
             * 2 在步骤4并没有退出时重新记录一个新的时间
             * 3 在步骤4后如果两个时间的差值大于步骤3的超时时间，说明步骤3超时返回，且并没有命中步骤4的条件，说明正常
             *   在步骤4后如果两个时间的差值小于于步骤3的超时时间，说明发送JDKBUG,并判断selectCnt是否大于MIN_PREMATURE_SELECTOR_RETURNS
             *   如果大于则重置Selector
             *
             *
             *
             * **/
            for (;;) {
                /**
                 * 在for循环第一步中，如果发现当前的定时任务队列中有任务的截止事件快到了(<=0.5ms)，就跳出循环。
                 * 此外，跳出之前如果发现目前为止还没有进行过select操作（if (selectCnt == 0)），那么就调用一次selectNow()，该方法会立即返回，不会阻塞
                 * 同时重置selectCnt
                 */
                // 计算本次 select 的超时时长，单位：毫秒。
                // + 500000L 是为了四舍五入
                // / 1000000L 是为了纳秒转为毫秒
                long timeoutMillis = (selectDeadLineNanos - currentTimeNanos + 500000L) / 1000000L;

                // 如果超时时长，则结束 select
                if (timeoutMillis <= 0) {
                    //跳出之前如果发现目前为止还没有进行过select操作
                    if (selectCnt == 0) {
                        // selectNow 一次，非阻塞
                        selector.selectNow();
                        // 重置 select 计数器
                        selectCnt = 1;
                    }
                    break;
                }

                /** 在for循环第二步中 若有新的任务加入 ,设置wakeUp 标识为 true **/
                if (hasTasks() && wakenUp.compareAndSet(false, true)) {
                    // selectNow 一次，非阻塞
                    selector.selectNow();
                    // 重置 select 计数器
                    selectCnt = 1;
                    break;
                }

                /** 在for循环第三步中 阻塞 select ，查询 Channel 是否有就绪的 IO 事件，这里超时刚好是又计划任务要执行就退出， **/
                int selectedKeys = selector.select(timeoutMillis);
                // select 计数器 ++
                selectCnt ++;

                // 结束 select ，如果满足下面任一一个条件
                if (selectedKeys != 0 || oldWakenUp || wakenUp.get() || hasTasks() || hasScheduledTasks()) {
                    break;
                }

                // 线程被打断。一般情况下不会出现，出现基本是 bug ，或者错误使用。
                if (Thread.interrupted()) {
                    if (logger.isDebugEnabled()) {
                        logger.debug("Selector.select() returned prematurely because " +
                                "Thread.currentThread().interrupt() was called. Use " +
                                "NioEventLoop.shutdownGracefully() to shutdown the NioEventLoop.");
                    }
                    selectCnt = 1;
                    break;
                }

                // 记录当前时间
                long time = System.nanoTime();
                // time - currentTimeNanos =TimeUnit.MILLISECONDS.toNanos(timeoutMillis)
                // 表示是超时之后返回
                if (time - TimeUnit.MILLISECONDS.toNanos(timeoutMillis) >= currentTimeNanos) {
                    // timeoutMillis elapsed without anything selected.
                    selectCnt = 1;
                }
                // 不符合 select 超时的提交，若 select 次数到达重建 Selector 对象的上限，进行重建
                else if (SELECTOR_AUTO_REBUILD_THRESHOLD > 0 &&
                        selectCnt >= SELECTOR_AUTO_REBUILD_THRESHOLD) {
                    selector = selectRebuildSelector(selectCnt);
                    // 重置 selectCnt 为 1
                    selectCnt = 1;
                    break;
                }

                currentTimeNanos = time;
            }

            if (selectCnt > MIN_PREMATURE_SELECTOR_RETURNS) {
                if (logger.isDebugEnabled()) {
                    logger.debug("Selector.select() returned prematurely {} times in a row for Selector {}.",
                            selectCnt - 1, selector);
                }
            }
        } catch (CancelledKeyException e) {
            if (logger.isDebugEnabled()) {
                logger.debug(CancelledKeyException.class.getSimpleName() + " raised by a Selector {} - JDK bug?",
                        selector, e);
            }
            // Harmless exception - log anyway
        }
    }

    /**
     * 重建 Selector 对象
     */
    private Selector selectRebuildSelector(int selectCnt) throws IOException {
        logger.warn(
                "Selector.select() returned prematurely {} times in a row; rebuilding Selector {}.",
                selectCnt, selector);
        // 重建 Selector 对象
        rebuildSelector();
        // 修改下 Selector 对象
        Selector selector = this.selector;
        // 立即 selectNow 一次，非阻塞
        selector.selectNow();
        return selector;
    }

    private void selectAgain() {
        needsToSelectAgain = false;
        try {
            selector.selectNow();
        } catch (Throwable t) {
            logger.warn("Failed to update SelectionKeys.", t);
        }
    }
}
