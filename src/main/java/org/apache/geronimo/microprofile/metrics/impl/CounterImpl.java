package org.apache.geronimo.microprofile.metrics.impl;

import java.util.concurrent.atomic.LongAdder;

import org.eclipse.microprofile.metrics.Counter;

public class CounterImpl implements Counter {
    private final LongAdder delegate = new LongAdder();

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
