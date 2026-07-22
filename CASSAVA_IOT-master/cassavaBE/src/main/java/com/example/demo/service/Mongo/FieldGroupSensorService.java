package com.example.demo.service.Mongo;

import com.example.demo.entity.MongoEntity.FieldGroupSensor;
import com.example.demo.repositories.mongo.FieldGroupSensorRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class FieldGroupSensorService {

    @Autowired
    private FieldGroupSensorRepository repository;

    public String addSensor(String groupId, String sensorId) {
        if (repository.existsByGroupIdAndSensorId(groupId, sensorId)) {
            return "Sensor already exists";
        }

        FieldGroupSensor fs = new FieldGroupSensor(groupId, sensorId);
        repository.save(fs);

        return "Sensor added";
    }

    public String removeSensor(String groupId, String sensorId) {
        if (!repository.existsByGroupIdAndSensorId(groupId, sensorId)) {
            return "Sensor not found";
        }

        repository.deleteByGroupIdAndSensorId(groupId, sensorId);

        return "Sensor removed";
    }

    public List<FieldGroupSensor> getSensors(String groupId) {
        return repository.findByGroupId(groupId);
    }
}
