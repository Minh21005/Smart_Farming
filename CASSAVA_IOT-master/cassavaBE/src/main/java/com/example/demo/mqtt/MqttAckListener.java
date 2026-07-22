package com.example.demo.mqtt;

import com.example.demo.service.Mongo.IrrigationScheduleService;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import org.eclipse.paho.client.mqttv3.MqttClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

@Service
public class MqttAckListener {

    private static final Logger log = LoggerFactory.getLogger(MqttAckListener.class);

    @Autowired
    private MqttClient mqttClient;

    @Autowired
    private IrrigationScheduleService scheduleService;

    private final ObjectMapper mapper = new ObjectMapper();

    @PostConstruct
    public void subscribe() {
        try {
            mqttClient.subscribe(MqttTopics.ACK_WILDCARD, 1, (topic, message) -> {
                try {
                    OperationAck ack = mapper.readValue(message.getPayload(), OperationAck.class);
                    log.info("MQTT ack {}: schedule={}, ack={}", topic, ack.getScheduleId(), ack.getAck());
                    if (MqttTopics.ACK_RUNNING.equalsIgnoreCase(ack.getAck())) {
                        // ack sớm "đang tưới" → SENT → RUNNING, thoát timeout NO_ACK 60s
                        scheduleService.markRunning(ack.getScheduleId());
                    } else {
                        boolean ok = MqttTopics.ACK_DONE.equalsIgnoreCase(ack.getAck());
                        scheduleService.handleAck(ack.getScheduleId(), ok, ack.getErrorMessage());
                    }
                } catch (Exception e) {
                    log.warn("Bad ack payload on {}: {}", topic, e.getMessage());
                }
            });
            log.info("MQTT subscribed to ack wildcard {}", MqttTopics.ACK_WILDCARD);
        } catch (Exception e) {
            log.warn("MQTT ack subscribe failed ({}). Will retry on reconnect.", e.getMessage());
        }
    }
}
