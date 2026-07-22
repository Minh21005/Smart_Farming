"""Train one or more anomaly detection models from a pre-fetched NASA CSV.

    # ONE TIME: fetch NASA POWER data into a CSV
    python -m scripts.fetch_nasa

    # Train (full CSV minus the last 1 month — that month is the eval holdout)
    python -m scripts.train --model arima                    # default --sensor temperature
    python -m scripts.train --model sarima
    python -m scripts.train --model lstm
    python -m scripts.train --model all

    # Per-sensor: train ARIMA + SARIMA for every weather sensor.
    # LSTM is multi-target (1 model predicts all 5 sensors at once) → --sensor
    # is ignored for LSTM; one `--model lstm` run produces one `artifacts/lstm/`
    # artifact that serves every sensor at inference time.
    python -m scripts.train --model arima  --sensor all
    python -m scripts.train --model sarima --sensor all
    python -m scripts.train --model lstm
    python -m scripts.train --model all    --sensor all

    # Auto-select orders by AIC stepwise search:
    #   ARIMA  — pmdarima.auto_arima (statsmodels backend)
    #   SARIMA — statsforecast.AutoARIMA (memory-efficient; full-data search OK)
    python -m scripts.train --model arima  --sensor all --auto-order
    python -m scripts.train --model sarima --sensor all --auto-order

    # Adjust the test holdout; pass 0 to use the full CSV
    python -m scripts.train --model arima --test-months 3
    python -m scripts.train --model lstm --test-months 0

Artifacts:
- ARIMA / SARIMA: per-sensor `{model}_{sensor}.pkl` (e.g. `arima_temperature.pkl`,
  `sarima_rain.pkl`). One artifact per sensor.
- LSTM: single multi-target artifact at `lstm/` — one model predicts all 5
  features (Dense(5) output). At inference, the caller picks one output via
  `LstmForecaster(target=<sensor>)`. So --sensor is ignored for LSTM.

The **last 1 month** of the CSV is excluded from training and reserved as a
held-out test set — matched by the same 1-month split in `evaluate_detection`
and `evaluate_forecast_per_sensor`, so the saved artifacts never see the eval slice.

Pass --data <path> to use a different CSV.
"""
from __future__ import annotations

import argparse
import sys
from pathlib import Path

# Allow running as a file (`python scripts/train.py` or IDE Run button) in
# addition to module mode (`python -m scripts.train`). Module mode already
# gets ml-service/ on sys.path; file mode does not.
sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

import pandas as pd  # noqa: E402

from ml.config import ARTIFACTS_DIR  # noqa: E402
from ml.forecasters import ArimaForecaster, LstmForecaster, SarimaForecaster  # noqa: E402

DEFAULT_DATA_PATH = ARTIFACTS_DIR / "nasa" / "training_data.csv"

WEATHER_SENSORS = ["temperature", "relativeHumidity", "rain", "radiation", "wind"]


def _arima_path(sensor: str) -> Path:
    return ARTIFACTS_DIR / f"arima_{sensor}.pkl"


def _sarima_path(sensor: str) -> Path:
    return ARTIFACTS_DIR / f"sarima_{sensor}.pkl"


def _load_training_data(path: Path, test_months: int = 0) -> pd.DataFrame:
    if not path.exists():
        raise SystemExit(
            f"Training data not found at {path}.\n"
            f"Run `python -m scripts.fetch_nasa` first to fetch NASA POWER data."
        )
    df = pd.read_csv(path, index_col="time", parse_dates=["time"])
    print(f"[DATA] Loaded {len(df)} hourly rows from {path}")
    print(f"       Span: {df.index[0]} .. {df.index[-1]}")

    if test_months > 0:
        test_cutoff = df.index[-1] - pd.DateOffset(months=test_months)
        df_train = df[df.index < test_cutoff]
        if len(df_train) < len(df):
            print(f"[DATA] Held out last {test_months} month(s) for eval -> train={len(df_train)} rows")
            print(f"       Train span: {df_train.index[0]} .. {df_train.index[-1]}")
            return df_train
    return df


def _auto_order(
    series: pd.Series,
    seasonal: bool,
    m: int = 1,
    search_size: int = 0,
) -> tuple[tuple[int, int, int], tuple[int, int, int, int]]:
    """Stepwise AIC search via pmdarima.auto_arima.

    Returns (order, seasonal_order). Constrained search bounds keep the runtime
    reasonable on hourly data:
      ARIMA   : max_p=5,  max_q=5,  max_d=2
      SARIMA  : max_p=2,  max_q=2,  max_P=1, max_Q=1, max_d=1, max_D=1, m=24

    If `search_size > 0`, runs the search on the LAST `search_size` points;
    the caller fits the final model on full data with the chosen order.
    """
    try:
        from pmdarima import auto_arima
    except ImportError:
        raise SystemExit(
            "pmdarima not installed. Run:\n"
            "  ml-service/.venv/Scripts/python.exe -m pip install pmdarima"
        )

    label = "SARIMA" if seasonal else "ARIMA"
    if search_size > 0 and len(series) > search_size:
        search_series = series.tail(search_size)
        print(f"  [{label}] Auto-search on last {search_size} of {len(series)} points...")
    else:
        search_series = series
        print(f"  [{label}] Auto-search on all {len(series)} points...")

    kwargs: dict = dict(
        seasonal=seasonal,
        stepwise=True,
        suppress_warnings=True,
        error_action="ignore",
        trace=True,
    )
    if seasonal:
        kwargs.update(m=m, max_p=2, max_q=2, max_P=1, max_Q=1, max_d=1, max_D=1)
    else:
        kwargs.update(max_p=5, max_q=5, max_d=2)

    fitted = auto_arima(search_series, **kwargs)
    return fitted.order, fitted.seasonal_order


def train_arima(
    df: pd.DataFrame,
    sensor: str,
    auto_order: bool = False,
    search_size: int = 0,
    interpolate_method: str = "linear",
    interpolate_limit: int | None = 3,
) -> None:
    if sensor not in df.columns:
        print(f"[ARIMA] Column '{sensor}' not in data — skipping")
        return
    # Pass the RAW series (gaps intact) so the forecaster's configured
    # interpolation actually runs — small gaps (<= limit) filled, the rest dropped.
    series = df[sensor]
    clean = series.interpolate(method=interpolate_method, limit=interpolate_limit).dropna()
    print(f"[ARIMA] Training {sensor}: {len(clean)} usable points after "
          f"interpolate(method={interpolate_method}, limit={interpolate_limit})...")

    if auto_order:
        order, _ = _auto_order(clean, seasonal=False, search_size=search_size)
        print(f"  Auto-selected order: {order}")
        det = ArimaForecaster(order=order, interpolate_method=interpolate_method,
                              interpolate_limit=interpolate_limit)
    else:
        det = ArimaForecaster(interpolate_method=interpolate_method,
                              interpolate_limit=interpolate_limit)

    print(f"  Fitting ARIMA{det.order}...")
    det.fit(series)
    out = _arima_path(sensor)
    det.save(out)
    print(f"  Saved -> {out} (residual_std={det.residual_std:.3f})")


def train_sarima(
    df: pd.DataFrame,
    sensor: str,
    auto_order: bool = False,
    interpolate_method: str = "linear",
    interpolate_limit: int | None = 3,
) -> None:
    if sensor not in df.columns:
        print(f"[SARIMA] Column '{sensor}' not in data — skipping")
        return
    # Raw series in; SarimaForecaster.fit applies the configured interpolation.
    series = df[sensor]
    clean = series.interpolate(method=interpolate_method, limit=interpolate_limit).dropna()
    print(f"[SARIMA] Training {sensor}: {len(clean)} usable points after "
          f"interpolate(method={interpolate_method}, limit={interpolate_limit})...")

    # SarimaForecaster uses statsforecast under the hood. When auto=True,
    # AutoARIMA's stepwise search runs INSIDE fit() — no separate helper call,
    # and no need to subsample for memory (statsforecast handles full data).
    det = SarimaForecaster(auto=auto_order, interpolate_method=interpolate_method,
                           interpolate_limit=interpolate_limit)

    if auto_order:
        print("  Fitting SARIMA via statsforecast AutoARIMA (stepwise search inside fit)...")
    else:
        print(f"  Fitting SARIMA{det.order}{det.seasonal_order}... (slow)")
    det.fit(series)
    out = _sarima_path(sensor)
    det.save(out)
    print(f"  Saved -> {out} (residual_std={det.residual_std:.3f})")


def train_lstm(df: pd.DataFrame) -> None:
    # LSTM is multivariate AND multi-target: 1 model jointly predicts all
    # 5 weather features (Dense(5) output). Single artifact at
    # ARTIFACTS_DIR / "lstm" serves every sensor — callers pick the target
    # view at load-time. So --sensor is ignored for LSTM training.
    det = LstmForecaster()
    print(f"[LSTM] Training multi-target (window={det.window}, epochs={det.epochs})... (slow)")
    det.fit(df)
    out = ARTIFACTS_DIR / "lstm"
    det.save(out)
    print(f"  Saved -> {out}")
    print(f"  residual_std per target:")
    for sensor_name, std in det._residual_std_by_target.items():
        print(f"    {sensor_name:<20} {std:.3f}")


def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument("--model", choices=["arima", "sarima", "lstm", "all"], required=True)
    ap.add_argument(
        "--sensor",
        default="temperature",
        help=f"Sensor column to train on, or 'all' for every weather sensor. "
             f"Available: {', '.join(WEATHER_SENSORS)}",
    )
    ap.add_argument(
        "--data",
        type=Path,
        default=DEFAULT_DATA_PATH,
        help="CSV produced by `python -m scripts.fetch_nasa`",
    )
    ap.add_argument(
        "--auto-order",
        action="store_true",
        help="Use pmdarima.auto_arima to stepwise-search ARIMA/SARIMA orders by AIC. "
             "Ignored for LSTM. SARIMA auto-search is slow -- pair with --auto-search-size.",
    )
    ap.add_argument(
        "--auto-search-size",
        type=int,
        default=0,
        help="When --auto-order is set, run the search on the last N points only "
             "(0 = use full data). Useful for SARIMA where each candidate fit is slow. "
             "The final model is still fit on the full series with the chosen order.",
    )
    ap.add_argument(
        "--test-months",
        type=int,
        default=1,
        help="Exclude the last N months from training, reserving them as a held-out "
             "test set. Default 1 (matches evaluate_detection / evaluate_forecast_per_sensor). "
             "Pass 0 to train on the full CSV.",
    )
    ap.add_argument(
        "--interpolate-method",
        default="linear",
        help="pandas Series.interpolate method applied to ARIMA/SARIMA input before "
             "fit (e.g. linear, time, nearest). Ignored for LSTM. Default: linear.",
    )
    ap.add_argument(
        "--interpolate-limit",
        type=lambda v: None if str(v).lower() in ("none", "", "0") else int(v),
        default=3,
        help="Max consecutive NaNs the ARIMA/SARIMA interpolation fills before "
             "dropping the rest. Pass 'none' for unlimited. Ignored for LSTM. Default: 3.",
    )
    args = ap.parse_args()

    df = _load_training_data(args.data, test_months=args.test_months)

    sensors = WEATHER_SENSORS if args.sensor == "all" else [args.sensor]
    for sensor in sensors:
        print(f"\n=== Training for sensor: {sensor} ===")
        if args.model in ("arima", "all"):
            train_arima(df, sensor, auto_order=args.auto_order, search_size=args.auto_search_size,
                        interpolate_method=args.interpolate_method,
                        interpolate_limit=args.interpolate_limit)
        if args.model in ("sarima", "all"):
            # SARIMA uses statsforecast AutoARIMA which handles full-data search
            # natively; --auto-search-size only affects ARIMA's pmdarima path.
            train_sarima(df, sensor, auto_order=args.auto_order,
                         interpolate_method=args.interpolate_method,
                         interpolate_limit=args.interpolate_limit)

    # LSTM is multi-target by construction (Dense(5) predicts all 5 sensors).
    # Train ONCE regardless of --sensor; `--sensor all` doesn't multiply LSTM
    # work the way it does for ARIMA / SARIMA.
    if args.model in ("lstm", "all"):
        print(f"\n=== Training LSTM (multi-target, all 5 sensors at once) ===")
        train_lstm(df)


if __name__ == "__main__":
    main()
