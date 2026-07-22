package com.example.demo.entity.MongoEntity;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.mapping.Document;

import java.util.Date;

@Document(collection = "sensor_value")
public class SensorValue {

    @Id
    private String id;

    private String groupId;

    private String fieldId;

    private String sensorId; // temperature, humidity, rain...

    private double value;

    private Date time;

    private String source; // "mqtt" | "predicted" | "nasa"

    public SensorValue() {}

    public SensorValue(String fieldId, String sensorId, double value, Date time) {
        this.fieldId = fieldId;
        this.sensorId = sensorId;
        this.value = value;
        this.time = time;
    }

    public static SensorValue forGroup(String groupId, String sensorId, double value, Date time) {
        SensorValue v = new SensorValue();
        v.groupId = groupId;
        v.sensorId = sensorId;
        v.value = value;
        v.time = time;
        return v;
    }

    public String getSource() {
        return source;
    }

    public void setSource(String source) {
        this.source = source;
    }

    public String getId() {
        return id;
    }

    public String getGroupId() {
        return groupId;
    }

    public void setGroupId(String groupId) {
        this.groupId = groupId;
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

    public double getValue() {
        return value;
    }

    public void setValue(double value) {
        this.value = value;
    }

    public Date getTime() {
        return time;
    }

    public void setTime(Date time) {
        this.time = time;
    }
}
