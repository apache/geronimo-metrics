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
package org.apache.geronimo.microprofile.metrics.extension.sigar;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.logging.Logger;

import org.hyperic.jni.ArchLoaderException;
import org.hyperic.jni.ArchNotSupportedException;
import org.hyperic.sigar.Sigar;
import org.hyperic.sigar.SigarException;
import org.hyperic.sigar.SigarLoader;

// note: for some integration the license is GPL3 so we can't provide it
//       -> user will have to download sigar-dist, unpack it and point to the folder using
//       $ -Dorg.hyperic.sigar.path=/path/to/folder/with/native/integs
public class InitSigar {

    private boolean valid;

    private final File tempDir;

    public InitSigar(final File tempDir) {
        this.tempDir = tempDir;
    }

    public void ensureSigarIsSetup() {
        valid = true;

        final SigarLoader loader = new SigarLoader(Sigar.class);
        try {
            loadFromPath(loader);
        } catch (final UnsatisfiedLinkError e) {
            unavailable(e.getMessage());
        }
    }

    private void loadFromPath(final SigarLoader loader) {
        try {
            final String systemProp = loader.getPackageName() + ".path";
            final String path = System.getProperty(systemProp);
            if (path == null) {
                final String libraryName = loader.getLibraryName();
                final File output = new File(tempDir, "sigar/" + libraryName);
                if (!output.exists()) {
                    final int dot = libraryName.lastIndexOf('.');
                    final String resourceName = libraryName.substring(0, dot) + "-"
                            + System.getProperty("sigar.version", "1.6.4") + libraryName.substring(dot);
                    try (final InputStream stream = Thread.currentThread().getContextClassLoader()
                                                          .getResourceAsStream(resourceName)) {
                        if (stream != null) {
                            output.getParentFile().mkdirs();
                            Files.copy(stream, output.toPath(), StandardCopyOption.REPLACE_EXISTING);
                        } else {
                            unavailable("native library not found in the classloader as " + resourceName);
                            return;
                        }
                        loader.load(output.getParentFile().getAbsolutePath());
                        afterLoad(systemProp);
                    } catch (final ArchLoaderException | IOException ex) {
                        unavailable(ex.getMessage());
                    }
                }
            } else if (!"-".equals(path)) {
                try {
                    loader.load(path);
                    afterLoad(systemProp);
                } catch (final ArchLoaderException ex) {
                    unavailable(ex.getMessage());
                }
            }
        } catch (final ArchNotSupportedException ex) {
            unavailable(ex.getMessage());
        }
    }

    private void unavailable(final String message) {
        Logger.getLogger(InitSigar.class.getName()).info("Sigar is not available: " + message);
        valid = false;
    }

    private void afterLoad(final String systemProp) {
        // ensure it works
        final String original = System.getProperty(systemProp);
        System.setProperty(systemProp, "-");
        try {
            testItWorks();
        } catch (final Throwable throwable) {
            unavailable(throwable.getMessage());
            if (original == null) {
                System.clearProperty(systemProp);
            } else {
                System.setProperty(systemProp, original);
            }
        }
    }

    private void testItWorks() throws SigarException {
        final Sigar sigar = new Sigar();
        sigar.getCpu();
        sigar.close();
    }

    public boolean isValid() {
        return valid;
    }
}
