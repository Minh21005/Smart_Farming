package com.example.demo.service.anomaly;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestTemplate;

import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Thin HTTP client to the ml-service /detect endpoint.
 * Fails soft: on connection/timeout/4xx errors we log and return null so the
 * MQTT callback never throws.
 */
@Service
public class MlDetectClient {

    private static final Logger log = LoggerFactory.getLogger(MlDetectClient.class);

    @Value("${ml.service.url:http://localhost:8082}")
    private String mlServiceUrl;

    @Value("${ml.service.timeout-ms:2000}")
    private int timeoutMs;

    private RestTemplate http;

    @PostConstruct
    public void init() {
        this.http = new RestTemplateBuilder()
                .setConnectTimeout(Duration.ofMillis(timeoutMs))
                .setReadTimeout(Duration.ofMillis(timeoutMs))
                .build();
        log.info("MlDetectClient initialised: url={} timeout={}ms", mlServiceUrl, timeoutMs);
    }

    public DetectResponse detect(String groupId, String sensorId, Instant time, double value) {
        Map<String, Object> body = new HashMap<>();
        body.put("groupId", groupId);
        body.put("sensorId", sensorId);
        body.put("time", time.toString());
        body.put("value", value);

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        HttpEntity<Map<String, Object>> req = new HttpEntity<>(body, headers);

        try {
            return http.postForObject(mlServiceUrl + "/detect", req, DetectResponse.class);
        } catch (HttpStatusCodeException e) {
            log.warn("ml-service /detect HTTP {} for sensorId={}: {}",
                    e.getStatusCode().value(), sensorId, e.getResponseBodyAsString());
            return null;
        } catch (ResourceAccessException e) {
            log.warn("ml-service /detect unreachable for sensorId={}: {}", sensorId, e.getMessage());
            return null;
        } catch (Exception e) {
            log.warn("ml-service /detect unexpected error for sensorId={}: {}", sensorId, e.getMessage());
            return null;
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class DetectResponse {
        @JsonProperty("is_anomaly") public boolean isAnomaly;
        public Double actual;
        public List<MethodVerdict> methods;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class MethodVerdict {
        public String name;
        public Double predicted;
        public Double residual;
        public Double score;
        @JsonProperty("is_anomaly") public boolean isAnomaly;
    }
}
