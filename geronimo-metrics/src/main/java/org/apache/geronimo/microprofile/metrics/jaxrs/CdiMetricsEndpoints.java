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

import static org.eclipse.microprofile.metrics.MetricRegistry.Type.BASE;
import static org.eclipse.microprofile.metrics.MetricRegistry.Type.VENDOR;

import javax.annotation.PostConstruct;
import javax.enterprise.context.ApplicationScoped;
import javax.inject.Inject;
import javax.ws.rs.Path;

import org.apache.geronimo.microprofile.metrics.common.jaxrs.MetricsEndpoints;
import org.eclipse.microprofile.metrics.MetricRegistry;
import org.eclipse.microprofile.metrics.annotation.RegistryType;

@Path("metrics")
@ApplicationScoped
public class CdiMetricsEndpoints extends MetricsEndpoints {
    @Inject
    @RegistryType(type = BASE)
    private MetricRegistry baseRegistry;

    @Inject
    @RegistryType(type = VENDOR)
    private MetricRegistry vendorRegistry;

    @Inject
    private MetricRegistry applicationRegistry;

    @PostConstruct
    private void init() {
        setApplicationRegistry(applicationRegistry);
        setBaseRegistry(baseRegistry);
        setVendorRegistry(vendorRegistry);
    }
}
