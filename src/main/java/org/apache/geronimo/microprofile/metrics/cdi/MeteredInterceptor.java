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
package org.apache.geronimo.microprofile.metrics.cdi;

import static java.util.Optional.ofNullable;

import java.io.Serializable;
import java.lang.reflect.Executable;
import java.lang.reflect.Modifier;
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
                    Modifier.isAbstract(executable.getDeclaringClass().getModifiers()) ? type.getJavaClass() : executable.getDeclaringClass(),
                    executable, metered == null ? null : metered.name(),
                    metered != null && metered.absolute(),
                    ofNullable(type.getAnnotation(Metered.class)).map(Metered::name).orElse(""));

            meter = Meter.class.cast(registry.getMetrics().get(name));
            if (meter == null) {
                throw new IllegalStateException("No meter with name [" + name + "] found in registry [" + registry + "]");
            }
            meters.putIfAbsent(executable, meter);
        }
        return meter;
    }
}
