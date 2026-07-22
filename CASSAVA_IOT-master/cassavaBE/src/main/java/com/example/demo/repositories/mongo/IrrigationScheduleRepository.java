package com.example.demo.repositories.mongo;

import com.example.demo.entity.MongoEntity.IrrigationSchedule;
import com.example.demo.entity.MongoEntity.IrrigationSchedule.Status;
import org.springframework.data.mongodb.repository.MongoRepository;

import java.util.Date;
import java.util.List;

public interface IrrigationScheduleRepository extends MongoRepository<IrrigationSchedule, String> {

    List<IrrigationSchedule> findByFieldIdOrderByScheduledTimeAsc(String fieldId);

    List<IrrigationSchedule> findByFieldIdAndStatusOrderByScheduledTimeAsc(String fieldId, Status status);

    List<IrrigationSchedule> findByStatusAndScheduledTimeBeforeOrderByScheduledTimeAsc(Status status, Date before);

    List<IrrigationSchedule> findByStatusAndSentAtBeforeOrderBySentAtAsc(Status status, Date sentBefore);

    List<IrrigationSchedule> findByStatus(Status status);

    void deleteByFieldId(String fieldId);
}
