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
package org.apache.geronimo.microprofile.metrics.common;

import static java.lang.String.format;

import java.lang.management.ClassLoadingMXBean;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryMXBean;
import java.lang.management.OperatingSystemMXBean;
import java.lang.management.RuntimeMXBean;
import java.lang.management.ThreadMXBean;
import java.util.function.LongSupplier;

import javax.json.bind.annotation.JsonbTransient;

import org.eclipse.microprofile.metrics.Counter;
import org.eclipse.microprofile.metrics.Gauge;
import org.eclipse.microprofile.metrics.Metadata;
import org.eclipse.microprofile.metrics.MetricRegistry;
import org.eclipse.microprofile.metrics.MetricType;
import org.eclipse.microprofile.metrics.MetricUnits;

// isnt it super weird to hardcode that instead of defining a JMX integration?
// also the gauge/counter choice is quite surprising sometimes
public class BaseMetrics {
    private final MetricRegistry registry;

    public BaseMetrics(final MetricRegistry registry) {
        this.registry = registry;
    }

    public void register() {
        final RuntimeMXBean runtimeMXBean = ManagementFactory.getRuntimeMXBean();
        registry.register(new Metadata(
                "jvm.uptime",
                "JVM Uptime",
                "Displays the start time of the Java virtual machine in milliseconds." +
                        "This attribute displays the approximate time when the Java virtual machine started.",
                MetricType.GAUGE, MetricUnits.MILLISECONDS), gauge(runtimeMXBean::getUptime));

        final OperatingSystemMXBean operatingSystemMXBean = ManagementFactory.getOperatingSystemMXBean();
        registry.register(new Metadata(
                "cpu.availableProcessors",
                "Available Processors",
                "Displays the number of processors available to the Java virtual machine. " +
                        "This value may change during a particular invocation of the virtual machine.",
                MetricType.GAUGE, MetricUnits.NONE), gauge(operatingSystemMXBean::getAvailableProcessors));

        final ClassLoadingMXBean classLoadingMXBean = ManagementFactory.getClassLoadingMXBean();
        registry.register(new Metadata(
                "classloader.currentLoadedClass.count",
                "Current Loaded Class Count",
                "Displays the number of classes that are currently loaded in the Java virtual machine.",
                MetricType.COUNTER, MetricUnits.NONE), counter(classLoadingMXBean::getLoadedClassCount));
        registry.register(new Metadata(
                "classloader.totalLoadedClass.count",
                "Total Loaded Class Count",
                "Displays the total number of classes that have been loaded since the Java virtual machine has started execution.",
                MetricType.COUNTER, MetricUnits.NONE), counter(classLoadingMXBean::getTotalLoadedClassCount));
        registry.register(new Metadata(
                "classloader.totalUnloadedClass.count",
                "Total Unloaded Loaded Class Count",
                "Displays the total number of classes unloaded since the Java virtual machine has started execution.",
                MetricType.COUNTER, MetricUnits.NONE), counter(classLoadingMXBean::getTotalLoadedClassCount));

        final ThreadMXBean threadMXBean = ManagementFactory.getThreadMXBean();
        registry.register(new Metadata(
                "thread.count",
                "Thread Count",
                "Displays the current number of live threads including both daemon and non-daemon threads",
                MetricType.COUNTER, MetricUnits.NONE), counter(threadMXBean::getThreadCount));
        registry.register(new Metadata(
                "thread.daemon.count",
                "Daemon Thread Count",
                "Displays the current number of live daemon threads.",
                MetricType.COUNTER, MetricUnits.NONE), counter(threadMXBean::getDaemonThreadCount));
        registry.register(new Metadata(
                "thread.max.count",
                "Peak Thread Count",
                "Displays the peak live thread count since the Java virtual machine started or peak was reset." +
                        "This includes daemon and non-daemon threads.",
                MetricType.COUNTER, MetricUnits.NONE), counter(threadMXBean::getPeakThreadCount));

        final MemoryMXBean memoryMXBean = ManagementFactory.getMemoryMXBean();
        registry.register(new Metadata(
                "memory.usedHeap",
                "Used Heap Memory",
                "Displays the amount of used heap memory in bytes.",
                MetricType.GAUGE, MetricUnits.BYTES), gauge(memoryMXBean.getHeapMemoryUsage()::getUsed));
        registry.register(new Metadata(
                "memory.committedHeap",
                "Committed Heap Memory",
                "Displays the amount of memory in bytes that is committed for the Java virtual machine to use. " +
                        "This amount of memory is guaranteed for the Java virtual machine to use.",
                MetricType.GAUGE, MetricUnits.BYTES), gauge(memoryMXBean.getHeapMemoryUsage()::getCommitted));
        registry.register(new Metadata(
                "memory.maxHeap",
                "Max Heap Memory",
                "Displays the maximum amount of heap memory in bytes that can be used for memory management. " +
                        "This attribute displays -1 if the maximum heap memory size is undefined. " +
                        "This amount of memory is not guaranteed to be available for memory management if it is greater than " +
                        "the amount of committed memory. The Java virtual machine may fail to allocate memory even " +
                        "if the amount of used memory does not exceed this maximum size.",
                MetricType.GAUGE, MetricUnits.BYTES), gauge(memoryMXBean.getHeapMemoryUsage()::getMax));

        ManagementFactory.getGarbageCollectorMXBeans().forEach(garbageCollectorMXBean -> {
            registry.register(new Metadata(
                    format("gc.%s.count", garbageCollectorMXBean.getName()),
                    "Garbage Collection Count",
                    "Displays the total number of collections that have occurred." +
                            "This attribute lists -1 if the collection count is undefined for this collector.",
                    MetricType.COUNTER, MetricUnits.NONE), counter(garbageCollectorMXBean::getCollectionCount));
            registry.register(new Metadata(
                    format("gc.%s.time", garbageCollectorMXBean.getName()),
                    "Garbage Collection Time",
                    "Displays the approximate accumulated collection elapsed time in milliseconds." +
                            "This attribute displays -1 if the collection elapsed time is undefined for this collector." +
                            "The Java virtual machine implementation may use a high resolution timer to measure the elapsed time." +
                            "This attribute may display the same value even if the collection count has been incremented if" +
                            "the collection elapsed time is very short.",
                    MetricType.GAUGE, MetricUnits.MILLISECONDS), gauge(garbageCollectorMXBean::getCollectionTime));
        });
    }

    private Gauge<Long> gauge(final LongSupplier supplier) {
        return new Gauge<Long>() {
            @Override
            @JsonbTransient
            public Long getValue() {
                return supplier.getAsLong();
            }
        };
    }

    private Counter counter(final LongSupplier supplier) {
        return new Counter() {
            @Override
            public void inc() {
                // no-op
            }

            @Override
            public void inc(final long n) {
                // no-op
            }

            @Override
            public void dec() {
                dec(1);
            }

            @Override
            public void dec(final long n) {
                // no-op
            }

            @Override
            public long getCount() {
                return supplier.getAsLong();
            }
        };
    }
}
