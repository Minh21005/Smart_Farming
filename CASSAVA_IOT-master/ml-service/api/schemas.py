"""Pydantic request/response models for the API."""
from __future__ import annotations

from datetime import datetime
from typing import Optional

from pydantic import BaseModel, Field


# ---- /detect -----------------------------------------------------------


class DetectRequest(BaseModel):
    group_id: str = Field(alias="groupId")
    sensor_id: str = Field(alias="sensorId")
    time: datetime
    value: float

    class Config:
        populate_by_name = True


class MethodVerdict(BaseModel):
    name: str
    predicted: Optional[float] = None
    residual: Optional[float] = None
    score: float
    is_anomaly: bool


class DetectResponse(BaseModel):
    time: datetime
    actual: float
    is_anomaly: bool  # OR over methods
    methods: list[MethodVerdict]


# ---- /forecast ---------------------------------------------------------


class ForecastRequest(BaseModel):
    group_id: str = Field(alias="groupId")
    sensor_id: str = Field(alias="sensorId")
    time: datetime
    horizon: int = 1  # number of hourly steps to forecast

    class Config:
        populate_by_name = True


class MethodForecast(BaseModel):
    name: str
    timestamps: list[datetime]
    forecast: list[float]
    residual_std: Optional[float] = None
    error: Optional[str] = None  # populated if this method failed


class ForecastResponse(BaseModel):
    time: datetime
    horizon: int
    methods: list[MethodForecast]


# ---- /models -----------------------------------------------------------


class ModelInfo(BaseModel):
    name: str
    role: str  # "detector" | "forecaster"
    loaded: bool
    residual_std: Optional[float] = None
