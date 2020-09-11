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
package org.apache.geronimo.microprofile.metrics.common.jaxrs;

import org.apache.geronimo.microprofile.metrics.common.RegistryImpl;
import org.apache.geronimo.microprofile.metrics.common.prometheus.PrometheusFormatter;
import org.eclipse.microprofile.metrics.ConcurrentGauge;
import org.eclipse.microprofile.metrics.Counter;
import org.eclipse.microprofile.metrics.Gauge;
import org.eclipse.microprofile.metrics.Histogram;
import org.eclipse.microprofile.metrics.Metadata;
import org.eclipse.microprofile.metrics.Meter;
import org.eclipse.microprofile.metrics.Metered;
import org.eclipse.microprofile.metrics.Metric;
import org.eclipse.microprofile.metrics.MetricID;
import org.eclipse.microprofile.metrics.MetricRegistry;
import org.eclipse.microprofile.metrics.SimpleTimer;
import org.eclipse.microprofile.metrics.Snapshot;
import org.eclipse.microprofile.metrics.Tag;
import org.eclipse.microprofile.metrics.Timer;

import javax.json.JsonValue;
import javax.ws.rs.GET;
import javax.ws.rs.OPTIONS;
import javax.ws.rs.Path;
import javax.ws.rs.PathParam;
import javax.ws.rs.Produces;
import javax.ws.rs.WebApplicationException;
import javax.ws.rs.core.Context;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;
import javax.ws.rs.core.SecurityContext;
import javax.ws.rs.core.UriInfo;
import java.time.Duration;
import java.util.Collections;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.function.Function;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static java.util.Collections.emptyMap;
import static java.util.Collections.singletonMap;
import static java.util.Optional.ofNullable;
import static java.util.function.Function.identity;
import static java.util.stream.Collectors.joining;
import static java.util.stream.Collectors.toMap;

@Path("metrics")
public class MetricsEndpoints {
    private final Pattern semicolon = Pattern.compile(";");

    private MetricRegistry baseRegistry;
    private MetricRegistry vendorRegistry;
    private MetricRegistry applicationRegistry;
    private Tag[] globalTags = new Tag[0]; // ensure forgetting to call init() is tolerated for backward compatibility

    private SecurityValidator securityValidator = new SecurityValidator() {
        {
            init();
        }
    };

    private PrometheusFormatter prometheus = new PrometheusFormatter().enableOverriding();

    protected void init() {
        globalTags = Stream.of(baseRegistry, vendorRegistry, applicationRegistry)
                .filter(RegistryImpl.class::isInstance)
                .map(RegistryImpl.class::cast)
                .findFirst()
                .map(RegistryImpl::getGlobalTags)
                .orElseGet(() -> new Tag[0]);
        prometheus.withGlobalTags(globalTags);
    }

    public void setBaseRegistry(final MetricRegistry baseRegistry) {
        this.baseRegistry = baseRegistry;
    }

    public void setVendorRegistry(final MetricRegistry vendorRegistry) {
        this.vendorRegistry = vendorRegistry;
    }

    public void setApplicationRegistry(final MetricRegistry applicationRegistry) {
        this.applicationRegistry = applicationRegistry;
    }

    public void setSecurityValidator(final SecurityValidator securityValidator) {
        this.securityValidator = securityValidator;
    }

    public void setPrometheus(final PrometheusFormatter prometheus) {
        this.prometheus = prometheus;
    }

    @GET
    @Produces(MediaType.APPLICATION_JSON)
    public Object getJson(@Context final SecurityContext securityContext,
                          @Context final UriInfo uriInfo) {
        securityValidator.checkSecurity(securityContext, uriInfo);
        return Stream.of(MetricRegistry.Type.values())
                .collect(toMap(MetricRegistry.Type::getName, it -> findRegistry(it.getName()).getMetrics().entrySet().stream()
                        .collect(toMap(this::getKey, m -> toJson(map(m.getValue()), formatTags(m.getKey())), this::merge))));
    }

    @GET
    @Produces(MediaType.TEXT_PLAIN)
    public String getText(@Context final SecurityContext securityContext,
                          @Context final UriInfo uriInfo) {
        securityValidator.checkSecurity(securityContext, uriInfo);
        return Stream.of(MetricRegistry.Type.values())
                .map(type -> {
                    final MetricRegistry metricRegistry = findRegistry(type.getName());
                    return prometheus.toText(metricRegistry, type.getName(), metrics(metricRegistry));
                })
                .collect(StringBuilder::new, StringBuilder::append, StringBuilder::append)
                .toString();
    }

    @GET
    @Path("{registry}")
    @Produces(MediaType.APPLICATION_JSON)
    public Object getJson(@PathParam("registry") final String registry,
                          @Context final SecurityContext securityContext,
                          @Context final UriInfo uriInfo) {
        securityValidator.checkSecurity(securityContext, uriInfo);
        return findRegistry(registry).getMetrics().entrySet().stream()
                .collect(toMap(this::getKey, it -> toJson(map(it.getValue()), formatTags(it.getKey())), this::merge));
    }

    @GET
    @Path("{registry}")
    @Produces(MediaType.TEXT_PLAIN)
    public String getText(@PathParam("registry") final String registry,
                          @Context final SecurityContext securityContext,
                          @Context final UriInfo uriInfo) {
        securityValidator.checkSecurity(securityContext, uriInfo);
        final MetricRegistry metricRegistry = findRegistry(registry);
        return prometheus.toText(metricRegistry, registry, metrics(metricRegistry)).toString();
    }

    @GET
    @Path("{registry}/{metric}")
    @Produces(MediaType.APPLICATION_JSON)
    public Object getJson(@PathParam("registry") final String registry,
                          @PathParam("metric") final String name,
                          @Context final SecurityContext securityContext,
                          @Context final UriInfo uriInfo) {
        securityValidator.checkSecurity(securityContext, uriInfo);
        final MetricRegistry metricRegistry = findRegistry(registry);
        return singleEntry(name, metricRegistry, this::map);
    }

    @GET
    @Path("{registry}/{metric}")
    @Produces(MediaType.TEXT_PLAIN)
    public String getText(@PathParam("registry") final String registry,
                          @PathParam("metric") final String name,
                          @Context final SecurityContext securityContext,
                          @Context final UriInfo uriInfo) {
        securityValidator.checkSecurity(securityContext, uriInfo);
        final MetricRegistry metricRegistry = findRegistry(registry);
        return prometheus.toText(
                metricRegistry, registry,
                singleEntry(name, metricRegistry, identity())).toString();
    }

    @OPTIONS
    @Path("{registry}/{metric}")
    @Produces(MediaType.APPLICATION_JSON)
    public Object getMetadata(@PathParam("registry") final String registry,
                              @PathParam("metric") final String name,
                              @Context final SecurityContext securityContext,
                              @Context final UriInfo uriInfo) {
        securityValidator.checkSecurity(securityContext, uriInfo);
        final MetricRegistry metricRegistry = findRegistry(registry);
        return ofNullable(metricRegistry.getMetadata().get(name))
                .map(metadata -> singletonMap(name, mapMeta(metadata, findMetricId(metricRegistry, metadata))))
                .orElse(emptyMap());
    }

    @OPTIONS
    @Path("{registry}")
    @Produces(MediaType.APPLICATION_JSON)
    public Object getMetadata(@PathParam("registry") final String registry,
                              @Context final SecurityContext securityContext,
                              @Context final UriInfo uriInfo) {
        securityValidator.checkSecurity(securityContext, uriInfo);
        final MetricRegistry metricRegistry = findRegistry(registry);
        return metricRegistry.getMetadata().entrySet().stream()
                .collect(toMap(Map.Entry::getKey, e -> mapMeta(e.getValue(), findMetricId(metricRegistry, e.getValue())), this::merge));
    }

    private MetricID findMetricId(final MetricRegistry metricRegistry, final Metadata value) {
        final Map<MetricID, Metric> metrics = metricRegistry.getMetrics();
        final MetricID directKey = RegistryImpl.class.isInstance(metricRegistry) && RegistryImpl.class.cast(metricRegistry).getGlobalTags().length > 0 ?
                new MetricID(value.getName(), RegistryImpl.class.cast(metricRegistry).getGlobalTags()) : new MetricID(value.getName());
        if (metrics.containsKey(directKey)) {
            return directKey;
        }
        return metrics.keySet().stream()
                .filter(it -> Objects.equals(it.getName(), value.getName()))
                .findFirst()
                .orElse(directKey);
    }

    private <A> A merge(final A a, final A b) {
        if (Map.class.isInstance(a) && Map.class.isInstance(b)) {
            final Map<String, Object> firstMap = (Map<String, Object>) a;
            final Map<String, Object> secondMap = (Map<String, Object>) b;
            final Map<String, Object> merged = Stream.concat(firstMap.entrySet().stream(), secondMap.entrySet().stream())
                    .collect(toMap(Map.Entry::getKey, Map.Entry::getValue, (m1, m2) -> m1));
            return (A) merged;
        }
        return a;
    }

    private Map<String, Metric> metrics(final MetricRegistry metricRegistry) {
        return metricRegistry.getMetrics().entrySet().stream()
                .collect(toMap(this::getKey, Map.Entry::getValue, this::merge));
    }

    private <T> Map<String, T> singleEntry(final String id, final MetricRegistry metricRegistry,
                                           final Function<Metric, T> metricMapper) {
        final MetricID key = RegistryImpl.class.isInstance(metricRegistry) && RegistryImpl.class.cast(metricRegistry).getGlobalTags().length > 0 ?
                new MetricID(id, RegistryImpl.class.cast(metricRegistry).getGlobalTags()) : new MetricID(id);
        final Map<MetricID, Metric> metrics = metricRegistry.getMetrics();
        return ofNullable(metrics.get(key)) // try first without any tag (fast access)
                .map(metric -> singletonMap(id + formatTags(key), metricMapper.apply(metric)))
                .orElseGet(() -> metrics.keySet().stream().filter(it -> Objects.equals(it.getName(), id)).findFirst() // else find first matching id
                        .map(metric -> singletonMap(id + formatTags(key), metricMapper.apply(metrics.get(metric))))
                        .orElseGet(Collections::emptyMap));
    }

    private Meta mapMeta(final Metadata value, final MetricID metricID) {
        return ofNullable(value).map(v -> new Meta(value, metricID, globalTags)).orElse(null);
    }

    private Object map(final Metric metric) {
        if (Counter.class.isInstance(metric)) {
            return Counter.class.cast(metric).getCount();
        }
        if (Gauge.class.isInstance(metric)) {
            return Gauge.class.cast(metric).getValue();
        }
        return metric;
    }

    private String getKey(final Map.Entry<MetricID, Metric> e) {
        if (Counter.class.isInstance(e.getValue()) || Gauge.class.isInstance(e.getValue())) {
            return e.getKey().getName() + formatTags(e.getKey());
        }
        return e.getKey().getName();
    }

    // https://github.com/eclipse/microprofile-metrics/issues/508
    private Object toJson(final Object metric, final String nameSuffix) {
        if (Timer.class.isInstance(metric)) {
            final Timer meter = Timer.class.cast(metric);
            final Map<Object, Object> map = new TreeMap<>();
            map.putAll(snapshot(meter.getSnapshot(), nameSuffix));
            map.putAll(meter(meter, nameSuffix));
            final Duration elapsedTime = meter.getElapsedTime();
            map.put("elapsedTime" + nameSuffix, elapsedTime == null ? 0 : elapsedTime.toNanos());
            return map;
        }
        if (SimpleTimer.class.isInstance(metric)) {
            final SimpleTimer simpleTimer = SimpleTimer.class.cast(metric);
            final Map<Object, Object> map = new TreeMap<>();
            map.put("count" + nameSuffix, simpleTimer.getCount());
            final Duration elapsedTime = simpleTimer.getElapsedTime();
            map.put("elapsedTime" + nameSuffix, elapsedTime == null ? 0 : elapsedTime.toNanos());
            final Duration minTimeDuration = simpleTimer.getMinTimeDuration();
            map.put("minTimeDuration" + nameSuffix, minTimeDuration == null ? JsonValue.NULL : minTimeDuration.toNanos());
            final Duration maxTimeDuration = simpleTimer.getMaxTimeDuration();
            map.put("maxTimeDuration" + nameSuffix, maxTimeDuration == null ? JsonValue.NULL : maxTimeDuration.toNanos());
            return map;
        }
        if (Meter.class.isInstance(metric)) {
            return meter(Meter.class.cast(metric), nameSuffix);
        }
        if (Histogram.class.isInstance(metric)) {
            final Histogram histogram = Histogram.class.cast(metric);
            final Map<Object, Object> map = new TreeMap<>();
            map.putAll(snapshot(histogram.getSnapshot(), nameSuffix));
            map.put("count" + nameSuffix, histogram.getCount());
            return map;
        }
        if (ConcurrentGauge.class.isInstance(metric)) {
            final ConcurrentGauge concurrentGauge = ConcurrentGauge.class.cast(metric);
            final Map<Object, Object> map = new TreeMap<>();
            map.put("min" + nameSuffix, concurrentGauge.getMin());
            map.put("current" + nameSuffix, concurrentGauge.getCount());
            map.put("max" + nameSuffix, concurrentGauge.getMax());
            return map;
        }
        // counters and gauges are unwrapped so skip it
        return metric;
    }

    private Map<String, Object> meter(final Metered metered, final String nameSuffix) {
        final Map<String, Object> map = new TreeMap<>();
        map.put("count" + nameSuffix, metered.getCount());
        map.put("meanRate" + nameSuffix, metered.getMeanRate());
        map.put("oneMinRate" + nameSuffix, metered.getOneMinuteRate());
        map.put("fiveMinRate" + nameSuffix, metered.getFiveMinuteRate());
        map.put("fifteenMinRate" + nameSuffix, metered.getFifteenMinuteRate());
        return map;
    }

    private Map<String, Object> snapshot(final Snapshot snapshot, final String nameSuffix) {
        final Map<String, Object> map = new TreeMap<>();
        map.put("p50" + nameSuffix, snapshot.getMedian());
        map.put("p75" + nameSuffix, snapshot.get75thPercentile());
        map.put("p95" + nameSuffix, snapshot.get95thPercentile());
        map.put("p98" + nameSuffix, snapshot.get98thPercentile());
        map.put("p99" + nameSuffix, snapshot.get99thPercentile());
        map.put("p999" + nameSuffix, snapshot.get999thPercentile());
        map.put("min" + nameSuffix, snapshot.getMin());
        map.put("mean" + nameSuffix, snapshot.getMean());
        map.put("max" + nameSuffix, snapshot.getMax());
        map.put("stddev" + nameSuffix, snapshot.getStdDev());
        return map;
    }

    private MetricRegistry findRegistry(final String registry) {
        switch (Stream.of(MetricRegistry.Type.values())
                .filter(it -> it.getName().equalsIgnoreCase(registry)).findFirst()
                .orElseThrow(() -> new WebApplicationException(Response.Status.NOT_FOUND))) {
            case BASE:
                return baseRegistry;
            case VENDOR:
                return vendorRegistry;
            default:
                return applicationRegistry;
        }
    }

    private String formatTags(final MetricID id) {
        return id.getTags().isEmpty() && globalTags.length == 0 ? "" : (';' +
                Stream.concat(id.getTagsAsList().stream(), Stream.of(globalTags))
                .map(e -> e.getTagName() + "=" + semicolon.matcher(e.getTagValue()).replaceAll("_"))
                .distinct()
                .collect(joining(";")));
    }

    public static class Meta {
        private final Metadata value;
        private final MetricID metricID;
        private final Tag[] globalTags;

        private Meta(final Metadata value, final MetricID metricID, final Tag[] globalTags) {
            this.value = value;
            this.metricID = metricID;
            this.globalTags = globalTags;
        }

        public String getName() {
            return value.getName();
        }

        public String getDisplayName() {
            return value.getDisplayName();
        }

        public String getDescription() {
            return value.getDescription();
        }

        public String getType() {
            return value.getType();
        }

        public String getTypeRaw() {
            return value.getTypeRaw().name();
        }

        public String getUnit() {
            return value.getUnit();
        }

        public String getTags() { // not sure why tck expect it, sounds worse than native getTags for clients (array of key/values)
            return Stream.concat(
                    metricID.getTags().entrySet().stream().map(e -> e.getKey() + '=' + e.getValue()),
                    Stream.of(globalTags).map(e -> e.getTagName() + '=' + e.getTagValue()))
                    .distinct()
                    .collect(joining(","));
        }
    }
}
