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
import io.netty.buffer.CompositeByteBuf;
import io.netty.channel.FixedRecvByteBufAllocator;
import io.netty.channel.MaxMessagesRecvByteBufAllocator;
import io.netty.channel.RecvByteBufAllocator;
import io.netty.util.UncheckedBooleanSupplier;
import io.netty.util.internal.ObjectUtil;

/**
 * {@link FixedRecvByteBufAllocator} which allows to use scattering reads {@code recvmmsg}.
 */
public final class ScatteringFixedRecvByteBufAllocator extends FixedRecvByteBufAllocator {
    private final int numBuffers;

    public ScatteringFixedRecvByteBufAllocator(int bufferSize, int numBuffers) {
        super(bufferSize);
        this.numBuffers = ObjectUtil.checkPositive(numBuffers, "numBuffers");
        super.maxMessagesPerRead(numBuffers);
    }

    @Override
    public Handle newHandle() {
        return new ScatteringHandle(super.newHandle());
    }

    @Override
    public MaxMessagesRecvByteBufAllocator maxMessagesPerRead(int maxMessagesPerRead) {
        return super.maxMessagesPerRead(Math.max(maxMessagesPerRead, numBuffers));
    }

    private final class ScatteringHandle extends DelegatingHandle implements ExtendedHandle {
        ScatteringHandle(Handle delegate) {
            super(delegate);
        }

        @Override
        public ByteBuf allocate(ByteBufAllocator alloc) {
            CompositeByteBuf compositeByteBuf = alloc.compositeDirectBuffer();
            for (int i = 0; i < numBuffers; i++) {
                compositeByteBuf.addComponent(true, super.allocate(alloc));
            }
            return compositeByteBuf;
        }

        @Override
        public boolean continueReading(UncheckedBooleanSupplier maybeMoreDataSupplier) {
            return ((ExtendedHandle) delegate()).continueReading(maybeMoreDataSupplier);
        }
    }
}
