#include <ESP8266WiFi.h>
#include <PubSubClient.h>

// ====== PIN PUMP ======
#define Pump1 5
#define Pump2 4
#define Pump3 12
#define Pump4 13

// GPIO 5 - Pum1 D1
// GPIO4 - Pump2 D2
// GPIO12 - Pump3 D6
// GPIO13 - Pump4 D7

// ====== WIFI ======
#define WIFI_SSID "K_CNNN"
#define WIFI_PASSWORD "congnghenn"

// ====== MQTT ======
#define MQTT_SERVER "192.168.1.22"
#define MQTT_PORT 1883
#define MQTT_USERNAME "student"
#define MQTT_PASSW "123456"

WiFiClient espClient;
PubSubClient mqttClient(espClient);

// ====== CONNECT WIFI ======
void connectWifi() {
  Serial.print("Connecting WiFi...");
  WiFi.begin(WIFI_SSID, WIFI_PASSWORD);

  while (WiFi.status() != WL_CONNECTED) {
    delay(500);
    Serial.print(".");
  }

  Serial.println("\nWiFi connected!");
  Serial.print("IP: ");
  Serial.println(WiFi.localIP());
}

// ====== CALLBACK MQTT ======
void callback(char* topic, byte* payload, unsigned int length) {
  Serial.print("Topic: ");
  Serial.println(topic);

  String msg = "";
  for (int i = 0; i < length; i++) {
    msg += (char)payload[i];
  }

  Serial.print("Message: ");
  Serial.println(msg);

  // ===== CONTROL PUMP =====
  if (strcmp(topic, "Pump1") == 0) {
    digitalWrite(Pump1, msg == "1" ? HIGH : LOW);
  }

  if (strcmp(topic, "Pump2") == 0) {
    digitalWrite(Pump2, msg == "1" ? HIGH : LOW);
  }

  if (strcmp(topic, "Pump3") == 0) {
    digitalWrite(Pump3, msg == "1" ? HIGH : LOW);
  }

  if (strcmp(topic, "Pump4") == 0) {
    digitalWrite(Pump4, msg == "1" ? HIGH : LOW);
  }
}

// ====== CONNECT MQTT ======
void reconnect() {
  while (!mqttClient.connected()) {

    //  nếu mất WiFi thì connect lại
    if (WiFi.status() != WL_CONNECTED) {
      connectWifi();
    }

    Serial.print("Connecting MQTT...");

    if (mqttClient.connect("ESP8266_Client", MQTT_USERNAME, MQTT_PASSW)) {
      Serial.println("connected!");

      mqttClient.subscribe("Pump1");
      mqttClient.subscribe("Pump2");
      mqttClient.subscribe("Pump3");
      mqttClient.subscribe("Pump4");

      mqttClient.publish("ESP8266/status", "Connected");
    } else {
      Serial.print("failed, rc=");
      Serial.println(mqttClient.state());
      delay(2000);
    }
  }
}

// ====== SETUP ======
void setup() {
  Serial.begin(9600);

  pinMode(Pump1, OUTPUT);
  pinMode(Pump2, OUTPUT);
  pinMode(Pump3, OUTPUT);
  pinMode(Pump4, OUTPUT);

  // mặc định tắt (tùy relay)
  digitalWrite(Pump1, LOW);
  digitalWrite(Pump2, LOW);
  digitalWrite(Pump3, LOW);
  digitalWrite(Pump4, LOW);

  connectWifi();

  mqttClient.setServer(MQTT_SERVER, MQTT_PORT);
  mqttClient.setCallback(callback);

}

// ====== LOOP ======
void loop() {
  /*
  Serial.println("dangtat");
  digitalWrite(Pump1, LOW);
  digitalWrite(Pump2, LOW);
  digitalWrite(Pump3, LOW);
  digitalWrite(Pump4, LOW);
  delay(30000);
  Serial.println("dangbat");
  digitalWrite(Pump1, HIGH);
  digitalWrite(Pump2, HIGH);
  digitalWrite(Pump3, HIGH);
  digitalWrite(Pump4, HIGH);
  delay(30000);
*/


  if (!mqttClient.connected()) {
    reconnect();
  }

  mqttClient.loop();

  // gửi trạng thái mỗi 5p
  static unsigned long lastStatus = 0;
  if (millis() - lastStatus > 300000) {
    lastStatus = millis();
    mqttClient.publish("ESP8266/status", "Still connected");
    Serial.println("Published: ESP MQTT Still connected");
  }
  
}