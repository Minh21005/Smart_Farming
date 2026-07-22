package com.example.demo.entity.MongoEntity;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

@Document(collection = "field_sensor")
public class FieldSensor {

    @Id
    private String id;

    private String fieldId;

    private String sensorId;

    public FieldSensor() {}

    public FieldSensor(String fieldId, String sensorId) {
        this.fieldId = fieldId;
        this.sensorId = sensorId;
    }

    public String getId() {
        return id;
    }

    public String getFieldId() {
        return fieldId;
    }

    public void setFieldId(String fieldId) {
        this.fieldId = fieldId;
    }

    public String getSensorId() {
        return sensorId;
    }

    public void setSensorId(String sensorId) {
        this.sensorId = sensorId;
    }
}