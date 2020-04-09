/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License. You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.geronimo.microprofile.metrics.common.json;

import org.apache.geronimo.microprofile.metrics.common.RegistryImpl;
import org.apache.geronimo.microprofile.metrics.common.jaxrs.MetricsEndpoints;
import org.apache.geronimo.microprofile.metrics.common.jaxrs.SecurityValidator;
import org.apache.geronimo.microprofile.metrics.common.prometheus.PrometheusFormatter;
import org.eclipse.microprofile.metrics.Gauge;
import org.eclipse.microprofile.metrics.MetricRegistry;
import org.junit.Test;

import javax.ws.rs.core.SecurityContext;
import javax.ws.rs.core.UriInfo;

import static java.util.Collections.singletonMap;
import static org.junit.Assert.assertEquals;

public class JsonMetricTest {

    @Test
    public void testJsonGaugeValue() {
        final RegistryImpl registry = new RegistryImpl(MetricRegistry.Type.APPLICATION);
        registry.register("foo", (Gauge<Long>) () -> 1L);

        final MetricsEndpoints endpoints = new MetricsEndpoints();
        endpoints.setApplicationRegistry(registry);
        endpoints.setPrometheus(new PrometheusFormatter());
        endpoints.setSecurityValidator(new SecurityValidator() {
            @Override
            public void checkSecurity(final SecurityContext securityContext, final UriInfo uriInfo) {
                // no-op
            }
        });
        final Object json = endpoints.getJson("application", "foo", null, null);
        assertEquals(singletonMap("foo", 1L), json);
    }
}
