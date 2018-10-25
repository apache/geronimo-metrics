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

import static org.eclipse.microprofile.metrics.MetricType.GAUGE;

import java.io.File;
import java.lang.annotation.Annotation;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Stream;

import javax.enterprise.event.Observes;
import javax.enterprise.inject.spi.AfterDeploymentValidation;
import javax.enterprise.inject.spi.Bean;
import javax.enterprise.inject.spi.BeanManager;
import javax.enterprise.inject.spi.BeforeShutdown;
import javax.enterprise.inject.spi.Extension;

import org.eclipse.microprofile.metrics.Gauge;
import org.eclipse.microprofile.metrics.Metadata;
import org.eclipse.microprofile.metrics.MetricRegistry;
import org.eclipse.microprofile.metrics.annotation.RegistryType;

public class MicroprofileMetricsSigarRegistrar implements Extension {

    private SigarRegistrar registrar;

    void afterValidation(@Observes final AfterDeploymentValidation validation,
                         final BeanManager beanManager) {
        final InitSigar initSigar = new InitSigar(findTempDir());
        initSigar.ensureSigarIsSetup();
        if (!initSigar.isValid()) {
            return;
        }

        final MetricRegistry.Type registryType = MetricRegistry.Type.valueOf(
                System.getProperty("geronimo.metrics.sigar.registry.type", "BASE"));
        final Set<Bean<?>> beans = beanManager.getBeans(MetricRegistry.class, new RegistryTypeLiteral(registryType));
        final MetricRegistry registry = MetricRegistry.class.cast(beanManager.getReference(
                beanManager.resolve(beans), MetricRegistry.class, beanManager.createCreationalContext(null)));
        registrar = new SigarRegistrar(
                def -> registry.register(
                    new Metadata(def.getName(), def.getDisplayName(), def.getDescription(), GAUGE, def.getUnit()),
                    (Gauge<Double>) () -> def.getEvaluator().getAsDouble()),
                def -> registry.remove(def.getName()));
        registrar.start();
    }

    void beforeShutdown(@Observes final BeforeShutdown beforeShutdown) {
        if (registrar == null) {
            return;
        }
        registrar.stop();
    }

    // let's try some well know temp folders and fallback on java io one
    private File findTempDir() {
        return new File(
                Stream.of(
                        "geronimo.metrics.sigar.location",
                        "catalina.base", "catalina.base",
                        "meecrowave.base", "tomee.base",
                        "application.base", "application.home")
                    .map(System::getProperty)
                    .filter(Objects::nonNull)
                    .map(File::new)
                    .filter(File::exists)
                    .flatMap(root -> Stream.of(
                            new File(root, "work"),
                            new File(root, "temp"),
                            new File(root, "tmp")))
                    .filter(File::exists)
                    .findFirst()
                    .orElseGet(() -> new File(System.getProperty("java.io.tmpdir", "."))),
                System.getProperty("geronimo.metrics.sigar.folder", "sigar"));
    }

    private static class RegistryTypeLiteral implements RegistryType {
        private final MetricRegistry.Type type;

        private RegistryTypeLiteral(final MetricRegistry.Type registryType) {
            this.type = registryType;
        }

        @Override
        public MetricRegistry.Type type() {
            return type;
        }

        @Override
        public Class<? extends Annotation> annotationType() {
            return RegistryType.class;
        }
    }
}
