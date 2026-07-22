/* edge_to_mongo_soil.c — standalone subscriber for the per-field soil moisture
 * MQTT topics; inserts each reading into MongoDB sensor_value as
 * { fieldId, sensorId, value, time }. (No groupId — soil rows are
 * keyed strictly to a field; the group is derivable via field→group lookup.)
 *
 * Build:
 *   cc -O2 edge_to_mongo_soil.c -o edge_to_mongo_soil \
 *      $(pkg-config --cflags --libs libmongoc-1.0) -lpaho-mqtt3c
 *
 * Deps (Debian / Raspberry Pi OS):
 *   sudo apt install -y build-essential pkg-config \
 *                       libpaho-mqtt-dev libmongoc-dev libbson-dev
 *
 * Run:
 *   ./edge_to_mongo_soil
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
#define MQTT_CLIENT_ID   "cassava-edge-soil"

#define QOS              1

/* Topic -> Mongo field._id. To add a new field, append a row here. */
static const struct { const char *topic; const char *field_id; } SOIL_FIELDS[] = {
    {"field1", "69edbb4deb1a2d33076466e9"},
    {"field2", "69edbb76eb1a2d33076466ec"},
    {"field3", "69edbb94eb1a2d33076466ef"},
    {"field4", "69edbbbeeb1a2d33076466f2"},
};
static const int SOIL_FIELDS_LEN = sizeof(SOIL_FIELDS) / sizeof(SOIL_FIELDS[0]);

/* ============================ END CONFIG ============================ */

static const char *lookup_field_id(const char *topic) {
    for (int i = 0; i < SOIL_FIELDS_LEN; i++) {
        if (strcmp(topic, SOIL_FIELDS[i].topic) == 0) return SOIL_FIELDS[i].field_id;
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

static int insert_reading(const char *field_id, const char *sensor_id,
                          double value, int64_t t_ms) {
    bson_t *doc = bson_new();
    bson_oid_t oid;
    bson_oid_init(&oid, NULL);
    BSON_APPEND_OID(doc, "_id", &oid);
    BSON_APPEND_UTF8(doc, "fieldId",  field_id);
    BSON_APPEND_UTF8(doc, "sensorId", sensor_id);
    BSON_APPEND_DOUBLE(doc, "value",  value);
    BSON_APPEND_DATE_TIME(doc, "time", t_ms);

    bson_error_t err;
    bool ok = mongoc_collection_insert_one(g_coll, doc, NULL, NULL, &err);
    if (!ok) {
        fprintf(stderr, "[edge:soil] insert failed (fieldId=%s sensorId=%s): %s\n",
                field_id, sensor_id, err.message);
    }
    bson_destroy(doc);
    return ok ? 0 : -1;
}

static int on_message(void *ctx, char *topic, int topic_len, MQTTClient_message *msg) {
    (void)ctx;
    (void)topic_len;

    char payload[512];
    int n = msg->payloadlen < (int)sizeof(payload) - 1
            ? msg->payloadlen : (int)sizeof(payload) - 1;
    memcpy(payload, msg->payload, n);
    payload[n] = '\0';

    const char *field_id = lookup_field_id(topic);
    if (!field_id) {
        fprintf(stderr, "[edge:soil] unknown topic %s, dropping payload: %s\n",
                topic, payload);
        MQTTClient_freeMessage(&msg);
        MQTTClient_free(topic);
        return 1;
    }

    int64_t t = now_ms();
    Pair pairs[16];
    int count = parse_payload(payload, pairs, 16);

    int inserted = 0;
    for (int i = 0; i < count; i++) {
        if (insert_reading(field_id, pairs[i].key, atof(pairs[i].value), t) == 0) {
            inserted++;
        }
    }
    fprintf(stdout, "[edge:soil] %s (fieldId=%s) inserted %d row(s) (payload: %s)\n",
            topic, field_id, inserted, payload);
    fflush(stdout);

    MQTTClient_freeMessage(&msg);
    MQTTClient_free(topic);
    return 1;
}

static void on_disconnect(void *ctx, char *cause) {
    (void)ctx;
    fprintf(stderr, "[edge:soil] connection lost: %s\n",
            cause ? cause : "unknown");
}

int main(void) {
    signal(SIGINT,  on_signal);
    signal(SIGTERM, on_signal);

    mongoc_init();
    bson_error_t bs_err;
    mongoc_uri_t *uri = mongoc_uri_new_with_error(MONGO_URI, &bs_err);
    if (!uri) {
        fprintf(stderr, "[edge:soil] bad mongo uri: %s\n", bs_err.message);
        return EXIT_FAILURE;
    }
    g_mongo = mongoc_client_new_from_uri(uri);
    mongoc_uri_destroy(uri);
    if (!g_mongo) {
        fprintf(stderr, "[edge:soil] mongoc_client_new failed\n");
        return EXIT_FAILURE;
    }
    mongoc_client_set_appname(g_mongo, "cassava-edge-soil");
    g_coll = mongoc_client_get_collection(g_mongo, MONGO_DB, MONGO_COLLECTION);

    if (MQTTClient_create(&g_mqtt, MQTT_BROKER_URL, MQTT_CLIENT_ID,
                          MQTTCLIENT_PERSISTENCE_NONE, NULL) != MQTTCLIENT_SUCCESS) {
        fprintf(stderr, "[edge:soil] MQTTClient_create failed\n");
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
        fprintf(stderr, "[edge:soil] MQTT connect failed: %d\n", rc);
        return EXIT_FAILURE;
    }

    for (int i = 0; i < SOIL_FIELDS_LEN; i++) {
        if (MQTTClient_subscribe(g_mqtt, SOIL_FIELDS[i].topic, QOS) != MQTTCLIENT_SUCCESS) {
            fprintf(stderr, "[edge:soil] subscribe %s failed\n", SOIL_FIELDS[i].topic);
            continue;
        }
        fprintf(stdout, "[edge:soil] subscribed %s -> fieldId=%s\n",
                SOIL_FIELDS[i].topic, SOIL_FIELDS[i].field_id);
    }
    fprintf(stdout, "[edge:soil] connected %s, %d topic(s)\n",
            MQTT_BROKER_URL, SOIL_FIELDS_LEN);
    fflush(stdout);

    while (g_run) sleep(1);

    MQTTClient_disconnect(g_mqtt, 1000);
    MQTTClient_destroy(&g_mqtt);
    mongoc_collection_destroy(g_coll);
    mongoc_client_destroy(g_mongo);
    mongoc_cleanup();
    return EXIT_SUCCESS;
}
