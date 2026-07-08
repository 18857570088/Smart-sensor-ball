package com.zclei.smartsensorball.auth

import com.zclei.smartsensorball.network.AppApiConfig
import java.io.BufferedReader
import java.io.InputStream
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import org.json.JSONObject

class ActivationService(
    private val baseUrl: String = AppApiConfig.BASE_URL,
) {
    fun activate(
        serial: String,
        code: String,
        installId: String,
        deviceHash: String,
        appVersion: String,
    ): ActivationApiResult =
        postJson(
            path = "/api/v1/activate",
            payload =
                JSONObject()
                    .put("serial", serial)
                    .put("code", code)
                    .put("install_id", installId)
                    .put("device_hash", deviceHash)
                    .put("app_version", appVersion),
        )

    fun check(
        serial: String,
        activationToken: String,
        installId: String,
        deviceHash: String,
        appVersion: String,
    ): ActivationApiResult =
        postJson(
            path = "/api/v1/check",
            payload =
                JSONObject()
                    .put("serial", serial)
                    .put("activation_token", activationToken)
                    .put("install_id", installId)
                    .put("device_hash", deviceHash)
                    .put("app_version", appVersion),
        )

    fun reactivateByDevice(
        installId: String,
        deviceHash: String,
        appVersion: String,
    ): ActivationApiResult =
        postJson(
            path = "/api/v1/reactivate-by-device",
            payload =
                JSONObject()
                    .put("install_id", installId)
                    .put("device_hash", deviceHash)
                    .put("app_version", appVersion),
        )

    private fun postJson(
        path: String,
        payload: JSONObject,
    ): ActivationApiResult {
        val connection = (URL(baseUrl.trimEnd('/') + path).openConnection() as HttpURLConnection)
        return try {
            connection.requestMethod = "POST"
            connection.connectTimeout = CONNECT_TIMEOUT_MS
            connection.readTimeout = READ_TIMEOUT_MS
            connection.doInput = true
            connection.doOutput = true
            connection.setRequestProperty("Content-Type", "application/json; charset=utf-8")
            connection.outputStream.use { output ->
                output.write(payload.toString().toByteArray(Charsets.UTF_8))
            }

            val responseCode = connection.responseCode
            val body =
                readBody(
                    if (responseCode in 200..299) {
                        connection.inputStream
                    } else {
                        connection.errorStream ?: connection.inputStream
                    },
                )
            parseResponse(responseCode, body)
        } catch (t: Throwable) {
            ActivationApiResult(
                success = false,
                message = t.message ?: "Network request failed.",
                reason = NETWORK_REASON,
            )
        } finally {
            connection.disconnect()
        }
    }

    private fun parseResponse(
        responseCode: Int,
        body: String,
    ): ActivationApiResult {
        val json =
            try {
                JSONObject(body)
            } catch (_: Throwable) {
                null
            }
        val status = json?.optString("status").orEmpty()
        val message =
            json?.optString("message")?.takeIf { it.isNotBlank() }
                ?: if (responseCode in 200..299) "OK" else "Request failed."
        val reason = json?.optString("reason")?.takeIf { it.isNotBlank() }
        return ActivationApiResult(
            success = responseCode in 200..299 && status == "ok",
            message = message,
            reason = reason,
            serial = json?.optString("serial")?.takeIf { it.isNotBlank() },
            activationToken = json?.optString("activation_token")?.takeIf { it.isNotBlank() },
            licenseState = json?.optString("license_state")?.takeIf { it.isNotBlank() },
        )
    }

    private fun readBody(stream: InputStream?): String {
        if (stream == null) {
            return ""
        }
        return stream.use { input ->
            BufferedReader(InputStreamReader(input, Charsets.UTF_8)).use { reader ->
                reader.readText()
            }
        }
    }

    companion object {
        const val NETWORK_REASON = "network_error"
        private const val CONNECT_TIMEOUT_MS = 6_000
        private const val READ_TIMEOUT_MS = 8_000
    }
}
