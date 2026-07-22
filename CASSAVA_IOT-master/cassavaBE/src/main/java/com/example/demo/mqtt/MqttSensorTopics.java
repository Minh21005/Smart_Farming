package com.example.demo.mqtt;

public final class MqttSensorTopics {

    private MqttSensorTopics() {}

    public static final String DEFAULT_WEATHER_TOPIC = "/sensor/weatherStation2";

    public static final String SENSOR_KEY_TEMPERATURE = "t";
    public static final String SENSOR_KEY_RELATIVE_HUMIDITY = "h";
    public static final String SENSOR_KEY_RADIATION = "rad";
    public static final String SENSOR_KEY_RAIN = "rai";
    public static final String SENSOR_KEY_WIND = "w";

    public static String resolveSensorId(String key) {
        switch (key) {
            case SENSOR_KEY_TEMPERATURE:       return "temperature";
            case SENSOR_KEY_RELATIVE_HUMIDITY: return "relativeHumidity";
            case SENSOR_KEY_RADIATION:         return "radiation";
            case SENSOR_KEY_RAIN:              return "rain";
            case SENSOR_KEY_WIND:              return "wind";
            default:                           return key;
        }
    }
}
