"""Fetch NASA POWER hourly weather data for LSTM training. Caches CSVs locally
under artifacts/nasa/ so subsequent runs don't re-fetch.

NASA POWER API:  https://power.larc.nasa.gov/docs/services/api/
Hourly product:  /api/temporal/hourly/point  (community=AG agroclimatology)

Coordinates default to (21.0075, 105.5416) — Hà Nội — matching what the
cassavaBE simulation hardcodes in `Field.java#hourlyET()`.
"""
from __future__ import annotations

from datetime import datetime, timedelta, timezone
from pathlib import Path

import numpy as np
import pandas as pd
import requests

from .config import ARTIFACTS_DIR

NASA_BASE_URL = "https://power.larc.nasa.gov/api/temporal/hourly/point"

# NASA variable → our schema name (matches sensorId in Mongo sensor_value)
NASA_PARAM_MAP: dict[str, str] = {
    "T2M": "temperature",            # °C
    "RH2M": "relativeHumidity",      # %
    "WS2M": "wind",                  # m/s
    "PRECTOTCORR": "rain",           # mm/h
    "ALLSKY_SFC_SW_DWN": "radiation",  # MJ/m²/h (matches our canonical unit)
}

DEFAULT_LAT = 21.0075   # Hà Nội — matches Field.java hourlyET() lat
DEFAULT_LON = 105.5416


def fetch_nasa_power(
    start: datetime,
    end: datetime,
    lat: float = DEFAULT_LAT,
    lon: float = DEFAULT_LON,
    cache_dir: Path = ARTIFACTS_DIR / "nasa",
) -> pd.DataFrame:
    """Fetch NASA POWER hourly data for [start, end]. Returns a DataFrame indexed
    by UTC datetime with columns matching NASA_PARAM_MAP values.

    Caches the response as CSV under cache_dir; re-uses on subsequent calls.
    """
    cache_dir.mkdir(parents=True, exist_ok=True)
    fname = f"{start:%Y%m%d}_{end:%Y%m%d}_{lat:.4f}_{lon:.4f}.csv"
    cache_file = cache_dir / fname

    if cache_file.exists():
        return _load_cached(cache_file)

    params = {
        "parameters": ",".join(NASA_PARAM_MAP.keys()),
        "community": "AG",
        "longitude": lon,
        "latitude": lat,
        "start": start.strftime("%Y%m%d"),
        "end": end.strftime("%Y%m%d"),
        "format": "JSON",
        "time-standard": "UTC",
    }

    print(f"Fetching NASA POWER {start:%Y-%m-%d} -> {end:%Y-%m-%d} for ({lat}, {lon})...")
    resp = requests.get(NASA_BASE_URL, params=params, timeout=180)
    resp.raise_for_status()
    df = _parse_response(resp.json())
    df.to_csv(cache_file)
    return df


def _parse_response(raw: dict) -> pd.DataFrame:
    """Parse NASA POWER hourly response into a tidy DataFrame.

    Response shape: properties.parameter.{NASA_VAR} → {YYYYMMDDHH: value, ...}.
    NASA encodes missing values as -999.
    """
    params = raw["properties"]["parameter"]
    cols: dict[str, pd.Series] = {}
    for nasa_var, our_name in NASA_PARAM_MAP.items():
        series = params.get(nasa_var, {})
        cols[our_name] = pd.Series(
            {pd.to_datetime(k, format="%Y%m%d%H", utc=True): v for k, v in series.items()}
        )
    df = pd.DataFrame(cols).sort_index()
    df = df.replace(-999.0, np.nan).astype(float)
    df.index.name = "time"
    return df


def _load_cached(path: Path) -> pd.DataFrame:
    df = pd.read_csv(path, index_col="time", parse_dates=["time"])
    return df


def fetch_range(
    start: datetime,
    end: datetime,
    **kwargs,
) -> pd.DataFrame:
    """Fetch NASA POWER hourly data over [start, end], chunking into 1-year
    requests because NASA POWER caps hourly range per call. Each per-year
    chunk is cached independently (see `fetch_nasa_power`).
    """
    chunks = []
    cur = start
    while cur < end:
        chunk_end = min(cur + timedelta(days=365), end)
        chunks.append(fetch_nasa_power(cur, chunk_end, **kwargs))
        cur = chunk_end + timedelta(days=1)
    return pd.concat(chunks).sort_index()


def fetch_years(
    years: int = 3,
    end: datetime | None = None,
    **kwargs,
) -> pd.DataFrame:
    """Fetch the last `years` years of NASA data ending at `end` (UTC midnight).

    Convenience wrapper around `fetch_range`. If `end` is None, defaults to
    (now - 60 days) since NASA hourly has a ~60-day finalization lag. Pass an
    explicit `end` when recent NASA data is known-bad (NASA occasionally
    publishes corrupt rows and backfills later).
    """
    if end is None:
        end = datetime.now(timezone.utc).replace(hour=0, minute=0, second=0, microsecond=0)
        end = end - timedelta(days=60)
    start = end - timedelta(days=365 * years)
    return fetch_range(start=start, end=end, **kwargs)
