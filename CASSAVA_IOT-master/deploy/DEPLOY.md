# Hướng dẫn deploy Cassava IoT lên server UET

Target server:
- **IP public**: 112.137.129.218
- **IP LAN**: 10.10.0.81
- **User**: uet
- **SSH port**: 22

Kiến trúc cuối cùng:

```
http://112.137.129.218/              → landing page (4 card cây trồng)
http://112.137.129.218/cassava/      → CassavaFE (React SPA)
http://112.137.129.218/cassava/api/* → proxy tới Spring Boot (127.0.0.1:8081)
```

---

## 1. Build artifact ở máy dev

### Build FE

```bash
cd CassavaFE
npm install
npm run build
# Output: CassavaFE/dist/
```

`dist/` chứa bundle đã inject `base: '/cassava/'` và env `VITE_API_BASE=/cassava/api`.

### Build BE

```bash
cd cassavaBE
mvn clean package -DskipTests
# Output: cassavaBE/target/demo1-0.0.1-SNAPSHOT.war
```

---

## 2. Chuẩn bị server lần đầu

SSH vào server:

```bash
ssh uet@112.137.129.218
```

Cài đặt gói:

```bash
sudo apt update
sudo apt install -y openjdk-17-jre-headless nginx rsync
```

Kiểm tra Java:

```bash
java -version   # phải là 17
```

Tạo cấu trúc thư mục:

```bash
sudo mkdir -p /opt/cassava/webroot/cassava
sudo mkdir -p /var/log/cassava
sudo chown -R uet:uet /opt/cassava /var/log/cassava
```

Mở firewall port 80 (nếu dùng ufw):

```bash
sudo ufw allow 80/tcp
sudo ufw status
```

---

## 3. Upload artifact từ máy dev

Từ máy dev (Git Bash trên Windows, hoặc WSL):

```bash
# Landing page
rsync -avz --delete landing/ uet@112.137.129.218:/opt/cassava/webroot/

# CassavaFE build
rsync -avz --delete CassavaFE/dist/ uet@112.137.129.218:/opt/cassava/webroot/cassava/

# Backend WAR
rsync -avz cassavaBE/target/demo1-0.0.1-SNAPSHOT.war uet@112.137.129.218:/opt/cassava/cassava-be.war
```

Kiểm tra trên server:

```bash
ssh uet@112.137.129.218 'ls -la /opt/cassava/ /opt/cassava/webroot/'
```

---

## 4. Cài systemd service

Upload file service:

```bash
scp deploy/systemd/cassava-be.service uet@112.137.129.218:/tmp/
```

Trên server:

```bash
sudo mv /tmp/cassava-be.service /etc/systemd/system/
sudo systemctl daemon-reload
sudo systemctl enable --now cassava-be
sudo systemctl status cassava-be
```

Xem log realtime:

```bash
sudo journalctl -u cassava-be -f
```

Kiểm tra BE đã chạy (localhost):

```bash
curl -s http://127.0.0.1:8081/mongo/field | head -c 200
```

---

## 5. Cài nginx

Upload nginx config:

```bash
scp deploy/nginx/cassava.conf uet@112.137.129.218:/tmp/
```

Trên server:

```bash
sudo mv /tmp/cassava.conf /etc/nginx/sites-available/cassava
sudo ln -sf /etc/nginx/sites-available/cassava /etc/nginx/sites-enabled/cassava

# Xóa default site nếu có
sudo rm -f /etc/nginx/sites-enabled/default

# Test config và reload
sudo nginx -t
sudo systemctl reload nginx
```

Test:

- Mở trình duyệt: `http://112.137.129.218/` → landing
- Click "Truy cập" trên card Sắn → `http://112.137.129.218/cassava/` → CassavaFE
- Đăng nhập → thấy FE gọi `/cassava/api/...` thành công

---

## 6. Edge ingest binaries (C, chạy trên pi3)

Hai chương trình C standalone (`edge/edge_to_mongo_weather.c` + `edge/edge_to_mongo_soil.c`) subscribe mosquitto edge và ghi raw sensor reading vào MongoDB `sensor_value`. Đây là phần persistence (thay thế `MqttWeatherService` đã bị xóa). Mỗi `.c` file là self-contained — toàn bộ config (Mongo URI, MQTT creds, `DEFAULT_GROUP_ID`, bảng `SOIL_FIELDS` topic→`fieldId`) hardcode trong block `CONFIG` ở đầu file. Sửa thì edit và recompile, không có Makefile / config JSON / shared helper. Chi tiết kiến trúc xem `deploy/MQTT.md` §11.

### 6.1. Yêu cầu trên pi3

- Mosquitto đã chạy với user/pass mà edge sensor đang dùng (mặc định `libe / 123456` — xem `deploy/MQTT.md` §8).
- Build toolchain + paho-mqtt + libmongoc:

```bash
sudo apt install -y build-essential pkg-config \
                    libpaho-mqtt-dev libmongoc-dev libbson-dev
```

### 6.2. Upload source từ máy dev

```bash
rsync -avz --delete edge/ uet@112.137.129.218:/opt/cassava/edge/
```

Nếu pi3 ≠ server prod (BE), upload trực tiếp vào pi3:

```bash
rsync -avz --delete edge/ pi@<pi3-host>:/opt/cassava/edge/
```

Trước khi sync lần đầu (hoặc khi rotate creds / đổi field IDs), kiểm tra block `CONFIG` ở đầu mỗi file:

- `edge/edge_to_mongo_weather.c` — `MONGO_URI`, `MQTT_BROKER_URL`, `MQTT_USERNAME`, `MQTT_PASSWORD`, `DEFAULT_GROUP_ID`, `WEATHER_TOPIC`.
- `edge/edge_to_mongo_soil.c` — same constants, plus bảng `SOIL_FIELDS` map topic mosquitto (`field1`, `field2`, …) sang `field._id` thật trong Mongo. Topic chưa có trong bảng sẽ bị drop kèm warning (`unknown topic ...`).

### 6.3. Build (lần đầu + sau mỗi lần sửa `.c`)

Trên pi3:

```bash
cd /opt/cassava/edge

cc -O2 edge_to_mongo_weather.c -o edge_to_mongo_weather \
   $(pkg-config --cflags --libs libmongoc-1.0) -lpaho-mqtt3c

cc -O2 edge_to_mongo_soil.c -o edge_to_mongo_soil \
   $(pkg-config --cflags --libs libmongoc-1.0) -lpaho-mqtt3c
```

### 6.4. Tạo systemd unit

Tạo `/etc/systemd/system/cassava-edge-weather.service`:

```ini
[Unit]
Description=Cassava edge weather → MongoDB ingest
After=network-online.target mosquitto.service
Wants=network-online.target

[Service]
Type=simple
User=uet
WorkingDirectory=/opt/cassava/edge
ExecStart=/opt/cassava/edge/edge_to_mongo_weather
Restart=on-failure
RestartSec=5
StandardOutput=append:/var/log/cassava/edge-weather.log
StandardError=append:/var/log/cassava/edge-weather.log

[Install]
WantedBy=multi-user.target
```

Tạo `/etc/systemd/system/cassava-edge-soil.service` y hệt nhưng đổi:
- `Description=Cassava edge soil → MongoDB ingest`
- `ExecStart=/opt/cassava/edge/edge_to_mongo_soil`
- log file → `/var/log/cassava/edge-soil.log`

Enable + start:

```bash
sudo mkdir -p /var/log/cassava && sudo chown uet:uet /var/log/cassava
sudo systemctl daemon-reload
sudo systemctl enable --now cassava-edge-weather cassava-edge-soil
sudo systemctl status cassava-edge-weather cassava-edge-soil
```

### 6.5. Verify

```bash
# Publish test message (đổi user/pass theo MQTT_USERNAME/MQTT_PASSWORD trong .c)
mosquitto_pub -h <broker_host> -t /sensor/weatherStation2 \
  -m "t 25.3;h 60;rad 800;rai 0;w 1.2" -u <user> -P <pass>

# Kiểm tra Mongo có 5 row mới
mongosh "mongodb://admin:uet%402026@112.137.129.218:27017/iot_agriculture?authSource=admin" \
  --eval 'db.sensor_value.find({source:"mqtt"}).sort({time:-1}).limit(5)'

# Tail log
tail -f /var/log/cassava/edge-weather.log
```

Log thành công: `[edge:weather] /sensor/weatherStation2 inserted 5 row(s) (payload: ...)`.


---

## 7. Quy trình cập nhật (sau lần đầu)

### Chỉ FE thay đổi

```bash
cd CassavaFE && npm run build
rsync -avz --delete dist/ uet@112.137.129.218:/opt/cassava/webroot/cassava/
```

Không cần restart gì cả — nginx serve file tĩnh.

### Chỉ BE thay đổi

```bash
cd cassavaBE && mvn clean package -DskipTests
rsync -avz target/demo1-0.0.1-SNAPSHOT.war uet@112.137.129.218:/opt/cassava/cassava-be.war
ssh uet@112.137.129.218 'sudo systemctl restart cassava-be'
```

### Edge C thay đổi

```bash
rsync -avz --delete edge/ uet@112.137.129.218:/opt/cassava/edge/
ssh uet@112.137.129.218 'cd /opt/cassava/edge && \
  cc -O2 edge_to_mongo_weather.c -o edge_to_mongo_weather \
     $(pkg-config --cflags --libs libmongoc-1.0) -lpaho-mqtt3c && \
  cc -O2 edge_to_mongo_soil.c -o edge_to_mongo_soil \
     $(pkg-config --cflags --libs libmongoc-1.0) -lpaho-mqtt3c && \
  sudo systemctl restart cassava-edge-weather cassava-edge-soil'
```

Mỗi file C standalone, recompile cả hai mỗi lần đổi (build < 1s). Sửa creds / field IDs thì edit block `CONFIG` ở đầu `.c` rồi chạy lại lệnh trên.

### Landing page thay đổi

```bash
rsync -avz --delete landing/ uet@112.137.129.218:/opt/cassava/webroot/
```

---

## 8. Troubleshoot

| Triệu chứng | Kiểm tra |
|---|---|
| Landing 404 | `ls /opt/cassava/webroot/index.html` có tồn tại? |
| `/cassava/` blank page | DevTools Network: file JS/CSS có load từ `/cassava/assets/...` không? Nếu 404 → rebuild FE (base path sai) |
| API 502 Bad Gateway | `sudo systemctl status cassava-be` — BE có chạy không? `curl http://127.0.0.1:8081/` có trả về gì không? |
| API 504 timeout | Mô phỏng chạy lâu → tăng `proxy_read_timeout` trong nginx config |
| BE lỗi MongoDB | Test connect: `mongosh mongodb://admin:uet%402026@112.137.129.218:27017/iot_agriculture?authSource=admin` |
| Firebase log spam | Đã được disable graceful (file key không tồn tại → skip). Nếu vẫn thấy stack trace về firebase, kiểm tra `FirebaseInitialization.java` |
| Edge script không ghi Mongo | `sudo systemctl status cassava-edge-weather` — đang chạy? `tail /var/log/cassava/edge-weather.log` có thấy `connected` + `subscribed`? |
| Edge log "auth failed" | Kiểm tra `MQTT_USERNAME` / `MQTT_PASSWORD` ở đầu file `.c` khớp `/etc/mosquitto/passwd`; sửa rồi recompile (xem §7) |
| Edge log "MongoDB connection refused" | `MONGO_URI` ở đầu file `.c` sai hoặc Mongo không listen 127.0.0.1 — confirm bằng `mongosh "<uri>"` cùng máy |
| Edge log "unknown topic ..." | Bảng `SOIL_FIELDS` trong `edge_to_mongo_soil.c` thiếu mapping cho topic đó — thêm dòng `{"fieldX", "<ObjectId>"}` rồi recompile |
| BE log `RANGE_FAIL` quá nhiều | Sensor lỗi vật lý hoặc threshold trong `application-prod.properties` cần điều chỉnh — sửa `anomaly.range.<sensor>.{min,max}` rồi restart BE |

Xem log BE chi tiết:

```bash
sudo journalctl -u cassava-be -n 200 --no-pager
tail -f /var/log/cassava/app.log
```

Xem log edge ingest:

```bash
sudo journalctl -u cassava-edge-weather -n 200 --no-pager
sudo journalctl -u cassava-edge-soil -n 200 --no-pager
tail -f /var/log/cassava/edge-weather.log /var/log/cassava/edge-soil.log
```

---

## 9. Thêm hệ thống cây mới (ví dụ tomato)

Sau này khi có TomatoFE + TomatoBE:

1. Build TomatoFE với `base: '/tomato/'` và `VITE_API_BASE=/tomato/api`
2. Deploy FE vào `/opt/cassava/webroot/tomato/`
3. Run TomatoBE trên port khác (8082)
4. Thêm block vào `/etc/nginx/sites-available/cassava`:

```nginx
location /tomato/api/ {
    proxy_pass http://127.0.0.1:8082/;
    # ... proxy headers như block cassava
}
location /tomato/ {
    try_files $uri $uri/ /tomato/index.html;
}
```

5. Sửa `landing/index.html`: đổi card Tomato từ `disabled` thành `active`, link `/tomato/`

---

## 10. Ghi chú bảo mật

- Hiện tại chạy HTTP, JWT đi plain text. Có domain thì chuyển sang HTTPS bằng Let's Encrypt (`certbot --nginx`)
- Spring Boot bind 127.0.0.1 → không thể truy cập trực tiếp port 8081 từ internet
- MongoDB đang public (112.137.129.218:27017) — cân nhắc đổi sang bind LAN hoặc thêm iptables rule chỉ cho phép localhost
- Firewall: chỉ mở 80 (HTTP), 22 (SSH). Không mở 8081, 27017, 1883 cho internet
- `edge/edge_to_mongo_weather.c` + `edge/edge_to_mongo_soil.c` hiện hardcode Mongo URI và mật khẩu mosquitto trong source — và source nằm trong git. Đây là pattern legacy của các C program Firebase ở root repo (`doc_thoi_tiet.c`, `doc_do_am.c`, `dk_bom.c`). Nếu cần tách creds khỏi git, chuyển sang đọc từ file config riêng (không commit) thay vì `#define`.
- Mosquitto bind 127.0.0.1 (xem `deploy/MQTT.md` §8) → edge sensor và BE phải cùng máy. Nếu cần expose ra LAN, bật TLS + ACL chứ đừng mở port 1883 thẳng
