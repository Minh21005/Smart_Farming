package com.example.demo.controller.mongo;

import com.example.demo.entity.MongoEntity.Field;
import com.example.demo.entity.MongoEntity.FieldSimulationResult;
import com.example.demo.repositories.mongo.FieldMongoRepository;
import com.example.demo.repositories.mongo.FieldSimulationResultRepository;
import com.example.demo.service.Mongo.FieldSimulator;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@CrossOrigin(origins = "http://localhost:5173")
@RestController
@RequestMapping("/simulation")
public class SimulationController {
   @Autowired
   private FieldSimulator fieldSimulator;

   @Autowired
   private FieldSimulationResultRepository simulationResultRepository;

   @Autowired
   private FieldMongoRepository fieldMongoRepository;

    // API: GET http://localhost:8081/simulation/run?fieldId=fieldTest
    @GetMapping("/run")
    public ResponseEntity<?> runModelSimulation(
            @RequestParam String fieldId
            ) {
        try {
            Map<String, Object> result = fieldSimulator.runSimulation(fieldId);
            return ResponseEntity.ok(result);
        } catch (Exception e) {
            return ResponseEntity.status(500).body("Lỗi mô phỏng: " + e.getMessage());
        }
    }

    @GetMapping("/chart")
    public ResponseEntity<?> getChart(
            @RequestParam String fieldId,
            @RequestParam(required = false) String cropStartTime) {

        Date crop = (cropStartTime == null || cropStartTime.isBlank())
                ? null
                : Date.from(Instant.parse(cropStartTime));

        if (crop == null) {
            Field field = fieldMongoRepository.findById(fieldId).orElse(null);
            if (field != null) crop = field.getStartTime();
        }

        List<FieldSimulationResult> data = crop != null
                ? simulationResultRepository.findByFieldIdAndCropStartTimeOrderByTimeAsc(fieldId, crop)
                : simulationResultRepository.findByFieldIdOrderByTimeAsc(fieldId);

        List<Double> labels = new ArrayList<>();
        List<Double> yield = new ArrayList<>();
        List<Double> irrigation = new ArrayList<>();
        List<Double> leafArea = new ArrayList<>();
        List<String> days = new ArrayList<>();

        for (FieldSimulationResult r : data) {
            // days
            days.add(r.getTime().toString());
            // labels
            labels.add(r.getLabileCarbon());
            yield.add(r.getYield());
            irrigation.add(r.getIrrigation());
            leafArea.add(r.getLeafArea());
        }

        Map<String, Object> result = new HashMap<>();
        result.put("day", days);
        result.put("labels", labels);
        result.put("yield", yield);
        result.put("irrigation", irrigation);
        result.put("leafArea", leafArea);

        return ResponseEntity.ok(result);
    }
}
