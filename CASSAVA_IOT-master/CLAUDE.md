# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

CASSAVA_IOT is a full-stack Smart Farming IoT platform for managing cassava crop fields with real-time sensor monitoring and automated irrigation control. It consists of a React frontend and a Spring Boot backend connected to MongoDB.

## Build & Run Commands

### Frontend (`CassavaFE/`)
```bash
cd CassavaFE
npm install           # install dependencies
npm run dev           # dev server at http://localhost:5173
npm run build         # production build to dist/
npm run lint          # ESLint
npm run preview       # preview production build
```

### Backend (`cassavaBE/`)
```bash
cd cassavaBE
mvn clean install     # build + download dependencies
mvn spring-boot:run   # run at http://localhost:8081
mvn test              # run all tests
mvn test -Dtest=ClassName#methodName   # run a single test
```
> Note: the BE has **no real test suite yet** — `src/test` contains only `Demo1ApplicationTests` (a Spring context-load smoke test). Don't rely on `mvn test` to validate behavior changes; verify manually or add tests.

### ML service (`ml-service/`) — optional, anomaly detection + forecasting
```bash
cd ml-service
python -m venv .venv && source .venv/Scripts/activate
pip install -r requirements.txt
cp .env.example .env
python -m scripts.check_data        # verify Mongo data sufficiency
uvicorn api.main:app --port 8082    # FastAPI on 8082, loopback only in prod
```

Python 3.13. **Now part of the runtime path (merged to `master` in `f65fcb9a`)**: BE's `MqttSensorListener` applies an in-process range gate then forwards in-range weather readings to `POST /detect` via `MlDetectClient` (2s timeout, fail-soft — if ml-service is down the BE keeps running and just skips the Tier-2 verdict). Run it manually in dev; in prod it's bound to loopback and started alongside the BE.

Two roles, two endpoints, **both per-sensor**:
- **Detection** (`POST /detect`) — request body `{groupId, sensorId, time, value}`. ml-service routes by `sensorId` (404 if unknown) and runs every registered detector for that sensor: `zscore` + `seasonal_zscore` (always, fitted at startup from the NASA CSV) plus `arima_residual` / `sarima_residual` / `lstm_residual` whenever the corresponding artifact is present. Response carries per-method verdicts and a combined `is_anomaly` (OR over methods).
- **Forecasting** (`POST /forecast`) — per-sensor ARIMA/SARIMA loaded from `{model}_{sensor}.pkl`; LSTM is a single **multi-target** artifact at `artifacts/lstm/` (Dense(5) predicts all 5 weather features at once — the caller picks a view via `LstmForecaster(target=<sensor>)`). Returns h-step predictions for the requested sensor.

Branches: `feat/anomaly-zscore` (statistical only), `feat/anomaly-ml` (ARIMA/SARIMA/LSTM only), `feat/anomaly-compare` (all five with dual role split, **plus the BE→ml-service integration and `sensor_correction` persistence**) — **now merged into `master`** (`origin/feat/anomaly-compare` and `master` both point at `f65fcb9a`), so `master` is the current source of truth. The earlier `feat/anomaly-zscore-be` WIP (Java port of seasonal Z-score into cassavaBE) was **superseded** by the HTTP-delegation approach landed in `c8b0897a` and is not being merged. Detection cadence is hourly.

Benchmark findings (the write-up docs have been removed from the repo, but the conclusions still drive the code): the BE's `PreferredDetectionMethods` defaults come from a per-sensor detector comparison — **seasonal_zscore** wins for temperature/relativeHumidity/radiation/wind, **sarima_residual** for rain. **rain is the documented weak spot for every detector** (zero-inflated, low F1 — a known limitation). By task family: **LSTM residuals** tend to win *detection* (sensitive to cycle shape → residuals flag anomalies), while **SARIMA** wins *one-step forecast + recovery* (exploits the 24h cycle + recent level via differencing → low one-step MAE); LSTM point-forecast accuracy trails the statistical models. (A historical "LSTM wins forecast 5/5" claim was an artifact of a now-fixed ARIMA/SARIMA `update()` bug — see below; the corrected result is SARIMA-dominant for forecast/recovery.)

`scripts/train.py`, `scripts/evaluate_detection.py`, `scripts/evaluate_forecast_per_sensor.py` all share `--test-months` (default 1) so saved artifacts never see the eval slice. `train.py --sensor <name>|all` produces per-sensor `arima_<sensor>.pkl` / `sarima_<sensor>.pkl`; LSTM ignores `--sensor` and trains **once** (the resulting `artifacts/lstm/` is multi-target — Dense(5) — and is loaded once at startup with per-target `residual_std`). `evaluate_detection.py --sensor all --methods all` reproduces the cross-sensor table.

Additional eval tooling (run as `python -m scripts.<name>`):
- `evaluate_forecast_per_sensor.py` — one-step-ahead MAE/RMSE/MAPE for ARIMA/SARIMA/LSTM across all 5 weather sensors (forecast analogue of `evaluate_detection.py --sensor all`). Also holds the shared `_build_arima` / `_build_sarima` / `_build_lstm_views` / `_walk_h1` helpers that the seasonal scripts import — keep it even though its CLI is rarely run directly.

**4-season rolling-origin backtest** (the methodology upgrade over the single winter-only holdout — see `seasonal_common.py` for the rationale). Injects synthetic anomalies into one representative month per season (Mar/Jun/Sep/Dec, advisor's picks, one per quarter); for each window the models are refit on all data strictly *before* it (no leakage), giving 4× the test data spanning all seasons:
- `seasonal_common.py` — shared windowing: `SEASONS`, `resolve_year`, `season_windows`, `rolling_split` (with a leakage assertion).
- `evaluate_detection_seasonal.py` — KB1. Per-season best-F1 **plus** a cross-season mean ± std at a single shared k (one year-round k = production-realistic). LSTM is fit once per window (4 fits, not 20) via the shared `_build_lstm_views`.
- `evaluate_forecast_seasonal.py` — KB2 (recovery: detect→impute loop, error vs clean truth at injected points) + KB3 (clean-window forecast MAE/RMSE/MAPE).
- ARIMA/SARIMA lift their per-sensor `order` from the saved artifacts (so train those first with `--auto-order`); weights are refit per window. LSTM trains fresh per window from current `lstm.py` config, so it needs **no** pre-trained artifact for the backtest.

`LstmForecaster.fit` takes an optional `seed`; `evaluate_detection.py` pins `seed=42` for the `lstm_residual` refit so per-sensor F1 numbers are comparable across detectors.

**LSTM config is literature-grounded**: stacked 2-layer LSTM (64→32) with **variational dropout** (`dropout` + `recurrent_dropout`, Gal & Ghahramani), **Huber loss**, **EarlyStopping on a validation tail** (epochs is a cap), Adam(1e-3). All knobs are `LstmForecaster.__init__` params, serialized in the artifact. Changing config requires `python -m scripts.train --model lstm` to regenerate the serving artifact (old artifacts still load via the Keras graph). **ARIMA/SARIMA interpolation** is configurable too: `interpolate_method` / `interpolate_limit` on the forecasters + `--interpolate-method` / `--interpolate-limit` on `train.py` (which now passes the raw gappy series so the config actually applies — small gaps filled, large gaps dropped).

**ARIMA/SARIMA online one-step update (don't regress this).** `predict()`/`update()` do a rolling one-step walk: params are fit once, then each `predict()` re-applies them to a **bounded ~90-day trailing context** (`ArimaForecaster` via statsmodels `ARIMAResults.apply`, `SarimaForecaster` via statsforecast `forward`), and `update(value, time)` appends the observation to a running `_history` (serialized in the artifact). This replaced an earlier `update()` that **silently failed** (statsmodels `append` of a tz-indexed Series raised "Columns must match"; statsforecast `forward([value])` was a no-op), leaving the model un-advanced so every `predict()` returned the same value → a **flat one-step forecast**. The flat bug had made ARIMA/SARIMA look far worse than they are (and produced a misleading "LSTM wins forecast" headline in an earlier benchmark write-up); after the fix SARIMA gives the best one-step MAE on most sensors. The bounded context keeps the walk O(1) per step instead of O(n) (full-history `apply`/`forward` was ~25× slower with identical one-step output).

Both FE and BE must run concurrently for development. FE Axios instances read `VITE_API_BASE` from env files — `.env.development` sets it to `http://localhost:8081`, `.env.production` sets it to `/cassava/api` (relative, resolved by nginx under the prod deploy). No Vite proxy config.

## Architecture

```
React SPA (5173) ──Axios──▶ Spring Boot API (8081) ──▶ MongoDB (remote)
                                    │   │                   ▲   ▲
                                    │   │ POST /detect      │   │ libmongoc insert (raw `sensor_value`)
                                    │   ▼ (per reading)     │   │ edge C binaries on pi3
                                    │  ml-service (8082)    │   │
                                    │  /detect /forecast    │   │ subscribe MQTT (localhost)
                                    │  per-sensor models    │   │
                                    │                       │   │
                                    │   sensor_correction ──┘   │  (BE writes on anomaly)
                                    │
                            mosquitto (prod) ◀──bridge──▶ mosquitto (pi3)
                                    ▲                       ▲
                                    │                       │
                          MQTT operation              valves out / ack in
                          MQTT sensor (anomaly)       weather + soil readings
                                                            ▲
                                                            │
                                                       edge (pi3)
```

Two MQTT subsystems share a **two-broker topology** linked by a mosquitto bridge:
- **mosquitto pi3** (edge-local) — every edge program (`edge_to_mongo_*`, `dk_bom_mqtt`, ESP publishers) connects to `localhost:1883`. Anonymous (localhost-only).
- **mosquitto prod** (`tcp://127.0.0.1:1883` on the BE host) — Spring Boot connects via loopback. Auth: `libe / 123456`.
- **bridge** (`deploy/mosquitto/cassava-bridge.conf` on pi3) — outbound TCP from pi3 to prod (`112.137.129.218:1883`). Forwards: `cmd in 1`, `ack out 1`, sensor topics `out 1`. Outbound-only direction means pi3 can sit behind NAT without port-forwarding.

Subsystems:
- **operation** — BE ↔ edge for irrigation commands/acks (`cassava/field/+/valve/+/{cmd,ack}`).
- **sensor** — edge publishes weather + soil readings on `/sensor/weatherStation2` and `field1..field4`. Raw persistence is owned by the **edge C binaries** (single-file `edge/edge_to_mongo_weather.c`, `edge/edge_to_mongo_soil.c`, compiled directly with `cc` on pi3) which insert into MongoDB `sensor_value`. The BE listens to these topics for **anomaly detection** (note: `mqtt.sensor.soil-topics` is configured with **six** entries — `field1..field4` plus `field2.1`/`field4.1` — a superset of the four the edge `SOIL_FIELDS` table currently feeds, so the two extras receive no traffic today): `MqttSensorListener` forwards each weather reading to ml-service `POST /detect` via `MlDetectClient`, then `PreferredDetectionMethods` picks one method's verdict per sensor; if that verdict is anomalous the BE writes a `SensorCorrection` row (raw value still owned by the edge). Soil readings are log-only (no detectors fit for soil yet).

See `deploy/MQTT.md` for runbook details (including the bridge-vs-direct rationale).

Main app (`Demo1Application`) uses `@EnableCaching` and `@EnableScheduling`. `FieldSimulator.runScheduledSimulationForAllFields()` runs the crop simulation for every Mongo field twice daily at **07:00 and 17:00 `Asia/Ho_Chi_Minh`** (cron `0 0 7,17 * * *`). On-demand runs are still available via `GET /simulation/run?fieldId=X`.

### Backend (`cassavaBE/src/main/java/com/example/demo/`)

- **controller/**: REST endpoints. `UserController` lives at the root; the rest are under `controller/mongo/`:
  - `FieldMongoController` (`/mongo/field`) — MongoDB field CRUD, plus `POST /mongo/field/clone/{id}` (deep-copy), `POST /mongo/field/resetCrop/{id}` (new growing cycle), and `GET /mongo/field/{id}/seasons` (crop-season list, served by `CropSeasonService`)
  - `FieldGroupController` (`/mongo/field-group`) — CRUD for field groups (groups share a weather station; every field must belong to one)
  - `FieldGroupSensorController` — sensor-to-group mapping (weather station sensors shared across a group's fields)
  - `FieldSensorController` (`/mongo/field/{fieldId}/sensor`) — per-field sensor mapping (soil moisture sensors)
  - `SensorValueController` (`/sensor-values`) — read-only sensor data: `/history` + `/combined` (per-field, hourly-bucketed), `/group-history` (per-group, one of the 5 shared weather sensors), `/corrections` (anomaly points + forecaster `predicted` value, backs the weather-chart overlay), `/latest` (latest reading per weather sensor for a group, backs the dashboard latest-readings panel)
  - `IrrigationHistoryController` (`/mongo/irrigation-history`) — irrigation record log
  - `IrrigationScheduleController` (`/mongo/irrigation-schedule`) — manual irrigation scheduling (`PENDING/SENT/RUNNING/DONE/CANCELLED/FAILED/NO_ACK` lifecycle — see "Field mode (SIMULATION vs OPERATION)" below)
  - `SimulationController` (`/simulation`) — run/chart simulation
  - `UserController` (`/api/auth`) — login, register, list users
- **service/**: Split between the root and a `Mongo/` subpackage.
  - `service/`: `JwtService`, `UserService`
  - `service/Mongo/`: `FieldMongoService`, `FieldGroupService`, `FieldGroupSensorService`, `FieldSensorService`, `FieldSimulator` (crop simulation + auto irrigation history), `SensorValueService`, `IrrigationHistoryService`, `IrrigationScheduleService`, `IrrigationScheduleScheduler` (15s/30s ticks driving the schedule lifecycle for both modes), `CropSeasonService` (derives the per-field crop-season list — distinct `cropStartTime` values aggregated from `simulation_result` + `irrigation_history`, with the running season flagged `isCurrent`; backs `GET /mongo/field/{id}/seasons`)
  - `service/anomaly/`: `MlDetectClient` (thin RestTemplate to ml-service `/detect`; fail-soft — connection / timeout / 4xx errors log and return null so the MQTT callback never throws) + `PreferredDetectionMethods` (picks which `/detect` method's verdict to act on per sensor; defaults from the benchmark winners — `temperature/relativeHumidity/radiation/wind` → `seasonal_zscore`, `rain` → `sarima_residual`; override via `ml.detection.preferred-method.<sensorId>`). **Anomaly detection is now two-tier**: `RangeCheckService` / `RangeCheckResult` apply an in-process physical-bounds gate (`anomaly.range.*`) **before** the ml-service call — an out-of-range reading is flagged as a `range_check` `SensorCorrection` and short-circuits (no `/detect` round-trip); in-range readings go to `MlDetectClient`. (These range classes were removed in `c8b0897a` in favour of ml-service's always-on `zscore` detector, then restored as the cheap first gate in `f65fcb9a` — the current state on `master`.)
- **mqtt/**: Both MQTT subsystems share a single Paho client bean (`MqttConfig.operationMqttClient()`) connected to the private mosquitto broker. See `deploy/MQTT.md`.
  - **Operation**: `MqttCommandPublisher` (publishes `OperationCommand` to `cassava/field/{fieldId}/valve/{valveId}/cmd` at QoS 1), `MqttAckListener` (subscribes to `cassava/field/+/valve/+/ack`, dispatches into `IrrigationScheduleService.handleAck`), `MqttTopics` (topic constants), `OperationCommand` / `OperationAck` (JSON POJOs).
  - **Sensor (anomaly)**: `MqttSensorListener` subscribes to `mqtt.sensor.weather-topic` + each `mqtt.sensor.soil-topics`, parses the `key value;` payload, and for **weather** readings runs a two-tier check — **Tier 1** `RangeCheckService` physical bounds (out-of-range → `range_check` `SensorCorrection`, skip ml-service); **Tier 2** (in-range) POSTs each `(groupId, sensorId, time, value)` to ml-service `/detect`, picks the preferred method's verdict, logs `[sensor] OK` or `[sensor] ANOMALY …`, and persists a `SensorCorrection` row on anomaly (raw value still owned by the edge). For **soil** readings it just logs (`[sensor] SOIL …`). `MqttSensorTopics` holds the `t/h/rad/rai/w → temperature/...` key map.
- **entity/**: Two separate `Field` classes (see disambiguation below), plus `User`, `FieldSensor`, `FieldGroup`, `FieldGroupSensor`, `SensorValue`, `FieldSimulationResult`, `IrrigationHistory`, `IrrigationSchedule`, `SensorCorrection` (anomalous reading + imputed value layered on top of `sensor_value`). Also `HistoryIrrigation` — a simulation-only POJO used by `FieldSimulator` to convert in-memory auto-irrigation events into `IrrigationHistory` Mongo docs.
- **repositories/**: Spring Data MongoDB repos under `repositories/mongo/` — includes `FieldGroupRepository`, `FieldGroupSensorRepository`, `FieldSimulationResultRepository`, `IrrigationHistoryRepository`, `IrrigationScheduleRepository`, `SensorCorrectionRepository`
- **DTO/** + **data/**: auth DTOs (`LoginRequest`, `UserResponseDTO`) live in `DTO/`; `data/` holds `ResponseDTO` (the generic API envelope used by `UserController`/`UserService`) and `Constant.java` (sim constants — **dead code**, no longer referenced by any other class). This package formerly also held ~29 experiment CSVs and a stray `demo.zip`; those were removed and the Java source tree is now guarded against re-adding data/binaries (`cassavaBE/src/main/java/**/*.{csv,zip}` in `.gitignore`).

**Field ↔ Group constraint**: `FieldMongoService.create()` rejects a field unless `groupId` references an existing `field_group`. Every field belongs to exactly one group; groups are the unit of weather-station sharing.

**Cascade delete**: `FieldMongoService.delete()` clears `field_sensor`, `sensor_value`, `simulation_result`, `irrigation_history`, and `irrigation_schedule` rows tied to the field before removing the field document. Any new field-scoped collection must be added here to avoid orphaned data. (`sensor_correction` is currently group-scoped — `fieldId` on the entity is nullable — so it is **not** in the cascade; revisit if you start writing field-scoped soil corrections.)

**Reset crop**: `POST /mongo/field/resetCrop/{id}` resets per-crop state on the `Field` Mongo document (startTime, DAP=1, irrigating=false) for a new growing cycle. Note: per-crop history (`simulation_result`, `irrigation_history`) is **retained** and distinguished by `cropStartTime` — it is not cleared on reset.

**Auto vs. manual irrigation**: A field's `autoIrrigation` flag is mutually exclusive with manual scheduling. `IrrigationScheduleService.create()` rejects new schedules while `autoIrrigation=true`, and requires a valid `valveId` in the range 1–4 (either on the schedule or inherited from the field).

**Field mode (SIMULATION vs OPERATION)**: Every Mongo `Field` has a `mode` string (default `SIMULATION`, normalized + validated to one of `SIMULATION` / `OPERATION` in `FieldMongoService`). It is **independent** of `autoIrrigation` and only governs how a manual schedule is *executed*:

- `OPERATION` — the real path. `IrrigationScheduleScheduler` publishes the schedule to the operation MQTT broker (`MqttCommandPublisher`) at its `scheduledTime`, marks `SENT`, and waits for an ack on `cassava/field/+/valve/+/ack`. `MqttAckListener` resolves it to `DONE`/`FAILED`. If no ack arrives within `mqtt.operation.ack-timeout-seconds`, a separate tick marks it `NO_ACK`.
- `SIMULATION` — the demo/dev path. **No MQTT traffic**. The same scheduler tick promotes `PENDING → RUNNING` (sets `startedAt`); a second tick (`completeRunningSimulations`) promotes `RUNNING → DONE` once `startedAt + durationSeconds` has elapsed. The FE shows the same control screens; only the underlying execution differs.

Both modes share the same controller, repo, and FE tab — the divergence is contained inside `IrrigationScheduleScheduler`. Full architecture, payload schemas, and edge subscriber spec live in `deploy/MQTT.md`.
- **Jwt/**: `JwtUtils` (token gen/validation), `JwtAuthFilter` (request filter)
- **config/**: `SecurityConfig` (CORS + auth rules), `WebConfig`
- **firebase/**: legacy directory name — only `CorsConfig` remains (see CORS note below); the Firebase integration has been removed

Auth flow: JWT with 24h expiry (HS512). Roles are ADMIN and USER. Public endpoints: `/api/auth/**` only; `/mongo/**` and `/simulation/**` are permitAll. Token injected via `JwtAuthFilter` in the Spring Security filter chain.

**CORS gotcha**: Two separate configurations exist — `SecurityConfig` restricts CORS to `http://localhost:5173` for Spring Security's filter, and `firebase/CorsConfig.java` registers a `WebMvcConfigurer` bean that applies to `/**` with origins `http://localhost:5173` + `http://112.137.129.218` (the prod IP). In production the FE is served same-origin via nginx so CORS is not actually triggered; the configs matter only for dev and for any direct external caller.

### Frontend (`CassavaFE/src/`)

React 19 + Vite, Ant Design v6, React Router v7, Recharts for charts.

- **pages/**: Grouped by feature —
  - `Auth/` (Login, Register)
  - `Fields/`: `FieldList`, `FieldSoilSensors` (per-field soil moisture), `FieldDetail/` (`index.jsx` + tabs: `DiseaseTab`, `IrrigationTab`, `ManualIrrigationTab`, `YieldTab`, `HistoryTab`), plus feature-local `components/FieldModal`, `components/SimulationDashboard`
  - `FieldGroups/`: `FieldGroupList` + `components/FieldGroupModal` — CRUD for groups that share a weather station
  - `Weather/`: `WeatherGroupList` (group picker), `WeatherDashboard` (per-group `/weather/:groupId`), `WeatherDetail` (per-sensor `/weather/detail/:sensorId`) — weather is accessed via the group, not an individual field
  - `Users/` (UserList)
- **services/**: Four Axios instances, each with `baseURL` built from `import.meta.env.VITE_API_BASE` (fallback `http://localhost:8081`):
  - `api.js` → `${VITE_API_BASE}` — injects `Authorization: Bearer <token>` from `localStorage.user.accessToken`
  - `authService.js` → `${VITE_API_BASE}/api` — same `Authorization: Bearer` interceptor
  - `fieldService.js` → `${VITE_API_BASE}/mongo` — JWT interceptor attached; endpoints are `permitAll` in `SecurityConfig` but the token is still sent
  - `groupService.js` → `${VITE_API_BASE}/mongo/field-group`
- **components/**: `Layout/MainLayout` only (responsive sidebar — `Drawer` on mobile <768px, collapsible `Sider` on desktop). Feature-specific components live under their `pages/<feature>/components/` folder.
- Routing is defined inline in `App.jsx` (e.g. `/fields`, `/fields/:id`, `/fields/:fieldId/soil-sensor`, `/field-groups`, `/weather`, `/weather/:groupId`). Auth guard lives in `MainLayout` — if `localStorage.user` is missing or has no `accessToken`, the layout renders `<Navigate to="/login" replace />` before mounting any child route.

Auth state is managed entirely via `localStorage` (key: `user` with `accessToken`, `id`, `username`, `isAdmin` fields). `services/api.js` has a response interceptor: any 401 clears `localStorage.user` and `window.location` redirects to `/login`. When the FE creates/updates a Field via `FieldList.handleModalSubmit`, it must inject `idUser` into the payload (BE rejects with `Invalid user` otherwise — `FieldModal` does not have an `idUser` form item, so it is added at submit-time from `localStorage.user.id`). No Redux/Zustand/Context is used.

### Two `Field` Entities — Critical Disambiguation

- **`entity/Field.java`** (~820 lines): The **crop simulation model**. Contains `_results`, `_weatherData`, `loadAllWeatherDataFromMongo()`, `runModel()`, soil/plant parameters, and irrigation logic. Used by `FieldSimulator` (Mongo pipeline) and `FieldMongoService`.
- **`entity/MongoEntity/Field.java`**: The **MongoDB document** (collection: `field`). Stores field configuration as flat fields (acreage, fieldCapacity, dripRate, autoIrrigation, etc.). Used by `FieldMongoService` for CRUD.

These are completely separate classes. The simulation model is NOT the MongoDB document.

### IoT Data Flow

Persistence and validation are split across two processes connecting to the same private mosquitto broker:

1. **Edge C binaries (`edge/edge_to_mongo_weather.c`, `edge/edge_to_mongo_soil.c`)** — each is a self-contained single `.c` file with all settings hardcoded in a `CONFIG` block at the top (Mongo URI, MQTT broker + creds, `default_group_id`, weather/soil topics, and the `SOIL_FIELDS` topic→`fieldId` table). Pi3 compiles each directly with `cc -O2 <file>.c $(pkg-config --cflags --libs libmongoc-1.0) -lpaho-mqtt3c`. They subscribe to `/sensor/weatherStation2` (weather) and `field1..4` (per-field soil moisture), parse the `key value;key value;...` payload, and insert into MongoDB `sensor_value` via `libmongoc`. Weather rows are keyed by `groupId` only (no `fieldId`); soil rows are keyed by `fieldId` only (no `groupId`) — the field's group is derivable via the `field→group` lookup. The `fieldId` for soil is resolved from the `SOIL_FIELDS` table in `edge_to_mongo_soil.c`. To add a soil field or rotate creds, edit the constants in the `.c` file and recompile. Setup + run notes live in `edge/README.md`; deploy/systemd in `deploy/DEPLOY.md` §6.
2. **Edge pump controller (`edge/dk_bom_mqtt.c`)** — same standalone-C style; cJSON sources live alongside it (`edge/cJSON.c`, `edge/cJSON.h`). Subscribes `cassava/field/+/valve/+/cmd`, parses JSON `OperationCommand`, drives the relay (publishes `1`/`0` to `Pump<valveId>` per the in-source `RELAYS` table), and replies with `OperationAck` on `…/ack`. Spawns one detached pthread per command so long irrigations don't block the broker callback. Build: `cc -O2 dk_bom_mqtt.c cJSON.c -o dk_bom_mqtt -lpaho-mqtt3c -lpthread`.
3. **BE (`mqtt/MqttSensorListener`)** — subscribes to the same topics on the same broker, parses the same payload, and **for weather** applies a two-tier check: first `RangeCheckService` physical bounds (`anomaly.range.*`) — an out-of-range reading short-circuits to a `range_check` `SensorCorrection`, skipping ml-service; in-range readings forward to ml-service `POST /detect` via `MlDetectClient`. `PreferredDetectionMethods` picks one method's verdict (defaults: `seasonal_zscore` for temperature/relativeHumidity/radiation/wind, `sarima_residual` for rain). On anomaly it logs `WARN [sensor] ANOMALY …` and writes a `SensorCorrection` row (raw `actual` + detector `predicted` + method + score). For **soil** it just logs (`INFO [sensor] SOIL …`). The detection call is fail-soft: if ml-service is unreachable or 4xx the BE logs `detect=skipped` and continues. **The BE does not write `sensor_value`** — that is solely the edge C job; the BE only writes `sensor_correction` on top.

Sensor ID mapping (kept in sync across the `WEATHER_MAP` table in `edge/edge_to_mongo_weather.c` and `MqttSensorTopics.resolveSensorId` on the BE): `t→temperature`, `h→relativeHumidity`, `rai→rain`, `rad→radiation`, `w→wind`, plus soil keys `humidity30` (30cm) and `humidity60` (60cm) which pass through verbatim.

**Sensor units** (canonical, what the C binaries publish and what the BE / FE expect): temperature °C, relativeHumidity %, rain mm/h, **radiation MJ/m²/h** (NOT W/m² — `Field.java` ET formula uses `Rs = radiation` directly, and PPFD = `radiation × 597.22`), wind m/s, humidity30/humidity60 %.

Detection config lives in `application*.properties`: `ml.service.url` (dev `http://localhost:8082`, prod `http://127.0.0.1:8082`), `ml.service.timeout-ms` (default 2000), per-sensor overrides `ml.detection.preferred-method.<sensorId>=<methodName>`, and the **Tier-1** `anomaly.range.<sensor>.{min,max}` physical bounds consumed by `RangeCheckService`. (ml-service additionally runs an always-on `zscore` detector fit per-sensor from the NASA POWER CSV at startup — independent of the BE-side Tier-1 range gate.)

Note: the sensor formerly named `humidity` was renamed to `relativeHumidity` — check for stale references if touching sensor code.

**Runtime artifact**: The Eclipse Paho MQTT client writes a persistence directory (e.g. `paho<id>-tcp...1883/`) into the repo root when the backend runs. It is not source — already gitignored as `paho*/`.

### Crop Simulation Pipeline

1. `GET /simulation/run?fieldId=X` triggers simulation
2. `FieldSimulator` fetches combined hourly sensor data via `SensorValueService.getCombinedValues(groupId, ...)` (a Mongo aggregation that buckets `sensor_value` rows by hour and averages per sensorId), feeds the resulting CSV-style strings into a `Field` object (from `entity/Field.java`) via `loadAllWeatherDataFromMongo()`, runs the model, and saves results to MongoDB
3. If `autoIrrigation` is true, automatically generates `IrrigationHistory` records
4. `GET /simulation/chart?fieldId=X` returns chart data (labels, yield, irrigation, leafArea, labileCarbon)
5. Results are replaced on each run (`deleteByFieldId` then `saveAll`)
6. DOY-to-Date conversion: anchor-based — uses first weather data entry's actual date and DOY as reference, computes all other dates via `baseDate.plusDays(doy - baseDoy)`. Static fields `previousDoy`/`doyOffset` in `Field.java` must be reset via `resetDoyStaticFields()` before each Mongo data load.

**Irrigation units**: Irrigation is **mm** (= L/m²) end-to-end. The simulation model (`Field.java`) produces mm and `FieldSimulator` persists them as-is to MongoDB (`simulation_result.irrigation`, `irrigation_history.amount`). For manual schedules, the FE collects only the duration; `IrrigationScheduleService.markDoneAndRecord()` computes the amount on DONE as `mm = dripRate × durationSeconds / (3600 × distanceBetweenHole × distanceBetweenRow)` (drip-rate × time over the cell area defined by emitter spacing). Frontend charts, history tables, and form labels all display mm. (Historical note: values were previously stored as m³/ha — see migration note below if you're touching legacy data.)

**Manual schedules → `irrigation_history`**: Both modes route their successful completion through `IrrigationScheduleService.markDoneAndRecord(id)`, which (a) sets the schedule's status to `DONE` + persists the computed `amount`, and (b) writes a row to `irrigation_history` so manual irrigation surfaces alongside auto-irrigation events in the history view. The method is **idempotent** — re-entry on a schedule already in a terminal status (`DONE/FAILED/CANCELLED/NO_ACK`) is a no-op. Call sites: `MqttAckListener` (via `handleAck(id, true, …)` for OPERATION mode) and `IrrigationScheduleScheduler.completeRunningSimulations()` (for SIMULATION mode). So `irrigation_history` is no longer auto-only.

### Key External Dependencies

- **MongoDB**: Remote instance at `112.137.129.218:27017`, database `iot_agriculture`
- **MQTT (two-broker topology with bridge)**: pi3 mosquitto (edge-local, anonymous on localhost) + prod mosquitto (loopback on the BE host, authenticated `libe / 123456`). The two are linked by a mosquitto bridge running on pi3 (`deploy/mosquitto/cassava-bridge.conf`) — outbound TCP from pi3 to `112.137.129.218:1883`, forwards cmd/ack/sensor topics. BE configures only the prod side via `mqtt.operation.broker-url` (dev: `tcp://112.137.129.218:1883`, prod: `tcp://127.0.0.1:1883`) + `mqtt.operation.username/password`. Auto-reconnect enabled — BE starts even if the broker is offline. The bridge handles edge-side reconnect transparently.

## Production Deployment

Deployed at `http://112.137.129.218/` on a UET-managed Ubuntu server (user `uet`). Single-port nginx (80) fans traffic path-based:

```
/               → /opt/cassava/webroot/index.html  (landing page: 4 crop cards)
/cassava/       → /opt/cassava/webroot/cassava/    (CassavaFE SPA, base=/cassava/)
/cassava/api/*  → proxy_pass http://127.0.0.1:8081/  (Spring Boot, loopback-only)
```

Key artifacts (all committed under `deploy/`):
- `deploy/nginx/cassava.conf` — site config; proxy trailing slash strips `/cassava/api/`; `proxy_read_timeout 600s` for long simulations
- `deploy/systemd/cassava-be.service` — runs `java -jar /opt/cassava/cassava-be.war` as `uet`, sets `SPRING_PROFILES_ACTIVE=prod`
- `deploy/DEPLOY.md` — full build/upload/install runbook + troubleshooting
- `deploy/MQTT.md` — operation MQTT architecture, payload schemas, schedule lifecycle, mosquitto setup, edge subscriber spec
- `cassavaBE/src/main/resources/application-prod.properties` — activated by prod profile; binds `server.address=127.0.0.1`, logs to `/var/log/cassava/app.log`, points operation MQTT to `tcp://127.0.0.1:1883`
- `landing/` — static HTML landing page with UET logo and one card per crop (cassava active, others "Sắp ra mắt")

FE build path: `vite.config.js` sets `base: '/cassava/'` only when `mode === 'production'`; `App.jsx` passes `basename={import.meta.env.BASE_URL}` into `BrowserRouter` so client-side routes resolve correctly under the subpath. Assets must be imported (`import logoUet from '.../logo-uet.png'`) — never reference `/src/...` paths, which only work in dev.

Update flow: FE-only changes → rebuild + rsync `dist/` to `/opt/cassava/webroot/cassava/`, no restart. BE-only changes → rebuild WAR, rsync, `systemctl restart cassava-be`.

## Git Remote

- **Repository**: `git@github.com:trieu-1802/CASSAVA_IOT.git`
- **Branch**: `master`

## Conventions

- Backend packaging is WAR (servlet-based with embedded Tomcat)
- Java 17 required
- Frontend API calls use four separate Axios instances — check which one matches the endpoint prefix before adding new API calls
- MongoDB collections: `field`, `field_group`, `field_group_sensor`, `field_sensor`, `sensor_value`, `sensor_correction`, `simulation_result`, `irrigation_history`, `irrigation_schedule`, `users`, `diseases`
- Both MQTT subsystems (operation + sensor anomaly) share a single Paho client bean `MqttConfig.operationMqttClient()` against the **prod** mosquitto broker. Edge programs (C binaries on pi3) connect to the **pi3-local** mosquitto; messages cross to BE via the mosquitto bridge.
- Raw sensor persistence is owned by the **edge C binaries** (`edge/edge_to_mongo_weather.c` + `edge/edge_to_mongo_soil.c`) writing `sensor_value`; the Java BE never writes `sensor_value` rows. The BE *does* write `sensor_correction` rows on anomaly (corrected/imputed values layered on top — downstream consumers JOIN by `(time, sensorId)` and prefer `predicted` over the raw value). If you find Java code writing `sensor_value`, it's drift — investigate before adding more.
- When changing the bridge config (`deploy/mosquitto/cassava-bridge.conf`), remember it must be re-applied on pi3 (`/etc/mosquitto/conf.d/`) and `mosquitto` restarted there — BE does not auto-pick up bridge changes since the bridge runs on the edge host, not on prod.
