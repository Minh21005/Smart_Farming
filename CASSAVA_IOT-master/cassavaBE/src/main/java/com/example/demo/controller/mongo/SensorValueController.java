package com.example.demo.controller.mongo;

import com.example.demo.entity.MongoEntity.SensorValue;
import com.example.demo.entity.MongoEntity.SensorCorrection;
import com.example.demo.repositories.mongo.SensorCorrectionRepository;
import com.example.demo.service.Mongo.SensorValueService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
@CrossOrigin(origins = "http://localhost:5173")
//@CrossOrigin(origins = "*") // Cho phép tất cả các nguồn gọi API này
@RestController
@RequestMapping("/sensor-values")
public class SensorValueController {
    @Autowired
    private SensorValueService sensorValueService;

    @Autowired
    private SensorCorrectionRepository correctionRepo;

    // API: GET http://localhost:8081/api/sensor-values/history?fieldId=fieldTest&sensorId=temperature
    @GetMapping("/history")
    public List<SensorValue> getSensorHistory(
            @RequestParam String fieldId,
            @RequestParam String sensorId) {
       // LocalDateTime twentyFourHoursAgo = LocalDateTime.now().minusHours(24);
        return sensorValueService.getHistory(fieldId, sensorId);
    }
    @GetMapping("/combined")
    public List<String> getCombinedData(@RequestParam String groupId) {
        // API này sẽ trả về List các String theo định dạng cậu muốn
        return sensorValueService.getCombinedValues(groupId);
    }

    // Per-group history for the 5 shared weather sensors
    // Ex: GET /sensor-values/group-history?groupId=...&sensorId=temperature
    @GetMapping("/group-history")
    public List<SensorValue> getGroupHistory(
            @RequestParam String groupId,
            @RequestParam String sensorId) {
        return sensorValueService.getGroupHistory(groupId, sensorId);
    }

    // Anomaly corrections for a group's weather sensor (chart overlay):
    // anomalous points + the forecaster's predicted value at that time.
    // Ex: GET /sensor-values/corrections?groupId=...&sensorId=temperature
    @GetMapping("/corrections")
    public List<SensorCorrection> getCorrections(
            @RequestParam String groupId,
            @RequestParam String sensorId) {
        return correctionRepo.findByGroupIdAndSensorIdOrderByTimeDesc(groupId, sensorId);
    }

    /**
     * Latest reading per weather sensor for a group. Shape:
     *   {
     *     "groupId": "default",
     *     "latestTime": "2026-05-21T10:00:00.000+00:00",
     *     "readings": {
     *       "temperature":      { "value": 30.24,  "time": "..." },
     *       "relativeHumidity": { "value": 96.9,   "time": "..." },
     *       "rain":             { "value": 4.6,    "time": "..." },
     *       "wind":             { "value": 1.6,    "time": "..." },
     *       "radiation":        { "value": 0.155,  "time": "..." }
     *     }
     *   }
     * Sensors with no data are omitted from `readings`. `latestTime` is the
     * max across all returned sensors (null if the group has no readings).
     */
    @GetMapping("/latest")
    public Map<String, Object> getLatestForGroup(@RequestParam String groupId) {
        Map<String, SensorValueService.Reading> readings =
                sensorValueService.getLatestForGroup(groupId);

        Date latestTime = null;
        for (SensorValueService.Reading r : readings.values()) {
            if (r.time != null && (latestTime == null || r.time.after(latestTime))) {
                latestTime = r.time;
            }
        }

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("groupId", groupId);
        body.put("latestTime", latestTime);
        body.put("readings", readings);
        return body;
    }
}
