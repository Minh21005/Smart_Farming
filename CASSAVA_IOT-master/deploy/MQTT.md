# MQTT Operation Layer

Architecture, message contracts, and operational runbook for the **operation
MQTT subsystem** that ties Cassava BE to edge controllers (e.g. pi3) so that
manual irrigation schedules trigger physical valves.

> The same private mosquitto broker also carries the **sensor data path**
> (weather + soil moisture readings). Persistence to MongoDB is owned by the
> standalone edge C binaries in `edge/` (`edge_to_mongo_weather`,
> `edge_to_mongo_soil`), each compiled directly with `cc` on pi3 — no
> Makefile, no shared helpers, all settings hardcoded inside the `.c` file.
> The BE only listens on those topics for anomaly detection. See §11 below.

---

## 1. High-Level Topology

```
                    ┌────────────────────────────┐
                    │    Spring Boot BE (8081)    │
                    │                             │
                    │  IrrigationScheduleScheduler│
                    │   ├─ tick 15s: PENDING due  │
                    │   ├─ tick 15s: RUNNING done │
                    │   └─ tick 30s: SENT stale   │
                    │                             │
                    │  MqttCommandPublisher  ─────┼──── publish ───▶ ┐
                    │  MqttAckListener     ◀──────┼──── subscribe ── │
                    └────────────────────────────┘                   │
                                                                     ▼
                                                 ┌─────────────────────────┐
                                                 │   Mosquitto broker       │
                                                 │   prod: 127.0.0.1:1883   │
                                                 │   dev:  112.137.129.218  │
                                                 └─────────────────────────┘
                                                              ▲
                                                              │ MQTT
                                                              ▼
                                                 ┌─────────────────────────┐
                                                 │   Edge controller (pi3)  │
                                                 │   - subscribe cmd        │
                                                 │   - actuate valve(s)     │
                                                 │   - publish ack          │
                                                 └─────────────────────────┘
```

Single broker, two MQTT clients connect to it. No bridge. The BE binds to
the broker at startup (auto-reconnect armed) and so does the edge.

---

## 2. Topic Conventions

All topics are namespaced under `cassava/field/{fieldId}/valve/{valveId}/…`.
The constants live in [MqttTopics.java](../cassavaBE/src/main/java/com/example/demo/mqtt/MqttTopics.java).

| Direction | Topic pattern | QoS | Retained | Purpose |
|-----------|---------------|-----|----------|---------|
| BE → edge | `cassava/field/{fieldId}/valve/{valveId}/cmd` | 1 | false | Command (open valve for N seconds) |
| edge → BE | `cassava/field/{fieldId}/valve/{valveId}/ack` | 1 | false | Acknowledgement of command outcome |

The BE subscribes once at startup to the wildcard `cassava/field/+/valve/+/ack`
(see `MqttAckListener`). Each edge subscribes to its own field/valve cmd
topic (or a per-field wildcard if it controls multiple valves on the same
field).

`fieldId` is the MongoDB ObjectId of the `field` document. `valveId` is the
integer 1–4 stored on the field (or overridden per-schedule).

---

## 3. Payload Schemas

JSON, UTF-8.

### `OperationCommand` (BE → edge)

[OperationCommand.java](../cassavaBE/src/main/java/com/example/demo/mqtt/OperationCommand.java)

```json
{
  "scheduleId":      "65d4f3a1c0e1a23bcd456789",
  "action":          "OPEN_TIMED",
  "durationSeconds": 600,
  "issuedAt":        1745568000000
}
```

| Field | Type | Notes |
|-------|------|-------|
| `scheduleId` | string | Mongo ObjectId of the `irrigation_schedule`. Edge must echo this back in the ack. |
| `action` | string | Currently `OPEN_TIMED`. Reserved: `CLOSE` (immediate close). |
| `durationSeconds` | int | How long to keep the valve open. |
| `issuedAt` | long | Epoch millis when BE published the command. |

### `OperationAck` (edge → BE)

[OperationAck.java](../cassavaBE/src/main/java/com/example/demo/mqtt/OperationAck.java)

```json
{
  "scheduleId":   "65d4f3a1c0e1a23bcd456789",
  "ack":          "DONE",
  "ackAt":        1745568605123,
  "errorMessage": null
}
```

| Field | Type | Notes |
|-------|------|-------|
| `scheduleId` | string | Must match the command's `scheduleId`. Used to route the ack. |
| `ack` | string | `DONE` (success) or `FAILED` (error during execution). |
| `ackAt` | long | Epoch millis when edge finished. |
| `errorMessage` | string | Optional. Required when `ack=FAILED` for diagnostics. |

The BE matches by `scheduleId` only — the topic is informational. If an ack
arrives for an unknown id (e.g. after BE restart), it is logged and ignored.

---

## 4. Schedule Lifecycle

`IrrigationSchedule` lives in MongoDB collection `irrigation_schedule`.
Status enum: `PENDING`, `SENT`, `RUNNING`, `DONE`, `CANCELLED`, `FAILED`,
`NO_ACK`.

The lifecycle depends on the **field's `mode`**.

### 4.1 OPERATION mode (real path)

```
   user
    │ POST /mongo/irrigation-schedule
    ▼
 ┌──────────┐ scheduledTime ≤ now (15s tick)        ┌──────────┐
 │ PENDING  │ ─── publishOpenTimed(...) ──────────▶ │   SENT   │
 └──────────┘     setSentAt(now)                    └──────────┘
                                                      │
                                  ┌───────────────────┼─────────────────────┐
                                  │ ack=DONE          │ ack=FAILED          │ no ack > timeout
                                  ▼                   ▼                     ▼
                              ┌──────┐           ┌────────┐           ┌──────────┐
                              │ DONE │           │ FAILED │           │  NO_ACK  │
                              └──────┘           └────────┘           └──────────┘
```

Driven by:

- `IrrigationScheduleScheduler.publishDueSchedules()` — every 15 s. Queries
  `findByStatusAndScheduledTimeBeforeOrderByScheduledTimeAsc(PENDING, now)`.
  For OPERATION fields: publish `OperationCommand`, then `markSent` (status
  becomes `SENT`, `sentAt` is set). On publish exception, leave status
  `PENDING` and retry next tick.
- `MqttAckListener.subscribe()` — at startup. On every ack message, calls
  `IrrigationScheduleService.handleAck(scheduleId, ok, errorMessage)` which
  flips the row to `DONE`/`FAILED` with `finishedAt`.
- `IrrigationScheduleScheduler.markStaleAsNoAck()` — every 30 s. For rows
  still in `SENT` past `mqtt.operation.ack-timeout-seconds`, flip to
  `NO_ACK` with `errorMessage="Edge không phản hồi sau …s"`.

### 4.2 SIMULATION mode (demo path, no MQTT)

```
   user
    │ POST /mongo/irrigation-schedule
    ▼
 ┌──────────┐ scheduledTime ≤ now (15s tick)         ┌──────────┐
 │ PENDING  │ ─── updateStatus(RUNNING) ───────────▶ │ RUNNING  │
 └──────────┘     setStartedAt(now)                  └──────────┘
                                                       │
                                                       │ startedAt + duration ≤ now (15s tick)
                                                       ▼
                                                   ┌──────┐
                                                   │ DONE │
                                                   └──────┘
```

Driven by the same scheduler, separate branches:

- `publishDueSchedules()` — for SIMULATION fields, calls
  `updateStatus(RUNNING, null)`. **No MQTT publish.** `startedAt` is set as
  a side effect.
- `completeRunningSimulations()` — every 15 s. Queries `findByStatus(RUNNING)`.
  For each row where `startedAt + durationSeconds * 1000 ≤ now`, calls
  `updateStatus(DONE, null)`.

OPERATION rows never enter `RUNNING` (they go `PENDING → SENT → DONE`), so
`completeRunningSimulations()` only ever touches simulation rows.

### 4.3 Manual cancellation

`PUT /mongo/irrigation-schedule/{id}/cancel` flips `PENDING → CANCELLED`.
Cancellation is rejected for any other status — once a command has gone out
to MQTT (`SENT`) or a simulation has started (`RUNNING`) we do not yank it
back.

---

## 5. Backend Components

| Class | Responsibility |
|-------|----------------|
| [MqttConfig](../cassavaBE/src/main/java/com/example/demo/mqtt/MqttConfig.java) | Builds the singleton `MqttClient` bean for the operation broker. `cleanSession=false` so subscriptions survive reconnects; `setAutomaticReconnect(true)` so BE survives broker outages. Initial connect failure is logged but **does not throw** — the client returns and Paho retries in the background. |
| [MqttTopics](../cassavaBE/src/main/java/com/example/demo/mqtt/MqttTopics.java) | Topic pattern + action/ack constants. Centralized so edge and BE stay aligned. |
| [OperationCommand](../cassavaBE/src/main/java/com/example/demo/mqtt/OperationCommand.java) / [OperationAck](../cassavaBE/src/main/java/com/example/demo/mqtt/OperationAck.java) | JSON payload POJOs (Jackson). |
| [MqttCommandPublisher](../cassavaBE/src/main/java/com/example/demo/mqtt/MqttCommandPublisher.java) | `publishOpenTimed(fieldId, valveId, scheduleId, durationSeconds)` serialises an `OperationCommand` and publishes at QoS 1, non-retained. |
| [MqttAckListener](../cassavaBE/src/main/java/com/example/demo/mqtt/MqttAckListener.java) | `@PostConstruct` subscribes the wildcard ack topic; on each message, deserialises and calls `IrrigationScheduleService.handleAck`. |
| [IrrigationScheduleScheduler](../cassavaBE/src/main/java/com/example/demo/service/Mongo/IrrigationScheduleScheduler.java) | Three `@Scheduled` ticks: dispatch due `PENDING`, complete `RUNNING` simulations, mark stale `SENT` as `NO_ACK`. |
| [IrrigationScheduleService](../cassavaBE/src/main/java/com/example/demo/service/Mongo/IrrigationScheduleService.java) | Domain service: validation on `create`, status transitions (`markSent`, `handleAck`, `updateStatus`, `cancel`), repo queries (`getDuePending`, `getStaleSent`, `getRunning`). |

---

## 6. Configuration

Set via `application.properties` / `application-prod.properties` or env
vars (Spring relaxed binding: `MQTT_OPERATION_BROKER_URL` etc.).

| Property | Default | Dev value | Prod value | Purpose |
|----------|---------|-----------|------------|---------|
| `mqtt.operation.broker-url` | `tcp://localhost:1883` | `tcp://112.137.129.218:1883` | `tcp://127.0.0.1:1883` | Operation broker URL |
| `mqtt.operation.client-id` | `cassava-be` | `cassava-be-dev` | `cassava-be-prod` | Stable per-environment client id (used with `cleanSession=false`) |
| `mqtt.operation.username` | empty | empty | empty | Optional auth |
| `mqtt.operation.password` | empty | empty | empty | Optional auth |
| `mqtt.operation.ack-timeout-seconds` | `60` | `60` | `60` | After this many seconds in `SENT`, the row flips to `NO_ACK` |

`client-id` **must be unique** per running BE instance per broker — two
clients with the same id cleanSession=false will keep kicking each other
off. The dev/prod ids are different so a developer running locally does not
disturb the production session. If you ever run two prod replicas, give
them distinct ids and audit broker-side queue ownership.

---

## 7. Edge Subscriber Spec

Reference behaviour for the edge controller (pi3 or similar). Any
implementation language works as long as it speaks MQTT 3.1.1.

**Connection**
- Broker URL: same broker the BE uses.
- `clientId`: stable per device (e.g. `pi3-fieldA`). Distinct from the BE id.
- `cleanSession`: false (so missed commands during a quick reboot are
  redelivered).
- QoS for subscribe and publish: **1**.

**Subscribe**
- Topic per managed field/valve, or a per-field wildcard:
  `cassava/field/{fieldId}/valve/+/cmd`.

**On `OperationCommand` received**
1. Parse JSON. Reject if `action` is unknown or `durationSeconds <= 0`.
2. Open the valve mapped to the topic's `{valveId}` segment.
3. Sleep `durationSeconds`, then close.
4. Publish an `OperationAck` to `cassava/field/{fieldId}/valve/{valveId}/ack`
   with the same `scheduleId` and `ack="DONE"`. Set `ackAt` to now.
5. If anything fails (valve hardware, sensor read, watchdog), still publish
   an ack with `ack="FAILED"` and `errorMessage` describing the cause —
   silence is the worst outcome because it strands the BE row in `SENT`
   until `NO_ACK`.

**Idempotency**
- The BE retries publishing only on publish-side failures. It does **not**
  re-issue the same `scheduleId` after a successful `SENT` flip. So edges
  do not need to deduplicate by `scheduleId` — but they should ignore
  duplicate deliveries from the broker (Paho redelivers QoS 1 on resume).
  A small in-memory LRU of recently-seen ids is enough.

**Restart resilience**
- With `cleanSession=false`, a brief edge restart will receive any commands
  the broker held during the gap. Long outages exceeding broker queue limits
  will not be replayed; in those cases the BE row will hit `NO_ACK` and the
  user can re-schedule manually.

---

## 8. Mosquitto Setup (production server)

Run on the same host as the BE so the BE can bind to `127.0.0.1:1883`.

```bash
sudo apt update && sudo apt install -y mosquitto mosquitto-clients

# Bind on all interfaces is the default; for now keep it open on the LAN
# so the edge can reach it from another machine. Restrict via UFW or ACL.
sudo systemctl enable --now mosquitto

# Smoke test from the same host
mosquitto_sub -h 127.0.0.1 -t 'cassava/#' -v &
mosquitto_pub -h 127.0.0.1 -t 'cassava/test' -m 'hello'
```

If you want auth (recommended once the edge identity is settled):

```bash
# The edge sensors today already authenticate with libe/123456 — match that
# so the existing firmware doesn't need re-flashing.
sudo mosquitto_passwd -c /etc/mosquitto/passwd libe   # set 123456 when prompted

# /etc/mosquitto/conf.d/auth.conf
listener 1883
allow_anonymous false
password_file /etc/mosquitto/passwd

sudo systemctl restart mosquitto
```

The same `libe / 123456` credentials are already wired into the prod profile
(`cassavaBE/src/main/resources/application-prod.properties`):

```
mqtt.operation.username=libe
mqtt.operation.password=123456
```

…and into the `MQTT_USERNAME` / `MQTT_PASSWORD` constants hardcoded at the
top of `edge/edge_to_mongo_weather.c` + `edge/edge_to_mongo_soil.c`.
Both subsystems (operation cmd/ack + sensor weather/soil) share this single
account because the broker is single-tenant for now.

ACLs (optional, more granular): use `acl_file` in `/etc/mosquitto/conf.d/`
to scope each user to their own topic prefix. Out of scope for the initial
cutover — keep auth-only until topology stabilises.

**Firewall**

The BE talks to mosquitto over loopback, so 1883 does **not** need to be
open on the public interface as long as edges sit on the LAN/VPN. If the
edge connects from outside the LAN, expose 1883 via UFW:

```bash
sudo ufw allow from <edge-cidr> to any port 1883 proto tcp
```

Do not open 1883 to `0.0.0.0` without auth.

---

## 9. Operational Runbook

### Smoke test the BE → broker path
```bash
# On the server
mosquitto_sub -h 127.0.0.1 -t 'cassava/field/+/valve/+/cmd' -v
# Then via FE: create a manual schedule on an OPERATION field with
# scheduledTime = now + 1 min. Within ~15s of that minute, the topic
# should print one line.
```

### Smoke test the edge → BE path
```bash
# Pick a known scheduleId from Mongo while the row is in SENT
mosquitto_pub -h 127.0.0.1 \
  -t 'cassava/field/<fieldId>/valve/1/ack' \
  -m '{"scheduleId":"<id>","ack":"DONE","ackAt":1745568000000}'
# Refresh the FE schedule list — the row should flip to DONE.
```

### Tail BE logs
```bash
sudo journalctl -u cassava-be -f | grep -E 'MQTT|Schedule'
```

### Common scenarios

| Symptom | Likely cause | Fix |
|---------|--------------|-----|
| Row stuck in `PENDING` past its scheduled time | Scheduler not running / field not found | Check BE is up; check `field` doc still exists; check log for `Publish schedule X failed` |
| Row stuck in `SENT` then flips to `NO_ACK` | Edge offline or not subscribed | Check edge is up, subscribed, broker reachable |
| Row immediately goes `RUNNING → DONE` instead of `SENT → DONE` | The field is in `SIMULATION` mode | Switch the field to `OPERATION` if you want real MQTT delivery |
| `MQTT operation broker connect failed` in BE log at startup | Mosquitto not running or not reachable | Start mosquitto, verify firewall; BE will auto-reconnect on its own |
| Two BEs fighting (commands appear duplicated then disappear) | Same `client-id`, `cleanSession=false` collision | Give each replica a unique `mqtt.operation.client-id` |

---

## 10. What's Out of Scope (intentionally)

- **TLS** — broker traffic is plaintext for now. Acceptable while LAN-only;
  add TLS before exposing 1883 to the internet.
- **Per-edge ACLs** — single-tenant scope. Will revisit when more devices
  join.
- **Streaming valve telemetry** — edges only ack at completion. Mid-run
  telemetry (flow rate, soil moisture during irrigation) belongs in the
  weather/sensor pipeline, not here.
- **Reissue of `NO_ACK` rows** — once a row hits `NO_ACK`, a human (via FE)
  decides whether to re-schedule. There is no automatic retry by design;
  retry semantics for physical actuation are too risky to default-on.

---

## 11. Sensor Data Path

The same mosquitto broker carries weather and soil-moisture readings from
the edge. **Two independent consumers** subscribe to the same topics with
different responsibilities:

```
                    ┌─────────────────────────┐
                    │   Edge sensors (pi3)     │
                    │   t/h/rad/rai/w  →  /sensor/weatherStation2
                    │   humidity30/60  →  field1..field4
                    └────────────┬────────────┘
                                 │ publish (key value;key value;)
                                 ▼
                    ┌─────────────────────────┐
                    │   Mosquitto broker       │
                    │   libe / 123456 (auth)   │
                    └──┬──────────────────┬───┘
                       │                  │
        subscribe ─────┘                  └───── subscribe
                       │                  │
                       ▼                  ▼
        ┌──────────────────────┐  ┌──────────────────────────┐
        │ edge_to_mongo_*       │  │ MqttSensorListener (BE)   │
        │ C binaries on pi3     │  │ + RangeCheckService       │
        │  libmongoc insert ────┼──▶  logs OK / RANGE_FAIL     │
        └──────────────────────┘  └──────────────────────────┘
                       │
                       ▼
                 MongoDB sensor_value
                 (source: "mqtt")
```

### 11.1 Topics + payload

| Topic                       | Direction      | Payload                                      | Persisted by           | Validated by         |
|-----------------------------|----------------|----------------------------------------------|------------------------|----------------------|
| `/sensor/weatherStation2`    | edge → broker  | `t 25.3;h 60;rad 800;rai 0;w 1.2`            | `edge_to_mongo_weather`| `MqttSensorListener` |
| `field1` … `field4`         | edge → broker  | `humidity30 32.1;humidity60 28.5`            | `edge_to_mongo_soil`   | `MqttSensorListener` |

Both subsystems use the same plain-text format: `key value` pairs separated
by `;`. Weather keys are abbreviated (`t/h/rad/rai/w`) and mapped to
canonical sensor IDs on both sides:

- Edge C: the `WEATHER_MAP` table at the top of `edge/edge_to_mongo_weather.c`
- BE Java: `mqtt.MqttSensorTopics.resolveSensorId`

Soil keys (`humidity30`, `humidity60`, …) pass through verbatim.

### 11.2 Persistence (edge C)

Pi3 runs the C binaries `edge/edge_to_mongo_weather` + `edge/edge_to_mongo_soil`.
Each `.c` file is self-contained — compiled directly with `cc` against
`libpaho-mqtt3c` + `libmongoc-1.0`, no Makefile, no shared helpers. Each
writes `sensor_value` documents:

```jsonc
// Weather row (edge_to_mongo_weather): groupId only.
{
  "groupId":  "<DEFAULT_GROUP_ID>",
  "sensorId": "temperature" | "relativeHumidity" | "rain" | "radiation" | "wind",
  "value":    25.3,
  "time":     ISODate("..."),
  "source":   "mqtt"
}

// Soil row (edge_to_mongo_soil): fieldId only — group is derivable via field→group.
{
  "fieldId":  "<from SOIL_FIELDS table>",
  "sensorId": "humidity30" | "humidity60" | ...,
  "value":    32.1,
  "time":     ISODate("..."),
  "source":   "mqtt"
}
```

Configuration is hardcoded in the `CONFIG` block at the top of each `.c`
file (Mongo URI, MQTT creds). `edge_to_mongo_weather.c` additionally has
`DEFAULT_GROUP_ID`; `edge_to_mongo_soil.c` has the `SOIL_FIELDS` table
mapping topic → `fieldId`. Edit and recompile to change. Build + run
commands live in `edge/README.md`.

### 11.3 Validation (BE Java)

`MqttSensorListener` (`@PostConstruct`) reuses the existing operation
`MqttClient` bean to subscribe to:

- `mqtt.sensor.weather-topic` (default `/sensor/weatherStation2`)
- each entry of `mqtt.sensor.soil-topics` (default `field1,field2,field3,field4,field2.1,field4.1` — listening is harmless even if the edge C does not publish on the `.1` topics today)

For every `(sensorId, value)` pair it calls `RangeCheckService.check(...)`,
which returns valid/invalid based on per-sensor thresholds:

| sensorId           | min  | max   | property prefix                  |
|--------------------|------|-------|----------------------------------|
| `temperature`      | -10  | 60    | `anomaly.range.temperature.*`    |
| `relativeHumidity` | 0    | 100   | `anomaly.range.relativeHumidity.*` |
| `rain`             | 0    | 500   | `anomaly.range.rain.*`           |
| `radiation`        | 0    | 1500  | `anomaly.range.radiation.*`      |
| `wind`             | 0    | 50    | `anomaly.range.wind.*`           |
| `humidity30`       | 0    | 100   | `anomaly.range.humidity30.*`     |
| `humidity60`       | 0    | 100   | `anomaly.range.humidity60.*`     |

Sensors with no configured threshold pass through (no opinion). The
listener does **not** persist anything — it only logs.

Tier 2 (Z-score), Tier 3 (Seasonal Z-score), and Tier 4 (ML imputation via
Python FastAPI) per `Anomaly_detection.docx` are out of scope for the
current code. They can be added as additional services that consume
the same `(sensorId, value)` stream — a pipeline interface will be designed
when those tiers land.

### 11.4 Smoke test

```bash
# Publish a clean reading
mosquitto_pub -h localhost -t /sensor/weatherStation2 \
  -m "t 25.3;h 60;rad 800;rai 0;w 1.2" -u libe -P 123456

# BE log expectation
# INFO  [sensor] OK topic=/sensor/weatherStation2 sensorId=temperature value=25.3
# (one line per parsed key)

# Mongo expectation
mongosh "$MONGO_URI" --eval \
  'db.sensor_value.find({groupId:"<default_group_id>"}).sort({time:-1}).limit(5)'
# 5 rows, each with source:"mqtt"

# Now publish an anomaly
mosquitto_pub -h localhost -t /sensor/weatherStation2 \
  -m "t 999;h 60;rad 800;rai 0;w 1.2" -u libe -P 123456

# BE log expectation
# WARN  [sensor] RANGE_FAIL topic=/sensor/weatherStation2 sensorId=temperature value=999.0 min=-10.0 max=60.0
# Mongo: row still inserted by edge_to_mongo_weather (source:"mqtt"); BE only logs.
```
