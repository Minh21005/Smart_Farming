"""KB1 — 4-season rolling-origin detection backtest.

The seasonal counterpart of `evaluate_detection.py`. Instead of one winter-only
holdout, this injects anomalies into ONE representative month per season (Mar/Jun/
Sep/Dec) and, for each, fits every detector on all data strictly before that month
(rolling origin → no leakage). It then reports per-season F1 plus a cross-season
mean ± std so a reader can see whether each detector generalises across the year.

    python -m scripts.evaluate_detection_seasonal                          # k=3, default detectors, temperature
    python -m scripts.evaluate_detection_seasonal --sensor all --methods all --sweep-k 1.0 10.0 0.5
    python -m scripts.evaluate_detection_seasonal --quick                   # one season smoke test
    python -m scripts.evaluate_detection_seasonal --year 2024               # backtest a different year

Reuses the scoring primitives of `evaluate_detection.py` (`inject_anomalies`,
`metrics_at_k`, `_build_detector`, `DetectorRun`) so the per-point scoring is
identical to the single-holdout report. The one deviation: `lstm_residual` fits
the multi-target LSTM ONCE per window and shares it across all five sensors
(4 fits total, not 20) — mirroring `evaluate_forecast_per_sensor._build_lstm_views`.
"""
from __future__ import annotations

import argparse
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

import numpy as np  # noqa: E402
import pandas as pd  # noqa: E402

from ml.detectors import ResidualDetector  # noqa: E402
from scripts.evaluate_detection import (  # noqa: E402
    ALL_METHODS,
    DEFAULT_DATA_PATH,
    WEATHER_SENSORS,
    DetectorRun,
    Metrics,
    _build_detector,
    inject_anomalies,
    metrics_at_k,
)
from scripts.evaluate_forecast_per_sensor import _build_lstm_views  # noqa: E402
from scripts.seasonal_common import resolve_year, rolling_split, season_windows  # noqa: E402

# How much real history to hand the LSTM as look-back context before each window.
# Must exceed the 48h window + interpolation slack; keeping it small keeps the
# per-point context slice fast.
LSTM_LOOKBACK = pd.Timedelta(hours=120)


def _walk_scores(det, test_perturbed: pd.Series, test_labels: pd.Series) -> DetectorRun:
    """Score every test point with an already-fitted detector (k irrelevant — we
    keep raw scores so any k can be swept afterwards). This is the walk loop of
    `evaluate_detection.score_detector`, lifted out so a pre-fitted LSTM view can
    be reused across sensors instead of refit per sensor."""
    n = len(test_perturbed)
    scores = np.full(n, np.nan)
    for i, (ts, val) in enumerate(test_perturbed.items()):
        if pd.isna(val):
            continue
        scores[i] = float(det.score(float(val), pd.Timestamp(ts), k=999.0).score)
    return DetectorRun(
        name=det.name,
        scores=scores,
        labels=test_labels.fillna(False).to_numpy(),
    )


def _perturbed_context(df: pd.DataFrame, window, sensor: str, perturbed: pd.Series) -> pd.DataFrame:
    """Look-back frame for the LSTM: real history just before the window plus the
    window itself, with only `sensor`'s column replaced by its perturbed values
    (other features stay clean). Mirrors `multivariate_test` in evaluate_detection."""
    ctx = df.loc[window.start - LSTM_LOOKBACK : window.end].copy()
    ctx.loc[perturbed.index, sensor] = perturbed.values
    return ctx


def evaluate(
    df: pd.DataFrame,
    windows,
    sensors: list[str],
    methods: list[str],
    patterns: list[str],
    k_values: list[float],
    max_points: int | None,
) -> list[dict]:
    """Return one row dict per (season, sensor, detector, k)."""
    rows: list[dict] = []
    use_lstm = "lstm_residual" in methods

    for s_idx, w in enumerate(windows):
        print(f"\n{'#' * 70}\n# Season window: {w}  [train < {w.start:%Y-%m-%d}]\n{'#' * 70}")

        # One LSTM fit per window, shared across sensors.
        lstm_views = {}
        if use_lstm:
            mv_train = df.loc[df.index < w.start]
            mv_full = df.loc[w.start - LSTM_LOOKBACK : w.end]
            lstm_views = _build_lstm_views(mv_train, mv_full)

        for sensor in sensors:
            if sensor not in df.columns:
                print(f"  [{sensor}] not in data — skipping")
                continue
            series = df[sensor].dropna()
            try:
                train, test = rolling_split(series, w)
            except (ValueError, AssertionError) as e:
                print(f"  [{sensor}] split failed: {e}")
                continue
            if max_points is not None:
                test = test.iloc[:max_points]

            # Seed varies per season so the four windows get independent anomaly
            # positions; patterns/rates are identical so seasons stay comparable.
            perturbed, labels = inject_anomalies(test, patterns, seed=42 + s_idx)
            n_anom = int(labels.sum())
            print(
                f"  [{sensor}] train={len(train)} test={len(test)} "
                f"injected={n_anom} ({n_anom / len(test):.1%}) [{'+'.join(patterns)}]"
            )

            for name in methods:
                try:
                    if name == "lstm_residual":
                        view = lstm_views[sensor]
                        view.set_offline_context(_perturbed_context(df, w, sensor, perturbed))
                        det = ResidualDetector(view, name="lstm_residual")
                    else:
                        det = _build_detector(name, sensor=sensor)
                        det.fit(train)
                    run = _walk_scores(det, perturbed, labels)
                except Exception as e:  # noqa: BLE001
                    print(f"    {name} failed: {e}")
                    continue

                for k in k_values:
                    m = metrics_at_k(run, k)
                    rows.append({
                        "season": w.key, "label": w.label, "month": f"{w.start:%Y-%m}",
                        "sensor": sensor, "detector": name, "k": float(k),
                        "inject": "+".join(patterns), "n_injected": n_anom, "n_test": len(test),
                        "precision": m.precision, "recall": m.recall, "f1": m.f1,
                    })
    return rows


# ---- reporting -------------------------------------------------------------

def _best_per_group(rows: list[dict], keys: tuple[str, ...]) -> dict[tuple, dict]:
    """Best-F1 row per group defined by `keys`."""
    best: dict[tuple, dict] = {}
    for r in rows:
        gk = tuple(r[k] for k in keys)
        if gk not in best or r["f1"] > best[gk]["f1"]:
            best[gk] = r
    return best


def _print_per_season(rows: list[dict], sensors: list[str], methods: list[str], windows) -> None:
    best = _best_per_group(rows, ("season", "sensor", "detector"))
    for w in windows:
        print(f"\n=== Per-season best F1 — {w.key} ({w.label}) {w.start:%Y-%m} ===")
        print(f"{'Sensor':<18}{'Detector':<20}{'k':>6}{'P':>9}{'R':>9}{'F1':>9}")
        print("-" * 71)
        for sensor in sensors:
            for det in methods:
                r = best.get((w.key, sensor, det))
                if r:
                    print(f"{sensor:<18}{det:<20}{r['k']:>6.2f}"
                          f"{r['precision']:>9.3f}{r['recall']:>9.3f}{r['f1']:>9.3f}")


def _print_cross_season(rows: list[dict], sensors: list[str], methods: list[str], n_windows: int) -> None:
    """Headline: for each (sensor, detector) pick the single k that maximises MEAN
    F1 across seasons (one year-round k = production-realistic, less test leakage
    than per-season best-k), then report mean ± std and the per-season spread."""
    df = pd.DataFrame(rows)
    print(f"\n{'=' * 78}\nCROSS-SEASON mean F1 at a shared k (k chosen to maximise mean across seasons)\n{'=' * 78}")
    print(f"{'Sensor':<18}{'Detector':<20}{'shared k':>9}{'mean F1':>9}{'± std':>8}{'min':>7}{'max':>7}{'seasons':>9}")
    print("-" * 87)
    for sensor in sensors:
        for det in methods:
            sub = df[(df["sensor"] == sensor) & (df["detector"] == det)]
            if sub.empty:
                continue
            # mean F1 over seasons for each k; pick best k.
            by_k = sub.groupby("k")["f1"].agg(["mean", "std", "min", "max", "count"])
            best_k = by_k["mean"].idxmax()
            r = by_k.loc[best_k]
            std = 0.0 if pd.isna(r["std"]) else r["std"]
            print(f"{sensor:<18}{det:<20}{best_k:>9.2f}{r['mean']:>9.3f}{std:>8.3f}"
                  f"{r['min']:>7.3f}{r['max']:>7.3f}{int(r['count']):>6}/{n_windows}")

    # Winner per sensor by mean F1.
    print(f"\n{'=' * 50}\nWinner per sensor (highest cross-season mean F1)\n{'=' * 50}")
    print(f"{'Sensor':<18}{'Winner':<20}{'mean F1':>9}{'k':>7}")
    print("-" * 54)
    for sensor in sensors:
        sub = df[df["sensor"] == sensor]
        if sub.empty:
            continue
        by = sub.groupby(["detector", "k"])["f1"].mean().reset_index()
        top = by.loc[by["f1"].idxmax()]
        print(f"{sensor:<18}{top['detector']:<20}{top['f1']:>9.3f}{top['k']:>7.2f}")


def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument("--methods", default="zscore,seasonal_zscore",
                    help=f"Comma-separated detectors or 'all'. Available: {', '.join(ALL_METHODS)}")
    ap.add_argument("--sensor", default="temperature",
                    help=f"Sensor or 'all'. Available: {', '.join(WEATHER_SENSORS)}")
    ap.add_argument("--inject", default="spike,outlier,drift")
    ap.add_argument("--k", type=float, default=3.0)
    ap.add_argument("--sweep-k", nargs=3, type=float, metavar=("MIN", "MAX", "STEP"), default=None)
    ap.add_argument("--year", type=int, default=None, help="Backtest year (default: latest fully-covered).")
    ap.add_argument("--data", type=Path, default=DEFAULT_DATA_PATH)
    ap.add_argument("--out", type=Path, default=DEFAULT_DATA_PATH.parent.parent / "seasonal_detection.csv")
    ap.add_argument("--max-points", type=int, default=None, help="Cap walk length per window (smoke test).")
    ap.add_argument("--quick", action="store_true", help="One season + max-points cap for a fast smoke test.")
    args = ap.parse_args()

    if not args.data.exists():
        raise SystemExit(f"Data not found at {args.data}. Run `python -m scripts.fetch_nasa` first.")
    df = pd.read_csv(args.data, index_col="time", parse_dates=["time"])

    methods = ALL_METHODS if args.methods == "all" else [m.strip() for m in args.methods.split(",")]
    sensors = WEATHER_SENSORS if args.sensor == "all" else [args.sensor]
    patterns = [p.strip() for p in args.inject.split(",") if p.strip()]
    if args.sweep_k:
        kmin, kmax, kstep = args.sweep_k
        if kstep <= 0 or kmax < kmin:
            raise SystemExit(f"Invalid sweep range [{kmin}, {kmax}] step={kstep}")
        k_values = [round(float(k), 4) for k in np.arange(kmin, kmax + kstep / 2, kstep)]
    else:
        k_values = [args.k]

    year = args.year or resolve_year(df)
    windows = season_windows(df, year)
    max_points = args.max_points
    if args.quick:
        windows = windows[:1]
        max_points = max_points or 300

    print(f"Data: {df.index[0]} .. {df.index[-1]}  ({len(df)} rows)")
    print(f"Backtest year: {year}")
    print(f"Windows: {[str(w) for w in windows]}")
    print(f"Sensors: {sensors}")
    print(f"Detectors: {methods}")
    print(f"Inject: {patterns}   k_values: {k_values}")
    print("\nMode: ROLLING-ORIGIN refit per window (no leakage)")

    rows = evaluate(df, windows, sensors, methods, patterns, k_values, max_points)
    if not rows:
        raise SystemExit("No results produced.")

    _print_per_season(rows, sensors, methods, windows)
    _print_cross_season(rows, sensors, methods, len(windows))

    args.out.parent.mkdir(parents=True, exist_ok=True)
    pd.DataFrame(rows).to_csv(args.out, index=False)
    print(f"\nSaved {len(rows)} rows -> {args.out}")


if __name__ == "__main__":
    main()
