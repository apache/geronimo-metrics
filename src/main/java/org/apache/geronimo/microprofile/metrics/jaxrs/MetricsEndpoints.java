package org.apache.geronimo.microprofile.metrics.jaxrs;

import static java.util.Collections.singletonMap;
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
import org.eclipse.microprofile.metrics.Metric;
import org.eclipse.microprofile.metrics.MetricRegistry;
import org.eclipse.microprofile.metrics.annotation.RegistryType;

@Path("metrics")
@ApplicationScoped
@Produces(MediaType.APPLICATION_JSON)
public class MetricsEndpoints {
    @Inject
    @RegistryType(type = BASE)
    private MetricRegistry baseRegistry;

    @Inject
    @RegistryType(type = VENDOR)
    private MetricRegistry vendorRegistry;

    @Inject
    private MetricRegistry applicationRegistry;

    @GET
    public Object get() {
        return Stream.of(MetricRegistry.Type.values())
                .collect(toMap(MetricRegistry.Type::getName, it -> findRegistry(it.getName()).getMetrics().entrySet().stream()
                        .collect(toMap(Map.Entry::getKey, m -> metricToJson(m.getValue())))));
    }

    @GET
    @Path("{registry}")
    public Object get(@PathParam("registry") final String registry) {
        return findRegistry(registry).getMetrics().entrySet().stream()
                .collect(toMap(Map.Entry::getKey, it -> metricToJson(it.getValue())));
    }

    @GET
    @Path("{registry}/{metric}")
    public Object get(@PathParam("registry") final String registry,
                                   @PathParam("metric") final String name) {
        return singletonMap(name, metricToJson(findRegistry(registry).getMetrics().get(name)));
    }

    @OPTIONS
    @Path("{registry}/{metric}")
    public Object options(@PathParam("registry") final String registry,
                                   @PathParam("metric") final String name) {
        return singletonMap(name, findRegistry(registry).getMetadata().get(name));
    }

    private Object metricToJson(final Metric metric) {
        if (Counter.class.isInstance(metric)) {
            return Counter.class.cast(metric).getCount();
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
}
