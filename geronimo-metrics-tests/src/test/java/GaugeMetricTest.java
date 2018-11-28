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

import org.apache.meecrowave.Meecrowave;
import org.apache.meecrowave.junit.MeecrowaveRule;
import org.junit.After;
import org.junit.Before;
import org.junit.ClassRule;
import org.junit.Test;

import javax.ws.rs.client.Client;
import javax.ws.rs.client.ClientBuilder;
import javax.ws.rs.client.WebTarget;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;

import static org.junit.Assert.assertEquals;

public class GaugeMetricTest {

    private WebTarget target;
    private Client client;

    @ClassRule
    public static final MeecrowaveRule MEECROWAVE = new MeecrowaveRule(new Meecrowave.Builder() {{
        setTomcatNoJmx(false);
    }}, "");


    @Before
    public void before() {
        client = ClientBuilder.newBuilder().build();
        target = client.target("http://" + MEECROWAVE.getConfiguration().getHost() + ":" + MEECROWAVE.getConfiguration().getHttpPort());
    }

    @After
    public void after() {
        client.close();
    }

    @Test
    public void testMetricGaugeJson() {
        Response response = target.path("/weather/temperature").request().get();
        assertEquals(200, response.getStatus());

        String gauge = target.path("/metrics/application/temperature")
                .request()
                .accept(MediaType.APPLICATION_JSON)
                .get(String.class);

        assertEquals("{\"temperature\":30}", gauge);
    }

    @Test
    public void testMetricGaugePrometheus() {
        Response response = target.path("/weather/temperature").request().get();
        assertEquals(200, response.getStatus());

        String gauge = target.path("/metrics/application/temperature")
                .request()
                .accept(MediaType.TEXT_PLAIN)
                .get(String.class);

        assertEquals("# TYPE application:temperature_celsius gauge\napplication:temperature_celsius{weather=\"temperature\"} 30.0\n", gauge);
    }
}
