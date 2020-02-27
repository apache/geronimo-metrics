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

import static java.util.Collections.singletonList;
import static java.util.Optional.ofNullable;
import static java.util.function.Function.identity;
import static java.util.stream.Collectors.toList;

import java.math.BigInteger;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.List;
import java.util.Optional;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.stream.Stream;

import javax.ws.rs.WebApplicationException;
import javax.ws.rs.core.Response;
import javax.ws.rs.core.SecurityContext;
import javax.ws.rs.core.UriInfo;

// default let it pass locally (127.*, localhost or 1::*),
// this matches prometheus use case in general
// WARNING: ensure you accept it is public or if you are behind a proxy that you get the right hostname!
public class SecurityValidator {
    private static final Predicate<String> LOCAL_MATCHER = it ->
            it.startsWith("127.") || it.startsWith("1::") || "localhost".equals(it);

    private List<Predicate<String>> acceptedHosts;
    private List<String> acceptedRoles;

    public void init() {
        acceptedHosts = config("geronimo.metrics.jaxrs.acceptedHosts", value -> {
            if ("<local>".equals(value)) {
                return LOCAL_MATCHER;
            }
            return Optional.ofNullable(value)
                    .filter(range -> range.startsWith("[") && range.endsWith("]"))
                    .map(v -> ((Predicate<String>) ipToValidate -> {
                        return Optional.of(value)
                                .map(range -> range.subSequence(1, range.length() - 1).toString())
                                .map(rangeWithoutBraces -> rangeWithoutBraces.split("\\.\\."))
                                .filter(values -> values.length == 2)
                                .map(rangeArray -> {
                                  try {
                                    BigInteger addressMin = new BigInteger(InetAddress.getByName(rangeArray[0]).getAddress());
                                    BigInteger addressMax = new BigInteger(InetAddress.getByName(rangeArray[1]).getAddress());
                                    BigInteger addressToValidate = new BigInteger(InetAddress.getByName(ipToValidate).getAddress());
                                    return addressMin.max(addressToValidate).equals(addressMax.min(addressToValidate));
                                  } catch (UnknownHostException e) {
                                    return false;
                                  }
                                }).orElse(false);
                    })).orElse((Predicate<String>) value::equals);
        }).orElse(singletonList(LOCAL_MATCHER));
        acceptedRoles = config("geronimo.metrics.jaxrs.acceptedRoles", identity()).orElse(null);
    }

    public void checkSecurity(final SecurityContext securityContext, final UriInfo uriInfo) {
        if (acceptedHosts != null && uriInfo != null) {
            final String host = uriInfo.getRequestUri().getHost();
            if (host == null || acceptedHosts.stream().noneMatch(it -> it.test(host))) {
                throw new WebApplicationException(Response.Status.NOT_FOUND);
            }
        }
        if (!hasValidRole(securityContext)) {
            if (securityContext == null || securityContext.getUserPrincipal() == null) {
                throw new WebApplicationException(Response.Status.UNAUTHORIZED);
            }
            throw new WebApplicationException(Response.Status.FORBIDDEN);
        }
    }

    private boolean hasValidRole(final SecurityContext securityContext) {
        return acceptedRoles == null || (securityContext != null &&
                securityContext.getUserPrincipal() != null &&
                acceptedRoles.stream().anyMatch(securityContext::isUserInRole));
    }

    private <T> Optional<List<T>> config(final String key, final Function<String, T> mapper) {
        return ofNullable(config(key))
                .map(value -> Stream.of(value.split(","))
                        .map(String::trim)
                        .filter(it -> !it.isEmpty())
                        .map(mapper)
                        .collect(toList()));
    }

    protected String config(final String key) {
        return System.getProperty(key);
    }
}
