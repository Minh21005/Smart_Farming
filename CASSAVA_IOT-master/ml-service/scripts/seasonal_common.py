"""Shared helpers for the 4-season rolling-origin backtest.

The single-holdout eval (`evaluate_detection.py`, `evaluate_forecast_per_sensor.py`)
only tests the last month of the CSV — December = winter for Bắc Bộ. That proves
nothing about spring/summer/autumn.

This module defines a **rolling-origin, multi-season backtest**: one representative
month per season (advisor's picks, one per quarter), each evaluated against a model
trained ONLY on data preceding that month — so there is no future leakage and the
four windows give 4× the test data while spanning all four seasons.

    xuân  → March
    hạ    → June
    thu   → September
    đông  → December

Used by `evaluate_detection_seasonal.py` (KB1) and `evaluate_forecast_seasonal.py`
(KB2 + KB3).
"""
from __future__ import annotations

from dataclasses import dataclass

import pandas as pd

# (key, month, Vietnamese label) — ordered chronologically within a year so the
# backtest walks spring → winter.
SEASONS: list[tuple[str, int, str]] = [
    ("spring", 3, "xuân"),
    ("summer", 6, "hạ"),
    ("autumn", 9, "thu"),
    ("winter", 12, "đông"),
]


@dataclass
class SeasonWindow:
    key: str          # "spring" | "summer" | "autumn" | "winter"
    label: str        # Vietnamese season name
    month: int        # 3 | 6 | 9 | 12
    year: int
    start: pd.Timestamp   # first hour of the month (inclusive)
    end: pd.Timestamp     # exclusive upper bound (start of next month, clipped to data)

    def __str__(self) -> str:
        return f"{self.key} ({self.label}) {self.start:%Y-%m}"


def resolve_year(df: pd.DataFrame) -> int:
    """Most recent year for which ALL four season-months are present in the index.

    Defaults the backtest to the latest fully-covered year so callers don't have
    to know the CSV span. Override with an explicit `--year`.
    """
    years = sorted({int(ts.year) for ts in df.index})
    months_present = {(int(ts.year), int(ts.month)) for ts in df.index}
    for y in reversed(years):
        if all((y, m) in months_present for _, m, _ in SEASONS):
            return y
    raise SystemExit(
        "No year in the data has all four season-months (3, 6, 9, 12) present. "
        "Re-fetch a fuller NASA CSV or pass --year explicitly."
    )


def season_windows(df: pd.DataFrame, year: int) -> list[SeasonWindow]:
    """Build the four season test windows for `year`, clipped to the data span."""
    data_end = df.index.max()
    # Match the index timezone (NASA CSV is tz-aware) so comparisons don't raise
    # "Cannot compare tz-naive and tz-aware timestamps".
    tz = getattr(df.index, "tz", None)
    out: list[SeasonWindow] = []
    for key, month, label in SEASONS:
        start = pd.Timestamp(year=year, month=month, day=1, tz=tz)
        end = start + pd.DateOffset(months=1)  # exclusive
        if start > data_end:
            continue
        # Include the final available point (CSV may stop mid-month, e.g. Dec 30).
        end = min(end, data_end + pd.Timedelta(hours=1))
        out.append(SeasonWindow(key, label, month, year, start, end))
    if not out:
        raise SystemExit(f"No season windows fall within the data span for year {year}.")
    return out


def rolling_split(
    series: pd.Series, window: SeasonWindow, min_train: int = 50, min_test: int = 10
) -> tuple[pd.Series, pd.Series]:
    """Rolling-origin split for one season window: train = everything strictly
    before the window; test = exactly the window's month.

    The leakage assertion is the whole point of the rolling design — it guarantees
    the model never sees a point at or after the window it is judged on.
    """
    train = series[series.index < window.start]
    test = series[(series.index >= window.start) & (series.index < window.end)]
    if len(train) < min_train:
        raise ValueError(
            f"{window}: train has {len(train)} points (< {min_train}). "
            f"This window is too early in the data for rolling-origin."
        )
    if len(test) < min_test:
        raise ValueError(f"{window}: test has {len(test)} points (< {min_test}).")
    assert train.index.max() < test.index.min(), (
        f"LEAKAGE: train ends {train.index.max()} >= test starts {test.index.min()}"
    )
    return train, test
