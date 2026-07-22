"""MongoDB sensor data loader.

Mirrors the aggregation logic of cassavaBE's `SensorValueService.getCombinedValues()`:
read raw sensor_value rows, sort by time, resample to hourly mean. The Java service
uses a Mongo `$dateTrunc` + `$group` pipeline; here we let pandas do the resampling
since downstream ML code already wants a pandas Series.
"""
from __future__ import annotations

from datetime import datetime
from typing import Optional

import pandas as pd
from pymongo import ASCENDING, MongoClient

from .config import (
    GROUP_ID,
    MONGO_DB,
    MONGO_URI,
    RESAMPLE_FREQ,
    SENSOR_COLLECTION,
    SENSOR_ID,
)

_client: MongoClient | None = None


def _get_client() -> MongoClient:
    global _client
    if _client is None:
        _client = MongoClient(MONGO_URI, serverSelectionTimeoutMS=5000)
    return _client


def load_sensor_series(
    group_id: str = GROUP_ID,
    sensor_id: str = SENSOR_ID,
    start: Optional[datetime] = None,
    end: Optional[datetime] = None,
    resample: str | None = RESAMPLE_FREQ,
) -> pd.Series:
    """Load sensor values from Mongo as a time-indexed pandas Series.

    Returns hourly-mean resampled series by default. Pass `resample=None` for raw.
    """
    coll = _get_client()[MONGO_DB][SENSOR_COLLECTION]
    query: dict = {"groupId": group_id, "sensorId": sensor_id}
    if start or end:
        time_q: dict = {}
        if start:
            time_q["$gte"] = start
        if end:
            time_q["$lte"] = end
        query["time"] = time_q

    cursor = coll.find(query, {"time": 1, "value": 1, "_id": 0}).sort("time", ASCENDING)
    rows = list(cursor)
    if not rows:
        return pd.Series(dtype=float, name=sensor_id)

    df = pd.DataFrame(rows)
    df["time"] = pd.to_datetime(df["time"], utc=True)
    df = df.set_index("time").sort_index()

    series: pd.Series = df["value"]
    if resample:
        series = series.resample(resample).mean()
    series.name = sensor_id
    return series


def train_test_split_chrono(
    series: pd.Series, test_frac: float = 0.2
) -> tuple[pd.Series, pd.Series]:
    """Chronological split (no shuffle) — train = older, test = newer."""
    n = len(series)
    if n < 10:
        raise ValueError(f"Need at least 10 points to split, got {n}")
    split = int(n * (1 - test_frac))
    return series.iloc[:split], series.iloc[split:]
