from __future__ import annotations

from pydantic import BaseModel, Field


class ActivateRequest(BaseModel):
    serial: str = Field(min_length=1, max_length=64)
    code: str = Field(min_length=1, max_length=64)
    install_id: str = Field(min_length=1, max_length=128)
    device_hash: str = Field(min_length=1, max_length=128)
    app_version: str | None = Field(default=None, max_length=64)


class CheckRequest(BaseModel):
    serial: str = Field(min_length=1, max_length=64)
    activation_token: str = Field(min_length=1, max_length=128)
    install_id: str = Field(min_length=1, max_length=128)
    device_hash: str = Field(min_length=1, max_length=128)
    app_version: str | None = Field(default=None, max_length=64)


class DeviceReactivationRequest(BaseModel):
    install_id: str = Field(min_length=1, max_length=128)
    device_hash: str = Field(min_length=1, max_length=128)
    app_version: str | None = Field(default=None, max_length=64)


class ResetRequest(BaseModel):
    serial: str = Field(min_length=1, max_length=64)
    note: str | None = Field(default=None, max_length=255)


class AuthenticatedRequest(BaseModel):
    serial: str = Field(min_length=1, max_length=64)
    activation_token: str = Field(min_length=1, max_length=128)
    install_id: str = Field(min_length=1, max_length=128)
    device_hash: str = Field(min_length=1, max_length=128)
    app_version: str | None = Field(default=None, max_length=64)


class UserBootstrapRequest(AuthenticatedRequest):
    language_code: str | None = Field(default=None, max_length=8)


class UserProfileUpdateRequest(AuthenticatedRequest):
    nickname: str | None = Field(default=None, min_length=1, max_length=64)
    language_code: str | None = Field(default=None, max_length=8)
    country_code: str | None = Field(default=None, max_length=8)
    avatar_color: str | None = Field(default=None, max_length=16)


class UserStatisticsRequest(AuthenticatedRequest):
    pass


class TrainingHistoryRequest(AuthenticatedRequest):
    limit: int = Field(default=10, ge=1, le=50)


class TrainingSessionCreateRequest(AuthenticatedRequest):
    mode_seconds: int = Field(ge=1, le=600)
    total_hits: int = Field(ge=0, le=10000)
    average_frequency: float = Field(ge=0.0, le=1000.0)
    best_burst_count: int = Field(ge=0, le=10000)
    best_burst_start_sec: float = Field(ge=0.0, le=600.0)
    started_at_epoch_ms: int | None = Field(default=None, ge=0)
    ended_at_epoch_ms: int | None = Field(default=None, ge=0)


class LeaderboardRequest(AuthenticatedRequest):
    board_key: str | None = Field(default=None, max_length=32)
    mode_seconds: int | None = Field(default=None, ge=1, le=600)
    window: str = Field(default="all", min_length=1, max_length=16)
    limit: int = Field(default=20, ge=1, le=100)
