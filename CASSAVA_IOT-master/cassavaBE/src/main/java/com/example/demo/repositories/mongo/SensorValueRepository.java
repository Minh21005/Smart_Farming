package com.example.demo.repositories.mongo;

import com.example.demo.entity.MongoEntity.SensorValue;
import org.springframework.data.mongodb.repository.MongoRepository;

import java.time.LocalDateTime;
import java.util.Date;
import java.util.List;

public interface SensorValueRepository extends MongoRepository<SensorValue, String> {

    List<SensorValue> findByFieldId(String fieldId);

    List<SensorValue> findByFieldIdAndSensorId(String fieldId, String sensorId);

    List<SensorValue> findTop1ByFieldIdAndSensorIdOrderByTimeDesc(String fieldId, String sensorId);

    List<SensorValue> findBySensorIdAndTimeBetweenOrderByTimeAsc(
        String sensorId,
        Date start,
        Date end
    );
    // Lấy danh sách giá trị theo Field và loại Sensor, sắp xếp thời gian mới nhất lên đầu
    List<SensorValue> findByFieldIdAndSensorIdOrderByTimeDesc(String fieldId, String sensorId);

    void deleteByFieldId(String fieldId);

    List<SensorValue> findByGroupId(String groupId);

    List<SensorValue> findByGroupIdAndSensorIdOrderByTimeDesc(String groupId, String sensorId);

    void deleteByGroupId(String groupId);
}