package com.example.demo.mqtt;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.eclipse.paho.client.mqttv3.MqttClient;
import org.eclipse.paho.client.mqttv3.MqttException;
import org.eclipse.paho.client.mqttv3.MqttMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

@Service
public class MqttCommandPublisher {

    private static final Logger log = LoggerFactory.getLogger(MqttCommandPublisher.class);

    @Autowired
    private MqttClient mqttClient;

    private final ObjectMapper mapper = new ObjectMapper();

    public void publishOpenTimed(String fieldId, int valveId, String scheduleId, int durationSeconds)
            throws MqttException, com.fasterxml.jackson.core.JsonProcessingException {

        OperationCommand cmd = new OperationCommand(
                scheduleId,
                MqttTopics.ACTION_OPEN_TIMED,
                durationSeconds,
                System.currentTimeMillis()
        );

        String topic = MqttTopics.commandTopic(fieldId, valveId);
        byte[] payload = mapper.writeValueAsBytes(cmd);

        MqttMessage msg = new MqttMessage(payload);
        msg.setQos(1);
        msg.setRetained(false);

        mqttClient.publish(topic, msg);
        log.info("MQTT publish {} → schedule={}, dur={}s", topic, scheduleId, durationSeconds);
    }

    public void publishClose(String fieldId, int valveId, String scheduleId)
            throws MqttException, com.fasterxml.jackson.core.JsonProcessingException {

        OperationCommand cmd = new OperationCommand(
                scheduleId,
                MqttTopics.ACTION_CLOSE,
                null,
                System.currentTimeMillis()
        );

        String topic = MqttTopics.commandTopic(fieldId, valveId);
        byte[] payload = mapper.writeValueAsBytes(cmd);

        MqttMessage msg = new MqttMessage(payload);
        msg.setQos(1);
        msg.setRetained(false);

        mqttClient.publish(topic, msg);
        log.info("MQTT publish {} → CLOSE schedule={}", topic, scheduleId);
    }
}
