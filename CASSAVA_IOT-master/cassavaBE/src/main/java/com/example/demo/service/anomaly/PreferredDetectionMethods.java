package com.example.demo.service.anomaly;

import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;

/**
 * Picks which /detect method's verdict to act on, per sensor.
 *
 * Defaults are the per-sensor winners from the ml-service detector benchmark:
 *   - temperature, relativeHumidity, radiation, wind → seasonal_zscore
 *   - rain → sarima_residual (z-score thuần can't catch zero-inflated rain spikes)
 *
 * Overrides come from `ml.detection.preferred-method.<sensorId>` properties.
 */
@Component
public class PreferredDetectionMethods {

    private static final Logger log = LoggerFactory.getLogger(PreferredDetectionMethods.class);

    private static final Map<String, String> DEFAULTS = Map.of(
            "temperature",      "seasonal_zscore",
            "relativeHumidity", "seasonal_zscore",
            "rain",             "sarima_residual",
            "radiation",        "seasonal_zscore",
            "wind",             "seasonal_zscore"
    );

    @Value("${ml.detection.preferred-method.temperature:}")      private String temperature;
    @Value("${ml.detection.preferred-method.relativeHumidity:}") private String relativeHumidity;
    @Value("${ml.detection.preferred-method.rain:}")             private String rain;
    @Value("${ml.detection.preferred-method.radiation:}")        private String radiation;
    @Value("${ml.detection.preferred-method.wind:}")             private String wind;

    private final Map<String, String> resolved = new HashMap<>();

    @PostConstruct
    public void init() {
        resolved.put("temperature",      pick("temperature", temperature));
        resolved.put("relativeHumidity", pick("relativeHumidity", relativeHumidity));
        resolved.put("rain",             pick("rain", rain));
        resolved.put("radiation",        pick("radiation", radiation));
        resolved.put("wind",             pick("wind", wind));
        log.info("Preferred detection methods: {}", resolved);
    }

    public String forSensor(String sensorId) {
        return resolved.get(sensorId);
    }

    private static String pick(String sensor, String override) {
        return (override != null && !override.isBlank()) ? override.trim() : DEFAULTS.get(sensor);
    }
}
