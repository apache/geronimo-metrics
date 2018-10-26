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

import static java.util.Collections.singletonMap;
import static org.junit.Assert.assertEquals;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Properties;

import org.apache.geronimo.microprofile.metrics.common.RegistryImpl;
import org.eclipse.microprofile.metrics.Gauge;
import org.eclipse.microprofile.metrics.Metric;
import org.junit.Test;

public class PrometheusFormatterTest {
    @Test
    public void rename() {
        final PrometheusFormatter prometheusFormatter = new PrometheusFormatter().enableOverriding();
        final RegistryImpl registry = new RegistryImpl();
        final Map<String, Metric> metrics = singletonMap("myMetric", (Gauge<Long>) () -> 1234L);
        metrics.forEach(registry::register);
        assertEquals(
                "# TYPE sample:my_metric gauge\nsample:my_metric 1234.0\n",
                prometheusFormatter.toText(registry, "sample", metrics).toString());
        System.setProperty("geronimo.metrics.prometheus.mapping.sample:my_metric", "renamed");
        prometheusFormatter.enableOverriding();
        assertEquals(
                "# TYPE renamed gauge\nrenamed 1234.0\n",
                prometheusFormatter.toText(registry, "sample", metrics).toString());
        System.clearProperty("sample:my_metric");
        System.setProperty("geronimo.metrics.prometheus.mapping.sample:my_metric", "renamed");
        prometheusFormatter.enableOverriding(new Properties() {{
            setProperty("sample:my_metric", "again");
        }});
        assertEquals(
                "# TYPE again gauge\nagain 1234.0\n",
                prometheusFormatter.toText(registry, "sample", metrics).toString());
    }

    @Test
    public void filter() {
        final PrometheusFormatter prometheusFormatter = new PrometheusFormatter().enableOverriding();
        final RegistryImpl registry = new RegistryImpl();
        final Map<String, Metric> metrics = new LinkedHashMap<>();
        metrics.put("myMetric1", (Gauge<Long>) () -> 1234L);
        metrics.put("myMetric2", (Gauge<Long>) () -> 1235L);
        metrics.forEach(registry::register);
        assertEquals(
                "# TYPE sample:my_metric1 gauge\nsample:my_metric1 1234.0\n" +
                        "# TYPE sample:my_metric2 gauge\nsample:my_metric2 1235.0\n",
                prometheusFormatter.toText(registry, "sample", metrics).toString());
        prometheusFormatter.enableOverriding(new Properties() {{
            setProperty("geronimo.metrics.filter.prefix", "sample:my_metric2");
        }});
        assertEquals(
                "# TYPE sample:my_metric2 gauge\nsample:my_metric2 1235.0\n",
                prometheusFormatter.toText(registry, "sample", metrics).toString());
    }
}
