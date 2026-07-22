package com.example.demo.service.Mongo;

import com.example.demo.entity.MongoEntity.Field;
import com.example.demo.repositories.mongo.FieldMongoRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.aggregation.Aggregation;
import org.springframework.data.mongodb.core.aggregation.AggregationResults;
import org.springframework.stereotype.Service;

import java.util.*;

@Service
public class CropSeasonService {

    @Autowired
    private MongoTemplate mongoTemplate;

    @Autowired
    private FieldMongoRepository fieldRepository;

    /**
     * Liệt kê các mùa vụ của một cánh đồng theo thứ tự giảm dần (mới nhất trước).
     * Mùa được xác định bằng cropStartTime — lấy từ cả simulation_result và
     * irrigation_history, hợp nhất theo ngày. Vụ đang chạy (= field.startTime hiện tại)
     * được đánh dấu isCurrent=true. cropEndTime của mỗi vụ CŨ = max(time) các bản ghi
     * của vụ đó (≈ ngày thu hoạch thực tế), KHÔNG phải cropStartTime vụ kế tiếp — để
     * không nuốt khoảng nghỉ giữa hai vụ (vd sắn T3→T1 rồi nghỉ tới T3 năm sau).
     * Vụ đang chạy dùng field.endTime (null = chưa kết thúc).
     */
    public List<Map<String, Object>> listSeasons(String fieldId) {
        Field field = fieldRepository.findById(fieldId).orElse(null);
        if (field == null) return Collections.emptyList();

        Map<Date, long[]> counts = new TreeMap<>(Comparator.reverseOrder());
        Map<Date, Date> lastRecord = new HashMap<>();   // max(time) mỗi cropStartTime

        accumulate(counts, lastRecord, aggregateSeason("simulation_result", fieldId), 0);
        accumulate(counts, lastRecord, aggregateSeason("irrigation_history", fieldId), 1);

        // Đảm bảo vụ đang chạy luôn hiển thị dù chưa có record nào
        if (field.getStartTime() != null) {
            counts.computeIfAbsent(field.getStartTime(), k -> new long[2]);
        }

        List<Map<String, Object>> seasons = new ArrayList<>(counts.size());
        List<Date> orderedDesc = new ArrayList<>(counts.keySet()); // đã DESC do TreeMap reverseOrder
        for (int i = 0; i < orderedDesc.size(); i++) {
            Date start = orderedDesc.get(i);
            boolean isCurrent = field.getStartTime() != null && start.equals(field.getStartTime());
            Date end;
            if (isCurrent) {
                end = field.getEndTime();                 // null = vụ đang chạy
            } else {
                // Vụ cũ: kết thúc tại bản ghi cuối cùng thực tế (≈ ngày thu hoạch),
                // KHÔNG lấy ngày bắt đầu vụ kế tiếp → tránh nuốt khoảng nghỉ giữa hai vụ.
                end = lastRecord.get(start);
            }

            Map<String, Object> season = new LinkedHashMap<>();
            season.put("cropStartTime", start);
            season.put("cropEndTime", end);
            season.put("isCurrent", isCurrent);
            season.put("simulationCount", counts.get(start)[0]);
            season.put("irrigationCount", counts.get(start)[1]);
            seasons.add(season);
        }
        return seasons;
    }

    /** Per cropStartTime: số bản ghi + max(time) (mốc cuối thực tế của vụ). */
    private Map<Date, Object[]> aggregateSeason(String collection, String fieldId) {
        Aggregation agg = Aggregation.newAggregation(
                Aggregation.match(org.springframework.data.mongodb.core.query.Criteria.where("fieldId").is(fieldId)),
                Aggregation.match(org.springframework.data.mongodb.core.query.Criteria.where("cropStartTime").ne(null)),
                Aggregation.group("cropStartTime").count().as("count").max("time").as("maxTime")
        );
        AggregationResults<Map> results = mongoTemplate.aggregate(agg, collection, Map.class);
        Map<Date, Object[]> out = new HashMap<>();
        for (Map row : results.getMappedResults()) {
            Object id = row.get("_id");
            if (!(id instanceof Date)) continue;
            long count = (row.get("count") instanceof Number) ? ((Number) row.get("count")).longValue() : 0L;
            Date maxTime = (row.get("maxTime") instanceof Date) ? (Date) row.get("maxTime") : null;
            out.put((Date) id, new Object[]{count, maxTime});
        }
        return out;
    }

    private void accumulate(Map<Date, long[]> counts, Map<Date, Date> lastRecord,
                            Map<Date, Object[]> stats, int idx) {
        for (Map.Entry<Date, Object[]> e : stats.entrySet()) {
            Date start = e.getKey();
            counts.computeIfAbsent(start, k -> new long[2])[idx] = (long) e.getValue()[0];
            Date maxTime = (Date) e.getValue()[1];
            if (maxTime != null) {
                lastRecord.merge(start, maxTime, (a, b) -> b.after(a) ? b : a);
            }
        }
    }
}
