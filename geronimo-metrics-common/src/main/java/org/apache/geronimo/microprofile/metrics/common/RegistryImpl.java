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
package org.apache.geronimo.microprofile.metrics.common;

import org.eclipse.microprofile.metrics.ConcurrentGauge;
import org.eclipse.microprofile.metrics.Counter;
import org.eclipse.microprofile.metrics.Gauge;
import org.eclipse.microprofile.metrics.Histogram;
import org.eclipse.microprofile.metrics.Metadata;
import org.eclipse.microprofile.metrics.Meter;
import org.eclipse.microprofile.metrics.Metric;
import org.eclipse.microprofile.metrics.MetricFilter;
import org.eclipse.microprofile.metrics.MetricID;
import org.eclipse.microprofile.metrics.MetricRegistry;
import org.eclipse.microprofile.metrics.MetricType;
import org.eclipse.microprofile.metrics.MetricUnits;
import org.eclipse.microprofile.metrics.SimpleTimer;
import org.eclipse.microprofile.metrics.Tag;
import org.eclipse.microprofile.metrics.Timer;

import java.util.Map;
import java.util.Objects;
import java.util.SortedMap;
import java.util.SortedSet;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;
import java.util.function.Supplier;

import static java.util.stream.Collectors.toCollection;
import static java.util.stream.Collectors.toMap;

public class RegistryImpl implements MetricRegistry {
    private static final Tag[] NO_TAG = new Tag[0];

    private final Type type;
    private final Tag[] globalTags;
    private final ConcurrentMap<MetricID, Holder<? extends Metric>> metrics = new ConcurrentHashMap<>();

    public RegistryImpl(final Type type) {
        this(type, new Tag[0]);
    }

    public RegistryImpl(final Type type, final Tag[] globalTags) {
        this.type = type;
        this.globalTags = globalTags;
    }

    public Tag[] getGlobalTags() {
        return globalTags;
    }

    @Override
    public <T extends Metric> T register(final Metadata metadata, final T metric) throws IllegalArgumentException {
        return register(metadata, metric, NO_TAG);
    }

    @Override
    public <T extends Metric> T register(final Metadata metadata, final T metric, final Tag... tags) throws IllegalArgumentException {
        final MetricID metricID = new MetricID(metadata.getName(), tags);
        final Holder<? extends Metric> present = metrics.putIfAbsent(
                metricID, new Holder<>(metric, metadata, metricID));
        return present != null ? (T) present.metric : metric;
    }

    @Override
    public <T extends Metric> T register(final String name, final T metric) throws IllegalArgumentException {
        final MetricType type;
        if (Counter.class.isInstance(metric)) {
            type = MetricType.COUNTER;
        } else if (Gauge.class.isInstance(metric)) {
            type = MetricType.GAUGE;
        } else if (Meter.class.isInstance(metric)) {
            type = MetricType.METERED;
        } else if (Timer.class.isInstance(metric)) {
            type = MetricType.TIMER;
        } else if (Histogram.class.isInstance(metric)) {
            type = MetricType.HISTOGRAM;
        } else if (ConcurrentGauge.class.isInstance(metric)) {
            type = MetricType.CONCURRENT_GAUGE;
        } else {
            type = MetricType.INVALID;
        }
        return register(Metadata.builder().withName(name).withType(type).build(), metric);
    }

    @Override
    public Counter getCounter(MetricID metricID) {
        return getMetric(metricID, Counter.class);
    }

    @Override
    public Counter counter(final String name) {
        return counter(Metadata.builder().withName(name).withType(MetricType.COUNTER).build(), NO_TAG);
    }

    @Override
    public Counter counter(final String name, final Tag... tags) {
        return counter(Metadata.builder().withName(name).withType(MetricType.COUNTER).build(), tags);
    }

    @Override
    public Counter counter(final MetricID metricID) {
        return counter(metricID.getName(), metricID.getTagsAsArray());
    }

    @Override
    public Counter counter(final Metadata metadata) {
        return counter(metadata, NO_TAG);
    }

    @Override
    public Counter counter(final Metadata metadata, final Tag... tags) {
        final MetricID metricID = new MetricID(metadata.getName(), tags);
        Holder<? extends Metric> holder = metrics.get(metricID);
        if (holder == null) {
            holder = new Holder<>(new CounterImpl(
                    metadata.getUnit() == null ? MetricUnits.NONE : metadata.getUnit()), enforceType(metadata, MetricType.COUNTER), metricID);
            final Holder<? extends Metric> existing = metrics.putIfAbsent(holder.metricID, holder);
            if (existing != null) {
                holder = existing;
            }
        }
        if (!Counter.class.isInstance(holder.metric)) {
            throw new IllegalArgumentException(holder.metric + " is not a counter");
        }
        return Counter.class.cast(holder.metric);
    }

    @Override
    public ConcurrentGauge getConcurrentGauge(MetricID metricID) {
        return getMetric(metricID, ConcurrentGauge.class);
    }

    @Override
    public ConcurrentGauge concurrentGauge(final String name) {
        return concurrentGauge(Metadata.builder().withName(name).withType(MetricType.CONCURRENT_GAUGE).build());
    }

    @Override
    public ConcurrentGauge concurrentGauge(final String name, final Tag... tags) {
        return concurrentGauge(Metadata.builder().withName(name).withType(MetricType.CONCURRENT_GAUGE).build(), tags);
    }

    @Override
    public ConcurrentGauge concurrentGauge(final MetricID metricID) {
        return concurrentGauge(metricID.getName(), metricID.getTagsAsArray());
    }

    @Override
    public ConcurrentGauge concurrentGauge(final Metadata metadata) {
        return concurrentGauge(metadata, NO_TAG);
    }

    @Override
    public ConcurrentGauge concurrentGauge(final Metadata metadata, final Tag... tags) {
        final MetricID metricID = new MetricID(metadata.getName(), tags);
        Holder<? extends Metric> holder = metrics.get(metricID);
        if (holder == null) {
            holder = new Holder<>(new ConcurrentGaugeImpl(
                    metadata.getUnit() == null ? MetricUnits.NONE : metadata.getUnit()), enforceType(metadata, MetricType.CONCURRENT_GAUGE), metricID);
            final Holder<? extends Metric> existing = metrics.putIfAbsent(holder.metricID, holder);
            if (existing != null) {
                holder = existing;
            }
        }
        if (!ConcurrentGauge.class.isInstance(holder.metric)) {
            throw new IllegalArgumentException(holder.metric + " is not a concurrent gauge");
        }
        return ConcurrentGauge.class.cast(holder.metric);
    }

    @Override
    public <T, R extends Number> Gauge<R> gauge(final String name, final T object, final Function<T, R> func, Tag... tags) {
        return gauge(new MetricID(name, tags), () -> func.apply(object));
    }

    @Override
    public <T, R extends Number> Gauge<R> gauge(final MetricID metricID, final T object, final Function<T, R> func) {
        return gauge(metricID, () -> func.apply(object));
    }

    @Override
    public <T, R extends Number> Gauge<R> gauge(final Metadata metadata, final T object, final Function<T, R> func, final Tag... tags) {
        final MetricID metricID = new MetricID(metadata.getName(), tags);
        Holder<? extends Metric> holder = metrics.get(metricID);
        if (holder == null) {
            holder = new Holder<>(new SimpleGaugeImpl<>(() -> func.apply(object)), enforceType(metadata, MetricType.GAUGE), metricID);
            final Holder<? extends Metric> existing = metrics.putIfAbsent(holder.metricID, holder);
            if (existing != null) {
                holder = existing;
            }
        }
        if (!Gauge.class.isInstance(holder.metric)) {
            throw new IllegalArgumentException(holder.metric + " is not a gauge");
        }
        return Gauge.class.cast(holder.metric);
    }

    @Override
    public <T extends Number> Gauge<T> gauge(final String name, final Supplier<T> supplier, final Tag... tags) {
        return gauge(new MetricID(name, tags), supplier);
    }

    @Override
    public <T extends Number> Gauge<T> gauge(final MetricID metricID, final Supplier<T> supplier) {
        Holder<? extends Metric> holder = metrics.get(metricID);
        if (holder == null) {
            holder = new Holder<>(
                    new SimpleGaugeImpl<>(supplier),
                    Metadata.builder().withName(metricID.getName()).withType(MetricType.GAUGE).build(),
                    metricID);
            final Holder<? extends Metric> existing = metrics.putIfAbsent(holder.metricID, holder);
            if (existing != null) {
                holder = existing;
            }
        }
        if (!Gauge.class.isInstance(holder.metric)) {
            throw new IllegalArgumentException(holder.metric + " is not a gauge");
        }
        return Gauge.class.cast(holder.metric);
    }

    @Override
    public <T extends Number> Gauge<T> gauge(final Metadata metadata, final Supplier<T> supplier, final Tag... tags) {
        return register(metadata, new SimpleGaugeImpl<>(supplier), tags);
    }

    @Override
    public Gauge<?> getGauge(MetricID metricID) {
        return getMetric(metricID, Gauge.class);
    }

    @Override
    public Histogram getHistogram(MetricID metricID) {
        return this.getMetric(metricID, Histogram.class);
    }

    @Override
    public Histogram histogram(final String name) {
        return histogram(Metadata.builder().withName(name).withType(MetricType.HISTOGRAM).build());
    }

    @Override
    public Histogram histogram(final String name, final Tag... tags) {
        return histogram(Metadata.builder().withName(name).withType(MetricType.HISTOGRAM).build(), tags);
    }

    @Override
    public Histogram histogram(final MetricID metricID) {
        return histogram(metricID.getName(), metricID.getTagsAsArray());
    }

    @Override
    public Histogram histogram(final Metadata metadata) {
        return histogram(metadata, NO_TAG);
    }

    @Override
    public Histogram histogram(final Metadata metadata, final Tag... tags) {
        final MetricID metricID = new MetricID(metadata.getName(), tags);
        Holder<? extends Metric> holder = metrics.get(metricID);
        if (holder == null) {
            holder = new Holder<>(new HistogramImpl(metadata.getUnit() == null ? MetricUnits.NONE : metadata.getUnit()), enforceType(metadata, MetricType.HISTOGRAM), metricID);
            final Holder<? extends Metric> existing = metrics.putIfAbsent(metricID, holder);
            if (existing != null) {
                holder = existing;
            }
        }
        if (!Histogram.class.isInstance(holder.metric)) {
            throw new IllegalArgumentException(holder.metric + " is not a histogram");
        }
        return Histogram.class.cast(holder.metric);
    }

    @Override
    public Meter getMeter(MetricID metricID) {
        return getMetric(metricID, Meter.class);
    }

    @Override
    public Meter meter(final String name) {
        return meter(Metadata.builder().withName(name).withType(MetricType.METERED).build());
    }

    @Override
    public Meter meter(final String name, final Tag... tags) {
        return meter(Metadata.builder().withName(name).withType(MetricType.METERED).build(), tags);
    }

    @Override
    public Meter meter(final MetricID metricID) {
        return meter(metricID.getName(), metricID.getTagsAsArray());
    }

    @Override
    public Meter meter(final Metadata metadata) {
        return meter(metadata, NO_TAG);
    }

    @Override
    public Meter meter(final Metadata metadata, final Tag... tags) {
        final MetricID metricID = new MetricID(metadata.getName(), tags);
        Holder<? extends Metric> holder = metrics.get(metricID);
        if (holder == null) {
            holder = new Holder<>(new MeterImpl(metadata.getUnit() == null ? MetricUnits.NONE : metadata.getUnit()), enforceType(metadata, MetricType.METERED), metricID);
            final Holder<? extends Metric> existing = metrics.putIfAbsent(metricID, holder);
            if (existing != null) {
                holder = existing;
            }
        }
        if (!Meter.class.isInstance(holder.metric)) {
            throw new IllegalArgumentException(holder.metric + " is not a meter");
        }
        return Meter.class.cast(holder.metric);
    }

    @Override
    public SimpleTimer getSimpleTimer(MetricID metricID) {
        return getMetric(metricID, SimpleTimer.class);
    }

    @Override
    public SimpleTimer simpleTimer(final MetricID metricID) {
        return simpleTimer(metricID.getName(), metricID.getTagsAsArray());
    }

    @Override
    public SimpleTimer simpleTimer(final Metadata metadata) {
        return simpleTimer(metadata, NO_TAG);
    }

    @Override
    public SimpleTimer simpleTimer(final String name) {
        return simpleTimer(new MetricID(name));
    }

    @Override
    public SimpleTimer simpleTimer(final String name, final Tag... tags) {
        return simpleTimer(Metadata.builder().withName(name).withType(MetricType.SIMPLE_TIMER).build(), tags);
    }

    @Override
    public SimpleTimer simpleTimer(final Metadata metadata, final Tag... tags) {
        final MetricID metricID = new MetricID(metadata.getName(), tags);
        Holder<? extends Metric> holder = metrics.get(metricID);
        if (holder == null) {
            holder = new Holder<>(new SimpleTimerImpl(metadata.getUnit() == null ? MetricUnits.NONE : metadata.getUnit()), enforceType(metadata, MetricType.SIMPLE_TIMER), metricID);
            final Holder<? extends Metric> existing = metrics.putIfAbsent(metricID, holder);
            if (existing != null) {
                holder = existing;
            }
        }
        if (!SimpleTimer.class.isInstance(holder.metric)) {
            throw new IllegalArgumentException(holder.metric + " is not a timer");
        }
        return SimpleTimer.class.cast(holder.metric);
    }

    @Override
    public Timer getTimer(MetricID metricID) {
        return getMetric(metricID, Timer.class);
    }

    @Override
    public Timer timer(final String name) {
        return timer(Metadata.builder().withName(name).withType(MetricType.TIMER).build());
    }

    @Override
    public Timer timer(final String name, final Tag... tags) {
        return timer(Metadata.builder().withName(name).withType(MetricType.TIMER).build(), tags);
    }

    @Override
    public Timer timer(final MetricID metricID) {
        return timer(metricID.getName(), metricID.getTagsAsArray());
    }

    @Override
    public Timer timer(final Metadata metadata) {
        return timer(metadata, NO_TAG);
    }

    @Override
    public Timer timer(final Metadata metadata, final Tag... tags) {
        final MetricID metricID = new MetricID(metadata.getName(), tags);
        Holder<? extends Metric> holder = metrics.get(metricID);
        if (holder == null) {
            holder = new Holder<>(new TimerImpl(metadata.getUnit() == null ? MetricUnits.NONE : metadata.getUnit()), enforceType(metadata, MetricType.TIMER), metricID);
            final Holder<? extends Metric> existing = metrics.putIfAbsent(metricID, holder);
            if (existing != null) {
                holder = existing;
            }
        }
        if (!Timer.class.isInstance(holder.metric)) {
            throw new IllegalArgumentException(holder.metric + " is not a timer");
        }
        return Timer.class.cast(holder.metric);
    }

    @Override
    public Metric getMetric(final MetricID metricID) {
        final Holder<? extends Metric> holder = metrics.get(metricID);
        return holder == null ? null : holder.metric;
    }

    @Override
    public <T extends Metric> T getMetric(final MetricID metricID, final Class<T> asType) {
        try {
            return asType.cast(getMetric(metricID));
        } catch (ClassCastException e) {
            throw new IllegalArgumentException(metricID + " was not of expected type " + asType, e);
        }
    }

    @Override
    public boolean remove(final String name) {
        final AtomicBoolean done = new AtomicBoolean(false);
        removeMatching((metricID, metric) -> {
            final boolean equals = Objects.equals(metricID.getName(), name);
            if (equals) {
                done.set(true);
            }
            return equals;
        });
        return done.get();
    }

    @Override
    public boolean remove(final MetricID metricID) {
        return metrics.remove(metricID) != null;
    }

    @Override
    public void removeMatching(final MetricFilter filter) {
        metrics.entrySet().removeIf(it -> filter.matches(it.getKey(), it.getValue().metric));
    }

    @Override
    public SortedSet<String> getNames() {
        return metrics.keySet().stream().map(MetricID::getName).collect(toCollection(TreeSet::new));
    }

    @Override
    public SortedSet<MetricID> getMetricIDs() {
        return new TreeSet<>(metrics.keySet());
    }

    @Override
    public SortedMap<MetricID, Gauge> getGauges() {
        return getGauges(MetricFilter.ALL);
    }

    @Override
    public SortedMap<MetricID, Gauge> getGauges(final MetricFilter filter) {
        return filterByType(filter, Gauge.class);
    }

    @Override
    public SortedMap<MetricID, Counter> getCounters() {
        return getCounters(MetricFilter.ALL);
    }

    @Override
    public SortedMap<MetricID, Counter> getCounters(final MetricFilter filter) {
        return filterByType(filter, Counter.class);
    }

    @Override
    public SortedMap<MetricID, ConcurrentGauge> getConcurrentGauges() {
        return getConcurrentGauges(MetricFilter.ALL);
    }

    @Override
    public SortedMap<MetricID, ConcurrentGauge> getConcurrentGauges(final MetricFilter filter) {
        return filterByType(filter, ConcurrentGauge.class);
    }

    @Override
    public SortedMap<MetricID, Histogram> getHistograms() {
        return getHistograms(MetricFilter.ALL);
    }

    @Override
    public SortedMap<MetricID, Histogram> getHistograms(final MetricFilter filter) {
        return filterByType(filter, Histogram.class);
    }

    @Override
    public SortedMap<MetricID, Meter> getMeters() {
        return getMeters(MetricFilter.ALL);
    }

    @Override
    public SortedMap<MetricID, Meter> getMeters(final MetricFilter filter) {
        return filterByType(filter, Meter.class);
    }

    @Override
    public SortedMap<MetricID, Timer> getTimers() {
        return getTimers(MetricFilter.ALL);
    }

    @Override
    public SortedMap<MetricID, Timer> getTimers(final MetricFilter filter) {
        return filterByType(filter, Timer.class);
    }

    @Override
    public SortedMap<MetricID, SimpleTimer> getSimpleTimers() {
        return filterByType(MetricFilter.ALL, SimpleTimer.class);
    }

    @Override
    public SortedMap<MetricID, SimpleTimer> getSimpleTimers(final MetricFilter filter) {
        return filterByType(filter, SimpleTimer.class);
    }

    @Override
    public SortedMap<MetricID, Metric> getMetrics(final MetricFilter metricFilter) {
        return filterByType(metricFilter, Metric.class);
    }

    @Override
    public <T extends Metric> SortedMap<MetricID, T> getMetrics(Class<T> ofType, MetricFilter filter) {
        return (SortedMap<MetricID, T>) getMetrics(
                (metricID, metric) -> filter.matches(metricID, metric)
                        && ofType.isAssignableFrom(metric.getClass()));
    }

    @Override
    public Map<MetricID, Metric> getMetrics() {
        return metrics.entrySet().stream().collect(toMap(Map.Entry::getKey, e -> e.getValue().metric));
    }

    @Override
    public Metadata getMetadata(final String name) {
        final Holder<? extends Metric> holder = metrics.get(new MetricID(name));
        return holder == null ? null : holder.metadata;
    }

    @Override
    public Map<String, Metadata> getMetadata() {
        return metrics.entrySet().stream()
                .collect(toMap(e -> e.getKey().getName(), e -> e.getValue().metadata, (a, b) -> a));
    }

    @Override
    public Type getType() {
        return type;
    }

    private Metadata enforceType(final Metadata metadata, final MetricType type) {
        if (metadata.getTypeRaw() == null || !type.equals(metadata.getTypeRaw())) {
            return Metadata.builder(metadata).withType(type).build();
        }
        return metadata;
    }

    private <T extends Metric> SortedMap<MetricID, T> filterByType(final MetricFilter filter, final Class<T> type) {
        return metrics.entrySet().stream()
                .filter(it -> type.isInstance(it.getValue().metric))
                .filter(it -> filter.matches(it.getKey(), it.getValue().metric))
                .collect(toMap(Map.Entry::getKey, e -> type.cast(e.getValue().metric), (a, b) -> {
                    throw new IllegalArgumentException("can't merge metrics"); // impossible
                }, TreeMap::new));
    }

    private static final class Holder<T extends Metric> {
        private final T metric;
        private final Metadata metadata;
        private final MetricID metricID;

        private Holder(final T metric, final Metadata metadata, final MetricID metricID) {
            this.metric = metric;
            this.metadata = Metadata.builder(metadata).build();
            this.metricID = metricID;
        }
    }
}
