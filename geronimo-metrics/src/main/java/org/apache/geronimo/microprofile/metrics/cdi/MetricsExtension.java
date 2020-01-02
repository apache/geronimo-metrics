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
import javax.enterprise.inject.spi.configurator.BeanConfigurator;
import javax.enterprise.util.AnnotationLiteral;
import javax.enterprise.util.Nonbinding;

import org.apache.geronimo.microprofile.metrics.common.BaseMetrics;
import org.apache.geronimo.microprofile.metrics.common.GaugeImpl;
import org.apache.geronimo.microprofile.metrics.common.RegistryImpl;
import org.apache.geronimo.microprofile.metrics.common.jaxrs.MetricsEndpoints;
import org.apache.geronimo.microprofile.metrics.jaxrs.CdiMetricsEndpoints;
import org.eclipse.microprofile.metrics.Counter;
import org.eclipse.microprofile.metrics.Gauge;
import org.eclipse.microprofile.metrics.Histogram;
import org.eclipse.microprofile.metrics.Metadata;
import org.eclipse.microprofile.metrics.Meter;
import org.eclipse.microprofile.metrics.Metric;
import org.eclipse.microprofile.metrics.MetricID;
import org.eclipse.microprofile.metrics.MetricRegistry;
import org.eclipse.microprofile.metrics.MetricType;
import org.eclipse.microprofile.metrics.Tag;
import org.eclipse.microprofile.metrics.Timer;
import org.eclipse.microprofile.metrics.annotation.ConcurrentGauge;
import org.eclipse.microprofile.metrics.annotation.Counted;
import org.eclipse.microprofile.metrics.annotation.Metered;
import org.eclipse.microprofile.metrics.annotation.RegistryType;
import org.eclipse.microprofile.metrics.annotation.Timed;

public class MetricsExtension implements Extension {
    private static final Tag[] NO_TAG = new Tag[0];

    private final MetricRegistry applicationRegistry = new RegistryImpl();
    private final MetricRegistry baseRegistry = new RegistryImpl();
    private final MetricRegistry vendorRegistry = new RegistryImpl();

    private final Map<MetricID, Metadata> registrations = new HashMap<>();
    private final Map<MetricID, Function<BeanManager, Gauge<?>>> gaugeFactories = new HashMap<>();
    private final Collection<Runnable> producersRegistrations = new ArrayList<>();
    private final Collection<CreationalContext<?>> creationalContexts = new ArrayList<>();

    private Map<String, String> environmentalTags;

    void vetoEndpointIfNotActivated(@Observes final ProcessAnnotatedType<CdiMetricsEndpoints> processAnnotatedType) {
        if ("false".equalsIgnoreCase(System.getProperty("geronimo.metrics.jaxrs.activated"))) { // default is secured so deploy
            processAnnotatedType.veto();
        }
    }

    // can happen in shades
    void vetoDefaultRegistry(@Observes final ProcessAnnotatedType<RegistryImpl> processAnnotatedType) {
        processAnnotatedType.veto();
    }

    // can happen in shades
    void vetoNonCdiEndpoint(@Observes final ProcessAnnotatedType<MetricsEndpoints> processAnnotatedType) {
        if (processAnnotatedType.getAnnotatedType().getJavaClass() == MetricsEndpoints.class) { // not subclasses
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
                .methods().stream().filter(method -> {
            final String name = method.getAnnotated().getJavaMember().getName();
            return name.equals("name") || name.equals("tags");
        }).forEach(method -> method.remove(a -> a.annotationType() == Nonbinding.class));

        // we must update @Metrics with that if it exists for the cdi resolution ot match since we make it binding
        environmentalTags = new MetricID("foo").getTags();
    }

    void onMetric(@Observes final ProcessProducerField<? extends Metric, ?> processProducerField, final BeanManager beanManager) {
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

    void onMetric(@Observes ProcessProducerMethod<? extends Metric, ?> processProducerMethod,
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
        final org.eclipse.microprofile.metrics.annotation.Metric config = annotated.getAnnotation(
                org.eclipse.microprofile.metrics.annotation.Metric.class);

        final MetricType type = findType(clazz);
        if (config != null) {
            final String name = Names.findName(injectionPoint.getMember().getDeclaringClass(), injectionPoint.getMember(),
                    of(config.name()).filter(it -> !it.isEmpty()).orElseGet(injectionPoint.getMember()::getName), config.absolute(),
                    "");
            final Metadata metadata = Metadata.builder()
                    .withName(name)
                    .withDisplayName(config.displayName())
                    .withDescription(config.description())
                    .withType(type)
                    .withUnit(config.unit())
                    .build();
            final MetricID id = new MetricID(name, createTags(config.tags()));
            addRegistration(metadata, id, true);

            if (!name.equals(config.name()) || !environmentalTags.isEmpty()) {
                final Annotation[] newQualifiers = Stream.concat(metricInjectionPointProcessEvent.getInjectionPoint().getQualifiers().stream()
                                .filter(it -> it.annotationType() != org.eclipse.microprofile.metrics.annotation.Metric.class),
                        Stream.of(new MetricImpl(metadata, id)))
                        .toArray(Annotation[]::new);
                metricInjectionPointProcessEvent.configureInjectionPoint()
                        .qualifiers(newQualifiers);
            }
        } else {
            final String name = MetricRegistry.name(injectionPoint.getMember().getDeclaringClass(), injectionPoint.getMember().getName());
            final Metadata metadata = Metadata.builder().withName(name).withType(type).build();
            final MetricID metricID = new MetricID(name);
            addRegistration(metadata, metricID, true);

            // ensure the injection uses the qualifier since we'll not register it without
            final Annotation[] newQualifiers = Stream.concat(metricInjectionPointProcessEvent.getInjectionPoint().getQualifiers().stream()
                            .filter(it -> it.annotationType() != Default.class),
                    Stream.of(new MetricImpl(metadata, metricID)))
                    .toArray(Annotation[]::new);
            metricInjectionPointProcessEvent.configureInjectionPoint()
                    .qualifiers(newQualifiers);
        }
    }

    private void addRegistration(final Metadata metadata, final MetricID id, final boolean reusable) {
        final Metadata existing = registrations.putIfAbsent(id, metadata);
        if (existing != null) {
            if (!reusable && !metadata.isReusable()) {
                throw new IllegalArgumentException(id.getName() + " is not set as reusable but is referenced twice");
            }
        }
    }

    public Tag[] createTags(final String[] tags) {
        return Stream.of(tags).filter(it -> it.contains("=")).map(it -> {
            final int sep = it.indexOf("=");
            return new Tag(it.substring(0, sep), it.substring(sep + 1));
        }).toArray(Tag[]::new);
    }

    void findInterceptorMetrics(@Observes @WithAnnotations({
            Counted.class,
            Timed.class,
            ConcurrentGauge.class,
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
                        final Metadata metadata = Metadata.builder()
                                .withName(name)
                                .withDisplayName(counted.displayName())
                                .withDescription(counted.description())
                                .withType(MetricType.COUNTER)
                                .withUnit(counted.unit())
                                .build();
                        final MetricID metricID = new MetricID(name, createTags(counted.tags()));
                        addRegistration(metadata, metricID, counted.reusable() || !isMethod);
                    }

                    final ConcurrentGauge concurrentGauge = ofNullable(method.getAnnotation(ConcurrentGauge.class)).orElseGet(() ->
                            annotatedType.getAnnotation(ConcurrentGauge.class));
                    if (concurrentGauge != null) {
                        final boolean isMethod = method.isAnnotationPresent(ConcurrentGauge.class);
                        final String name = Names.findName(javaClass, javaMember, isMethod ? concurrentGauge.name() : "", concurrentGauge.absolute(),
                                ofNullable(annotatedType.getAnnotation(ConcurrentGauge.class)).map(ConcurrentGauge::name).orElse(""));
                        final Metadata metadata = Metadata.builder()
                                .withName(name)
                                .withDisplayName(concurrentGauge.displayName())
                                .withDescription(concurrentGauge.description())
                                .withType(MetricType.CONCURRENT_GAUGE)
                                .withUnit(concurrentGauge.unit())
                                .build();
                        final MetricID metricID = new MetricID(name, createTags(concurrentGauge.tags()));
                        addRegistration(metadata, metricID, concurrentGauge.reusable() || !isMethod);
                    }

                    final Timed timed = ofNullable(method.getAnnotation(Timed.class)).orElseGet(() -> annotatedType.getAnnotation(Timed.class));
                    if (timed != null) {
                        final boolean isMethod = method.isAnnotationPresent(Timed.class);
                        final String name = Names.findName(javaClass, javaMember, isMethod ? timed.name() : "", timed.absolute(),
                                ofNullable(annotatedType.getAnnotation(Timed.class)).map(Timed::name).orElse(""));
                        final Metadata metadata = Metadata.builder()
                                .withName(name)
                                .withDisplayName(timed.displayName())
                                .withDescription(timed.description())
                                .withType(MetricType.TIMER)
                                .withUnit(timed.unit())
                                .build();
                        final MetricID metricID = new MetricID(name, createTags(timed.tags()));
                        addRegistration(metadata, metricID, timed.reusable() || !isMethod);
                    }

                    final org.eclipse.microprofile.metrics.annotation.Metered metered = ofNullable(method.getAnnotation(org.eclipse.microprofile.metrics.annotation.Metered.class))
                            .orElseGet(() -> annotatedType.getAnnotation(org.eclipse.microprofile.metrics.annotation.Metered.class));
                    if (metered != null) {
                        final boolean isMethod = method.isAnnotationPresent(Metered.class);
                        final String name = Names.findName(javaClass, javaMember, isMethod ? metered.name() : "", metered.absolute(),
                                ofNullable(annotatedType.getAnnotation(Metered.class)).map(Metered::name).orElse(""));
                        final Metadata metadata = Metadata.builder()
                                .withName(name)
                                .withDisplayName(metered.displayName())
                                .withDescription(metered.description())
                                .withType(MetricType.METERED)
                                .withUnit(metered.unit())
                                .build();
                        final MetricID metricID = new MetricID(name, createTags(metered.tags()));
                        addRegistration(metadata, metricID, metered.reusable() || !isMethod);
                    }

                    final org.eclipse.microprofile.metrics.annotation.Gauge gauge = ofNullable(method.getAnnotation(org.eclipse.microprofile.metrics.annotation.Gauge.class))
                            .orElseGet(() -> annotatedType.getAnnotation(org.eclipse.microprofile.metrics.annotation
                                    .Gauge.class));
                    if (gauge != null) {
                        final String name = Names.findName(
                                javaClass, javaMember, gauge.name(), gauge.absolute(),
                                ofNullable(annotatedType.getAnnotation(org.eclipse.microprofile.metrics.annotation.Gauge.class)).map(org.eclipse.microprofile.metrics.annotation.Gauge::name).orElse(""));
                        final Metadata metadata = Metadata.builder()
                                .withName(name)
                                .withDisplayName(gauge.displayName())
                                .withDescription(gauge.description())
                                .withType(MetricType.GAUGE)
                                .withUnit(gauge.unit())
                                .build();
                        final MetricID metricID = new MetricID(name, createTags(gauge.tags()));
                        addRegistration(metadata, metricID, false);
                        gaugeFactories.put(metricID, beanManager -> {
                            final Object reference = getInstance(javaClass, beanManager);
                            final Method mtd = Method.class.cast(javaMember);
                            return new GaugeImpl<>(reference, mtd);
                        });
                    }
                });
    }

    void afterBeanDiscovery(@Observes final AfterBeanDiscovery afterBeanDiscovery) {
        addBean(afterBeanDiscovery, MetricRegistry.Type.APPLICATION.name(), MetricRegistry.class, new RegistryTypeImpl(MetricRegistry.Type.APPLICATION), applicationRegistry, true);
        addBean(afterBeanDiscovery, MetricRegistry.Type.BASE.name(), MetricRegistry.class, new RegistryTypeImpl(MetricRegistry.Type.BASE), baseRegistry, false);
        addBean(afterBeanDiscovery, MetricRegistry.Type.VENDOR.name(), MetricRegistry.class, new RegistryTypeImpl(MetricRegistry.Type.VENDOR), vendorRegistry, false);

        // metrics
        registrations.forEach((id, metadata) -> {
            final String idSuffix = id.getName() + "#" + id.getTagsAsString();
            switch (metadata.getTypeRaw()) {
                case GAUGE:
                    addBean(afterBeanDiscovery, idSuffix, Gauge.class, new MetricImpl(metadata, id), new Gauge<Object>() {
                        private final AtomicReference<Gauge<?>> ref = new AtomicReference<>();

                        @Override
                        public Object getValue() {
                            Gauge<?> gauge = ref.get();
                            if (gauge == null) { // getGauges() is expensive in current form, avoid it
                                gauge = applicationRegistry.getGauges().get(id);
                                ref.compareAndSet(null, gauge);
                            }
                            return gauge.getValue();
                        }
                    }, true);
                    break;
                case TIMER:
                    addBean(afterBeanDiscovery, idSuffix, Timer.class, new MetricImpl(metadata, id),
                            applicationRegistry.timer(metadata, id.getTagsAsList().toArray(NO_TAG)), true);
                    break;
                case COUNTER:
                    addBean(afterBeanDiscovery, idSuffix, Counter.class, new MetricImpl(metadata, id),
                            applicationRegistry.counter(metadata, id.getTagsAsList().toArray(NO_TAG)), true);
                    break;
                case CONCURRENT_GAUGE:
                    addBean(afterBeanDiscovery, idSuffix, org.eclipse.microprofile.metrics.ConcurrentGauge.class,
                            new MetricImpl(metadata, id),
                            applicationRegistry.concurrentGauge(metadata, id.getTagsAsList().toArray(NO_TAG)), true);
                    break;
                case METERED:
                    addBean(afterBeanDiscovery, idSuffix, Meter.class, new MetricImpl(metadata, id),
                            applicationRegistry.meter(metadata, id.getTagsAsList().toArray(NO_TAG)), true);
                    break;
                case HISTOGRAM:
                    addBean(afterBeanDiscovery, idSuffix, Histogram.class, new MetricImpl(metadata, id),
                            applicationRegistry.histogram(metadata, id.getTagsAsList().toArray(NO_TAG)), true);
                    break;
                default:
            }
        });
    }

    void afterDeploymentValidation(@Observes final AfterDeploymentValidation afterDeploymentValidation,
                                   final BeanManager beanManager) {
        registrations.entrySet().stream()
                .filter(e -> e.getValue().getTypeRaw() == MetricType.GAUGE)
                .forEach(entry -> {
                    final Gauge<?> gauge = gaugeFactories.get(entry.getKey()).apply(beanManager);
                    applicationRegistry.register(entry.getValue(), gauge, entry.getKey().getTagsAsList().toArray(NO_TAG));
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
        applicationRegistry.register(
                metadata, Metric.class.cast(getInstance(clazz, beanManager, bean)),
                createTags(config.tags()));
    }

    private Metadata createMetadata(final org.eclipse.microprofile.metrics.annotation.Metric config,
                                    final Class<?> clazz, final Member javaMember, final Class<?> beanClass) {
        final String name = Names.findName(beanClass, javaMember,
                of(config.name()).filter(it -> !it.isEmpty()).orElseGet(javaMember::getName), config.absolute(),
                "");
        final Metadata metadata = Metadata.builder()
                .withName(name)
                .withDisplayName(config.displayName())
                .withDescription(config.description())
                .withType(findType(clazz))
                .withUnit(config.unit())
                .build();
        final MetricID id = new MetricID(name, createTags(config.tags()));
        addRegistration(metadata, id, true);
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
        } else if (org.eclipse.microprofile.metrics.ConcurrentGauge.class.isAssignableFrom(clazz)) {
            type = MetricType.CONCURRENT_GAUGE;
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

    private void addBean(final AfterBeanDiscovery afterBeanDiscovery,
                         final String idSuffix,
                         final Class<?> type,
                         final Annotation qualifier,
                         final Object instance,
                         final boolean addDefaultQualifier) {
        final BeanConfigurator<Object> base = afterBeanDiscovery.addBean()
                .id(MetricsExtension.class.getName() + ":" + type.getName() + ":" + idSuffix)
                .beanClass(type)
                .types(type, Object.class)
                .scope(Dependent.class) // avoid proxies, tck use assertEquals(proxy, registry.get(xxx))
                .createWith(c -> instance);
        if (addDefaultQualifier) {
            base.qualifiers(qualifier, Default.Literal.INSTANCE, Any.Literal.INSTANCE);
        } else {
            base.qualifiers(qualifier, Any.Literal.INSTANCE);
        }
    }

    private static final class MetricImpl extends AnnotationLiteral<org.eclipse.microprofile.metrics.annotation.Metric> implements org.eclipse.microprofile.metrics.annotation.Metric {
        private final Metadata metadata;
        private final String[] tags;

        private MetricImpl(final Metadata metadata, final MetricID metricID) {
            this.metadata = metadata;
            this.tags = metricID.getTags().entrySet().stream()
                    .map(e -> e.getKey() + "=" + e.getValue())
                    .toArray(String[]::new);
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
            return metadata.getDescription().orElse("");
        }

        @Override
        public String unit() {
            return metadata.getUnit().orElse("");
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

    private static class Registration {
        private final MetricID id;
        private final Metadata metadata;

        private Registration(final MetricID id, final Metadata metadata) {
            this.id = id;
            this.metadata = metadata;
        }
    }
}
