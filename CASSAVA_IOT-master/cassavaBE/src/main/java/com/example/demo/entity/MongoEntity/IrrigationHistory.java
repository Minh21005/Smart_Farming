package com.example.demo.entity.MongoEntity;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

import java.util.Date;

@Document(collection = "irrigation_history")
public class IrrigationHistory {

    @Id
    private String id;

    private String fieldId;
    private Date cropStartTime;

    private String time;
    private String userName;

    private Double amount;
    private Double duration;

    public IrrigationHistory() {
    }

    public IrrigationHistory(String fieldId, String time, String userName,
                             Double amount, Double duration) {
        this.fieldId = fieldId;
        this.time = time;
        this.userName = userName;
        this.amount = amount;
        this.duration = duration;
    }

    public IrrigationHistory(String fieldId, Date cropStartTime, String time, String userName,
                             Double amount, Double duration) {
        this.fieldId = fieldId;
        this.cropStartTime = cropStartTime;
        this.time = time;
        this.userName = userName;
        this.amount = amount;
        this.duration = duration;
    }

    // getters/setters

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getFieldId() {
        return fieldId;
    }

    public void setFieldId(String fieldId) {
        this.fieldId = fieldId;
    }

    public Date getCropStartTime() {
        return cropStartTime;
    }

    public void setCropStartTime(Date cropStartTime) {
        this.cropStartTime = cropStartTime;
    }

    public String getTime() {
        return time;
    }

    public void setTime(String time) {
        this.time = time;
    }

    public String getUserName() {
        return userName;
    }

    public void setUserName(String userName) {
        this.userName = userName;
    }

    public Double getAmount() {
        return amount;
    }

    public void setAmount(Double amount) {
        this.amount = amount;
    }

    public Double getDuration() {
        return duration;
    }

    public void setDuration(Double duration) {
        this.duration = duration;
    }
}
