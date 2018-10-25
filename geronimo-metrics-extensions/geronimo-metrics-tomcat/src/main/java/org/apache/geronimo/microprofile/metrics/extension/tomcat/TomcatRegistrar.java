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

import java.lang.annotation.Annotation;
import java.lang.management.ManagementFactory;
import java.util.Collection;
import java.util.HashSet;
import java.util.Objects;
import java.util.ServiceLoader;
import java.util.concurrent.Executor;
import java.util.function.Consumer;
import java.util.function.Supplier;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;

import javax.management.MBeanServer;
import javax.management.ObjectInstance;
import javax.management.ObjectName;

import org.apache.catalina.Server;
import org.apache.catalina.Service;
import org.apache.catalina.connector.Connector;
import org.apache.coyote.AbstractProtocol;
import org.apache.geronimo.microprofile.metrics.extension.common.Definition;
import org.apache.geronimo.microprofile.metrics.extension.common.ThrowingSupplier;
import org.apache.tomcat.util.threads.ThreadPoolExecutor;

// this is a class working outside a MP server, don't import CDI or anything not selfcontained!
public class TomcatRegistrar {
    private final Consumer<Definition> onRegister;
    private final Consumer<Definition> onUnregister;

    public TomcatRegistrar(final Consumer<Definition> onRegister,
                           final Consumer<Definition> onUnregister) {
        this.onRegister = onRegister;
        this.onUnregister = onUnregister;
    }

    public void start() {
        final Collection<Integer> ports = new HashSet<>();
        Stream.concat(
                findServers(),
                StreamSupport.stream(ServiceLoader.load(ServerRegistration.class).spliterator(), false)
                    .map(Supplier::get))
            .filter(Objects::nonNull)
            .distinct()
            .map(Server::findServices)
            .flatMap(Stream::of)
            .map(Service::findConnectors)
            .flatMap(Stream::of)
            .map(Connector::getProtocolHandler)
            .filter(AbstractProtocol.class::isInstance)
            .map(AbstractProtocol.class::cast)
            .forEach(protocol -> {
                final Executor executor = protocol.getExecutor();
                final int port = protocol.getPort();
                if (!ports.add(port)) {
                    return;
                }
                final String prefix = "server.executor.port_" + port + ".";
                if (java.util.concurrent.ThreadPoolExecutor.class.isInstance(executor)) {
                    final java.util.concurrent.ThreadPoolExecutor pool =
                            java.util.concurrent.ThreadPoolExecutor.class.cast(executor);
                    addGauge(prefix + "queue.size", "Connector Queue Size", () -> pool.getQueue().size());
                    addGauge(prefix + "active", "Connector Active Count", pool::getActiveCount);
                    addGauge(prefix + "tasks.completed", "Connector Completed Tasks", pool::getCompletedTaskCount);
                    addGauge(prefix + "tasks.count", "Connector Tasks Count", pool::getTaskCount);
                }
                if (ThreadPoolExecutor.class.isInstance(executor)) {
                    final ThreadPoolExecutor pool = ThreadPoolExecutor.class.cast(executor);
                    addGauge(prefix + "submitted", "Connector Submitted Tasks", pool::getSubmittedCount);
                }
            });

        // plain tomcat, test on jmx, not as rich as from the instance (this is why we have a SPI)
        final MBeanServer server = ManagementFactory.getPlatformMBeanServer();
        try {
            server.queryMBeans(new ObjectName("*:type=ThreadPool,*"), null).stream()
                  .map(ObjectInstance::getObjectName)
                  .filter(it -> ports.add(getPort(it)))
                  .forEach(name -> {
                      final String prefix = "server.executor.port_" + getPort(name) + ".";
                      addGauge(prefix + "thread.count", "Connector Thread Count", () -> Number.class.cast(server.getAttribute(name, "currentThreadCount")));
                      addGauge(prefix + "active", "Connector Thread Busy", () -> Number.class.cast(server.getAttribute(name, "currentThreadsBusy")));
                  });
        } catch (final Exception e) {
            // no-op
        }
    }

    private int getPort(final ObjectName it) {
        final String name = it.getKeyPropertyList().get("name");
        final int sep = name.lastIndexOf('-');
        final String port = name.substring(sep + 1);
        return Integer.parseInt(port);
    }

    private void addGauge(final String name, final String descriptionAndDisplayName,
                          final ThrowingSupplier<Number> supplier) {
        onRegister.accept(new Definition(name, descriptionAndDisplayName, descriptionAndDisplayName, "count", supplier));
    }

    private Stream<Server> findServers() {
        return Stream.of(findMeecrowave(), findTomEE());
    }

    private Server findTomEE() {
        try {
            final ClassLoader loader = Thread.currentThread().getContextClassLoader();
            final Class<?> tomcatHelper = loader.loadClass("org.apache.tomee.loader.TomcatHelper");
            return Server.class.cast(tomcatHelper.getMethod("").invoke(null));
        } catch (final Exception | Error e) {
            return null;
        }
    }

    private Server findMeecrowave() {
        try {
            final ClassLoader loader = Thread.currentThread().getContextClassLoader();
            final Class<?> meecrowaveClass = loader.loadClass("org.apache.meecrowave.Meecrowave");
            final Class<?> cdi = loader.loadClass("javax.enterprise.inject.spi.CDI");
            final Object current = cdi.getMethod("current").invoke(null);
            final Object meecrowaveInstance = cdi.getMethod("select", Class.class, Annotation[].class)
                                     .invoke(current, meecrowaveClass, new Annotation[0]);
            final Object meecrowave = meecrowaveInstance.getClass().getMethod("get").invoke(meecrowaveInstance);
            final Object tomcat = meecrowave.getClass().getMethod("getTomcat").invoke(meecrowave);
            return Server.class.cast(tomcat.getClass().getMethod("getServer").invoke(tomcat));
        } catch (final Exception | Error e) {
            return null;
        }
    }

    public void stop() {
        // no-op for now
    }
}
