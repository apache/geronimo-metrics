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
package org.apache.geronimo.microprofile.metrics.common.jaxrs;

import java.net.URI;
import java.security.Principal;
import java.util.List;

import javax.ws.rs.WebApplicationException;
import javax.ws.rs.core.MultivaluedMap;
import javax.ws.rs.core.PathSegment;
import javax.ws.rs.core.SecurityContext;
import javax.ws.rs.core.UriBuilder;
import javax.ws.rs.core.UriInfo;

import org.junit.Test;

public class SecurityValidatorTest {
    private static final SecurityContext ANONYMOUS = new SecurityContext() {
        @Override
        public Principal getUserPrincipal() {
            return null;
        }

        @Override
        public boolean isUserInRole(final String role) {
            return false;
        }

        @Override
        public boolean isSecure() {
            return false;
        }

        @Override
        public String getAuthenticationScheme() {
            return null;
        }
    };
    private static final SecurityContext LOGGED_NO_ROLE = new SecurityContext() {
        @Override
        public Principal getUserPrincipal() {
            return () -> "somebody";
        }

        @Override
        public boolean isUserInRole(final String role) {
            return false;
        }

        @Override
        public boolean isSecure() {
            return false;
        }

        @Override
        public String getAuthenticationScheme() {
            return null;
        }
    };
    private static final SecurityContext ADMIN = new SecurityContext() {
        @Override
        public Principal getUserPrincipal() {
            return () -> "somebody";
        }

        @Override
        public boolean isUserInRole(final String role) {
            return "admin".equals(role);
        }

        @Override
        public boolean isSecure() {
            return false;
        }

        @Override
        public String getAuthenticationScheme() {
            return null;
        }
    };
    private static final UriInfo REMOTE = uri("http://geronimo.somewhere");
    private static final UriInfo REMOTE_IP = uri("http://10.10.10.1");
    private static final UriInfo LOCALHOST = uri("http://localhost");

    @Test
    public void localValid() {
        new SecurityValidator() {{
            init();
        }}.checkSecurity(ANONYMOUS, LOCALHOST);
    }

    @Test
    public void remoteWithIpRangeValid() {
        new SecurityValidator() {
            {
                init();
            }

            @Override
            protected String config(final String key) {
                return key.endsWith("acceptedHosts") ? "[10.10.10.0..10.10.10.255]" : null;
            }
        }.checkSecurity(ANONYMOUS, REMOTE_IP);
    }

    @Test(expected = WebApplicationException.class)
    public void remoteWithIpRangeInvalid() {
        new SecurityValidator() {
            {
                init();
            }

            @Override
            protected String config(final String key) {
                return key.endsWith("acceptedHosts") ? "[20.10.10.0..20.10.10.255]" : null;
            }
        }.checkSecurity(ANONYMOUS, REMOTE_IP);
    }

    @Test(expected = WebApplicationException.class)
    public void remoteInvalid() {
        new SecurityValidator() {{
            init();
        }}.checkSecurity(ANONYMOUS, REMOTE);
    }

    @Test
    public void roleValid() {
        new SecurityValidator() {
            {
                init();
            }

            @Override
            protected String config(final String key) {
                return key.endsWith("acceptedRoles") ? "admin" : null;
            }
        }.checkSecurity(ADMIN, LOCALHOST);
    }

    @Test(expected = WebApplicationException.class)
    public void roleAnonymousInvalid() {
        new SecurityValidator() {
            {
                init();
            }

            @Override
            protected String config(final String key) {
                return key.endsWith("acceptedRoles") ? "admin" : null;
            }
        }.checkSecurity(ANONYMOUS, LOCALHOST);
    }

    @Test(expected = WebApplicationException.class)
    public void roleLoggedButInvalid() {
        new SecurityValidator() {
            {
                init();
            }

            @Override
            protected String config(final String key) {
                return key.endsWith("acceptedRoles") ? "admin" : null;
            }
        }.checkSecurity(LOGGED_NO_ROLE, LOCALHOST);
    }

    @Test
    public void roleAndHostValid() {
        new SecurityValidator() {
            {
                init();
            }

            @Override
            protected String config(final String key) {
                return key.endsWith("acceptedRoles") ? "admin" : "geronimo.somewhere";
            }
        }.checkSecurity(ADMIN, REMOTE);
    }

    @Test
    public void roleAndIpRangeValid() {
        new SecurityValidator() {
            {
                init();
            }

            @Override
            protected String config(final String key) {
                return key.endsWith("acceptedRoles") ? "admin" : key.endsWith("acceptedHosts") ? "[10.10.10.0..10.10.10.255]" : null;
            }
        }.checkSecurity(ADMIN, REMOTE_IP);
    }

    @Test(expected = WebApplicationException.class)
    public void roleAndIpRangeInvalid() {
        new SecurityValidator() {
            {
                init();
            }

            @Override
            protected String config(final String key) {
                return key.endsWith("acceptedRoles") ? "admin" : key.endsWith("acceptedHosts") ? "[20.10.10.0..20.10.10.255]" : null;
            }
        }.checkSecurity(ADMIN, REMOTE_IP);
    }

    @Test(expected = WebApplicationException.class)
    public void roleAnonymousAndIpRangeValid() {
        new SecurityValidator() {
            {
                init();
            }

            @Override
            protected String config(final String key) {
                return key.endsWith("acceptedRoles") ? "admin" : key.endsWith("acceptedHosts") ? "[10.10.10.0..10.10.10.255]" : null;
            }
        }.checkSecurity(LOGGED_NO_ROLE, REMOTE_IP);
    }

    @Test(expected = WebApplicationException.class)
    public void roleAnonymousAndIpRangeInvalid() {
        new SecurityValidator() {
            {
                init();
            }

            @Override
            protected String config(final String key) {
                return key.endsWith("acceptedRoles") ? "admin" : key.endsWith("acceptedHosts") ? "[20.10.10.0..20.10.10.255]" : null;
            }
        }.checkSecurity(LOGGED_NO_ROLE, REMOTE_IP);
    }

    private static UriInfo uri(final String request) {
        return new UriInfoMock(request);
    }

    private static class UriInfoMock implements UriInfo {
        private final URI request;

        private UriInfoMock(final String request) {
            this.request = URI.create(request);
        }

        @Override
        public String getPath() {
            return null;
        }

        @Override
        public String getPath(boolean decode) {
            return null;
        }

        @Override
        public List<PathSegment> getPathSegments() {
            return null;
        }

        @Override
        public List<PathSegment> getPathSegments(boolean decode) {
            return null;
        }

        @Override
        public URI getRequestUri() {
            return request;
        }

        @Override
        public UriBuilder getRequestUriBuilder() {
            return null;
        }

        @Override
        public URI getAbsolutePath() {
            return null;
        }

        @Override
        public UriBuilder getAbsolutePathBuilder() {
            return null;
        }

        @Override
        public URI getBaseUri() {
            return null;
        }

        @Override
        public UriBuilder getBaseUriBuilder() {
            return null;
        }

        @Override
        public MultivaluedMap<String, String> getPathParameters() {
            return null;
        }

        @Override
        public MultivaluedMap<String, String> getPathParameters(boolean decode) {
            return null;
        }

        @Override
        public MultivaluedMap<String, String> getQueryParameters() {
            return null;
        }

        @Override
        public MultivaluedMap<String, String> getQueryParameters(boolean decode) {
            return null;
        }

        @Override
        public List<String> getMatchedURIs() {
            return null;
        }

        @Override
        public List<String> getMatchedURIs(boolean decode) {
            return null;
        }

        @Override
        public List<Object> getMatchedResources() {
            return null;
        }

        @Override
        public URI resolve(URI uri) {
            return null;
        }

        @Override
        public URI relativize(URI uri) {
            return null;
        }
    }
}
