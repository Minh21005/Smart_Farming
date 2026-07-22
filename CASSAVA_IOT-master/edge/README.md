# Edge MQTT programs (C, standalone)

Three single-file C programs run on the edge node (pi3), each subscribing to
the local mosquitto broker for a different slice of the system:

- **Two ingest programs** (`edge_to_mongo_weather.c`, `edge_to_mongo_soil.c`)
  write raw sensor readings into MongoDB `sensor_value`. They replace the
  BE-side `MqttWeatherService` (deleted) — persistence is owned by the edge
  layer; the BE only listens for anomaly detection (`MqttSensorListener`).
- **One pump controller** (`dk_bom_mqtt.c`) subscribes to the operation
  cmd topic, actuates the pump (publishes `1`/`0` to the legacy
  `Pump<valveId>` relay topic), and replies with the JSON ack expected by
  the BE.

Each `.c` file is self-contained — broker URL, credentials, default groupId,
topic→fieldId tables, valve→relay tables — all are constants at the top of
the file. Edit the `CONFIG` block in the source, recompile, run.

## Files

| File | Role |
|------|------|
| `edge_to_mongo_weather.c` | Subscriber for `/sensor/weatherStation2`, inserts into `sensor_value`. |
| `edge_to_mongo_soil.c`    | Subscriber for `field1..field4`, inserts into `sensor_value` with `fieldId`. |
| `dk_bom_mqtt.c`           | Subscriber for `cassava/field/+/valve/+/cmd`, actuates `Pump<valveId>`, publishes JSON ack on `…/ack`. |

## Build

```bash
sudo apt install -y build-essential pkg-config \
                    libpaho-mqtt-dev libmongoc-dev libbson-dev

# Ingest (libmongoc + paho)
cc -O2 edge_to_mongo_weather.c -o edge_to_mongo_weather \
   $(pkg-config --cflags --libs libmongoc-1.0) -lpaho-mqtt3c

cc -O2 edge_to_mongo_soil.c -o edge_to_mongo_soil \
   $(pkg-config --cflags --libs libmongoc-1.0) -lpaho-mqtt3c

# Pump controller (place cJSON.{c,h} alongside dk_bom_mqtt.c, then)
cc -O2 dk_bom_mqtt.c cJSON.c -o dk_bom_mqtt \
   -lpaho-mqtt3c -lpthread
```

## Edit constants if your environment differs

All three files share the connection block at the top:

```c
#define MONGO_URI        "mongodb://admin:uet%402026@112.137.129.218:27017/iot_agriculture?authSource=admin"
#define MQTT_BROKER_URL  "tcp://localhost:1883"
#define MQTT_USERNAME    "libe"
#define MQTT_PASSWORD    "123456"
```

`edge_to_mongo_weather.c` additionally defines `DEFAULT_GROUP_ID` — weather
readings are keyed by `groupId` (no `fieldId`) since a weather station is
shared across all fields in a group:

```c
#define DEFAULT_GROUP_ID "69e35b13e405c05c3dab13c9"
```

`edge_to_mongo_soil.c` defines a topic→`fieldId` table — soil readings are
keyed by `fieldId` only (no `groupId`); the group is derivable via the
field→group lookup in MongoDB:

```c
static const struct { const char *topic; const char *field_id; } SOIL_FIELDS[] = {
    {"field1", "69edbb4deb1a2d33076466e9"},
    {"field2", "69edbb76eb1a2d33076466ec"},
    {"field3", "69edbb94eb1a2d33076466ef"},
    {"field4", "69edbbbeeb1a2d33076466f2"},
};
```

To add a new field, append a row and recompile.

`dk_bom_mqtt.c` defines a valveId→relay-topic table:

```c
static const struct { int valve_id; const char *relay_topic; } RELAYS[] = {
    {1, "Pump1"},
    {2, "Pump2"},
    {3, "Pump3"},
    {4, "Pump4"},
};
```

`MAX_DURATION_SEC` (default 3600) caps how long a single OPEN_TIMED command
can keep the valve open; longer durations are rejected with `FAILED` /
`"invalid duration"`.

## Run

```bash
./edge_to_mongo_weather &
./edge_to_mongo_soil &
./dk_bom_mqtt &
```

On prod, run as systemd units (one per process). See `deploy/DEPLOY.md` §6.

## Verify

```bash
# Publish a fake weather reading
mosquitto_pub -h localhost -t /sensor/weatherStation2 \
  -m "t 25.3;h 60;rad 800;rai 0;w 1.2" -u libe -P 123456

# Check Mongo
mongosh "mongodb://admin:uet%402026@112.137.129.218:27017/iot_agriculture?authSource=admin" \
  --eval 'db.sensor_value.find({source:"mqtt"}).sort({time:-1}).limit(5)'
```

A successful run logs `[edge:weather] /sensor/weatherStation2 inserted 5 row(s) (...)`.

## Verify pump controller

`dk_bom_mqtt` waits for an `OperationCommand` JSON on
`cassava/field/+/valve/+/cmd`. Smoke test by hand:

```bash
# Publish a fake BE command for a 5-second irrigation on field 'fakeField',
# valve 1. scheduleId is arbitrary — pick something you can recognise in logs.
mosquitto_pub -h localhost \
  -t 'cassava/field/fakeField/valve/1/cmd' \
  -u libe -P 123456 \
  -m '{"scheduleId":"smoke-1","action":"OPEN_TIMED","durationSeconds":5,"issuedAt":0}'

# Watch the relay topic — you should see "1" then "0" 5s later
mosquitto_sub -h localhost -t 'Pump1' -u libe -P 123456 -v &

# Watch the ack come back
mosquitto_sub -h localhost -t 'cassava/field/+/valve/+/ack' -u libe -P 123456 -v
# expect a single line with payload: {"scheduleId":"smoke-1","ack":"DONE",...}
```

For an end-to-end test through the BE, create a manual irrigation schedule
on an `OPERATION`-mode field via the FE; within 15s of the scheduled time
the BE publishes the cmd, `dk_bom_mqtt` actuates and acks, and the schedule
flips `PENDING → SENT → DONE`. See `deploy/MQTT.md` §4.1 for the lifecycle
and §9 for the operational runbook.

## Mosquitto bridge (pi3 ↔ prod broker)

If the pi3 runs its own mosquitto (so edge programs talk to `localhost`) and
the BE lives on the prod host's mosquitto (`112.137.129.218:1883`), forward
the cassava topics with the bridge config in `deploy/mosquitto/cassava-bridge.conf`:

```bash
sudo cp deploy/mosquitto/cassava-bridge.conf /etc/mosquitto/conf.d/
sudo systemctl restart mosquitto
mosquitto_sub -h localhost -t '$SYS/broker/connection/cassava-prod/state' -v
# 1 = bridge connected to prod
```

What flows through:
- `cassava/field/+/valve/+/cmd` — prod → pi3 (so `dk_bom_mqtt` receives BE commands)
- `cassava/field/+/valve/+/ack` — pi3 → prod (so the BE receives acks)
- `/sensor/weatherStation2`, `field1..field4` — pi3 → prod (so the BE listener
  and the prod-side `edge_to_mongo_*` see them)

If pi3 connects directly to the prod broker (no local mosquitto), this
bridge is unnecessary — set `MQTT_BROKER_URL` in each `.c` to
`tcp://112.137.129.218:1883` and skip the install.

## Payload format

Both topics use the same plain-text format the existing edge sensors already
publish:

```
t 25.3;h 60;rad 800;rai 0;w 1.2
humidity30 32.1;humidity60 28.5
```

`key value` pairs separated by `;`. Weather keys are abbreviated
(`t`, `h`, `rad`, `rai`, `w`) and mapped to canonical `sensorId`s by the
`WEATHER_MAP` table in `edge_to_mongo_weather.c`. Soil keys are passed
through verbatim — name them as you want them stored in `sensor_value.sensorId`.

Document shape inserted (matches `MongoEntity/SensorValue.java`):

Weather row — `groupId` only, no `fieldId`:
```json
{
  "_id": "<ObjectId>",
  "groupId": "69e35b13e405c05c3dab13c9",
  "sensorId": "temperature",
  "value": 25.3,
  "time": "<ISODate, UTC>",
  "source": "mqtt"
}
```

Soil row — `fieldId` only, no `groupId`:
```json
{
  "_id": "<ObjectId>",
  "fieldId": "69edbb4deb1a2d33076466e9",
  "sensorId": "humidity30",
  "value": 32.1,
  "time": "<ISODate, UTC>",
  "source": "mqtt"
}
```

## Note on credentials in source

The Mongo URI and MQTT password are hardcoded in the `.c` files (and therefore
in git). If you need to keep credentials out of the repository, switch to a
config-file build instead.
