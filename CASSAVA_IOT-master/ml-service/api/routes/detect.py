"""POST /detect -- run sensor-specific detectors against a single (time, value).

Each weather sensor has its own fitted detectors (zscore + seasonal_zscore).
We look them up by `sensorId`, run them all, and return per-method verdicts
plus a combined `is_anomaly` (OR over methods).

Forecasters are NOT run here; for forecasting see /forecast.
"""
from __future__ import annotations

import logging

import pandas as pd
from fastapi import APIRouter, HTTPException

from api.registry import DETECTORS
from api.schemas import DetectRequest, DetectResponse, MethodVerdict

logger = logging.getLogger(__name__)
router = APIRouter()


@router.post("/detect", response_model=DetectResponse)
def detect(req: DetectRequest) -> DetectResponse:
    if not DETECTORS:
        raise HTTPException(503, "No detectors registered -- see startup logs")

    dets = DETECTORS.get(req.sensor_id)
    if not dets:
        raise HTTPException(
            404,
            f"No detectors registered for sensorId={req.sensor_id}. "
            f"Available: {list(DETECTORS.keys())}",
        )

    ts = pd.Timestamp(req.time)
    verdicts: list[MethodVerdict] = []
    any_anomaly = False
    for name, det in dets.items():
        try:
            result = det.score(req.value, ts, k=3.0)
            verdicts.append(
                MethodVerdict(
                    name=name,
                    predicted=result.predicted,
                    residual=result.residual,
                    score=result.score,
                    is_anomaly=result.is_anomaly,
                )
            )
            any_anomaly = any_anomaly or result.is_anomaly
        except Exception:
            logger.exception("Detector %s/%s failed during scoring", req.sensor_id, name)
            verdicts.append(MethodVerdict(name=name, score=0.0, is_anomaly=False))

    return DetectResponse(
        time=req.time,
        actual=req.value,
        is_anomaly=any_anomaly,
        methods=verdicts,
    )
