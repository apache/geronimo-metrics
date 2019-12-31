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

import java.util.concurrent.atomic.AtomicLong;

import org.eclipse.microprofile.metrics.ConcurrentGauge;

public class ConcurrentGaugeImpl implements ConcurrentGauge {
    private final AtomicLong delegate = new AtomicLong();
    private final AtomicLong min = new AtomicLong();
    private final AtomicLong max = new AtomicLong();
    private final String unit;

    public ConcurrentGaugeImpl(final String unit) {
        this.unit = unit;
    }

    @Override
    public void inc() {
        final long value = delegate.incrementAndGet();
        final long max = this.max.get();
        if (max < value) {
            this.max.compareAndSet(max, value);
        }
    }

    @Override
    public void dec() {
        final long value = delegate.decrementAndGet();
        final long min = this.min.get();
        if (min < value) {
            this.min.compareAndSet(min, value);
        }
    }

    @Override
    public long getCount() {
        return delegate.get();
    }

    @Override
    public long getMax() {
        return max.get();
    }

    @Override
    public long getMin() {
        return min.get();
    }
}
