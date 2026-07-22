package com.example.demo.service.anomaly;

public class RangeCheckResult {

    private final boolean valid;
    private final Double min;
    private final Double max;

    private RangeCheckResult(boolean valid, Double min, Double max) {
        this.valid = valid;
        this.min = min;
        this.max = max;
    }

    public static RangeCheckResult ok() {
        return new RangeCheckResult(true, null, null);
    }

    public static RangeCheckResult fail(Double min, Double max) {
        return new RangeCheckResult(false, min, max);
    }

    public boolean isValid() {
        return valid;
    }

    public Double getMin() {
        return min;
    }

    public Double getMax() {
        return max;
    }
}
