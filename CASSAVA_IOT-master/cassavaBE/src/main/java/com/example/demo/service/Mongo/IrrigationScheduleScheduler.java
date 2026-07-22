package com.example.demo.service.Mongo;

import com.example.demo.entity.MongoEntity.Field;
import com.example.demo.entity.MongoEntity.IrrigationSchedule;
import com.example.demo.entity.MongoEntity.IrrigationSchedule.Status;
import com.example.demo.mqtt.MqttCommandPublisher;
import com.example.demo.repositories.mongo.FieldMongoRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.Date;
import java.util.List;

@Component
public class IrrigationScheduleScheduler {

    private static final Logger log = LoggerFactory.getLogger(IrrigationScheduleScheduler.class);

    @Autowired
    private IrrigationScheduleService scheduleService;

    @Autowired
    private FieldMongoRepository fieldRepository;

    @Autowired
    private MqttCommandPublisher publisher;

    @Value("${mqtt.operation.ack-timeout-seconds:60}")
    private long ackTimeoutSeconds;

    @Scheduled(fixedDelay = 15_000L)
    public void publishDueSchedules() {
        Date now = new Date();
        List<IrrigationSchedule> due = scheduleService.getDuePending(now);
        for (IrrigationSchedule s : due) {
            Field field = fieldRepository.findById(s.getFieldId()).orElse(null);
            if (field == null) continue;

            if ("SIMULATION".equalsIgnoreCase(field.getMode())) {
                scheduleService.updateStatus(s.getId(), Status.RUNNING, null);
                log.info("Schedule {} (SIMULATION) → RUNNING (no MQTT)", s.getId());
                continue;
            }

            try {
                publisher.publishOpenTimed(s.getFieldId(), s.getValveId(), s.getId(),
                        (int) Math.round(s.getDurationSeconds()));
                scheduleService.markSent(s.getId());
            } catch (Exception e) {
                log.warn("Publish schedule {} failed: {}", s.getId(), e.getMessage());
            }
        }
    }

    @Scheduled(fixedDelay = 15_000L)
    public void completeRunningSimulations() {
        long nowMs = System.currentTimeMillis();
        List<IrrigationSchedule> running = scheduleService.getRunning();
        for (IrrigationSchedule s : running) {
            if (s.getStartedAt() == null || s.getDurationSeconds() == null) continue;
            long finishMs = s.getStartedAt().getTime() + (long) (s.getDurationSeconds() * 1000);
            if (nowMs < finishMs) continue;

            scheduleService.markDoneAndRecord(s.getId());
            log.info("Schedule {} RUNNING → DONE (startedAt + duration elapsed)", s.getId());
        }
    }

    @Scheduled(fixedDelay = 30_000L)
    public void markStaleAsNoAck() {
        Date cutoff = new Date(System.currentTimeMillis() - ackTimeoutSeconds * 1000L);
        List<IrrigationSchedule> stale = scheduleService.getStaleSent(cutoff);
        for (IrrigationSchedule s : stale) {
            scheduleService.updateStatus(s.getId(), Status.NO_ACK,
                    "Edge không phản hồi sau " + ackTimeoutSeconds + "s");
            log.warn("Schedule {} → NO_ACK (sentAt={})", s.getId(), s.getSentAt());
        }
    }
}
