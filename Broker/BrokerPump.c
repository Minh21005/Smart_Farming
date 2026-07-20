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
#include <unistd.h>
#include <pthread.h>
#include <sys/time.h>

/* ============================ CONFIG (edit here) ============================ */

#define MQTT_BROKER_URL  "tcp://localhost:1883"
#define MQTT_USERNAME    "libe"
#define MQTT_PASSWORD    "123456"
#define MQTT_CLIENT_ID   "cassava-edge-pump"

#define CMD_TOPIC_FILTER "cassava/field/+/valve/+/cmd"

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
static volatile sig_atomic_t g_run = 1;
static pthread_mutex_t       g_pub_mutex = PTHREAD_MUTEX_INITIALIZER;
/* Cờ abort theo valve_id: lệnh CLOSE bật cờ để worker đang tưới thoát sớm. */
static volatile sig_atomic_t g_abort[MAX_VALVE_ID + 1];

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

static int build_ack_json(const char *schedule_id, int ok, const char *err,
                          char *out, size_t cap) {
    cJSON *root = cJSON_CreateObject();
    cJSON_AddStringToObject(root, "scheduleId", schedule_id ? schedule_id : "");
    cJSON_AddStringToObject(root, "ack",        ok ? "DONE" : "FAILED");
    cJSON_AddNumberToObject(root, "ackAt",      (double)now_ms());
    if (ok || !err) cJSON_AddNullToObject(root, "errorMessage");
    else            cJSON_AddStringToObject(root, "errorMessage", err);

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

static void send_ack(const char *cmd_topic, const char *schedule_id, int ok, const char *err) {
    char ack_topic[256];
    char payload[256];
    if (build_ack_topic(cmd_topic, ack_topic, sizeof(ack_topic)) != 0) {
        fprintf(stderr, "[pump] cannot build ack topic from %s\n", cmd_topic);
        return;
    }
    if (build_ack_json(schedule_id, ok, err, payload, sizeof(payload)) != 0) {
        fprintf(stderr, "[pump] cannot build ack json (schedule=%s)\n",
                schedule_id ? schedule_id : "?");
        return;
    }
    publish_str(ack_topic, payload);
    fprintf(stdout, "[pump] %s schedule=%s -> %s\n",
            ok ? "ACK_DONE" : "ACK_FAILED",
            schedule_id ? schedule_id : "?", ack_topic);
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
    cJSON *root = cJSON_CreateObject();
    cJSON_AddStringToObject(root, "scheduleId", schedule_id ? schedule_id : "");
    cJSON_AddStringToObject(root, "ack",        "RUNNING");
    cJSON_AddNumberToObject(root, "ackAt",      (double)now_ms());
    cJSON_AddNullToObject(root, "errorMessage");
    char *s = cJSON_PrintUnformatted(root);
    if (s && strlen(s) + 1 <= sizeof(payload)) {
        strcpy(payload, s);
        publish_str(ack_topic, payload);
        fprintf(stdout, "[pump] ACK_RUNNING schedule=%s -> %s\n",
                schedule_id ? schedule_id : "?", ack_topic);
        fflush(stdout);
    }
    if (s) cJSON_free(s);
    cJSON_Delete(root);
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

    if (job->valve_id >= 0 && job->valve_id <= MAX_VALVE_ID) g_abort[job->valve_id] = 0;

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
    if (job->valve_id >= 0 && job->valve_id <= MAX_VALVE_ID) g_abort[job->valve_id] = 0;

    fprintf(stdout, "[pump] %s schedule=%s relay=%s\n",
            stopped ? "STOPPED" : "DONE", job->schedule_id, job->relay_topic);
    fflush(stdout);

    send_ack(job->cmd_topic, job->schedule_id, 1, NULL);

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
        send_ack(topic, NULL, 0, "invalid json");
        goto done;
    }

    const cJSON *jSched = cJSON_GetObjectItemCaseSensitive(root, "scheduleId");
    const cJSON *jAct   = cJSON_GetObjectItemCaseSensitive(root, "action");
    const cJSON *jDur   = cJSON_GetObjectItemCaseSensitive(root, "durationSeconds");

    const char *sched = (cJSON_IsString(jSched) && jSched->valuestring) ? jSched->valuestring : NULL;
    const char *act   = (cJSON_IsString(jAct)   && jAct->valuestring)   ? jAct->valuestring   : NULL;
    int duration      = cJSON_IsNumber(jDur) ? jDur->valueint : 0;

    if (!sched || !act) {
        send_ack(topic, sched, 0, "missing scheduleId or action");
        goto cleanup;
    }

    int valve_id = parse_valve_id(topic);
    const char *relay = (valve_id > 0) ? lookup_relay(valve_id) : NULL;
    if (!relay) {
        send_ack(topic, sched, 0, "unknown valveId");
        goto cleanup;
    }

    /* CLOSE: dừng tưới đang chạy — tắt relay ngay + bật cờ abort để worker (nếu còn
     * đang đếm thời gian) thoát sớm. BE đã tính lượng nước một phần qua /stop nên ack
     * này chỉ là xác nhận (idempotent ở BE). */
    if (strcmp(act, "CLOSE") == 0) {
        /* CHỈ set cờ abort — KHÔNG publish/ack trong callback. Publish ngay trong
         * on_message sẽ kẹt thread nhận của Paho sync client (sau đó không nhận
         * thêm cmd nào). Worker đang chạy sẽ phát hiện cờ và tự tắt relay + ack
         * DONE trong ~1s. BE cũng đã ghi DONE khi bấm Dừng nên không cần ack ở đây. */
        if (valve_id <= MAX_VALVE_ID) g_abort[valve_id] = 1;
        fprintf(stdout, "[pump] CLOSE schedule=%s valve=%d (abort set)\n", sched, valve_id);
        fflush(stdout);
        goto cleanup;
    }

    if (strcmp(act, "OPEN_TIMED") != 0) {
        send_ack(topic, sched, 0, "unsupported action");
        goto cleanup;
    }
    if (duration <= 0 || duration > MAX_DURATION_SEC) {
        send_ack(topic, sched, 0, "invalid duration");
        goto cleanup;
    }

    PumpJob *job = (PumpJob *)calloc(1, sizeof(*job));
    if (!job) { send_ack(topic, sched, 0, "oom"); goto cleanup; }

    strncpy(job->cmd_topic,   topic, sizeof(job->cmd_topic) - 1);
    strncpy(job->schedule_id, sched, sizeof(job->schedule_id) - 1);
    strncpy(job->relay_topic, relay, sizeof(job->relay_topic) - 1);
    job->valve_id = valve_id;
    job->duration = duration;

    pthread_t tid;
    if (pthread_create(&tid, NULL, pump_worker, job) != 0) {
        send_ack(topic, sched, 0, "thread create failed");
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
    return EXIT_SUCCESS;
}
