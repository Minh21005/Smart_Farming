package com.example.demo.service.Mongo;

import com.example.demo.entity.Field;
import com.example.demo.entity.MongoEntity.FieldSimulationResult;
import com.example.demo.entity.MongoEntity.IrrigationHistory;
import com.example.demo.repositories.mongo.FieldMongoRepository;
import com.example.demo.repositories.mongo.FieldSimulationResultRepository;
import com.example.demo.repositories.mongo.IrrigationHistoryRepository;
import com.example.demo.service.Mongo.SensorValueService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.*;

@Service
public class FieldSimulator {

    private static final Logger log = LoggerFactory.getLogger(FieldSimulator.class);

    @Autowired
    private SensorValueService sensorValueService;

    @Autowired
    private FieldSimulationResultRepository simulationResultRepository;

    @Autowired
    private IrrigationHistoryRepository irrigationHistoryRepository;

    @Autowired
    private FieldMongoRepository fieldMongoRepository;

    // Daily at 07:00 and 17:00 (server local time)
    @Scheduled(cron = "0 0 7,17 * * *", zone = "Asia/Ho_Chi_Minh")
    public void runScheduledSimulationForAllFields() {
        List<com.example.demo.entity.MongoEntity.Field> fields = fieldMongoRepository.findAll();
        log.info("Scheduled simulation starting for {} fields", fields.size());

        int ok = 0;
        int failed = 0;
        for (com.example.demo.entity.MongoEntity.Field f : fields) {
            String fieldId = f.getId();
            try {
                runSimulation(fieldId);
                ok++;
            } catch (Exception e) {
                failed++;
                log.error("Scheduled simulation failed for fieldId={}: {}", fieldId, e.getMessage());
            }
        }
        log.info("Scheduled simulation finished: ok={}, failed={}", ok, failed);
    }

    public Map<String, Object> runSimulation(String fieldId) throws IOException {
        // 1. Nạp cấu hình cánh đồng (cần groupId để lấy dữ liệu thời tiết chung)
        com.example.demo.entity.MongoEntity.Field cfg = fieldMongoRepository.findById(fieldId)
                .orElseThrow(() -> new RuntimeException("Field not found: " + fieldId));

        if (cfg.getGroupId() == null || cfg.getGroupId().trim().isEmpty()) {
            throw new RuntimeException("Cánh đồng chưa được gán vào nhóm (groupId) nào");
        }

        // 2. Lấy dữ liệu thời tiết chung của nhóm từ MongoDB, giới hạn theo [startTime, endTime]
        // endTime null = vụ đang chạy → dùng now()
        Date rangeStart = cfg.getStartTime();
        Date rangeEnd = cfg.getEndTime() != null ? cfg.getEndTime() : new Date();
        if (rangeStart != null && rangeEnd.before(rangeStart)) {
            throw new RuntimeException("Ngày kết thúc vụ không được trước ngày bắt đầu");
        }
        List<String> combinedData = sensorValueService.getCombinedValues(cfg.getGroupId(), rangeStart, rangeEnd);

        if (combinedData == null || combinedData.isEmpty()) {
            throw new RuntimeException("Không có dữ liệu cảm biến cho nhóm trong khoảng vụ ["
                    + rangeStart + " → " + rangeEnd + "]");
        }

        Field field = new Field(cfg.getName());
        field.acreage             = cfg.getAcreage();
        field.fieldCapacity       = cfg.getFieldCapacity();
        field.distanceBetweenRow  = cfg.getDistanceBetweenRow() * 100;  // m → cm
        field.distanceBetweenHole = cfg.getDistanceBetweenHole() * 100; // m → cm
        field.dripRate            = cfg.getDripRate();
        field.numberOfHoles       = cfg.getNumberOfHoles();
        field.fertilizationLevel  = cfg.getFertilizationLevel();
        field.irrigationDuration  = cfg.getIrrigationDuration();
        field.scaleRain           = cfg.getScaleRain();
        field.autoIrrigation      = cfg.isAutoIrrigation();

        // 3. Nạp dữ liệu vào Model
        field.loadAllWeatherDataFromMongo(combinedData);

        // 4. Chạy mô phỏng
        field.runModel();

        // 5. Anchor theo startTime của vụ — không còn phụ thuộc vào row weather đầu tiên.
        // Nếu startTime null (data cũ) thì fallback về row weather đầu tiên cho tương thích ngược.
        // Lưu ý: Field.getDoy() trả về DOY 0-based (Jan 1 = 0), còn LocalDate.getDayOfYear()
        // trả về 1-based (Jan 1 = 1) → phải -1 để khớp với _results[8] (lưu `t` từ getDoy).
        Date cropStartTime = cfg.getStartTime();
        LocalDate baseDate;
        int baseDoy;
        if (cropStartTime != null) {
            baseDate = cropStartTime.toInstant().atZone(ZoneId.systemDefault()).toLocalDate();
            baseDoy = baseDate.getDayOfYear() - 1;
        } else {
            String firstTimeStr = Field._weatherData.get(0).get(0).toString();
            baseDate = LocalDate.parse(firstTimeStr.substring(0, 10));
            baseDoy = (int) Math.floor(Double.parseDouble(Field._weatherData.get(0).get(1).toString()));
        }

        // 6. Lưu kết quả vào MongoDB
        List<FieldSimulationResult> savedResults = saveResultsToMongo(fieldId, cropStartTime, field, baseDate, baseDoy);

        // 7. Lưu lịch sử tưới nếu autoIrrigation
        List<IrrigationHistory> irrigationRecords = saveIrrigationHistory(fieldId, cropStartTime, field, baseDate, baseDoy);

        // 8. Trả về kết quả
        Map<String, Object> result = new HashMap<>();
        result.put("status", "success");
        result.put("message", "Mô phỏng hoàn tất cho field: " + fieldId);
        result.put("dataPoints", combinedData.size());
        result.put("simulationResults", savedResults.size());
        result.put("irrigationRecords", irrigationRecords.size());

        return result;
    }

    private List<IrrigationHistory> saveIrrigationHistory(String fieldId, Date cropStartTime, Field field, LocalDate baseDate, int baseDoy) {
        if (!field.autoIrrigation || field.listHistory.isEmpty()) {
            return Collections.emptyList();
        }

        // Chỉ xóa lịch sử của vụ hiện tại — các vụ cũ giữ nguyên
        if (cropStartTime != null) {
            irrigationHistoryRepository.deleteByFieldIdAndCropStartTime(fieldId, cropStartTime);
        } else {
            irrigationHistoryRepository.deleteByFieldId(fieldId);
        }

        List<IrrigationHistory> toSave = new ArrayList<>();

        // Use exact irrigation events recorded during simulation (Field.java line 794-815)
        for (com.example.demo.entity.HistoryIrrigation h : field.listHistory) {
            double amountMm = h.getAmount(); // simulation model already produces mm
            double duration = h.getDuration(); // already in minutes

            // Use the raw simulation time `t` as the date source — same anchor formula
            // simulation_result uses. Field.java's `time` string is a fake-2024 calendar
            // that loses information past day 365 and on leap-year boundaries; do not parse it.
            double tRaw = h.getSimulationT() != null ? h.getSimulationT() : 0.0;
            int dayOffset = (int) Math.floor(tRaw);
            LocalDate correctedDate = baseDate.plusDays(dayOffset - baseDoy);
            double fractionDay = tRaw - Math.floor(tRaw);
            int hours = (int) (fractionDay * 24);
            int minutes = (int) ((fractionDay * 24 - hours) * 60);
            String correctedTime = String.format("%s %02d:%02d:00", correctedDate, hours, minutes);

            toSave.add(new IrrigationHistory(fieldId, cropStartTime, correctedTime, h.getUserName(), amountMm, duration));
        }

        if (toSave.isEmpty()) {
            return Collections.emptyList();
        }

        return irrigationHistoryRepository.saveAll(toSave);
    }

    private List<FieldSimulationResult> saveResultsToMongo(String fieldId, Date cropStartTime, Field field, LocalDate baseDate, int baseDoy) {
        List<List<Double>> results = field._results;

        if (results == null || results.isEmpty() || results.get(0).isEmpty()) {
            return Collections.emptyList();
        }

        // Chỉ xóa kết quả của vụ hiện tại — các vụ cũ giữ nguyên
        if (cropStartTime != null) {
            simulationResultRepository.deleteByFieldIdAndCropStartTime(fieldId, cropStartTime);
        } else {
            simulationResultRepository.deleteByFieldId(fieldId);
        }

        List<FieldSimulationResult> toSave = new ArrayList<>();

        for (int i = 1; i < results.get(0).size(); i++) {
            int doy = (int) Math.floor(results.get(8).get(i));
            LocalDate localDate = baseDate.plusDays(doy - baseDoy);
            Date time = Date.from(localDate.atStartOfDay(ZoneId.systemDefault()).toInstant());

            double yield = results.get(0).get(i);
            double irrigation = results.get(2).get(i); // mm, from simulation model
            double leafArea = results.get(3).get(i);
            double labileCarbon = results.get(4).get(i);

            toSave.add(new FieldSimulationResult(fieldId, cropStartTime, time, yield, irrigation, leafArea, labileCarbon));
        }

        return simulationResultRepository.saveAll(toSave);
    }
}
