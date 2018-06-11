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
package org.apache.geronimo.microprofile.metrics.impl;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;

import org.eclipse.microprofile.metrics.Gauge;

public class GaugeImpl<T> implements Gauge<T> {
    private final Method method;
    private final Object reference;

    public GaugeImpl(final Object reference, final Method method) {
        this.method = method;
        this.reference = reference;
    }

    @Override
    public T getValue() {
        try {
            return (T) method.invoke(reference);
        } catch (final IllegalAccessException e) {
            throw new IllegalStateException(e);
        } catch (final InvocationTargetException e) {
            throw new IllegalStateException(e.getCause());
        }
    }
}
