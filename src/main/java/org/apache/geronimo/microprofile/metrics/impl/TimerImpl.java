package org.apache.geronimo.microprofile.metrics.impl;

import java.util.concurrent.Callable;
import java.util.concurrent.TimeUnit;

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
    public Snapshot getSnapshot() {
        return histogram.getSnapshot();
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
