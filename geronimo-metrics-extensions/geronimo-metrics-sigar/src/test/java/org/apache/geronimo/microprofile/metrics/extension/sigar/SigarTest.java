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
package org.apache.geronimo.microprofile.metrics.extension.sigar;

import static java.util.stream.Collectors.toList;
import static org.eclipse.microprofile.metrics.MetricRegistry.Type.BASE;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.util.List;

import javax.inject.Inject;

import org.apache.meecrowave.Meecrowave;
import org.apache.meecrowave.junit.MeecrowaveRule;
import org.eclipse.microprofile.metrics.MetricRegistry;
import org.eclipse.microprofile.metrics.annotation.RegistryType;
import org.junit.ClassRule;
import org.junit.Test;

public class SigarTest {
    @ClassRule
    public static final MeecrowaveRule RULE = new MeecrowaveRule(new Meecrowave.Builder() {{
        setSkipHttp(true);
    }}, "");

    @Inject
    @RegistryType(type = BASE)
    private MetricRegistry registry;

    @Test
    public void test() {
        RULE.inject(this);
        final List<String> keys = registry.getGauges()
                                             .keySet()
                                             .stream()
                                             .filter(it -> it.startsWith("sigar."))
                                             .sorted()
                                             .collect(toList());
        assertTrue(keys.toString(), keys.size() > 10 /*whatever, just check it is registered*/);
        // ensure gauge is usable
        final Object cpu = registry.getGauges().get("sigar.cpu.total").getValue();
        assertNotNull(cpu);
    }
}
