#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>
#include <time.h>
#include <signal.h>
#include <sys/time.h>

#include <MQTTClient.h>
#include <curl/curl.h>

#include <mongoc/mongoc.h>

/* ============================ MQTT CONFIG ============================ */

#define MQTT_BROKER     "tcp://localhost:1883"

#define MQTT_CLIENT_ID  "soil_merged"

#define MQTT_USERNAME   "libe"
#define MQTT_PASSWORD   "123456"

#define MQTT_QOS        1
#define MQTT_TIMEOUT    10000L

/* ============================ FIREBASE CONFIG ============================ */

#define FIREBASE_URL \
"https://directionproject-1e798-default-rtdb.firebaseio.com/user"

#define LOG_FILE_PATH \
"/home/student/Desktop/soil_moisture_data.txt"

/* ============================ MONGODB CONFIG ============================ */

#define MONGO_URI \
"mongodb://admin:uet%%402026@112.137.129.218:27017/iot_agriculture?authSource=admin"

#define MONGO_DB         "iot_agriculture"
#define MONGO_COLLECTION "sensor_value"

/* ============================ FIELD MAP ============================ */

static const struct {
    const char *topic;
    const char *field_id;
} SOIL_FIELDS[] = {
    {"field1", "69edbb4deb1a2d33076466e9"},
    {"field2", "69edbb76eb1a2d33076466ec"},
    {"field3", "69edbb94eb1a2d33076466ef"},
    {"field4", "69edbbbeeb1a2d33076466f2"},
};

static const int SOIL_FIELDS_LEN =
    sizeof(SOIL_FIELDS) / sizeof(SOIL_FIELDS[0]);

/* ============================ GLOBALS ============================ */

MQTTClient client;

mongoc_client_t *g_mongo = NULL;
mongoc_collection_t *g_coll = NULL;

static volatile sig_atomic_t running = 1;

/* ============================ UTILS ============================ */

void handle_signal(int sig) {
    (void)sig;
    running = 0;
}

static const char *lookup_field_id(
    const char *topic
) {
    for (int i = 0; i < SOIL_FIELDS_LEN; i++) {

        if (strcmp(topic,
                   SOIL_FIELDS[i].topic) == 0) {

            return SOIL_FIELDS[i].field_id;
        }
    }

    return NULL;
}

void getCurrentTime(
    char *dateStr,
    char *timeStr
) {
    time_t now = time(NULL);

    struct tm *tm_info = localtime(&now);

    strftime(dateStr,
             16,
             "%Y-%m-%d",
             tm_info);

    strftime(timeStr,
             16,
             "%H:%M:%S",
             tm_info);
}

static int64_t now_ms(void) {

    struct timeval tv;

    gettimeofday(&tv, NULL);

    return (int64_t)tv.tv_sec * 1000LL +
           (int64_t)tv.tv_usec / 1000LL;
}

void writeToLogFile(
    const char *topic,
    const char *dateStr,
    const char *timeStr,
    const char *payload
) {

    FILE *file = fopen(LOG_FILE_PATH, "a");

    if (file) {

        fprintf(file,
                "%s %s %s: %s\n",
                topic,
                dateStr,
                timeStr,
                payload);

        fclose(file);
    }
}

size_t writeCallback(
    void *contents,
    size_t size,
    size_t nmemb,
    void *userp
) {
    return size * nmemb;
}

/* ============================ FIREBASE ============================ */

void set_data_firebase(
    const char* path,
    const char* json_data
) {

    CURL *curl;

    CURLcode res;

    char full_url[512];

    snprintf(full_url,
             sizeof(full_url),
             "%s/%s.json",
             FIREBASE_URL,
             path);

    curl = curl_easy_init();

    if (curl) {

        curl_easy_setopt(curl,
                         CURLOPT_URL,
                         full_url);

        curl_easy_setopt(curl,
                         CURLOPT_CUSTOMREQUEST,
                         "PUT");

        curl_easy_setopt(curl,
                         CURLOPT_POSTFIELDS,
                         json_data);

        curl_easy_setopt(curl,
                         CURLOPT_WRITEFUNCTION,
                         writeCallback);

        res = curl_easy_perform(curl);

        if (res != CURLE_OK) {

            fprintf(stderr,
                    "Firebase failed: %s\n",
                    curl_easy_strerror(res));
        }

        curl_easy_cleanup(curl);
    }
}

/* ============================ MONGODB ============================ */

static int insert_reading(
    const char *field_id,
    const char *sensor_id,
    double value,
    int64_t t_ms
) {

    bson_t *doc = bson_new();

    bson_oid_t oid;

    bson_oid_init(&oid, NULL);

    BSON_APPEND_OID(doc, "_id", &oid);

    BSON_APPEND_UTF8(doc,
                     "fieldId",
                     field_id);

    BSON_APPEND_UTF8(doc,
                     "sensorId",
                     sensor_id);

    BSON_APPEND_DOUBLE(doc,
                       "value",
                       value);

    BSON_APPEND_DATE_TIME(doc,
                          "time",
                          t_ms);

    bson_error_t err;

    bool ok = mongoc_collection_insert_one(
        g_coll,
        doc,
        NULL,
        NULL,
        &err
    );

    if (!ok) {

        fprintf(stderr,
                "[MongoDB] insert failed: %s\n",
                err.message);
    }
    else {

        printf("[MongoDB] inserted "
               "field=%s sensor=%s value=%.2f\n",
               field_id,
               sensor_id,
               value);
    }

    bson_destroy(doc);

    return ok ? 0 : -1;
}

/* ============================ PARSE ============================ */

typedef struct {
    char key[32];
    char value[32];
} Pair;

static int parse_payload(
    const char *payload,
    Pair *pairs,
    int max
) {

    char buf[512];

    strncpy(buf,
            payload,
            sizeof(buf) - 1);

    buf[sizeof(buf) - 1] = '\0';

    int n = 0;

    char *s1 = NULL;
    char *s2 = NULL;

    char *tok = strtok_r(buf, ";", &s1);

    while (tok && n < max) {

        while (*tok == ' ' ||
               *tok == '\t' ||
               *tok == '\n' ||
               *tok == '\r') {

            tok++;
        }

        if (*tok) {

            char copy[64];

            strncpy(copy,
                    tok,
                    sizeof(copy) - 1);

            copy[sizeof(copy) - 1] = '\0';

            char *k = strtok_r(copy,
                               " \t",
                               &s2);

            char *v = strtok_r(NULL,
                               " \t",
                               &s2);

            if (k && v) {

                strncpy(pairs[n].key,
                        k,
                        31);

                pairs[n].key[31] = '\0';

                strncpy(pairs[n].value,
                        v,
                        31);

                pairs[n].value[31] = '\0';

                n++;
            }
        }

        tok = strtok_r(NULL, ";", &s1);
    }

    return n;
}

/* ============================ MQTT CALLBACK ============================ */

int on_message(
    void *context,
    char *topicName,
    int topicLen,
    MQTTClient_message *message
) {

    (void)context;
    (void)topicLen;

    char payload[512];

    int n = message->payloadlen <
            (int)sizeof(payload) - 1
            ? message->payloadlen
            : (int)sizeof(payload) - 1;

    memcpy(payload,
           message->payload,
           n);

    payload[n] = '\0';

    char dateStr[16];
    char timeStr[16];

    getCurrentTime(dateStr,
                   timeStr);

    writeToLogFile(
        topicName,
        dateStr,
        timeStr,
        payload
    );

    printf("\n[%s %s]\n",
           dateStr,
           timeStr);

    printf("Topic: %s\n",
           topicName);

    printf("Payload: %s\n",
           payload);

    const char *field_id =
        lookup_field_id(topicName);

    if (!field_id) {

        fprintf(stderr,
                "Unknown topic: %s\n",
                topicName);

        MQTTClient_freeMessage(&message);
        MQTTClient_free(topicName);

        return 1;
    }

    /* ================= FIREBASE ================= */

    char json_payload[512];

    snprintf(
        json_payload,
        sizeof(json_payload),
        "{\"value\":\"%s\","
        "\"time\":\"%s %s\"}",
        payload,
        dateStr,
        timeStr
    );

    char fb_path[256];

    snprintf(
        fb_path,
        sizeof(fb_path),
        "%s/humidity_minute/%s/%s",
        topicName,
        dateStr,
        timeStr
    );

    set_data_firebase(
        fb_path,
        json_payload
    );

    printf("[Firebase] uploaded\n");

    /* ================= MONGODB ================= */

    int64_t t_ms = now_ms();

    Pair pairs[16];

    int count = parse_payload(
        payload,
        pairs,
        16
    );

    int inserted = 0;

    for (int i = 0; i < count; i++) {

        if (insert_reading(
                field_id,
                pairs[i].key,
                atof(pairs[i].value),
                t_ms
            ) == 0) {

            inserted++;
        }
    }

    printf("[MongoDB] inserted %d row(s)\n",
           inserted);

    MQTTClient_freeMessage(&message);
    MQTTClient_free(topicName);

    return 1;
}

void connectionLost(
    void *context,
    char *cause
) {

    (void)context;

    printf("Connection lost: %s\n",
           cause ? cause : "unknown");
}

/* ============================ MAIN ============================ */

int main(void) {

    signal(SIGINT, handle_signal);
    signal(SIGTERM, handle_signal);

    curl_global_init(CURL_GLOBAL_ALL);

    mongoc_init();

    bson_error_t bs_err;

    mongoc_uri_t *uri =
        mongoc_uri_new_with_error(
            MONGO_URI,
            &bs_err
        );

    if (!uri) {

        fprintf(stderr,
                "Bad Mongo URI: %s\n",
                bs_err.message);

        return EXIT_FAILURE;
    }

    g_mongo = mongoc_client_new_from_uri(uri);

    mongoc_uri_destroy(uri);

    if (!g_mongo) {

        fprintf(stderr,
                "Failed to create Mongo client\n");

        return EXIT_FAILURE;
    }

    g_coll = mongoc_client_get_collection(
        g_mongo,
        MONGO_DB,
        MONGO_COLLECTION
    );

    printf("[MongoDB] connected\n");

    MQTTClient_connectOptions conn_opts =
        MQTTClient_connectOptions_initializer;

    int rc;

    MQTTClient_create(
        &client,
        MQTT_BROKER,
        MQTT_CLIENT_ID,
        MQTTCLIENT_PERSISTENCE_NONE,
        NULL
    );

    conn_opts.keepAliveInterval = 20;
    conn_opts.cleansession = 1;
    conn_opts.username = MQTT_USERNAME;
    conn_opts.password = MQTT_PASSWORD;

    MQTTClient_setCallbacks(
        client,
        NULL,
        connectionLost,
        on_message,
        NULL
    );

    rc = MQTTClient_connect(
        client,
        &conn_opts
    );

    if (rc != MQTTCLIENT_SUCCESS) {

        fprintf(stderr,
                "MQTT connect failed: %d\n",
                rc);

        return EXIT_FAILURE;
    }

    printf("[MQTT] connected\n");

    for (int i = 0; i < SOIL_FIELDS_LEN; i++) {

        MQTTClient_subscribe(
            client,
            SOIL_FIELDS[i].topic,
            MQTT_QOS
        );

        printf("[MQTT] subscribed: %s\n",
               SOIL_FIELDS[i].topic);
    }

    while (running) {
        sleep(1);
    }

    printf("Shutting down...\n");

    MQTTClient_disconnect(
        client,
        MQTT_TIMEOUT
    );

    MQTTClient_destroy(&client);

    mongoc_collection_destroy(g_coll);

    mongoc_client_destroy(g_mongo);

    mongoc_cleanup();

    curl_global_cleanup();

    return EXIT_SUCCESS;
}
