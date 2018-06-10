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
            return MetricRegistry.name(declaring,
                    // bug in the JVM?
                    Constructor.class.isInstance(executable) ? executable.getDeclaringClass().getSimpleName() : executable.getName());
        } else if (absolute) {
            return annotationName;
        }
        return MetricRegistry.name(declaring, annotationName);
    }
}
