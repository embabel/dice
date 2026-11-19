/*
 * Copyright 2024-2026 Embabel Pty Ltd.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package example.dice.mcp;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.tomcat.TomcatWebServer;
import org.springframework.boot.web.server.autoconfigure.ServerProperties;
import org.springframework.boot.web.server.context.WebServerApplicationContext;

import java.net.InetAddress;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The server checks no identity, so with the app's own settings it must only listen on this
 * machine. Without an address Tomcat would listen on every interface, and anyone on the same
 * network could read the memories.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class LoopbackBindTests {

    @Autowired
    ServerProperties serverProperties;

    @Autowired
    WebServerApplicationContext context;

    @Test
    void isConfiguredToListenOnLoopbackOnly() {
        InetAddress address = serverProperties.getAddress();

        assertThat(address).as("server.address").isNotNull();
        assertThat(address.isLoopbackAddress()).as("%s is a loopback address", address).isTrue();
    }

    @Test
    void theRunningServerListensOnLoopbackOnly() {
        assertThat(context.getWebServer()).isInstanceOf(TomcatWebServer.class);
        TomcatWebServer server = (TomcatWebServer) context.getWebServer();

        // The connector's own address is what Tomcat actually bound, whatever the properties say.
        Object bound = server.getTomcat().getConnector().getProperty("address");

        assertThat(bound).as("connector address").isInstanceOf(InetAddress.class);
        assertThat(((InetAddress) bound).isLoopbackAddress())
                .as("%s is a loopback address", bound)
                .isTrue();
    }
}
