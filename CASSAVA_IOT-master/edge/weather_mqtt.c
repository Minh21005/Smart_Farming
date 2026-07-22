#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <time.h>
#include <unistd.h>
#include <signal.h>
#include <sys/time.h>

#include <MQTTClient.h>
#include <curl/curl.h>

#include <mongoc/mongoc.h>

/* ============================ MQTT CONFIG ============================ */

#define MQTT_BROKER     "tcp://localhost:1883"
#define MQTT_CLIENT_ID  "weather_merged"

#define MQTT_USERNAME   "libe"
#define MQTT_PASSWORD   "123456"

#define MQTT_QOS        1
#define MQTT_TIMEOUT    10000L

#define MQTT_TOPIC_WEATHER "/sensor/weatherStation"

/* ============================ FIREBASE CONFIG ============================ */

#define FIREBASE_URL    "https://directionproject-1e798-default-rtdb.firebaseio.com"

#define PATH_FIREBASE   "user/measured_data"

#define MQTT_TOPIC_1    "field1"
#define MQTT_TOPIC_2    "field2"
#define MQTT_TOPIC_3    "field3"
#define MQTT_TOPIC_4    "field4"

#define LOG_FILE_PATH   "/home/student/Desktop/weather_data.txt"

/* ============================ MONGODB CONFIG ============================ */

#define MONGO_URI        "mongodb://admin:uet%402026@112.137.129.218:27017/iot_agriculture?authSource=admin"
#define MONGO_DB         "iot_agriculture"
#define MONGO_COLLECTION "sensor_value"

#define DEFAULT_GROUP_ID "69e35b13e405c05c3dab13c9"

/* ============================ WEATHER STRUCT ============================ */

typedef struct {
    double radiation;
    double rainFall;
    double relativeHumidity;
    double temperature;
    double windSpeed;

    char time[32];

    int hasRadiation;
    int hasRainFall;
    int hasHumidity;
    int hasTemperature;
    int hasWindSpeed;
} WeatherData;

/* ============================ GLOBALS ============================ */

MQTTClient client;

mongoc_client_t *g_mongo = NULL;
mongoc_collection_t *g_coll = NULL;

static volatile sig_atomic_t running = 1;

/* ============================ SENSOR MAP ============================ */

static const struct {
    const char *key;
    const char *sensor_id;
} WEATHER_MAP[] = {
    {"t",   "temperature"},
    {"h",   "relativeHumidity"},
    {"rad", "radiation"},
    {"rai", "rain"},
    {"w",   "wind"},
};

static const int WEATHER_MAP_LEN =
    sizeof(WEATHER_MAP) / sizeof(WEATHER_MAP[0]);

/* ============================ UTILS ============================ */

void handle_signal(int sig) {
    (void)sig;
    running = 0;
}

void getCurrentTime(char *dateStr, char *timeStr) {
    time_t now = time(NULL);

    struct tm *tm_info = localtime(&now);

    strftime(dateStr, 16, "%Y-%m-%d", tm_info);
    strftime(timeStr, 16, "%H:%M:%S", tm_info);
}

static int64_t now_ms(void) {
    struct timeval tv;

    gettimeofday(&tv, NULL);

    return (int64_t)tv.tv_sec * 1000LL +
           (int64_t)tv.tv_usec / 1000LL;
}

size_t writeCallback(void *contents, size_t size,
                     size_t nmemb, void *userp) {
    return size * nmemb;
}

void writeToLogFile(const char *dateStr,
                    const char *timeStr,
                    const char *payload) {
    FILE *file = fopen(LOG_FILE_PATH, "a");

    if (file) {
        fprintf(file, "%s %s: %s\n",
                dateStr, timeStr, payload);

        fclose(file);
    }
}

static const char *resolve_sensor_id(const char *key) {
    for (int i = 0; i < WEATHER_MAP_LEN; i++) {
        if (strcmp(key, WEATHER_MAP[i].key) == 0) {
            return WEATHER_MAP[i].sensor_id;
        }
    }

    return NULL;
}

/* ============================ FIREBASE ============================ */

int setDataFirebase(const char *jsonData,
                    const char *path) {
    CURL *curl;
    CURLcode res;

    char url[512];

    struct curl_slist *headers = NULL;

    snprintf(url, sizeof(url),
             "%s/%s.json",
             FIREBASE_URL,
             path);

    curl = curl_easy_init();

    if (!curl) {
        fprintf(stderr,
                "Failed to initialize CURL\n");

        return -1;
    }

    headers = curl_slist_append(
        headers,
        "Content-Type: application/json"
    );

    curl_easy_setopt(curl, CURLOPT_URL, url);
    curl_easy_setopt(curl, CURLOPT_CUSTOMREQUEST, "PUT");
    curl_easy_setopt(curl, CURLOPT_POSTFIELDS, jsonData);
    curl_easy_setopt(curl, CURLOPT_HTTPHEADER, headers);
    curl_easy_setopt(curl, CURLOPT_WRITEFUNCTION, writeCallback);

    res = curl_easy_perform(curl);

    if (res != CURLE_OK) {
        fprintf(stderr,
                "Firebase request failed: %s\n",
                curl_easy_strerror(res));
    }

    curl_slist_free_all(headers);
    curl_easy_cleanup(curl);

    return (res == CURLE_OK) ? 0 : -1;
}

/* ============================ MONGODB ============================ */

static int insert_reading(
    const char *sensor_id,
    double value,
    int64_t t_ms
) {
    bson_t *doc = bson_new();

    bson_oid_t oid;
    bson_oid_init(&oid, NULL);

    BSON_APPEND_OID(doc, "_id", &oid);
    BSON_APPEND_UTF8(doc, "groupId", DEFAULT_GROUP_ID);
    BSON_APPEND_UTF8(doc, "sensorId", sensor_id);
    BSON_APPEND_DOUBLE(doc, "value", value);
    BSON_APPEND_DATE_TIME(doc, "time", t_ms);

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
    } else {
        printf("[MongoDB] inserted %s = %.2f\n",
               sensor_id,
               value);
    }

    bson_destroy(doc);

    return ok ? 0 : -1;
}

/* ============================ WEATHER PARSE ============================ */

void parseWeatherData(const char *payload,
                      WeatherData *data) {

    char buffer[256];

    char *token;

    char *saveptr1;
    char *saveptr2;

    data->hasRadiation = 0;
    data->hasRainFall = 0;
    data->hasHumidity = 0;
    data->hasTemperature = 0;
    data->hasWindSpeed = 0;

    strncpy(buffer, payload, sizeof(buffer) - 1);

    buffer[sizeof(buffer) - 1] = '\0';

    token = strtok_r(buffer, ";", &saveptr1);

    while (token != NULL) {

        char *key;
        char *value;

        char tokenCopy[64];

        while (*token == ' ') token++;

        strncpy(tokenCopy,
                token,
                sizeof(tokenCopy) - 1);

        tokenCopy[sizeof(tokenCopy) - 1] = '\0';

        key = strtok_r(tokenCopy, " ", &saveptr2);
        value = strtok_r(NULL, " ", &saveptr2);

        if (key && value) {

            if (strcmp(key, "rad") == 0) {
                data->radiation = atof(value);
                data->hasRadiation = 1;
            }

            else if (strcmp(key, "rai") == 0) {
                data->rainFall = atof(value);
                data->hasRainFall = 1;
            }

            else if (strcmp(key, "h") == 0) {
                data->relativeHumidity = atof(value);
                data->hasHumidity = 1;
            }

            else if (strcmp(key, "t") == 0) {
                data->temperature = atof(value);
                data->hasTemperature = 1;
            }

            else if (strcmp(key, "w") == 0) {
                data->windSpeed = atof(value);
                data->hasWindSpeed = 1;
            }
        }

        token = strtok_r(NULL, ";", &saveptr1);
    }
}

/* ============================ JSON ============================ */

char* weatherDataToJson(WeatherData *data) {

    static char json[512];

    char temp[64];

    int first = 1;

    strcpy(json, "{");

    if (data->hasRadiation) {
        snprintf(temp, sizeof(temp),
                 "\"radiation\":%.2f",
                 data->radiation);

        strcat(json, temp);

        first = 0;
    }

    if (data->hasRainFall) {

        if (!first) strcat(json, ",");

        snprintf(temp, sizeof(temp),
                 "\"rainFall\":%.2f",
                 data->rainFall);

        strcat(json, temp);

        first = 0;
    }

    if (data->hasHumidity) {

        if (!first) strcat(json, ",");

        snprintf(temp, sizeof(temp),
                 "\"relativeHumidity\":%.2f",
                 data->relativeHumidity);

        strcat(json, temp);

        first = 0;
    }

    if (data->hasTemperature) {

        if (!first) strcat(json, ",");

        snprintf(temp, sizeof(temp),
                 "\"temperature\":%.2f",
                 data->temperature);

        strcat(json, temp);

        first = 0;
    }

    if (data->hasWindSpeed) {

        if (!first) strcat(json, ",");

        snprintf(temp, sizeof(temp),
                 "\"windSpeed\":%.2f",
                 data->windSpeed);

        strcat(json, temp);

        first = 0;
    }

    if (!first) strcat(json, ",");

    snprintf(temp, sizeof(temp),
             "\"time\":\"%s\"",
             data->time);

    strcat(json, temp);

    strcat(json, "}");

    return json;
}

/* ============================ MQTT CALLBACK ============================ */

int messageArrived(void *context,
                   char *topicName,
                   int topicLen,
                   MQTTClient_message *message) {

    (void)context;
    (void)topicLen;

    char dateStr[16];
    char timeStr[16];

    char *payload;

    WeatherData weatherData;

    char *jsonData;

    char path[256];

    getCurrentTime(dateStr, timeStr);

    payload = (char *)malloc(message->payloadlen + 1);

    memcpy(payload,
           message->payload,
           message->payloadlen);

    payload[message->payloadlen] = '\0';

    printf("\n[%s %s]\n",
           dateStr,
           timeStr);

    printf("Topic: %s\n", topicName);
    printf("Payload: %s\n", payload);

    writeToLogFile(dateStr,
                   timeStr,
                   payload);

    if (strcmp(topicName,
               MQTT_TOPIC_WEATHER) == 0) {

        parseWeatherData(payload,
                         &weatherData);

        snprintf(weatherData.time,
                 sizeof(weatherData.time),
                 "%s %s",
                 dateStr,
                 timeStr);

        jsonData = weatherDataToJson(&weatherData);

        const char *fields[] = {
            MQTT_TOPIC_1,
            MQTT_TOPIC_2,
            MQTT_TOPIC_3,
            MQTT_TOPIC_4
        };

        for (int i = 0; i < 4; i++) {

            snprintf(path,
                     sizeof(path),
                     "%s/%s/measured_data/%s/%s",
                     PATH_FIREBASE,
                     fields[i],
                     dateStr,
                     timeStr);

            setDataFirebase(jsonData, path);
        }

        printf("[Firebase] uploaded\n");

        int64_t t_ms = now_ms();

        if (weatherData.hasTemperature) {
            insert_reading(
                resolve_sensor_id("t"),
                weatherData.temperature,
                t_ms
            );
        }

        if (weatherData.hasHumidity) {
            insert_reading(
                resolve_sensor_id("h"),
                weatherData.relativeHumidity,
                t_ms
            );
        }

        if (weatherData.hasRadiation) {
            insert_reading(
                resolve_sensor_id("rad"),
                weatherData.radiation,
                t_ms
            );
        }

        if (weatherData.hasRainFall) {
            insert_reading(
                resolve_sensor_id("rai"),
                weatherData.rainFall,
                t_ms
            );
        }

        if (weatherData.hasWindSpeed) {
            insert_reading(
                resolve_sensor_id("w"),
                weatherData.windSpeed,
                t_ms
            );
        }
    }

    free(payload);

    MQTTClient_freeMessage(&message);
    MQTTClient_free(topicName);

    return 1;
}

int mqtt_connect_and_subscribe(void) {
    MQTTClient_connectOptions connOpts = MQTTClient_connectOptions_initializer;

    connOpts.keepAliveInterval = 60;
    connOpts.cleansession = 1;
    connOpts.username = MQTT_USERNAME;
    connOpts.password = MQTT_PASSWORD;

    int rc = MQTTClient_connect(client, &connOpts);
    if (rc != MQTTCLIENT_SUCCESS) {
        fprintf(stderr,
                "Failed to connect MQTT: %d\n",
                rc);
        return rc;
    }

    rc = MQTTClient_subscribe(
        client,
        MQTT_TOPIC_WEATHER,
        MQTT_QOS
    );

    if (rc != MQTTCLIENT_SUCCESS) {
        fprintf(stderr,
                "Failed to subscribe: %d\n",
                rc);
        return rc;
    }

    printf("[MQTT] connected and subscribed: %s\n",
           MQTT_TOPIC_WEATHER);
    return MQTTCLIENT_SUCCESS;
}

void connectionLost(void *context,
                    char *cause) {

    (void)context;

    printf("Connection lost: %s\n",
           cause ? cause : "unknown");
}

/* ============================ MAIN ============================ */

int main(void) {

    signal(SIGINT, handle_signal);
    signal(SIGTERM, handle_signal);

    int rc;

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

    rc = MQTTClient_create(
        &client,
        MQTT_BROKER,
        MQTT_CLIENT_ID,
        MQTTCLIENT_PERSISTENCE_NONE,
        NULL
    );

    if (rc != MQTTCLIENT_SUCCESS) {

        fprintf(stderr,
                "Failed to create MQTT client: %d\n",
                rc);

        return EXIT_FAILURE;
    }

    MQTTClient_setCallbacks(
        client,
        NULL,
        connectionLost,
        messageArrived,
        NULL
    );

    rc = mqtt_connect_and_subscribe();
    if (rc != MQTTCLIENT_SUCCESS) {
        return EXIT_FAILURE;
    }

    while (running) {
        sleep(1);
    }

    printf("Shutting down...\n");

    MQTTClient_disconnect(client, MQTT_TIMEOUT);
    MQTTClient_destroy(&client);

    mongoc_collection_destroy(g_coll);
    mongoc_client_destroy(g_mongo);
    mongoc_cleanup();

    curl_global_cleanup();

    return EXIT_SUCCESS;
}
