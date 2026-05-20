from __future__ import annotations

import os
from dataclasses import dataclass


def _require(name: str) -> str:
    value = os.getenv(name, "").strip()
    if not value:
        raise RuntimeError(f"Missing required environment variable: {name}")
    return value


@dataclass(frozen=True)
class Settings:
    app_name: str
    app_host: str
    app_port: int
    db_host: str
    db_port: int
    db_name: str
    db_user: str
    db_password: str
    code_pepper: str
    admin_token: str
    default_product_code: str
    upload_dir: str


def load_settings() -> Settings:
    return Settings(
        app_name=os.getenv("APP_NAME", "sensorball-auth").strip() or "sensorball-auth",
        app_host=os.getenv("APP_HOST", "127.0.0.1").strip() or "127.0.0.1",
        app_port=int(os.getenv("APP_PORT", "8013")),
        db_host=_require("DB_HOST"),
        db_port=int(os.getenv("DB_PORT", "3306")),
        db_name=_require("DB_NAME"),
        db_user=_require("DB_USER"),
        db_password=_require("DB_PASSWORD"),
        code_pepper=_require("CODE_PEPPER"),
        admin_token=_require("ADMIN_TOKEN"),
        default_product_code=os.getenv("DEFAULT_PRODUCT_CODE", "SSB01").strip() or "SSB01",
        upload_dir=os.getenv("UPLOAD_DIR", "uploads").strip() or "uploads",
    )

