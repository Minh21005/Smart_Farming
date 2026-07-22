"""In-process registry of loaded detectors and forecasters.

Populated by `api.main.lifespan` at startup; consumed by the route modules.

Both registries are keyed by **sensor_id then model name** because each
sensor (temperature, relativeHumidity, rain, radiation, wind) needs its own
fitted model — μ/σ for temperature are nothing like those for rain, and an
ARIMA order tuned for temperature is not optimal for rain.

LSTM is multivariate around the temperature target and lives only under the
`temperature` key.
"""
from __future__ import annotations

from ml.base import AnomalyDetector, Forecaster

DETECTORS: dict[str, dict[str, AnomalyDetector]] = {}
FORECASTERS: dict[str, dict[str, Forecaster]] = {}
