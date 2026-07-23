#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <time.h>
#include <sys/time.h>
#include <unistd.h>
#include <pthread.h>
#include <MQTTClient.h>
#include <curl/curl.h>
#include <sqlite3.h>
#include <mongoc/mongoc.h>
#include <bson/bson.h>

#define MQTT_BROKER     "192.168.1.22"
#define MQTT_CLIENT_ID  "weather_station_01" 
#define MQTT_USERNAME   "student"
#define MQTT_PASSWORD   "123456"
#define MQTT_QOS        1
#define MQTT_TOPIC_WEATHER "/sensor/weatherStation"

#define FIREBASE_URL    "https://directionproject-1e798-default-rtdb.firebaseio.com"
#define PATH_FIREBASE   "user/measured_data"
#define MQTT_TOPIC_1    "field1"
#define MQTT_TOPIC_2    "field2"
#define MQTT_TOPIC_3    "field3"
#define MQTT_TOPIC_4    "field4"

#define MONGO_URI        "mongodb://admin:uet%402026@112.137.129.218:27017/iot_agriculture?authSource=admin"
#define MONGO_DB         "iot_agriculture"
#define MONGO_COLLECTION "sensor_value"
#define DEFAULT_GROUP_ID "69e35b13e405c05c3dab13c9"

#define DB_FILE_PATH    "offline_data.db"
#define MAX_DB_RECORDS  100000 
#define LIMIT_50_PCT    (MAX_DB_RECORDS * 0.5)
#define LIMIT_80_PCT    (MAX_DB_RECORDS * 0.8)

typedef struct {
    double radiation;
    double rainFall;
    double relativeHumidity;
    double temperature;
    double windSpeed;
    char time[32];
    int hasRadiation; int hasRainFall; int hasHumidity; int hasTemperature; int hasWindSpeed;
} WeatherData;

MQTTClient client;
sqlite3 *db;
mongoc_client_pool_t *g_mongo_pool = NULL;
pthread_mutex_t db_mutex = PTHREAD_MUTEX_INITIALIZER;

time_t last_sqlite_save_time = 0;

int64_t now_ms(void);
void parseWeatherData(const char *payload, WeatherData *data);
void weatherDataToJson(WeatherData *data, char *jsonOut, size_t maxLen);
int setDataFirebase(const char *jsonData, const char *path);
int insert_mongo_reading(mongoc_collection_t *coll, const char *sensor_id, double value, int64_t t_ms);
int pushToMongo(WeatherData *data, int64_t t_ms);

void initSQLite();
int getDBRecordCount();
int shouldSaveToSQLite();
void saveToSQLite(const char *type, const char *payload, int64_t t_ms);
void* senderWorkerThread(void *arg);

int messageArrived(void *context, char *topicName, int topicLen, MQTTClient_message *message) {
    int64_t t_ms = now_ms();

    char *payload = (char *)malloc(message->payloadlen + 1);
    if (!payload) return 1;
    memcpy(payload, message->payload, message->payloadlen);
    payload[message->payloadlen] = '\0';

    printf("\n[Luong 1] Nhan tu MQTT: %s\n", payload);

    const char *type = NULL;
    if (strcmp(topicName, MQTT_TOPIC_WEATHER) == 0) {
        type = "weather";
    }

    if (type) {
        if (shouldSaveToSQLite()) {
            saveToSQLite(type, payload, t_ms);
        } else {
            printf("[Luong 1] Dat nguong dung luong DB. Bo qua ban tin de tiet kiem bo nho.\n");
        }
    }

    free(payload);
    MQTTClient_freeMessage(&message);
    MQTTClient_free(topicName);
    return 1;
}

void* senderWorkerThread(void *arg) {
    printf("[Luong 2] Tieng trinh gui cloud da bat dau...\n");
    
    while(1) {
        int id = -1;
        char payload[256] = {0};
        char dateStr[16] = {0};
        char timeStr[16] = {0};
        int firebase_stat = 0;
        int mongo_stat = 0;
        int pump_stat = 0;
        int attempts = 0;
        int64_t t_ms = 0;
        int row_found = 0;

        const char *select_sql = "SELECT id, payload, date, time, firebase_stat, mongo_stat, pump_stat, attempts, t_ms "
                                 "FROM offline_data WHERE type = 'weather' ORDER BY id ASC LIMIT 1;";
        sqlite3_stmt *stmt;

        pthread_mutex_lock(&db_mutex);
        if (sqlite3_prepare_v2(db, select_sql, -1, &stmt, NULL) == SQLITE_OK) {
            if (sqlite3_step(stmt) == SQLITE_ROW) {
                id = sqlite3_column_int(stmt, 0);
                strcpy(payload, (const char *)sqlite3_column_text(stmt, 1));
                strcpy(dateStr, (const char *)sqlite3_column_text(stmt, 2));
                strcpy(timeStr, (const char *)sqlite3_column_text(stmt, 3));
                firebase_stat = sqlite3_column_int(stmt, 4);
                mongo_stat = sqlite3_column_int(stmt, 5);
                pump_stat = sqlite3_column_int(stmt, 6);
                attempts = sqlite3_column_int(stmt, 7);
                t_ms = sqlite3_column_int64(stmt, 8);
                row_found = 1;
            }
            sqlite3_finalize(stmt);
        }
        pthread_mutex_unlock(&db_mutex);

        if (row_found) {
            WeatherData wData;
            parseWeatherData(payload, &wData);
            snprintf(wData.time, sizeof(wData.time), "%s %s", dateStr, timeStr);

            int network_error = 0;

            if (firebase_stat == 0) {
                char jsonData[256];
                weatherDataToJson(&wData, jsonData, sizeof(jsonData));
                int fb_ok = 1;
                char path[256];
                const char *fields[] = {MQTT_TOPIC_1, MQTT_TOPIC_2, MQTT_TOPIC_3, MQTT_TOPIC_4};
                
                for (int i = 0; i < 4; i++) {
                    snprintf(path, sizeof(path), "%s/%s/measured_data/%s/%s", PATH_FIREBASE, fields[i], dateStr, timeStr);
                    if (!setDataFirebase(jsonData, path)) {
                        fb_ok = 0;
                        break;
                    }
                }
                if (fb_ok) firebase_stat = 1;
                else network_error = 1;
            }

            if (mongo_stat == 0) {
                if (pushToMongo(&wData, t_ms)) mongo_stat = 1;
                else network_error = 1;
            }

            pthread_mutex_lock(&db_mutex);
            if (firebase_stat == 1 && mongo_stat == 1) {
                char del_sql[64];
                snprintf(del_sql, sizeof(del_sql), "DELETE FROM offline_data WHERE id = %d;", id);
                sqlite3_exec(db, del_sql, 0, 0, 0);
                printf("[Luong 2] ID %d: Gui thanh cong ca Firebase & Mongo -> Da xoa khoi DB.\n", id);
            } else {
                attempts++;
                if (attempts >= 100) {
                    char del_sql[64];
                    snprintf(del_sql, sizeof(del_sql), "DELETE FROM offline_data WHERE id = %d;", id);
                    sqlite3_exec(db, del_sql, 0, 0, 0);
                    printf("[Luong 2] ID %d: LOI QUA 100 LAN -> Buoc phai xoa khoi DB.\n", id);
                } else {
                    char up_sql[128];
                    snprintf(up_sql, sizeof(up_sql), 
                             "UPDATE offline_data SET firebase_stat = %d, mongo_stat = %d, attempts = %d WHERE id = %d;", 
                             firebase_stat, mongo_stat, attempts, id);
                    sqlite3_exec(db, up_sql, 0, 0, 0);
                    printf("[Luong 2] ID %d: Gui loi! Cap nhat co [FB:%d, Mongo:%d], So lan thu: %d\n", 
                           id, firebase_stat, mongo_stat, attempts);
                }
            }
            pthread_mutex_unlock(&db_mutex);

            if (network_error) sleep(5); 
            else usleep(50000);
        } else {
            sleep(1);
        }
    }
    return NULL;
}

void initSQLite() {
    sqlite3_open(DB_FILE_PATH, &db);
    const char *sql_create = 
        "CREATE TABLE IF NOT EXISTS offline_data ("
        "id INTEGER PRIMARY KEY AUTOINCREMENT, "
        "type TEXT NOT NULL, "
        "payload TEXT, date TEXT, time TEXT, "
        "firebase_stat INTEGER DEFAULT 0, mongo_stat INTEGER DEFAULT 0, "
        "pump_stat INTEGER DEFAULT 0, attempts INTEGER DEFAULT 0, t_ms INTEGER);";
    sqlite3_exec(db, sql_create, 0, 0, 0);
}

int getDBRecordCount() {
    int count = 0;
    const char *sql = "SELECT COUNT(*) FROM offline_data;";
    sqlite3_stmt *stmt;
    if (sqlite3_prepare_v2(db, sql, -1, &stmt, NULL) == SQLITE_OK) {
        if (sqlite3_step(stmt) == SQLITE_ROW) count = sqlite3_column_int(stmt, 0);
        sqlite3_finalize(stmt);
    }
    return count;
}

int shouldSaveToSQLite() {
    pthread_mutex_lock(&db_mutex);
    int count = getDBRecordCount();
    pthread_mutex_unlock(&db_mutex);
    
    time_t now = time(NULL);

    if (count >= LIMIT_80_PCT) {
        if (difftime(now, last_sqlite_save_time) >= 86400) return 1;
        return 0;
    } else if (count >= LIMIT_50_PCT) {
        if (difftime(now, last_sqlite_save_time) >= 3600) return 1;
        return 0;
    }
    return 1;
}

void saveToSQLite(const char *type, const char *payload, int64_t t_ms) {
    const char *sql = "INSERT INTO offline_data (type, payload, date, time, firebase_stat, mongo_stat, pump_stat, attempts, t_ms) "
                      "VALUES (?, ?, date('now'), time('now'), 0, 0, 0, 0, ?);";
    sqlite3_stmt *stmt;
    pthread_mutex_lock(&db_mutex);
    if (sqlite3_prepare_v2(db, sql, -1, &stmt, NULL) == SQLITE_OK) {
        sqlite3_bind_text(stmt, 1, type, -1, SQLITE_TRANSIENT);
        sqlite3_bind_text(stmt, 2, payload, -1, SQLITE_TRANSIENT);
        sqlite3_bind_int64(stmt, 3, t_ms);
        if (sqlite3_step(stmt) == SQLITE_DONE) {
            printf("[Luong 1] Da chen vao SQLite thanh cong.\n");
            last_sqlite_save_time = time(NULL);
        }
        sqlite3_finalize(stmt);
    }
    pthread_mutex_unlock(&db_mutex);
}

int64_t now_ms(void) {
    struct timeval tv; gettimeofday(&tv, NULL);
    return (int64_t)tv.tv_sec * 1000LL + (int64_t)tv.tv_usec / 1000LL;
}


void parseWeatherData(const char *payload, WeatherData *data) {
    char buffer[256]; char *token, *saveptr1, *saveptr2;
    data->hasRadiation = data->hasRainFall = data->hasHumidity = data->hasTemperature = data->hasWindSpeed = 0;
    strncpy(buffer, payload, sizeof(buffer) - 1); buffer[sizeof(buffer) - 1] = '\0';
    token = strtok_r(buffer, ";", &saveptr1);
    while (token != NULL) {
        char tokenCopy[64]; while (*token == ' ') token++;
        strncpy(tokenCopy, token, sizeof(tokenCopy) - 1);
        char *key = strtok_r(tokenCopy, " ", &saveptr2); char *value = strtok_r(NULL, " ", &saveptr2);
        if (key && value) {
            if (strcmp(key, "rad") == 0) { data->radiation = atof(value); data->hasRadiation = 1; }
            else if (strcmp(key, "rai") == 0) { data->rainFall = atof(value); data->hasRainFall = 1; }
            else if (strcmp(key, "h") == 0) { data->relativeHumidity = atof(value); data->hasHumidity = 1; }
            else if (strcmp(key, "t") == 0) { data->temperature = atof(value); data->hasTemperature = 1; }
            else if (strcmp(key, "w") == 0) { data->windSpeed = atof(value); data->hasWindSpeed = 1; }
        }
        token = strtok_r(NULL, ";", &saveptr1);
    }
}

void weatherDataToJson(WeatherData *data, char *jsonOut, size_t maxLen) {
    int offset = 0, first = 1; offset += snprintf(jsonOut + offset, maxLen - offset, "{");
    if (data->hasRadiation) { offset += snprintf(jsonOut + offset, maxLen - offset, "%s\"rad\":%.2f", first ? "" : ",", data->radiation); first = 0; }
    if (data->hasRainFall) { offset += snprintf(jsonOut + offset, maxLen - offset, "%s\"rai\":%.2f", first ? "" : ",", data->rainFall); first = 0; }
    if (data->hasHumidity) { offset += snprintf(jsonOut + offset, maxLen - offset, "%s\"h\":%.2f", first ? "" : ",", data->relativeHumidity); first = 0; }
    if (data->hasTemperature) { offset += snprintf(jsonOut + offset, maxLen - offset, "%s\"t\":%.2f", first ? "" : ",", data->temperature); first = 0; }
    if (data->hasWindSpeed) { offset += snprintf(jsonOut + offset, maxLen - offset, "%s\"w\":%.2f", first ? "" : ",", data->windSpeed); first = 0; }
    snprintf(jsonOut + offset, maxLen - offset, "%s\"ts\":\"%s\"}", first ? "" : ",", data->time);
}

size_t writeCallback(void *contents, size_t size, size_t nmemb, void *userp) { return size * nmemb; }

int setDataFirebase(const char *jsonData, const char *path) {
    CURL *curl = curl_easy_init(); if (!curl) return 0;
    char url[512]; snprintf(url, sizeof(url), "%s/%s.json", FIREBASE_URL, path);
    struct curl_slist *headers = curl_slist_append(NULL, "Content-Type: application/json");
    curl_easy_setopt(curl, CURLOPT_URL, url); curl_easy_setopt(curl, CURLOPT_CUSTOMREQUEST, "PUT");
    curl_easy_setopt(curl, CURLOPT_POSTFIELDS, jsonData); curl_easy_setopt(curl, CURLOPT_HTTPHEADER, headers);
    curl_easy_setopt(curl, CURLOPT_WRITEFUNCTION, writeCallback); curl_easy_setopt(curl, CURLOPT_TIMEOUT, 3L);
    CURLcode res = curl_easy_perform(curl); curl_slist_free_all(headers); curl_easy_cleanup(curl);
    return (res == CURLE_OK) ? 1 : 0;
}

void initMongo() {
    mongoc_init(); bson_error_t bs_err;
    mongoc_uri_t *uri = mongoc_uri_new_with_error(MONGO_URI, &bs_err);
    if (!uri) { exit(EXIT_FAILURE); }
    g_mongo_pool = mongoc_client_pool_new(uri);
    mongoc_uri_destroy(uri);
}

int insert_mongo_reading(mongoc_collection_t *coll, const char *sensor_id, double value, int64_t t_ms) {
    bson_t *doc = bson_new(); bson_oid_t oid; bson_oid_init(&oid, NULL);
    BSON_APPEND_OID(doc, "_id", &oid); BSON_APPEND_UTF8(doc, "groupId", DEFAULT_GROUP_ID);
    BSON_APPEND_UTF8(doc, "sensorId", sensor_id); BSON_APPEND_DOUBLE(doc, "value", value);
    BSON_APPEND_DATE_TIME(doc, "time", t_ms);
    bson_error_t err; bool ok = mongoc_collection_insert_one(coll, doc, NULL, NULL, &err);
    bson_destroy(doc); return ok ? 1 : 0;
}

int pushToMongo(WeatherData *data, int64_t t_ms) {
    if (!g_mongo_pool) return 0;
    mongoc_client_t *m_client = mongoc_client_pool_pop(g_mongo_pool);
    mongoc_collection_t *coll = mongoc_client_get_collection(m_client, MONGO_DB, MONGO_COLLECTION);
    int success = 1;
    if (data->hasTemperature && !insert_mongo_reading(coll, "temperature", data->temperature, t_ms)) success = 0;
    if (data->hasHumidity && !insert_mongo_reading(coll, "relativeHumidity", data->relativeHumidity, t_ms)) success = 0;
    if (data->hasRadiation && !insert_mongo_reading(coll, "radiation", data->radiation, t_ms)) success = 0;
    if (data->hasRainFall && !insert_mongo_reading(coll, "rain", data->rainFall, t_ms)) success = 0;
    if (data->hasWindSpeed && !insert_mongo_reading(coll, "wind", data->windSpeed, t_ms)) success = 0;
    mongoc_collection_destroy(coll); mongoc_client_pool_push(g_mongo_pool, m_client);
    return success;
}

void connectionLost(void *context, char *cause) { printf("[MQTT] Mat ket noi: %s\n", cause ? cause : "unknown"); }

int main(int argc, char *argv[]) {
    MQTTClient_connectOptions connOpts = MQTTClient_connectOptions_initializer;
    pthread_t sender_tid;
    
    curl_global_init(CURL_GLOBAL_ALL);
    initSQLite();
    initMongo();

    if (pthread_create(&sender_tid, NULL, senderWorkerThread, NULL) != 0) {
        fprintf(stderr, "Khong the tao luong gui Cloud!\n");
        return EXIT_FAILURE;
    }
    pthread_detach(sender_tid);

    int rc = MQTTClient_create(&client, MQTT_BROKER, MQTT_CLIENT_ID, MQTTCLIENT_PERSISTENCE_NONE, NULL);
    if (rc != MQTTCLIENT_SUCCESS) return EXIT_FAILURE;

    MQTTClient_setCallbacks(client, NULL, connectionLost, messageArrived, NULL);
    connOpts.keepAliveInterval = 20; connOpts.cleansession = 1;
    connOpts.username = MQTT_USERNAME; connOpts.password = MQTT_PASSWORD; connOpts.connectTimeout = 5;

    rc = MQTTClient_connect(client, &connOpts);
    int is_subscribed = 0;
    
    while (1) {
        if (MQTTClient_isConnected(client)) {
            if (!is_subscribed) {
                rc = MQTTClient_subscribe(client, MQTT_TOPIC_WEATHER, MQTT_QOS);
                if (rc == MQTTCLIENT_SUCCESS) is_subscribed = 1;
                else sleep(2);
            }
        } else {
            printf("[Ngoai vi] Khong nhan ban tin. Thu ket noi lai...\n");
            is_subscribed = 0; 
            MQTTClient_connect(client, &connOpts);
            sleep(5);
        }
        sleep(1);
    }

    mongoc_client_pool_destroy(g_mongo_pool); mongoc_cleanup();
    MQTTClient_destroy(&client); curl_global_cleanup();
    if (db) sqlite3_close(db);
    return EXIT_SUCCESS;
}