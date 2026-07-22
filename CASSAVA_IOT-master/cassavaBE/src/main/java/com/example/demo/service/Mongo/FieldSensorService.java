package com.example.demo.service.Mongo;

import com.example.demo.entity.MongoEntity.FieldSensor;
import com.example.demo.repositories.mongo.FieldSensorRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class FieldSensorService {

    public static final List<String> FIELD_SENSOR_IDS = List.of(
            "humidity30",
            "humidity60"
    );

    @Autowired
    private FieldSensorRepository repository;

    public void initDefaultSensors(String fieldId) {
        for (String sensorId : FIELD_SENSOR_IDS) {
            if (!repository.existsByFieldIdAndSensorId(fieldId, sensorId)) {
                repository.save(new FieldSensor(fieldId, sensorId));
            }
        }
    }

    // ========================
    // ADD SENSOR
    // ========================
    public String addSensor(String fieldId, String sensorId) {

        if (repository.existsByFieldIdAndSensorId(fieldId, sensorId)) {
            return "Sensor already exists";
        }

        FieldSensor fs = new FieldSensor();
        fs.setFieldId(fieldId);
        fs.setSensorId(sensorId);

        repository.save(fs);

        return "Sensor added";
    }

    // ========================
    // REMOVE SENSOR
    // ========================
    public String removeSensor(String fieldId, String sensorId) {

        if (!repository.existsByFieldIdAndSensorId(fieldId, sensorId)) {
            return "Sensor not found";
        }

        repository.deleteByFieldIdAndSensorId(fieldId, sensorId);

        return "Sensor removed";
    }

    // ========================
    // GET ALL SENSOR
    // ========================
    public List<FieldSensor> getSensors(String fieldId) {
        return repository.findByFieldId(fieldId);
    }
}