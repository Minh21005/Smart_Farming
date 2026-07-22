"""Adapt a `Forecaster` to the `AnomalyDetector` interface via the residual rule.

This is the bridge between the two halves of the service: a forecaster
(ARIMA/SARIMA/LSTM) trained for next-hour prediction can be exposed as a
detector by thresholding |actual - forecast| / sigma > k. Lets us reuse
the same trained model for both the planning endpoint and a Tier-4-style
anomaly detector without duplicating the training pipeline.

The wrapper does not own the forecaster's lifecycle in a strict sense:
`fit` delegates to the wrapped forecaster, `score` calls `predict` then
`update`, and `save`/`load` round-trip the forecaster's own artifact.
"""
from __future__ import annotations

from pathlib import Path

import pandas as pd

from ..base import AnomalyDetector, Forecaster, ScoreResult


class ResidualDetector(AnomalyDetector):
    def __init__(self, forecaster: Forecaster, name: str | None = None) -> None:
        super().__init__()
        self.forecaster = forecaster
        self.name = name or f"{forecaster.name}_residual"
        self.fitted = forecaster.fitted
        self.residual_std = forecaster.residual_std

    def fit(self, series: pd.Series) -> None:
        self.forecaster.fit(series)
        self.fitted = self.forecaster.fitted
        self.residual_std = self.forecaster.residual_std

    def score(self, actual: float, time: pd.Timestamp, k: float = 3.0) -> ScoreResult:
        if not self.forecaster.fitted:
            return ScoreResult(actual, None, None, 0.0, False, self.name)

        try:
            forecast = self.forecaster.predict(pd.Timestamp(time), horizon=1).forecast[0]
        except Exception:
            return ScoreResult(actual, None, None, 0.0, False, self.name)

        residual = actual - forecast
        sigma = self.forecaster.residual_std or 1.0
        z = abs(residual) / sigma
        is_anom = z > k

        try:
            self.forecaster.update(actual, pd.Timestamp(time))
        except Exception:
            pass

        return ScoreResult(actual, forecast, residual, z, is_anom, self.name)

    def save(self, path: Path) -> None:
        self.forecaster.save(path)

    def load(self, path: Path) -> None:
        self.forecaster.load(path)
        self.fitted = self.forecaster.fitted
        self.residual_std = self.forecaster.residual_std
