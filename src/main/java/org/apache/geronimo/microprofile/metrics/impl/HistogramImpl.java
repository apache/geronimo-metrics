package org.apache.geronimo.microprofile.metrics.impl;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Collection;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;
import java.util.stream.LongStream;
import java.util.stream.Stream;

import javax.json.bind.annotation.JsonbTransient;

import org.eclipse.microprofile.metrics.Histogram;
import org.eclipse.microprofile.metrics.Snapshot;

// todo? rework it to use the classical exponential decay impl?
public class HistogramImpl implements Histogram {
    private static final long INTERVAL_NS = TimeUnit.MINUTES.toNanos(1);
    private static final Value[] EMPTY_VALUES_ARRAY = new Value[0];

    private final LongAdder count = new LongAdder();
    private final Collection<Value> values = new CopyOnWriteArrayList<>();
    private final AtomicLong lastCleanUp = new AtomicLong(System.nanoTime());

    @Override
    public void update(final int value) {
        update((long) value);
    }

    @Override
    public synchronized void update(final long value) {
        refresh();
        count.increment();
        values.add(new Value(System.nanoTime(), value));
    }

    @Override
    public long getCount() {
        return count.sum();
    }

    @Override
    @JsonbTransient
    public Snapshot getSnapshot() {
        refresh();
        return new SnapshotImpl(values.toArray(EMPTY_VALUES_ARRAY));
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

    public double getStdDev() {
        return getSnapshot().getStdDev();
    }

    // cheap way to avoid to explode the mem for nothing
    private void refresh() {
        final long now = System.nanoTime();
        final long lastUpdateNs = lastCleanUp.get();
        final long elaspsedTime = now - lastUpdateNs;
        if (elaspsedTime > INTERVAL_NS && lastCleanUp.compareAndSet(lastUpdateNs, now)) {
            final long cleanFrom = now - INTERVAL_NS;
            values.removeIf(it -> it.timestamp > cleanFrom);
        }
    }

    private static class SnapshotImpl extends Snapshot {
        private final Value[] values;
        private volatile long[] sorted;

        private SnapshotImpl(final Value[] values) {
            this.values = values;
        }

        @Override
        public double getValue(final double quantile) {
            if (values.length == 0) {
                return 0;
            }
            if (values.length == 1) {
                return values[0].value;
            }
            if (sorted == null) {
                synchronized (this) {
                    if (sorted == null) {
                        sorted = getValues();
                        Arrays.sort(sorted);
                    }
                }
            }
            return sorted[(int) Math.floor((sorted.length - 1) * quantile)];
        }

        @Override
        public long[] getValues() {
            return longs().toArray();
        }

        @Override
        public int size() {
            return values.length;
        }

        @Override
        public long getMax() {
            return longs().max().orElse(0);
        }

        @Override
        public double getMean() {
            return longs().sum() / (double) values.length;
        }

        @Override
        public long getMin() {
            return longs().min().orElse(0);
        }

        @Override
        public double getStdDev() {
            if (values.length <= 1) {
                return 0;
            }
            final double mean = getMean();
            return Math.sqrt(longs().map(v -> (long) Math.pow(v - mean, 2)).sum() / values.length - 1);
        }

        @Override
        public void dump(final OutputStream output) {
            longs().forEach(v -> {
                try {
                    output.write((v + "\n").getBytes(StandardCharsets.UTF_8));
                } catch (final IOException e) {
                    throw new IllegalStateException(e);
                }
            });
        }

        private LongStream longs() {
            return Stream.of(values).mapToLong(i -> i.value);
        }
    }

    private static final class Value {
        private final long timestamp;
        private final long value;

        private Value(final long timestamp, final long value) {
            this.timestamp = timestamp;
            this.value = value;
        }
    }
}
