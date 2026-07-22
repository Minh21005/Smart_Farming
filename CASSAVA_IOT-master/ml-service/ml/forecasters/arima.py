"""ARIMA(p,d,q) forecaster — univariate hourly series.

Online forecasting: parameters are estimated once in `fit()`; `update(value, time)`
appends the observation to a running history and each `predict()` re-applies the
fitted parameters (no re-MLE) to a trailing context window via `ARIMAResults.apply`.
This gives a true one-step-ahead rolling forecast. (Re-applying the whole history
each step is O(n) and crippling for long series, so the context is bounded — see
`_APPLY_CONTEXT`; a one-step forecast only needs recent context for the filter
state to converge.)

To turn this into an anomaly detector, wrap with
`ml.detectors.ResidualDetector(ArimaForecaster())`.
"""
from __future__ import annotations

import pickle
from pathlib import Path

import numpy as np
import pandas as pd
from statsmodels.tsa.arima.model import ARIMA

from ..base import ForecastResult, Forecaster

_APPLY_CONTEXT = 2160  # hours = 90 days of trailing context for the one-step filter


class ArimaForecaster(Forecaster):
    name = "arima"

    def __init__(
        self,
        order: tuple[int, int, int] = (2, 1, 2),
        interpolate_method: str = "linear",
        interpolate_limit: int | None = 3,
    ) -> None:
        super().__init__()
        self.order = order
        # Gap-filling applied to the input series before fit (pandas
        # Series.interpolate). `interpolate_limit` caps how many *consecutive*
        # NaNs are filled (None = unlimited); any NaNs left after that are dropped.
        # Defaults preserve the previous hardcoded behaviour (linear, limit=3).
        self.interpolate_method = interpolate_method
        self.interpolate_limit = interpolate_limit
        self._results = None  # statsmodels ARIMAResults (holds fitted params)
        self._history = None  # all observations seen so far (train + updates)

    def fit(self, series: pd.Series) -> None:
        clean = series.interpolate(
            method=self.interpolate_method, limit=self.interpolate_limit
        ).dropna()
        if len(clean) < 30:
            raise ValueError(f"ARIMA needs >= 30 points, got {len(clean)}")
        self._results = ARIMA(clean, order=self.order).fit()
        self._history = np.asarray(clean.values, dtype=float)
        self.fitted = True
        residuals = clean - self._results.fittedvalues
        self._calibrate_residual_std(residuals)

    def predict(self, time: pd.Timestamp, horizon: int = 1) -> ForecastResult:
        if self._results is None or self._history is None:
            raise RuntimeError("ArimaForecaster not fitted")
        # Re-apply fitted params to the recent context (no re-MLE) and forecast.
        res = self._results.apply(self._history[-_APPLY_CONTEXT:], refit=False)
        fc = np.asarray(res.forecast(steps=horizon), dtype=float)
        ts = pd.date_range(pd.Timestamp(time), periods=horizon, freq="h")
        return ForecastResult(
            forecast=[float(v) for v in fc],
            timestamps=list(ts),
            name=self.name,
            residual_std=self.residual_std,
        )

    def update(self, value: float, time: pd.Timestamp) -> None:
        if self._history is None:
            return
        self._history = np.append(self._history, float(value))

    def save(self, path: Path) -> None:
        with open(path, "wb") as f:
            pickle.dump(
                {
                    "order": self.order,
                    "results": self._results,
                    "history": self._history,
                    "residual_std": self.residual_std,
                    "interpolate_method": self.interpolate_method,
                    "interpolate_limit": self.interpolate_limit,
                },
                f,
            )

    def load(self, path: Path) -> None:
        with open(path, "rb") as f:
            d = pickle.load(f)
        self.order = d["order"]
        self._results = d["results"]
        self._history = d.get("history")
        self.residual_std = d["residual_std"]
        self.interpolate_method = d.get("interpolate_method", self.interpolate_method)
        self.interpolate_limit = d.get("interpolate_limit", self.interpolate_limit)
        self.fitted = True
