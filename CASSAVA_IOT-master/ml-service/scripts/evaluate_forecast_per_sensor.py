"""Per-sensor one-step-ahead forecast eval — MAE/RMSE/MAPE for ARIMA/SARIMA/LSTM across all 5 weather sensors.

Mirrors `evaluate_detection.py --sensor all`, but for forecasting (no anomaly
injection). Each model is fit on the train slice and walked across the clean
test slice in one-step-ahead mode:

  predict(time) -> error vs actual -> update(actual, time)

ARIMA / SARIMA: per-sensor artifact (`arima_<sensor>.pkl`, `sarima_<sensor>.pkl`)
contributes the hyperparameter order; weights are refit on the train slice.

LSTM is multi-target (Dense(5)), so a single fit serves every sensor — views
share the fitted model and only differ by `target`. Offline context is the
full train+test multivariate frame; `_load_recent_context` masks out future
points via `df.loc[:end_time].iloc[:-1]`, so there is no leakage.

    python -m scripts.evaluate_forecast_per_sensor
    python -m scripts.evaluate_forecast_per_sensor --methods arima,sarima
    python -m scripts.evaluate_forecast_per_sensor --max-points 200
"""
from __future__ import annotations

import argparse
import sys
from dataclasses import dataclass
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

import numpy as np  # noqa: E402
import pandas as pd  # noqa: E402

from ml.config import ARTIFACTS_DIR  # noqa: E402
from ml.forecasters import ArimaForecaster, LstmForecaster, SarimaForecaster  # noqa: E402

WEATHER_SENSORS = ["temperature", "relativeHumidity", "rain", "radiation", "wind"]
ALL_METHODS = ["arima", "sarima", "lstm"]
DEFAULT_DATA = ARTIFACTS_DIR / "nasa" / "training_data.csv"


@dataclass
class Row:
    sensor: str
    method: str
    mae: float
    rmse: float
    mape: float
    n_eval: int


def _split_holdout(series: pd.Series, months: int = 1) -> tuple[pd.Series, pd.Series]:
    cutoff = series.index[-1] - pd.DateOffset(months=months)
    return series[series.index < cutoff], series[series.index >= cutoff]


def _build_arima(sensor: str) -> ArimaForecaster:
    path = ARTIFACTS_DIR / f"arima_{sensor}.pkl"
    if not path.exists():
        return ArimaForecaster()
    loaded = ArimaForecaster()
    loaded.load(path)
    return ArimaForecaster(order=loaded.order)


def _build_sarima(sensor: str) -> SarimaForecaster:
    path = ARTIFACTS_DIR / f"sarima_{sensor}.pkl"
    if not path.exists():
        return SarimaForecaster()
    loaded = SarimaForecaster()
    loaded.load(path)
    return SarimaForecaster(
        order=loaded.order,
        seasonal_order=loaded.seasonal_order,
        auto=False,
    )


def _build_lstm_views(mv_train: pd.DataFrame, mv_full: pd.DataFrame,
                      seed: int = 42) -> dict[str, LstmForecaster]:
    """Fit LSTM once (seed pinned for reproducibility), return 5 views sharing the fit."""
    print(f"[LSTM] Fitting multi-target Dense(5) once on multivariate train slice (seed={seed})...")
    base = LstmForecaster(target="temperature")
    base.fit(mv_train, seed=seed)
    views: dict[str, LstmForecaster] = {}
    for sensor in WEATHER_SENSORS:
        v = LstmForecaster(target=sensor)
        v._model = base._model
        v._scaler = base._scaler
        v._residual_std_by_target = base._residual_std_by_target
        v.residual_std = base._residual_std_by_target[sensor]
        v.fitted = True
        v.set_offline_context(mv_full)
        views[sensor] = v
    return views


def _walk_h1(
    fc,
    train_series: pd.Series,
    test_series: pd.Series,
    needs_fit: bool,
    max_points: int | None,
) -> Row | None:
    """One-step-ahead walk. For LSTM we skip fit (it was done once upstream)."""
    if needs_fit:
        fc.fit(train_series)
    actuals = test_series.values.astype(float)
    timestamps = test_series.index
    n_walk = len(test_series) - 1
    if max_points is not None:
        n_walk = min(n_walk, max_points)

    errs: list[float] = []
    actual_at_h: list[float] = []
    for i in range(n_walk):
        ts = pd.Timestamp(timestamps[i])
        try:
            pred = fc.predict(ts, horizon=1)
        except Exception:
            try:
                fc.update(float(actuals[i]), ts)
            except Exception:
                pass
            continue

        errs.append(pred.forecast[0] - actuals[i])
        actual_at_h.append(actuals[i])

        try:
            fc.update(float(actuals[i]), ts)
        except Exception:
            pass

    if not errs:
        return None

    e = np.array(errs, dtype=float)
    a = np.array(actual_at_h, dtype=float)
    mae = float(np.mean(np.abs(e)))
    rmse = float(np.sqrt(np.mean(e ** 2)))
    mask = np.abs(a) > 1e-3
    mape = float(np.mean(np.abs(e[mask] / a[mask])) * 100) if mask.any() else float("nan")
    return Row(sensor="", method=fc.name, mae=mae, rmse=rmse, mape=mape, n_eval=len(e))


def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument("--methods", default=",".join(ALL_METHODS),
                    help=f"Comma-separated forecaster names. Available: {', '.join(ALL_METHODS)}")
    ap.add_argument("--sensors", default=",".join(WEATHER_SENSORS),
                    help=f"Comma-separated sensors, or 'all'. Available: {', '.join(WEATHER_SENSORS)}")
    ap.add_argument("--data", type=Path, default=DEFAULT_DATA)
    ap.add_argument("--test-months", type=int, default=1)
    ap.add_argument("--max-points", type=int, default=None,
                    help="Cap predict-walk length per sensor (useful to smoke-test LSTM quickly).")
    ap.add_argument("--out", type=Path, default=ARTIFACTS_DIR / "forecast_per_sensor.csv")
    args = ap.parse_args()

    if not args.data.exists():
        raise SystemExit(f"Data not found at {args.data}. Run `python -m scripts.fetch_nasa` first.")

    df = pd.read_csv(args.data, index_col="time", parse_dates=["time"])
    selected_methods = [m.strip() for m in args.methods.split(",") if m.strip()]
    selected_sensors = (
        WEATHER_SENSORS if args.sensors == "all"
        else [s.strip() for s in args.sensors.split(",") if s.strip()]
    )
    print(f"Sensors: {selected_sensors}")
    print(f"Methods: {selected_methods}")
    print(f"Test holdout: last {args.test_months} month(s)")

    rows: list[Row] = []

    # Multivariate frames are needed by LSTM. Use the union of all sensor indices.
    train_idx_ref, test_idx_ref = None, None
    for s in selected_sensors:
        if s not in df.columns:
            print(f"  WARN: sensor {s!r} not in data; skipping")
            continue
        tr, te = _split_holdout(df[s].dropna(), months=args.test_months)
        train_idx_ref = tr.index if train_idx_ref is None else train_idx_ref
        test_idx_ref = te.index if test_idx_ref is None else test_idx_ref

    mv_train = df.loc[train_idx_ref].copy()
    mv_test = df.loc[test_idx_ref].copy()
    mv_full = pd.concat([mv_train, mv_test]).sort_index()

    # ---- LSTM: one fit, five sensor views -----------------------------------
    lstm_views: dict[str, LstmForecaster] = {}
    if "lstm" in selected_methods:
        lstm_views = _build_lstm_views(mv_train, mv_full)

    # ---- Walk each (sensor, method) ----------------------------------------
    for sensor in selected_sensors:
        if sensor not in df.columns:
            continue
        train, test = _split_holdout(df[sensor].dropna(), months=args.test_months)
        print(f"\n=== {sensor} === train={len(train)}, test={len(test)}")

        for method in selected_methods:
            print(f"  [{method}] fitting + walking...")
            try:
                if method == "arima":
                    fc = _build_arima(sensor)
                    res = _walk_h1(fc, train, test, needs_fit=True, max_points=args.max_points)
                elif method == "sarima":
                    fc = _build_sarima(sensor)
                    res = _walk_h1(fc, train, test, needs_fit=True, max_points=args.max_points)
                elif method == "lstm":
                    fc = lstm_views[sensor]
                    res = _walk_h1(fc, train, test, needs_fit=False, max_points=args.max_points)
                else:
                    raise SystemExit(f"Unknown method: {method}")
            except Exception as e:
                print(f"    {method} failed: {e}")
                continue

            if res is None:
                print(f"    {method}: no successful predictions")
                continue
            res.sensor = sensor
            rows.append(res)
            print(f"    {method}: MAE={res.mae:.3f}  RMSE={res.rmse:.3f}  MAPE={res.mape:.2f}%  n={res.n_eval}")

    # ---- Pretty per-sensor matrix ------------------------------------------
    print("\n" + "=" * 78)
    print("Per-sensor forecast comparison (h=1, one-step-ahead)")
    print("=" * 78)
    print(f"{'Sensor':<18} {'Method':<8} {'MAE':>10} {'RMSE':>10} {'MAPE %':>10} {'N':>8}")
    print("-" * 78)
    rows.sort(key=lambda r: (WEATHER_SENSORS.index(r.sensor), ALL_METHODS.index(r.method)))
    for r in rows:
        mape_s = f"{r.mape:>10.2f}" if not np.isnan(r.mape) else f"{'nan':>10}"
        print(f"{r.sensor:<18} {r.method:<8} {r.mae:>10.3f} {r.rmse:>10.3f} {mape_s} {r.n_eval:>8d}")

    # ---- Save CSV -----------------------------------------------------------
    args.out.parent.mkdir(parents=True, exist_ok=True)
    pd.DataFrame([
        {"sensor": r.sensor, "method": r.method, "mae": r.mae,
         "rmse": r.rmse, "mape": r.mape, "n_eval": r.n_eval}
        for r in rows
    ]).to_csv(args.out, index=False)
    print(f"\nSaved -> {args.out}")


if __name__ == "__main__":
    main()
