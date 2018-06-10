package org.apache.geronimo.microprofile.metrics.test;

import java.lang.reflect.Field;

import javax.enterprise.context.spi.CreationalContext;
import javax.enterprise.inject.spi.AnnotatedType;
import javax.enterprise.inject.spi.BeanManager;
import javax.enterprise.inject.spi.CDI;
import javax.enterprise.inject.spi.InjectionTarget;

import org.apache.catalina.Context;
import org.apache.meecrowave.Meecrowave;
import org.apache.meecrowave.arquillian.MeecrowaveContainer;
import org.jboss.arquillian.container.spi.client.container.DeployableContainer;
import org.jboss.arquillian.container.spi.context.annotation.DeploymentScoped;
import org.jboss.arquillian.container.spi.event.container.AfterDeploy;
import org.jboss.arquillian.container.spi.event.container.BeforeUnDeploy;
import org.jboss.arquillian.container.test.impl.client.protocol.local.LocalProtocol;
import org.jboss.arquillian.container.test.spi.client.deployment.ApplicationArchiveProcessor;
import org.jboss.arquillian.container.test.spi.client.deployment.DeploymentPackager;
import org.jboss.arquillian.container.test.spi.client.protocol.Protocol;
import org.jboss.arquillian.core.api.InstanceProducer;
import org.jboss.arquillian.core.api.annotation.Inject;
import org.jboss.arquillian.core.api.annotation.Observes;
import org.jboss.arquillian.core.spi.LoadableExtension;
import org.jboss.arquillian.test.spi.TestClass;
import org.jboss.arquillian.test.spi.event.suite.Before;
import org.jboss.shrinkwrap.api.Archive;
import org.jboss.shrinkwrap.api.ShrinkWrap;
import org.jboss.shrinkwrap.api.spec.JavaArchive;
import org.jboss.shrinkwrap.api.spec.WebArchive;

public class ArquillianSetup implements LoadableExtension {
    @Override
    public void register(final ExtensionBuilder extensionBuilder) {
        extensionBuilder.observer(EnvSetup.class)
                .override(DeployableContainer.class, MeecrowaveContainer.class, TckContainer.class)
                .override(Protocol.class, LocalProtocol.class, ForceWarProtocol.class)
                .service(ApplicationArchiveProcessor.class, EnsureTestIsInTheArchiveProcessor.class);
    }

    public static class EnvSetup {
        @Inject
        @DeploymentScoped
        private InstanceProducer<BeanManager> beanManagerInstanceProducer;

        @Inject
        @DeploymentScoped
        private InstanceProducer<ClassLoader> appClassLoaderInstanceProducer;

        @Inject
        @DeploymentScoped
        private InstanceProducer<ClassLoader> testClassLoaderInstanceProducer;

        public void onDeploy(@Observes final AfterDeploy afterDeploy) {
            final DeployableContainer<?> deployableContainer = afterDeploy.getDeployableContainer();
            try {
                final Field container = MeecrowaveContainer.class.getDeclaredField("container");
                container.setAccessible(true);
                final Meecrowave meecrowave = Meecrowave.class.cast(container.get(deployableContainer));
                final ClassLoader appLoader = Context.class.cast(meecrowave.getTomcat().getHost().findChildren()[0]).getLoader().getClassLoader();
                final Thread thread = Thread.currentThread();
                appClassLoaderInstanceProducer.set(appLoader);
                testClassLoaderInstanceProducer.set(meecrowave.getTomcat().getServer().getParentClassLoader());
                thread.setContextClassLoader(appLoader);
                beanManagerInstanceProducer.set(CDI.current().getBeanManager());
            } catch (final Exception e) {
                // no-op, will not happen and if so it is another container
            }
        }

        public void onUndeploy(@Observes final BeforeUnDeploy beforeUnDeploy) {
            final ClassLoader cl = testClassLoaderInstanceProducer.get();
            Thread.currentThread().setContextClassLoader(cl);
        }

        public void enrich(@Observes final Before before) {
            final Thread thread = Thread.currentThread();
            final ClassLoader classLoader = thread.getContextClassLoader();
            thread.setContextClassLoader(appClassLoaderInstanceProducer.get());
            try {
                final BeanManager beanManager = beanManagerInstanceProducer.get();
                final Object testInstance = before.getTestInstance();
                final AnnotatedType<?> annotatedType = beanManager.createAnnotatedType(before.getTestClass().getJavaClass());
                final InjectionTarget injectionTarget = beanManager.createInjectionTarget(annotatedType);
                final CreationalContext<?> creationalContext = beanManager.createCreationalContext(null);
                injectionTarget.inject(testInstance, creationalContext);
                creationalContext.release();
            } finally {
                thread.setContextClassLoader(classLoader);
            }
        }
    }

    public static class ForceWarProtocol extends LocalProtocol {
        @Override
        public DeploymentPackager getPackager() {
            return (deployment, collection) -> {
                final Archive<?> archive = deployment.getApplicationArchive();
                if (JavaArchive.class.isInstance(archive)) {
                    return ShrinkWrap.create(WebArchive.class, archive.getName().replace(".jar", ".war"))
                            .addAsLibraries(archive);
                }
                return archive;
            };
        }
    }

    public static class EnsureTestIsInTheArchiveProcessor implements ApplicationArchiveProcessor {
        @Override
        public void process(final Archive<?> archive, final TestClass testClass) {
            if (JavaArchive.class.isInstance(archive) && !archive.contains(testClass.getName().replace('.', '/') + ".class")) {
                JavaArchive.class.cast(archive).addClass(testClass.getJavaClass());
            }
        }
    }
}
