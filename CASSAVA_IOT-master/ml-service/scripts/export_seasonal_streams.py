"""Export KB2 (recovery) + KB3 (forecast) STREAMS for the 4 season months of a year.

Unlike `evaluate_forecast_seasonal.py` (which only saves aggregate MAE/RMSE), this
writes the full per-timestamp series so they can be loaded back into the irrigation
system for an end-to-end check:

  artifacts/streams_<year>/
    clean_<year>.csv                 # ground truth, 5 sensors  (the "correct data")
    missing_mask_<year>.csv          # which hours were dropped (shared by all methods)
    kb3_forecast_<method>_<year>.csv # one-step-ahead forecast, 5 sensors
    kb2_recovered_<method>_<year>.csv# clean where present, model-imputed at gaps, 5 sensors

Per method (arima, sarima, lstm). All 4 season months (Mar/Jun/Sep/Dec) are stacked
with their real <year> timestamps. Rolling-origin: each month's models are fit on all
data strictly before that month (no leakage). ARIMA/SARIMA lift their order from the
saved per-sensor artifacts; LSTM trains fresh once per month (multi-target, 5 views).

    python -m scripts.export_seasonal_streams                      # all methods, all sensors, 10% missing
    python -m scripts.export_seasonal_streams --methods lstm --missing-days 1,6,11,16,21,26
    python -m scripts.export_seasonal_streams --quick              # one month smoke test
"""
from __future__ import annotations

import argparse
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

import numpy as np  # noqa: E402
import pandas as pd  # noqa: E402

from ml.config import ARTIFACTS_DIR  # noqa: E402
from ml.forecasters import ArimaForecaster, SarimaForecaster  # noqa: E402
from scripts.evaluate_forecast_per_sensor import (  # noqa: E402
    WEATHER_SENSORS,
    _build_lstm_views,
)
from scripts.seasonal_common import resolve_year, season_windows  # noqa: E402


def _load_orders(method: str, sensors: list[str]) -> dict:
    """Load each per-sensor artifact ONCE to extract its (seasonal_)order.

    The huge part of an ARIMA artifact is the fitted statsmodels result (260–460 MB);
    we only need the tiny `order` tuple for the rolling-origin refit. Caching it here
    avoids reloading the pickle per window × per sensor (the real bottleneck)."""
    orders: dict = {}
    for s in sensors:
        path = ARTIFACTS_DIR / f"{method}_{s}.pkl"
        if not path.exists():
            orders[s] = None
            continue
        m = ArimaForecaster() if method == "arima" else SarimaForecaster()
        try:
            m.load(path)
            orders[s] = m.order if method == "arima" else (m.order, m.seasonal_order)
        except Exception:  # noqa: BLE001
            orders[s] = None
    return orders


def _make_uni(method: str, sensor: str, orders: dict):
    """Fresh forecaster from a cached order (no artifact reload)."""
    o = orders.get(sensor)
    if method == "arima":
        return ArimaForecaster(order=o) if o else ArimaForecaster()
    return (SarimaForecaster(order=o[0], seasonal_order=o[1], auto=False)
            if o else SarimaForecaster())

ALL_METHODS = ["arima", "sarima", "lstm"]
LSTM_LOOKBACK = pd.Timedelta(hours=120)
DEFAULT_DATA = ARTIFACTS_DIR / "nasa" / "training_data.csv"


def _missing_mask(index: pd.DatetimeIndex, days: list[int]) -> pd.Series:
    """Boolean mask over `index`: True for every hour whose day-of-month is in
    `days` (all 5 sensors drop together — a full-day station outage). Fully
    deterministic: each listed day = one 24h gap, identical in every month."""
    return pd.Series(index.day.isin(days), index=index)


# ---- univariate (ARIMA/SARIMA) walks --------------------------------------

def _uni_forecast(fc, train: pd.Series, clean: pd.Series) -> pd.Series:
    """One-step-ahead forecast over `clean`; real actuals fed forward each step."""
    fc.fit(train)
    out = []
    for ts, val in clean.items():
        ts = pd.Timestamp(ts)
        try:
            out.append(float(fc.predict(ts, horizon=1).forecast[0]))
        except Exception:  # noqa: BLE001
            out.append(np.nan)
        try:
            fc.update(float(val), ts)
        except Exception:  # noqa: BLE001
            pass
    return pd.Series(out, index=clean.index)


def _uni_recover(fc, train: pd.Series, clean: pd.Series, mask: pd.Series) -> pd.Series:
    """Detect->impute walk: at masked points use the forecast (fed back); elsewhere
    keep the real value. Returns the full recovered series."""
    fc.fit(train)
    rec = []
    for ts in clean.index:
        ts = pd.Timestamp(ts)
        if bool(mask.loc[ts]):
            try:
                fed = float(fc.predict(ts, horizon=1).forecast[0])
            except Exception:  # noqa: BLE001
                fed = float(clean.loc[ts])
        else:
            fed = float(clean.loc[ts])
        rec.append(fed)
        try:
            fc.update(fed, ts)
        except Exception:  # noqa: BLE001
            pass
    return pd.Series(rec, index=clean.index)


# ---- multivariate (LSTM) joint walks --------------------------------------

def _lstm_forecast(views: dict, mv_full_clean: pd.DataFrame, month_idx) -> dict[str, list]:
    for v in views.values():
        v.set_offline_context(mv_full_clean)
    out = {s: [] for s in WEATHER_SENSORS}
    for ts in month_idx:
        ts = pd.Timestamp(ts)
        for s in WEATHER_SENSORS:
            try:
                out[s].append(float(views[s].predict(ts, horizon=1).forecast[0]))
            except Exception:  # noqa: BLE001
                out[s].append(np.nan)
    return out


def _lstm_recover(views: dict, mv_full_clean: pd.DataFrame, clean_df: pd.DataFrame,
                  mask: pd.Series) -> dict[str, list]:
    """Joint recovery: at a masked hour all 5 sensors are imputed and fed back into
    the shared context, so the next steps see the imputed (not clean) past."""
    ctx = mv_full_clean.copy()
    for v in views.values():
        v.set_offline_context(ctx)
    out = {s: [] for s in WEATHER_SENSORS}
    for ts in clean_df.index:
        ts = pd.Timestamp(ts)
        if bool(mask.loc[ts]):
            preds = {}
            for s in WEATHER_SENSORS:
                try:
                    preds[s] = float(views[s].predict(ts, horizon=1).forecast[0])
                except Exception:  # noqa: BLE001
                    preds[s] = float(clean_df.loc[ts, s])
            for s in WEATHER_SENSORS:
                ctx.loc[ts, s] = preds[s]   # feed imputed value forward
                out[s].append(preds[s])
        else:
            for s in WEATHER_SENSORS:
                out[s].append(float(clean_df.loc[ts, s]))  # ctx already holds the actual
    return out


def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument("--methods", default=",".join(ALL_METHODS))
    ap.add_argument("--year", type=int, default=None)
    ap.add_argument("--data", type=Path, default=DEFAULT_DATA)
    ap.add_argument("--missing-days", default="1,6,11,16,21,26",
                    help="Days-of-month dropped entirely (all 5 sensors, full 24h each) for "
                         "KB2. Default '1,6,11,16,21,26' → 6 one-day outages/month (~20%).")
    ap.add_argument("--out", type=Path, default=None)
    ap.add_argument("--quick", action="store_true", help="One month, cap rows — smoke test.")
    ap.add_argument("--max-points", type=int, default=None)
    args = ap.parse_args()

    if not args.data.exists():
        raise SystemExit(f"Data not found at {args.data}.")
    df = pd.read_csv(args.data, index_col="time", parse_dates=["time"])

    methods = [m.strip() for m in args.methods.split(",") if m.strip()]
    missing_days = [int(x) for x in args.missing_days.split(",") if x.strip()]
    year = args.year or resolve_year(df)
    windows = season_windows(df, year)
    if args.quick:
        windows = windows[:1]
        args.max_points = args.max_points or 240

    out_dir = args.out or (ARTIFACTS_DIR / f"streams_{year}")
    out_dir.mkdir(parents=True, exist_ok=True)

    print(f"Year: {year}  | windows: {[str(w) for w in windows]}")
    print(f"Methods: {methods}  | missing days-of-month: {missing_days}")
    print(f"Out dir: {out_dir}")

    # Accumulators (stacked across the 4 months).
    clean_parts: list[pd.DataFrame] = []
    mask_parts: list[pd.Series] = []
    fc_parts: dict[str, list[pd.DataFrame]] = {m: [] for m in methods}
    rec_parts: dict[str, list[pd.DataFrame]] = {m: [] for m in methods}

    use_lstm = "lstm" in methods

    # Cache ARIMA/SARIMA orders ONCE (avoid reloading the big artifacts per window).
    orders = {"arima": {}, "sarima": {}}
    for meth in ("arima", "sarima"):
        if meth in methods:
            print(f"Caching {meth} orders (load each artifact once)...")
            orders[meth] = _load_orders(meth, WEATHER_SENSORS)
            print(f"  {meth}: {orders[meth]}")

    for s_idx, w in enumerate(windows):
        print(f"\n# {w}  [train < {w.start:%Y-%m-%d}]")
        month_mask_idx = (df.index >= w.start) & (df.index < w.end)
        clean_df = df.loc[month_mask_idx, WEATHER_SENSORS].copy()
        if args.max_points:
            clean_df = clean_df.iloc[: args.max_points]
        month_idx = clean_df.index
        mask = _missing_mask(month_idx, missing_days)
        print(f"  rows={len(month_idx)}  dropped={int(mask.sum())} ({mask.mean():.1%})  "
              f"days={sorted(set(month_idx[mask.values].day))}")

        clean_parts.append(clean_df)
        mask_parts.append(mask.rename("missing"))

        mv_train = df.loc[df.index < w.start]
        mv_full = df.loc[w.start - LSTM_LOOKBACK : month_idx[-1]]

        lstm_views = {}
        if use_lstm:
            lstm_views = _build_lstm_views(mv_train, mv_full)

        for method in methods:
            print(f"  [{method}] forecast + recover ...")
            if method == "lstm":
                fc_cols = _lstm_forecast(lstm_views, mv_full, month_idx)
                rec_cols = _lstm_recover(lstm_views, mv_full, clean_df, mask)
            else:
                fc_cols, rec_cols = {}, {}
                for s in WEATHER_SENSORS:
                    train_s = df.loc[df.index < w.start, s].dropna()
                    clean_s = clean_df[s]
                    fc_cols[s] = _uni_forecast(_make_uni(method, s, orders[method]), train_s, clean_s).values
                    rec_cols[s] = _uni_recover(_make_uni(method, s, orders[method]), train_s, clean_s, mask).values
            fc_parts[method].append(pd.DataFrame(fc_cols, index=month_idx)[WEATHER_SENSORS])
            rec_parts[method].append(pd.DataFrame(rec_cols, index=month_idx)[WEATHER_SENSORS])

    # ---- write files --------------------------------------------------------
    def _save(frame: pd.DataFrame, name: str) -> None:
        frame.index.name = "time"
        path = out_dir / name
        frame.to_csv(path)
        print(f"  saved {name}  ({len(frame)} rows)")

    # Clip forecasts/recoveries to physical bounds: a linear output head can emit
    # negative rain/radiation (rain is zero-inflated, the model dithers around 0),
    # which is meaningless and would corrupt the irrigation model. Clean truth is
    # real data → not clipped.
    physical = {"rain": (0.0, None), "radiation": (0.0, None),
                "wind": (0.0, None), "relativeHumidity": (0.0, 100.0)}

    def _clip(frame: pd.DataFrame) -> pd.DataFrame:
        for col, (lo, hi) in physical.items():
            if col in frame.columns:
                frame[col] = frame[col].clip(lower=lo, upper=hi)
        return frame

    print("\nWriting files:")
    _save(pd.concat(clean_parts).sort_index(), f"clean_{year}.csv")
    _save(pd.concat(mask_parts).sort_index().to_frame(), f"missing_mask_{year}.csv")
    for method in methods:
        _save(_clip(pd.concat(fc_parts[method]).sort_index()), f"kb3_forecast_{method}_{year}.csv")
        _save(_clip(pd.concat(rec_parts[method]).sort_index()), f"kb2_recovered_{method}_{year}.csv")

    print(f"\nDone -> {out_dir}")


if __name__ == "__main__":
    main()
