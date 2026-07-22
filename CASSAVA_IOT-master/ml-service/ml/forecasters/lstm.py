"""LSTM forecaster — multivariate, multi-target.

Input: 48-hour window of (temperature, relativeHumidity, wind, rain, radiation).
Output: predicted next-hour value of **all 5** features (Dense(5)).

A single trained model serves every weather sensor: at inference time the
caller picks one output via the `target` parameter. `target` is a *view*
on a shared multi-output model — it does NOT affect training (training is
joint over all 5 columns with MSE summed across them).

Training data comes from NASA POWER (3+ years). At prediction time, we
query the last 48 hours of MQTT sensor data from Mongo (or an injected
offline context) and ask the LSTM to predict the next-hour values of all
5 features; the caller's `target` selects which scalar to return.

Architecture (literature-grounded):
    LSTM(64, variational dropout) -> LSTM(32, variational dropout)
        -> Dropout(0.1) -> Dense(32, relu) -> Dense(5)
    Huber loss, Adam(1e-3), EarlyStopping on a validation tail.
Variational dropout (dropout + recurrent_dropout on the LSTM cells, Gal &
Ghahramani 2016) replaces the old post-LSTM Dropout; depth (2 stacked layers)
and the robust Huber loss follow the hourly-weather LSTM literature.

Multi-step horizons (`predict(time, h>1)`) are produced by iterative
roll-forward: at each step the predicted 5-vector replaces the oldest
row of the window so the next step has self-consistent multivariate
context. Crude but cheap; for richer horizons retrain with seq2seq output.

To turn this into an anomaly detector for a specific sensor, wrap with
`ml.detectors.ResidualDetector(LstmForecaster(target=sensor))`. The
wrapper uses `residual_std` for that target (calibrated per-target at
fit/load time).
"""
from __future__ import annotations

import pickle
from datetime import timedelta
from pathlib import Path

import numpy as np
import pandas as pd

from ..base import ForecastResult, Forecaster
from ..data import load_sensor_series

LSTM_FEATURES = ["temperature", "relativeHumidity", "wind", "rain", "radiation"]


class LstmForecaster(Forecaster):
    name = "lstm"

    def __init__(
        self,
        window: int = 48,
        epochs: int = 100,
        batch_size: int = 32,
        target: str = "temperature",
        hidden_units: int = 64,
        num_layers: int = 2,
        dropout: float = 0.1,
        recurrent_dropout: float = 0.1,
        dense_units: int = 32,
        learning_rate: float = 1e-3,
        loss: str = "huber",
        huber_delta: float = 0.1,
        validation_split: float = 0.1,
        patience: int = 8,
    ) -> None:
        super().__init__()
        if target not in LSTM_FEATURES:
            raise ValueError(
                f"LSTM target {target!r} not in LSTM_FEATURES {LSTM_FEATURES}"
            )
        self.window = window
        # `epochs` is now a CAP: EarlyStopping (patience) usually halts well before
        # this, restoring the best-val-loss weights. Literature-grounded defaults —
        # see the module docstring above for the architecture rationale.
        self.epochs = epochs
        self.batch_size = batch_size
        self.target = target
        self.target_idx = LSTM_FEATURES.index(target)
        # Architecture / training knobs (lifted out of fit() so the config is
        # explicit and serialisable; values come from the literature review).
        self.hidden_units = hidden_units      # units in the first LSTM layer
        self.num_layers = num_layers          # stacked LSTM layers (64 -> 32 ...)
        self.dropout = dropout                # input dropout on LSTM (variational)
        self.recurrent_dropout = recurrent_dropout  # recurrent dropout (Gal & Ghahramani)
        self.dense_units = dense_units        # bottleneck Dense width
        self.learning_rate = learning_rate
        self.loss = loss                      # "huber" (robust) or "mse"
        self.huber_delta = huber_delta        # Huber transition (scaled units)
        self.validation_split = validation_split
        self.patience = patience              # EarlyStopping patience (epochs)
        self._scaler = None  # sklearn MinMaxScaler
        self._model = None   # Keras model with Dense(len(LSTM_FEATURES)) output
        # `residual_std` mirrors `self._residual_std_by_target[self.target]`
        # so ResidualDetector wrappers see the right sigma per target view.
        self._residual_std_by_target: dict[str, float] = {}
        # Offline-evaluation context: when set, predict() pulls the 48h window
        # from this DataFrame instead of querying Mongo. Used by the offline
        # evaluator that has no Mongo data flow.
        self._offline_context: pd.DataFrame | None = None

    def set_offline_context(self, multivariate_df: pd.DataFrame) -> None:
        """Override the Mongo-based context loader with an in-memory frame.

        The frame must have all `LSTM_FEATURES` columns and a sorted DatetimeIndex.
        Each `predict(time, ...)` call will then look up the 48 hours ending just
        before `time` from this frame instead of hitting Mongo.
        """
        self._offline_context = multivariate_df

    # ---- training (NASA multivariate, multi-target) ---------------------

    def fit(self, multivariate_df: pd.DataFrame, seed: int | None = None) -> None:
        """Train on a NASA-style multivariate hourly DataFrame.

        The model is trained jointly to predict all 5 features at next step.
        After fit, `_residual_std_by_target` holds per-target residual sigma
        and `residual_std` exposes the value for `self.target`.

        `seed`: if provided, makes initialization + dropout deterministic so
        runs with the same seed are byte-identical. `evaluate_detection.py`
        pins seed=42 for the lstm_residual refit so per-sensor F1 numbers are
        comparable across detectors (separates config effect from random-init noise).
        """
        from sklearn.preprocessing import MinMaxScaler  # noqa: PLC0415
        from tensorflow import keras  # noqa: PLC0415

        if seed is not None:
            import random as _random  # noqa: PLC0415
            _random.seed(seed)
            np.random.seed(seed)
            keras.utils.set_random_seed(seed)

        df = multivariate_df.reindex(columns=LSTM_FEATURES).interpolate(limit=3).dropna()
        if len(df) < self.window + 50:
            raise ValueError(f"LSTM needs >= {self.window + 50} rows, got {len(df)}")

        self._scaler = MinMaxScaler()
        scaled = self._scaler.fit_transform(df.values)

        X, Y = self._make_sequences(scaled)

        # Stacked LSTM with variational dropout. `dropout` (inputs) +
        # `recurrent_dropout` (recurrent connections, same mask across timesteps)
        # is the theoretically-grounded scheme for RNNs from Gal & Ghahramani
        # (NeurIPS 2016) — naive Dropout on recurrent state hurts. recurrent_dropout
        # disables the cuDNN kernel, but we train on CPU so there is no loss.
        layers = [keras.layers.Input(shape=(self.window, len(LSTM_FEATURES)))]
        units = self.hidden_units
        for li in range(self.num_layers):
            last = li == self.num_layers - 1
            layers.append(
                keras.layers.LSTM(
                    units,
                    return_sequences=not last,   # stack feeds sequences downward
                    dropout=self.dropout,
                    recurrent_dropout=self.recurrent_dropout,
                )
            )
            units = max(units // 2, 8)           # taper 64 -> 32 -> ...
        layers.append(keras.layers.Dropout(self.dropout))   # feed-forward dropout
        layers.append(keras.layers.Dense(self.dense_units, activation="relu"))
        layers.append(keras.layers.Dense(len(LSTM_FEATURES)))  # 5 linear outputs
        self._model = keras.Sequential(layers)

        # Huber (robust to spikes; MSE-like within |residual| < delta so the
        # near-Gaussian residual core that the |z|>k detector assumes is kept).
        # delta is in MinMax-scaled units. Fall back to plain MSE if requested.
        loss_fn = (
            keras.losses.Huber(delta=self.huber_delta)
            if self.loss == "huber" else self.loss
        )
        self._model.compile(
            optimizer=keras.optimizers.Adam(learning_rate=self.learning_rate),
            loss=loss_fn,
        )
        # EarlyStopping on a chronological validation tail (Keras takes the last
        # `validation_split` of X before shuffling). `epochs` is the cap.
        callbacks = []
        if self.validation_split and self.patience:
            callbacks.append(
                keras.callbacks.EarlyStopping(
                    monitor="val_loss",
                    patience=self.patience,
                    restore_best_weights=True,
                )
            )
        self._model.fit(
            X, Y,
            epochs=self.epochs,
            batch_size=self.batch_size,
            validation_split=self.validation_split or 0.0,
            callbacks=callbacks,
            verbose=0,
        )
        self.fitted = True

        # Calibrate residual_std per target so ResidualDetector wrappers
        # threshold |residual| / sigma correctly for whichever sensor view.
        Yhat_scaled = self._model.predict(X, verbose=0)  # (N, 5)
        self._residual_std_by_target = {}
        for idx, feature in enumerate(LSTM_FEATURES):
            predicted = self._inverse_one_col(Yhat_scaled[:, idx], idx)
            actual = df[feature].iloc[self.window :].values
            residuals = actual - predicted
            self._residual_std_by_target[feature] = float(np.std(residuals))
        self.residual_std = self._residual_std_by_target[self.target]

    def _make_sequences(self, scaled: np.ndarray) -> tuple[np.ndarray, np.ndarray]:
        """Build training pairs. Y is the FULL 5-feature next-step vector."""
        X, Y = [], []
        for i in range(len(scaled) - self.window):
            X.append(scaled[i : i + self.window])
            Y.append(scaled[i + self.window])  # all 5 features at next step
        return np.array(X), np.array(Y)

    def _inverse_one_col(self, scaled_col: np.ndarray, col_idx: int) -> np.ndarray:
        """Inverse-transform one scaled column back to original units.

        Scaler was fit on all 5 features, so we pad the other 4 with zeros.
        The MinMax inverse is independent per column, so the padding values
        don't matter — only the column we're extracting.
        """
        pad = np.zeros((len(scaled_col), len(LSTM_FEATURES)))
        pad[:, col_idx] = scaled_col
        return self._scaler.inverse_transform(pad)[:, col_idx]

    # ---- prediction (returns target view; rolls forward all 5 features) -

    def predict(self, time: pd.Timestamp, horizon: int = 1) -> ForecastResult:
        if self._model is None or self._scaler is None:
            raise RuntimeError("LstmForecaster not fitted")

        ts = pd.Timestamp(time)
        context = self._load_recent_context(ts)
        if len(context) < self.window:
            raise RuntimeError(f"Need {self.window} hours of context, got {len(context)}")

        # Roll forward consistently across all 5 features. Each step's full
        # 5-vector prediction becomes the next window row, so the model's
        # multivariate input stays self-consistent for h > 1.
        scaled_window = self._scaler.transform(context.values).copy()
        forecasts: list[float] = []
        for _ in range(horizon):
            X = scaled_window.reshape(1, self.window, len(LSTM_FEATURES))
            yhat_scaled_all = self._model.predict(X, verbose=0)[0]  # (5,)

            # Extract the configured target column, inverse-transform → unit gốc
            target_scaled = float(yhat_scaled_all[self.target_idx])
            predicted = float(
                self._inverse_one_col(np.array([target_scaled]), self.target_idx)[0]
            )
            forecasts.append(predicted)

            # Slide window: drop oldest, append the FULL 5-d prediction
            scaled_window = np.vstack([scaled_window[1:], yhat_scaled_all])

        out_ts = pd.date_range(ts, periods=horizon, freq="h")
        return ForecastResult(
            forecast=forecasts,
            timestamps=list(out_ts),
            name=self.name,
            residual_std=self.residual_std,
        )

    # No `update()` override needed: LSTM rebuilds its 48h window on each
    # predict() call from `_load_recent_context`, so newly-arrived points
    # (live Mongo or appended offline frame) are picked up automatically.

    def _load_recent_context(self, end_time: pd.Timestamp) -> pd.DataFrame:
        """Pull the last `window` hours of multivariate data ending just before
        `end_time`. Uses `_offline_context` if set; otherwise queries Mongo.
        """
        if self._offline_context is not None:
            df = self._offline_context
            df = df.loc[:end_time].iloc[:-1] if end_time in df.index else df.loc[:end_time]
            df = df.reindex(columns=LSTM_FEATURES).interpolate(limit=3).dropna()
            return df.tail(self.window)

        start = (end_time - timedelta(hours=self.window + 5)).to_pydatetime()
        end = end_time.to_pydatetime()
        cols: dict[str, pd.Series] = {}
        for sensor in LSTM_FEATURES:
            cols[sensor] = load_sensor_series(sensor_id=sensor, start=start, end=end)
        df = pd.DataFrame(cols).interpolate(limit=3).dropna()
        return df.tail(self.window)

    # ---- persistence -----------------------------------------------------

    def save(self, path: Path) -> None:
        path.mkdir(parents=True, exist_ok=True)
        self._model.save(path / "model.keras")
        with open(path / "meta.pkl", "wb") as f:
            pickle.dump(
                {
                    "window": self.window,
                    "epochs": self.epochs,
                    "batch_size": self.batch_size,
                    "scaler": self._scaler,
                    "residual_std_by_target": self._residual_std_by_target,
                    # Architecture / training config (so a reload rebuilds an
                    # identically-configured view; older artifacts omit these and
                    # fall back to the constructor defaults on load).
                    "hidden_units": self.hidden_units,
                    "num_layers": self.num_layers,
                    "dropout": self.dropout,
                    "recurrent_dropout": self.recurrent_dropout,
                    "dense_units": self.dense_units,
                    "learning_rate": self.learning_rate,
                    "loss": self.loss,
                    "huber_delta": self.huber_delta,
                    # New format marker; legacy single-target artifacts don't have it.
                    "multi_target": True,
                },
                f,
            )

    def load(self, path: Path) -> None:
        from tensorflow import keras  # noqa: PLC0415

        self._model = keras.models.load_model(path / "model.keras")
        with open(path / "meta.pkl", "rb") as f:
            d = pickle.load(f)
        self.window = d["window"]
        self.epochs = d["epochs"]
        self.batch_size = d["batch_size"]
        self._scaler = d["scaler"]
        # Architecture / training config — fall back to current defaults for
        # artifacts saved before these were serialised. The Keras graph itself is
        # restored from model.keras, so these only matter for re-fit / introspection.
        self.hidden_units = d.get("hidden_units", self.hidden_units)
        self.num_layers = d.get("num_layers", self.num_layers)
        self.dropout = d.get("dropout", self.dropout)
        self.recurrent_dropout = d.get("recurrent_dropout", self.recurrent_dropout)
        self.dense_units = d.get("dense_units", self.dense_units)
        self.learning_rate = d.get("learning_rate", self.learning_rate)
        self.loss = d.get("loss", self.loss)
        self.huber_delta = d.get("huber_delta", self.huber_delta)

        if d.get("multi_target"):
            # New multi-output format: residual_std per target dict.
            self._residual_std_by_target = d["residual_std_by_target"]
            self.residual_std = self._residual_std_by_target.get(self.target)
            if self.residual_std is None:
                raise ValueError(
                    f"Multi-target LSTM artifact at {path} has no calibrated "
                    f"residual_std for target={self.target}; available targets: "
                    f"{list(self._residual_std_by_target.keys())}"
                )
        else:
            # Legacy single-output format (Dense(1), temperature-only target).
            # Refuse to load with a non-temperature target since the model
            # architecture can only emit temperature.
            if self.target != "temperature":
                raise ValueError(
                    f"Legacy single-target LSTM artifact at {path} can only "
                    f"predict 'temperature'; cannot serve target={self.target}. "
                    f"Retrain via `python -m scripts.train --model lstm` to get "
                    f"the new multi-target artifact."
                )
            self.residual_std = d["residual_std"]
            self._residual_std_by_target = {"temperature": d["residual_std"]}

        self.fitted = True
