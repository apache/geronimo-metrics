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
import static org.hyperic.sigar.SigarProxyCache.EXPIRE_DEFAULT;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.function.DoubleSupplier;
import java.util.stream.IntStream;
import java.util.stream.Stream;

import org.apache.geronimo.microprofile.metrics.extension.common.Definition;
import org.apache.geronimo.microprofile.metrics.extension.common.ThrowingSupplier;
import org.hyperic.sigar.Cpu;
import org.hyperic.sigar.CpuInfo;
import org.hyperic.sigar.Sigar;
import org.hyperic.sigar.SigarException;
import org.hyperic.sigar.SigarProxy;
import org.hyperic.sigar.SigarProxyCache;

// important: this class is stack agnostic and must not use cdi or anything else
public class SigarRegistrar {
    private final Consumer<Definition> onRegister;
    private final Consumer<Definition> onUnregister;
    private Sigar sigarImpl;
    private SigarProxy sigar;
    private Thread refreshThread;
    private volatile boolean stopped = true;
    private long refreshInterval;
    private final Map<String, Definition> currentDefinitions = new HashMap<>();

    public SigarRegistrar(final Consumer<Definition> onRegister,
                           final Consumer<Definition> onUnregister) {
        this.onRegister = onRegister;
        this.onUnregister = onUnregister;
    }

    public synchronized void start() {
        this.sigarImpl = new Sigar();
        this.sigar = SigarProxyCache.newInstance(sigarImpl, Integer.getInteger("geronimo.metrics.sigar.cache", EXPIRE_DEFAULT));

        refreshInterval = Long.getLong("geronimo.metrics.sigar.refreshInterval", TimeUnit.MINUTES.toMillis(5));
        if (refreshInterval > 0) {
            refreshThread = new Thread(() -> {
                final long iterationDuration = 250;
                final long iterations = refreshInterval / iterationDuration;
                while (!stopped) {
                    for (long i = 0; i < iterations; i++) {
                        if (stopped) {
                            return;
                        }
                        try {
                            Thread.sleep(iterationDuration);
                        } catch (final InterruptedException e) {
                            Thread.currentThread().interrupt();
                        }
                    }
                    tick();
                }
            }, getClass().getName() + "-refresher-" + hashCode());
            stopped = false;
            refreshThread.start();
        }
        tick();
    }

    public synchronized void tick() {
        final Collection<Definition> currentMetrics = collectMetrics();
        final Collection<Definition> alreadyRegistered = currentMetrics.stream()
                .filter(it -> currentDefinitions.containsKey(it.getName()))
                .collect(toList());
        final Collection<Definition> missingRegistered = new ArrayList<>(currentDefinitions.values());
        missingRegistered.removeAll(alreadyRegistered);

        // remove no more accurate metrics
        missingRegistered.forEach(it -> {
            currentDefinitions.remove(it.getName());
            if (onUnregister != null) {
                onUnregister.accept(it);
            }
        });

        // register new metrics
        currentMetrics.removeAll(alreadyRegistered);
        currentMetrics.forEach(it -> onRegister.accept(new Definition(
            it.getName(), it.getDisplayName(), it.getDescription(), it.getUnit(),
            () -> it.getEvaluator().getAsDouble())));
    }

    public synchronized void stop() {
        if (refreshThread != null) {
            stopped = true;
            try {
                refreshThread.join(500);
                if (refreshThread.isAlive()) {
                    refreshThread.interrupt();
                }
            } catch (final InterruptedException e) {
                Thread.currentThread().interrupt();
            } finally {
                refreshThread = null;
            }
        }
        sigarImpl.close();
    }

    private Collection<Definition> collectMetrics() {
        final Collection<Definition> definitions = new ArrayList<>();
        
        // global
        addCpu(definitions, "sigar.cpu.", () -> sigar.getCpu());
        addMem(definitions);

        // individual CPU
        try {
            final CpuInfo[] cpuInfoList = sigar.getCpuInfoList();
            IntStream.range(0, cpuInfoList.length)
                     .forEach(idx -> addCpu(definitions, "sigar.cpu." + idx + ".", () -> sigar.getCpuList()[idx]));
        } catch (final SigarException se) {
            // ignore
        }

        // network
        addNetwork(definitions);

        // filesystem
        addFileSystem(definitions);

        return definitions;
    }

    private void addFileSystem(final Collection<Definition> definitions) {
        try {
            Stream.of(sigar.getFileSystemList())
                  .filter(it -> !it.getDirName().startsWith("/sys") &&
                            !it.getDirName().startsWith("/dev") &&
                            !it.getDirName().startsWith("/proc") &&
                            !it.getDirName().startsWith("/run") &&
                            !it.getDirName().startsWith("/snap"))
                  .forEach(fs -> {
                final String devName = fs.getDevName();
                final String baseName = "sigar.net.disk." + devName.replace('/', '_').replaceFirst("^_", "") + ".";
                definitions.add(new Definition(
                        baseName + "read.count", devName + " Reads",
                        "Reads on " + devName, "count",
                        () -> sigar.getDiskUsage(devName).getReads()));
                definitions.add(new Definition(
                        baseName + "write.count", devName + " Writes",
                        "Writes on " + devName, "count",
                        () -> sigar.getDiskUsage(devName).getWrites()));
                definitions.add(new Definition(
                        baseName + "read.bytes", devName + " Reads",
                        "Reads on " + devName, "bytes",
                        () -> sigar.getDiskUsage(devName).getReadBytes()));
                definitions.add(new Definition(
                        baseName + "write.bytes", devName + " Writes",
                        "Writes on " + devName, "bytes",
                        () -> sigar.getDiskUsage(devName).getWriteBytes()));
            });
        } catch (final SigarException e) {
            // no-op
        }
    }

    private void addNetwork(final Collection<Definition> definitions) {
        try {
            sigar.getTcp(); // ensure it is allowed+available
            definitions.add(new Definition("sigar.network.tcp.active.opens", "Opening connections",
                    "Active connections openings", "count",
                    () -> sigar.getTcp().getActiveOpens()));
            definitions.add(new Definition("sigar.network.tcp.passive.opens", "Passive connections",
                    "Passive connection openings", "count",
                    () -> sigar.getTcp().getPassiveOpens()));
            definitions.add(new Definition("sigar.network.tcp.attempts.fails", "Failed connections",
                    "Failed connection attempts", "count",
                    () -> sigar.getTcp().getAttemptFails()));
            definitions.add(new Definition("sigar.network.tcp.established.reset", "Resetted connections",
                    "Connection resets received", "count",
                    () -> sigar.getTcp().getEstabResets()));
            definitions.add(new Definition("sigar.network.tcp.established.current", "Established connections",
                    "Connections established", "count",
                    () -> sigar.getTcp().getCurrEstab()));
            definitions.add(new Definition("sigar.network.tcp.segments.in", "Received segments",
                    "Received segments", "count",
                    () -> sigar.getTcp().getInSegs()));
            definitions.add(new Definition("sigar.network.tcp.segments.out", "Sent segments",
                    "Send out segments", "count",
                    () -> sigar.getTcp().getOutSegs()));
            definitions.add(new Definition("sigar.network.tcp.segments.retrans", "Retransmitted segments",
                    "Retransmitted segments", "count",
                    () -> sigar.getTcp().getRetransSegs()));
            definitions.add(new Definition("sigar.network.tcp.resets.out", "Sent resets",
                    "Sent resets", "count",
                    () -> sigar.getTcp().getOutRsts()));
        } catch (final Exception | Error  notAvailable) {
            // no-op
        }
        try {
            sigar.getNetStat();
            definitions.add(new Definition("sigar.network.tcp.output.total", "Total Outbound",
                    "Sent bytes", "bytes",
                    () -> sigar.getNetStat().getTcpOutboundTotal()));
            definitions.add(new Definition("sigar.network.tcp.inbound.total", "Total Inbound",
                    "Received bytes", "bytes",
                    () -> sigar.getNetStat().getTcpInboundTotal()));
            definitions.add(new Definition("sigar.network.tcp.established", "TCP established",
                    "TCP established", "count",
                    () -> sigar.getNetStat().getTcpEstablished()));
            definitions.add(new Definition("sigar.network.tcp.idle", "TCP Idle",
                    "TCP Idle", "count",
                    () -> sigar.getNetStat().getTcpIdle()));
            definitions.add(new Definition("sigar.network.tcp.closing", "TCP Closing",
                    "TCP Closing", "count",
                    () -> sigar.getNetStat().getTcpClosing()));
            definitions.add(new Definition("sigar.network.tcp.bound", "TCP Bound",
                    "TCP Bound", "count",
                    () -> sigar.getNetStat().getTcpBound()));
            definitions.add(new Definition("sigar.network.tcp.close", "TCP Close",
                    "TCP Close", "count",
                    () -> sigar.getNetStat().getTcpClose()));
            definitions.add(new Definition("sigar.network.tcp.closewait", "TCP Close Wait",
                    "TCP Close Wait", "count",
                    () -> sigar.getNetStat().getTcpCloseWait()));
            definitions.add(new Definition("sigar.network.tcp.listen", "TCP Listen",
                    "TCP Listen", "count",
                    () -> sigar.getNetStat().getTcpListen()));
        } catch (final Exception | Error  notAvailable) {
            // no-op
        }
    }

    private void addMem(final Collection<Definition> definitions) {
        definitions.add(new Definition(
                "sigar.mem.ram", "System RAM Memory",
                "The total amount of physical memory, in [bytes]", "bytes",
                () -> sigar.getMem().getRam()));
        definitions.add(new Definition(
                "sigar.mem.total", "System Total Memory",
                "The amount of physical memory, in [bytes]", "bytes",
                () -> sigar.getMem().getTotal()));
        definitions.add(new Definition(
                "sigar.mem.used", "System Used Memory",
                "The amount of physical memory in use, in [bytes]", "bytes",
                () -> sigar.getMem().getUsed()));
        definitions.add(new Definition(
                "sigar.mem.free", "System Free Memory",
                "The amount of free physical memory, in [bytes]", "bytes",
                () -> sigar.getMem().getFree()));
        definitions.add(new Definition(
                "sigar.mem.actual.used", "System Actual Used Memory",
                "The actual amount of physical memory in use, in [bytes]", "bytes",
                () -> sigar.getMem().getActualUsed()));
        definitions.add(new Definition(
                "sigar.mem.actual.free", "System Actual Free Memory",
                "The actual amount of free physical memory, in [bytes]", "bytes",
                () -> sigar.getMem().getActualFree()));
    }

    private void addCpu(final Collection<Definition> definitions,
                        final String base,
                        final ThrowingSupplier<Cpu> provider) {
        definitions.add(new Definition(
                base + "idle", "CPU Idle Time",
                "The idle time of the CPU, in [ms]", "ms",
                () -> provider.get().getIdle()));
        definitions.add(new Definition(
                base + "nice", "CPU Nice Priority Time",
                "The time of the CPU spent on nice priority, in [ms]", "ms",
                () -> provider.get().getNice()));
        definitions.add(new Definition(
                base + "sys", "CPU User Time",
                "The time of the CPU used by the system, in [ms]", "ms",
                () -> provider.get().getSys()));
        definitions.add(new Definition(
                base + "total", "CPU Total Time",
                "The total time of the CPU, in [ms]", "ms",
                () -> provider.get().getTotal()));
        definitions.add(new Definition(
                base + "wait", "CPU Wait Time",
                "The time the CPU had to wait for data to be loaded, in [ms]", "ms",
                () -> provider.get().getWait()));
    }
}
