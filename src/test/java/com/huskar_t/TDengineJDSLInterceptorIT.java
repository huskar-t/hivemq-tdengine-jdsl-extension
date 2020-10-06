
/*
 * Copyright 2018-present HiveMQ GmbH
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.huskar_t;

import com.hivemq.client.mqtt.MqttGlobalPublishFilter;
import com.hivemq.client.mqtt.mqtt5.Mqtt5BlockingClient;
import com.hivemq.client.mqtt.mqtt5.Mqtt5Client;
import com.hivemq.client.mqtt.mqtt5.message.publish.Mqtt5Publish;
import com.hivemq.extension.sdk.api.annotations.NotNull;
import com.hivemq.testcontainer.core.MavenHiveMQExtensionSupplier;
import com.hivemq.testcontainer.junit5.HiveMQTestContainerExtension;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.extension.RegisterExtension;

import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;


/**
 * This tests the functionality of the {@link TDengineJDSLInterceptor}.
 * It uses the HiveMQ Testcontainer to automatically package and deploy this extension inside a HiveMQ docker container.
 * Should deploy the TDengine database first https://www.taosdata.com/cn/getting-started/
 * Modify the tdengine.xml
 * The simplest way to test is using http (sdk need shared memory and config FQDN)
 */
class TDengineJDSLInterceptorIT {

    @RegisterExtension
    public final @NotNull HiveMQTestContainerExtension extension =
            new HiveMQTestContainerExtension()
                    .withExtension(MavenHiveMQExtensionSupplier.direct().get());

    @Test
    @Timeout(value = 5, unit = TimeUnit.MINUTES)
    void test_kv() throws InterruptedException {
        final Mqtt5BlockingClient client = Mqtt5Client.builder()
                .identifier("test-tdengine-hivemq")
                .serverPort(extension.getMqttPort())
                .buildBlocking();
        client.connect();

        final Mqtt5BlockingClient.Mqtt5Publishes publishes = client.publishes(MqttGlobalPublishFilter.ALL);
        client.subscribeWith().topicFilter("jdsl/test_kv").send();
        client.publishWith().topic("jdsl/test_kv").payload("{\"temperature\": \"15\",\"humidity\": \"17\"}".getBytes(StandardCharsets.UTF_8)).send();
        Mqtt5Publish receive = publishes.receive();
        assertTrue(receive.getPayload().isPresent());
        assertEquals("{\"temperature\": \"15\",\"humidity\": \"17\"}", new String(receive.getPayloadAsBytes(), StandardCharsets.UTF_8));
    }

    @Test
    @Timeout(value = 5, unit = TimeUnit.MINUTES)
    void test_iot() throws InterruptedException {
        final Mqtt5BlockingClient client = Mqtt5Client.builder()
                .identifier("test-tdengine-hivemq")
                .serverPort(extension.getMqttPort())
                .buildBlocking();
        client.connect();

        final Mqtt5BlockingClient.Mqtt5Publishes publishes = client.publishes(MqttGlobalPublishFilter.ALL);
        client.subscribeWith().topicFilter("jdsl/test_iot").send();
        client.publishWith().topic("jdsl/test_iot").payload("{ \"device\": \"d1\",\"point\": {\"pointName\": \"sunshine\",\"value\": [92,93,94]}}".getBytes(StandardCharsets.UTF_8)).send();
        Mqtt5Publish receive = publishes.receive();
        assertTrue(receive.getPayload().isPresent());
        assertEquals("{ \"device\": \"d1\",\"point\": {\"pointName\": \"sunshine\",\"value\": [92,93,94]}}", new String(receive.getPayloadAsBytes(), StandardCharsets.UTF_8));
    }

    @Test
    @Timeout(value = 5, unit = TimeUnit.MINUTES)
    void test_topic() throws InterruptedException {
        final Mqtt5BlockingClient client = Mqtt5Client.builder()
                .identifier("test-tdengine-hivemq")
                .serverPort(extension.getMqttPort())
                .buildBlocking();
        client.connect();

        final Mqtt5BlockingClient.Mqtt5Publishes publishes = client.publishes(MqttGlobalPublishFilter.ALL);
        client.subscribeWith().topicFilter("jdsl/test_topic/test").send();
        client.publishWith().topic("jdsl/test_topic/test").payload("\"test_value\"".getBytes(StandardCharsets.UTF_8)).send();
        Mqtt5Publish receive = publishes.receive();
        assertTrue(receive.getPayload().isPresent());
        assertEquals("\"test_value\"", new String(receive.getPayloadAsBytes(), StandardCharsets.UTF_8));
    }
}