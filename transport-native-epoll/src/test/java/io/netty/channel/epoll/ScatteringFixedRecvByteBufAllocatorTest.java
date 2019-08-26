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

import io.netty.buffer.CompositeByteBuf;
import io.netty.buffer.UnpooledByteBufAllocator;
import io.netty.channel.RecvByteBufAllocator;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class ScatteringFixedRecvByteBufAllocatorTest {

    @Test
    public void testAllocate() {
        int size = 512;
        int numBuffers = 4;
        ScatteringFixedRecvByteBufAllocator allocator = new ScatteringFixedRecvByteBufAllocator(size, numBuffers);
        RecvByteBufAllocator.Handle handle = allocator.newHandle();
        CompositeByteBuf buf = (CompositeByteBuf) handle.allocate(UnpooledByteBufAllocator.DEFAULT);
        assertEquals(numBuffers, buf.numComponents());

        for (int i = 0; i < buf.numComponents(); i++) {
            assertEquals(size, buf.component(i).writableBytes());
        }
        assertTrue(buf.release());
    }
}
