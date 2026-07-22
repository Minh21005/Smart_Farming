package com.example.demo.controller.mongo;

import com.example.demo.entity.MongoEntity.IrrigationHistory;
import com.example.demo.service.Mongo.IrrigationHistoryService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.Date;
import java.util.List;

@CrossOrigin(origins = "http://localhost:5173")
@RestController
@RequestMapping("/mongo/irrigation-history")
public class IrrigationHistoryController {

    @Autowired
    private IrrigationHistoryService irrigationHistoryService;

    @GetMapping
    public List<IrrigationHistory> getByFieldId(
            @RequestParam String fieldId,
            @RequestParam(required = false) String cropStartTime) {
        Date crop = (cropStartTime == null || cropStartTime.isBlank())
                ? null
                : Date.from(Instant.parse(cropStartTime));
        return irrigationHistoryService.getByFieldId(fieldId, crop);
    }

    @PostMapping
    public IrrigationHistory create(@RequestBody IrrigationHistory history) {
        return irrigationHistoryService.create(history);
    }

    @DeleteMapping("/{id}")
    public String delete(@PathVariable String id) {
        irrigationHistoryService.delete(id);
        return "Deleted successfully";
    }

    @DeleteMapping("/field/{fieldId}")
    public String deleteByFieldId(@PathVariable String fieldId) {
        irrigationHistoryService.deleteByFieldId(fieldId);
        return "Deleted all irrigation history for field: " + fieldId;
    }
}
