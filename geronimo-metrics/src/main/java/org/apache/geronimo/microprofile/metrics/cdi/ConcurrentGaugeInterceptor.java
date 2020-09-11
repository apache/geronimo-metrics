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

import org.eclipse.microprofile.metrics.ConcurrentGauge;
import org.eclipse.microprofile.metrics.MetricID;
import org.eclipse.microprofile.metrics.MetricRegistry;
import org.eclipse.microprofile.metrics.Tag;

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
import java.io.Serializable;
import java.lang.reflect.Executable;
import java.lang.reflect.Modifier;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.stream.Stream;

import static java.util.Optional.ofNullable;

@Interceptor
@Priority(Interceptor.Priority.LIBRARY_BEFORE)
@org.eclipse.microprofile.metrics.annotation.ConcurrentGauge
public class ConcurrentGaugeInterceptor implements Serializable {
    @Inject
    private MetricRegistry registry;

    @Inject
    @Intercepted
    private Bean<?> bean;

    @Inject
    private BeanManager beanManager;

    @Inject
    private MetricsExtension extension;

    private transient volatile ConcurrentMap<Executable, Meta> gauges = new ConcurrentHashMap<>();

    @AroundConstruct
    public Object onConstructor(final InvocationContext context) throws Exception {
        return invoke(context, context.getConstructor());
    }

    @AroundInvoke
    public Object onMethod(final InvocationContext context) throws Exception {
        return invoke(context, context.getMethod());
    }

    private Object invoke(final InvocationContext context, final Executable executable) throws Exception {
        final Meta counter = find(executable);
        counter.gauge.inc();
        try {
            return context.proceed();
        } finally {
            counter.gauge.dec();
        }
    }

    private Meta find(final Executable executable) {
        if (gauges == null) {
            synchronized (this) {
                if (gauges == null) {
                    gauges = new ConcurrentHashMap<>();
                }
            }
        }
        Meta meta = gauges.get(executable);
        if (meta == null) {
            final AnnotatedType<?> type = beanManager.createAnnotatedType(bean.getBeanClass());
            final org.eclipse.microprofile.metrics.annotation.ConcurrentGauge concurrentGauge = Stream.concat(type.getMethods().stream(), type.getConstructors().stream())
                    .filter(it -> it.getJavaMember().equals(executable))
                    .findFirst()
                    .map(m -> m.getAnnotation(org.eclipse.microprofile.metrics.annotation.ConcurrentGauge.class))
                    .orElse(null);
            final String name = Names.findName(
                    Modifier.isAbstract(executable.getDeclaringClass().getModifiers()) ? type.getJavaClass() : executable.getDeclaringClass(),
                    executable, concurrentGauge == null ? null : concurrentGauge.name(),
                    concurrentGauge != null && concurrentGauge.absolute(),
                    ofNullable(extension.getAnnotation(type, org.eclipse.microprofile.metrics.annotation.ConcurrentGauge.class))
                            .map(org.eclipse.microprofile.metrics.annotation.ConcurrentGauge::name)
                            .orElse(""));

            final ConcurrentGauge gauge = registry.getConcurrentGauge(
                    new MetricID(name, extension.createTags(concurrentGauge == null ? new String[0] : concurrentGauge.tags())));
            if (gauge == null) {
                throw new IllegalStateException("No counter with name [" + name + "] found in registry [" + registry + "]");
            }
            meta = new Meta(gauge);
            gauges.putIfAbsent(executable, meta);
        }
        return meta;
    }

    private static final class Meta {
        private final ConcurrentGauge gauge;

        private Meta(final ConcurrentGauge gauge) {
            this.gauge = gauge;
        }
    }
}
