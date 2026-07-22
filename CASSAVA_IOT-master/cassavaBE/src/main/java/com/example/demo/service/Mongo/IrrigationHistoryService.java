package com.example.demo.service.Mongo;

import com.example.demo.entity.MongoEntity.Field;
import com.example.demo.entity.MongoEntity.IrrigationHistory;
import com.example.demo.repositories.mongo.FieldMongoRepository;
import com.example.demo.repositories.mongo.IrrigationHistoryRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Date;
import java.util.List;

@Service
public class IrrigationHistoryService {

    @Autowired
    private IrrigationHistoryRepository irrigationHistoryRepository;

    @Autowired
    private FieldMongoRepository fieldMongoRepository;

    public List<IrrigationHistory> getByFieldId(String fieldId) {
        return getByFieldId(fieldId, null);
    }

    public List<IrrigationHistory> getByFieldId(String fieldId, Date cropStartTime) {
        Date crop = cropStartTime;
        if (crop == null) {
            Field field = fieldMongoRepository.findById(fieldId).orElse(null);
            if (field != null) crop = field.getStartTime();
        }
        if (crop != null) {
            return irrigationHistoryRepository
                    .findByFieldIdAndCropStartTimeOrderByTimeDesc(fieldId, crop);
        }
        return irrigationHistoryRepository.findByFieldIdOrderByTimeDesc(fieldId);
    }

    public IrrigationHistory create(IrrigationHistory history) {
        if (history.getTime() == null || history.getTime().isEmpty()) {
            DateTimeFormatter formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
            history.setTime(LocalDateTime.now().format(formatter));
        }
        // Stamp cropStartTime from the field's current crop cycle
        if (history.getCropStartTime() == null && history.getFieldId() != null) {
            fieldMongoRepository.findById(history.getFieldId())
                    .ifPresent(f -> history.setCropStartTime(f.getStartTime()));
        }
        return irrigationHistoryRepository.save(history);
    }

    public void delete(String id) {
        irrigationHistoryRepository.deleteById(id);
    }

    public void deleteByFieldId(String fieldId) {
        irrigationHistoryRepository.deleteByFieldId(fieldId);
    }
}
