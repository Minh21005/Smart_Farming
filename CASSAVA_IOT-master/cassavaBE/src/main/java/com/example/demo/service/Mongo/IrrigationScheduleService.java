package com.example.demo.service.Mongo;

import com.example.demo.entity.MongoEntity.Field;
import com.example.demo.entity.MongoEntity.IrrigationHistory;
import com.example.demo.entity.MongoEntity.IrrigationSchedule;
import com.example.demo.entity.MongoEntity.IrrigationSchedule.Status;
import com.example.demo.mqtt.MqttCommandPublisher;
import com.example.demo.repositories.mongo.FieldMongoRepository;
import com.example.demo.repositories.mongo.IrrigationHistoryRepository;
import com.example.demo.repositories.mongo.IrrigationScheduleRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;

@Service
public class IrrigationScheduleService {

    @Autowired
    private IrrigationScheduleRepository scheduleRepository;

    @Autowired
    private FieldMongoRepository fieldMongoRepository;

    @Autowired
    private IrrigationHistoryRepository irrigationHistoryRepository;

    @Autowired
    private MqttCommandPublisher commandPublisher;

    public IrrigationSchedule create(IrrigationSchedule schedule) {
        if (schedule.getFieldId() == null || schedule.getFieldId().trim().isEmpty()) {
            throw new IllegalArgumentException("fieldId không được để trống");
        }
        Field field = fieldMongoRepository.findById(schedule.getFieldId())
                .orElseThrow(() -> new RuntimeException("Không tìm thấy cánh đồng ID: " + schedule.getFieldId()));

        if (field.isAutoIrrigation()) {
            throw new RuntimeException("Cánh đồng đang ở chế độ tưới tự động, không thể đặt lịch tưới tay");
        }

        Integer valveId = schedule.getValveId() != null ? schedule.getValveId() : field.getValveId();
        if (valveId == null || valveId < 1 || valveId > 4) {
            throw new RuntimeException("Cánh đồng chưa được gán van bơm hợp lệ (valveId 1-4)");
        }
        schedule.setValveId(valveId);

        if (schedule.getScheduledTime() == null) {
            throw new IllegalArgumentException("scheduledTime không được để trống");
        }
        if (schedule.getDurationSeconds() == null || schedule.getDurationSeconds() <= 0) {
            throw new IllegalArgumentException("durationSeconds phải > 0");
        }

        Date now = new Date();
        schedule.setId(null);
        schedule.setStatus(Status.PENDING);
        schedule.setCreatedAt(now);
        schedule.setUpdatedAt(now);
        schedule.setStartedAt(null);
        schedule.setFinishedAt(null);
        schedule.setErrorMessage(null);

        return scheduleRepository.save(schedule);
    }

    public List<IrrigationSchedule> getByFieldId(String fieldId) {
        return scheduleRepository.findByFieldIdOrderByScheduledTimeAsc(fieldId);
    }

    public List<IrrigationSchedule> getPendingByFieldId(String fieldId) {
        return scheduleRepository.findByFieldIdAndStatusOrderByScheduledTimeAsc(fieldId, Status.PENDING);
    }

    public List<IrrigationSchedule> getDuePending(Date before) {
        return scheduleRepository.findByStatusAndScheduledTimeBeforeOrderByScheduledTimeAsc(Status.PENDING, before);
    }

    public IrrigationSchedule cancel(String id) {
        IrrigationSchedule s = scheduleRepository.findById(id)
                .orElseThrow(() -> new RuntimeException("Không tìm thấy lịch tưới ID: " + id));
        if (s.getStatus() != Status.PENDING) {
            throw new RuntimeException("Chỉ có thể hủy lịch ở trạng thái PENDING (hiện tại: " + s.getStatus() + ")");
        }
        s.setStatus(Status.CANCELLED);
        s.setUpdatedAt(new Date());
        return scheduleRepository.save(s);
    }

    public IrrigationSchedule updateStatus(String id, Status status, String errorMessage) {
        IrrigationSchedule s = scheduleRepository.findById(id)
                .orElseThrow(() -> new RuntimeException("Không tìm thấy lịch tưới ID: " + id));
        Date now = new Date();
        s.setStatus(status);
        s.setUpdatedAt(now);
        if (status == Status.RUNNING && s.getStartedAt() == null) {
            s.setStartedAt(now);
        }
        if (status == Status.DONE || status == Status.FAILED || status == Status.CANCELLED) {
            s.setFinishedAt(now);
        }
        if (status == Status.FAILED || status == Status.NO_ACK) {
            s.setErrorMessage(errorMessage); // chỉ trạng thái lỗi mới mang errorMessage
        } else {
            s.setErrorMessage(null);         // chuyển sang trạng thái không-lỗi → dọn message cũ
        }
        return scheduleRepository.save(s);
    }

    public void delete(String id) {
        scheduleRepository.deleteById(id);
    }

    public IrrigationSchedule markSent(String id) {
        IrrigationSchedule s = scheduleRepository.findById(id)
                .orElseThrow(() -> new RuntimeException("Không tìm thấy lịch tưới ID: " + id));
        Date now = new Date();
        s.setStatus(Status.SENT);
        s.setSentAt(now);
        s.setUpdatedAt(now);
        return scheduleRepository.save(s);
    }

    /**
     * Edge xác nhận đã bắt đầu tưới (ack "RUNNING"): chỉ chuyển SENT → RUNNING
     * và set startedAt. Bỏ qua nếu lịch đã ở trạng thái khác (terminal / đã RUNNING)
     * để ack đến trễ không ghi đè kết quả. Sau đó completeRunningSimulations tự đẩy
     * RUNNING → DONE khi startedAt + durationSeconds trôi qua (tính amount + ghi history).
     */
    public IrrigationSchedule markRunning(String id) {
        IrrigationSchedule s = scheduleRepository.findById(id).orElse(null);
        if (s == null || s.getStatus() != Status.SENT) return s;
        Date now = new Date();
        s.setStatus(Status.RUNNING);
        if (s.getStartedAt() == null) s.setStartedAt(now);
        s.setUpdatedAt(now);
        s.setErrorMessage(null);
        return scheduleRepository.save(s);
    }

    public IrrigationSchedule handleAck(String id, boolean ok, String errorMessage) {
        if (ok) {
            return markDoneAndRecord(id);
        }
        IrrigationSchedule s = scheduleRepository.findById(id).orElse(null);
        if (s == null) return null;
        Date now = new Date();
        s.setStatus(Status.FAILED);
        s.setFinishedAt(now);
        s.setUpdatedAt(now);
        if (errorMessage != null) {
            s.setErrorMessage(errorMessage);
        }
        return scheduleRepository.save(s);
    }

    /**
     * Mark a schedule as DONE: compute the actual water amount (mm) from
     * field irrigation parameters × duration, persist it on the schedule,
     * and log a row to irrigation_history so the event surfaces in the
     * history view alongside auto-irrigation events.
     *
     * Idempotent: returns immediately if the schedule is already in a
     * terminal status (DONE/FAILED/CANCELLED/NO_ACK).
     */
    public IrrigationSchedule markDoneAndRecord(String id) {
        IrrigationSchedule s = scheduleRepository.findById(id).orElse(null);
        if (s == null) return null;
        if (s.getStatus() == Status.DONE || s.getStatus() == Status.FAILED
                || s.getStatus() == Status.CANCELLED || s.getStatus() == Status.NO_ACK) {
            return s;
        }

        Field field = fieldMongoRepository.findById(s.getFieldId()).orElse(null);
        Double amountMm = computeAmountMm(field, s.getDurationSeconds());

        Date now = new Date();
        s.setStatus(Status.DONE);
        s.setFinishedAt(now);
        s.setUpdatedAt(now);
        s.setAmount(amountMm);
        s.setErrorMessage(null); // lịch hoàn tất thì không còn lỗi tồn đọng
        IrrigationSchedule saved = scheduleRepository.save(s);

        if (field != null && amountMm != null) {
            Date eventTime = s.getStartedAt() != null ? s.getStartedAt() : now;
            String timeStr = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(eventTime);
            Double durationMinutes = s.getDurationSeconds() != null
                    ? s.getDurationSeconds() / 60.0 : null;
            IrrigationHistory history = new IrrigationHistory(
                    s.getFieldId(),
                    field.getStartTime(),
                    timeStr,
                    s.getUserName(),
                    amountMm,
                    durationMinutes);
            irrigationHistoryRepository.save(history);
        }

        return saved;
    }

    /**
     * Dừng tưới một lịch đang RUNNING: gửi lệnh CLOSE xuống edge (tắt bơm), rồi ghi
     * nhận lượng nước MỘT PHẦN theo thời gian đã tưới thực tế (startedAt → now),
     * đánh DONE và ghi history. Chỉ áp dụng cho lịch đang RUNNING.
     */
    public IrrigationSchedule stop(String id) {
        IrrigationSchedule s = scheduleRepository.findById(id)
                .orElseThrow(() -> new RuntimeException("Không tìm thấy lịch tưới ID: " + id));
        if (s.getStatus() != Status.RUNNING) {
            throw new RuntimeException("Chỉ dừng được lịch đang tưới (RUNNING). Hiện tại: " + s.getStatus());
        }
        if (s.getValveId() == null) {
            throw new RuntimeException("Lịch chưa có valveId, không gửi được lệnh dừng");
        }

        try {
            commandPublisher.publishClose(s.getFieldId(), s.getValveId(), s.getId());
        } catch (Exception e) {
            throw new RuntimeException("Không gửi được lệnh dừng tới edge: " + e.getMessage());
        }

        Field field = fieldMongoRepository.findById(s.getFieldId()).orElse(null);
        Date now = new Date();
        double elapsedSec = s.getStartedAt() != null
                ? Math.max(0.0, (now.getTime() - s.getStartedAt().getTime()) / 1000.0) : 0.0;
        Double amountMm = computeAmountMm(field, elapsedSec);

        s.setStatus(Status.DONE);
        s.setFinishedAt(now);
        s.setUpdatedAt(now);
        s.setAmount(amountMm);
        s.setErrorMessage(null);
        IrrigationSchedule saved = scheduleRepository.save(s);

        if (field != null && amountMm != null) {
            Date eventTime = s.getStartedAt() != null ? s.getStartedAt() : now;
            String timeStr = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(eventTime);
            Double durationMinutes = elapsedSec / 60.0;
            IrrigationHistory history = new IrrigationHistory(
                    s.getFieldId(),
                    field.getStartTime(),
                    timeStr,
                    s.getUserName(),
                    amountMm,
                    durationMinutes);
            irrigationHistoryRepository.save(history);
        }
        return saved;
    }

    /**
     * mm = dripRate (L/h per emitter) × durationSeconds /
     *      (3600 × distanceBetweenHole_m × distanceBetweenRow_m)
     * Returns null if any required parameter is missing or non-positive.
     */
    private Double computeAmountMm(Field field, Double durationSeconds) {
        if (field == null || durationSeconds == null || durationSeconds <= 0) return null;
        double dripRate = field.getDripRate();
        double dHole = field.getDistanceBetweenHole();
        double dRow = field.getDistanceBetweenRow();
        if (dripRate <= 0 || dHole <= 0 || dRow <= 0) return null;
        return dripRate * durationSeconds / (3600.0 * dHole * dRow);
    }

    public List<IrrigationSchedule> getStaleSent(Date sentBefore) {
        return scheduleRepository.findByStatusAndSentAtBeforeOrderBySentAtAsc(Status.SENT, sentBefore);
    }

    public List<IrrigationSchedule> getRunning() {
        return scheduleRepository.findByStatus(Status.RUNNING);
    }
}
