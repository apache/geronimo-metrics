package org.apache.geronimo.microprofile.metrics.impl;

import java.util.concurrent.Callable;
import java.util.concurrent.TimeUnit;

import javax.json.bind.annotation.JsonbTransient;

import org.eclipse.microprofile.metrics.Histogram;
import org.eclipse.microprofile.metrics.Meter;
import org.eclipse.microprofile.metrics.Snapshot;
import org.eclipse.microprofile.metrics.Timer;

public class TimerImpl implements Timer {
    private final Histogram histogram;
    private final Meter meter;

    public TimerImpl() {
        this.histogram = new HistogramImpl();
        this.meter = new MeterImpl();
    }

    @Override
    public void update(final long duration, final TimeUnit unit) {
        if (duration >= 0) {
            histogram.update(unit.toNanos(duration));
            meter.mark();
        }
    }

    @Override
    public <T> T time(final Callable<T> event) throws Exception {
        try (final Context context = time()) {
            return event.call();
        }
    }

    @Override
    public void time(final Runnable event) {
        try {
            time(() -> {
                event.run();
                return null;
            });
        } catch (final RuntimeException e) {
            throw e;
        } catch (final Exception e) {
            throw new IllegalStateException(e);
        }
    }

    @Override
    public Context time() {
        return new ContextImpl();
    }

    @Override
    public long getCount() {
        return histogram.getCount();
    }

    @Override
    public double getFifteenMinuteRate() {
        return meter.getFifteenMinuteRate();
    }

    @Override
    public double getFiveMinuteRate() {
        return meter.getFiveMinuteRate();
    }

    @Override
    public double getMeanRate() {
        return meter.getMeanRate();
    }

    @Override
    public double getOneMinuteRate() {
        return meter.getOneMinuteRate();
    }

    @Override
    @JsonbTransient
    public Snapshot getSnapshot() {
        return histogram.getSnapshot();
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

    private class ContextImpl implements Context {
        private final long start = System.nanoTime();

        @Override
        public long stop() {
            final long duration = System.nanoTime() - start;
            update(duration, TimeUnit.NANOSECONDS);
            return duration;
        }

        @Override
        public void close() {
            stop();
        }
    }
}
