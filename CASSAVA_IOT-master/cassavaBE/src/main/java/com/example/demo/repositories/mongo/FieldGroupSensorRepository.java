package com.example.demo.repositories.mongo;

import com.example.demo.entity.MongoEntity.FieldGroupSensor;
import org.springframework.data.mongodb.repository.MongoRepository;

import java.util.List;

public interface FieldGroupSensorRepository extends MongoRepository<FieldGroupSensor, String> {

    boolean existsByGroupIdAndSensorId(String groupId, String sensorId);

    List<FieldGroupSensor> findByGroupId(String groupId);

    void deleteByGroupIdAndSensorId(String groupId, String sensorId);

    void deleteByGroupId(String groupId);
}
