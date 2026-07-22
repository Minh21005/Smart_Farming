"""POST /forecast -- run all loaded forecasters for one sensor.

Body: { groupId, sensorId, time, horizon }. `time` is the start of the
forecast window; the response contains `horizon` hourly predictions per
forecaster, side-by-side. Detection methods (z-score / seasonal z-score) are
NOT run here.
"""
from __future__ import annotations

import logging

import pandas as pd
from fastapi import APIRouter, HTTPException

from api.registry import FORECASTERS
from api.schemas import ForecastRequest, ForecastResponse, MethodForecast

logger = logging.getLogger(__name__)
router = APIRouter()


@router.post("/forecast", response_model=ForecastResponse)
def forecast(req: ForecastRequest) -> ForecastResponse:
    if not FORECASTERS:
        raise HTTPException(503, "No forecasters registered -- see startup logs")
    if req.horizon < 1:
        raise HTTPException(400, "horizon must be >= 1")

    fcs = FORECASTERS.get(req.sensor_id)
    if not fcs:
        raise HTTPException(
            404,
            f"No forecasters registered for sensorId={req.sensor_id}. "
            f"Available: {list(FORECASTERS.keys())}",
        )

    ts = pd.Timestamp(req.time)
    methods: list[MethodForecast] = []
    for name, fc in fcs.items():
        try:
            result = fc.predict(ts, horizon=req.horizon)
            methods.append(
                MethodForecast(
                    name=name,
                    timestamps=[t.to_pydatetime() for t in result.timestamps],
                    forecast=result.forecast,
                    residual_std=result.residual_std,
                )
            )
        except Exception as e:
            logger.exception("Forecaster %s/%s failed during predict", req.sensor_id, name)
            methods.append(
                MethodForecast(
                    name=name,
                    timestamps=[],
                    forecast=[],
                    residual_std=None,
                    error=str(e),
                )
            )

    return ForecastResponse(time=req.time, horizon=req.horizon, methods=methods)
