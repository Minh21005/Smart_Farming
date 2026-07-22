package com.example.demo.mqtt;

public class OperationCommand {

    private String scheduleId;
    private String action;
    private Integer durationSeconds;
    private long issuedAt;

    public OperationCommand() {}

    public OperationCommand(String scheduleId, String action, Integer durationSeconds, long issuedAt) {
        this.scheduleId = scheduleId;
        this.action = action;
        this.durationSeconds = durationSeconds;
        this.issuedAt = issuedAt;
    }

    public String getScheduleId() { return scheduleId; }
    public void setScheduleId(String scheduleId) { this.scheduleId = scheduleId; }

    public String getAction() { return action; }
    public void setAction(String action) { this.action = action; }

    public Integer getDurationSeconds() { return durationSeconds; }
    public void setDurationSeconds(Integer durationSeconds) { this.durationSeconds = durationSeconds; }

    public long getIssuedAt() { return issuedAt; }
    public void setIssuedAt(long issuedAt) { this.issuedAt = issuedAt; }
}
