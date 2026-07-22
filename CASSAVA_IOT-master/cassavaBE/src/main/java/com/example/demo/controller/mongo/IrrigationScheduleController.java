package com.example.demo.controller.mongo;

import com.example.demo.entity.MongoEntity.IrrigationSchedule;
import com.example.demo.entity.MongoEntity.IrrigationSchedule.Status;
import com.example.demo.service.Mongo.IrrigationScheduleService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.Date;
import java.util.List;
import java.util.Map;

@CrossOrigin(origins = "http://localhost:5173")
@RestController
@RequestMapping("/mongo/irrigation-schedule")
public class IrrigationScheduleController {

    @Autowired
    private IrrigationScheduleService scheduleService;

    @PostMapping
    public IrrigationSchedule create(@RequestBody IrrigationSchedule schedule) {
        return scheduleService.create(schedule);
    }

    @GetMapping
    public List<IrrigationSchedule> getByFieldId(@RequestParam String fieldId,
                                                 @RequestParam(required = false) Boolean pendingOnly) {
        if (Boolean.TRUE.equals(pendingOnly)) {
            return scheduleService.getPendingByFieldId(fieldId);
        }
        return scheduleService.getByFieldId(fieldId);
    }

    @GetMapping("/due")
    public List<IrrigationSchedule> getDuePending(@RequestParam(required = false) Long beforeMillis) {
        Date before = beforeMillis != null ? new Date(beforeMillis) : new Date();
        return scheduleService.getDuePending(before);
    }

    @PutMapping("/{id}/cancel")
    public IrrigationSchedule cancel(@PathVariable String id) {
        return scheduleService.cancel(id);
    }

    /** Dừng tưới một lịch đang RUNNING: gửi CLOSE xuống edge + ghi lượng nước một phần. */
    @PutMapping("/{id}/stop")
    public IrrigationSchedule stop(@PathVariable String id) {
        return scheduleService.stop(id);
    }

    @PutMapping("/{id}/status")
    public IrrigationSchedule updateStatus(@PathVariable String id,
                                           @RequestBody Map<String, String> body) {
        Status status = Status.valueOf(body.get("status"));
        String errorMessage = body.get("errorMessage");
        return scheduleService.updateStatus(id, status, errorMessage);
    }

    @DeleteMapping("/{id}")
    public String delete(@PathVariable String id) {
        scheduleService.delete(id);
        return "Deleted schedule: " + id;
    }
}
