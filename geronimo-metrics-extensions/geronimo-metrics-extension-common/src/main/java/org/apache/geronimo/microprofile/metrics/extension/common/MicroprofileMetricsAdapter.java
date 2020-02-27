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
package org.apache.geronimo.microprofile.metrics.extension.common;

import static org.eclipse.microprofile.metrics.MetricType.GAUGE;

import java.util.function.Consumer;
import java.util.logging.Logger;

import org.eclipse.microprofile.metrics.Gauge;
import org.eclipse.microprofile.metrics.Metadata;
import org.eclipse.microprofile.metrics.MetricRegistry;

public class MicroprofileMetricsAdapter {
    private final MetricRegistry registry;

    public MicroprofileMetricsAdapter(final MetricRegistry registry) {
        this.registry = registry;
    }

    public Consumer<Definition> registrer() {
        return def -> {
            final Metadata metadata = Metadata.builder()
                .withName(def.getName())
                .withDisplayName(def.getDisplayName())
                .withDescription(def.getDescription())
                .withType(GAUGE)
                .withUnit(def.getUnit())
                .reusable(true)
                .build();
            try {
                registry.register(metadata, (Gauge<Double>) () -> def.getEvaluator()
                                                                     .getAsDouble());
            } catch (final RuntimeException re) {
                Logger.getLogger(MicroprofileMetricsAdapter.class.getName()).fine(re.getMessage());
            }
        };
    }

    public Consumer<Definition> unregistrer() {
        return def -> registry.remove(def.getName());
    }
}
