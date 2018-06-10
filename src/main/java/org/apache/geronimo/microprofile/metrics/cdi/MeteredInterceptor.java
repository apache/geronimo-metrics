package org.apache.geronimo.microprofile.metrics.cdi;

import java.io.Serializable;
import java.lang.reflect.Executable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.stream.Stream;

import javax.annotation.Priority;
import javax.enterprise.inject.Intercepted;
import javax.enterprise.inject.spi.AnnotatedType;
import javax.enterprise.inject.spi.Bean;
import javax.enterprise.inject.spi.BeanManager;
import javax.inject.Inject;
import javax.interceptor.AroundConstruct;
import javax.interceptor.AroundInvoke;
import javax.interceptor.Interceptor;
import javax.interceptor.InvocationContext;

import org.eclipse.microprofile.metrics.Meter;
import org.eclipse.microprofile.metrics.MetricRegistry;
import org.eclipse.microprofile.metrics.annotation.Metered;

@Metered
@Interceptor
@Priority(Interceptor.Priority.LIBRARY_BEFORE)
public class MeteredInterceptor implements Serializable {
    @Inject
    private MetricRegistry registry;

    @Inject
    @Intercepted
    private Bean<?> bean;

    @Inject
    private BeanManager beanManager;

    private transient volatile ConcurrentMap<Executable, Meter> meters = new ConcurrentHashMap<>();

    @AroundConstruct
    public Object onConstructor(final InvocationContext context) throws Exception {
        findMeter(context.getConstructor()).mark();
        return context.proceed();
    }

    @AroundInvoke
    public Object onMethod(final InvocationContext context) throws Exception {
        findMeter(context.getMethod()).mark();
        return context.proceed();
    }

    private Meter findMeter(final Executable executable) {
        if (meters == null) {
            synchronized (this) {
                if (meters == null) {
                    meters = new ConcurrentHashMap<>();
                }
            }
        }
        Meter meter = meters.get(executable);
        if (meter == null) {
            final AnnotatedType<?> type = beanManager.createAnnotatedType(bean.getBeanClass());
            final Metered metered = Stream.concat(type.getMethods().stream(), type.getConstructors().stream())
                    .filter(it -> it.getJavaMember().equals(executable))
                    .findFirst()
                    .map(m -> m.getAnnotation(Metered.class))
                    .orElse(null);
            final String name = Names.findName(
                    type.getJavaClass(),
                    executable, metered == null ? null : metered.name(),
                    metered != null && metered.absolute());

            meter = Meter.class.cast(registry.getMetrics().get(name));
            if (meter == null) {
                throw new IllegalStateException("No meter with name [" + name + "] found in registry [" + registry + "]");
            }
            meters.putIfAbsent(executable, meter);
        }
        return meter;
    }
}
