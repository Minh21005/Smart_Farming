package com.example.demo.service.Mongo;

import com.example.demo.entity.MongoEntity.FieldGroup;
import com.example.demo.entity.MongoEntity.FieldGroupSensor;
import com.example.demo.repositories.UserRepository;
import com.example.demo.repositories.mongo.FieldGroupRepository;
import com.example.demo.repositories.mongo.FieldGroupSensorRepository;
import com.example.demo.repositories.mongo.FieldMongoRepository;
import com.example.demo.repositories.mongo.SensorValueRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.Date;
import java.util.List;

@Service
public class FieldGroupService {

    public static final List<String> GROUP_SENSOR_IDS = List.of(
            "temperature",
            "relativeHumidity",
            "rain",
            "radiation",
            "wind"
    );

    @Autowired
    private FieldGroupRepository groupRepository;

    @Autowired
    private FieldGroupSensorRepository groupSensorRepository;

    @Autowired
    private FieldMongoRepository fieldRepository;

    @Autowired
    private SensorValueRepository sensorValueRepository;

    @Autowired
    private UserRepository userRepository;

    public FieldGroup create(FieldGroup g) {
        if (g.getName() == null || g.getName().trim().isEmpty()) {
            throw new IllegalArgumentException("Tên nhóm không được để trống");
        }
        if (groupRepository.existsByName(g.getName())) {
            throw new RuntimeException("Tên nhóm '" + g.getName() + "' đã tồn tại");
        }
        if (g.getIdUser() == null || !userRepository.existsById(g.getIdUser())) {
            throw new RuntimeException("Invalid user");
        }

        g.setId(null);
        g.setCreatedAt(new Date());

        FieldGroup saved = groupRepository.save(g);
        initDefaultSensors(saved.getId());
        return saved;
    }

    public FieldGroup getById(String id) {
        return groupRepository.findById(id).orElse(null);
    }

    public List<FieldGroup> getAll() {
        return groupRepository.findAll();
    }

    public List<FieldGroup> getByUser(String idUser) {
        return groupRepository.findByIdUser(idUser);
    }

    public FieldGroup update(String id, FieldGroup data) {
        FieldGroup old = getById(id);
        if (old == null) return null;

        if (data.getIdUser() != null && !userRepository.existsById(data.getIdUser())) {
            throw new RuntimeException("User not found");
        }

        if (data.getName() != null && !data.getName().equals(old.getName())) {
            if (groupRepository.existsByName(data.getName())) {
                throw new RuntimeException("Tên nhóm '" + data.getName() + "' đã tồn tại");
            }
            old.setName(data.getName());
        }

        if (data.getIdUser() != null) {
            old.setIdUser(data.getIdUser());
        }

        return groupRepository.save(old);
    }

    public void delete(String id) {
        List<com.example.demo.entity.MongoEntity.Field> attached = fieldRepository.findByGroupId(id);
        if (!attached.isEmpty()) {
            throw new RuntimeException("Không thể xoá nhóm: còn " + attached.size() + " cánh đồng đang gắn với nhóm này");
        }

        groupSensorRepository.deleteByGroupId(id);
        sensorValueRepository.deleteByGroupId(id);
        groupRepository.deleteById(id);
    }

    public void initDefaultSensors(String groupId) {
        for (String sensorId : GROUP_SENSOR_IDS) {
            if (!groupSensorRepository.existsByGroupIdAndSensorId(groupId, sensorId)) {
                groupSensorRepository.save(new FieldGroupSensor(groupId, sensorId));
            }
        }
    }
}
