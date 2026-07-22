"""Modified Z-score detector with sliding window.

Uses MAD (median absolute deviation) instead of std → robust to outliers in
the window. The Iglewicz & Hoaglin formula:

    z_mod = 0.6745 * (x - median) / MAD

where MAD = median(|x_i - median|). Threshold ≈ 3.5 for "anomaly" under their
original recommendation; we use the API-supplied `k` (default 3.0) for fair
comparison across detectors.

State: keeps a rolling buffer of the most recent `window` observations. Each
call to `score()` uses the buffer as the reference, then appends the new
observation. So a value is always scored against its **past** context only.
"""
from __future__ import annotations

import pickle
from collections import deque
from pathlib import Path

import numpy as np
import pandas as pd

from ..base import AnomalyDetector, ScoreResult

_MAD_CONSISTENCY = 0.6745  # Iglewicz & Hoaglin


class ZScoreDetector(AnomalyDetector):
    name = "zscore"

    def __init__(self, window: int = 60):
        super().__init__()
        self.window = window
        self._buffer: deque[float] = deque(maxlen=window)

    def fit(self, series: pd.Series) -> None:
        clean = series.dropna()
        if len(clean) < 10:
            raise ValueError(f"Need at least 10 points, got {len(clean)}")
        self._buffer.clear()
        for v in clean.tail(self.window).values:
            self._buffer.append(float(v))
        self.fitted = True

        med = float(clean.median())
        residuals = clean - med
        self._calibrate_residual_std(residuals)

    def score(self, actual: float, time: pd.Timestamp, k: float = 3.0) -> ScoreResult:
        if len(self._buffer) < 10:
            # Not enough context yet — abstain (return non-anomaly with 0 score)
            self._buffer.append(actual)
            return ScoreResult(actual, None, None, 0.0, False, self.name)

        arr = np.fromiter(self._buffer, dtype=float)
        med = float(np.median(arr))
        mad = float(np.median(np.abs(arr - med)))

        if mad < 1e-9:
            # Constant window — degenerate; can't form a z-score
            self._buffer.append(actual)
            return ScoreResult(actual, med, actual - med, 0.0, False, self.name)

        z = _MAD_CONSISTENCY * (actual - med) / mad
        is_anom = abs(z) > k

        # Slide window AFTER scoring so the score uses past context only.
        self._buffer.append(actual)

        return ScoreResult(actual, med, actual - med, abs(z), is_anom, self.name)

    def save(self, path: Path) -> None:
        with open(path, "wb") as f:
            pickle.dump(
                {
                    "window": self.window,
                    "buffer": list(self._buffer),
                    "residual_std": self.residual_std,
                },
                f,
            )

    def load(self, path: Path) -> None:
        with open(path, "rb") as f:
            d = pickle.load(f)
        self.window = d["window"]
        self._buffer = deque(d["buffer"], maxlen=self.window)
        self.residual_std = d["residual_std"]
        self.fitted = True
