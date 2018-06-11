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
package org.apache.geronimo.microprofile.metrics.cdi;

import java.lang.reflect.Constructor;
import java.lang.reflect.Member;

import org.eclipse.microprofile.metrics.MetricRegistry;

final class Names {
    private Names() {
        // no-op
    }

    static String findName(final Class<?> declaring, final Member executable,
                           final String annotationName, final boolean absolute) {
        if (annotationName == null || annotationName.isEmpty()) {
            if (absolute) {
                return executable.getName();
            }
            return MetricRegistry.name(declaring,
                    // bug in the JVM?
                    Constructor.class.isInstance(executable) ? executable.getDeclaringClass().getSimpleName() : executable.getName());
        } else if (absolute) {
            return annotationName;
        }
        return MetricRegistry.name(declaring, annotationName);
    }
}
