/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.geronimo.microprofile.metrics.common;

import java.util.concurrent.atomic.LongAdder;

import org.eclipse.microprofile.metrics.Counter;

public class CounterImpl implements Counter {
    private final LongAdder delegate = new LongAdder();
    private final String unit;

    public CounterImpl(final String unit) {
        this.unit = unit;
    }

    @Override
    public void inc() {
        delegate.increment();
    }

    @Override
    public void inc(final long n) {
        delegate.add(n);
    }

    @Override
    public void dec() {
        delegate.decrement();
    }

    @Override
    public void dec(final long n) {
        delegate.add(-n);
    }

    @Override
    public long getCount() {
        return delegate.sum();
    }
}
