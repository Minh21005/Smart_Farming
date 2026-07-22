"""Quick sanity check on Mongo sensor data — run before training to know if there's
enough history.

    python -m scripts.check_data
    python -m scripts.check_data --sensor temperature --days 30
"""
from __future__ import annotations

import argparse
from datetime import datetime, timedelta, timezone

import pandas as pd

from ml.config import GROUP_ID, SENSOR_ID
from ml.data import load_sensor_series


def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument("--group", default=GROUP_ID)
    ap.add_argument("--sensor", default=SENSOR_ID)
    ap.add_argument("--days", type=int, default=None, help="Look back N days (default: all)")
    args = ap.parse_args()

    start = None
    if args.days:
        start = datetime.now(timezone.utc) - timedelta(days=args.days)

    print(f"Loading {args.sensor} for group {args.group}...")
    raw = load_sensor_series(args.group, args.sensor, start=start, resample=None)
    hourly = load_sensor_series(args.group, args.sensor, start=start)

    if raw.empty:
        print("  No data found.")
        return

    span_days = (raw.index[-1] - raw.index[0]).total_seconds() / 86400
    median_dt = raw.index.to_series().diff().median()

    print()
    print(f"Raw rows:           {len(raw)}")
    print(f"Time range:         {raw.index[0]} → {raw.index[-1]}")
    print(f"Span:               {span_days:.1f} days")
    print(f"Median sample gap:  {median_dt}")
    print(f"Value range:        [{raw.min():.2f}, {raw.max():.2f}]   mean={raw.mean():.2f}")
    print()
    print(f"Hourly resampled:   {len(hourly)} buckets")
    missing_hours = hourly.isna().sum()
    print(f"Empty hours:        {missing_hours} ({100*missing_hours/len(hourly):.1f}%)")
    print()

    print("Training feasibility (rough thresholds):")
    print(f"  ARIMA  (≥48 hourly points required):   {'OK' if len(hourly) >= 48 else 'INSUFFICIENT'}")
    print(f"  SARIMA (≥72 hourly points, 3+ days):   {'OK' if len(hourly) >= 72 else 'INSUFFICIENT'}")
    print(f"  LSTM   (≥336 hourly points, 14+ days): {'OK' if len(hourly) >= 336 else 'INSUFFICIENT'}")
    print(f"  Seasonal Z (≥14 days for hour buckets): {'OK' if span_days >= 14 else 'INSUFFICIENT'}")


if __name__ == "__main__":
    main()
