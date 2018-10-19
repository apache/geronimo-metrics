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

import static java.util.stream.Collectors.toMap;

import java.util.Map;
import java.util.SortedMap;
import java.util.SortedSet;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

import org.eclipse.microprofile.metrics.Counter;
import org.eclipse.microprofile.metrics.Gauge;
import org.eclipse.microprofile.metrics.Histogram;
import org.eclipse.microprofile.metrics.Metadata;
import org.eclipse.microprofile.metrics.Meter;
import org.eclipse.microprofile.metrics.Metric;
import org.eclipse.microprofile.metrics.MetricFilter;
import org.eclipse.microprofile.metrics.MetricRegistry;
import org.eclipse.microprofile.metrics.MetricType;
import org.eclipse.microprofile.metrics.Timer;

public class RegistryImpl extends MetricRegistry {
    private final ConcurrentMap<String, Holder<? extends Metric>> metrics = new ConcurrentHashMap<>();

    @Override
    public <T extends Metric> T register(final Metadata metadata, final T metric) throws IllegalArgumentException {
        final Holder<? extends Metric> holder = metrics.putIfAbsent(metadata.getName(), new Holder<>(metric, metadata));
        if (holder != null && !metadata.isReusable() && !holder.metadata.isReusable()) {
            throw new IllegalArgumentException("'" + metadata.getName() + "' metric already exists and is not reusable");
        }
        return metric;
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
        return register(new Metadata(name, type), metric);
    }

    @Override
    public <T extends Metric> T register(final String name, final T metric, final Metadata metadata) throws IllegalArgumentException {
        return register(metadata, metric);
    }

    @Override
    public Counter counter(final Metadata metadata) {
        Holder<? extends Metric> holder = metrics.get(metadata.getName());
        if (holder == null) {
            holder = new Holder<>(new CounterImpl(metadata.getUnit()), metadata);
            final Holder<? extends Metric> existing = metrics.putIfAbsent(metadata.getName(), holder);
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
    public Histogram histogram(final Metadata metadata) {
        Holder<? extends Metric> holder = metrics.get(metadata.getName());
        if (holder == null) {
            holder = new Holder<>(new HistogramImpl(metadata.getUnit()), metadata);
            final Holder<? extends Metric> existing = metrics.putIfAbsent(metadata.getName(), holder);
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
        Holder<? extends Metric> holder = metrics.get(metadata.getName());
        if (holder == null) {
            holder = new Holder<>(new MeterImpl(metadata.getUnit()), metadata);
            final Holder<? extends Metric> existing = metrics.putIfAbsent(metadata.getName(), holder);
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
        Holder<? extends Metric> holder = metrics.get(metadata.getName());
        if (holder == null) {
            holder = new Holder<>(new TimerImpl(metadata.getUnit()), metadata);
            final Holder<? extends Metric> existing = metrics.putIfAbsent(metadata.getName(), holder);
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
        return counter(new Metadata(name, MetricType.COUNTER));
    }

    @Override
    public Histogram histogram(final String name) {
        return histogram(new Metadata(name, MetricType.HISTOGRAM));
    }

    @Override
    public Meter meter(final String name) {
        return meter(new Metadata(name, MetricType.METERED));
    }

    @Override
    public Timer timer(final String name) {
        return timer(new Metadata(name, MetricType.TIMER));
    }

    @Override
    public boolean remove(final String name) {
        return metrics.remove(name) != null;
    }

    @Override
    public void removeMatching(final MetricFilter filter) {
        metrics.entrySet().removeIf(it -> filter.matches(it.getKey(), it.getValue().metric));
    }

    @Override
    public SortedSet<String> getNames() {
        return new TreeSet<>(metrics.keySet());
    }

    @Override
    public SortedMap<String, Gauge> getGauges() {
        return getGauges(MetricFilter.ALL);
    }

    @Override
    public SortedMap<String, Gauge> getGauges(final MetricFilter filter) {
        return filterByType(filter, Gauge.class);
    }

    @Override
    public SortedMap<String, Counter> getCounters() {
        return getCounters(MetricFilter.ALL);
    }

    @Override
    public SortedMap<String, Counter> getCounters(final MetricFilter filter) {
        return filterByType(filter, Counter.class);
    }

    @Override
    public SortedMap<String, Histogram> getHistograms() {
        return getHistograms(MetricFilter.ALL);
    }

    @Override
    public SortedMap<String, Histogram> getHistograms(final MetricFilter filter) {
        return filterByType(filter, Histogram.class);
    }

    @Override
    public SortedMap<String, Meter> getMeters() {
        return getMeters(MetricFilter.ALL);
    }

    @Override
    public SortedMap<String, Meter> getMeters(final MetricFilter filter) {
        return filterByType(filter, Meter.class);
    }

    @Override
    public SortedMap<String, Timer> getTimers() {
        return getTimers(MetricFilter.ALL);
    }

    @Override
    public SortedMap<String, Timer> getTimers(final MetricFilter filter) {
        return filterByType(filter, Timer.class);
    }

    @Override
    public Map<String, Metric> getMetrics() {
        return metrics.entrySet().stream().collect(toMap(Map.Entry::getKey, e -> e.getValue().metric));
    }

    @Override
    public Map<String, Metadata> getMetadata() {
        return metrics.entrySet().stream().collect(toMap(Map.Entry::getKey, e -> e.getValue().metadata));
    }

    private <T extends Metric> SortedMap<String, T> filterByType(final MetricFilter filter, final Class<T> type) {
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
            this.metadata = new Metadata(metadata.getName(), metadata.getDisplayName(), metadata.getDescription(), metadata.getTypeRaw(), metadata.getUnit());
            this.metadata.setReusable(metadata.isReusable());
            this.metadata.setTags(metadata.getTags());
        }
    }
}
