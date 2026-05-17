from __future__ import annotations

from dataclasses import dataclass
from typing import Any


@dataclass(frozen=True)
class TierDefinition:
    level: int
    key: str
    min_best_hits: int


@dataclass(frozen=True)
class AchievementDefinition:
    key: str
    metric: str
    goal: int
    sort_order: int


TIERS: tuple[TierDefinition, ...] = (
    TierDefinition(level=1, key="beginner", min_best_hits=0),
    TierDefinition(level=2, key="prospect", min_best_hits=40),
    TierDefinition(level=3, key="contender", min_best_hits=50),
    TierDefinition(level=4, key="striker", min_best_hits=60),
    TierDefinition(level=5, key="challenger", min_best_hits=70),
    TierDefinition(level=6, key="elite", min_best_hits=80),
    TierDefinition(level=7, key="master", min_best_hits=90),
    TierDefinition(level=8, key="legend", min_best_hits=100),
    TierDefinition(level=9, key="champion", min_best_hits=120),
)


ACHIEVEMENTS: tuple[AchievementDefinition, ...] = (
    AchievementDefinition("first_training", "total_sessions", 1, 10),
    AchievementDefinition("sessions_5", "total_sessions", 5, 20),
    AchievementDefinition("sessions_15", "total_sessions", 15, 30),
    AchievementDefinition("sessions_30", "total_sessions", 30, 40),
    AchievementDefinition("hits_100", "total_hits", 100, 50),
    AchievementDefinition("hits_500", "total_hits", 500, 60),
    AchievementDefinition("hits_1000", "total_hits", 1000, 70),
    AchievementDefinition("hits_5000", "total_hits", 5000, 80),
    AchievementDefinition("best_30_40", "best_30_hits", 40, 90),
    AchievementDefinition("best_30_60", "best_30_hits", 60, 100),
    AchievementDefinition("best_30_80", "best_30_hits", 80, 110),
    AchievementDefinition("best_30_100", "best_30_hits", 100, 120),
    AchievementDefinition("best_60_90", "best_60_hits", 90, 130),
    AchievementDefinition("best_60_120", "best_60_hits", 120, 140),
    AchievementDefinition("best_60_150", "best_60_hits", 150, 150),
    AchievementDefinition("best_60_180", "best_60_hits", 180, 160),
    AchievementDefinition("burst_4", "best_burst_record", 4, 170),
    AchievementDefinition("burst_6", "best_burst_record", 6, 180),
    AchievementDefinition("burst_8", "best_burst_record", 8, 190),
    AchievementDefinition("burst_10", "best_burst_record", 10, 200),
    AchievementDefinition("streak_3", "longest_streak", 3, 210),
    AchievementDefinition("streak_7", "longest_streak", 7, 220),
    AchievementDefinition("streak_14", "longest_streak", 14, 230),
    AchievementDefinition("streak_30", "longest_streak", 30, 240),
)


def tier_for_best_hits(best_hits: int) -> TierDefinition:
    current = TIERS[0]
    for tier in TIERS:
        if best_hits >= tier.min_best_hits:
            current = tier
        else:
            break
    return current


def tier_for_level(level: int) -> TierDefinition:
    for tier in TIERS:
        if tier.level == level:
            return tier
    return TIERS[0]


def tier_snapshot(best_hits: int) -> dict[str, Any]:
    tier = tier_for_best_hits(best_hits)
    next_tier = next((candidate for candidate in TIERS if candidate.level == tier.level + 1), None)
    return {
        "level": tier.level,
        "key": tier.key,
        "best_hits": best_hits,
        "next_level": next_tier.level if next_tier else None,
        "next_key": next_tier.key if next_tier else None,
        "next_hits": next_tier.min_best_hits if next_tier else None,
        "progress_hits": max(0, best_hits - tier.min_best_hits),
        "progress_target_hits": (next_tier.min_best_hits - tier.min_best_hits) if next_tier else 0,
    }


def achievement_progress_rows(metrics: dict[str, int]) -> list[dict[str, Any]]:
    rows: list[dict[str, Any]] = []
    for item in ACHIEVEMENTS:
        progress = int(metrics.get(item.metric, 0))
        rows.append(
            {
                "key": item.key,
                "metric": item.metric,
                "goal": item.goal,
                "progress": max(0, progress),
                "unlocked": progress >= item.goal,
                "sort_order": item.sort_order,
            }
        )
    return rows
