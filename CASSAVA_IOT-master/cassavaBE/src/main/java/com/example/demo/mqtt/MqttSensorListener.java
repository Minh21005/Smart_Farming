package com.example.demo.mqtt;

import com.example.demo.entity.MongoEntity.SensorCorrection;
import com.example.demo.repositories.mongo.SensorCorrectionRepository;
import com.example.demo.service.anomaly.MlDetectClient;
import com.example.demo.service.anomaly.PreferredDetectionMethods;
import com.example.demo.service.anomaly.RangeCheckResult;
import com.example.demo.service.anomaly.RangeCheckService;
import jakarta.annotation.PostConstruct;
import org.eclipse.paho.client.mqttv3.MqttClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Date;
import java.util.List;

/**
 * Subscribes to weather + soil MQTT topics.
 *
 * Weather flow (hourly per the weather station's publish cadence), two-tier:
 *   Tier 1 — RangeCheckService physical-bounds gate. Out-of-range → flag as a
 *            `range_check` SensorCorrection and STOP (no ml-service round-trip).
 *   Tier 2 — in-range → POST /detect (ml-service) → pick the preferred detector's
 *            verdict → if is_anomaly, write a SensorCorrection row.
 *   The raw value stays in sensor_value (owned by the edge C binaries); this
 *   collection layers the imputed/flagged value on top for consumers to JOIN.
 *
 * Soil flow: log only — no detectors fitted for soil per the benchmark scope.
 */
@Component
public class MqttSensorListener {

    private static final Logger log = LoggerFactory.getLogger(MqttSensorListener.class);

    /** Method name recorded on a SensorCorrection when the Tier-1 range gate rejects a reading. */
    private static final String RANGE_METHOD = "range_check";

    @Autowired
    private MqttClient mqttClient;

    @Autowired
    private MlDetectClient mlDetect;

    @Autowired
    private PreferredDetectionMethods preferredMethods;

    @Autowired
    private SensorCorrectionRepository correctionRepo;

    @Autowired
    private RangeCheckService rangeCheck;

    @Value("${mqtt.sensor.weather-topic:/sensor/weatherStation2}")
    private String weatherTopic;

    @Value("${mqtt.sensor.soil-topics:field1,field2,field3,field4,field2.1,field4.1}")
    private String[] soilTopics;

    @Value("${mqtt.sensor.weather-group-id:default}")
    private String weatherGroupId;

    @PostConstruct
    public void subscribe() {
        subscribeTopic(weatherTopic, true);
        for (String t : soilTopics) {
            String topic = t.trim();
            if (!topic.isEmpty()) subscribeTopic(topic, false);
        }
    }

    private void subscribeTopic(String topic, boolean isWeather) {
        try {
            mqttClient.subscribe(topic, 1, (rcvTopic, message) -> handle(rcvTopic, message.getPayload(), isWeather));
            log.info("MQTT sensor subscribed: {} (weather={})", topic, isWeather);
        } catch (Exception e) {
            log.warn("MQTT sensor subscribe failed for {}: {}", topic, e.getMessage());
        }
    }

    private void handle(String topic, byte[] payload, boolean isWeather) {
        String body = new String(payload).trim();
        if (body.isEmpty()) return;

        Instant now = Instant.now();
        for (String pair : body.split(";")) {
            String[] kv = pair.trim().split("\\s+");
            if (kv.length < 2) continue;

            String sensorId = isWeather ? MqttSensorTopics.resolveSensorId(kv[0]) : kv[0];
            double value;
            try {
                value = Double.parseDouble(kv[1]);
            } catch (NumberFormatException e) {
                log.warn("[sensor] PARSE_FAIL topic={} pair='{}' err={}", topic, pair, e.getMessage());
                continue;
            }

            if (isWeather) {
                detectWeather(topic, sensorId, now, value);
            } else {
                log.info("[sensor] SOIL topic={} sensorId={} value={}", topic, sensorId, value);
            }
        }
    }

    private void detectWeather(String topic, String sensorId, Instant time, double value) {
        // Tier 1 — cheap physical-range gate. A value outside the sensor's plausible
        // bounds is obviously bad; flag it and skip the ml-service round-trip.
        RangeCheckResult rc = rangeCheck.check(sensorId, value);
        if (!rc.isValid()) {
            log.warn("[sensor] RANGE_FAIL topic={} sensorId={} value={} min={} max={} (skipping ml-service)",
                    topic, sensorId, value, rc.getMin(), rc.getMax());
            persistRangeCorrection(sensorId, time, value);
            return;
        }

        // Tier 2 — ml-service per-sensor detectors.
        MlDetectClient.DetectResponse r = mlDetect.detect(weatherGroupId, sensorId, time, value);
        if (r == null) {
            log.info("[sensor] WEATHER topic={} sensorId={} value={} detect=skipped",
                    topic, sensorId, value);
            return;
        }

        String preferred = preferredMethods.forSensor(sensorId);
        if (preferred == null) {
            log.warn("[sensor] no preferred method configured for sensorId={}; verdicts={}",
                    sensorId, summarise(r.methods));
            return;
        }

        MlDetectClient.MethodVerdict chosen = findMethod(r.methods, preferred);
        if (chosen == null) {
            log.warn("[sensor] preferred method '{}' missing from /detect response for sensorId={}; verdicts={}",
                    preferred, sensorId, summarise(r.methods));
            return;
        }

        if (chosen.isAnomaly) {
            log.warn("[sensor] ANOMALY topic={} sensorId={} value={} predicted={} method={} score={}",
                    topic, sensorId, value, chosen.predicted, chosen.name,
                    chosen.score == null ? "?" : String.format("%.2f", chosen.score));
            persistCorrection(sensorId, time, value, chosen);
        } else {
            log.info("[sensor] OK topic={} sensorId={} value={} method={} score={}",
                    topic, sensorId, value, chosen.name,
                    chosen.score == null ? "?" : String.format("%.2f", chosen.score));
        }
    }

    private void persistCorrection(String sensorId, Instant time, double actual, MlDetectClient.MethodVerdict chosen) {
        try {
            SensorCorrection c = new SensorCorrection();
            c.setTime(Date.from(time));
            c.setGroupId(weatherGroupId);
            c.setSensorId(sensorId);
            c.setActual(actual);
            c.setPredicted(chosen.predicted);
            c.setMethod(chosen.name);
            c.setScore(chosen.score);
            correctionRepo.save(c);
        } catch (Exception e) {
            log.warn("[sensor] failed to persist correction sensorId={} err={}", sensorId, e.getMessage());
        }
    }

    /**
     * Records a Tier-1 range-gate rejection. There is no imputation to offer
     * (the ml-service call was skipped), so `predicted`/`score` stay null —
     * the row only marks the reading as out-of-range for downstream consumers.
     */
    private void persistRangeCorrection(String sensorId, Instant time, double actual) {
        try {
            SensorCorrection c = new SensorCorrection();
            c.setTime(Date.from(time));
            c.setGroupId(weatherGroupId);
            c.setSensorId(sensorId);
            c.setActual(actual);
            c.setPredicted(null);
            c.setMethod(RANGE_METHOD);
            c.setScore(null);
            correctionRepo.save(c);
        } catch (Exception e) {
            log.warn("[sensor] failed to persist range correction sensorId={} err={}", sensorId, e.getMessage());
        }
    }

    private static MlDetectClient.MethodVerdict findMethod(List<MlDetectClient.MethodVerdict> methods, String name) {
        if (methods == null) return null;
        for (MlDetectClient.MethodVerdict m : methods) {
            if (name.equals(m.name)) return m;
        }
        return null;
    }

    private static String summarise(List<MlDetectClient.MethodVerdict> methods) {
        if (methods == null || methods.isEmpty()) return "[]";
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < methods.size(); i++) {
            MlDetectClient.MethodVerdict m = methods.get(i);
            if (i > 0) sb.append(", ");
            sb.append(m.name).append("(score=")
              .append(m.score == null ? "?" : String.format("%.2f", m.score))
              .append(", anomaly=").append(m.isAnomaly).append(")");
        }
        return sb.append("]").toString();
    }
}
