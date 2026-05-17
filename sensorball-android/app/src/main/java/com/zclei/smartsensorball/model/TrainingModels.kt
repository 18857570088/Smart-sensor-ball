package com.zclei.smartsensorball.model

enum class TrainingMode(val durationSeconds: Int, val label: String) {
    Seconds30(durationSeconds = 30, label = "30 sec"),
    Seconds60(durationSeconds = 60, label = "60 sec"),
    Burst10(durationSeconds = 10, label = "10 sec burst"),
    Burst15(durationSeconds = 15, label = "15 sec burst"),
}

enum class AppLanguage(val storageValue: String) {
    Chinese(storageValue = "zh"),
    English(storageValue = "en"),
    French(storageValue = "fr"),
    Thai(storageValue = "th");

    companion object {
        fun fromStorage(value: String?): AppLanguage =
            entries.firstOrNull { it.storageValue == value } ?: Chinese
    }
}

enum class TrainingPhase {
    Idle,
    Countdown,
    Running,
    Finished,
    Error,
}

data class TrainingReport(
    val mode: TrainingMode,
    val totalHits: Int,
    val averageFrequency: Float,
    val bestBurstCount: Int,
    val bestBurstStartSec: Float,
    val endedAtEpochMs: Long,
)

data class TrainerUiState(
    val phase: TrainingPhase = TrainingPhase.Idle,
    val selectedMode: TrainingMode = TrainingMode.Seconds30,
    val countdownValue: Int? = null,
    val hitCount: Int = 0,
    val remainingMillis: Long = 0L,
    val latestReport: TrainingReport? = null,
    val reportHistory: List<TrainingReport> = emptyList(),
    val errorMessage: String? = null,
    val isBusy: Boolean = false,
)
