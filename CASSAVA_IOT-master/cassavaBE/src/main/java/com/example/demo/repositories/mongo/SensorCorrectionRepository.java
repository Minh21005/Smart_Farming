package com.example.demo.repositories.mongo;

import com.example.demo.entity.MongoEntity.SensorCorrection;
import org.springframework.data.mongodb.repository.MongoRepository;

import java.util.Date;
import java.util.List;

public interface SensorCorrectionRepository extends MongoRepository<SensorCorrection, String> {

    List<SensorCorrection> findByGroupIdAndSensorIdOrderByTimeDesc(String groupId, String sensorId);

    List<SensorCorrection> findByGroupIdAndSensorIdAndTimeBetweenOrderByTimeAsc(
            String groupId, String sensorId, Date start, Date end
    );

    void deleteByGroupId(String groupId);

    void deleteByFieldId(String fieldId);
}
