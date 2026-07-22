# cassavaBE

Spring Boot + MongoDB backend for **CASSAVA_IOT** — a smart-farming IoT platform for cassava fields: crop simulation, sensor monitoring, two-tier anomaly detection, and MQTT-driven irrigation control.

> Full architecture, payload schemas, and conventions live in the repo-root [CLAUDE.md](../CLAUDE.md). MQTT + deploy runbooks are under [deploy/](../deploy/).

## Build & Run

Java 17 required. Packaging is **WAR** (Spring Boot 3.1.3; embedded Tomcat in dev, external in prod).

```bash
cd cassavaBE
mvn clean install                      # build + download deps
mvn spring-boot:run                    # run at http://localhost:8081
mvn test                               # all tests
mvn test -Dtest=ClassName#methodName   # single test
```

The dev profile reads `application.properties`; production activates `application-prod.properties` via `SPRING_PROFILES_ACTIVE=prod` (binds loopback, logs to `/var/log/cassava/app.log`).

## Architecture

```
React SPA (5173) ──Axios──▶ Spring Boot (8081) ──▶ MongoDB (112.137.129.218, iot_agriculture)
                                  │
                                  ├─ MQTT operation ◀─▶ edge (valve cmd/ack)
                                  └─ MQTT sensor → two-tier anomaly → ml-service (8082) + sensor_correction
```

- **controller/** — REST. `UserController` (`/api/auth`); under `controller/mongo/`: field CRUD (`/mongo/field`), field groups (`/mongo/field-group`), group/field sensors, sensor values (`/sensor-values`), irrigation history + schedule (`/mongo/irrigation-*`), simulation (`/simulation`).
- **service/Mongo/** — `FieldMongoService`, `FieldGroupService`, `FieldSimulator` (crop sim + auto irrigation; runs 07:00 & 17:00 `Asia/Ho_Chi_Minh`), `IrrigationScheduleService` + `IrrigationScheduleScheduler` (schedule lifecycle for both field modes), `CropSeasonService`, `SensorValueService`, …
- **service/anomaly/** — the two-tier detector (see below).
- **mqtt/** — one Paho client bean (`MqttConfig`) shared by two subsystems (operation + sensor).
- **entity/** — note **two** unrelated `Field` classes: `entity/Field.java` (crop-simulation model) vs `entity/MongoEntity/Field.java` (the `field` Mongo document).
- **Jwt/ + config/** — JWT auth (HS512, 24h), roles ADMIN/USER. Public: `/api/auth/**`; `/mongo/**` and `/simulation/**` are permitAll.

A field's `mode` (`SIMULATION` | `OPERATION`) governs how manual irrigation schedules execute: SIMULATION advances the lifecycle in-process (no MQTT); OPERATION publishes to the edge over the operation broker and waits for an ack.

## Anomaly detection (two-tier)

`MqttSensorListener` subscribes to the weather + soil sensor topics and, for each **weather** reading, runs:

1. **Tier 1 — `RangeCheckService`** (in-process physical bounds, `anomaly.range.*`). A value outside `[min,max]` is obviously bad → write a `sensor_correction` row with `method=range_check` and **skip** the ml-service call (no imputation offered).
2. **Tier 2 — ml-service `POST /detect`** (`MlDetectClient`, fail-soft 2s timeout). `PreferredDetectionMethods` picks one method's verdict per sensor (defaults: `seasonal_zscore` for temperature/relativeHumidity/radiation/wind, `sarima_residual` for rain). On anomaly → `sensor_correction` row carrying the detector's `predicted` + `score`.

The BE never writes raw `sensor_value` (that is the edge C binaries' job); it only layers `sensor_correction` on top — downstream consumers JOIN by `(time, sensorId)` and prefer `predicted`. Soil readings are log-only. ml-service must be running on `:8082` for Tier 2 (optional in dev — Tier 1 still gates readings if ml-service is down).

## MQTT

Two subsystems on one broker (see [deploy/MQTT.md](../deploy/MQTT.md)):
- **operation** — `MqttCommandPublisher` / `MqttAckListener` drive irrigation valves (`cassava/field/+/valve/+/{cmd,ack}`), feeding the schedule lifecycle.
- **sensor** — `MqttSensorListener` consumes weather/soil readings for anomaly detection.

## Config

`application.properties` (dev) / `application-prod.properties` (prod). Key blocks:
- `spring.data.mongodb.uri` — remote Mongo (`iot_agriculture`).
- `mqtt.operation.*` — broker URL + creds + ack timeout.
- `mqtt.sensor.*` — weather/soil topics + weather group id.
- `anomaly.range.<sensor>.{min,max}` — **Tier-1** physical bounds.
- `ml.service.url` / `ml.service.timeout-ms` — **Tier-2** ml-service endpoint.
- `ml.detection.preferred-method.<sensorId>` — override the per-sensor Tier-2 method.

## Deploy

Built as a WAR, run via systemd behind nginx (path-based routing, `/cassava/api/*` → loopback `:8081`). Full runbook: [deploy/DEPLOY.md](../deploy/DEPLOY.md).
