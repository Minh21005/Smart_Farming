"""Detector backtest -- precision/recall/F1 vs k, with synthetic anomaly injection.

    python -m scripts.evaluate_detection                                # k=3.0, default detectors, temperature
    python -m scripts.evaluate_detection --k 5.0
    python -m scripts.evaluate_detection --sweep-k 1.0 10.0 0.5
    python -m scripts.evaluate_detection --sweep-k 1.0 10.0 0.5 --sweep-out sweep.csv
    python -m scripts.evaluate_detection --methods zscore,seasonal_zscore
    python -m scripts.evaluate_detection --methods zscore,lstm_residual   # composition demo
    python -m scripts.evaluate_detection --inject spike,drift
    python -m scripts.evaluate_detection --sensor relativeHumidity      # evaluate a non-default sensor
    python -m scripts.evaluate_detection --sensor all                   # loop over all 5 weather sensors

Default mode is REFIT: every detector is fit fresh on the train slice for
each evaluation. ML-residual detectors lift their order/architecture from
the saved artifact metadata (per-sensor artifact wins; legacy temperature
artifact is the fallback) but re-fit parameters on the train slice -- no
test-data leakage.

LSTM-residual now retargets per sensor: the multivariate input window is
still all 5 weather features, but the prediction head is set to `sensor`.
So `--sensor all` runs a fair LSTM comparison across temperature, RH, rain,
radiation, and wind.
"""
from __future__ import annotations

import argparse
import sys
from dataclasses import dataclass
from pathlib import Path

# Allow file-mode invocation in addition to `python -m scripts.evaluate_detection`.
sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

import numpy as np  # noqa: E402
import pandas as pd  # noqa: E402

from ml.base import AnomalyDetector  # noqa: E402
from ml.config import ARTIFACTS_DIR  # noqa: E402
from ml.detectors import (  # noqa: E402
    ResidualDetector,
    SeasonalZScoreDetector,
    ZScoreDetector,
)
from ml.forecasters import ArimaForecaster, LstmForecaster, SarimaForecaster  # noqa: E402


# Each entry maps a CLI name to a callable that builds a fresh detector for refit.
# ML-residual detectors are exposed via composition so the comparison is honest:
# `lstm_residual` evaluates the LSTM forecaster wrapped as a detector. The
# `sensor` argument lets us re-target LSTM (and lift sensor-specific
# ARIMA/SARIMA orders) when those are available.
def _build_detector(name: str, sensor: str = "temperature") -> AnomalyDetector:
    if name == "zscore":
        return ZScoreDetector()
    if name == "seasonal_zscore":
        return SeasonalZScoreDetector()
    if name == "arima_residual":
        return ResidualDetector(_build_forecaster("arima", sensor), name="arima_residual")
    if name == "sarima_residual":
        return ResidualDetector(_build_forecaster("sarima", sensor), name="sarima_residual")
    if name == "lstm_residual":
        return ResidualDetector(_build_forecaster("lstm", sensor), name="lstm_residual")
    raise SystemExit(f"Unknown detector: {name}")


def _build_forecaster(name: str, sensor: str = "temperature"):
    """Build a forecaster, lifting hyperparameters from its artifact if present.

    ARIMA / SARIMA: per-sensor artifact (`arima_<sensor>.pkl`,
    `sarima_<sensor>.pkl`) wins; otherwise the legacy temperature-only
    artifact provides the hyperparameter lift.

    LSTM: single multi-target artifact at `lstm/`. The eval refits a fresh
    multi-target LSTM each call (joint Dense(5) loss); `target=sensor`
    only selects which output column is exposed by predict() for residual
    scoring.
    """
    per_sensor_paths = {
        "arima": ARTIFACTS_DIR / f"arima_{sensor}.pkl",
        "sarima": ARTIFACTS_DIR / f"sarima_{sensor}.pkl",
    }
    legacy_paths = {
        "arima": ARTIFACTS_DIR / "arima.pkl",
        "sarima": ARTIFACTS_DIR / "sarima.pkl",
        "lstm": ARTIFACTS_DIR / "lstm",
    }
    cls_map = {
        "arima": ArimaForecaster,
        "sarima": SarimaForecaster,
        "lstm": LstmForecaster,
    }
    cls = cls_map[name]

    # LSTM uses a single shared artifact regardless of sensor; lift hyperparams,
    # rebuild fresh with target=sensor for refit.
    if name == "lstm":
        art = legacy_paths["lstm"]
        if not art.exists():
            return LstmForecaster(target=sensor)
        loaded = LstmForecaster(target="temperature")  # safe to load any target
        try:
            loaded.load(art)
        except Exception:
            return LstmForecaster(target=sensor)
        return LstmForecaster(
            window=loaded.window,
            epochs=loaded.epochs,
            batch_size=loaded.batch_size,
            target=sensor,
        )

    art = per_sensor_paths[name] if per_sensor_paths[name].exists() else legacy_paths[name]
    if not art.exists():
        return cls()
    loaded = cls()
    try:
        loaded.load(art)
    except Exception:
        return cls()
    if name == "arima":
        return ArimaForecaster(order=loaded.order)
    if name == "sarima":
        return SarimaForecaster(
            order=loaded.order,
            seasonal_order=loaded.seasonal_order,
            auto=False,
        )
    return cls()


DEFAULT_METHODS = ["zscore", "seasonal_zscore"]
ALL_METHODS = [
    "zscore", "seasonal_zscore",
    "arima_residual", "sarima_residual", "lstm_residual",
]
WEATHER_SENSORS = ["temperature", "relativeHumidity", "rain", "radiation", "wind"]
DEFAULT_DATA_PATH = ARTIFACTS_DIR / "nasa" / "training_data.csv"


@dataclass
class Metrics:
    name: str
    k: float
    precision: float
    recall: float
    f1: float


@dataclass
class DetectorRun:
    """Raw scores per test point, BEFORE thresholding."""
    name: str
    scores: np.ndarray
    labels: np.ndarray


def inject_anomalies(
    series: pd.Series, patterns: list[str], seed: int = 42
) -> tuple[pd.Series, pd.Series]:
    rng = np.random.default_rng(seed)
    perturbed = series.copy()
    labels = pd.Series(False, index=series.index)
    n = len(series)

    if "spike" in patterns:
        std = series.std()
        for idx in rng.choice(n, size=max(1, n // 100), replace=False):
            sign = rng.choice([-1, 1])
            perturbed.iloc[idx] = perturbed.iloc[idx] + sign * 5 * std
            labels.iloc[idx] = True

    if "outlier" in patterns:
        hi = series.max() + 50
        for idx in rng.choice(n, size=max(1, n // 200), replace=False):
            perturbed.iloc[idx] = hi
            labels.iloc[idx] = True

    if "drift" in patterns:
        if n > 50:
            start = int(n * 0.6)
            end = min(start + 24, n)
            drift = np.linspace(0, 3 * series.std(), end - start)
            perturbed.iloc[start:end] = perturbed.iloc[start:end] + drift
            labels.iloc[start:end] = True

    if "missing" in patterns:
        # Dropped / missing readings: NaN gaps where the sensor lost the value.
        # These are what the RECOVERY scenario (KB2) restores — the detect→impute
        # loop imputes a forecast at each gap. Point-wise detectors (z-score) skip
        # NaN, so this pattern is meant for the recovery walk, not KB1 detection.
        for idx in rng.choice(n, size=max(1, n // 100), replace=False):
            perturbed.iloc[idx] = np.nan
            labels.iloc[idx] = True

    return perturbed, labels


def _train_test_split_holdout(
    series: pd.Series, test_months: int = 1
) -> tuple[pd.Series, pd.Series]:
    """Hold out the last `test_months` months as the test slice; everything before
    is train. Matches `scripts.train` (`--test-months`), so the saved artifacts
    never see the eval points."""
    if len(series) < 50:
        raise ValueError(f"Need at least 50 points to split, got {len(series)}")
    cutoff = series.index[-1] - pd.DateOffset(months=test_months)
    train = series[series.index < cutoff]
    test = series[series.index >= cutoff]
    if len(train) < 10 or len(test) < 10:
        raise ValueError(
            f"Holdout split produced train={len(train)} test={len(test)}; "
            f"need >=10 each. Try a smaller --test-months."
        )
    return train, test


def score_detector(
    det: AnomalyDetector,
    train: pd.Series,
    test_perturbed: pd.Series,
    test_labels: pd.Series,
    multivariate_train: pd.DataFrame | None = None,
    multivariate_test: pd.DataFrame | None = None,
) -> DetectorRun:
    """Fit on train slice, then score every test point. Returns raw |z| scores
    so any k-threshold can be evaluated after the fact."""
    # Pick the right training shape: ResidualDetector(LstmForecaster) needs
    # the multivariate DataFrame; everyone else takes the univariate series.
    needs_multivariate = (
        isinstance(det, ResidualDetector)
        and isinstance(det.forecaster, LstmForecaster)
    )
    if needs_multivariate:
        if multivariate_train is None:
            raise RuntimeError("LSTM-residual refit needs multivariate_train DataFrame")
        # Pin LSTM init seed for reproducibility — without this, every sensor
        # gets a different random LSTM and lstm_residual F1 numbers are not
        # comparable across detectors or between thesis sections.
        det.forecaster.fit(multivariate_train, seed=42)
        det.fitted = det.forecaster.fitted
        det.residual_std = det.forecaster.residual_std
    else:
        det.fit(train)

    # LSTM context override for offline backtest (no Mongo).
    if isinstance(det, ResidualDetector) and isinstance(det.forecaster, LstmForecaster):
        if multivariate_test is not None:
            det.forecaster.set_offline_context(multivariate_test)

    n = len(test_perturbed)
    scores = np.full(n, np.nan)
    for i, (ts, val) in enumerate(test_perturbed.items()):
        if pd.isna(val):
            continue
        result = det.score(float(val), pd.Timestamp(ts), k=999.0)  # k irrelevant for raw
        scores[i] = float(result.score)

    return DetectorRun(
        name=det.name,
        scores=scores,
        labels=test_labels.fillna(False).to_numpy(),
    )


def metrics_at_k(run: DetectorRun, k: float) -> Metrics:
    flags = (~np.isnan(run.scores)) & (run.scores > k)
    tp = int(np.sum(flags & run.labels))
    fp = int(np.sum(flags & ~run.labels))
    fn = int(np.sum(~flags & run.labels))
    precision = tp / (tp + fp) if (tp + fp) > 0 else 0.0
    recall = tp / (tp + fn) if (tp + fn) > 0 else 0.0
    f1 = 2 * precision * recall / (precision + recall) if (precision + recall) > 0 else 0.0
    return Metrics(run.name, k, precision, recall, f1)


def _print_single_k_table(rows: list[Metrics]) -> None:
    print()
    print(f"{'Method':<25} {'Precision':>10} {'Recall':>10} {'F1':>10}")
    print("-" * 60)
    for m in rows:
        print(f"{m.name:<25} {m.precision:>10.3f} {m.recall:>10.3f} {m.f1:>10.3f}")


def _print_sweep_section(name: str, sweep_rows: list[Metrics]) -> Metrics:
    best = max(sweep_rows, key=lambda m: m.f1)
    print()
    print(f"== {name} == best at k={best.k:.2f}: P={best.precision:.3f} R={best.recall:.3f} F1={best.f1:.3f}")
    print(f"  {'k':>6} {'Precision':>10} {'Recall':>10} {'F1':>10}")
    for m in sweep_rows:
        marker = " *" if m.k == best.k else ""
        print(f"  {m.k:>6.2f} {m.precision:>10.3f} {m.recall:>10.3f} {m.f1:>10.3f}{marker}")
    return best


def _evaluate_sensor(
    df: pd.DataFrame,
    sensor: str,
    selected: list[str],
    patterns: list[str],
    k_values: list[float],
    test_months: int,
) -> list[Metrics]:
    """Run the full eval pipeline on one sensor column. Returns the best-F1 row
    per detector (one row per detector if single k, else best across the sweep)."""
    print(f"\n{'=' * 60}\nSensor: {sensor}\n{'=' * 60}")

    if sensor not in df.columns:
        print(f"  Column '{sensor}' not in data — skipping")
        return []

    series = df[sensor].dropna()
    if len(series) < 100:
        print(f"  Only {len(series)} points — too few to evaluate; skipping")
        return []

    train, test = _train_test_split_holdout(series, test_months=test_months)
    test_perturbed, test_labels = inject_anomalies(test, patterns)
    n_anomalies = int(test_labels.sum())
    print(f"  Train: {len(train)}; test: {len(test)}; injected {n_anomalies} anomalies ({n_anomalies / len(test):.1%})")

    multivariate_test = df.loc[test_perturbed.index].copy()
    multivariate_test[sensor] = test_perturbed
    multivariate_train = df.loc[train.index].copy()

    runs: list[DetectorRun] = []
    for name in selected:
        try:
            det = _build_detector(name, sensor=sensor)
            print(f"  Fitting + scoring {name}...")
            run = score_detector(
                det, train, test_perturbed, test_labels,
                multivariate_train=multivariate_train,
                multivariate_test=multivariate_test,
            )
            runs.append(run)
        except Exception as e:
            print(f"  {name} failed: {e}")

    if not runs:
        return []

    if len(k_values) == 1:
        rows = [metrics_at_k(r, k_values[0]) for r in runs]
        _print_single_k_table(rows)
        return rows

    all_rows: list[Metrics] = []
    best_per_det: list[Metrics] = []
    for run in runs:
        sweep_rows = [metrics_at_k(run, k) for k in k_values]
        best = _print_sweep_section(run.name, sweep_rows)
        best_per_det.append(best)
        all_rows.extend(sweep_rows)

    print()
    print(f"=== Best k per detector ({sensor}) ===")
    print(f"{'Method':<25} {'Best k':>8} {'Precision':>10} {'Recall':>10} {'F1':>10}")
    print("-" * 70)
    for m in best_per_det:
        print(f"{m.name:<25} {m.k:>8.2f} {m.precision:>10.3f} {m.recall:>10.3f} {m.f1:>10.3f}")
    return best_per_det


def _print_cross_sensor_summary(per_sensor: dict[str, list[Metrics]]) -> None:
    """Final table: best F1 per (sensor, detector) so the winner per sensor is obvious."""
    print()
    print("=" * 75)
    print("Cross-sensor summary (best F1 per detector, per sensor)")
    print("=" * 75)
    print(f"{'Sensor':<20} {'Detector':<22} {'k':>6} {'Precision':>10} {'Recall':>9} {'F1':>8}")
    print("-" * 75)
    for sensor, rows in per_sensor.items():
        if not rows:
            print(f"{sensor:<20} (no results)")
            continue
        for m in rows:
            print(f"{sensor:<20} {m.name:<22} {m.k:>6.2f} {m.precision:>10.3f} {m.recall:>9.3f} {m.f1:>8.3f}")


def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument(
        "--methods",
        default=",".join(DEFAULT_METHODS),
        help=f"Comma-separated detector names, or 'all'. Available: {', '.join(ALL_METHODS)}",
    )
    ap.add_argument(
        "--sensor",
        default="temperature",
        help=f"Sensor column to evaluate, or 'all' for all 5 weather sensors. Available: {', '.join(WEATHER_SENSORS)}",
    )
    ap.add_argument("--inject", default="spike,outlier,drift")
    ap.add_argument("--k", type=float, default=3.0)
    ap.add_argument(
        "--sweep-k",
        nargs=3,
        type=float,
        metavar=("MIN", "MAX", "STEP"),
        default=None,
    )
    ap.add_argument("--sweep-out", type=Path, default=None)
    ap.add_argument("--data", type=Path, default=DEFAULT_DATA_PATH)
    ap.add_argument(
        "--test-months",
        type=int,
        default=1,
        help="Hold out the last N months as test. Default 1 (matches scripts.train).",
    )
    args = ap.parse_args()

    if not args.data.exists():
        raise SystemExit(
            f"Data file not found at {args.data}.\n"
            f"Run `python -m scripts.fetch_nasa` first."
        )

    print(f"Loading {args.data}...")
    df = pd.read_csv(args.data, index_col="time", parse_dates=["time"])

    selected = ALL_METHODS if args.methods == "all" else [m.strip() for m in args.methods.split(",")]
    patterns = [p.strip() for p in args.inject.split(",") if p.strip()]
    sensors = WEATHER_SENSORS if args.sensor == "all" else [args.sensor]

    if args.sweep_k:
        kmin, kmax, kstep = args.sweep_k
        if kstep <= 0 or kmax < kmin:
            raise SystemExit(f"Invalid sweep range [{kmin}, {kmax}] step={kstep}")
        k_values = list(np.arange(kmin, kmax + kstep / 2, kstep))
    else:
        k_values = [args.k]

    print(f"Sensors: {sensors}")
    print(f"Detectors: {selected}")
    print(f"Injection patterns: {patterns}")
    print(f"k_values: {[f'{k:.2f}' for k in k_values]}")
    print(f"Test holdout: last {args.test_months} month(s)")
    print("\nMode: REFIT on train slice (no test-data leakage)")

    per_sensor: dict[str, list[Metrics]] = {}
    all_rows_for_csv: list[tuple[str, Metrics]] = []
    for sensor in sensors:
        best_rows = _evaluate_sensor(df, sensor, selected, patterns, k_values, args.test_months)
        per_sensor[sensor] = best_rows
        for m in best_rows:
            all_rows_for_csv.append((sensor, m))

    if len(sensors) > 1:
        _print_cross_sensor_summary(per_sensor)

    if args.sweep_out and all_rows_for_csv:
        args.sweep_out.parent.mkdir(parents=True, exist_ok=True)
        out_df = pd.DataFrame([
            {"sensor": s, "detector": m.name, "k": m.k,
             "precision": m.precision, "recall": m.recall, "f1": m.f1}
            for s, m in all_rows_for_csv
        ])
        out_df.to_csv(args.sweep_out, index=False)
        print(f"\nResults saved -> {args.sweep_out}")


if __name__ == "__main__":
    main()
