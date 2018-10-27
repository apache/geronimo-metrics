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

import static org.apache.geronimo.microprofile.metrics.common.expdecay.ExponentialMovingAverage.forMinutes;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;
import java.util.stream.Stream;

import javax.json.bind.annotation.JsonbProperty;

import org.apache.geronimo.microprofile.metrics.common.expdecay.ExponentialMovingAverage;
import org.eclipse.microprofile.metrics.Meter;

public class MeterImpl implements Meter {
    private static final long REFRESH_INTERVAL = TimeUnit.SECONDS.toNanos(5);

    private final AtomicLong lastRefresh = new AtomicLong(System.nanoTime());
    private final LongAdder count = new LongAdder();
    private final ExponentialMovingAverage rate15 = forMinutes(15);
    private final ExponentialMovingAverage rate5 = forMinutes(5);
    private final ExponentialMovingAverage rate1 = forMinutes(1);
    private final long initNs = System.nanoTime();
    private final String unit;

    public MeterImpl(final String unit) {
        this.unit = unit;
    }

    public String getUnit() {
        return unit;
    }

    @Override
    public void mark() {
        mark(1);
    }

    @Override
    public void mark(final long n) {
        updateIfNeeded();
        count.add(n);
        rate1.add(n);
        rate5.add(n);
        rate15.add(n);
    }

    @Override
    public long getCount() {
        return count.sum();
    }

    @Override
    @JsonbProperty("fifteenMinRate")
    public double getFifteenMinuteRate() {
        updateIfNeeded();
        return rate15.rate();
    }

    @Override
    @JsonbProperty("fiveMinRate")
    public double getFiveMinuteRate() {
        updateIfNeeded();
        return rate5.rate();
    }

    @Override
    @JsonbProperty("oneMinRate")
    public double getOneMinuteRate() {
        updateIfNeeded();
        return rate1.rate();
    }

    @Override
    public double getMeanRate() {
        final long count = getCount();
        if (count == 0) {
            return 0;
        }
        final long duration = System.nanoTime() - initNs;
        if (duration == 0) {
            return 0;
        }
        final long seconds = TimeUnit.NANOSECONDS.toSeconds(duration);
        if (seconds == 0) {
            return 0;
        }
        return count * 1. / seconds;
    }

    private void updateIfNeeded() {
        final long now = System.nanoTime();
        final long previousRefresh = lastRefresh.get();
        if (now - previousRefresh >= REFRESH_INTERVAL && lastRefresh.compareAndSet(previousRefresh, now)) {
            lastRefresh.set(now);
            Stream.of(rate1, rate5, rate15).forEach(ExponentialMovingAverage::refresh);
        }
    }
}
