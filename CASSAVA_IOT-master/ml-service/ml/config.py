"""Centralized config for the ml-service. Reads from .env (loaded once at import)."""
import os
from pathlib import Path

from dotenv import load_dotenv

load_dotenv()

MONGO_URI: str = os.getenv("MONGO_URI", "mongodb://112.137.129.218:27017")
MONGO_DB: str = os.getenv("MONGO_DB", "iot_agriculture")
SENSOR_COLLECTION: str = "sensor_value"

# DEFAULT_GROUP_ID is hardcoded in edge/edge_to_mongo_weather.c — every weather
# row published by the pi3 binary carries this groupId.
GROUP_ID: str = os.getenv("GROUP_ID", "69e35b13e405c05c3dab13c9")
SENSOR_ID: str = os.getenv("SENSOR_ID", "temperature")

ANOMALY_K: float = float(os.getenv("ANOMALY_K", "3.0"))
RESAMPLE_FREQ: str = os.getenv("RESAMPLE_FREQ", "1h")
API_PORT: int = int(os.getenv("API_PORT", "8082"))

ROOT_DIR: Path = Path(__file__).resolve().parent.parent
ARTIFACTS_DIR: Path = ROOT_DIR / "artifacts"
ARTIFACTS_DIR.mkdir(exist_ok=True)
