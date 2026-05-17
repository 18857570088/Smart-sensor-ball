package com.zclei.smartsensorball.auth

data class ActivationState(
    val serial: String,
    val activationToken: String,
    val installId: String,
    val deviceHash: String,
    val activatedAtEpochMs: Long,
    val lastCheckAtEpochMs: Long,
)

data class ActivationApiResult(
    val success: Boolean,
    val message: String,
    val reason: String? = null,
    val serial: String? = null,
    val activationToken: String? = null,
    val licenseState: String? = null,
)
