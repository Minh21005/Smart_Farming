/* dk_bom_mqtt.c — standalone MQTT pump controller for cassava operation pipeline.
 *
 * Replaces the Firebase-polling logic in legacy dk_bom.c with the new
 * BE → edge schema documented in deploy/MQTT.md §3+§7:
 *   - subscribe  cassava/field/+/valve/+/cmd  (JSON OperationCommand)
 *   - actuate    publish "1" / "0" to Pump<valveId> (legacy GPIO bridge)
 *   - publish    cassava/field/<fieldId>/valve/<valveId>/ack (JSON OperationAck)
 *
 * Build (place cJSON.{c,h} alongside this file):
 *   cc -O2 dk_bom_mqtt.c cJSON.c -o dk_bom_mqtt \
 *      -lpaho-mqtt3c -lpthread
 *
 * Deps (Debian / Raspberry Pi OS):
 *   sudo apt install -y build-essential pkg-config libpaho-mqtt-dev
 *
 * Run:
 *   ./dk_bom_mqtt
 */

#include <MQTTClient.h>
#include "cJSON.h"

#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <signal.h>
#include <stdint.h>
#include <unistd.h>
#include <pthread.h>
#include <sys/time.h>
#include <sqlite3.h>

/* ============================ CONFIG (edit here) ============================ */

#define MQTT_BROKER_URL  "tcp://localhost:1883"
#define MQTT_USERNAME    "libe"
#define MQTT_PASSWORD    "123456"
#define MQTT_CLIENT_ID   "cassava-edge-pump"

#define CMD_TOPIC_FILTER "cassava/field/+/valve/+/cmd"
#define DB_FILE_PATH    "offline_data.db"
#define MAX_DB_RECORDS  100000
#define LIMIT_50_PCT    (MAX_DB_RECORDS * 0.5)
#define LIMIT_80_PCT    (MAX_DB_RECORDS * 0.8)

/* Safety upper bound — refuse OPEN_TIMED requests exceeding this. */
#define MAX_DURATION_SEC 3600

#define QOS              1

/* valveId -> relay topic. Edge sensors today react to "Pump1" (legacy dk_bom.c).
 * Add more rows when more valves come online. */
static const struct { int valve_id; const char *relay_topic; } RELAYS[] = {
    {1, "Pump1"},
    {2, "Pump2"},
    {3, "Pump3"},
    {4, "Pump4"},
};
static const int RELAYS_LEN = sizeof(RELAYS) / sizeof(RELAYS[0]);

/* ============================ END CONFIG ============================ */

#define MAX_VALVE_ID 16

static MQTTClient            g_mqtt;
static sqlite3              *db = NULL;
static pthread_mutex_t       db_mutex = PTHREAD_MUTEX_INITIALIZER;
static volatile sig_atomic_t g_run = 1;
static pthread_mutex_t       g_pub_mutex = PTHREAD_MUTEX_INITIALIZER;
/* Cờ abort theo valve_id: lệnh CLOSE bật cờ để worker đang tưới thoát sớm. */
static volatile sig_atomic_t g_abort[MAX_VALVE_ID + 1];
static volatile sig_atomic_t g_running[MAX_VALVE_ID + 1];
static time_t                last_sqlite_save_time = 0;

static void on_signal(int s) { (void)s; g_run = 0; }

static int64_t now_ms(void) {
    struct timeval tv;
    gettimeofday(&tv, NULL);
    return (int64_t)tv.tv_sec * 1000LL + (int64_t)tv.tv_usec / 1000LL;
}

static const char *lookup_relay(int valve_id) {
    for (int i = 0; i < RELAYS_LEN; i++) {
        if (RELAYS[i].valve_id == valve_id) return RELAYS[i].relay_topic;
    }
    return NULL;
}

static int publish_str(const char *topic, const char *payload) {
    MQTTClient_message msg = MQTTClient_message_initializer;
    msg.payload    = (void *)payload;
    msg.payloadlen = (int)strlen(payload);
    msg.qos        = QOS;
    msg.retained   = 0;
    pthread_mutex_lock(&g_pub_mutex);
    int rc = MQTTClient_publishMessage(g_mqtt, topic, &msg, NULL);
    pthread_mutex_unlock(&g_pub_mutex);
    return rc;
}

static int parse_field_id(const char *topic) {
    const char *p = strstr(topic, "/field/");
    if (!p) return -1;
    p += 7;
    char *end = NULL;
    long v = strtol(p, &end, 10);
    if (end == p || *end != '/') return -1;
    return (int)v;
}

static int getDBRecordCount(void) {
    int count = 0;
    const char *sql = "SELECT COUNT(*) FROM offline_data;";
    sqlite3_stmt *stmt;
    if (sqlite3_prepare_v2(db, sql, -1, &stmt, NULL) == SQLITE_OK) {
        if (sqlite3_step(stmt) == SQLITE_ROW) count = sqlite3_column_int(stmt, 0);
        sqlite3_finalize(stmt);
    }
    return count;
}

static int shouldSaveToSQLite(void) {
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

static void saveToSQLite(const char *type, const char *payload, int pump_stat, int64_t t_ms) {
    const char *sql = "INSERT INTO offline_data (type, payload, date, time, firebase_stat, mongo_stat, pump_stat, attempts, t_ms) "
                      "VALUES (?, ?, date('now','localtime'), time('now','localtime'), 0, 0, ?, 0, ?);";
    sqlite3_stmt *stmt;
    pthread_mutex_lock(&db_mutex);
    if (sqlite3_prepare_v2(db, sql, -1, &stmt, NULL) == SQLITE_OK) {
        sqlite3_bind_text(stmt, 1, type, -1, SQLITE_TRANSIENT);
        sqlite3_bind_text(stmt, 2, payload, -1, SQLITE_TRANSIENT);
        sqlite3_bind_int(stmt, 3, pump_stat);
        sqlite3_bind_int64(stmt, 4, t_ms);
        if (sqlite3_step(stmt) == SQLITE_DONE) {
            last_sqlite_save_time = time(NULL);
        }
        sqlite3_finalize(stmt);
    }
    pthread_mutex_unlock(&db_mutex);
}

static void initSQLite(void) {
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

/* Replace trailing "/cmd" with "/ack". */
static int build_ack_topic(const char *cmd_topic, char *out, size_t cap) {
    size_t n = strlen(cmd_topic);
    if (n < 4 || strcmp(cmd_topic + n - 4, "/cmd") != 0) return -1;
    if (n + 1 > cap) return -1;
    memcpy(out, cmd_topic, n - 3);
    memcpy(out + n - 3, "ack", 3);
    out[n] = '\0';
    return 0;
}

/* Parse "cassava/field/<fieldId>/valve/<valveId>/cmd" → valve_id (1..N).
 * Returns -1 on malformed topic. */
static int parse_valve_id(const char *cmd_topic) {
    const char *p = strstr(cmd_topic, "/valve/");
    if (!p) return -1;
    p += 7;
    char *end = NULL;
    long v = strtol(p, &end, 10);
    if (end == p || *end != '/') return -1;
    return (int)v;
}

static int build_ack_json(const char *schedule_id, const char *ack,
                          const char *err, const char *pump_state,
                          char *out, size_t cap) {
    cJSON *root = cJSON_CreateObject();
    cJSON_AddStringToObject(root, "scheduleId", schedule_id ? schedule_id : "");
    cJSON_AddStringToObject(root, "ack",        ack ? ack : "FAILED");
    cJSON_AddNumberToObject(root, "ackAt",      (double)now_ms());
    if (pump_state) cJSON_AddStringToObject(root, "pumpState", pump_state);
    if (err) cJSON_AddStringToObject(root, "errorMessage", err);
    else           cJSON_AddNullToObject(root, "errorMessage");

    char *s = cJSON_PrintUnformatted(root);
    int rc = -1;
    if (s && strlen(s) + 1 <= cap) {
        strcpy(out, s);
        rc = 0;
    }
    if (s) cJSON_free(s);
    cJSON_Delete(root);
    return rc;
}

static void send_ack(const char *cmd_topic, const char *schedule_id,
                     const char *ack, const char *err, const char *pump_state) {
    char ack_topic[256];
    char payload[256];
    if (build_ack_topic(cmd_topic, ack_topic, sizeof(ack_topic)) != 0) {
        fprintf(stderr, "[pump] cannot build ack topic from %s\n", cmd_topic);
        return;
    }
    if (build_ack_json(schedule_id, ack, err, pump_state, payload, sizeof(payload)) != 0) {
        fprintf(stderr, "[pump] cannot build ack json (schedule=%s)\n",
                schedule_id ? schedule_id : "?");
        return;
    }
    publish_str(ack_topic, payload);
    fprintf(stdout, "[pump] %s schedule=%s pumpState=%s -> %s\n",
            ack ? ack : "UNKNOWN",
            schedule_id ? schedule_id : "?",
            pump_state ? pump_state : "N/A",
            ack_topic);
    fflush(stdout);
}

/* Ack sớm "RUNNING": báo BE đã bắt đầu tưới ngay khi nhận lệnh, trước khi chờ hết
 * duration. BE chuyển SENT -> RUNNING nên lịch không bị NO_ACK với lần tưới dài. */
static void send_running_ack(const char *cmd_topic, const char *schedule_id) {
    char ack_topic[256];
    char payload[256];
    if (build_ack_topic(cmd_topic, ack_topic, sizeof(ack_topic)) != 0) {
        fprintf(stderr, "[pump] cannot build ack topic from %s\n", cmd_topic);
        return;
    }
    if (build_ack_json(schedule_id, "RUNNING", NULL, "ON", payload, sizeof(payload)) == 0) {
        publish_str(ack_topic, payload);
        fprintf(stdout, "[pump] ACK_RUNNING schedule=%s pumpState=ON -> %s\n",
                schedule_id ? schedule_id : "?", ack_topic);
        fflush(stdout);
    }
}

typedef struct {
    char cmd_topic[256];
    char schedule_id[64];
    char relay_topic[32];
    int  valve_id;
    int  duration;
} PumpJob;

static void *pump_worker(void *arg) {
    PumpJob *job = (PumpJob *)arg;

    fprintf(stdout, "[pump] OPEN schedule=%s relay=%s duration=%ds\n",
            job->schedule_id, job->relay_topic, job->duration);
    fflush(stdout);

    if (job->valve_id >= 0 && job->valve_id <= MAX_VALVE_ID) {
        g_abort[job->valve_id] = 0;
        g_running[job->valve_id] = 1;
    }

    publish_str(job->relay_topic, "1");
    send_running_ack(job->cmd_topic, job->schedule_id);  // báo BE: đang tưới (thoát NO_ACK)

    /* Ngủ theo từng giây để có thể ngắt sớm khi nhận lệnh CLOSE (g_abort). */
    int stopped = 0;
    for (int i = 0; i < job->duration && g_run; i++) {
        if (job->valve_id >= 0 && job->valve_id <= MAX_VALVE_ID && g_abort[job->valve_id]) {
            stopped = 1;
            break;
        }
        sleep(1);
    }

    publish_str(job->relay_topic, "0");
    if (job->valve_id >= 0 && job->valve_id <= MAX_VALVE_ID) {
        g_abort[job->valve_id] = 0;
        g_running[job->valve_id] = 0;
    }

    fprintf(stdout, "[pump] %s schedule=%s relay=%s\n",
            stopped ? "STOPPED" : "DONE", job->schedule_id, job->relay_topic);
    fflush(stdout);

    send_ack(job->cmd_topic, job->schedule_id, "DONE", NULL, "OFF");

    free(job);
    return NULL;
}

static int on_message(void *ctx, char *topic, int topic_len, MQTTClient_message *msg) {
    (void)ctx;
    (void)topic_len;

    char payload[1024];
    int n = msg->payloadlen < (int)sizeof(payload) - 1
            ? msg->payloadlen : (int)sizeof(payload) - 1;
    memcpy(payload, msg->payload, n);
    payload[n] = '\0';

    fprintf(stdout, "[pump] cmd %s payload=%s\n", topic, payload);
    fflush(stdout);

    cJSON *root = cJSON_Parse(payload);
    if (!root) {
        send_ack(topic, NULL, "FAILED", "invalid json", NULL);
        goto done;
    }

    const cJSON *jSched = cJSON_GetObjectItemCaseSensitive(root, "scheduleId");
    const cJSON *jAct   = cJSON_GetObjectItemCaseSensitive(root, "action");
    const cJSON *jDur   = cJSON_GetObjectItemCaseSensitive(root, "durationSeconds");

    const char *sched = (cJSON_IsString(jSched) && jSched->valuestring) ? jSched->valuestring : NULL;
    const char *act   = (cJSON_IsString(jAct)   && jAct->valuestring)   ? jAct->valuestring   : NULL;
    int duration      = cJSON_IsNumber(jDur) ? jDur->valueint : 0;

    if (!sched || !act) {
        send_ack(topic, sched, "FAILED", "missing scheduleId or action", NULL);
        goto cleanup;
    }

    int valve_id = parse_valve_id(topic);
    const char *relay = (valve_id > 0) ? lookup_relay(valve_id) : NULL;
    if (!relay) {
        send_ack(topic, sched, "FAILED", "unknown valveId", NULL);
        goto cleanup;
    }

    int pump_state = -1;
    if (strcmp(act, "CLOSE") == 0) pump_state = 0;
    else if (strcmp(act, "OPEN_TIMED") == 0) pump_state = 1;

    /* CLOSE: dừng tưới đang chạy — nếu có phiên chạy hiện tại thì bật cờ abort để
     * worker tự tắt relay và gửi ACK_DONE OFF. Nếu không có phiên chạy thì tắt relay
     * ngay và gửi trạng thái OFF tức thì. */
    if (strcmp(act, "CLOSE") == 0) {
        if (pump_state >= 0) {
            saveToSQLite("pump", payload, pump_state, now_ms());
        }
        if (valve_id > 0 && valve_id <= MAX_VALVE_ID && g_running[valve_id]) {
            g_abort[valve_id] = 1;
            fprintf(stdout, "[pump] CLOSE schedule=%s valve=%d (abort set)\n", sched, valve_id);
            fflush(stdout);
            goto cleanup;
        }

        publish_str(relay, "0");
        send_ack(topic, sched, "DONE", NULL, "OFF");
        fprintf(stdout, "[pump] CLOSE schedule=%s valve=%d -> OFF\n", sched, valve_id);
        fflush(stdout);
        goto cleanup;
    }

    if (strcmp(act, "OPEN_TIMED") != 0) {
        send_ack(topic, sched, "FAILED", "unsupported action", NULL);
        goto cleanup;
    }
    if (duration <= 0 || duration > MAX_DURATION_SEC) {
        send_ack(topic, sched, "FAILED", "invalid duration", NULL);
        goto cleanup;
    }

    if (pump_state >= 0) {
        saveToSQLite("pump", payload, pump_state, now_ms());
    }

    PumpJob *job = (PumpJob *)calloc(1, sizeof(*job));
    if (!job) { send_ack(topic, sched, "FAILED", "oom", NULL); goto cleanup; }

    strncpy(job->cmd_topic,   topic, sizeof(job->cmd_topic) - 1);
    strncpy(job->schedule_id, sched, sizeof(job->schedule_id) - 1);
    strncpy(job->relay_topic, relay, sizeof(job->relay_topic) - 1);
    job->valve_id = valve_id;
    job->duration = duration;

    pthread_t tid;
    if (pthread_create(&tid, NULL, pump_worker, job) != 0) {
        send_ack(topic, sched, "FAILED", "thread create failed", NULL);
        free(job);
        goto cleanup;
    }
    pthread_detach(tid);

cleanup:
    cJSON_Delete(root);
done:
    MQTTClient_freeMessage(&msg);
    MQTTClient_free(topic);
    return 1;
}

static void on_disconnect(void *ctx, char *cause) {
    (void)ctx;
    fprintf(stderr, "[pump] connection lost: %s\n", cause ? cause : "unknown");
}

int main(void) {
    signal(SIGINT,  on_signal);
    signal(SIGTERM, on_signal);

    initSQLite();

    if (MQTTClient_create(&g_mqtt, MQTT_BROKER_URL, MQTT_CLIENT_ID,
                          MQTTCLIENT_PERSISTENCE_NONE, NULL) != MQTTCLIENT_SUCCESS) {
        fprintf(stderr, "[pump] MQTTClient_create failed\n");
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
        fprintf(stderr, "[pump] MQTT connect failed: %d\n", rc);
        return EXIT_FAILURE;
    }

    if (MQTTClient_subscribe(g_mqtt, CMD_TOPIC_FILTER, QOS) != MQTTCLIENT_SUCCESS) {
        fprintf(stderr, "[pump] subscribe %s failed\n", CMD_TOPIC_FILTER);
        return EXIT_FAILURE;
    }
    fprintf(stdout, "[pump] connected %s, subscribed %s (max duration %ds)\n",
            MQTT_BROKER_URL, CMD_TOPIC_FILTER, MAX_DURATION_SEC);
    fflush(stdout);

    /* Watchdog: client đồng bộ không tự reconnect. Nếu mất kết nối (bridge/network
     * blip), kết nối lại + subscribe lại để edge không "chết" tới lúc restart tay. */
    while (g_run) {
        if (!MQTTClient_isConnected(g_mqtt)) {
            fprintf(stderr, "[pump] disconnected, reconnecting...\n");
            if (MQTTClient_connect(g_mqtt, &opts) == MQTTCLIENT_SUCCESS
                && MQTTClient_subscribe(g_mqtt, CMD_TOPIC_FILTER, QOS) == MQTTCLIENT_SUCCESS) {
                fprintf(stdout, "[pump] reconnected + resubscribed %s\n", CMD_TOPIC_FILTER);
                fflush(stdout);
            } else {
                sleep(5); /* backoff trước khi thử lại */
            }
        }
        sleep(2);
    }

    MQTTClient_disconnect(g_mqtt, 1000);
    MQTTClient_destroy(&g_mqtt);
    if (db) sqlite3_close(db);
    return EXIT_SUCCESS;
}
