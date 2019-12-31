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

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.concurrent.atomic.AtomicLong;

import org.eclipse.microprofile.metrics.ConcurrentGauge;

// this minute thing is stupid but what the TCK expect...todo: move to a scheduledexecutor to avoid that stupid cost
public class ConcurrentGaugeImpl implements ConcurrentGauge {
    private static final Clock CLOCK = Clock.tickMinutes(ZoneId.of("UTC"));

    private final AtomicLong delegate = new AtomicLong();
    private final AtomicLong min = new AtomicLong();
    private final AtomicLong max = new AtomicLong();

    private volatile Instant currentMinute = CLOCK.instant();

    private volatile long lastMax = -1;
    private volatile long lastMin = -1;
    private final String unit;

    public ConcurrentGaugeImpl(final String unit) {
        this.unit = unit;
    }

    public String getUnit() {
        return unit;
    }

    @Override
    public void inc() {
        maybeRotate();
        synchronized (this) {
            final long value = delegate.incrementAndGet();
            final long max = this.max.get();
            if (max < value) {
                this.max.set(value);
            }
        }
    }

    @Override
    public void dec() {
        maybeRotate();
        synchronized (this) {
            final long value = delegate.decrementAndGet();
            final long min = this.min.get();
            if (min < value) {
                this.min.set(value);
            }
        }
    }

    @Override
    public long getCount() {
        maybeRotate();
        return delegate.get();
    }

    @Override
    public long getMax() {
        maybeRotate();
        return lastMax;
    }

    @Override
    public long getMin() {
        maybeRotate();
        return lastMin;
    }

    private void maybeRotate() {
        final Instant now = CLOCK.instant();
        if (now.isAfter(currentMinute)) {
            synchronized (this) {
                if (now.isAfter(currentMinute)) {
                    final long count = delegate.get();
                    lastMin = min.getAndSet(count);
                    lastMax = max.getAndSet(count);
                    min.set(count);
                    max.set(count);
                    currentMinute = now;
                }
            }
        }
    }
}
