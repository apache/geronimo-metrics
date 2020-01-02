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
package org.apache.geronimo.microprofile.metrics.common.prometheus;

import static java.lang.Math.pow;
import static java.util.Locale.ROOT;
import static java.util.Optional.of;
import static java.util.stream.Collectors.joining;
import static java.util.stream.Collectors.toList;

import java.io.IOException;
import java.io.InputStream;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.stream.Stream;

import org.eclipse.microprofile.metrics.ConcurrentGauge;
import org.eclipse.microprofile.metrics.Counter;
import org.eclipse.microprofile.metrics.Gauge;
import org.eclipse.microprofile.metrics.Histogram;
import org.eclipse.microprofile.metrics.Metadata;
import org.eclipse.microprofile.metrics.Meter;
import org.eclipse.microprofile.metrics.Metric;
import org.eclipse.microprofile.metrics.MetricRegistry;
import org.eclipse.microprofile.metrics.MetricUnits;
import org.eclipse.microprofile.metrics.Snapshot;
import org.eclipse.microprofile.metrics.Tag;
import org.eclipse.microprofile.metrics.Timer;

// this is so weird to have this format built-in but not mainstream ones,
// todo: pby make it dropped from the spec
// note: this is a simplified serialization flavor and it can need some more love
// todo: cache all the keys, can easily be done decorating the registry and enriching metadata (ExtendedMetadata)
public class PrometheusFormatter {
    private final Map<String, String> keyMapping = new HashMap<>();
    private Predicate<String> prefixFilter = null;

    public PrometheusFormatter enableOverriding(final Properties properties) {
        properties.stringPropertyNames().forEach(k -> keyMapping.put(k, properties.getProperty(k)));
        afterOverride();
        return this;
    }

    public PrometheusFormatter enableOverriding() {
        try (final InputStream source = Thread.currentThread().getContextClassLoader()
                                              .getResourceAsStream("META-INF/geronimo-metrics/prometheus-mapping.properties")) {
            if (source != null) {
                final Properties properties = new Properties();
                properties.load(source);
                enableOverriding(properties);
            }
        } catch (final IOException e) {
            // no-op
        }
        System.getProperties().stringPropertyNames().stream()
              .filter(it -> it.startsWith("geronimo.metrics.prometheus.mapping."))
              .forEach(k -> keyMapping.put(k.substring("geronimo.metrics.prometheus.mapping.".length()), System.getProperty(k)));
        afterOverride();
        return this;
    }

    private void afterOverride() {
        final String prefix = keyMapping.get("geronimo.metrics.filter.prefix");
        if (prefix == null) {
            prefixFilter = null;
        } else {
            final List<String> prefixes = Stream.of(prefix.split(","))
                                               .map(String::trim)
                                               .filter(it -> !it.isEmpty())
                                               .collect(toList());
            final Predicate<String> directPredicate = name -> prefixes.stream().anyMatch(name::startsWith);
            prefixFilter = name -> directPredicate.test(name) || directPredicate.test(keyMapping.getOrDefault(name, name));
        }
    }

    public StringBuilder toText(final MetricRegistry registry,
                                final String registryKey,
                                final Map<String, Metric> entries) {
        final Map<String, Metadata> metadatas = registry.getMetadata();
        return entries.entrySet().stream()
                .map(it -> {
                    String key = it.getKey();
                    final int tagSep = key.indexOf(';');
                    if (tagSep > 0) {
                        key = key.substring(0, tagSep);
                    }
                    final Metadata metadata = metadatas.get(key);
                    return new Entry(metadata, registryKey + ':' + toPrometheusKey(metadata), it.getValue());
                })
                .filter(it -> prefixFilter == null || prefixFilter.test(it.prometheusKey))
                .map(entry -> {
                    switch (entry.metadata.getTypeRaw()) {
                        case COUNTER: {
                            final String key = toPrometheusKey(entry.metadata);
                            return new StringBuilder()
                                    .append(value(registryKey, key, Counter.class.cast(entry.metric).getCount(), entry.metadata));
                        }
                        case CONCURRENT_GAUGE: {
                            final String key = toPrometheusKey(entry.metadata);
                            return new StringBuilder()
                                    .append(value(registryKey, key, ConcurrentGauge.class.cast(entry.metric).getCount(), entry.metadata));
                        }
                        case GAUGE: {
                            final Object val = Gauge.class.cast(entry.metric).getValue();
                            if (Number.class.isInstance(val)) {
                                final String key = toPrometheusKey(entry.metadata);
                                return new StringBuilder()
                                        .append(value(registryKey, key, Number.class.cast(val).doubleValue(), entry.metadata));
                            }
                            return new StringBuilder();
                        }
                        case METERED: {
                            final String keyBase = toPrometheus(entry.metadata);
                            final String key = keyBase + toUnitSuffix(entry.metadata);
                            final Meter meter = Meter.class.cast(entry.metric);
                            return new StringBuilder()
                                    .append(value(registryKey, key + "_count", meter.getCount(), entry.metadata))
                                    .append(value(registryKey, keyBase + "_rate_per_second", meter.getMeanRate(), entry.metadata))
                                    .append(value(registryKey, keyBase + "_one_min_rate_per_second", meter.getOneMinuteRate(), entry.metadata))
                                    .append(value(registryKey, keyBase + "_five_min_rate_per_second", meter.getFiveMinuteRate(), entry.metadata))
                                    .append(value(registryKey, keyBase + "_fifteen_min_rate_per_second", meter.getFifteenMinuteRate(), entry.metadata));
                        }
                        case TIMER: {
                            final String keyBase = toPrometheus(entry.metadata);
                            final String keyUnit = toUnitSuffix(entry.metadata);
                            final Timer timer = Timer.class.cast(entry.metric);
                            return new StringBuilder()
                                    .append(type(registryKey, keyBase + keyUnit + " summary", entry.metadata))
                                    .append(value(registryKey, keyBase + keyUnit + "_count", timer.getCount(), entry.metadata))
                                    .append(value(registryKey, keyBase + "_rate_per_second", timer.getMeanRate(), entry.metadata))
                                    .append(value(registryKey, keyBase + "_one_min_rate_per_second", timer.getOneMinuteRate(), entry.metadata))
                                    .append(value(registryKey, keyBase + "_five_min_rate_per_second", timer.getFiveMinuteRate(), entry.metadata))
                                    .append(value(registryKey, keyBase + "_fifteen_min_rate_per_second", timer.getFifteenMinuteRate(), entry.metadata))
                                    .append(toPrometheus(registryKey, keyBase, keyUnit, timer.getSnapshot(), entry.metadata));
                        }
                        case HISTOGRAM:
                            final String keyBase = toPrometheus(entry.metadata);
                            final String keyUnit = toUnitSuffix(entry.metadata);
                            final Histogram histogram = Histogram.class.cast(entry.metric);
                            return new StringBuilder()
                                    .append(type(registryKey, keyBase + keyUnit + " summary", entry.metadata))
                                    .append(value(registryKey, keyBase + keyUnit + "_count", histogram.getCount(), entry.metadata))
                                    .append(toPrometheus(registryKey, keyBase, keyUnit, histogram.getSnapshot(), entry.metadata));
                        default:
                            return new StringBuilder();
                    }
                })
                .collect(StringBuilder::new, StringBuilder::append, StringBuilder::append);
    }

    private StringBuilder toPrometheus(final String registryKey, final String keyBase, final String keyUnit,
                                       final Snapshot snapshot, final Metadata metadata, final Tag... tags) {
        final Function<Stream<Tag>, Tag[]> metaFactory = newTags ->
                Stream.concat(newTags, Stream.of(tags)).toArray(Tag[]::new);
        final String completeKey = keyBase + keyUnit;
        return new StringBuilder()
                .append(value(registryKey, keyBase + "_min" + keyUnit, snapshot.getMin(), metadata))
                .append(value(registryKey, keyBase + "_max" + keyUnit, snapshot.getMax(), metadata))
                .append(value(registryKey, keyBase + "_mean" + keyUnit, snapshot.getMean(), metadata))
                .append(value(registryKey, keyBase + "_stddev" + keyUnit, snapshot.getStdDev(), metadata))
                .append(value(registryKey, completeKey, snapshot.getMedian(), metadata,
                        metaFactory.apply(Stream.of(new Tag("quantile", "0.5")))))
                .append(value(registryKey, completeKey, snapshot.get75thPercentile(), metadata,
                        metaFactory.apply(Stream.of(new Tag("quantile", "0.75")))))
                .append(value(registryKey, completeKey, snapshot.get95thPercentile(), metadata,
                        metaFactory.apply(Stream.of(new Tag("quantile", "0.95")))))
                .append(value(registryKey, completeKey, snapshot.get98thPercentile(), metadata,
                        metaFactory.apply(Stream.of(new Tag("quantile", "0.98")))))
                .append(value(registryKey, completeKey, snapshot.get99thPercentile(), metadata,
                        metaFactory.apply(Stream.of(new Tag("quantile", "0.99")))))
                .append(value(registryKey, completeKey, snapshot.get999thPercentile(), metadata,
                        metaFactory.apply(Stream.of(new Tag("quantile", "0.999")))));
    }

    private String toPrometheusKey(final Metadata metadata) {
        return toPrometheus(metadata) + toUnitSuffix(metadata);
    }

    private String toUnitSuffix(final Metadata metadata) {
        return metadata.getUnit().orElse("none").equalsIgnoreCase("none") ?
                "" : ("_" + toPrometheusUnit(metadata.getUnit().orElse("")));
    }

    private StringBuilder value(final String registryKey, final String key, final double value,
                                final Metadata metadata, final Tag... tags) {
        final String builtKey = registryKey + ':' + key;
        return new StringBuilder()
                .append(type(registryKey, key, metadata))
                .append(keyMapping.getOrDefault(builtKey, builtKey))
                .append(of(tags)
                        .filter(t -> t.length > 0)
                        .map(t -> Stream.of(tags)
                                .map(e -> e.getTagName() + "=\"" + e.getTagValue() + "\"")
                                .collect(joining(",", "{", "}")))
                        .orElse(""))
                .append(' ').append(toPrometheusValue(metadata.getUnit().orElse(""), value)).append("\n");
    }

    private StringBuilder type(final String registryKey, final String key, final Metadata metadata) {
        final String builtKey = registryKey + ':' + key;
        final StringBuilder builder = new StringBuilder()
                .append("# TYPE ").append(keyMapping.getOrDefault(builtKey, builtKey));
        if (metadata != null) {
            builder.append(' ').append(metadata.getType());
        }
        return builder.append("\n");
    }

    private String toPrometheusUnit(final String unit) {
        if (unit == null) {
            return null;
        }
        switch (unit) {
            case MetricUnits.BITS:
            case MetricUnits.KILOBITS:
            case MetricUnits.MEGABITS:
            case MetricUnits.GIGABITS:
            case MetricUnits.KIBIBITS:
            case MetricUnits.MEBIBITS:
            case MetricUnits.GIBIBITS:
            case MetricUnits.BYTES:
            case MetricUnits.KILOBYTES:
            case MetricUnits.MEGABYTES:
            case MetricUnits.GIGABYTES:
                return "bytes";

            case MetricUnits.NANOSECONDS:
            case MetricUnits.MICROSECONDS:
            case MetricUnits.MILLISECONDS:
            case MetricUnits.SECONDS:
            case MetricUnits.MINUTES:
            case MetricUnits.HOURS:
            case MetricUnits.DAYS:
                return "seconds";

            default:
                return unit;
        }
    }

    private double toPrometheusValue(final String unit, final double value) {
        if (unit == null) {
            return value;
        }
        switch (unit) {
            case MetricUnits.BITS:
                return value / 8;
            case MetricUnits.KILOBITS:
                return value * 1000 / 8;
            case MetricUnits.MEGABITS:
                return value * pow(1000, 2) / 8;
            case MetricUnits.GIGABITS:
                return value * pow(1000, 3) / 8;
            case MetricUnits.KIBIBITS:
                return value * 128;
            case MetricUnits.MEBIBITS:
                return value * pow(1024, 2);
            case MetricUnits.GIBIBITS:
                return value * pow(1024, 3);
            case MetricUnits.BYTES:
                return value;
            case MetricUnits.KILOBYTES:
                return value * 1000;
            case MetricUnits.MEGABYTES:
                return value * pow(1000, 2);
            case MetricUnits.GIGABYTES:
                return value * pow(1000, 3);
            case MetricUnits.NANOSECONDS:
                return value;
            case MetricUnits.MICROSECONDS:
                return value / 1000;
            case MetricUnits.MILLISECONDS:
                return value / pow(1000, 2);
            case MetricUnits.SECONDS:
                return value / pow(1000, 3);
            case MetricUnits.MINUTES:
                return value * 60 / pow(1000, 3);
            case MetricUnits.HOURS:
                return value * pow(60, 2) / pow(1000, 3);
            case MetricUnits.DAYS:
                return value * pow(60, 2) * 24 / pow(1000, 3);
            default:
                return value;
        }
    }

    private String toPrometheus(final Metadata id) {
        return id.getName()
                .replaceAll("[^\\w]+", "_")
                .replaceAll("(.)(\\p{Upper})", "$1_$2")
                .replace("__", "_")
                .replace(":_", ":")
                .toLowerCase(ROOT);
    }

    private static class Entry {
        private final Metadata metadata;
        private final String prometheusKey;
        private final Metric metric;

        private Entry(final Metadata metadata, final String prometheusKey, final Metric metric) {
            this.metadata = metadata;
            this.prometheusKey = prometheusKey;
            this.metric = metric;
        }
    }
}
