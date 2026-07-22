package com.example.demo.repositories.mongo;

import com.example.demo.entity.MongoEntity.FieldSensor;
import org.springframework.data.mongodb.repository.MongoRepository;

import java.util.List;

public interface FieldSensorRepository extends MongoRepository<FieldSensor, String> {

    boolean existsByFieldIdAndSensorId(String fieldId, String sensorId);

    List<FieldSensor> findByFieldId(String fieldId);

    void deleteByFieldIdAndSensorId(String fieldId, String sensorId);

    void deleteByFieldId(String fieldId);
}