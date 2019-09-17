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
package io.netty.buffer;

import io.netty.util.AsciiString;
import io.netty.util.ByteProcessor;
import io.netty.util.CharsetUtil;
import io.netty.util.IllegalReferenceCountException;
import io.netty.util.ResourceLeakDetector;
import io.netty.util.ResourceLeakDetectorFactory;
import io.netty.util.internal.PlatformDependent;
import io.netty.util.internal.StringUtil;
import io.netty.util.internal.SystemPropertyUtil;
import io.netty.util.internal.logging.InternalLogger;
import io.netty.util.internal.logging.InternalLoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
import java.nio.channels.GatheringByteChannel;
import java.nio.channels.ScatteringByteChannel;
import java.nio.charset.Charset;

import static io.netty.util.internal.MathUtil.isOutOfBounds;
import static io.netty.util.internal.ObjectUtil.checkPositiveOrZero;

/**
 * A skeletal implementation of a buffer.
 */
public abstract class AbstractByteBuf extends ByteBuf {
    private static final InternalLogger logger = InternalLoggerFactory.getInstance(AbstractByteBuf.class);
    private static final String LEGACY_PROP_CHECK_ACCESSIBLE = "io.netty.buffer.bytebuf.checkAccessible";
    private static final String PROP_CHECK_ACCESSIBLE = "io.netty.buffer.checkAccessible";
    static final boolean checkAccessible; // accessed from CompositeByteBuf
    private static final String PROP_CHECK_BOUNDS = "io.netty.buffer.checkBounds";
    private static final boolean checkBounds;

    static {
        if (SystemPropertyUtil.contains(PROP_CHECK_ACCESSIBLE)) {
            checkAccessible = SystemPropertyUtil.getBoolean(PROP_CHECK_ACCESSIBLE, true);
        } else {
            checkAccessible = SystemPropertyUtil.getBoolean(LEGACY_PROP_CHECK_ACCESSIBLE, true);
        }
        checkBounds = SystemPropertyUtil.getBoolean(PROP_CHECK_BOUNDS, true);
        if (logger.isDebugEnabled()) {
            logger.debug("-D{}: {}", PROP_CHECK_ACCESSIBLE, checkAccessible);
            logger.debug("-D{}: {}", PROP_CHECK_BOUNDS, checkBounds);
        }
    }

    static final ResourceLeakDetector<ByteBuf> leakDetector =
            ResourceLeakDetectorFactory.instance().newResourceLeakDetector(ByteBuf.class);

    /**
     * 读取位置
     */
    int readerIndex;

    /**
     * 写入位置
     */
    int writerIndex;

    /**
     * {@link #readerIndex} 读取位置的标记
     */
    private int markedReaderIndex;

    /**
     * {@link #writerIndex} 写入位置的标记
     */
    private int markedWriterIndex;

    /**
     * 最大容量
     */
    private int maxCapacity;

    /**
     * capacity 属性，在 AbstractByteBuf 未定义，而是由子类来实现。
     * ByteBuf 根据内存类型分成 Heap 和 Direct ，它们获取 capacity 的值的方式不同。
     */
    protected AbstractByteBuf(int maxCapacity) {
        checkPositiveOrZero(maxCapacity, "maxCapacity");
        this.maxCapacity = maxCapacity;
    }

    /**
     * 此缓冲区是否只读的
     */
    @Override
    public boolean isReadOnly() {
        return false;
    }

    /**
     * 返回此缓冲区的只读版本
     */
    @SuppressWarnings("deprecation")
    @Override
    public ByteBuf asReadOnly() {
        if (isReadOnly()) {
            return this;
        }
        return Unpooled.unmodifiableBuffer(this);
    }

    /**
     * 返回缓冲区允许的最大容量
     */
    @Override
    public int maxCapacity() {
        return maxCapacity;
    }

    /**
     * 设置缓冲区允许的最大容量
     */
    protected final void maxCapacity(int maxCapacity) {
        this.maxCapacity = maxCapacity;
    }

    /**
     * 返回读取位置
     */
    @Override
    public int readerIndex() {
        return readerIndex;
    }

    /**
     * 设置读取位置
     */
    @Override
    public ByteBuf readerIndex(int readerIndex) {
        //检查坐标边界
        if (checkBounds) {
            checkIndexBounds(readerIndex, writerIndex, capacity());
        }
        this.readerIndex = readerIndex;
        return this;
    }

    /**
     * 返回写入位置
     */
    @Override
    public int writerIndex() {
        return writerIndex;
    }

    /**
     * 设置写入位置
     */
    @Override
    public ByteBuf writerIndex(int writerIndex) {
        //检查坐标边界
        if (checkBounds) {
            checkIndexBounds(readerIndex, writerIndex, capacity());
        }
        this.writerIndex = writerIndex;
        return this;
    }

    /**
     * 设置读取位置，写入位置
     */
    @Override
    public ByteBuf setIndex(int readerIndex, int writerIndex) {
        //检查坐标边界
        if (checkBounds) {
            checkIndexBounds(readerIndex, writerIndex, capacity());
        }
        setIndex0(readerIndex, writerIndex);
        return this;
    }

    /**
     * 设置读取位置，写入位置
     */
    final void setIndex0(int readerIndex, int writerIndex) {
        this.readerIndex = readerIndex;
        this.writerIndex = writerIndex;
    }

    /**
     * 检查坐标边界
     */
    private static void checkIndexBounds(final int readerIndex, final int writerIndex, final int capacity) {
        if (readerIndex < 0 || readerIndex > writerIndex || writerIndex > capacity) {
            throw new IndexOutOfBoundsException(String.format(
                    "readerIndex: %d, writerIndex: %d (expected: 0 <= readerIndex <= writerIndex <= capacity(%d))",
                    readerIndex, writerIndex, capacity));
        }
    }


    /**
     * 清空读取位置，写入位置
     */
    @Override
    public ByteBuf clear() {
        readerIndex = writerIndex = 0;
        return this;
    }

    //====================是否可读====================
    @Override
    public boolean isReadable() {
        return writerIndex > readerIndex;
    }

    @Override
    public boolean isReadable(int numBytes) {
        return writerIndex - readerIndex >= numBytes;
    }

    @Override
    public int readableBytes() {
        return writerIndex - readerIndex;
    }

    //====================是否可写====================
    @Override
    public boolean isWritable() {
        return capacity() > writerIndex;
    }

    @Override
    public boolean isWritable(int numBytes) {
        return capacity() - writerIndex >= numBytes;
    }

    @Override
    public int writableBytes() {
        return capacity() - writerIndex;
    }

    @Override
    public int maxWritableBytes() {
        return maxCapacity() - writerIndex;
    }

    //====================标记读====================
    /**
     * 标记读位置
     */
    @Override
    public ByteBuf markReaderIndex() {
        markedReaderIndex = readerIndex;
        return this;
    }

    /**
     * 重置读位置到已标记处
     */
    @Override
    public ByteBuf resetReaderIndex() {
        readerIndex(markedReaderIndex);
        return this;
    }

    //====================标记写====================

    /**
     * 标记写位置
     */
    @Override
    public ByteBuf markWriterIndex() {
        markedWriterIndex = writerIndex;
        return this;
    }

    /**
     * 重置写位置到已标记处
     */
    @Override
    public ByteBuf resetWriterIndex() {
        writerIndex(markedWriterIndex);
        return this;
    }

    /**
     * 释放【所有的】废弃段的空间内存
     */
    @Override
    public ByteBuf discardReadBytes() {
        /** 校验可访问 */
        ensureAccessible();
        /** 无废弃段，直接返回 */
        if (readerIndex == 0) {
            return this;
        }

        /** 未读取完 */
        if (readerIndex != writerIndex) {
            /** 将可读段复制到 ByteBuf 头 */
            setBytes(0, this, readerIndex, writerIndex - readerIndex);
            /** 写索引减小 */
            writerIndex -= readerIndex;
            /** 对读写标记为做调整 */
            adjustMarkers(readerIndex);
            /** 读索引重置为 0 */
            readerIndex = 0;
        }
        /**  全部读取完 */
        else {
            /** 对读写标记为做调整 */
            adjustMarkers(readerIndex);
            /** 读写位置都重置为 0 */
            writerIndex = readerIndex = 0;
        }
        return this;
    }

    /**
     * 读取超过容量的一半，释放【所有的】废弃段的空间内存
     */
    @Override
    public ByteBuf discardSomeReadBytes() {
        /** 校验可访问 */
        ensureAccessible();
        /** 无废弃段，直接返回 */
        if (readerIndex == 0) {
            return this;
        }

        /**  全部读取完 */
        if (readerIndex == writerIndex) {
            /** 对读写标记为做调整 */
            adjustMarkers(readerIndex);
            /** 读写位置都重置为 0 */
            writerIndex = readerIndex = 0;
            return this;
        }

        /**  读取超过容量的一半，进行释放 */
        if (readerIndex >= capacity() >>> 1) {
            /** 将可读段复制到 ByteBuf 头 */
            setBytes(0, this, readerIndex, writerIndex - readerIndex);
            /** 写索引减小 */
            writerIndex -= readerIndex;
            /** 对读写标记为做调整 */
            adjustMarkers(readerIndex);
            /** 读索引重置为 0 */
            readerIndex = 0;
        }
        return this;
    }

    /**
     * 对读写标记为做调整
     */
    protected final void adjustMarkers(int decrement) {
        /** 获取原始读标记位置 */
        int markedReaderIndex = this.markedReaderIndex;
         /** 读标记位小于减少值(decrement) */
        if (markedReaderIndex <= decrement) {
            /** 重置读标记位为 0 */
            this.markedReaderIndex = 0;
            /** 获取原始写标记位置 */
            int markedWriterIndex = this.markedWriterIndex;
            /** 写标记位小于减少值(decrement) */
            if (markedWriterIndex <= decrement) {
                /** 重置写标记位为 0 */
                this.markedWriterIndex = 0;
            } else {
                /** 减小写标记位 */
                this.markedWriterIndex = markedWriterIndex - decrement;
            }
        } else {
            /** 减小读标记位 */
            this.markedReaderIndex = markedReaderIndex - decrement;
            /** 减小写标记位 */
            markedWriterIndex -= decrement;
        }
    }

    protected final void trimIndicesToCapacity(int newCapacity) {
        if (writerIndex() > newCapacity) {
            setIndex0(Math.min(readerIndex(), newCapacity), newCapacity);
        }
    }

    /**
     * 保证有足够写入minWritableBytes大小可写空间。若不够，则进行扩容
     */
    @Override
    public ByteBuf ensureWritable(int minWritableBytes) {
        /** 检查给定的参数是正数还是零minWritableBytes必须是正数 **/
        checkPositiveOrZero(minWritableBytes, "minWritableBytes");
        ensureWritable0(minWritableBytes);
        return this;
    }

    /**
     * 保证有足够写入minWritableBytes大小可写空间内部实现
     */
    final void ensureWritable0(int minWritableBytes) {
        /** 检查是否可访问 **/
        ensureAccessible();
        /** 当前ByteBuf有足够写入minWritableBytes大小的空间，直接返回 **/
        if (minWritableBytes <= writableBytes()) {
            return;
        }
        final int writerIndex = writerIndex();
        /**最大上限maxCapacity都无法满足写入minWritableBytes 抛出 IndexOutOfBoundsException 异常 **/
        if (checkBounds) {
            if (minWritableBytes > maxCapacity - writerIndex) {
                throw new IndexOutOfBoundsException(String.format(
                        "writerIndex(%d) + minWritableBytes(%d) exceeds maxCapacity(%d): %s",
                        writerIndex, minWritableBytes, maxCapacity, this));
            }
        }

        /** 计算新的容量。默认情况下，2 倍扩容，并且不超过最大容量上限 **/
        int minNewCapacity = writerIndex + minWritableBytes;
        int newCapacity = alloc().calculateNewCapacity(minNewCapacity, maxCapacity);
        int fastCapacity = writerIndex + maxFastWritableBytes();
        if (newCapacity > fastCapacity && minNewCapacity <= fastCapacity) {
            newCapacity = fastCapacity;
        }

        /**  扩容到新的容量大小。 **/
        capacity(newCapacity);
    }

    @Override
    public int ensureWritable(int minWritableBytes, boolean force) {
        /** 检查是否可访问 **/
        ensureAccessible();
        /** 检查给定的参数是正数还是零minWritableBytes必须是正数 **/
        checkPositiveOrZero(minWritableBytes, "minWritableBytes");

        /** 当前ByteBuf有足够写入minWritableBytes大小的空间，直接返回 **/
        if (minWritableBytes <= writableBytes()) {
            return 0;
        }

        /** 获取缓冲区允许的最大容量 **/
        final int maxCapacity = maxCapacity();
        /** 返回写入位置 **/
        final int writerIndex = writerIndex();
        /** 最大上限maxCapacity都无法满足写入minWritableBytes **/
        if (minWritableBytes > maxCapacity - writerIndex) {
            /** 不强制设置，或者已经到达最大容量   **/
            if (!force || capacity() == maxCapacity) {
                return 1;
            }
            /** 设置为最大容量    **/
            capacity(maxCapacity);
            return 3;
        }

        /** 计算新的容量。默认情况下，2 倍扩容，并且不超过最大容量上限。 **/
        int minNewCapacity = writerIndex + minWritableBytes;
        int newCapacity = alloc().calculateNewCapacity(minNewCapacity, maxCapacity);
        int fastCapacity = writerIndex + maxFastWritableBytes();
        if (newCapacity > fastCapacity && minNewCapacity <= fastCapacity) {
            newCapacity = fastCapacity;
        }

        /** 设置新的容量大小**/
        capacity(newCapacity);
        return 2;
    }

    /**
     * 设置字节序
     */
    @Override
    public ByteBuf order(ByteOrder endianness) {
        if (endianness == order()) {
            return this;
        }
        /** 如果字节序未修改，直接返回该 ByteBuf 对象。 **/
        if (endianness == null) {
            throw new NullPointerException("endianness");
        }
        /** 如果字节序有修改，调用 #newSwappedByteBuf() 方法，TODO SwappedByteBuf **/
        return newSwappedByteBuf();
    }

    /**
     * 为此{@link ByteBuf}实例创建一个新的{@link SwappedByteBuf}。
     */
    protected SwappedByteBuf newSwappedByteBuf() {
        return new SwappedByteBuf(this);
    }

    /**
     * 获取Byte,读取位置不移动
     */
    @Override
    public byte getByte(int index) {
        /** 校验读取是否会超过容量 **/
        checkIndex(index);
        /** 读取 Int 数据。这是一个抽象方法，由子类实现 **/
        return _getByte(index);
    }

    /**
     * 将此缓冲区的数据从指定的{@code index}写入dst
     * 此方法不修改此缓冲区的{@code readerindex}或{@code writerindex}。
     */
    @Override
    public ByteBuf getBytes(int index, byte[] dst) {
        getBytes(index, dst, 0, dst.length);
        return this;
    }

    /**
     * 将此缓冲区的数据从指定的{@code index}写入dst
     * 此方法不修改此缓冲区的{@code readerindex}或{@code writerindex}。
     * 此方法修改dst缓冲区{@code writerindex
     */
    @Override
    public ByteBuf getBytes(int index, ByteBuf dst) {
        getBytes(index, dst, dst.writableBytes());
        return this;
    }

    /**
     * 将此缓冲区的数据从指定的{@code index}写入dst，设置写入字节大小length
     * 此方法不修改此缓冲区的{@code readerindex}或{@code writerindex}。
     * 此方法修改dst缓冲区{@code writerindex
     */
    @Override
    public ByteBuf getBytes(int index, ByteBuf dst, int length) {
        getBytes(index, dst, dst.writerIndex(), length);
        dst.writerIndex(dst.writerIndex() + length);
        return this;
    }

    /**
     * 获取Byte,读取位置移动
     */
    @Override
    public byte readByte() {
        /** 校验读取是否会超过可读段 **/
        checkReadableBytes0(1);
        /** 获取原始读取位置 **/
        int i = readerIndex;
        /** 读取 Byte 数据 设置给b **/
        byte b = _getByte(i);
        /** 读取位置+1 **/
        readerIndex = i + 1;
        /** 返回b **/
        return b;
    }

    protected abstract byte _getByte(int index);


    /**
     * 在该缓冲区中的指定{@code index}处设置指定字节
     * 此方法不修改此缓冲区的{@code readerindex}或{@code writerindex}。
     */
    @Override
    public ByteBuf setByte(int index, int value) {
        /** 校验写入是否会超过容量 **/
        checkIndex(index);
        /** 在指定设置 Byte 数据 **/
        _setByte(index, value);
        /** 返回当前对象 **/
        return this;
    }

    protected abstract void _setByte(int index, int value);

    /**
     * 从指定{@code index}开始将指定源数组的数据传输到此缓冲区。
     * 此方法不修改此缓冲区的{@code readerindex}或{@code writerindex}。
     */
    @Override
    public ByteBuf setBytes(int index, byte[] src) {
        setBytes(index, src, 0, src.length);
        return this;
    }

    /**
     * 从指定的@code index}开始将指定源缓冲区的数据传输到此缓冲区，直到源缓冲区变得不可读为止
     * 此方法不修改当前ByteBuf对应readerIndex或writerIndex源缓冲器
     * 此方法不修改源src readerIndex
     */
    @Override
    public ByteBuf setBytes(int index, ByteBuf src) {
        setBytes(index, src, src.readableBytes());
        return this;
    }

    /**
     * 从指定的@code index}开始将指定源缓冲区的数据传输到此缓冲区，传输数据长度为length
     * 此方法不修改readerIndex或writerIndex源缓冲器
     * 此方法修改源src readerIndex
     */
    @Override
    public ByteBuf setBytes(int index, ByteBuf src, int length) {
        /** 校验是否会超过容量 **/
        checkIndex(index, length);
        /** src不能为null **/
        if (src == null) {
            throw new NullPointerException("src");
        }
        /** 检查src可读取容量大于length **/
        if (checkBounds) {
            checkReadableBounds(src, length);
        }
        /** 从指定的@code index}开始将指定源缓冲区的数据传输到此缓冲区，传输数据长度为length **/
        setBytes(index, src, src.readerIndex(), length);
        /** 修改src readerIndex **/
        src.readerIndex(src.readerIndex() + length);
        return this;
    }

    /**
     * 在当前{@code writerindex}处设置指定字节，并在此缓冲区中将{@code writerindex}增加{@code 1}。
     * 此方法在写入空间不足时会尝试扩容
     */
    @Override
    public ByteBuf writeByte(int value) {
        /** 保证有足够写入minWritableBytes大小可写空间内部实现 **/
        ensureWritable0(1);
        /** 在writerIndex位置插入value，并移动writerIndex **/
        _setByte(writerIndex++, value);
        return this;
    }


    @Override
    public boolean getBoolean(int index) {
        return getByte(index) != 0;
    }

    @Override
    public short getUnsignedByte(int index) {
        return (short) (getByte(index) & 0xFF);
    }

    @Override
    public short getShort(int index) {
        checkIndex(index, 2);
        return _getShort(index);
    }

    protected abstract short _getShort(int index);

    @Override
    public short getShortLE(int index) {
        checkIndex(index, 2);
        return _getShortLE(index);
    }

    protected abstract short _getShortLE(int index);

    @Override
    public int getUnsignedShort(int index) {
        return getShort(index) & 0xFFFF;
    }

    @Override
    public int getUnsignedShortLE(int index) {
        return getShortLE(index) & 0xFFFF;
    }

    @Override
    public int getUnsignedMedium(int index) {
        checkIndex(index, 3);
        return _getUnsignedMedium(index);
    }

    protected abstract int _getUnsignedMedium(int index);

    @Override
    public int getUnsignedMediumLE(int index) {
        checkIndex(index, 3);
        return _getUnsignedMediumLE(index);
    }

    protected abstract int _getUnsignedMediumLE(int index);

    @Override
    public int getMedium(int index) {
        int value = getUnsignedMedium(index);
        if ((value & 0x800000) != 0) {
            value |= 0xff000000;
        }
        return value;
    }

    @Override
    public int getMediumLE(int index) {
        int value = getUnsignedMediumLE(index);
        if ((value & 0x800000) != 0) {
            value |= 0xff000000;
        }
        return value;
    }

    @Override
    public int getInt(int index) {
        checkIndex(index, 4);
        return _getInt(index);
    }

    protected abstract int _getInt(int index);

    @Override
    public int getIntLE(int index) {
        checkIndex(index, 4);
        return _getIntLE(index);
    }

    protected abstract int _getIntLE(int index);

    @Override
    public long getUnsignedInt(int index) {
        return getInt(index) & 0xFFFFFFFFL;
    }

    @Override
    public long getUnsignedIntLE(int index) {
        return getIntLE(index) & 0xFFFFFFFFL;
    }

    @Override
    public long getLong(int index) {
        checkIndex(index, 8);
        return _getLong(index);
    }

    protected abstract long _getLong(int index);

    @Override
    public long getLongLE(int index) {
        checkIndex(index, 8);
        return _getLongLE(index);
    }

    protected abstract long _getLongLE(int index);

    @Override
    public char getChar(int index) {
        return (char) getShort(index);
    }

    @Override
    public float getFloat(int index) {
        return Float.intBitsToFloat(getInt(index));
    }

    @Override
    public double getDouble(int index) {
        return Double.longBitsToDouble(getLong(index));
    }



    @Override
    public CharSequence getCharSequence(int index, int length, Charset charset) {
        if (CharsetUtil.US_ASCII.equals(charset) || CharsetUtil.ISO_8859_1.equals(charset)) {
            // ByteBufUtil.getBytes(...) will return a new copy which the AsciiString uses directly
            return new AsciiString(ByteBufUtil.getBytes(this, index, length, true), false);
        }
        return toString(index, length, charset);
    }

    @Override
    public CharSequence readCharSequence(int length, Charset charset) {
        CharSequence sequence = getCharSequence(readerIndex, length, charset);
        readerIndex += length;
        return sequence;
    }



    @Override
    public ByteBuf setBoolean(int index, boolean value) {
        setByte(index, value? 1 : 0);
        return this;
    }

    @Override
    public ByteBuf setShort(int index, int value) {
        checkIndex(index, 2);
        _setShort(index, value);
        return this;
    }

    protected abstract void _setShort(int index, int value);

    @Override
    public ByteBuf setShortLE(int index, int value) {
        checkIndex(index, 2);
        _setShortLE(index, value);
        return this;
    }

    protected abstract void _setShortLE(int index, int value);

    @Override
    public ByteBuf setChar(int index, int value) {
        setShort(index, value);
        return this;
    }

    @Override
    public ByteBuf setMedium(int index, int value) {
        checkIndex(index, 3);
        _setMedium(index, value);
        return this;
    }

    protected abstract void _setMedium(int index, int value);

    @Override
    public ByteBuf setMediumLE(int index, int value) {
        checkIndex(index, 3);
        _setMediumLE(index, value);
        return this;
    }

    protected abstract void _setMediumLE(int index, int value);

    @Override
    public ByteBuf setInt(int index, int value) {
        checkIndex(index, 4);
        _setInt(index, value);
        return this;
    }

    protected abstract void _setInt(int index, int value);

    @Override
    public ByteBuf setIntLE(int index, int value) {
        checkIndex(index, 4);
        _setIntLE(index, value);
        return this;
    }

    protected abstract void _setIntLE(int index, int value);

    @Override
    public ByteBuf setFloat(int index, float value) {
        setInt(index, Float.floatToRawIntBits(value));
        return this;
    }

    @Override
    public ByteBuf setLong(int index, long value) {
        checkIndex(index, 8);
        _setLong(index, value);
        return this;
    }

    protected abstract void _setLong(int index, long value);

    @Override
    public ByteBuf setLongLE(int index, long value) {
        checkIndex(index, 8);
        _setLongLE(index, value);
        return this;
    }

    protected abstract void _setLongLE(int index, long value);

    @Override
    public ByteBuf setDouble(int index, double value) {
        setLong(index, Double.doubleToRawLongBits(value));
        return this;
    }



    private static void checkReadableBounds(final ByteBuf src, final int length) {
        if (length > src.readableBytes()) {
            throw new IndexOutOfBoundsException(String.format(
                    "length(%d) exceeds src.readableBytes(%d) where src is: %s", length, src.readableBytes(), src));
        }
    }



    @Override
    public ByteBuf setZero(int index, int length) {
        if (length == 0) {
            return this;
        }

        checkIndex(index, length);

        int nLong = length >>> 3;
        int nBytes = length & 7;
        for (int i = nLong; i > 0; i --) {
            _setLong(index, 0);
            index += 8;
        }
        if (nBytes == 4) {
            _setInt(index, 0);
            // Not need to update the index as we not will use it after this.
        } else if (nBytes < 4) {
            for (int i = nBytes; i > 0; i --) {
                _setByte(index, (byte) 0);
                index ++;
            }
        } else {
            _setInt(index, 0);
            index += 4;
            for (int i = nBytes - 4; i > 0; i --) {
                _setByte(index, (byte) 0);
                index ++;
            }
        }
        return this;
    }

    @Override
    public int setCharSequence(int index, CharSequence sequence, Charset charset) {
        return setCharSequence0(index, sequence, charset, false);
    }

    private int setCharSequence0(int index, CharSequence sequence, Charset charset, boolean expand) {
        if (charset.equals(CharsetUtil.UTF_8)) {
            int length = ByteBufUtil.utf8MaxBytes(sequence);
            if (expand) {
                ensureWritable0(length);
                checkIndex0(index, length);
            } else {
                checkIndex(index, length);
            }
            return ByteBufUtil.writeUtf8(this, index, sequence, sequence.length());
        }
        if (charset.equals(CharsetUtil.US_ASCII) || charset.equals(CharsetUtil.ISO_8859_1)) {
            int length = sequence.length();
            if (expand) {
                ensureWritable0(length);
                checkIndex0(index, length);
            } else {
                checkIndex(index, length);
            }
            return ByteBufUtil.writeAscii(this, index, sequence, length);
        }
        byte[] bytes = sequence.toString().getBytes(charset);
        if (expand) {
            ensureWritable0(bytes.length);
            // setBytes(...) will take care of checking the indices.
        }
        setBytes(index, bytes);
        return bytes.length;
    }



    @Override
    public boolean readBoolean() {
        return readByte() != 0;
    }

    @Override
    public short readUnsignedByte() {
        return (short) (readByte() & 0xFF);
    }

    @Override
    public short readShort() {
        checkReadableBytes0(2);
        short v = _getShort(readerIndex);
        readerIndex += 2;
        return v;
    }

    @Override
    public short readShortLE() {
        checkReadableBytes0(2);
        short v = _getShortLE(readerIndex);
        readerIndex += 2;
        return v;
    }

    @Override
    public int readUnsignedShort() {
        return readShort() & 0xFFFF;
    }

    @Override
    public int readUnsignedShortLE() {
        return readShortLE() & 0xFFFF;
    }

    @Override
    public int readMedium() {
        int value = readUnsignedMedium();
        if ((value & 0x800000) != 0) {
            value |= 0xff000000;
        }
        return value;
    }

    @Override
    public int readMediumLE() {
        int value = readUnsignedMediumLE();
        if ((value & 0x800000) != 0) {
            value |= 0xff000000;
        }
        return value;
    }

    @Override
    public int readUnsignedMedium() {
        checkReadableBytes0(3);
        int v = _getUnsignedMedium(readerIndex);
        readerIndex += 3;
        return v;
    }

    @Override
    public int readUnsignedMediumLE() {
        checkReadableBytes0(3);
        int v = _getUnsignedMediumLE(readerIndex);
        readerIndex += 3;
        return v;
    }

    @Override
    public int readInt() {
        checkReadableBytes0(4);
        int v = _getInt(readerIndex);
        readerIndex += 4;
        return v;
    }

    @Override
    public int readIntLE() {
        checkReadableBytes0(4);
        int v = _getIntLE(readerIndex);
        readerIndex += 4;
        return v;
    }

    @Override
    public long readUnsignedInt() {
        return readInt() & 0xFFFFFFFFL;
    }

    @Override
    public long readUnsignedIntLE() {
        return readIntLE() & 0xFFFFFFFFL;
    }

    @Override
    public long readLong() {
        checkReadableBytes0(8);
        long v = _getLong(readerIndex);
        readerIndex += 8;
        return v;
    }

    @Override
    public long readLongLE() {
        checkReadableBytes0(8);
        long v = _getLongLE(readerIndex);
        readerIndex += 8;
        return v;
    }

    @Override
    public char readChar() {
        return (char) readShort();
    }

    @Override
    public float readFloat() {
        return Float.intBitsToFloat(readInt());
    }

    @Override
    public double readDouble() {
        return Double.longBitsToDouble(readLong());
    }

    @Override
    public ByteBuf readBytes(int length) {
        checkReadableBytes(length);
        if (length == 0) {
            return Unpooled.EMPTY_BUFFER;
        }

        ByteBuf buf = alloc().buffer(length, maxCapacity);
        buf.writeBytes(this, readerIndex, length);
        readerIndex += length;
        return buf;
    }

    @Override
    public ByteBuf readSlice(int length) {
        checkReadableBytes(length);
        ByteBuf slice = slice(readerIndex, length);
        readerIndex += length;
        return slice;
    }

    @Override
    public ByteBuf readRetainedSlice(int length) {
        checkReadableBytes(length);
        ByteBuf slice = retainedSlice(readerIndex, length);
        readerIndex += length;
        return slice;
    }

    @Override
    public ByteBuf readBytes(byte[] dst, int dstIndex, int length) {
        checkReadableBytes(length);
        getBytes(readerIndex, dst, dstIndex, length);
        readerIndex += length;
        return this;
    }

    @Override
    public ByteBuf readBytes(byte[] dst) {
        readBytes(dst, 0, dst.length);
        return this;
    }

    @Override
    public ByteBuf readBytes(ByteBuf dst) {
        readBytes(dst, dst.writableBytes());
        return this;
    }

    @Override
    public ByteBuf readBytes(ByteBuf dst, int length) {
        if (checkBounds) {
            if (length > dst.writableBytes()) {
                throw new IndexOutOfBoundsException(String.format(
                        "length(%d) exceeds dst.writableBytes(%d) where dst is: %s", length, dst.writableBytes(), dst));
            }
        }
        readBytes(dst, dst.writerIndex(), length);
        dst.writerIndex(dst.writerIndex() + length);
        return this;
    }

    @Override
    public ByteBuf readBytes(ByteBuf dst, int dstIndex, int length) {
        checkReadableBytes(length);
        getBytes(readerIndex, dst, dstIndex, length);
        readerIndex += length;
        return this;
    }

    @Override
    public ByteBuf readBytes(ByteBuffer dst) {
        int length = dst.remaining();
        checkReadableBytes(length);
        getBytes(readerIndex, dst);
        readerIndex += length;
        return this;
    }

    @Override
    public int readBytes(GatheringByteChannel out, int length)
            throws IOException {
        checkReadableBytes(length);
        int readBytes = getBytes(readerIndex, out, length);
        readerIndex += readBytes;
        return readBytes;
    }

    @Override
    public int readBytes(FileChannel out, long position, int length)
            throws IOException {
        checkReadableBytes(length);
        int readBytes = getBytes(readerIndex, out, position, length);
        readerIndex += readBytes;
        return readBytes;
    }

    @Override
    public ByteBuf readBytes(OutputStream out, int length) throws IOException {
        checkReadableBytes(length);
        getBytes(readerIndex, out, length);
        readerIndex += length;
        return this;
    }

    @Override
    public ByteBuf skipBytes(int length) {
        checkReadableBytes(length);
        readerIndex += length;
        return this;
    }

    @Override
    public ByteBuf writeBoolean(boolean value) {
        writeByte(value ? 1 : 0);
        return this;
    }



    @Override
    public ByteBuf writeShort(int value) {
        ensureWritable0(2);
        _setShort(writerIndex, value);
        writerIndex += 2;
        return this;
    }

    @Override
    public ByteBuf writeShortLE(int value) {
        ensureWritable0(2);
        _setShortLE(writerIndex, value);
        writerIndex += 2;
        return this;
    }

    @Override
    public ByteBuf writeMedium(int value) {
        ensureWritable0(3);
        _setMedium(writerIndex, value);
        writerIndex += 3;
        return this;
    }

    @Override
    public ByteBuf writeMediumLE(int value) {
        ensureWritable0(3);
        _setMediumLE(writerIndex, value);
        writerIndex += 3;
        return this;
    }

    @Override
    public ByteBuf writeInt(int value) {
        ensureWritable0(4);
        _setInt(writerIndex, value);
        writerIndex += 4;
        return this;
    }

    @Override
    public ByteBuf writeIntLE(int value) {
        ensureWritable0(4);
        _setIntLE(writerIndex, value);
        writerIndex += 4;
        return this;
    }

    @Override
    public ByteBuf writeLong(long value) {
        ensureWritable0(8);
        _setLong(writerIndex, value);
        writerIndex += 8;
        return this;
    }

    @Override
    public ByteBuf writeLongLE(long value) {
        ensureWritable0(8);
        _setLongLE(writerIndex, value);
        writerIndex += 8;
        return this;
    }

    @Override
    public ByteBuf writeChar(int value) {
        writeShort(value);
        return this;
    }

    @Override
    public ByteBuf writeFloat(float value) {
        writeInt(Float.floatToRawIntBits(value));
        return this;
    }

    @Override
    public ByteBuf writeDouble(double value) {
        writeLong(Double.doubleToRawLongBits(value));
        return this;
    }

    @Override
    public ByteBuf writeBytes(byte[] src, int srcIndex, int length) {
        ensureWritable(length);
        setBytes(writerIndex, src, srcIndex, length);
        writerIndex += length;
        return this;
    }

    @Override
    public ByteBuf writeBytes(byte[] src) {
        writeBytes(src, 0, src.length);
        return this;
    }

    @Override
    public ByteBuf writeBytes(ByteBuf src) {
        writeBytes(src, src.readableBytes());
        return this;
    }

    @Override
    public ByteBuf writeBytes(ByteBuf src, int length) {
        if (checkBounds) {
            checkReadableBounds(src, length);
        }
        writeBytes(src, src.readerIndex(), length);
        src.readerIndex(src.readerIndex() + length);
        return this;
    }

    @Override
    public ByteBuf writeBytes(ByteBuf src, int srcIndex, int length) {
        ensureWritable(length);
        setBytes(writerIndex, src, srcIndex, length);
        writerIndex += length;
        return this;
    }

    @Override
    public ByteBuf writeBytes(ByteBuffer src) {
        int length = src.remaining();
        ensureWritable0(length);
        setBytes(writerIndex, src);
        writerIndex += length;
        return this;
    }

    @Override
    public int writeBytes(InputStream in, int length)
            throws IOException {
        ensureWritable(length);
        int writtenBytes = setBytes(writerIndex, in, length);
        if (writtenBytes > 0) {
            writerIndex += writtenBytes;
        }
        return writtenBytes;
    }

    @Override
    public int writeBytes(ScatteringByteChannel in, int length) throws IOException {
        ensureWritable(length);
        int writtenBytes = setBytes(writerIndex, in, length);
        if (writtenBytes > 0) {
            writerIndex += writtenBytes;
        }
        return writtenBytes;
    }

    @Override
    public int writeBytes(FileChannel in, long position, int length) throws IOException {
        ensureWritable(length);
        int writtenBytes = setBytes(writerIndex, in, position, length);
        if (writtenBytes > 0) {
            writerIndex += writtenBytes;
        }
        return writtenBytes;
    }

    @Override
    public ByteBuf writeZero(int length) {
        if (length == 0) {
            return this;
        }

        ensureWritable(length);
        int wIndex = writerIndex;
        checkIndex0(wIndex, length);

        int nLong = length >>> 3;
        int nBytes = length & 7;
        for (int i = nLong; i > 0; i --) {
            _setLong(wIndex, 0);
            wIndex += 8;
        }
        if (nBytes == 4) {
            _setInt(wIndex, 0);
            wIndex += 4;
        } else if (nBytes < 4) {
            for (int i = nBytes; i > 0; i --) {
                _setByte(wIndex, (byte) 0);
                wIndex++;
            }
        } else {
            _setInt(wIndex, 0);
            wIndex += 4;
            for (int i = nBytes - 4; i > 0; i --) {
                _setByte(wIndex, (byte) 0);
                wIndex++;
            }
        }
        writerIndex = wIndex;
        return this;
    }

    @Override
    public int writeCharSequence(CharSequence sequence, Charset charset) {
        int written = setCharSequence0(writerIndex, sequence, charset, true);
        writerIndex += written;
        return written;
    }

    /**
     * 拷贝可读部分的字节数组
     */
    @Override
    public ByteBuf copy() {
        return copy(readerIndex, readableBytes());
    }

    /**
     * 返回共享此缓冲区的整个区域的缓冲区
     */
    @Override
    public ByteBuf duplicate() {
        ensureAccessible();
        return new UnpooledDuplicatedByteBuf(this);
    }

    /**
     * 返回共享此缓冲区的整个区域的缓冲区，引用计数+1
     */
    @Override
    public ByteBuf retainedDuplicate() {
        return duplicate().retain();
    }

    /**
     * 返回此缓冲区可读字节的片段。修改返回的缓冲区或此缓冲区的内容会影响彼此的内容
     */
    @Override
    public ByteBuf slice() {
        return slice(readerIndex, readableBytes());
    }

    /**
     * 返回此缓冲区可读字节的片段。修改返回的缓冲区或此缓冲区的内容会影响彼此的内容，引用计数+1
     */
    @Override
    public ByteBuf retainedSlice() {
        return slice().retain();
    }

    /**
     * 返回从指定的@code index}开始将指定缓冲区的长度为length数据的片段。修改返回的缓冲区或此缓冲区的内容会影响彼此的内容
     */
    @Override
    public ByteBuf slice(int index, int length) {
        ensureAccessible();
        return new UnpooledSlicedByteBuf(this, index, length);
    }

    /**
     * 返回从指定的@code index}开始将指定缓冲区的长度为length数据的片段。修改返回的缓冲区或此缓冲区的内容会影响彼此的内容，引用计数+1
     */
    @Override
    public ByteBuf retainedSlice(int index, int length) {
        return slice(index, length).retain();
    }

    @Override
    public ByteBuffer nioBuffer() {
        return nioBuffer(readerIndex, readableBytes());
    }

    @Override
    public ByteBuffer[] nioBuffers() {
        return nioBuffers(readerIndex, readableBytes());
    }

    @Override
    public String toString(Charset charset) {
        return toString(readerIndex, readableBytes(), charset);
    }

    @Override
    public String toString(int index, int length, Charset charset) {
        return ByteBufUtil.decodeString(this, index, length, charset);
    }

    @Override
    public int indexOf(int fromIndex, int toIndex, byte value) {
        if (fromIndex <= toIndex) {
            return firstIndexOf(fromIndex, toIndex, value);
        } else {
            return lastIndexOf(fromIndex, toIndex, value);
        }
    }

    private int firstIndexOf(int fromIndex, int toIndex, byte value) {
        fromIndex = Math.max(fromIndex, 0);
        if (fromIndex >= toIndex || capacity() == 0) {
            return -1;
        }
        checkIndex(fromIndex, toIndex - fromIndex);

        for (int i = fromIndex; i < toIndex; i ++) {
            if (_getByte(i) == value) {
                return i;
            }
        }

        return -1;
    }

    private int lastIndexOf(int fromIndex, int toIndex, byte value) {
        fromIndex = Math.min(fromIndex, capacity());
        if (fromIndex < 0 || capacity() == 0) {
            return -1;
        }

        checkIndex(toIndex, fromIndex - toIndex);

        for (int i = fromIndex - 1; i >= toIndex; i --) {
            if (_getByte(i) == value) {
                return i;
            }
        }

        return -1;
    }

    @Override
    public int bytesBefore(byte value) {
        return bytesBefore(readerIndex(), readableBytes(), value);
    }

    @Override
    public int bytesBefore(int length, byte value) {
        checkReadableBytes(length);
        return bytesBefore(readerIndex(), length, value);
    }

    @Override
    public int bytesBefore(int index, int length, byte value) {
        int endIndex = indexOf(index, index + length, value);
        if (endIndex < 0) {
            return -1;
        }
        return endIndex - index;
    }

    @Override
    public int forEachByte(ByteProcessor processor) {
        ensureAccessible();
        try {
            return forEachByteAsc0(readerIndex, writerIndex, processor);
        } catch (Exception e) {
            PlatformDependent.throwException(e);
            return -1;
        }
    }

    @Override
    public int forEachByte(int index, int length, ByteProcessor processor) {
        checkIndex(index, length);
        try {
            return forEachByteAsc0(index, index + length, processor);
        } catch (Exception e) {
            PlatformDependent.throwException(e);
            return -1;
        }
    }

    int forEachByteAsc0(int start, int end, ByteProcessor processor) throws Exception {
        for (; start < end; ++start) {
            if (!processor.process(_getByte(start))) {
                return start;
            }
        }

        return -1;
    }

    @Override
    public int forEachByteDesc(ByteProcessor processor) {
        ensureAccessible();
        try {
            return forEachByteDesc0(writerIndex - 1, readerIndex, processor);
        } catch (Exception e) {
            PlatformDependent.throwException(e);
            return -1;
        }
    }

    @Override
    public int forEachByteDesc(int index, int length, ByteProcessor processor) {
        checkIndex(index, length);
        try {
            return forEachByteDesc0(index + length - 1, index, processor);
        } catch (Exception e) {
            PlatformDependent.throwException(e);
            return -1;
        }
    }

    int forEachByteDesc0(int rStart, final int rEnd, ByteProcessor processor) throws Exception {
        for (; rStart >= rEnd; --rStart) {
            if (!processor.process(_getByte(rStart))) {
                return rStart;
            }
        }
        return -1;
    }

    @Override
    public int hashCode() {
        return ByteBufUtil.hashCode(this);
    }

    @Override
    public boolean equals(Object o) {
        return this == o || (o instanceof ByteBuf && ByteBufUtil.equals(this, (ByteBuf) o));
    }

    @Override
    public int compareTo(ByteBuf that) {
        return ByteBufUtil.compare(this, that);
    }

    @Override
    public String toString() {
        if (refCnt() == 0) {
            return StringUtil.simpleClassName(this) + "(freed)";
        }

        StringBuilder buf = new StringBuilder()
            .append(StringUtil.simpleClassName(this))
            .append("(ridx: ").append(readerIndex)
            .append(", widx: ").append(writerIndex)
            .append(", cap: ").append(capacity());
        if (maxCapacity != Integer.MAX_VALUE) {
            buf.append('/').append(maxCapacity);
        }

        ByteBuf unwrapped = unwrap();
        if (unwrapped != null) {
            buf.append(", unwrapped: ").append(unwrapped);
        }
        buf.append(')');
        return buf.toString();
    }

    protected final void checkIndex(int index) {
        checkIndex(index, 1);
    }

    protected final void checkIndex(int index, int fieldLength) {
        /** 校验是否可访问 **/
        ensureAccessible();
        /** 校验是否会超过容量 **/
        checkIndex0(index, fieldLength);
    }

    /**
     * 校验是否会超过容量
     */
    final void checkIndex0(int index, int fieldLength) {
        if (checkBounds) {
            checkRangeBounds("index", index, fieldLength, capacity());
        }
    }

    /**
     * 校验是否会超过容量
     */
    private static void checkRangeBounds(final String indexName, final int index,
            final int fieldLength, final int capacity) {
        if (isOutOfBounds(index, fieldLength, capacity)) {
            throw new IndexOutOfBoundsException(String.format(
                    "%s: %d, length: %d (expected: range(0, %d))", indexName, index, fieldLength, capacity));
        }
    }



    protected final void checkSrcIndex(int index, int length, int srcIndex, int srcCapacity) {
        checkIndex(index, length);
        if (checkBounds) {
            checkRangeBounds("srcIndex", srcIndex, length, srcCapacity);
        }
    }

    protected final void checkDstIndex(int index, int length, int dstIndex, int dstCapacity) {
        checkIndex(index, length);
        if (checkBounds) {
            checkRangeBounds("dstIndex", dstIndex, length, dstCapacity);
        }
    }

    protected final void checkDstIndex(int length, int dstIndex, int dstCapacity) {
        checkReadableBytes(length);
        if (checkBounds) {
            checkRangeBounds("dstIndex", dstIndex, length, dstCapacity);
        }
    }

    /**
     * Throws an {@link IndexOutOfBoundsException} if the current
     * {@linkplain #readableBytes() readable bytes} of this buffer is less
     * than the specified value.
     */
    protected final void checkReadableBytes(int minimumReadableBytes) {
        checkPositiveOrZero(minimumReadableBytes, "minimumReadableBytes");
        checkReadableBytes0(minimumReadableBytes);
    }

    protected final void checkNewCapacity(int newCapacity) {
        ensureAccessible();
        if (checkBounds) {
            if (newCapacity < 0 || newCapacity > maxCapacity()) {
                throw new IllegalArgumentException("newCapacity: " + newCapacity +
                        " (expected: 0-" + maxCapacity() + ')');
            }
        }
    }

    private void checkReadableBytes0(int minimumReadableBytes) {
        ensureAccessible();
        if (checkBounds) {
            if (readerIndex > writerIndex - minimumReadableBytes) {
                throw new IndexOutOfBoundsException(String.format(
                        "readerIndex(%d) + length(%d) exceeds writerIndex(%d): %s",
                        readerIndex, minimumReadableBytes, writerIndex, this));
            }
        }
    }

    /**
     * Should be called by every method that tries to access the buffers content to check
     * if the buffer was released before.
     */
    protected final void ensureAccessible() {
        if (checkAccessible && !isAccessible()) {
            throw new IllegalReferenceCountException(0);
        }
    }



    final void discardMarks() {
        markedReaderIndex = markedWriterIndex = 0;
    }
}
