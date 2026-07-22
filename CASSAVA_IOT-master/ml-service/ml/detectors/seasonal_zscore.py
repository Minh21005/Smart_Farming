"""Seasonal Z-score detector — compares a value against the historical
distribution at the *same hour-of-day*.

Idea: 28°C might be normal at 14:00 but anomalous at 03:00. A standard Z-score
would average across all hours and flag neither. Bucketing by hour-of-day lets
us catch this kind of contextual anomaly.

Buckets are keyed by `time.hour` (UTC, since Mongo stores UTC and pandas
preserves it). For Vietnam (UTC+7) this means hour=0 ⇒ 07:00 local — that's
fine, the daily pattern still exists, just shifted.

Each bucket uses the *modified* Z-score (median + MAD), the same robust statistic
as `ZScoreDetector` (§2.2.2) — only the reference set is the matching hour-of-day
bucket instead of a sliding window. Median/MAD (not mean/std) keeps the per-bucket
threshold from being dragged by a few spikes.

    z_mod = 0.6745 * (x - median_bucket) / MAD_bucket

Need ≥ ~14 days of data so each bucket has enough samples for a stable
median/MAD estimate.
"""
from __future__ import annotations

import pickle
from pathlib import Path

import pandas as pd

from ..base import AnomalyDetector, ScoreResult

_MIN_PER_BUCKET = 10
_MAD_CONSISTENCY = 0.6745  # Iglewicz & Hoaglin — MAD ≈ σ under normality


class SeasonalZScoreDetector(AnomalyDetector):
    name = "seasonal_zscore"

    def __init__(self) -> None:
        super().__init__()
        self._bucket_stats: dict[int, tuple[float, float]] = {}  # hour → (median, MAD)

    def fit(self, series: pd.Series) -> None:
        clean = series.dropna()
        if len(clean) < _MIN_PER_BUCKET * 24:
            raise ValueError(
                f"Need ≥ {_MIN_PER_BUCKET * 24} hourly points (≥ {_MIN_PER_BUCKET} per "
                f"hour bucket), got {len(clean)}"
            )

        df = pd.DataFrame({"value": clean.values, "hour": clean.index.hour})
        residuals = []
        for hour, group in df.groupby("hour"):
            if len(group) >= _MIN_PER_BUCKET:
                med = float(group["value"].median())
                mad = float((group["value"] - med).abs().median())
                self._bucket_stats[int(hour)] = (med, mad)
                residuals.extend((group["value"] - med).tolist())

        if not self._bucket_stats:
            raise ValueError("No bucket has enough samples to estimate stats")

        self.fitted = True
        self._calibrate_residual_std(pd.Series(residuals))

    def score(self, actual: float, time: pd.Timestamp, k: float = 3.0) -> ScoreResult:
        ts = pd.Timestamp(time)
        hour = int(ts.hour)
        if hour not in self._bucket_stats:
            return ScoreResult(actual, None, None, 0.0, False, self.name)

        med, mad = self._bucket_stats[hour]
        if mad < 1e-9:
            return ScoreResult(actual, med, actual - med, 0.0, False, self.name)

        z = _MAD_CONSISTENCY * (actual - med) / mad
        is_anom = abs(z) > k
        return ScoreResult(actual, med, actual - med, abs(z), is_anom, self.name)

    def save(self, path: Path) -> None:
        with open(path, "wb") as f:
            pickle.dump(
                {"bucket_stats": self._bucket_stats, "residual_std": self.residual_std}, f
            )

    def load(self, path: Path) -> None:
        with open(path, "rb") as f:
            d = pickle.load(f)
        self._bucket_stats = d["bucket_stats"]
        self.residual_std = d["residual_std"]
        self.fitted = True
