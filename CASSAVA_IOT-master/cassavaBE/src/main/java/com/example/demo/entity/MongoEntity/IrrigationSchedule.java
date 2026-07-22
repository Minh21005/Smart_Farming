package com.example.demo.entity.MongoEntity;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

import java.util.Date;

@Document(collection = "irrigation_schedule")
public class IrrigationSchedule {

    public enum Status {
        PENDING,
        SENT,
        RUNNING,
        DONE,
        CANCELLED,
        FAILED,
        NO_ACK
    }

    @Id
    private String id;

    private String fieldId;
    private Integer valveId;
    private Date scheduledTime;
    private Double durationSeconds;
    private Double amount;
    private String userName;
    private Status status;
    private Date createdAt;
    private Date updatedAt;
    private Date sentAt;
    private Date startedAt;
    private Date finishedAt;
    private String errorMessage;

    public IrrigationSchedule() {
    }

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }

    public String getFieldId() { return fieldId; }
    public void setFieldId(String fieldId) { this.fieldId = fieldId; }

    public Integer getValveId() { return valveId; }
    public void setValveId(Integer valveId) { this.valveId = valveId; }

    public Date getScheduledTime() { return scheduledTime; }
    public void setScheduledTime(Date scheduledTime) { this.scheduledTime = scheduledTime; }

    public Double getDurationSeconds() { return durationSeconds; }
    public void setDurationSeconds(Double durationSeconds) { this.durationSeconds = durationSeconds; }

    public Double getAmount() { return amount; }
    public void setAmount(Double amount) { this.amount = amount; }

    public String getUserName() { return userName; }
    public void setUserName(String userName) { this.userName = userName; }

    public Status getStatus() { return status; }
    public void setStatus(Status status) { this.status = status; }

    public Date getCreatedAt() { return createdAt; }
    public void setCreatedAt(Date createdAt) { this.createdAt = createdAt; }

    public Date getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Date updatedAt) { this.updatedAt = updatedAt; }

    public Date getSentAt() { return sentAt; }
    public void setSentAt(Date sentAt) { this.sentAt = sentAt; }

    public Date getStartedAt() { return startedAt; }
    public void setStartedAt(Date startedAt) { this.startedAt = startedAt; }

    public Date getFinishedAt() { return finishedAt; }
    public void setFinishedAt(Date finishedAt) { this.finishedAt = finishedAt; }

    public String getErrorMessage() { return errorMessage; }
    public void setErrorMessage(String errorMessage) { this.errorMessage = errorMessage; }
}
