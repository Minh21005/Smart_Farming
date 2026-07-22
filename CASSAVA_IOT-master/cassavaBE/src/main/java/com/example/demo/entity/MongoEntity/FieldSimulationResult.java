package com.example.demo.entity.MongoEntity;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

import java.util.Date;

@Document(collection = "simulation_result")
public class FieldSimulationResult {

    @Id
    private String id;

    private String fieldId;
    private Date cropStartTime;
    private Date time;

    private double yield;
    private double irrigation;
    private double leafArea;
    private double labileCarbon;

    public FieldSimulationResult() {
    }

    public FieldSimulationResult(String fieldId, Date time,
                            double yield, double irrigation,
                            double leafArea, double labileCarbon) {
        this.fieldId = fieldId;
        this.time = time;
        this.yield = yield;
        this.irrigation = irrigation;
        this.leafArea = leafArea;
        this.labileCarbon = labileCarbon;
    }

    public FieldSimulationResult(String fieldId, Date cropStartTime, Date time,
                            double yield, double irrigation,
                            double leafArea, double labileCarbon) {
        this.fieldId = fieldId;
        this.cropStartTime = cropStartTime;
        this.time = time;
        this.yield = yield;
        this.irrigation = irrigation;
        this.leafArea = leafArea;
        this.labileCarbon = labileCarbon;
    }

    // getters/setters
    // ===== GETTERS =====

    public String getId() {
        return id;
    }

    public String getFieldId() {
        return fieldId;
    }

    public Date getCropStartTime() {
        return cropStartTime;
    }

    public Date getTime() {
        return time;
    }

    public double getYield() {
        return yield;
    }

    public double getIrrigation() {
        return irrigation;
    }

    public double getLeafArea() {
        return leafArea;
    }

    public double getLabileCarbon() {
        return labileCarbon;
    }


    // ===== SETTERS =====

    public void setId(String id) {
        this.id = id;
    }

    public void setFieldId(String fieldId) {
        this.fieldId = fieldId;
    }

    public void setCropStartTime(Date cropStartTime) {
        this.cropStartTime = cropStartTime;
    }

    public void setTime(Date time) {
        this.time = time;
    }

    public void setYield(double yield) {
        this.yield = yield;
    }

    public void setIrrigation(double irrigation) {
        this.irrigation = irrigation;
    }

    public void setLeafArea(double leafArea) {
        this.leafArea = leafArea;
    }

    public void setLabileCarbon(double labileCarbon) {
        this.labileCarbon = labileCarbon;
    }
}
