package com.example.demo.service.Mongo;

import com.example.demo.entity.MongoEntity.SensorValue;

import com.example.demo.repositories.mongo.SensorValueRepository;
import com.mongodb.BasicDBObject;
import org.bson.Document;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.aggregation.*;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Arrays;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Service
public class SensorValueService {
    @Autowired
    private SensorValueRepository repository;
    @Autowired
    private MongoTemplate mongoTemplate;
    public List<SensorValue> getHistory(String fieldId, String sensorId) {
        return repository.findByFieldIdAndSensorIdOrderByTimeDesc(fieldId, sensorId);
    }

    public List<SensorValue> getGroupHistory(String groupId, String sensorId) {
        return repository.findByGroupIdAndSensorIdOrderByTimeDesc(groupId, sensorId);
    }

    private static final List<String> WEATHER_SENSORS =
            Arrays.asList("temperature", "relativeHumidity", "rain", "wind", "radiation");

    /**
     * Latest reading per weather sensor for a group, in one aggregation:
     * match groupId + sensorId ∈ weather → sort time DESC → group by sensorId
     * keeping the first (newest) value + time.
     *
     * Returns a LinkedHashMap keyed by sensorId in the canonical order. Sensors
     * with no data are omitted (caller decides how to render missing values).
     */
    public Map<String, Reading> getLatestForGroup(String groupId) {
        MatchOperation match = Aggregation.match(
                Criteria.where("groupId").is(groupId).and("sensorId").in(WEATHER_SENSORS));
        SortOperation sort = Aggregation.sort(org.springframework.data.domain.Sort.Direction.DESC, "time");
        AggregationOperation group = context -> new Document("$group",
                new Document("_id", "$sensorId")
                        .append("value", new Document("$first", "$value"))
                        .append("time", new Document("$first", "$time")));

        Aggregation agg = Aggregation.newAggregation(match, sort, group)
                .withOptions(Aggregation.newAggregationOptions().allowDiskUse(true).build());
        AggregationResults<Document> results = mongoTemplate.aggregate(agg, "sensor_value", Document.class);

        Map<String, Reading> bySensor = new java.util.HashMap<>();
        for (Document doc : results.getMappedResults()) {
            String sensorId = doc.getString("_id");
            Double value = doc.get("value", Number.class) == null
                    ? null : doc.get("value", Number.class).doubleValue();
            Date time = doc.getDate("time");
            bySensor.put(sensorId, new Reading(value, time));
        }

        // Re-key in canonical order so the FE renders the grid consistently.
        Map<String, Reading> ordered = new LinkedHashMap<>();
        for (String s : WEATHER_SENSORS) {
            if (bySensor.containsKey(s)) ordered.put(s, bySensor.get(s));
        }
        return ordered;
    }

    public static class Reading {
        public Double value;
        public Date time;

        public Reading() {}
        public Reading(Double value, Date time) { this.value = value; this.time = time; }
    }

    /**
     * Gom dữ liệu cảm biến theo từng giờ: bucket time xuống đầu giờ, lấy trung bình
     * mỗi loại sensor trong cùng 1 giờ, rồi trả về 1 hàng / 1 giờ. Dùng cho mô hình
     * tính ET0 theo giờ trong Field.java.
     */
    public List<String> getCombinedValues(String groupId) {
        return getCombinedValues(groupId, null, null);
    }

    /**
     * Overload có lọc theo khoảng thời gian vụ mùa.
     * start/end null = không giới hạn đầu/cuối tương ứng.
     * Dùng cho mô phỏng vụ trong quá khứ.
     */
    public List<String> getCombinedValues(String groupId, java.util.Date start, java.util.Date end) {
        // 1. Lọc theo groupId + (tùy chọn) khoảng thời gian
        Criteria criteria = Criteria.where("groupId").is(groupId);
        if (start != null && end != null) {
            criteria = criteria.and("time").gte(start).lte(end);
        } else if (start != null) {
            criteria = criteria.and("time").gte(start);
        } else if (end != null) {
            criteria = criteria.and("time").lte(end);
        }
        MatchOperation matchStage = Aggregation.match(criteria);

        // 2. Gắn thêm hourTime = time làm tròn xuống đầu giờ (UTC)
        AggregationOperation addHourStage = context -> new Document("$addFields",
                new Document("hourTime", new Document("$dateTrunc",
                        new Document("date", "$time").append("unit", "hour"))));

        // 3. Gom theo (hourTime, sensorId) và lấy trung bình giá trị trong cùng 1 giờ
        AggregationOperation avgPerSensorStage = context -> new Document("$group",
                new Document("_id", new Document("hourTime", "$hourTime").append("sensorId", "$sensorId"))
                        .append("value", new Document("$avg", "$value")));

        // 4. Gom tất cả sensor của cùng 1 giờ thành 1 hàng
        AggregationOperation groupByHourStage = context -> new Document("$group",
                new Document("_id", "$_id.hourTime")
                        .append("time", new Document("$first", "$_id.hourTime"))
                        .append("sensors", new Document("$push",
                                new Document("k", "$_id.sensorId").append("v", "$value"))));

        // 5. Sắp xếp thời gian tăng dần
        SortOperation sortStage = Aggregation.sort(org.springframework.data.domain.Sort.Direction.ASC, "time");

        Aggregation aggregation = Aggregation.newAggregation(
                        matchStage, addHourStage, avgPerSensorStage, groupByHourStage, sortStage)
                .withOptions(Aggregation.newAggregationOptions().allowDiskUse(true).build());

        AggregationResults<Document> results = mongoTemplate.aggregate(aggregation, "sensor_value", Document.class);

        return results.getMappedResults().stream().map(doc -> {
            // hourTime là BSON Date đã truncate xuống đầu giờ; format UTC để không lệch 7h
            java.util.Date timeDate = doc.getDate("time");
            java.text.SimpleDateFormat sdf = new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss");
            sdf.setTimeZone(java.util.TimeZone.getTimeZone("UTC"));
            String timeStr = sdf.format(timeDate);

            @SuppressWarnings("unchecked")
            List<Document> sensors = (List<Document>) doc.get("sensors");

            // Trung bình mỗi loại trong giờ đó (rainfall tính mm/day nên cũng lấy avg)
            double rad = getValue(sensors, "radiation");
            double temp = getValue(sensors, "temperature");
            double rain = getValue(sensors, "rain");
            double hum = getValue(sensors, "relativeHumidity");
            double wind = getValue(sensors, "wind");

            return String.format("%s,%s,%f,%f,%f,%f,%f",
                    timeStr, timeStr, rad, temp, rain, hum, wind);
        }).collect(Collectors.toList());
    }

    private double getValue(List<org.bson.Document> sensors, String type) {
    return sensors.stream()
            .filter(s -> s.getString("k").equals(type))
            .map(s -> s.get("v", Number.class).doubleValue())
            .findFirst().orElse(0.0);
    }
}