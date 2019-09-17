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
package io.netty.util;

/**
 * 当new ReferenceCounted实例化时，它以引用计数开始1。 retain()增加引用计数，
 * 并release()减少引用计数。如果引用计数减少到0，则将显式释放对象
 */
public interface ReferenceCounted {
    /**
     * 返回此对象的引用计数。如果0，则表示此对象已被释放。
     */
    int refCnt();

    /**
     * 增加引用计数1。
     */
    ReferenceCounted retain();

    /**
     * 按指定的方式增加引用计数increment。
     */
    ReferenceCounted retain(int increment);

    /**
     * 记录此对象的当前访问位置以进行调试。
     * 如果确定此对象泄漏，则此操作记录的信息将通过{@link resourcelakdetector}提供给您。此方法是{@link touch（object）touch（null）}的快捷方式。
     */
    ReferenceCounted touch();

    /**
     * 记录此对象的当前访问位置以及用于调试的附加任意信息。如果确定此对象泄漏，则此操作记录的信息将通过{@link resourcelakdetector}提供给您。
     */
    ReferenceCounted touch(Object hint);

    /**
     * 如果引用计数达到{@code 0}，则将引用计数减少{@code 1}并释放此对象。
     */
    boolean release();

    /**
     * 如果引用计数达到{@code 0}，则按指定的{@code减量}减少引用计数并释放此对象。
     */
    boolean release(int decrement);
}
