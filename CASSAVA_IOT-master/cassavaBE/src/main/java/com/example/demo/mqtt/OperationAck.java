package com.example.demo.mqtt;

public class OperationAck {

    private String scheduleId;
    private String ack;
    private long ackAt;
    private String errorMessage;

    public OperationAck() {}

    public String getScheduleId() { return scheduleId; }
    public void setScheduleId(String scheduleId) { this.scheduleId = scheduleId; }

    public String getAck() { return ack; }
    public void setAck(String ack) { this.ack = ack; }

    public long getAckAt() { return ackAt; }
    public void setAckAt(long ackAt) { this.ackAt = ackAt; }

    public String getErrorMessage() { return errorMessage; }
    public void setErrorMessage(String errorMessage) { this.errorMessage = errorMessage; }
}
