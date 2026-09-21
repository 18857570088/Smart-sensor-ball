package com.zclei.smartsensorball.auth

data class ActivationState(
    val serial: String,
    val activationToken: String,
    val installId: String,
    val deviceHash: String,
    val activatedAtEpochMs: Long,
    val lastCheckAtEpochMs: Long,
)
