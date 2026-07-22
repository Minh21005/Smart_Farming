"""KB2 (recovery) + KB3 (forecast) — 4-season rolling-origin forecast backtest.

Seasonal counterpart of `evaluate_forecast_per_sensor.py`. For each season month
(Mar/Jun/Sep/Dec) every forecaster is fit on data strictly before that month
(rolling origin) and walked one-step-ahead across it, producing two metric families:

  KB3 (chất lượng dự báo)   — MAE/RMSE/MAPE on the CLEAN window. Pure forecast skill.
  KB2 (chất lượng khôi phục) — emulate the production detect→impute loop on the
                               INJECTED window: the injected fault here is MISSING
                               VALUES (NaN gaps, `--inject missing`), the data fault
                               the recovery service exists to repair. At each gap the
                               recovered value = the model's forecast; recovery error =
                               forecast − clean truth. The recovered value is fed back
                               into the model's context (the real "feed a clean stream
                               forward" pipeline from the thesis §2.6.1), so errors can
                               compound exactly as they would in production. (KB1
                               detection — `evaluate_detection_seasonal.py` — instead
                               injects spike/outlier/drift, the present-but-wrong
                               anomalies a point-wise detector can flag.)

    python -m scripts.evaluate_forecast_seasonal
    python -m scripts.evaluate_forecast_seasonal --sensors all --methods arima,sarima,lstm
    python -m scripts.evaluate_forecast_seasonal --quick                # one season smoke test

Reuses `evaluate_forecast_per_sensor` (`_build_arima/_build_sarima/_build_lstm_views`,
`_walk_h1`) for KB3 and `evaluate_detection.inject_anomalies` for the KB2 labels.
"""
from __future__ import annotations

import argparse
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

import numpy as np  # noqa: E402
import pandas as pd  # noqa: E402

from ml.config import ARTIFACTS_DIR  # noqa: E402
from scripts.evaluate_detection import inject_anomalies  # noqa: E402
from scripts.evaluate_forecast_per_sensor import (  # noqa: E402
    ALL_METHODS,
    DEFAULT_DATA,
    WEATHER_SENSORS,
    _build_arima,
    _build_lstm_views,
    _build_sarima,
    _walk_h1,
)
from scripts.seasonal_common import resolve_year, rolling_split, season_windows  # noqa: E402

LSTM_LOOKBACK = pd.Timedelta(hours=120)


def _advance(fc, value: float, ts: pd.Timestamp, is_lstm: bool, ctx: pd.DataFrame, sensor: str) -> None:
    """Feed one observation forward. ARIMA/SARIMA append state; the LSTM has no
    online state so we mutate its offline context frame in place (predict() rebuilds
    its 48h window from that frame each call)."""
    if is_lstm:
        ctx.loc[ts, sensor] = value
    else:
        try:
            fc.update(float(value), pd.Timestamp(ts))
        except Exception:  # noqa: BLE001
            pass


def _walk_recovery(
    fc, train: pd.Series, clean: pd.Series, perturbed: pd.Series, labels: pd.Series,
    needs_fit: bool, is_lstm: bool, ctx: pd.DataFrame | None, sensor: str,
) -> dict | None:
    """KB2 detect→impute walk. Returns recovery error stats over injected points."""
    if needs_fit:
        fc.fit(train)
    rec_errs: list[float] = []
    for ts in clean.index:
        ts = pd.Timestamp(ts)
        try:
            pred = float(fc.predict(ts, horizon=1).forecast[0])
        except Exception:  # noqa: BLE001
            _advance(fc, float(clean.loc[ts]), ts, is_lstm, ctx, sensor)
            continue
        if bool(labels.loc[ts]):
            # Flagged anomaly → impute the forecast; error is vs the clean truth.
            rec_errs.append(pred - float(clean.loc[ts]))
            fed = pred
        else:
            fed = float(perturbed.loc[ts])  # == clean at non-injected points
        _advance(fc, fed, ts, is_lstm, ctx, sensor)

    if not rec_errs:
        return None
    e = np.asarray(rec_errs, dtype=float)
    return {
        "recovery_mae": float(np.mean(np.abs(e))),
        "recovery_rmse": float(np.sqrt(np.mean(e ** 2))),
        "n_recovered": len(e),
    }


def evaluate(df, windows, sensors, methods, patterns, max_points) -> list[dict]:
    rows: list[dict] = []
    use_lstm = "lstm" in methods

    for s_idx, w in enumerate(windows):
        print(f"\n{'#' * 70}\n# Season window: {w}  [train < {w.start:%Y-%m-%d}]\n{'#' * 70}")
        mv_full_clean = df.loc[w.start - LSTM_LOOKBACK : w.end]
        lstm_views = {}
        if use_lstm:
            mv_train = df.loc[df.index < w.start]
            lstm_views = _build_lstm_views(mv_train, mv_full_clean)

        for sensor in sensors:
            if sensor not in df.columns:
                continue
            series = df[sensor].dropna()
            try:
                train, test = rolling_split(series, w)
            except (ValueError, AssertionError) as e:
                print(f"  [{sensor}] split failed: {e}")
                continue
            if max_points is not None:
                test = test.iloc[:max_points]
            clean = test
            perturbed, labels = inject_anomalies(test, patterns, seed=42 + s_idx)
            n_injected = int(labels.sum())
            print(f"  [{sensor}] train={len(train)} test={len(test)} "
                  f"injected={n_injected} ({'+'.join(patterns)})")

            for method in methods:
                is_lstm = method == "lstm"
                try:
                    # KB3 — clean forecast skill.
                    if is_lstm:
                        view = lstm_views[sensor]
                        view.set_offline_context(mv_full_clean)
                        res3 = _walk_h1(view, train, clean, needs_fit=False, max_points=None)
                    elif method == "arima":
                        res3 = _walk_h1(_build_arima(sensor), train, clean, needs_fit=True, max_points=None)
                    elif method == "sarima":
                        res3 = _walk_h1(_build_sarima(sensor), train, clean, needs_fit=True, max_points=None)
                    else:
                        raise SystemExit(f"Unknown method: {method}")

                    # KB2 — recovery via detect→impute feedback loop.
                    if is_lstm:
                        ctx = mv_full_clean.copy()           # isolated, mutable context
                        view2 = lstm_views[sensor]
                        view2.set_offline_context(ctx)
                        rec = _walk_recovery(view2, train, clean, perturbed, labels,
                                             needs_fit=False, is_lstm=True, ctx=ctx, sensor=sensor)
                    else:
                        fc2 = _build_arima(sensor) if method == "arima" else _build_sarima(sensor)
                        rec = _walk_recovery(fc2, train, clean, perturbed, labels,
                                             needs_fit=True, is_lstm=False, ctx=None, sensor=sensor)
                except Exception as e:  # noqa: BLE001
                    print(f"    {method} failed: {e}")
                    continue

                if res3 is None:
                    continue
                row = {
                    "season": w.key, "label": w.label, "month": f"{w.start:%Y-%m}",
                    "sensor": sensor, "method": method,
                    "inject": "+".join(patterns), "n_injected": n_injected,
                    "mae": res3.mae, "rmse": res3.rmse, "mape": res3.mape, "n_eval": res3.n_eval,
                    "recovery_mae": rec["recovery_mae"] if rec else float("nan"),
                    "recovery_rmse": rec["recovery_rmse"] if rec else float("nan"),
                    "n_recovered": rec["n_recovered"] if rec else 0,
                }
                rows.append(row)
                rmae = f"{row['recovery_mae']:.3f}" if rec else "  n/a"
                print(f"    {method:<7} KB3 MAE={res3.mae:.3f} RMSE={res3.rmse:.3f} "
                      f"MAPE={res3.mape:.1f}%  |  KB2 recMAE={rmae} (n={row['n_recovered']})")
    return rows


def _cross_season(rows: list[dict], sensors, methods, metric: str, lower_is_better: bool = True) -> None:
    df = pd.DataFrame(rows)
    title = {"mae": "KB3 forecast MAE", "recovery_mae": "KB2 recovery MAE"}.get(metric, metric)
    print(f"\n{'=' * 70}\nCROSS-SEASON mean {title} (± std across seasons)\n{'=' * 70}")
    print(f"{'Sensor':<18}{'Method':<8}{'mean':>10}{'± std':>9}{'min':>9}{'max':>9}{'seasons':>9}")
    print("-" * 72)
    winners: dict[str, tuple[str, float]] = {}
    for sensor in sensors:
        for method in methods:
            sub = df[(df["sensor"] == sensor) & (df["method"] == method)][metric].dropna()
            if sub.empty:
                continue
            mean, std = float(sub.mean()), float(sub.std() if len(sub) > 1 else 0.0)
            print(f"{sensor:<18}{method:<8}{mean:>10.3f}{std:>9.3f}"
                  f"{sub.min():>9.3f}{sub.max():>9.3f}{len(sub):>9}")
            if sensor not in winners or (mean < winners[sensor][1]) == lower_is_better:
                winners[sensor] = (method, mean)
    print(f"\nWinner per sensor ({title}, {'lower' if lower_is_better else 'higher'} better):")
    for sensor in sensors:
        if sensor in winners:
            print(f"  {sensor:<18} -> {winners[sensor][0]:<8} ({winners[sensor][1]:.3f})")


def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument("--methods", default=",".join(ALL_METHODS),
                    help=f"Comma-separated forecasters. Available: {', '.join(ALL_METHODS)}")
    ap.add_argument("--sensors", default="all",
                    help=f"Comma-separated sensors or 'all'. Available: {', '.join(WEATHER_SENSORS)}")
    ap.add_argument("--inject", default="missing",
                    help="Fault to inject in the KB2 recovery window. Default 'missing' "
                         "(NaN gaps = the fault recovery restores). KB3 forecast uses the "
                         "clean window regardless.")
    ap.add_argument("--year", type=int, default=None)
    ap.add_argument("--data", type=Path, default=DEFAULT_DATA)
    ap.add_argument("--out", type=Path, default=ARTIFACTS_DIR / "seasonal_forecast.csv")
    ap.add_argument("--max-points", type=int, default=None)
    ap.add_argument("--quick", action="store_true", help="One season + cap for a fast smoke test.")
    args = ap.parse_args()

    if not args.data.exists():
        raise SystemExit(f"Data not found at {args.data}. Run `python -m scripts.fetch_nasa` first.")
    df = pd.read_csv(args.data, index_col="time", parse_dates=["time"])

    methods = [m.strip() for m in args.methods.split(",") if m.strip()]
    sensors = WEATHER_SENSORS if args.sensors == "all" else [s.strip() for s in args.sensors.split(",")]
    patterns = [p.strip() for p in args.inject.split(",") if p.strip()]

    year = args.year or resolve_year(df)
    windows = season_windows(df, year)
    max_points = args.max_points
    if args.quick:
        windows = windows[:1]
        sensors = sensors[:1]
        max_points = max_points or 300

    print(f"Data: {df.index[0]} .. {df.index[-1]}  ({len(df)} rows)")
    print(f"Backtest year: {year}")
    print(f"Windows: {[str(w) for w in windows]}")
    print(f"Sensors: {sensors}   Methods: {methods}   Inject: {patterns}")
    print("\nMode: ROLLING-ORIGIN refit per window (no leakage)")

    rows = evaluate(df, windows, sensors, methods, patterns, max_points)
    if not rows:
        raise SystemExit("No results produced.")

    _cross_season(rows, sensors, methods, "mae", lower_is_better=True)
    _cross_season(rows, sensors, methods, "recovery_mae", lower_is_better=True)

    args.out.parent.mkdir(parents=True, exist_ok=True)
    pd.DataFrame(rows).to_csv(args.out, index=False)
    print(f"\nSaved {len(rows)} rows -> {args.out}")


if __name__ == "__main__":
    main()
