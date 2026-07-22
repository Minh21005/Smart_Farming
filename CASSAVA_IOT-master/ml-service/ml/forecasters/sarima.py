"""SARIMA(p,d,q)(P,D,Q,s) forecaster — built on `statsforecast`.

Replaces the older `statsmodels.SARIMAX` implementation, which allocates a
(state_dim^2, T) Kalman covariance array during every fit and OOMs on
multi-year hourly data with seasonal=24. statsforecast's Cython ARIMA fits
the same problem in tens of MB.

Two modes:
  - `auto=False` (default): fixed `order` and `seasonal_order` -- fits via
    `statsforecast.models.ARIMA`.
  - `auto=True`: stepwise AIC search via `statsforecast.models.AutoARIMA`.
    The chosen `(p,d,q)(P,D,Q,m)` is recorded back onto `self.order` /
    `self.seasonal_order` after fit.

Online forecasting: `update(value, time)` extends model state via the
underlying model's `forward()` without refitting.
"""
from __future__ import annotations

import pickle
from pathlib import Path

import numpy as np
import pandas as pd

from ..base import ForecastResult, Forecaster

# forward() re-filters the whole history each call (O(n) per step → O(n·m) per walk).
# A one-step forecast only needs enough trailing context for the filter state to
# converge; ~90 days ≫ the 24h seasonal period and the small AR/MA orders, so the
# forecast is numerically identical to using all history but the walk is ~20× faster.
# (Parameters are still estimated on ALL training data in fit().)
_FORWARD_CONTEXT = 2160  # hours = 90 days


class SarimaForecaster(Forecaster):
    name = "sarima"

    def __init__(
        self,
        order: tuple[int, int, int] = (1, 1, 1),
        seasonal_order: tuple[int, int, int, int] = (1, 1, 1, 24),
        auto: bool = False,
        interpolate_method: str = "linear",
        interpolate_limit: int | None = 3,
    ) -> None:
        super().__init__()
        self.order = order
        self.seasonal_order = seasonal_order
        self.auto = auto
        # Gap-filling applied to the input series before fit (pandas
        # Series.interpolate). `interpolate_limit` caps how many *consecutive*
        # NaNs are filled (None = unlimited); any NaNs left after that are dropped.
        # Defaults preserve the previous hardcoded behaviour (linear, limit=3).
        self.interpolate_method = interpolate_method
        self.interpolate_limit = interpolate_limit
        self._model = None  # statsforecast ARIMA or AutoARIMA after fit
        self._history = None  # all observations seen so far (train + updates)

    def fit(self, series: pd.Series) -> None:
        from statsforecast.models import ARIMA, AutoARIMA  # noqa: PLC0415

        clean = series.interpolate(
            method=self.interpolate_method, limit=self.interpolate_limit
        ).dropna()
        s = self.seasonal_order[3]
        if len(clean) < 3 * s:
            raise ValueError(f"SARIMA(s={s}) needs >= {3 * s} points, got {len(clean)}")
        y = clean.values.astype(np.float64)

        if self.auto:
            print(f"  [SARIMA] AutoARIMA stepwise search (season_length={s})...")
            model = AutoARIMA(
                season_length=s,
                max_p=2, max_q=2,
                max_P=1, max_Q=1,
                max_d=1, max_D=1,
                stepwise=True,
                trace=True,
                approximation=False,
            )
            model.fit(y)
            arma = model.model_["arma"]
            p, q, P, Q, m, d, D = (int(x) for x in arma)
            self.order = (p, d, q)
            self.seasonal_order = (P, D, Q, m)
            print(f"  [SARIMA] Auto-selected order: {self.order}{self.seasonal_order}")
        else:
            p, d, q = self.order
            P, D, Q, m = self.seasonal_order
            model = ARIMA(
                order=(p, d, q),
                seasonal_order=(P, D, Q),
                season_length=m,
            )
            model.fit(y)

        self._model = model
        self._history = y  # keep history; predict() filters this with fixed params
        self.fitted = True

        residuals = np.asarray(model.model_["residuals"])
        residuals = residuals[~np.isnan(residuals)]
        self._calibrate_residual_std(pd.Series(residuals))

    def predict(self, time: pd.Timestamp, horizon: int = 1) -> ForecastResult:
        if self._model is None:
            raise RuntimeError("SarimaForecaster not fitted")
        # forward() applies the fitted parameters to the accumulated history and
        # forecasts `horizon` steps from its end — so successive predict()/update()
        # calls give a true one-step-ahead walk. (Plain predict() always forecasts
        # from the training end, ignoring updates → a flat forecast.)
        ctx = self._history[-_FORWARD_CONTEXT:]
        out = self._model.forward(y=ctx, h=horizon)
        values = [float(v) for v in out["mean"]]
        ts = pd.date_range(pd.Timestamp(time), periods=horizon, freq="h")
        return ForecastResult(
            forecast=values,
            timestamps=list(ts),
            name=self.name,
            residual_std=self.residual_std,
        )

    def update(self, value: float, time: pd.Timestamp) -> None:
        if self._model is None or self._history is None:
            return
        self._history = np.append(self._history, np.float64(value))

    def save(self, path: Path) -> None:
        with open(path, "wb") as f:
            pickle.dump(
                {
                    "order": self.order,
                    "seasonal_order": self.seasonal_order,
                    "model": self._model,
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
        self.seasonal_order = d["seasonal_order"]
        self._model = d["model"]
        self._history = d.get("history")
        self.residual_std = d["residual_std"]
        self.interpolate_method = d.get("interpolate_method", self.interpolate_method)
        self.interpolate_limit = d.get("interpolate_limit", self.interpolate_limit)
        self.fitted = True
