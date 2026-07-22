"""FastAPI app -- detector + forecaster split.

Two roles, two endpoints, both per-sensor:
  - /detect   -> for each weather sensor: zscore + seasonal_zscore (always)
                 plus arima_residual / sarima_residual / lstm_residual (each
                 when its artifact exists).
  - /forecast -> per-sensor ARIMA / SARIMA loaded from `{model}_{sensor}.pkl`,
                 plus a single multi-target LSTM (Dense(5)) viewed per sensor.

Each artifact failure is independent: missing files or bad fits leave the
remaining models registered. /health and /models report the live registry.
"""
from __future__ import annotations

import logging
from contextlib import asynccontextmanager
from pathlib import Path

import pandas as pd
from fastapi import FastAPI

from api.registry import DETECTORS, FORECASTERS
from api.routes.detect import router as detect_router
from api.routes.forecast import router as forecast_router
from api.schemas import ModelInfo
from ml.base import AnomalyDetector, Forecaster
from ml.config import ARTIFACTS_DIR
from ml.detectors import ResidualDetector, SeasonalZScoreDetector, ZScoreDetector
from ml.forecasters import ArimaForecaster, LstmForecaster, SarimaForecaster

logger = logging.getLogger(__name__)
logging.basicConfig(level=logging.INFO)

NASA_CSV_PATH = ARTIFACTS_DIR / "nasa" / "training_data.csv"
# Single multi-target LSTM artifact (Dense(5) output, predicts all 5 sensors).
# At /detect and /forecast lookup we create one LstmForecaster instance per
# sensor view sharing the same underlying Keras model + scaler.
LSTM_PATH = ARTIFACTS_DIR / "lstm"

# Weather sensors that get per-sensor models at startup. Must match the
# canonical sensor ids resolved by the BE's MqttSensorTopics.
WEATHER_SENSORS = ["temperature", "relativeHumidity", "rain", "radiation", "wind"]

# Statistical detectors fit fresh from the NASA CSV for every sensor.
LIVE_STATISTICAL_DETECTORS: list[tuple[str, type[AnomalyDetector]]] = [
    ("zscore", ZScoreDetector),
    ("seasonal_zscore", SeasonalZScoreDetector),
]

# Per-sensor forecasters loaded from `{model}_{sensor}.pkl`. Each one is
# both exposed via /forecast and wrapped as a ResidualDetector for /detect.
PER_SENSOR_FORECASTERS: list[tuple[str, type[Forecaster]]] = [
    ("arima", ArimaForecaster),
    ("sarima", SarimaForecaster),
]


def _load_forecaster(name: str, cls: type[Forecaster], path: Path) -> Forecaster | None:
    if not path.exists():
        logger.warning("%s artifact missing at %s -- skipping", name, path)
        return None
    try:
        fc = cls()
        fc.load(path)
        logger.info("Loaded forecaster %s (residual_std=%s)", name, fc.residual_std)
        return fc
    except Exception:
        logger.exception("Failed to load %s from %s", name, path)
        return None


def _fit_detector(name: str, cls: type[AnomalyDetector], series: pd.Series) -> AnomalyDetector | None:
    try:
        det = cls()
        det.fit(series)
        logger.info(
            "Fit detector %s on %d points (residual_std=%s)", name, len(series), det.residual_std
        )
        return det
    except Exception:
        logger.exception("Failed to fit %s", name)
        return None


def _register_sensor(sensor_id: str, nasa_df: pd.DataFrame | None) -> None:
    """Populate DETECTORS[sensor_id] and FORECASTERS[sensor_id] for the
    non-LSTM models. LSTM is loaded once in `_register_lstm_views()` since
    its artifact is shared across all sensor targets.
    """
    detectors: dict[str, AnomalyDetector] = {}
    forecasters: dict[str, Forecaster] = {}

    # zscore + seasonal_zscore from the NASA CSV column.
    if nasa_df is not None and sensor_id in nasa_df.columns:
        series = nasa_df[sensor_id].dropna()
        for det_name, det_cls in LIVE_STATISTICAL_DETECTORS:
            det = _fit_detector(f"{det_name}/{sensor_id}", det_cls, series)
            if det is not None:
                detectors[det_name] = det

    # ARIMA + SARIMA per-sensor artifacts → both /forecast and residual /detect.
    for fc_name, fc_cls in PER_SENSOR_FORECASTERS:
        path = ARTIFACTS_DIR / f"{fc_name}_{sensor_id}.pkl"
        fc = _load_forecaster(f"{fc_name}/{sensor_id}", fc_cls, path)
        if fc is not None:
            forecasters[fc_name] = fc
            detectors[f"{fc_name}_residual"] = ResidualDetector(fc, name=f"{fc_name}_residual")

    if detectors:
        DETECTORS[sensor_id] = detectors
    if forecasters:
        FORECASTERS[sensor_id] = forecasters


def _register_lstm_views() -> None:
    """Load the single multi-target LSTM artifact and create one view per
    sensor sharing the same underlying Keras model + scaler. Each view
    exposes the right `residual_std` for its target so ResidualDetector
    wrappers threshold correctly.
    """
    if not LSTM_PATH.exists():
        logger.warning("LSTM artifact missing at %s -- LSTM detector/forecaster skipped", LSTM_PATH)
        return

    # Load once with target=temperature as the seed view.
    try:
        master = LstmForecaster(target="temperature")
        master.load(LSTM_PATH)
        logger.info("Loaded multi-target LSTM from %s", LSTM_PATH)
    except Exception:
        logger.exception("Failed to load LSTM from %s -- skipping", LSTM_PATH)
        return

    available_targets = list(master._residual_std_by_target.keys())
    for sensor_id in WEATHER_SENSORS:
        if sensor_id not in available_targets:
            logger.warning(
                "LSTM artifact has no calibrated residual_std for %s "
                "(available targets: %s); skipping lstm_residual/%s",
                sensor_id, available_targets, sensor_id,
            )
            continue
        view = LstmForecaster(target=sensor_id)
        # Share the heavy state across views — only the target / target_idx /
        # residual_std differ.
        view._model = master._model
        view._scaler = master._scaler
        view.window = master.window
        view.epochs = master.epochs
        view.batch_size = master.batch_size
        view._residual_std_by_target = master._residual_std_by_target
        view.residual_std = master._residual_std_by_target[sensor_id]
        view.fitted = True

        FORECASTERS.setdefault(sensor_id, {})["lstm"] = view
        DETECTORS.setdefault(sensor_id, {})["lstm_residual"] = ResidualDetector(
            view, name="lstm_residual"
        )


@asynccontextmanager
async def lifespan(app: FastAPI):
    nasa_df: pd.DataFrame | None = None
    if NASA_CSV_PATH.exists():
        try:
            nasa_df = pd.read_csv(NASA_CSV_PATH, index_col="time", parse_dates=["time"])
        except Exception:
            logger.exception("Failed to load NASA CSV at %s -- statistical detectors will be skipped", NASA_CSV_PATH)
            nasa_df = None
    else:
        logger.warning(
            "NASA CSV missing at %s -- statistical detectors will not fit. "
            "Run `python -m scripts.fetch_nasa` first.",
            NASA_CSV_PATH,
        )

    for sensor_id in WEATHER_SENSORS:
        _register_sensor(sensor_id, nasa_df)

    # LSTM is multi-target — one artifact serves every sensor view.
    _register_lstm_views()

    logger.info(
        "ml-service starting; detectors=%s, forecasters=%s",
        {s: list(d.keys()) for s, d in DETECTORS.items()},
        {s: list(f.keys()) for s, f in FORECASTERS.items()},
    )
    yield
    logger.info("ml-service shutting down")


app = FastAPI(title="Cassava ML Service", version="0.5.0", lifespan=lifespan)
app.include_router(detect_router)
app.include_router(forecast_router)


@app.get("/health")
def health() -> dict:
    return {
        "status": "ok",
        "detectors": {s: list(d.keys()) for s, d in DETECTORS.items()},
        "forecasters": {s: list(f.keys()) for s, f in FORECASTERS.items()},
    }


@app.get("/models", response_model=list[ModelInfo])
def list_models() -> list[ModelInfo]:
    out: list[ModelInfo] = []
    for sensor_id, dets in DETECTORS.items():
        for name, det in dets.items():
            out.append(
                ModelInfo(
                    name=f"{name}/{sensor_id}",
                    role="detector",
                    loaded=True,
                    residual_std=det.residual_std,
                )
            )
    for sensor_id, fcs in FORECASTERS.items():
        for name, fc in fcs.items():
            out.append(
                ModelInfo(
                    name=f"{name}/{sensor_id}",
                    role="forecaster",
                    loaded=True,
                    residual_std=fc.residual_std,
                )
            )
    return out
