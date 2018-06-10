package org.apache.geronimo.microprofile.metrics.impl;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;

import org.eclipse.microprofile.metrics.Gauge;

public class GaugeImpl<T> implements Gauge<T> {
    private final Method method;
    private final Object reference;

    public GaugeImpl(final Object reference, final Method method) {
        this.method = method;
        this.reference = reference;
    }

    @Override
    public T getValue() {
        try {
            return (T) method.invoke(reference);
        } catch (final IllegalAccessException e) {
            throw new IllegalStateException(e);
        } catch (final InvocationTargetException e) {
            throw new IllegalStateException(e.getCause());
        }
    }
}
