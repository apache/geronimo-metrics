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

import static java.util.stream.Collectors.toCollection;
import static java.util.stream.Collectors.toMap;

import java.util.Map;
import java.util.SortedMap;
import java.util.SortedSet;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

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
import org.eclipse.microprofile.metrics.Tag;
import org.eclipse.microprofile.metrics.Timer;

public class RegistryImpl extends MetricRegistry {
    private static final Tag[] NO_TAG = new Tag[0];

    private final ConcurrentMap<MetricID, Holder<? extends Metric>> metrics = new ConcurrentHashMap<>();

    @Override
    public <T extends Metric> T register(final Metadata metadata, final T metric) throws IllegalArgumentException {
        final Holder<? extends Metric> holder = metrics.putIfAbsent(
                new MetricID(metadata.getName()), new Holder<>(metric, metadata));
        if (holder != null && !metadata.isReusable() && !holder.metadata.isReusable()) {
            throw new IllegalArgumentException("'" + metadata.getName() + "' metric already exists and is not reusable");
        }
        return metric;
    }

    @Override
    public <T extends Metric> T register(final Metadata metadata, final T metric, final Tag... tags) throws IllegalArgumentException {
        return register(Metadata.builder(metadata).build(), metric);
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
        } else {
            type = MetricType.INVALID;
        }
        return register(Metadata.builder().withName(name).withType(type).build(), metric);
    }

    @Override
    public Counter counter(final Metadata metadata) {
        return counter(metadata, NO_TAG);
    }

    @Override
    public Counter counter(final Metadata metadata, final Tag... tags) {
        Holder<? extends Metric> holder = metrics.get(new MetricID(metadata.getName(), tags));
        if (holder == null) {
            holder = new Holder<>(new CounterImpl(metadata.getUnit().orElse("")), metadata);
            final Holder<? extends Metric> existing = metrics.putIfAbsent(new MetricID(metadata.getName(), tags), holder);
            if (existing != null) {
                holder = existing;
            }
        } else if (!metadata.isReusable()) {
            throw new IllegalArgumentException("Metric " + metadata.getName() + " already exists and is not set as reusable");
        } else if (!holder.metadata.isReusable()) {
            throw new IllegalArgumentException("Metric " + metadata.getName() + " already exists and was not set as reusable");
        }
        if (!Counter.class.isInstance(holder.metric)) {
            throw new IllegalArgumentException(holder.metric + " is not a counter");
        }
        return Counter.class.cast(holder.metric);
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
    public ConcurrentGauge concurrentGauge(final Metadata metadata) {
        return concurrentGauge(metadata, NO_TAG);
    }

    @Override
    public ConcurrentGauge concurrentGauge(final Metadata metadata, final Tag... tags) {
        Holder<? extends Metric> holder = metrics.get(new MetricID(metadata.getName(), tags));
        if (holder == null) {
            holder = new Holder<>(new ConcurrentGaugeImpl(metadata.getUnit().orElse("")), metadata);
            final Holder<? extends Metric> existing = metrics.putIfAbsent(new MetricID(metadata.getName(), tags), holder);
            if (existing != null) {
                holder = existing;
            }
        } else if (!metadata.isReusable()) {
            throw new IllegalArgumentException("Metric " + metadata.getName() + " already exists and is not set as reusable");
        } else if (!holder.metadata.isReusable()) {
            throw new IllegalArgumentException("Metric " + metadata.getName() + " already exists and was not set as reusable");
        }
        if (!ConcurrentGauge.class.isInstance(holder.metric)) {
            throw new IllegalArgumentException(holder.metric + " is not a counter");
        }
        return ConcurrentGauge.class.cast(holder.metric);
    }

    @Override
    public Histogram histogram(final Metadata metadata) {
        return histogram(metadata, NO_TAG);
    }

    @Override
    public Histogram histogram(final Metadata metadata, final Tag... tags) {

        Holder<? extends Metric> holder = metrics.get(new MetricID(metadata.getName(), tags));
        if (holder == null) {
            holder = new Holder<>(new HistogramImpl(metadata.getUnit().orElse("")), metadata);
            final Holder<? extends Metric> existing = metrics.putIfAbsent(new MetricID(metadata.getName(), tags), holder);
            if (existing != null) {
                holder = existing;
            }
        } else if (!metadata.isReusable()) {
            throw new IllegalArgumentException("Metric " + metadata.getName() + " already exists and is not set as reusable");
        } else if (!holder.metadata.isReusable()) {
            throw new IllegalArgumentException("Metric " + metadata.getName() + " already exists and was not set as reusable");
        }
        if (!Histogram.class.isInstance(holder.metric)) {
            throw new IllegalArgumentException(holder.metric + " is not a histogram");
        }
        return Histogram.class.cast(holder.metric);
    }

    @Override
    public Meter meter(final Metadata metadata) {
        return meter(metadata, NO_TAG);
    }

    @Override
    public Meter meter(final Metadata metadata, final Tag... tags) {
        Holder<? extends Metric> holder = metrics.get(new MetricID(metadata.getName(), tags));
        if (holder == null) {
            holder = new Holder<>(new MeterImpl(metadata.getUnit().orElse("")), metadata);
            final Holder<? extends Metric> existing = metrics.putIfAbsent(new MetricID(metadata.getName(), tags), holder);
            if (existing != null) {
                holder = existing;
            }
        } else if (!metadata.isReusable()) {
            throw new IllegalArgumentException("Metric " + metadata.getName() + " already exists and is not set as reusable");
        } else if (!holder.metadata.isReusable()) {
            throw new IllegalArgumentException("Metric " + metadata.getName() + " already exists and was not set as reusable");
        }
        if (!Meter.class.isInstance(holder.metric)) {
            throw new IllegalArgumentException(holder.metric + " is not a meter");
        }
        return Meter.class.cast(holder.metric);
    }

    @Override
    public Timer timer(final Metadata metadata) {
        return timer(metadata, NO_TAG);
    }

    @Override
    public Timer timer(final Metadata metadata, final Tag... tags) {
        Holder<? extends Metric> holder = metrics.get(new MetricID(metadata.getName(), tags));
        if (holder == null) {
            holder = new Holder<>(new TimerImpl(metadata.getUnit().orElse("")), metadata);
            final Holder<? extends Metric> existing = metrics.putIfAbsent(new MetricID(metadata.getName(), tags), holder);
            if (existing != null) {
                holder = existing;
            }
        } else if (!metadata.isReusable()) {
            throw new IllegalArgumentException("Metric " + metadata.getName() + " already exists and is not set as reusable");
        } else if (!holder.metadata.isReusable()) {
            throw new IllegalArgumentException("Metric " + metadata.getName() + " already exists and was not set as reusable");
        }
        if (!Timer.class.isInstance(holder.metric)) {
            throw new IllegalArgumentException(holder.metric + " is not a timer");
        }
        return Timer.class.cast(holder.metric);
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
    public Histogram histogram(final String name) {
        return histogram(Metadata.builder().withName(name).withType(MetricType.HISTOGRAM).build());
    }

    @Override
    public Histogram histogram(final String name, final Tag... tags) {
        return histogram(Metadata.builder().withName(name).withType(MetricType.HISTOGRAM).build());
    }

    @Override
    public Meter meter(final String name) {
        return meter(Metadata.builder().withName(name).withType(MetricType.METERED).build());
    }

    @Override
    public Meter meter(final String name, final Tag... tags) {
        return meter(Metadata.builder().withName(name).withType(MetricType.METERED).build());
    }

    @Override
    public Timer timer(final String name) {
        return timer(Metadata.builder().withName(name).withType(MetricType.TIMER).build());
    }

    @Override
    public Timer timer(final String name, final Tag... tags) {
        return timer(Metadata.builder().withName(name).withType(MetricType.TIMER).build());
    }

    @Override
    public boolean remove(final String name) {
        return remove(new MetricID(name));
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
    public Map<MetricID, Metric> getMetrics() {
        return metrics.entrySet().stream().collect(toMap(Map.Entry::getKey, e -> e.getValue().metric));
    }

    @Override
    public Map<String, Metadata> getMetadata() {
        return metrics.entrySet().stream().collect(toMap(e -> e.getKey().getName(), e -> e.getValue().metadata));
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

        private Holder(final T metric, final Metadata metadata) {
            this.metric = metric;
            this.metadata = Metadata.builder(metadata).build();
        }
    }
}
