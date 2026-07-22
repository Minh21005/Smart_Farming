"""Abstract bases for the two roles in this service.

`AnomalyDetector` answers "is this point anomalous?" — the streaming guard.
`Forecaster` answers "what comes next?" — the planning input.

The split is intentional: the same model rarely wins at both. Robust
detectors (median/MAD, hour-of-day baselines) ignore single bad points,
which is exactly wrong for forecasting; smooth multivariate forecasters
(LSTM) chase signal, which makes them noisy at flagging spikes.

A `Forecaster` can still be exposed as an `AnomalyDetector` via the
`ResidualDetector` wrapper in `ml.detectors.residual` — composition over
inheritance.
"""
from __future__ import annotations

from abc import ABC, abstractmethod
from dataclasses import dataclass
from pathlib import Path
from typing import Optional

import pandas as pd


# ---- detection ----------------------------------------------------------


@dataclass
class ScoreResult:
    actual: float
    predicted: Optional[float]   # None for non-forecasting detectors (e.g. raw z-score)
    residual: Optional[float]
    score: float                  # |z|-like score; > k => anomaly
    is_anomaly: bool
    method: str


class AnomalyDetector(ABC):
    """Streaming anomaly judge."""

    name: str = "base"

    def __init__(self) -> None:
        self.residual_std: float | None = None
        self.fitted: bool = False

    @abstractmethod
    def fit(self, series: pd.Series) -> None: ...

    @abstractmethod
    def score(self, actual: float, time: pd.Timestamp, k: float = 3.0) -> ScoreResult: ...

    @abstractmethod
    def save(self, path: Path) -> None: ...

    @abstractmethod
    def load(self, path: Path) -> None: ...

    def _calibrate_residual_std(self, residuals: pd.Series) -> None:
        clean = residuals.dropna()
        if len(clean) < 2:
            raise ValueError("Need at least 2 residuals to calibrate sigma")
        self.residual_std = float(clean.std())


# ---- forecasting --------------------------------------------------------


@dataclass
class ForecastResult:
    """Output of a `Forecaster.predict(time, horizon)` call."""
    forecast: list[float]                  # length = horizon
    timestamps: list[pd.Timestamp]         # length = horizon (one step apart, hourly grid)
    name: str
    residual_std: Optional[float] = None   # in-sample sigma, useful for confidence bands


class Forecaster(ABC):
    """Predicts future values of a hourly series.

    `fit(data)` accepts a Series (univariate) or DataFrame (multivariate),
    depending on the implementation. `predict(time, horizon)` returns the next
    `horizon` hourly values starting at `time`. `update(value, time)` extends
    the model state online without a full refit (default: no-op).

    `residual_std` is the in-sample residual sigma calibrated at fit time —
    used by `ResidualDetector` to threshold |actual - forecast| / sigma.
    """

    name: str = "base"

    def __init__(self) -> None:
        self.residual_std: float | None = None
        self.fitted: bool = False

    @abstractmethod
    def fit(self, data) -> None: ...

    @abstractmethod
    def predict(self, time: pd.Timestamp, horizon: int = 1) -> ForecastResult: ...

    def update(self, value: float, time: pd.Timestamp) -> None:
        """Extend model state with one new observation. Default: no-op."""
        return None

    @abstractmethod
    def save(self, path: Path) -> None: ...

    @abstractmethod
    def load(self, path: Path) -> None: ...

    def _calibrate_residual_std(self, residuals: pd.Series) -> None:
        clean = residuals.dropna()
        if len(clean) < 2:
            raise ValueError("Need at least 2 residuals to calibrate sigma")
        self.residual_std = float(clean.std())
