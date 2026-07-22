package com.example.demo.controller.mongo;

import com.example.demo.entity.MongoEntity.FieldGroupSensor;
import com.example.demo.service.Mongo.FieldGroupSensorService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@CrossOrigin(origins = "http://localhost:5173")
@RestController
@RequestMapping("/mongo/field-group")
public class FieldGroupSensorController {

    @Autowired
    private FieldGroupSensorService service;

    @PostMapping("/{groupId}/sensor")
    public String addSensor(
            @PathVariable String groupId,
            @RequestParam String sensorId
    ) {
        return service.addSensor(groupId, sensorId);
    }

    @DeleteMapping("/{groupId}/sensor")
    public String removeSensor(
            @PathVariable String groupId,
            @RequestParam String sensorId
    ) {
        return service.removeSensor(groupId, sensorId);
    }

    @GetMapping("/{groupId}/sensor")
    public List<FieldGroupSensor> getSensors(@PathVariable String groupId) {
        return service.getSensors(groupId);
    }
}
