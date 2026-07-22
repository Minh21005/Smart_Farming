package com.example.demo.entity.MongoEntity;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

@Document(collection = "field_group_sensor")
public class FieldGroupSensor {

    @Id
    private String id;

    private String groupId;

    private String sensorId;

    public FieldGroupSensor() {}

    public FieldGroupSensor(String groupId, String sensorId) {
        this.groupId = groupId;
        this.sensorId = sensorId;
    }

    public String getId() { return id; }

    public String getGroupId() { return groupId; }
    public void setGroupId(String groupId) { this.groupId = groupId; }

    public String getSensorId() { return sensorId; }
    public void setSensorId(String sensorId) { this.sensorId = sensorId; }
}
