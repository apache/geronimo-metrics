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
import static java.util.Collections.singletonMap;
import static java.util.Locale.ROOT;
import static java.util.Optional.of;
import static java.util.stream.Collectors.joining;

import java.util.Map;
import java.util.function.Function;
import java.util.stream.Stream;

import org.eclipse.microprofile.metrics.Counter;
import org.eclipse.microprofile.metrics.Gauge;
import org.eclipse.microprofile.metrics.Histogram;
import org.eclipse.microprofile.metrics.Metadata;
import org.eclipse.microprofile.metrics.Meter;
import org.eclipse.microprofile.metrics.Metric;
import org.eclipse.microprofile.metrics.MetricRegistry;
import org.eclipse.microprofile.metrics.MetricUnits;
import org.eclipse.microprofile.metrics.Snapshot;
import org.eclipse.microprofile.metrics.Timer;

// this is so weird to have this format built-in but not mainstream ones,
// todo: pby make it dropped from the spec
// note: this is a simplified serialization flavor and it can need some more love
public class PrometheusFormatter {
    public StringBuilder toText(final MetricRegistry registry,
                                final String registryKey,
                                final Map<String, Metric> entries) {
        final Map<String, Metadata> metadatas = registry.getMetadata();
        return entries.entrySet().stream()
                .map(entry -> {
                    final Metadata metadata = metadatas.get(entry.getKey());
                    final Metric value = entry.getValue();
                    switch (metadata.getTypeRaw()) {
                        case COUNTER: {
                            final String key = toPrometheusKey(metadata);
                            return new StringBuilder()
                                    .append(value(registryKey, key, Counter.class.cast(value).getCount(), metadata));
                        }
                        case GAUGE: {
                            final Object val = Gauge.class.cast(value).getValue();
                            if (Number.class.isInstance(val)) {
                                final String key = toPrometheusKey(metadata);
                                return new StringBuilder()
                                        .append(value(registryKey, key, Number.class.cast(val).doubleValue(), metadata));
                            }
                            return new StringBuilder();
                        }
                        case METERED: {
                            final String keyBase = toPrometheus(metadata);
                            final String key = keyBase + toUnitSuffix(metadata);
                            final Meter meter = Meter.class.cast(value);
                            return new StringBuilder()
                                    .append(value(registryKey, key + "_count", meter.getCount(), metadata))
                                    .append(value(registryKey, keyBase + "_rate_per_second", meter.getMeanRate(), metadata))
                                    .append(value(registryKey, keyBase + "_one_min_rate_per_second", meter.getOneMinuteRate(), metadata))
                                    .append(value(registryKey, keyBase + "_five_min_rate_per_second", meter.getFiveMinuteRate(), metadata))
                                    .append(value(registryKey, keyBase + "_fifteen_min_rate_per_second", meter.getFifteenMinuteRate(), metadata));
                        }
                        case TIMER: {
                            final String keyBase = toPrometheus(metadata);
                            final String keyUnit = toUnitSuffix(metadata);
                            final Timer timer = Timer.class.cast(value);
                            return new StringBuilder()
                                    .append(type(registryKey, keyBase + keyUnit + " summary", metadata))
                                    .append(value(registryKey, keyBase + keyUnit + "_count", timer.getCount(), metadata))
                                    .append(value(registryKey, keyBase + "_rate_per_second", timer.getMeanRate(), metadata))
                                    .append(value(registryKey, keyBase + "_one_min_rate_per_second", timer.getOneMinuteRate(), metadata))
                                    .append(value(registryKey, keyBase + "_five_min_rate_per_second", timer.getFiveMinuteRate(), metadata))
                                    .append(value(registryKey, keyBase + "_fifteen_min_rate_per_second", timer.getFifteenMinuteRate(), metadata))
                                    .append(toPrometheus(registryKey, keyBase, keyUnit, timer.getSnapshot(), metadata));
                        }
                        case HISTOGRAM:
                            final String keyBase = toPrometheus(metadata);
                            final String keyUnit = toUnitSuffix(metadata);
                            final Histogram histogram = Histogram.class.cast(value);
                            return new StringBuilder()
                                    .append(type(registryKey, keyBase + keyUnit + " summary", metadata))
                                    .append(value(registryKey, keyBase + keyUnit + "_count", histogram.getCount(), metadata))
                                    .append(toPrometheus(registryKey, keyBase, keyUnit, histogram.getSnapshot(), metadata));
                        default:
                            return new StringBuilder();
                    }
                })
                .collect(StringBuilder::new, StringBuilder::append, StringBuilder::append);
    }

    private StringBuilder toPrometheus(final String registryKey, final String keyBase, final String keyUnit, final Snapshot snapshot, final Metadata metadata) {
        final Function<Map<String, String>, Metadata> metaFactory = tag -> new Metadata(metadata.getName(), metadata.getDisplayName(), metadata.getDescription(), metadata.getTypeRaw(), metadata.getUnit(),
                Stream.concat(metadata.getTags().entrySet().stream(), tag.entrySet().stream())
                        .map(e -> e.getKey() + '=' + e.getValue())
                        .collect(joining(",")));
        final String completeKey = keyBase + keyUnit;
        return new StringBuilder()
                .append(value(registryKey, keyBase + "_min" + keyUnit, snapshot.getMin(), metadata))
                .append(value(registryKey, keyBase + "_max" + keyUnit, snapshot.getMax(), metadata))
                .append(value(registryKey, keyBase + "_mean" + keyUnit, snapshot.getMean(), metadata))
                .append(value(registryKey, keyBase + "_stddev" + keyUnit, snapshot.getStdDev(), metadata))
                .append(value(registryKey, completeKey, snapshot.getMedian(), metaFactory.apply(singletonMap("quantile", "0.5"))))
                .append(value(registryKey, completeKey, snapshot.get75thPercentile(), metaFactory.apply(singletonMap("quantile", "0.75"))))
                .append(value(registryKey, completeKey, snapshot.get95thPercentile(), metaFactory.apply(singletonMap("quantile", "0.95"))))
                .append(value(registryKey, completeKey, snapshot.get98thPercentile(), metaFactory.apply(singletonMap("quantile", "0.98"))))
                .append(value(registryKey, completeKey, snapshot.get99thPercentile(), metaFactory.apply(singletonMap("quantile", "0.99"))))
                .append(value(registryKey, completeKey, snapshot.get999thPercentile(), metaFactory.apply(singletonMap("quantile", "0.999"))));
    }

    private String toPrometheusKey(final Metadata metadata) {
        return toPrometheus(metadata) + toUnitSuffix(metadata);
    }

    private String toUnitSuffix(final Metadata metadata) {
        return metadata.getUnit().equalsIgnoreCase("none") ?
                "" : ("_" + toPrometheusUnit(metadata.getUnit()));
    }

    private StringBuilder value(final String registryKey, final String key, final double value,
                                final Metadata metadata) {
        return new StringBuilder()
                .append(type(registryKey, key, metadata))
                .append(registryKey).append(':').append(key)
                .append(of(metadata.getTags())
                        .filter(t -> !t.isEmpty())
                        .map(t -> t.entrySet().stream()
                                .map(e -> e.getKey() + "=\"" + e.getValue() + "\"")
                                .collect(joining(",", "{", "}")))
                        .orElse(""))
                .append(' ').append(toPrometheusValue(metadata.getUnit(), value)).append("\n");
    }

    private StringBuilder type(final String registryKey, final String key, final Metadata metadata) {
        final StringBuilder builder = new StringBuilder()
                .append("# TYPE ").append(registryKey).append(':').append(key);
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

    private String toPrometheus(final Metadata metadata) {
        return metadata.getName()
                .replaceAll("[^\\w]+", "_")
                .replaceAll("(.)(\\p{Upper})", "$1_$2")
                .replace("__", "_")
                .replace(":_", ":")
                .toLowerCase(ROOT);
    }
}
