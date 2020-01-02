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

import static java.util.Collections.emptyMap;
import static java.util.Collections.singletonMap;
import static java.util.Optional.ofNullable;
import static java.util.function.Function.identity;
import static java.util.stream.Collectors.joining;
import static java.util.stream.Collectors.toMap;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Stream;

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
import org.eclipse.microprofile.metrics.Snapshot;
import org.eclipse.microprofile.metrics.Timer;

@Path("metrics")
public class MetricsEndpoints {
    private MetricRegistry baseRegistry;
    private MetricRegistry vendorRegistry;
    private MetricRegistry applicationRegistry;

    private SecurityValidator securityValidator = new SecurityValidator() {
        {
            init();
        }
    };

    private PrometheusFormatter prometheus = new PrometheusFormatter().enableOverriding();

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
                        .collect(toMap(this::getKey, m -> toJson(map(m.getValue()), formatTags(m.getKey())), (a, b) -> a))));
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
                .collect(toMap(this::getKey, it -> toJson(map(it.getValue()), formatTags(it.getKey())), (a, b) -> a));
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
        return ofNullable(findRegistry(registry).getMetadata().get(name))
                .map(metric -> singletonMap(name, mapMeta(metric, new MetricID(name))))
                .orElse(emptyMap());
    }

    @OPTIONS
    @Path("{registry}")
    @Produces(MediaType.APPLICATION_JSON)
    public Object getMetadata(@PathParam("registry") final String registry,
                              @Context final SecurityContext securityContext,
                              @Context final UriInfo uriInfo) {
        securityValidator.checkSecurity(securityContext, uriInfo);
        return findRegistry(registry).getMetadata().entrySet().stream()
                .collect(toMap(Map.Entry::getKey, e -> mapMeta(e.getValue(), new MetricID(e.getKey())), (a, b) -> a));
    }

    private Map<String, Metric> metrics(final MetricRegistry metricRegistry) {
        return metricRegistry.getMetrics().entrySet().stream()
                .collect(toMap(this::getKey, Map.Entry::getValue, (a, b) -> a));
    }

    private <T> Map<String, T> singleEntry(final String id, final MetricRegistry metricRegistry,
                                           final Function<Metric, T> metricMapper) {
        final MetricID key = new MetricID(id);
        return ofNullable(metricRegistry.getMetrics().get(key))
                .map(metric -> singletonMap(id + formatTags(key), metricMapper.apply(metric)))
                .orElseGet(Collections::emptyMap);
    }

    private Meta mapMeta(final Metadata value, final MetricID metricID) {
        return ofNullable(value).map(v -> new Meta(value, metricID)).orElse(null);
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
            final Map<Object, Object> map = new HashMap<>(15);
            map.putAll(snapshot(meter.getSnapshot(), nameSuffix));
            map.putAll(meter(meter, nameSuffix));
            return map;
        }
        if (Meter.class.isInstance(metric)) {
            return meter(Meter.class.cast(metric), nameSuffix);
        }
        if (Histogram.class.isInstance(metric)) {
            final Histogram histogram = Histogram.class.cast(metric);
            final Map<Object, Object> map = new HashMap<>(11);
            map.putAll(snapshot(histogram.getSnapshot(), nameSuffix));
            map.put("count" + nameSuffix, histogram.getCount());
            return map;
        }
        if (ConcurrentGauge.class.isInstance(metric)) {
            final ConcurrentGauge concurrentGauge = ConcurrentGauge.class.cast(metric);
            final Map<Object, Object> map = new HashMap<>(3);
            map.put("min" + nameSuffix, concurrentGauge.getMin());
            map.put("current" + nameSuffix, concurrentGauge.getCount());
            map.put("max" + nameSuffix, concurrentGauge.getMax());
            return map;
        }
        // counters and gauges are unwrapped so skip it
        return metric;
    }

    private Map<String, Object> meter(final Metered metered, final String nameSuffix) {
        final Map<String, Object> map = new HashMap<>(5);
        map.put("count" + nameSuffix, metered.getCount());
        map.put("meanRate" + nameSuffix, metered.getMeanRate());
        map.put("oneMinRate" + nameSuffix, metered.getOneMinuteRate());
        map.put("fiveMinRate" + nameSuffix, metered.getFiveMinuteRate());
        map.put("fifteenMinRate" + nameSuffix, metered.getFifteenMinuteRate());
        return map;
    }

    private Map<String, Object> snapshot(final Snapshot snapshot, final String nameSuffix) {
        final Map<String, Object> map = new HashMap<>(10);
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
        return id.getTags().isEmpty() ? "" : (';' + id.getTags().entrySet().stream()
                .map(e -> e.getKey() + "=" + e.getValue())
                .collect(joining(",")));
    }

    public static class Meta {
        private final Metadata value;
        private final MetricID metricID;

        private Meta(final Metadata value, final MetricID metricID) {
            this.value = value;
            this.metricID = metricID;
        }

        public String getName() {
            return value.getName();
        }

        public String getDisplayName() {
            return value.getDisplayName();
        }

        public String getDescription() {
            return value.getDescription().orElse(null);
        }

        public String getType() {
            return value.getType();
        }

        public String getTypeRaw() {
            return value.getTypeRaw().name();
        }

        public String getUnit() {
            return value.getUnit().orElse(null);
        }

        public boolean isReusable() {
            return value.isReusable();
        }

        public String getTags() { // not sure why tck expect it, sounds worse than native getTags for clients
            return metricID.getTags().entrySet().stream().map(e -> e.getKey() + '=' + e.getValue()).collect(joining(","));
        }
    }
}
