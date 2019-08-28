/*
 * Copyright 2019 The Netty Project
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
package io.netty.channel.epoll;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufAllocator;
import io.netty.channel.AdaptiveRecvByteBufAllocator;
import io.netty.channel.MaxMessagesRecvByteBufAllocator;
import io.netty.util.UncheckedBooleanSupplier;
import io.netty.util.internal.ObjectUtil;

import java.util.List;

/**
 * {@link AdaptiveRecvByteBufAllocator} which allows to use scattering reads {@code recvmmsg}.
 * The number of messages it tries to read depends on the underlying allocated {@link ByteBuf} that
 * depends on the last number of bytes read.
 *
 * See {@link AdaptiveRecvByteBufAllocator} for more details on how the size of the buffer is calculated.
 *
 */
public final class ScatteringAdaptiveRecvByteBufAllocator extends AdaptiveRecvByteBufAllocator {

    private final int bufferSize;

    public ScatteringAdaptiveRecvByteBufAllocator(int minimum, int initial, int maximum, int bufferSize) {
        super(verifySettings(minimum, bufferSize), initial, maximum);
        this.bufferSize = bufferSize;
    }

    private static int verifySettings(int minimum, int bufferSize) {
        ObjectUtil.checkPositive(bufferSize, "bufferSize");
        if (minimum < bufferSize) {
            throw new IllegalArgumentException("minimum(" + minimum + ") must be >= bufferSize(" + bufferSize + ')');
        }
        return minimum;
    }

    public ScatteringAdaptiveRecvByteBufAllocator() {
        this(1024, 1024, 64 * 1024, 1024);
    }

    @Override
    public ScatteringAdaptiveRecvByteBufAllocator respectMaybeMoreData(boolean respectMaybeMoreData) {
        super.respectMaybeMoreData(respectMaybeMoreData);
        return this;
    }

    @Override
    public ScatteringAdaptiveRecvByteBufAllocator maxMessagesPerRead(int maxMessagesPerRead) {
        super.maxMessagesPerRead(maxMessagesPerRead);
        return this;
    }

    @Override
    public Handle newHandle() {
        return new ScatteringHandle(super.newHandle());
    }

    private final class ScatteringHandle extends DelegatingHandle implements io.netty.channel.epoll.ScatteringHandle {
        ScatteringHandle(Handle delegate) {
            super(delegate);
        }

        @Override
        public void allocateScattering(ByteBufAllocator alloc, List<ByteBuf> buffers) {
            ByteBuf buffer = super.allocate(alloc);

            int numBuffers = buffer.writableBytes() / bufferSize;

            if (numBuffers > 0) {
                try {
                    for (int i = 0, offset = 0; i < numBuffers; i++, offset += bufferSize) {
                        ByteBuf slice = buffer.retainedSlice(offset, bufferSize).clear();
                        buffers.add(slice);
                    }
                } finally {
                    // We need to call release now as we retained the slices.
                    buffer.release();
                }
            } else {
                buffers.add(buffer);
            }
        }

        @Override
        public boolean continueReading(UncheckedBooleanSupplier maybeMoreDataSupplier) {
            return ((ExtendedHandle) delegate()).continueReading(maybeMoreDataSupplier);
        }
    }
}
