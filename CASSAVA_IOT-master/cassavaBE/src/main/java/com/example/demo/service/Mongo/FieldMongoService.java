package com.example.demo.service.Mongo;

import com.example.demo.entity.MongoEntity.Field;
import com.example.demo.entity.MongoEntity.FieldSimulationResult;
import com.example.demo.entity.MongoEntity.IrrigationHistory;
import com.example.demo.entity.MongoEntity.SensorValue;
import com.example.demo.repositories.UserRepository;
import com.example.demo.repositories.mongo.FieldGroupRepository;
import com.example.demo.repositories.mongo.FieldMongoRepository;
import com.example.demo.repositories.mongo.FieldSimulationResultRepository;
import com.example.demo.repositories.mongo.IrrigationHistoryRepository;
import com.example.demo.repositories.mongo.IrrigationScheduleRepository;
import com.example.demo.repositories.mongo.SensorValueRepository;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Date;
import java.util.List;

@Service
public class FieldMongoService {

    @Autowired
    private FieldMongoRepository fieldRepository;

    @Autowired
    private FieldGroupRepository fieldGroupRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private SensorValueRepository sensorValueRepository;

    @Autowired
    private FieldSimulationResultRepository simulationResultRepository;

    @Autowired
    private IrrigationHistoryRepository irrigationHistoryRepository;

    @Autowired
    private IrrigationScheduleRepository irrigationScheduleRepository;

    @Autowired
    private FieldSensorService fieldSensorService;

    @Autowired
    private com.example.demo.repositories.mongo.FieldSensorRepository fieldSensorRepository;

    // ========================
    // CREATE (FIXED)
    // ========================
    public Field create(Field field) {

        if (field.getIdUser() == null || !userRepository.existsById(field.getIdUser())) {
            throw new RuntimeException("Invalid user");
        }
        if (field.getName() == null || field.getName().trim().isEmpty()) {
            throw new IllegalArgumentException("Tên cánh đồng không được để trống");
        }
        if (fieldRepository.existsByName(field.getName())) {
            throw new RuntimeException("Tên cánh đồng '" + field.getName() + "' đã tồn tại trong hệ thống");
        }
        if (field.getGroupId() == null || field.getGroupId().trim().isEmpty()
                || !fieldGroupRepository.existsById(field.getGroupId())) {
            throw new RuntimeException("Cánh đồng phải thuộc một nhóm (groupId) hợp lệ");
        }

        validateField(field);
        field.setMode(normalizeMode(field.getMode()));

        field.setId(null);
        if (field.getStartTime() == null) {
            field.setStartTime(new Date());
        }

        Field saved = fieldRepository.save(field);
        fieldSensorService.initDefaultSensors(saved.getId());
        return saved;
    }

    private String normalizeMode(String mode) {
        if (mode == null) return "SIMULATION";
        String m = mode.trim().toUpperCase();
        if (!m.equals("SIMULATION") && !m.equals("OPERATION")) {
            throw new RuntimeException("mode chỉ nhận giá trị SIMULATION hoặc OPERATION");
        }
        return m;
    }
    public Field clone (String srcFieldId, String newName) {
        if (newName == null || newName.trim().isEmpty()) {
            throw new IllegalArgumentException("Tên cánh đồng mới không được để trống");
        }
        Field sourceField = fieldRepository.findById(srcFieldId)
                .orElseThrow(() -> new RuntimeException("Không tìm thấy cánh đồng gốc ID: " + srcFieldId));

        if (fieldRepository.existsByName(newName)) {
            throw new RuntimeException("Tên cánh đồng '" + newName + "' đã tồn tại trong hệ thống");
        }
        Field newField = new Field();
        BeanUtils.copyProperties(sourceField, newField);

        newField.setId(null);
        newField.setName(newName);
        newField.setIrrigating(false);

        Field saved = fieldRepository.save(newField);
        String newFieldId = saved.getId();

        fieldSensorService.initDefaultSensors(newFieldId);

        List<SensorValue> sensorValues = sensorValueRepository.findByFieldId(srcFieldId);
        List<SensorValue> clonedSensorValues = new ArrayList<>(sensorValues.size());
        for (SensorValue sv : sensorValues) {
            SensorValue copy = new SensorValue(newFieldId, sv.getSensorId(), sv.getValue(), sv.getTime());
            clonedSensorValues.add(copy);
        }
        sensorValueRepository.saveAll(clonedSensorValues);

        List<FieldSimulationResult> simResults = simulationResultRepository.findByFieldIdOrderByTimeAsc(srcFieldId);
        List<FieldSimulationResult> clonedSimResults = new ArrayList<>(simResults.size());
        for (FieldSimulationResult r : simResults) {
            clonedSimResults.add(new FieldSimulationResult(
                    newFieldId, r.getCropStartTime(), r.getTime(), r.getYield(), r.getIrrigation(),
                    r.getLeafArea(), r.getLabileCarbon()));
        }
        simulationResultRepository.saveAll(clonedSimResults);

        List<IrrigationHistory> histories = irrigationHistoryRepository.findByFieldIdOrderByTimeDesc(srcFieldId);
        List<IrrigationHistory> clonedHistories = new ArrayList<>(histories.size());
        for (IrrigationHistory h : histories) {
            clonedHistories.add(new IrrigationHistory(
                    newFieldId, h.getCropStartTime(), h.getTime(), h.getUserName(), h.getAmount(), h.getDuration()));
        }
        irrigationHistoryRepository.saveAll(clonedHistories);

        return saved;
    }

    private void validateField(Field field) {

        if (field.getAcreage() <= 0) {
            throw new RuntimeException("Acreage must be > 0");
        }

        if (field.getFieldCapacity() <= 0 || field.getFieldCapacity() > 100) {
            throw new RuntimeException("FieldCapacity must be between 0 and 100");
        }

        if (field.getDripRate() <= 0) {
            throw new RuntimeException("DripRate must be > 0");
        }

        if (field.getNumberOfHoles() <= 0) {
            throw new RuntimeException("NumberOfHoles must be > 0");
        }

        if (field.getScaleRain() < 0) {
            throw new RuntimeException("ScaleRain must be >= 0");
        }
    }

    // ========================
    // GET BY ID
    // ========================
    public Field getById(String id) {
        return fieldRepository.findById(id).orElse(null);
    }

    // ========================
    // GET ALL
    // ========================
    public List<Field> getAll() {
        return fieldRepository.findAll();
    }

    // ========================
    // GET BY USER
    // ========================
    public List<Field> getByUser(String userId) {
        return fieldRepository.findByIdUser(userId);
    }

    // ========================
    // UPDATE
    // ========================
    public Field update(String id, Field newData) {

        Field old = getById(id);
        if (old == null) return null;

        // 🔥 nếu đổi user → validate lại
        if (!userRepository.existsById(newData.getIdUser())) {
            throw new RuntimeException("User not found");
        }

        if (newData.getGroupId() == null || newData.getGroupId().trim().isEmpty()
                || !fieldGroupRepository.existsById(newData.getGroupId())) {
            throw new RuntimeException("Cánh đồng phải thuộc một nhóm (groupId) hợp lệ");
        }

        old.setIdUser(newData.getIdUser());
        old.setGroupId(newData.getGroupId());
        old.setName(newData.getName());
        old.setAcreage(newData.getAcreage());
        old.setAutoIrrigation(newData.isAutoIrrigation());
        old.setMode(normalizeMode(newData.getMode()));

        old.setFieldCapacity(newData.getFieldCapacity());
        old.setIrrigationDuration(newData.getIrrigationDuration());

        old.setDistanceBetweenRow(newData.getDistanceBetweenRow());
        old.setDistanceBetweenHole(newData.getDistanceBetweenHole());

        old.setDripRate(newData.getDripRate());
        old.setScaleRain(newData.getScaleRain());

        old.setNumberOfHoles(newData.getNumberOfHoles());

        old.setFertilizationLevel(newData.getFertilizationLevel());
        old.setDAP(newData.getDAP());

        old.setStartTime(newData.getStartTime());
        old.setEndTime(newData.getEndTime());

        old.setIrrigating(newData.isIrrigating());

        old.setValveId(newData.getValveId());

        return fieldRepository.save(old);
    }

    // ========================
    // DELETE
    // ========================
    public void delete(String id) {
        fieldSensorRepository.deleteByFieldId(id);
        sensorValueRepository.deleteByFieldId(id);
        simulationResultRepository.deleteByFieldId(id);
        irrigationHistoryRepository.deleteByFieldId(id);
        irrigationScheduleRepository.deleteByFieldId(id);
        fieldRepository.deleteById(id);
    }

    // ========================
    // RESET CROP CYCLE ("Mùa mới")
    // Clears per-crop data (sensor values, simulation results, irrigation history)
    // and resets the Field's per-crop state. Keeps field config and sensor mappings.
    // ========================
    public Field resetCropCycle(String id, Date startTime, Date endTime) {
        Field field = fieldRepository.findById(id)
                .orElseThrow(() -> new RuntimeException("Không tìm thấy cánh đồng ID: " + id));

        if (startTime != null && endTime != null && endTime.before(startTime)) {
            throw new IllegalArgumentException("Ngày kết thúc vụ không được trước ngày bắt đầu");
        }

        // Giữ nguyên lịch sử của các vụ trước.
        // Vụ mới được phân biệt bằng startTime mới — simulation_result / irrigation_history
        // sẽ được tag bởi cropStartTime tại thời điểm ghi.
        field.setStartTime(startTime != null ? startTime : new Date());
        field.setEndTime(endTime); // null = vụ đang chạy
        field.setDAP(1);
        field.setIrrigating(false);

        return fieldRepository.save(field);
    }
}