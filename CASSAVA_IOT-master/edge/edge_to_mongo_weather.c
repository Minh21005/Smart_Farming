/* edge_to_mongo_weather.c — standalone subscriber for the weather station MQTT
 * topic; inserts each reading into MongoDB sensor_value as
 * { groupId, sensorId, value, time }.
 *
 * Build:
 *   cc -O2 edge_to_mongo_weather.c -o edge_to_mongo_weather \
 *      $(pkg-config --cflags --libs libmongoc-1.0) -lpaho-mqtt3c
 *
 * Deps (Debian / Raspberry Pi OS):
 *   sudo apt install -y build-essential pkg-config \
 *                       libpaho-mqtt-dev libmongoc-dev libbson-dev
 *
 * Run:
 *   ./edge_to_mongo_weather
 */

#include <MQTTClient.h>
#include <mongoc/mongoc.h>

#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <signal.h>
#include <unistd.h>
#include <sys/time.h>

/* ============================ CONFIG (edit here) ============================ */

#define MONGO_URI        "mongodb://admin:uet%402026@112.137.129.218:27017/iot_agriculture?authSource=admin"
#define MONGO_DB         "iot_agriculture"
#define MONGO_COLLECTION "sensor_value"

#define MQTT_BROKER_URL  "tcp://localhost:1883"
#define MQTT_USERNAME    "libe"
#define MQTT_PASSWORD    "123456"
#define MQTT_CLIENT_ID   "cassava-edge-weather"

#define DEFAULT_GROUP_ID "69e35b13e405c05c3dab13c9"
#define WEATHER_TOPIC    "#"  // Subscribe to all topics to debug

#define QOS              1

/* ============================ END CONFIG ============================ */

static const struct { const char *key; const char *sensor_id; } WEATHER_MAP[] = {
    {"t",   "temperature"},
    {"h",   "relativeHumidity"},
    {"rad", "radiation"},
    {"rai", "rain"},
    {"w",   "wind"},
};
static const int WEATHER_MAP_LEN = sizeof(WEATHER_MAP) / sizeof(WEATHER_MAP[0]);

static const char *resolve_sensor_id(const char *key) {
    for (int i = 0; i < WEATHER_MAP_LEN; i++) {
        if (strcmp(key, WEATHER_MAP[i].key) == 0) return WEATHER_MAP[i].sensor_id;
    }
    return NULL;
}

static int64_t now_ms(void) {
    struct timeval tv;
    gettimeofday(&tv, NULL);
    return (int64_t)tv.tv_sec * 1000LL + (int64_t)tv.tv_usec / 1000LL;
}

typedef struct { char key[32]; char value[32]; } Pair;

static int parse_payload(const char *payload, Pair *pairs, int max) {
    char buf[512];
    strncpy(buf, payload, sizeof(buf) - 1);
    buf[sizeof(buf) - 1] = '\0';

    int n = 0;
    char *s1 = NULL, *s2 = NULL;
    char *tok = strtok_r(buf, ";", &s1);
    while (tok && n < max) {
        while (*tok == ' ' || *tok == '\t' || *tok == '\n' || *tok == '\r') tok++;
        if (*tok) {
            char copy[64];
            strncpy(copy, tok, sizeof(copy) - 1);
            copy[sizeof(copy) - 1] = '\0';
            char *k = strtok_r(copy, " \t", &s2);
            char *v = strtok_r(NULL, " \t", &s2);
            if (k && v) {
                strncpy(pairs[n].key,   k, 31); pairs[n].key[31]   = '\0';
                strncpy(pairs[n].value, v, 31); pairs[n].value[31] = '\0';
                n++;
            }
        }
        tok = strtok_r(NULL, ";", &s1);
    }
    return n;
}

static mongoc_client_t      *g_mongo;
static mongoc_collection_t  *g_coll;
static MQTTClient            g_mqtt;
static volatile sig_atomic_t g_run = 1;

static void on_signal(int s) { (void)s; g_run = 0; }

static int insert_reading(const char *sensor_id, double value, int64_t t_ms) {
    bson_t *doc = bson_new();
    bson_oid_t oid;
    bson_oid_init(&oid, NULL);
    BSON_APPEND_OID(doc, "_id", &oid);
    BSON_APPEND_UTF8(doc, "groupId",  DEFAULT_GROUP_ID);
    BSON_APPEND_UTF8(doc, "sensorId", sensor_id);
    BSON_APPEND_DOUBLE(doc, "value",  value);
    BSON_APPEND_DATE_TIME(doc, "time", t_ms);

    bson_error_t err;
    bool ok = mongoc_collection_insert_one(g_coll, doc, NULL, NULL, &err);
    if (!ok) {
        fprintf(stderr, "[edge:weather] MongoDB insert failed (sensorId=%s, value=%.2f): %s\n",
                sensor_id, value, err.message);
        fflush(stderr);
    } else {
        fprintf(stdout, "[edge:weather] Successfully inserted: sensorId=%s, value=%.2f\n",
                sensor_id, value);
        fflush(stdout);
    }
    bson_destroy(doc);
    return ok ? 0 : -1;
}

static int on_message(void *ctx, char *topic, int topic_len, MQTTClient_message *msg) {
    (void)ctx;
    (void)topic_len;

    fprintf(stdout, "[edge:weather] Received message on topic: %s\n", topic);
    fflush(stdout);

    char payload[512];
    int n = msg->payloadlen < (int)sizeof(payload) - 1
            ? msg->payloadlen : (int)sizeof(payload) - 1;
    memcpy(payload, msg->payload, n);
    payload[n] = '\0';

    fprintf(stdout, "[edge:weather] Payload: %s\n", payload);
    fflush(stdout);

    int64_t t = now_ms();
    Pair pairs[16];
    int count = parse_payload(payload, pairs, 16);

    fprintf(stdout, "[edge:weather] Parsed %d pairs from payload\n", count);
    fflush(stdout);

    int inserted = 0;
    for (int i = 0; i < count; i++) {
        const char *sid = resolve_sensor_id(pairs[i].key);
        fprintf(stdout, "[edge:weather] Pair %d: key='%s', value='%s', sensor_id='%s'\n",
                i, pairs[i].key, pairs[i].value, sid ? sid : "NULL");
        fflush(stdout);
        if (!sid) continue;
        if (insert_reading(sid, atof(pairs[i].value), t) == 0) inserted++;
    }
    fprintf(stdout, "[edge:weather] %s inserted %d row(s) (payload: %s)\n",
            topic, inserted, payload);
    fflush(stdout);

    MQTTClient_freeMessage(&msg);
    MQTTClient_free(topic);
    return 1;
}

static void on_disconnect(void *ctx, char *cause) {
    (void)ctx;
    fprintf(stderr, "[edge:weather] MQTT connection lost: %s\n",
            cause ? cause : "unknown reason");
    fflush(stderr);
}

int main(void) {
    signal(SIGINT,  on_signal);
    signal(SIGTERM, on_signal);

    mongoc_init();
    bson_error_t bs_err;
    mongoc_uri_t *uri = mongoc_uri_new_with_error(MONGO_URI, &bs_err);
    if (!uri) {
        fprintf(stderr, "[edge:weather] bad mongo uri: %s\n", bs_err.message);
        return EXIT_FAILURE;
    }
    g_mongo = mongoc_client_new_from_uri(uri);
    mongoc_uri_destroy(uri);
    if (!g_mongo) {
        fprintf(stderr, "[edge:weather] mongoc_client_new failed\n");
        return EXIT_FAILURE;
    }
    fprintf(stdout, "[edge:weather] Successfully connected to MongoDB\n");
    fflush(stdout);
    mongoc_client_set_appname(g_mongo, "cassava-edge-weather");
    g_coll = mongoc_client_get_collection(g_mongo, MONGO_DB, MONGO_COLLECTION);
    fprintf(stdout, "[edge:weather] Connected to collection: %s.%s\n", MONGO_DB, MONGO_COLLECTION);
    fflush(stdout);

    if (MQTTClient_create(&g_mqtt, MQTT_BROKER_URL, MQTT_CLIENT_ID,
                          MQTTCLIENT_PERSISTENCE_NONE, NULL) != MQTTCLIENT_SUCCESS) {
        fprintf(stderr, "[edge:weather] MQTTClient_create failed\n");
        return EXIT_FAILURE;
    }
    MQTTClient_setCallbacks(g_mqtt, NULL, on_disconnect, on_message, NULL);

    MQTTClient_connectOptions opts = MQTTClient_connectOptions_initializer;
    opts.keepAliveInterval  = 60;
    opts.cleansession       = 1;
    opts.username           = MQTT_USERNAME;
    opts.password           = MQTT_PASSWORD;

    int rc = MQTTClient_connect(g_mqtt, &opts);
    if (rc != MQTTCLIENT_SUCCESS) {
        fprintf(stderr, "[edge:weather] MQTT connect failed: %d\n", rc);
        return EXIT_FAILURE;
    }
    fprintf(stdout, "[edge:weather] Successfully connected to MQTT broker: %s\n", MQTT_BROKER_URL);
    fflush(stdout);

    if (MQTTClient_subscribe(g_mqtt, WEATHER_TOPIC, QOS) != MQTTCLIENT_SUCCESS) {
        fprintf(stderr, "[edge:weather] subscribe %s failed\n", WEATHER_TOPIC);
        return EXIT_FAILURE;
    }
    fprintf(stdout, "[edge:weather] Successfully subscribed to topic: %s\n", WEATHER_TOPIC);
    fflush(stdout);
    fprintf(stdout, "[edge:weather] connected %s, subscribed %s -> groupId=%s\n",
            MQTT_BROKER_URL, WEATHER_TOPIC, DEFAULT_GROUP_ID);
    fflush(stdout);

    int heartbeat_count = 0;
    while (g_run) {
        sleep(1);
        heartbeat_count++;
        if (heartbeat_count % 60 == 0) {  // Log every 60 seconds
            fprintf(stdout, "[edge:weather] Heartbeat: still running (connected to %s, subscribed to %s)\n",
                    MQTT_BROKER_URL, WEATHER_TOPIC);
            fflush(stdout);
        }
    }

    fprintf(stdout, "[edge:weather] Shutting down...\n");
    fflush(stdout);

    MQTTClient_disconnect(g_mqtt, 1000);
    fprintf(stdout, "[edge:weather] Disconnected from MQTT broker\n");
    fflush(stdout);
    MQTTClient_destroy(&g_mqtt);
    mongoc_collection_destroy(g_coll);
    mongoc_client_destroy(g_mongo);
    mongoc_cleanup();
    fprintf(stdout, "[edge:weather] Clean shutdown completed\n");
    fflush(stdout);
    return EXIT_SUCCESS;
}
