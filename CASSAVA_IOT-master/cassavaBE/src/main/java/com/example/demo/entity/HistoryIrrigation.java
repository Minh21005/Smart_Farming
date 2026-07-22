package com.example.demo.entity;

public class HistoryIrrigation {
    String time; // legacy display string built from a fake 2024 calendar; do NOT use for date math
    Double simulationT; // raw simulation time (offset from cropStart). Authoritative source for date math.
    String userName; // user who performed the irrigation
    Double amount; // amount of water used
    Double duration; // duration of irrigation in minutes

    public Double getSimulationT() {
        return simulationT;
    }

    public void setSimulationT(Double simulationT) {
        this.simulationT = simulationT;
    }

    public Double getDuration() {
        return duration;
    }

    public void setDuration(Double duration) {
        this.duration = duration;
    }

    public HistoryIrrigation(String time, String userName, Double amount, Double duration) {
        this.time = time;
        this.userName = userName;
        this.amount = amount;
        this.duration = duration;
    }

    public HistoryIrrigation() {
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
}
