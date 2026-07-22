"""Forecasters — predict the next h hourly values of a series.

Each implementation exposes the same `Forecaster` interface (fit/predict/
update/save/load) regardless of its internals (ARIMA, SARIMA, LSTM).

To use any of these as an anomaly detector, wrap with
`ml.detectors.ResidualDetector(forecaster)` — the |residual|/sigma > k rule
turns a forecaster into a Tier-4-style detector.
"""
from .arima import ArimaForecaster
from .lstm import LstmForecaster
from .sarima import SarimaForecaster

__all__ = ["ArimaForecaster", "SarimaForecaster", "LstmForecaster"]
