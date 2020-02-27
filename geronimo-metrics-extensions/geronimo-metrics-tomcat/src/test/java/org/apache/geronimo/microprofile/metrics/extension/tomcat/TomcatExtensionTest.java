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
package org.apache.geronimo.microprofile.metrics.extension.tomcat;

import static org.junit.Assert.assertNotNull;

import javax.enterprise.inject.spi.CDI;

import org.apache.geronimo.microprofile.metrics.extension.common.RegistryTypeLiteral;
import org.apache.meecrowave.Meecrowave;
import org.apache.meecrowave.junit.MeecrowaveRule;
import org.eclipse.microprofile.metrics.Gauge;
import org.eclipse.microprofile.metrics.MetricID;
import org.eclipse.microprofile.metrics.MetricRegistry;
import org.junit.ClassRule;
import org.junit.Test;

public class TomcatExtensionTest {
    @ClassRule
    public static final MeecrowaveRule MEECROWAVE = new MeecrowaveRule(new Meecrowave.Builder() {{
        setTomcatNoJmx(false);
    }}, "");

    @Test
    public void checkTomcatRegistration() {
        final Gauge gauge = CDI.current()
                               .select(MetricRegistry.class, new RegistryTypeLiteral(MetricRegistry.Type.BASE))
                               .get()
                               .getGauges()
                               .get(new MetricID("server.executor.port_" + MEECROWAVE.getConfiguration().getHttpPort() + ".active"));
        assertNotNull(gauge);
        assertNotNull(gauge.getValue());
    }
}
