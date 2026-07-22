"""Anomaly detectors — robust statistical methods + composition wrappers.

Detectors answer "is this point anomalous?" against a single observation.
For ML-forecast-based detection, see `ResidualDetector` which wraps a
`Forecaster` and applies the |residual|/sigma > k rule.
"""
from .residual import ResidualDetector
from .seasonal_zscore import SeasonalZScoreDetector
from .zscore import ZScoreDetector

__all__ = ["ZScoreDetector", "SeasonalZScoreDetector", "ResidualDetector"]
