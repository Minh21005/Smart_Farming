"""Fetch NASA POWER hourly weather data and save as a single CSV for training.

    python -m scripts.fetch_nasa                                   # 2020-01-01 -> 2025-12-30
    python -m scripts.fetch_nasa --start 2018-01-01 --end 2024-12-30
    python -m scripts.fetch_nasa --output artifacts/my_data.csv

Run this once before `python -m scripts.train`. The training script reads
from artifacts/nasa/training_data.csv (or whatever --output you pass).

Default range is 2020-01-01 -> 2025-12-30. The end date stops short of
2025-12-31 because NASA POWER published corrupt hourly data from that date
onwards (observed 2026-05); bump the end date when NASA backfills clean data.

Per-year chunks are also cached individually under artifacts/nasa/ — re-runs
that overlap the same date range hit cache instead of re-fetching.
"""
from __future__ import annotations

import argparse
import sys
from datetime import datetime, timezone
from pathlib import Path

# Allow running as a file (`python scripts/fetch_nasa.py` or IDE Run button)
# in addition to module mode (`python -m scripts.fetch_nasa`). Module mode
# already gets ml-service/ on sys.path; file mode does not.
sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

from ml.config import ARTIFACTS_DIR  # noqa: E402
from ml.nasa_loader import DEFAULT_LAT, DEFAULT_LON, fetch_range  # noqa: E402


def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument("--start", default="2020-01-01", help="Start date YYYY-MM-DD (UTC)")
    ap.add_argument("--end", default="2025-12-30", help="End date YYYY-MM-DD (UTC)")
    ap.add_argument("--lat", type=float, default=DEFAULT_LAT)
    ap.add_argument("--lon", type=float, default=DEFAULT_LON)
    ap.add_argument(
        "--output",
        type=Path,
        default=ARTIFACTS_DIR / "nasa" / "training_data.csv",
        help="Where to write the consolidated CSV",
    )
    args = ap.parse_args()

    start = datetime.fromisoformat(args.start).replace(tzinfo=timezone.utc)
    end = datetime.fromisoformat(args.end).replace(tzinfo=timezone.utc)
    if end <= start:
        raise SystemExit(f"--end ({end:%Y-%m-%d}) must be after --start ({start:%Y-%m-%d})")

    print(
        f"Fetching NASA POWER from {start:%Y-%m-%d} to {end:%Y-%m-%d} "
        f"at ({args.lat}, {args.lon})..."
    )
    df = fetch_range(start=start, end=end, lat=args.lat, lon=args.lon)
    if df.empty:
        raise SystemExit("NASA fetch returned empty -- check network or API status")

    args.output.parent.mkdir(parents=True, exist_ok=True)
    df.to_csv(args.output)
    print(
        f"Saved {len(df)} hourly rows -> {args.output} "
        f"(span: {df.index[0]} .. {df.index[-1]})"
    )


if __name__ == "__main__":
    main()
