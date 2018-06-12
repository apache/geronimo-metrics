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

import static java.util.Optional.of;
import static java.util.Optional.ofNullable;

import java.lang.annotation.Annotation;
import java.lang.reflect.Field;
import java.lang.reflect.Member;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import java.util.stream.Stream;

import javax.enterprise.context.Dependent;
import javax.enterprise.context.spi.CreationalContext;
import javax.enterprise.event.Observes;
import javax.enterprise.inject.Any;
import javax.enterprise.inject.Default;
import javax.enterprise.inject.spi.AfterBeanDiscovery;
import javax.enterprise.inject.spi.AfterDeploymentValidation;
import javax.enterprise.inject.spi.Annotated;
import javax.enterprise.inject.spi.AnnotatedCallable;
import javax.enterprise.inject.spi.AnnotatedType;
import javax.enterprise.inject.spi.Bean;
import javax.enterprise.inject.spi.BeanManager;
import javax.enterprise.inject.spi.BeforeBeanDiscovery;
import javax.enterprise.inject.spi.BeforeShutdown;
import javax.enterprise.inject.spi.Extension;
import javax.enterprise.inject.spi.InjectionPoint;
import javax.enterprise.inject.spi.ProcessAnnotatedType;
import javax.enterprise.inject.spi.ProcessInjectionPoint;
import javax.enterprise.inject.spi.ProcessProducerField;
import javax.enterprise.inject.spi.ProcessProducerMethod;
import javax.enterprise.inject.spi.WithAnnotations;
import javax.enterprise.util.AnnotationLiteral;
import javax.enterprise.util.Nonbinding;

import org.apache.geronimo.microprofile.metrics.impl.BaseMetrics;
import org.apache.geronimo.microprofile.metrics.impl.GaugeImpl;
import org.apache.geronimo.microprofile.metrics.impl.RegistryImpl;
import org.apache.geronimo.microprofile.metrics.jaxrs.MetricsEndpoints;
import org.eclipse.microprofile.metrics.Counter;
import org.eclipse.microprofile.metrics.Gauge;
import org.eclipse.microprofile.metrics.Histogram;
import org.eclipse.microprofile.metrics.Metadata;
import org.eclipse.microprofile.metrics.Meter;
import org.eclipse.microprofile.metrics.Metric;
import org.eclipse.microprofile.metrics.MetricRegistry;
import org.eclipse.microprofile.metrics.MetricType;
import org.eclipse.microprofile.metrics.Timer;
import org.eclipse.microprofile.metrics.annotation.Counted;
import org.eclipse.microprofile.metrics.annotation.Metered;
import org.eclipse.microprofile.metrics.annotation.RegistryType;
import org.eclipse.microprofile.metrics.annotation.Timed;

public class MetricsExtension implements Extension {
    private static final Annotation[] EMPTY_ANNOTATIONS = new Annotation[0];

    private final MetricRegistry applicationRegistry = new RegistryImpl();
    private final MetricRegistry baseRegistry = new RegistryImpl();
    private final MetricRegistry vendorRegistry = new RegistryImpl();

    private final Map<String, Metadata> registrations = new HashMap<>();
    private final Map<String, Function<BeanManager, Gauge<?>>> gaugeFactories = new HashMap<>();
    private final Collection<Runnable> producersRegistrations = new ArrayList<>();
    private final Collection<CreationalContext<?>> creationalContexts = new ArrayList<>();

    void letOtherExtensionsUseRegistries(@Observes final ProcessAnnotatedType<MetricsEndpoints> processAnnotatedType) {
        if (!Boolean.getBoolean("geronimo.metrics.jaxrs.activated")) {
            processAnnotatedType.veto();
        }
    }

    void letOtherExtensionsUseRegistries(@Observes final BeforeBeanDiscovery beforeBeanDiscovery, final BeanManager beanManager) {
        beforeBeanDiscovery.addQualifier(RegistryType.class);
        beanManager.fireEvent(applicationRegistry);
        beanManager.fireEvent(applicationRegistry, new RegistryTypeImpl(MetricRegistry.Type.APPLICATION));
        beanManager.fireEvent(baseRegistry, new RegistryTypeImpl(MetricRegistry.Type.BASE));
        beanManager.fireEvent(vendorRegistry, new RegistryTypeImpl(MetricRegistry.Type.VENDOR));

        // we make @Metric.name binding (to avoid to write producers relying on injection point)
        beforeBeanDiscovery.configureQualifier(org.eclipse.microprofile.metrics.annotation.Metric.class)
                .methods().stream().filter(method -> method.getAnnotated().getJavaMember().getName().equals("name"))
                .forEach(method -> method.remove(a -> a.annotationType() == Nonbinding.class));
    }

    private void onMetric(@Observes final ProcessProducerField<? extends Metric, ?> processProducerField, final BeanManager beanManager) {
        final org.eclipse.microprofile.metrics.annotation.Metric config = processProducerField.getAnnotated()
                .getAnnotation(org.eclipse.microprofile.metrics.annotation.Metric.class);
        if (config == null) {
            return;
        }
        final Class<?> clazz = findClass(processProducerField.getAnnotated().getBaseType());
        if (clazz == null || !Metric.class.isAssignableFrom(clazz)) {
            return;
        }
        final Member javaMember = processProducerField.getAnnotatedProducerField().getJavaMember();
        final Bean<?> bean = processProducerField.getBean();
        producersRegistrations.add(() -> registerProducer(beanManager, config, clazz, javaMember, bean));
    }

    private void onMetric(@Observes ProcessProducerMethod<? extends Metric, ?> processProducerMethod,
                          final BeanManager beanManager) {
        final org.eclipse.microprofile.metrics.annotation.Metric config = processProducerMethod.getAnnotated()
                .getAnnotation(org.eclipse.microprofile.metrics.annotation.Metric.class);
        if (config == null) {
            return;
        }
        final Class<?> clazz = findClass(processProducerMethod.getAnnotated().getBaseType());
        if (clazz == null || !Metric.class.isAssignableFrom(clazz)) {
            return;
        }
        final Member javaMember = processProducerMethod.getAnnotatedProducerMethod().getJavaMember();
        final Bean<?> bean = processProducerMethod.getBean();
        producersRegistrations.add(() -> registerProducer(beanManager, config, clazz, javaMember, bean));
    }

    void onMetric(@Observes final ProcessInjectionPoint<?, ?> metricInjectionPointProcessEvent) {
        final InjectionPoint injectionPoint = metricInjectionPointProcessEvent.getInjectionPoint();
        final Class<?> clazz = findClass(injectionPoint.getType());
        if (clazz == null || !Metric.class.isAssignableFrom(clazz)) {
            return;
        }

        final Annotated annotated = injectionPoint.getAnnotated();
        final org.eclipse.microprofile.metrics.annotation.Metric config = annotated.getAnnotation(org.eclipse.microprofile.metrics.annotation.Metric.class);

        final MetricType type = findType(clazz);
        if (config != null) {
            String name = Names.findName(injectionPoint.getMember().getDeclaringClass(), injectionPoint.getMember(),
                    of(config.name()).filter(it -> !it.isEmpty()).orElseGet(injectionPoint.getMember()::getName), config.absolute(),
                    "");
            final Metadata metadata = new Metadata(name, config.displayName(), config.description(), type, config.unit());
            Stream.of(config.tags()).forEach(metadata::addTag);
            final Metadata existing = registrations.putIfAbsent(name, metadata);
            if (existing != null) { // merge tags
                Stream.of(config.tags()).forEach(existing::addTag);
            }

            if (!name.equals(config.name())) {
                final Annotation[] newQualifiers = Stream.concat(metricInjectionPointProcessEvent.getInjectionPoint().getQualifiers().stream()
                                .filter(it -> it.annotationType() != org.eclipse.microprofile.metrics.annotation.Metric.class),
                        Stream.of(new MetricImpl(metadata)))
                        .toArray(Annotation[]::new);
                metricInjectionPointProcessEvent.configureInjectionPoint()
                        .qualifiers(newQualifiers);
            }
        } else {
            final String name = MetricRegistry.name(injectionPoint.getMember().getDeclaringClass(), injectionPoint.getMember().getName());
            final Metadata metadata = new Metadata(name, type);
            registrations.putIfAbsent(name, metadata);

            // ensure the injection uses the qualifier since we'll not register it without
            final Annotation[] newQualifiers = Stream.concat(metricInjectionPointProcessEvent.getInjectionPoint().getQualifiers().stream()
                            .filter(it -> it.annotationType() != Default.class),
                    Stream.of(new MetricImpl(metadata)))
                    .toArray(Annotation[]::new);
            metricInjectionPointProcessEvent.configureInjectionPoint()
                    .qualifiers(newQualifiers);
        }
    }

    void findInterceptorMetrics(@Observes @WithAnnotations({
            Counted.class,
            Timed.class,
            org.eclipse.microprofile.metrics.annotation.Metered.class,
            org.eclipse.microprofile.metrics.annotation.Gauge.class
    }) final ProcessAnnotatedType<?> pat) {
        final AnnotatedType<?> annotatedType = pat.getAnnotatedType();
        final Class<?> javaClass = annotatedType.getJavaClass();
        if (javaClass.getName().startsWith("org.apache.geronimo.microprofile.metrics.") ||
                Modifier.isAbstract(javaClass.getModifiers()) ||
                javaClass.isInterface()) {
            return;
        }

        Stream.concat(annotatedType.getMethods().stream(), annotatedType.getConstructors().stream())
                .filter(method -> method.getJavaMember().getDeclaringClass() == javaClass || Modifier.isAbstract(method.getJavaMember().getDeclaringClass().getModifiers()))
                .filter(method -> !method.getJavaMember().isSynthetic() && !Modifier.isPrivate(method.getJavaMember().getModifiers()))
                .forEach(method -> {
                    final Member javaMember = method.getJavaMember();

                    final Counted counted = ofNullable(method.getAnnotation(Counted.class)).orElseGet(() ->
                            annotatedType.getAnnotation(Counted.class));
                    if (counted != null) {
                        final boolean isMethod = method.isAnnotationPresent(Counted.class);
                        final String name = Names.findName(javaClass, javaMember, isMethod ? counted.name() : "", counted.absolute(),
                                ofNullable(annotatedType.getAnnotation(Counted.class)).map(Counted::name).orElse(""));
                        final Metadata metadata = new Metadata(name, counted.displayName(), counted.description(), MetricType.COUNTER, counted.unit());
                        Stream.of(counted.tags()).forEach(metadata::addTag);
                        addRegistration(method, name, metadata, counted.reusable() || !isMethod, counted.tags());
                    }

                    final Timed timed = ofNullable(method.getAnnotation(Timed.class)).orElseGet(() -> annotatedType.getAnnotation(Timed.class));
                    if (timed != null) {
                        final boolean isMethod = method.isAnnotationPresent(Timed.class);
                        final String name = Names.findName(javaClass, javaMember, isMethod ? timed.name() : "", timed.absolute(),
                                ofNullable(annotatedType.getAnnotation(Timed.class)).map(Timed::name).orElse(""));
                        final Metadata metadata = new Metadata(name, timed.displayName(), timed.description(), MetricType.TIMER, timed.unit());
                        Stream.of(timed.tags()).forEach(metadata::addTag);
                        addRegistration(method, name, metadata, timed.reusable() || !isMethod, timed.tags());
                    }

                    final org.eclipse.microprofile.metrics.annotation.Metered metered = ofNullable(method.getAnnotation(org.eclipse.microprofile.metrics.annotation.Metered.class))
                            .orElseGet(() -> annotatedType.getAnnotation(org.eclipse.microprofile.metrics.annotation.Metered.class));
                    if (metered != null) {
                        final boolean isMethod = method.isAnnotationPresent(Metered.class);
                        final String name = Names.findName(javaClass, javaMember, isMethod ? metered.name() : "", metered.absolute(),
                                ofNullable(annotatedType.getAnnotation(Metered.class)).map(Metered::name).orElse(""));
                        final Metadata metadata = new Metadata(name, metered.displayName(), metered.description(), MetricType.METERED, metered.unit());
                        Stream.of(metered.tags()).forEach(metadata::addTag);
                        addRegistration(method, name, metadata, metered.reusable() || !isMethod, metered.tags());
                    }

                    final org.eclipse.microprofile.metrics.annotation.Gauge gauge = ofNullable(method.getAnnotation(org.eclipse.microprofile.metrics.annotation.Gauge.class))
                            .orElseGet(() -> annotatedType.getAnnotation(org.eclipse.microprofile.metrics.annotation
                                    .Gauge.class));
                    if (gauge != null) {
                        final String name = Names.findName(
                                javaClass, javaMember, gauge.name(), gauge.absolute(),
                                ofNullable(annotatedType.getAnnotation(org.eclipse.microprofile.metrics.annotation.Gauge.class)).map(org.eclipse.microprofile.metrics.annotation.Gauge::name).orElse(""));
                        final Metadata metadata = new Metadata(name, gauge.displayName(), gauge.description(), MetricType.GAUGE, gauge.unit());
                        Stream.of(gauge.tags()).forEach(metadata::addTag);
                        addRegistration(method, name, metadata, false, gauge.tags());
                        gaugeFactories.put(name, beanManager -> {
                            final Object reference = getInstance(javaClass, beanManager);
                            final Method mtd = Method.class.cast(javaMember);
                            return new GaugeImpl<>(reference, mtd);
                        });
                    }
                });
    }

    void afterBeanDiscovery(@Observes final AfterBeanDiscovery afterBeanDiscovery, final BeanManager beanManager) {
        addBean(afterBeanDiscovery, MetricRegistry.Type.APPLICATION.name() + "_@Default", MetricRegistry.class, Default.Literal.INSTANCE, applicationRegistry);
        addBean(afterBeanDiscovery, MetricRegistry.Type.APPLICATION.name(), MetricRegistry.class, new RegistryTypeImpl(MetricRegistry.Type.APPLICATION), applicationRegistry);
        addBean(afterBeanDiscovery, MetricRegistry.Type.BASE.name(), MetricRegistry.class, new RegistryTypeImpl(MetricRegistry.Type.BASE), baseRegistry);
        addBean(afterBeanDiscovery, MetricRegistry.Type.VENDOR.name(), MetricRegistry.class, new RegistryTypeImpl(MetricRegistry.Type.VENDOR), vendorRegistry);

        // metrics
        registrations.forEach((name, registration) -> {
            switch (registration.getTypeRaw()) {
                case GAUGE:
                    addBean(afterBeanDiscovery, name, Gauge.class, new MetricImpl(registration), new Gauge<Object>() {
                        private final AtomicReference<Gauge<?>> ref = new AtomicReference<>();

                        @Override
                        public Object getValue() {
                            Gauge<?> gauge = ref.get();
                            if (gauge == null) { // getGauges() is expensive in current form, avoid it
                                gauge = applicationRegistry.getGauges().get(name);
                                ref.compareAndSet(null, gauge);
                            }
                            return gauge.getValue();
                        }
                    });
                    break;
                case TIMER:
                    addBean(afterBeanDiscovery, name, Timer.class, new MetricImpl(registration), applicationRegistry.timer(registration));
                    break;
                case COUNTER:
                    addBean(afterBeanDiscovery, name, Counter.class, new MetricImpl(registration), applicationRegistry.counter(registration));
                    break;
                case METERED:
                    addBean(afterBeanDiscovery, name, Meter.class, new MetricImpl(registration), applicationRegistry.meter(registration));
                    break;
                case HISTOGRAM:
                    addBean(afterBeanDiscovery, name, Histogram.class, new MetricImpl(registration), applicationRegistry.histogram(registration));
                    break;
                default:
            }
        });
    }

    void afterDeploymentValidation(@Observes final AfterDeploymentValidation afterDeploymentValidation,
                                   final BeanManager beanManager) {
        registrations.values().stream().filter(m -> m.getTypeRaw() == MetricType.GAUGE)
                .forEach(registration -> {
                    final Gauge<?> gauge = gaugeFactories.get(registration.getName()).apply(beanManager);
                    applicationRegistry.register(registration, gauge);
                });
        producersRegistrations.forEach(Runnable::run);

        producersRegistrations.clear();
        gaugeFactories.clear();
        registrations.clear();

        // mainly for tck, to drop if we add real vendor metrics
        vendorRegistry.counter("startTime").inc(System.currentTimeMillis());

        if (!Boolean.getBoolean("geronimo.metrics.base.skip")) {
            new BaseMetrics(baseRegistry).register();
        }
    }

    void beforeShutdown(@Observes final BeforeShutdown beforeShutdown) {
        creationalContexts.forEach(CreationalContext::release);
    }

    private void registerProducer(final BeanManager beanManager, final org.eclipse.microprofile.metrics.annotation.Metric config,
                                  final Class<?> clazz, final Member javaMember, final Bean<?> bean) {
        Class<?> beanClass = bean.getBeanClass();
        if (beanClass == null) {
            beanClass = javaMember.getDeclaringClass();
        }
        final Metadata metadata = createMetadata(config, clazz, javaMember, beanClass);
        applicationRegistry.register(metadata, Metric.class.cast(getInstance(clazz, beanManager, bean)));
    }

    private Metadata createMetadata(final org.eclipse.microprofile.metrics.annotation.Metric config,
                                    final Class<?> clazz, final Member javaMember, final Class<?> beanClass) {
        final String name = Names.findName(beanClass, javaMember,
                of(config.name()).filter(it -> !it.isEmpty()).orElseGet(javaMember::getName), config.absolute(),
                "");
        final Metadata metadata = new Metadata(name, config.displayName(), config.description(), findType(clazz), config.unit());
        Stream.of(config.tags()).forEach(metadata::addTag);
        final Metadata existing = registrations.putIfAbsent(name, metadata);
        if (existing != null) { // merge tags
            Stream.of(config.tags()).forEach(existing::addTag);
        }
        return metadata;
    }

    private MetricType findType(final Class<?> clazz) {
        final MetricType type;
        if (Counter.class.isAssignableFrom(clazz)) {
            type = MetricType.COUNTER;
        } else if (Gauge.class.isAssignableFrom(clazz)) {
            type = MetricType.GAUGE;
        } else if (Meter.class.isAssignableFrom(clazz)) {
            type = MetricType.METERED;
        } else if (Timer.class.isAssignableFrom(clazz)) {
            type = MetricType.TIMER;
        } else if (Histogram.class.isAssignableFrom(clazz)) {
            type = MetricType.HISTOGRAM;
        } else {
            type = MetricType.INVALID;
        }
        return type;
    }

    private Class<?> findClass(final Type baseType) {
        Type type = baseType;
        if (ParameterizedType.class.isInstance(baseType)) {
            type = ParameterizedType.class.cast(baseType).getRawType();
        }
        if (Class.class.isInstance(type)) {
            return Class.class.cast(type);
        }
        return null;
    }

    private Object getInstance(final Class<?> javaClass, final BeanManager beanManager) {
        final Bean<?> bean = beanManager.resolve(beanManager.getBeans(javaClass, Default.Literal.INSTANCE));
        return getInstance(javaClass, beanManager, bean);
    }

    private Object getInstance(final Class<?> javaClass, final BeanManager beanManager, final Bean<?> bean) {
        final CreationalContext<Object> creationalContext = beanManager.createCreationalContext(null);
        final Object reference = beanManager.getReference(bean, javaClass, creationalContext);
        if (!beanManager.isNormalScope(bean.getScope())) {
            creationalContexts.add(creationalContext);
        }
        return reference;
    }

    private void addRegistration(final AnnotatedCallable<?> executable, final String name, final Metadata metadata, final boolean reusable, final String[] tags) {
        final Metadata existing = registrations.putIfAbsent(name, metadata);
        if (existing != null) { // merge tags
            if (reusable) {
                Stream.of(tags).forEach(metadata::addTag);
            } else {
                throw new IllegalArgumentException(name + " is not set as reusable on " + executable + " but was used somewhere else");
            }
        }
    }

    private void addBean(final AfterBeanDiscovery afterBeanDiscovery,
                         final String idSuffix,
                         final Class<?> type,
                         final Annotation qualifier,
                         final Object instance) {
        afterBeanDiscovery.addBean()
                .id(MetricsExtension.class.getName() + ":" + type.getName() + ":" + idSuffix)
                .beanClass(type)
                .types(type, Object.class)
                .qualifiers(qualifier, Any.Literal.INSTANCE)
                .scope(Dependent.class) // avoid proxies, tck use assertEquals(proxy, registry.get(xxx))
                .createWith(c -> instance);
    }

    private static final class MetricImpl extends AnnotationLiteral<org.eclipse.microprofile.metrics.annotation.Metric> implements org.eclipse.microprofile.metrics.annotation.Metric {
        private final Metadata metadata;
        private final String[] tags;

        private MetricImpl(final Metadata metadata) {
            this.metadata = metadata;
            this.tags = metadata.getTagsAsString().split(",");
        }

        @Override
        public Class<? extends Annotation> annotationType() {
            return org.eclipse.microprofile.metrics.annotation.Metric.class;
        }

        @Override
        public String name() {
            return metadata.getName();
        }

        @Override
        public String[] tags() {
            return tags;
        }

        @Override
        public boolean absolute() {
            return false;
        }

        @Override
        public String displayName() {
            return ofNullable(metadata.getDisplayName()).orElse("");
        }

        @Override
        public String description() {
            return ofNullable(metadata.getDescription()).orElse("");
        }

        @Override
        public String unit() {
            return metadata.getUnit();
        }
    }

    private static final class RegistryTypeImpl extends AnnotationLiteral<RegistryType> implements RegistryType {

        private final MetricRegistry.Type type;

        private RegistryTypeImpl(final MetricRegistry.Type type) {
            this.type = type;
        }

        @Override
        public MetricRegistry.Type type() {
            return type;
        }

        @Override
        public Class<? extends Annotation> annotationType() {
            return RegistryType.class;
        }
    }
}
