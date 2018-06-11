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
package org.apache.geronimo.microprofile.metrics.jaxrs;

import static java.util.Collections.singletonMap;
import static java.util.Optional.ofNullable;
import static java.util.stream.Collectors.joining;
import static java.util.stream.Collectors.toMap;
import static org.eclipse.microprofile.metrics.MetricRegistry.Type.BASE;
import static org.eclipse.microprofile.metrics.MetricRegistry.Type.VENDOR;

import java.util.Map;
import java.util.stream.Stream;

import javax.enterprise.context.ApplicationScoped;
import javax.inject.Inject;
import javax.ws.rs.GET;
import javax.ws.rs.OPTIONS;
import javax.ws.rs.Path;
import javax.ws.rs.PathParam;
import javax.ws.rs.Produces;
import javax.ws.rs.WebApplicationException;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;

import org.eclipse.microprofile.metrics.Counter;
import org.eclipse.microprofile.metrics.Gauge;
import org.eclipse.microprofile.metrics.Metadata;
import org.eclipse.microprofile.metrics.Metric;
import org.eclipse.microprofile.metrics.MetricRegistry;
import org.eclipse.microprofile.metrics.annotation.RegistryType;

@Path("metrics")
@ApplicationScoped
public class MetricsEndpoints {
    @Inject
    @RegistryType(type = BASE)
    private MetricRegistry baseRegistry;

    @Inject
    @RegistryType(type = VENDOR)
    private MetricRegistry vendorRegistry;

    @Inject
    private MetricRegistry applicationRegistry;

    @Inject
    private PrometheusFormatter prometheus;

    @GET
    @Produces(MediaType.APPLICATION_JSON)
    public Object getJson() {
        return Stream.of(MetricRegistry.Type.values())
                .collect(toMap(MetricRegistry.Type::getName, it -> findRegistry(it.getName()).getMetrics().entrySet().stream()
                        .collect(toMap(Map.Entry::getKey, m -> map(m.getValue())))));
    }

    @GET
    @Produces(MediaType.TEXT_PLAIN)
    public String getText() {
        return Stream.of(MetricRegistry.Type.values())
                .map(type -> {
                    final MetricRegistry metricRegistry = findRegistry(type.getName());
                    return prometheus.toText(metricRegistry, type.getName(), metricRegistry.getMetrics());
                })
                .collect(StringBuilder::new, StringBuilder::append, StringBuilder::append)
                .toString();
    }

    @GET
    @Path("{registry}")
    @Produces(MediaType.APPLICATION_JSON)
    public Object getJson(@PathParam("registry") final String registry) {
        return findRegistry(registry).getMetrics().entrySet().stream()
                .collect(toMap(Map.Entry::getKey, it -> map(it.getValue())));
    }

    @GET
    @Path("{registry}")
    @Produces(MediaType.TEXT_PLAIN)
    public String getText(@PathParam("registry") final String registry) {
        final MetricRegistry metricRegistry = findRegistry(registry);
        return prometheus.toText(metricRegistry, registry, metricRegistry.getMetrics()).toString();
    }

    @GET
    @Path("{registry}/{metric}")
    @Produces(MediaType.APPLICATION_JSON)
    public Object getJson(@PathParam("registry") final String registry,
                          @PathParam("metric") final String name) {
        return singletonMap(name, findRegistry(registry).getMetrics().get(name));
    }

    @GET
    @Path("{registry}/{metric}")
    @Produces(MediaType.TEXT_PLAIN)
    public String getText(@PathParam("registry") final String registry,
                          @PathParam("metric") final String name) {
        final MetricRegistry metricRegistry = findRegistry(registry);
        return prometheus.toText(metricRegistry, registry, singletonMap(name, metricRegistry.getMetrics().get(name))).toString();
    }

    @OPTIONS
    @Path("{registry}/{metric}")
    @Produces(MediaType.APPLICATION_JSON)
    public Object getMetadata(@PathParam("registry") final String registry,
                          @PathParam("metric") final String name) {
        return singletonMap(name, mapMeta(findRegistry(registry).getMetadata().get(name)));
    }

    @OPTIONS
    @Path("{registry}")
    @Produces(MediaType.APPLICATION_JSON)
    public Object getMetadata(@PathParam("registry") final String registry) {
        return findRegistry(registry).getMetadata().entrySet().stream()
                .collect(toMap(Map.Entry::getKey, e -> mapMeta(e.getValue())));
    }

    private Meta mapMeta(final Metadata value) {
        return ofNullable(value).map(Meta::new).orElse(null);
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

    private MetricRegistry findRegistry(final String registry) {
        switch (Stream.of(MetricRegistry.Type.values()).filter(it -> it.getName().equals(registry)).findFirst()
                .orElseThrow(() -> new WebApplicationException(Response.Status.NOT_FOUND))) {
            case BASE:
                return baseRegistry;
            case VENDOR:
                return vendorRegistry;
            default:
                return applicationRegistry;
        }
    }

    public static class Meta {
        private final Metadata value;

        private Meta(final Metadata value) {
            this.value = value;
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

        public boolean isReusable() {
            return value.isReusable();
        }

        public String getTags() { // not sure why tck expect it, sounds worse than native getTags for clients
            return value.getTags().entrySet().stream().map(e -> e.getKey() + '=' + e.getValue()).collect(joining(","));
        }
    }
}
