package com.example.demo.controller.mongo;

import com.example.demo.entity.MongoEntity.FieldSensor;
import com.example.demo.service.Mongo.FieldSensorService;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.List;
@CrossOrigin(origins = "http://localhost:5173")
@RestController
@RequestMapping("/mongo/field")
public class FieldSensorController {

    @Autowired
    private FieldSensorService service;

    // ========================
    // ADD SENSOR
    // ========================
    @PostMapping("/{fieldId}/sensor")
    public String addSensor(
            @PathVariable String fieldId,
            @RequestParam String sensorId
    ) {
        return service.addSensor(fieldId, sensorId);
    }

    // ========================
    // REMOVE SENSOR
    // ========================
    @DeleteMapping("/{fieldId}/sensor")
    public String removeSensor(
            @PathVariable String fieldId,
            @RequestParam String sensorId
    ) {
        return service.removeSensor(fieldId, sensorId);
    }

    // ========================
    // GET SENSORS
    // ========================
    @GetMapping("/{fieldId}/sensor")
    public List<FieldSensor> getSensors(@PathVariable String fieldId) {
        return service.getSensors(fieldId);
    }
}