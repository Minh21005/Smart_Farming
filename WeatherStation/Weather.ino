#include <ESP8266WiFi.h>
#include <PubSubClient.h>
#include <Wire.h>
#include <Adafruit_Sensor.h>
#include <Adafruit_BME280.h>
#include <BH1750.h>
// =====================================
// BME280
// =====================================

Adafruit_BME280 bme;
BH1750 lightMeter;
// =====================================
// SENSOR VALUES
// =====================================

float radiation = 0;
float humidity = 0;
float temperature = 0;

float windSpeed = 0;
float rainAmount = 0;
float lux = 0;
// =====================================
// WIFI
// =====================================

const char* ssid = "K_CNNN";
const char* wifi_password = "congnghenn";

// =====================================
// MQTT
// =====================================

const char* MQTT_SERVER = "192.168.1.22";
const uint16_t MQTT_PORT = 1883;

const char* username = "student";
const char* password = "123456";

const char* topic = "/sensor/weatherStation";

// =====================================
// MQTT OBJECT
// =====================================

WiFiClient espClient;
PubSubClient client(espClient);

// =====================================
// PIN CONFIG
// =====================================

#define ANEMOMETER_PIN D5 //white: GND // green:GPIO
#define RAIN_PIN D6 //black: GND // brown:GPIO

// BME280
// SDA = D2
// SCL = D1

// =====================================
// TIMING
// =====================================

#define CALC_INTERVAL 1000
#define MQTT_INTERVAL 60000
#define RAIN_RESET_TIMEOUT 300000
#define DEBOUNCE_TIME 5000

static unsigned long lastMinuteMillis = 0;
unsigned long nextCalc = 0;
unsigned long nextMQTT = 0;

// =====================================
// WIND
// =====================================

volatile unsigned int anemometerCounter = 0;
volatile unsigned long last_micros_an = 0;

// =====================================
// RAIN
// =====================================

volatile unsigned int rainTrigger = 0;
volatile unsigned long last_micros_rg = 0;
// thời gian có mưa gần nhất
unsigned long lastRainMillis = 0;
// =====================================
// FUNCTION PROTOTYPES
// =====================================

void reconnectMQTT();

float readWindSpd();

void ICACHE_RAM_ATTR countAnemometer();

void ICACHE_RAM_ATTR countingRain();

// =====================================
// SETUP
// =====================================

void setup() {

  Serial.begin(9600);

  Serial.println("Starting Weather Station");

  // =====================================
  // BME280
  // =====================================

  Wire.begin(D2, D1);

  lightMeter.begin(BH1750::CONTINUOUS_HIGH_RES_MODE);
  Serial.println("BH1750 ready");
  if (!lightMeter.begin(BH1750::CONTINUOUS_HIGH_RES_MODE)) {
  Serial.println("Khong tim thay BH1750!");
}
  bool status = bme.begin(0x76);

  // nếu không được thử 0x77
  // bool status = bme.begin(0x77);

  if (!status) {

    Serial.println("Khong tim thay BME280!");
    ///*
    Serial.println("Khong tim thay BME280! Dang khoi dong lai...");
    delay(3000);
    ESP.restart();
    //*/
    //while (1);
  }

  Serial.println("BME280 ready");

  // =====================================
  // SENSOR PINS
  // =====================================

  pinMode(ANEMOMETER_PIN, INPUT_PULLUP);

  attachInterrupt(
    digitalPinToInterrupt(ANEMOMETER_PIN),
    countAnemometer,
    FALLING
  );

  pinMode(RAIN_PIN, INPUT_PULLUP);

  attachInterrupt(
    digitalPinToInterrupt(RAIN_PIN),
    countingRain,
    FALLING
  );

  // =====================================
  // WIFI
  // =====================================
  WiFi.mode(WIFI_STA);
  WiFi.begin(ssid, wifi_password);

  Serial.print("Connecting WiFi");

  while (WiFi.status() != WL_CONNECTED) {
    delay(500);
    Serial.print(".");
    
  }

  // 

  Serial.println();
  Serial.println("WiFi Connected");

  Serial.print("IP: ");
  Serial.println(WiFi.localIP());

  Serial.println();
  Serial.print("Dia chi MAC cua ESP8266 la: ");
  Serial.println(WiFi.macAddress());

  // =====================================
  // MQTT
  // =====================================

  client.setServer(MQTT_SERVER, MQTT_PORT);

  reconnectMQTT();

  nextCalc = millis() + CALC_INTERVAL;

  nextMQTT = millis() + MQTT_INTERVAL;

  Serial.println("Weather Station Started");
  
}

// =====================================
// LOOP
// =====================================

void loop() {

  // =====================================
  // MQTT
  // =====================================
  

  while (WiFi.status() != WL_CONNECTED) {
    delay(500);
    Serial.print(".");
    
  }

  if (!client.connected()) {
    reconnectMQTT();
  }

  client.loop();

  unsigned long now = millis();
  // =====================================
  // RESET RAIN AFTER 10 MIN NO PULSE
  // =====================================

  if ((now - lastRainMillis) >= RAIN_RESET_TIMEOUT &&
      rainTrigger > 0) {

    noInterrupts();

    rainTrigger = 0;

    interrupts();

    rainAmount = 0;

    Serial.println("Rain reset after 10 minutes no pulse");
  }

  if (now - lastMinuteMillis >= 60000) {
    lastMinuteMillis = now;
    
  }
  // =====================================
  // READ SENSOR
  // =====================================

  if (now >= nextCalc) {

    nextCalc = now + CALC_INTERVAL;

    // =====================================
    // BME280
    // =====================================

    temperature = bme.readTemperature();
    
    

    humidity = bme.readHumidity();

    // =====================================
    // WIND
    // =====================================

    Serial.print("windPulse: ");
    Serial.println(anemometerCounter);

  lux=lightMeter.readLightLevel();// lux
    
    radiation =  2e-9 * lux * lux - 2e-5 * lux + 0.4265;
    if(radiation<=0 || lux <=1){
      radiation = 0;
    }

    windSpeed = readWindSpd();

    // =====================================
    // RAIN
    // =====================================

    noInterrupts();

    Serial.print("rainpulse: ");
    Serial.println(rainTrigger);

    unsigned int rainCount = rainTrigger;

    interrupts();
  //mm
    rainAmount = rainCount * 0.2794;

    // =====================================
    // SERIAL
    // =====================================

    Serial.println("========== DATA ==========");
    
    Serial.print("Lux thuc te: ");
    Serial.println(lux);
    
    Serial.print("Radiation: ");
    Serial.println(radiation, 2);

    Serial.print("Rain: ");
    Serial.println(rainAmount);

    Serial.print("Humidity: ");
    Serial.println(humidity);

    Serial.print("Temperature: ");
    Serial.println(temperature);

    Serial.print("Wind: ");
    Serial.println(windSpeed);

    Serial.println();
  }

  // =====================================
  // SEND MQTT
  // =====================================

  if (now >= nextMQTT) {

    nextMQTT = now + MQTT_INTERVAL;

    char payload[128];

    snprintf(
      payload,
      sizeof(payload),
      "rad %.2f;rai %.1f;h %.1f;t %.1f;w %.1f",
      radiation,
      rainAmount,
      humidity,
      temperature,
      windSpeed
    );

    Serial.println("Sending MQTT:");
    Serial.println(payload);

    client.publish(topic, payload);
  }

  delay(1);

  

}

// =====================================
// MQTT RECONNECT
// =====================================

void reconnectMQTT() {

  while (!client.connected()) {

    Serial.print("Connecting MQTT...");

    if (client.connect(
          "ESP8266Weather",
          username,
          password)) {

      Serial.println("connected");

    } else {

      Serial.print("failed rc=");
      Serial.println(client.state());

      delay(5000);

     
    }
  }
  
}

// =====================================
// WIND SPEED
// =====================================

float readWindSpd() {

  noInterrupts();
  unsigned int count = anemometerCounter;
  anemometerCounter = 0; // Reset đếm xung cho chu kỳ 5s tiếp theo
  interrupts();

  //
  float pulsesPerSecond = count; 

  // m/s:
  return pulsesPerSecond * 0.667;

  // 1 pulse = 2.4 km/h = 0.667 m/s
  
}

// =====================================
// WIND ISR
// =====================================

void ICACHE_RAM_ATTR countAnemometer() {

  unsigned long now = micros();

  if ((now - last_micros_an) >= DEBOUNCE_TIME) {

    anemometerCounter++;

    last_micros_an = now;
  }
}

// =====================================
// RAIN ISR
// =====================================

void ICACHE_RAM_ATTR countingRain() {

  unsigned long now = micros();

  if ((now - last_micros_rg) >= DEBOUNCE_TIME) {

    rainTrigger++;
  // cập nhật thời gian mưa gần nhất
    lastRainMillis = millis();
    last_micros_rg = now;
  }
}