package com.example.demo.service.anomaly;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.Map;

import jakarta.annotation.PostConstruct;

@Service
public class RangeCheckService {

    @Value("${anomaly.range.temperature.min:-10}")      private double tempMin;
    @Value("${anomaly.range.temperature.max:60}")       private double tempMax;
    @Value("${anomaly.range.relativeHumidity.min:0}")   private double rhMin;
    @Value("${anomaly.range.relativeHumidity.max:100}") private double rhMax;
    @Value("${anomaly.range.rain.min:0}")               private double rainMin;
    @Value("${anomaly.range.rain.max:500}")             private double rainMax;
    @Value("${anomaly.range.radiation.min:0}")          private double radMin;
    @Value("${anomaly.range.radiation.max:1500}")       private double radMax;
    @Value("${anomaly.range.wind.min:0}")               private double windMin;
    @Value("${anomaly.range.wind.max:50}")              private double windMax;
    @Value("${anomaly.range.humidity30.min:0}")         private double h30Min;
    @Value("${anomaly.range.humidity30.max:100}")       private double h30Max;
    @Value("${anomaly.range.humidity60.min:0}")         private double h60Min;
    @Value("${anomaly.range.humidity60.max:100}")       private double h60Max;

    private final Map<String, double[]> bounds = new HashMap<>();

    @PostConstruct
    void init() {
        bounds.put("temperature",      new double[]{tempMin, tempMax});
        bounds.put("relativeHumidity", new double[]{rhMin,   rhMax});
        bounds.put("rain",             new double[]{rainMin, rainMax});
        bounds.put("radiation",        new double[]{radMin,  radMax});
        bounds.put("wind",             new double[]{windMin, windMax});
        bounds.put("humidity30",       new double[]{h30Min,  h30Max});
        bounds.put("humidity60",       new double[]{h60Min,  h60Max});
    }

    public RangeCheckResult check(String sensorId, double value) {
        double[] b = bounds.get(sensorId);
        if (b == null) return RangeCheckResult.ok();
        if (value < b[0] || value > b[1]) return RangeCheckResult.fail(b[0], b[1]);
        return RangeCheckResult.ok();
    }
}
