package com.example.demo.mqtt;

import org.eclipse.paho.client.mqttv3.MqttClient;
import org.eclipse.paho.client.mqttv3.MqttConnectOptions;
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class MqttConfig {

    private static final Logger log = LoggerFactory.getLogger(MqttConfig.class);

    @Value("${mqtt.operation.broker-url:tcp://localhost:1883}")
    private String brokerUrl;

    @Value("${mqtt.operation.client-id:cassava-be}")
    private String clientId;

    @Value("${mqtt.operation.username:}")
    private String username;

    @Value("${mqtt.operation.password:}")
    private String password;

    @Bean(destroyMethod = "disconnectForcibly")
    public MqttClient operationMqttClient() {
        try {
            MqttClient client = new MqttClient(brokerUrl, clientId, new MemoryPersistence());

            MqttConnectOptions opts = new MqttConnectOptions();
            opts.setAutomaticReconnect(true);
            opts.setCleanSession(false);
            opts.setConnectionTimeout(10);
            opts.setKeepAliveInterval(30);
            if (username != null && !username.isEmpty()) {
                opts.setUserName(username);
                opts.setPassword(password.toCharArray());
            }

            try {
                client.connect(opts);
                log.info("MQTT operation broker connected: {}", brokerUrl);
            } catch (Exception e) {
                log.warn("MQTT operation broker connect failed ({}). Auto-reconnect armed; publish/subscribe will retry.",
                        e.getMessage());
            }

            return client;
        } catch (Exception e) {
            throw new IllegalStateException("Cannot create MQTT client for " + brokerUrl, e);
        }
    }
}
