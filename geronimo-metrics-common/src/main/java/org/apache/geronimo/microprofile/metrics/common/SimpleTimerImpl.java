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

import org.eclipse.microprofile.metrics.SimpleTimer;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.concurrent.Callable;

public class SimpleTimerImpl implements SimpleTimer {
    private static final Clock MINUTE_CLOCK = Clock.tickMinutes(ZoneId.of("UTC"));
    private static final Clock CLOCK = Clock.systemUTC();

    // same logic than ConcurrentGaugeImpl
    private volatile Instant currentMinute = CLOCK.instant();

    private volatile Duration current;
    private volatile Duration min;
    private volatile Duration max;
    private volatile Duration previousMin;
    private volatile Duration previousMax;
    private volatile long count;

    private final String unit;

    public SimpleTimerImpl(final String unit) {
        this.unit = unit;
    }

    public String getUnit() {
        return unit;
    }

    @Override
    public void update(final Duration duration) {
        if (duration.isNegative()) {
            return;
        }
        maybeRotate();
        synchronized (this) {
            count++;
            current = duration;
            if (max == null || duration.toMillis() > max.toMillis()) {
                max = duration;
            }
            if (min == null || duration.toMillis() < min.toMillis()) {
                min = duration;
            }
        }
    }

    @Override
    public <T> T time(final Callable<T> callable) throws Exception {
        final Instant startTime = CLOCK.instant();
        try {
            return callable.call();
        } finally {
            update(Duration.between(startTime, CLOCK.instant()));
        }
    }

    @Override
    public void time(final Runnable runnable) {
        try {
            time(() -> {
                runnable.run();
                return null;
            });
        } catch (final RuntimeException re) {
            throw re;
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    @Override
    public Context time() {
        return new ContextImpl();
    }

    @Override
    public Duration getElapsedTime() {
        return current;
    }

    @Override
    public long getCount() {
        return count;
    }

    @Override
    public Duration getMaxTimeDuration() {
        maybeRotate();
        return previousMax;
    }

    @Override
    public Duration getMinTimeDuration() {
        maybeRotate();
        return previousMin;
    }

    private void maybeRotate() {
        final Instant now = MINUTE_CLOCK.instant();
        if (now.isAfter(currentMinute)) {
            synchronized (this) {
                if (now.isAfter(currentMinute)) {
                    rotate(now);
                }
            }
        }
    }

    private void rotate(final Instant now) {
        previousMax = max;
        previousMin = min;
        max = min = null;
        currentMinute = now;
    }

    private class ContextImpl implements Context {
        private final Instant start = CLOCK.instant();

        @Override
        public long stop() {
            final Duration duration = Duration.between(start, CLOCK.instant());
            update(duration);
            return duration.toNanos();
        }

        @Override
        public void close() {
            stop();
        }
    }
}
