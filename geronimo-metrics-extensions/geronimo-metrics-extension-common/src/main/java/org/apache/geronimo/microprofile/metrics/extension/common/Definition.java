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

import java.util.Objects;
import java.util.function.DoubleSupplier;

public class Definition {
    private final String name;
    private final String displayName;
    private final String description;
    private final String unit;
    private final DoubleSupplier evaluator;

    private final int hash;

    public Definition(final String name, final String displayName, final String description,
                       final String unit, final ThrowingSupplier<Number> evaluator) {
        this.name = name;
        this.displayName = displayName;
        this.description = description;
        this.unit = unit;
        this.evaluator = () -> {
            try {
                return evaluator.get().doubleValue();
            } catch (final Throwable throwable) {
                return -1;
            }
        };

        this.hash = Objects.hash(name);
    }

    public String getName() {
        return name;
    }

    public String getDisplayName() {
        return displayName;
    }

    public String getDescription() {
        return description;
    }

    public String getUnit() {
        return unit;
    }

    public DoubleSupplier getEvaluator() {
        return evaluator;
    }

    @Override
    public boolean equals(final Object that) {
        if (this == that) {
            return true;
        }
        if (that == null || getClass() != that.getClass()) {
            return false;
        }
        return Objects.equals(name, Definition.class.cast(that).name);
    }

    @Override
    public int hashCode() {
        return hash;
    }
}
