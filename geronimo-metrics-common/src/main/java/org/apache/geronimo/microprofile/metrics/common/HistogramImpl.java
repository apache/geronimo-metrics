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

import static java.util.Comparator.comparing;
import static java.util.stream.Collectors.toMap;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.concurrent.ConcurrentSkipListMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.stream.LongStream;
import java.util.stream.Stream;

import javax.json.bind.annotation.JsonbTransient;

import org.eclipse.microprofile.metrics.Histogram;
import org.eclipse.microprofile.metrics.Snapshot;

// impl adapted from apache sirona
public class HistogramImpl implements Histogram {
    // potential config
    private static final double ALPHA = Double.parseDouble(System.getProperty("geronimo.metrics.storage.alpha", "0.015"));
    private static final int BUCKET_SIZE = Integer.getInteger("geronimo.metrics.storage.size", 1024);
    private static final long REFRESH_INTERVAL = TimeUnit.HOURS.toNanos(1);

    private static final Value[] EMPTY_ARRAY = new Value[0];

    private final String unit;

    private final ReadWriteLock lock = new ReentrantReadWriteLock();
    private final AtomicLong count = new AtomicLong();
    private final ConcurrentSkipListMap<Double, Value> bucket = new ConcurrentSkipListMap<>();
    private final AtomicLong nextRefreshTime = new AtomicLong(System.nanoTime() + REFRESH_INTERVAL);
    private volatile long startTime = nowSec();

    public HistogramImpl(final String unit) {
        this.unit = unit;
    }

    @Override
    public void update(final int value) {
        update((long) value);
    }

    @Override
    public synchronized void update(final long value) {
        add(value);
    }

    @Override
    public long getCount() {
        return count.get();
    }

    @Override
    @JsonbTransient
    public Snapshot getSnapshot() {
        return snapshot();
    }

    public String getUnit() {
        return unit;
    }

    public double getP50() {
        return getSnapshot().getMedian();
    }

    public double getP75() {
        return getSnapshot().get75thPercentile();
    }

    public double getP95() {
        return getSnapshot().get95thPercentile();
    }

    public double getP98() {
        return getSnapshot().get98thPercentile();
    }

    public double getP99() {
        return getSnapshot().get99thPercentile();
    }

    public double getP999() {
        return getSnapshot().get999thPercentile();
    }

    public long getMax() {
        return getSnapshot().getMax();
    }

    public double getMean() {
        return getSnapshot().getMean();
    }

    public long getMin() {
        return getSnapshot().getMin();
    }

    public double getStddev() {
        return getSnapshot().getStdDev();
    }

    private long nowSec() {
        return TimeUnit.MILLISECONDS.toSeconds(System.currentTimeMillis());
    }

    public void add(final long value) {
        ensureUpToDate();

        final Lock lock = this.lock.readLock();
        lock.lock();
        try {
            final Value sample = new Value(value, Math.exp(ALPHA * (nowSec() - startTime)));
            final double priority = sample.weight / Math.random();

            final long size = count.incrementAndGet();
            if (size <= BUCKET_SIZE) {
                bucket.put(priority, sample);
            } else { // iterate through the bucket until we need removing low priority entries to get a new space
                double first = bucket.firstKey();
                if (first < priority && bucket.putIfAbsent(priority, sample) == null) {
                    while (bucket.remove(first) == null) {
                        first = bucket.firstKey();
                    }
                }
            }
        } finally {
            lock.unlock();
        }
    }

    private void ensureUpToDate() {
        final long next = nextRefreshTime.get();
        final long now = System.nanoTime();
        if (now < next) {
            return;
        }

        final Lock lock = this.lock.writeLock();
        lock.lock();
        try {
            if (nextRefreshTime.compareAndSet(next, now + REFRESH_INTERVAL)) {
                final long oldStartTime = startTime;
                startTime = nowSec();
                final double updateFactor = Math.exp(-ALPHA * (startTime - oldStartTime));
                if (updateFactor != 0.) {
                    bucket.putAll(new ArrayList<>(bucket.keySet()).stream()
                            .collect(toMap(k -> k * updateFactor, k -> {
                                final Value previous = bucket.remove(k);
                                return new Value(previous.value, previous.weight * updateFactor);
                            })));
                    count.set(bucket.size()); // N keys can lead to the same key so we must update it
                } else {
                    bucket.clear();
                    count.set(0);
                }
            }
        } finally {
            lock.unlock();
        }
    }

    public Snapshot snapshot() {
        ensureUpToDate();
        final Lock lock = this.lock.readLock();
        lock.lock();
        try {
            return new SnapshotImpl(bucket.values().toArray(EMPTY_ARRAY));
        } finally {
            lock.unlock();
        }
    }

    private static final class Value {
        private final long value;
        private final double weight;

        private Value(final long value, final double weight) {
            this.value = value;
            this.weight= weight;
        }
    }

    private static class SnapshotImpl extends Snapshot {
        private final Value[] values;
        private Value[] sorted;

        private SnapshotImpl(final Value[] values) {
            this.values = values;
            // no high computation here, we are under lock + all methods are not called in general
        }

        @Override
        public int size() {
            return values.length;
        }

        @Override
        public long[] getValues() {
            return values(sorted()).toArray();
        }

        @Override
        public long getMax() {
            if (values.length == 0) {
                return 0;
            }
            if (sorted != null) {
                return sorted[sorted.length - 1].value;
            }
            return values(values).max().orElse(0);
        }

        @Override
        public long getMin() {
            if (values.length == 0) {
                return 0;
            }
            if (sorted != null) {
                return sorted[0].value;
            }
            return values(values).min().orElse(0);
        }

        @Override
        public double getMean() {
            if (values.length == 0) {
                return 0;
            }
            return values(values).sum() * 1. / values.length;
        }

        @Override
        public double getStdDev() {
            if (values.length <= 1) {
                return 0;
            }
            final double mean = getMean();
            final double sumWeight = Stream.of(values).mapToDouble(i -> i.weight).sum();
            return Math.sqrt(Stream.of(values)
                    .mapToDouble(v -> Math.pow(v.value - mean, 2) * (v.weight / sumWeight))
                    .sum());
        }

        @Override
        public void dump(final OutputStream output) {
            values(sorted()).forEach(v -> {
                try {
                    output.write((v + "\n").getBytes(StandardCharsets.UTF_8));
                } catch (final IOException e) {
                    throw new IllegalStateException(e);
                }
            });
        }

        @Override
        public double getValue(final double quantile) {
            if (!(quantile >= 0 || quantile <= 1)) {
                throw new IllegalArgumentException("Quantile " + quantile + " is invalid");
            }
            if (values.length == 0) {
                return 0;
            }
            if (values.length == 1) {
                return values[0].value;
            }
            final int idx = (int) Math.floor((values.length - 1) * quantile);
            return sorted()[idx].value;
        }

        private Value[] sorted() {
            if (sorted == null) {
                synchronized (this) {
                    if (sorted == null) {
                        sorted = new Value[values.length];
                        System.arraycopy(values, 0, sorted, 0, values.length);
                        Arrays.sort(sorted, comparing(i -> i.value));
                    }
                }
            }
            return sorted;
        }

        private LongStream values(final Value[] values) {
            return Stream.of(values).mapToLong(i -> i.value);
        }
    }
}
