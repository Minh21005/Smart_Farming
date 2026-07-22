package com.example.demo.mqtt;

public final class MqttTopics {

    private MqttTopics() {}

    public static String commandTopic(String fieldId, int valveId) {
        return String.format("cassava/field/%s/valve/%d/cmd", fieldId, valveId);
    }

    public static String ackTopic(String fieldId, int valveId) {
        return String.format("cassava/field/%s/valve/%d/ack", fieldId, valveId);
    }

    public static final String ACK_WILDCARD = "cassava/field/+/valve/+/ack";

    public static final String ACTION_OPEN_TIMED = "OPEN_TIMED";
    public static final String ACTION_CLOSE = "CLOSE";

    public static final String ACK_DONE = "DONE";
    public static final String ACK_FAILED = "FAILED";
    public static final String ACK_RUNNING = "RUNNING";
}
