package org.apache.geronimo.microprofile.metrics.impl;

import static java.util.Arrays.copyOf;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.stream.LongStream;

import org.eclipse.microprofile.metrics.Histogram;
import org.eclipse.microprofile.metrics.Snapshot;

// todo: rework it since this impl is not that great, an exponential decay impl is better
// todo: drop values from time to time - previous comment is ok for that
public class HistogramImpl implements Histogram {
    private final Collection<Long> values = new ArrayList<>();

    @Override
    public void update(final int value) {
        update((long) value);
    }

    @Override
    public synchronized void update(final long value) {
        values.add(value);
    }

    @Override
    public long getCount() {
        return values.size();
    }

    @Override
    public Snapshot getSnapshot() {
        return new SnapshotImpl(values.stream().mapToLong(i -> i).toArray());
    }

    private static class SnapshotImpl extends Snapshot {
        private final long[] values;
        private volatile long[] sorted;

        private SnapshotImpl(final long[] values) {
            this.values = values;
        }

        @Override
        public double getValue(final double quantile) {
            if (values.length == 0) {
                return 0;
            }
            if (values.length == 1) {
                return values[0];
            }
            if (sorted == null) {
                synchronized (this) {
                    if (sorted == null) {
                        sorted = new long[values.length];
                        System.arraycopy(values, 0, sorted, 0, values.length);
                        Arrays.sort(sorted);
                    }
                }
            }
            return sorted[(int) Math.floor((sorted.length - 1) * quantile)];
        }

        @Override
        public long[] getValues() {
            return copyOf(values, values.length);
        }

        @Override
        public int size() {
            return values.length;
        }

        @Override
        public long getMax() {
            return LongStream.of(values).max().orElse(0);
        }

        @Override
        public double getMean() {
            return LongStream.of(values).sum() / (double) values.length;
        }

        @Override
        public long getMin() {
            return LongStream.of(values).min().orElse(0);
        }

        @Override
        public double getStdDev() {
            if (values.length <= 1) {
                return 0;
            }
            final double mean = getMean();
            return Math.sqrt(LongStream.of(values).map(v -> (long) Math.pow(v - mean, 2)).sum() / values.length - 1);
        }

        @Override
        public void dump(final OutputStream output) {
            LongStream.of(values).forEach(v -> {
                try {
                    output.write((v + "\n").getBytes(StandardCharsets.UTF_8));
                } catch (final IOException e) {
                    throw new IllegalStateException(e);
                }
            });
        }
    }
}
