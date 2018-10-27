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
package org.apache.geronimo.microprofile.metrics.common.expdecay;

import static java.util.concurrent.TimeUnit.SECONDS;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.LongAdder;

public class ExponentialMovingAverage {
    private static final double INTERVAL = SECONDS.toNanos(5);
    private static final double RATE_RATIO = TimeUnit.SECONDS.toNanos(1);

    private final LongAdder accumulator = new LongAdder();
    private final double alpha;

    private volatile double rate = 0.0;

    private ExponentialMovingAverage(final double alpha) {
        this.alpha = alpha;
    }

    public double rate() {
        return rate * RATE_RATIO;
    }

    public void add(final long n) {
        accumulator.add(n);
    }

    public void refresh() {
        final long count = accumulator.sumThenReset();
        final double instantRate = count / INTERVAL;
        final double newRate = rate == 0. ? instantRate : nextRate(instantRate);
        this.rate = newRate;
    }

    private double nextRate(final double instantRate) {
        return rate + (alpha * (instantRate - rate));
    }

    public static ExponentialMovingAverage forMinutes(final int minutes) {
        return new ExponentialMovingAverage(Math.exp(-5/*INTERVAL in sec*/ / 60. / minutes));
    }
}
