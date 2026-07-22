# ml-service

Python service for CASSAVA_IOT. Two roles:

- **Anomaly detection** — per-sensor detectors flag bad points on the streaming sensor path: `zscore` + `seasonal_zscore` (statistical, always on) plus `arima_residual` / `sarima_residual` / `lstm_residual` (forecaster residuals, each registered when its artifact exists).
- **Forecasting** — per-sensor ARIMA / SARIMA / LSTM predict the next `h` hourly values for irrigation planning.

These are deliberately separate. A model that's good at one is rarely the best at the other: detectors want robustness to outliers, forecasters want signal-tracking.

```
ml/
├── base.py              # AnomalyDetector ABC + Forecaster ABC
├── detectors/
│   ├── zscore.py            # Modified Z-score (sliding MAD)
│   ├── seasonal_zscore.py   # hour-of-day baseline
│   └── residual.py          # wraps a Forecaster as a Detector
└── forecasters/
    ├── arima.py
    ├── sarima.py
    └── lstm.py              # multivariate, multi-target Dense(5), 48h window

api/
├── main.py                  # mounts both routers
├── schemas.py
├── registry.py              # in-process model registry
└── routes/
    ├── detect.py            # POST /detect
    └── forecast.py          # POST /forecast

scripts/
├── fetch_nasa.py                   # ONE-TIME: pull NASA POWER CSV
├── check_data.py                   # verify Mongo data sufficiency
├── train.py                        # train per-sensor forecasters from CSV
├── evaluate_detection.py           # P/R/F1 vs k, with anomaly injection
├── evaluate_forecast_per_sensor.py # one-step MAE/RMSE/MAPE per sensor (+ shared eval helpers)
├── seasonal_common.py              # 4-season rolling-origin windowing
├── evaluate_detection_seasonal.py  # KB1: seasonal detection backtest
├── evaluate_forecast_seasonal.py   # KB2 recovery + KB3 forecast backtest
└── export_seasonal_streams.py      # dump clean / recovered / forecast streams
```

## Branch context

This branch (`feat/anomaly-compare`) carries both the detection and forecasting halves under one tree, plus the BE→ml-service integration (see below). The pure branches `feat/anomaly-zscore` (statistical only) and `feat/anomaly-ml` (ARIMA/SARIMA/LSTM only) remain as single-approach references.

## Integration with the backend

On this branch the service sits on the live sensor path. The Spring Boot BE's `MqttSensorListener` POSTs every weather reading to `/detect` (`{groupId, sensorId, time, value}`), picks one method's verdict per sensor (`PreferredDetectionMethods`), and on an anomaly writes a `sensor_correction` row (raw `actual` + detector `predicted` + method + score). The call is **fail-soft**: 2s timeout, and if ml-service is down the BE skips the verdict and keeps running. ml-service is **stateless** — it returns verdicts/forecasts and never writes to Mongo (persistence is the BE's job; raw `sensor_value` is owned by the edge C binaries). In prod it binds to loopback (`127.0.0.1:8082`) and is started alongside the BE.

## Setup

Python 3.13.

```bash
cd ml-service
python -m venv .venv
source .venv/Scripts/activate   # Windows Git Bash
# .venv\Scripts\activate        # Windows cmd / PowerShell
# source .venv/bin/activate     # Linux / macOS

pip install -r requirements.txt
cp .env.example .env
```

## Training (forecasters only)

Two-step workflow: fetch NASA POWER once into a CSV, then train forecasters from it. The detectors don't need training — they fit at API startup from the same CSV.

```bash
# 1. ONE TIME: fetch NASA POWER -> artifacts/nasa/training_data.csv
#    Default range: 2020-01-01 -> 2025-12-30 (skips known-corrupt 2025-12-31+ data)
python -m scripts.fetch_nasa

# 2. Train forecasters
python -m scripts.train --model arima                 # fast (~1-2 min)
python -m scripts.train --model sarima                # ~30-60 min
python -m scripts.train --model lstm                  # ~15-45 min CPU
python -m scripts.train --model all
```

### Auto-selecting ARIMA/SARIMA orders

```bash
python -m scripts.train --model arima --auto-order    # pmdarima.auto_arima
python -m scripts.train --model sarima --auto-order   # statsforecast AutoARIMA
```

ARIMA uses `pmdarima` on the `statsmodels` backend. SARIMA uses `statsforecast`'s Cython AutoARIMA — `statsmodels.SARIMAX` allocates a `(state_dim², T)` Kalman covariance array during fit and OOMs on multi-year hourly data with seasonal=24, while `statsforecast` handles the same problem in tens of MB.

Search bounds: ARIMA `max_p=5, max_q=5, max_d=2`; SARIMA `max_p=2, max_q=2, max_P=1, max_Q=1, max_d=1, max_D=1, m=24`.

Override defaults: `python -m scripts.fetch_nasa --start 2018-01-01 --end 2024-12-30 --output custom.csv` then `python -m scripts.train --model all --data custom.csv`.

Artifacts land in `artifacts/{arima_<sensor>.pkl, sarima_<sensor>.pkl, lstm/{model.keras,meta.pkl}}` (per-sensor ARIMA/SARIMA, one shared multi-target LSTM) and `artifacts/nasa/training_data.csv`. The API loads whichever artifacts exist at startup; missing ones are skipped (warning logged).

## Evaluating

### Detection — precision / recall / F1

```bash
python -m scripts.evaluate_detection                              # default detectors, k=3.0
python -m scripts.evaluate_detection --sweep-k 1.0 10.0 0.5
python -m scripts.evaluate_detection --methods all                # include lstm_residual etc.
python -m scripts.evaluate_detection --inject spike,drift
```

Default detectors are `zscore,seasonal_zscore`. Pass `--methods all` (or include `arima_residual`, `sarima_residual`, `lstm_residual` explicitly) to evaluate forecasters wrapped as detectors via the residual rule.

### Forecasting — MAE / RMSE / MAPE on clean test data

```bash
python -m scripts.evaluate_forecast_per_sensor                   # one-step error, all 5 sensors
python -m scripts.evaluate_forecast_per_sensor --methods arima,lstm
python -m scripts.evaluate_forecast_seasonal                     # 4-season rolling-origin (KB2 recovery + KB3 forecast)
```

This walks the **unperturbed** test slice in one-step-ahead mode: at each timestamp, predict, compare against the actual continuation, then `update()` with the truth. No anomaly injection — the apples-to-apples forecast quality comparison. The seasonal variant refits per window across Mar/Jun/Sep/Dec (no future leakage).

## Run

```bash
# 1. Fetch NASA + train forecasters (one time)
python -m scripts.fetch_nasa
python -m scripts.train --model all

# 2. (Optional) Backtests
python -m scripts.evaluate_detection
python -m scripts.evaluate_forecast_per_sensor

# 3. Start API — loads forecaster artifacts + fits detectors from the NASA CSV
uvicorn api.main:app --port 8082
```

## API

- `GET  /health` — liveness + lists of loaded detectors and forecasters
- `GET  /models` — per-model metadata (role: `detector` / `forecaster`)
- `POST /detect` — body `{groupId, sensorId, time, value}` → per-method anomaly verdict + combined `is_anomaly`
- `POST /forecast` — body `{groupId, sensorId, time, horizon}` → per-method `[t+1 ... t+horizon]` predictions

## Config

All via `.env` (see `.env.example`). Key values:

- `MONGO_URI`, `MONGO_DB` — same Mongo as `cassavaBE`
- `GROUP_ID`, `SENSOR_ID` — **defaults for the Mongo data utilities only** (`scripts.check_data`, `ml/data.py` fetch helpers). The serving API ignores them: `/detect` and `/forecast` route by the `sensorId` in the request body and register all 5 weather sensors at startup. `GROUP_ID` mirrors the hardcoded group in `edge/edge_to_mongo_weather.c`.
- `ANOMALY_K` — z-score threshold default; `|score| > K` ⇒ anomaly
- `RESAMPLE_FREQ` — pandas freq string for hourly resampling

## Status

Done:
- Per-sensor detection + forecasting across all 5 weather sensors.
- BE integration: verdicts consumed on the live MQTT path and anomalies persisted to `sensor_correction` (raw + `predicted`) — see *Integration with the backend* above.
- 4-season rolling-origin backtest (`scripts/*_seasonal.py`) covering detection (KB1), recovery (KB2), and forecast (KB3).

Not yet done:
- Live-stream backfill — the bad raw `sensor_value` is kept as-is; `sensor_correction.predicted` is layered on top (downstream consumers prefer it) rather than overwriting the raw row.
- FE visualization of anomalies / corrections.
- A dedicated systemd unit for ml-service in prod (currently started alongside the BE).
