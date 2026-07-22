package com.example.demo.controller.mongo;

import com.example.demo.entity.MongoEntity.FieldGroup;
import com.example.demo.service.Mongo.FieldGroupService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@CrossOrigin(origins = "http://localhost:5173")
@RestController
@RequestMapping("/mongo/field-group")
public class FieldGroupController {

    @Autowired
    private FieldGroupService service;

    @PostMapping
    public ResponseEntity<?> create(@RequestBody FieldGroup group) {
        try {
            return ResponseEntity.status(HttpStatus.CREATED).body(service.create(group));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(e.getMessage());
        } catch (RuntimeException e) {
            return ResponseEntity.status(HttpStatus.CONFLICT).body(e.getMessage());
        }
    }

    @GetMapping
    public List<FieldGroup> getAll() {
        return service.getAll();
    }

    @GetMapping("/{id}")
    public ResponseEntity<?> getById(@PathVariable String id) {
        FieldGroup g = service.getById(id);
        if (g == null) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body("Không tìm thấy nhóm");
        }
        return ResponseEntity.ok(g);
    }

    @GetMapping("/user/{userId}")
    public List<FieldGroup> getByUser(@PathVariable String userId) {
        return service.getByUser(userId);
    }

    @PutMapping("/{id}")
    public ResponseEntity<?> update(@PathVariable String id, @RequestBody FieldGroup data) {
        try {
            FieldGroup updated = service.update(id, data);
            if (updated == null) {
                return ResponseEntity.status(HttpStatus.NOT_FOUND).body("Không tìm thấy nhóm");
            }
            return ResponseEntity.ok(updated);
        } catch (RuntimeException e) {
            return ResponseEntity.status(HttpStatus.CONFLICT).body(e.getMessage());
        }
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<?> delete(@PathVariable String id) {
        try {
            service.delete(id);
            return ResponseEntity.ok("Deleted successfully");
        } catch (RuntimeException e) {
            return ResponseEntity.status(HttpStatus.CONFLICT).body(e.getMessage());
        }
    }
}
