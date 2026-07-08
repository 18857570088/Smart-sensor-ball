package com.zclei.smartsensorball.cloud

import com.zclei.smartsensorball.auth.ActivationState
import com.zclei.smartsensorball.model.AppLanguage
import com.zclei.smartsensorball.model.TrainingReport
import com.zclei.smartsensorball.network.AppApiConfig
import java.io.BufferedReader
import java.io.InputStream
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import org.json.JSONArray
import org.json.JSONObject

class CloudSyncService(
    private val baseUrl: String = AppApiConfig.BASE_URL,
) {
    private fun normalizedNullableString(value: String?): String? {
        val normalized = value?.trim().orEmpty()
        if (normalized.isBlank() || normalized.equals("null", ignoreCase = true)) {
            return null
        }
        return normalized
    }

    private fun normalizedAvatarColor(value: String?): String =
        normalizedNullableString(value)?.takeIf { it.startsWith("#") && it.length == 7 } ?: "#145DA0"

    fun bootstrap(
        state: ActivationState,
        language: AppLanguage,
        appVersion: String,
    ): CloudBootstrapResult =
        postJson(
            path = "/api/v1/user/bootstrap",
            payload = authPayload(state, appVersion).put("language_code", language.storageValue),
            parser = ::parseBootstrapResult,
        )

    fun updateProfile(
        state: ActivationState,
        nickname: String,
        language: AppLanguage,
        avatarColor: String?,
        appVersion: String,
    ): CloudBootstrapResult =
        postJson(
            path = "/api/v1/user/profile/update",
            payload =
                authPayload(state, appVersion)
                    .put("nickname", nickname)
                    .put("language_code", language.storageValue)
                    .put("avatar_color", avatarColor ?: JSONObject.NULL),
            parser = ::parseBootstrapResult,
        )

    fun uploadTrainingSession(
        state: ActivationState,
        report: TrainingReport,
        appVersion: String,
    ): CloudSessionUploadResult =
        postJson(
            path = "/api/v1/training/session",
            payload =
                authPayload(state, appVersion)
                    .put("mode_seconds", report.mode.durationSeconds)
                    .put("total_hits", report.totalHits)
                    .put("average_frequency", report.averageFrequency.toDouble())
                    .put("best_burst_count", report.bestBurstCount)
                    .put("best_burst_start_sec", report.bestBurstStartSec.toDouble())
                    .put("ended_at_epoch_ms", report.endedAtEpochMs),
            parser = ::parseSessionUploadResult,
        )

    fun fetchLeaderboard(
        state: ActivationState,
        boardKey: String,
        appVersion: String,
        window: String = "all",
        limit: Int = 20,
    ): CloudLeaderboardResult =
        postJson(
            path = "/api/v1/leaderboard",
            payload =
                authPayload(state, appVersion)
                    .put("board_key", boardKey)
                    .put("window", window)
                    .put("limit", limit),
            parser = { code, json, body ->
                parseLeaderboardResult(
                    responseCode = code,
                    json = json,
                    fallbackBody = body,
                    fallbackBoardKey = boardKey,
                    fallbackWindow = window,
                )
            },
        )

    fun fetchSoundEffects(): CloudSoundEffectCatalog =
        getJson(
            path = "/api/v1/sound-effects",
            parser = ::parseSoundEffectCatalog,
        )

    private fun authPayload(
        state: ActivationState,
        appVersion: String,
    ): JSONObject =
        JSONObject()
            .put("serial", state.serial)
            .put("activation_token", state.activationToken)
            .put("install_id", state.installId)
            .put("device_hash", state.deviceHash)
            .put("app_version", appVersion)

    private fun <T> postJson(
        path: String,
        payload: JSONObject,
        parser: (Int, JSONObject?, String) -> T,
    ): T {
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
            val json =
                try {
                    JSONObject(body)
                } catch (_: Throwable) {
                    null
                }
            parser(responseCode, json, body)
        } catch (t: Throwable) {
            parser(
                0,
                JSONObject()
                    .put("status", "blocked")
                    .put("reason", NETWORK_REASON)
                    .put("message", t.message ?: "Network request failed."),
                t.message ?: "",
            )
        } finally {
            connection.disconnect()
        }
    }

    private fun <T> getJson(
        path: String,
        parser: (Int, JSONObject?, String) -> T,
    ): T {
        val connection = (URL(baseUrl.trimEnd('/') + path).openConnection() as HttpURLConnection)
        return try {
            connection.requestMethod = "GET"
            connection.connectTimeout = CONNECT_TIMEOUT_MS
            connection.readTimeout = READ_TIMEOUT_MS
            connection.doInput = true
            val responseCode = connection.responseCode
            val body =
                readBody(
                    if (responseCode in 200..299) {
                        connection.inputStream
                    } else {
                        connection.errorStream ?: connection.inputStream
                    },
                )
            val json =
                try {
                    JSONObject(body)
                } catch (_: Throwable) {
                    null
                }
            parser(responseCode, json, body)
        } catch (t: Throwable) {
            parser(
                0,
                JSONObject()
                    .put("status", "blocked")
                    .put("reason", NETWORK_REASON)
                    .put("message", t.message ?: "Network request failed."),
                t.message ?: "",
            )
        } finally {
            connection.disconnect()
        }
    }

    private fun parseBootstrapResult(
        responseCode: Int,
        json: JSONObject?,
        fallbackBody: String,
    ): CloudBootstrapResult {
        val success = responseCode in 200..299 && json?.optString("status") == "ok"
        return CloudBootstrapResult(
            success = success,
            message = json?.optString("message").orEmpty().ifBlank { fallbackBody.ifBlank { "Request failed." } },
            reason = json?.optString("reason")?.takeIf { it.isNotBlank() },
            profile = json?.optJSONObject("profile")?.let(::parseProfile),
            statistics = json?.optJSONObject("statistics")?.let(::parseStatistics),
            history = json?.optJSONArray("history")?.let(::parseHistory).orEmpty(),
            achievements = json?.optJSONArray("achievements")?.let(::parseAchievements).orEmpty(),
            tier = json?.optJSONObject("tier")?.let(::parseTier),
            promoted = json?.optBoolean("promoted") == true,
        )
    }

    private fun parseSessionUploadResult(
        responseCode: Int,
        json: JSONObject?,
        fallbackBody: String,
    ): CloudSessionUploadResult {
        val success = responseCode in 200..299 && json?.optString("status") == "ok"
        return CloudSessionUploadResult(
            success = success,
            message = json?.optString("message").orEmpty().ifBlank { fallbackBody.ifBlank { "Request failed." } },
            reason = json?.optString("reason")?.takeIf { it.isNotBlank() },
            sessionId = json?.optLong("session_id")?.takeIf { it > 0L },
            profile = json?.optJSONObject("profile")?.let(::parseProfile),
            statistics = json?.optJSONObject("statistics")?.let(::parseStatistics),
            history = json?.optJSONArray("history")?.let(::parseHistory).orEmpty(),
            achievements = json?.optJSONArray("achievements")?.let(::parseAchievements).orEmpty(),
            tier = json?.optJSONObject("tier")?.let(::parseTier),
            promoted = json?.optBoolean("promoted") == true,
        )
    }

    private fun parseLeaderboardResult(
        responseCode: Int,
        json: JSONObject?,
        fallbackBody: String,
        fallbackBoardKey: String,
        fallbackWindow: String,
    ): CloudLeaderboardResult {
        val success = responseCode in 200..299 && json?.optString("status") == "ok"
        return CloudLeaderboardResult(
            success = success,
            message = json?.optString("message").orEmpty().ifBlank { fallbackBody.ifBlank { "Request failed." } },
            reason = json?.optString("reason")?.takeIf { it.isNotBlank() },
            boardKey = json?.optString("board_key").orEmpty().ifBlank { fallbackBoardKey },
            modeSeconds = json?.optInt("mode_seconds") ?: 0,
            window = json?.optString("window").orEmpty().ifBlank { fallbackWindow },
            top = json?.optJSONArray("top")?.let(::parseLeaderboardEntries).orEmpty(),
            me = json?.optJSONObject("me")?.let(::parseLeaderboardEntry),
        )
    }

    private fun parseSoundEffectCatalog(
        responseCode: Int,
        json: JSONObject?,
        fallbackBody: String,
    ): CloudSoundEffectCatalog {
        val success = responseCode in 200..299 && json?.optString("status") == "ok"
        return CloudSoundEffectCatalog(
            success = success,
            message = json?.optString("message").orEmpty().ifBlank { fallbackBody.ifBlank { "Request failed." } },
            version = json?.optInt("version") ?: 0,
            updatedAt = normalizedNullableString(json?.optString("updated_at")),
            items = json?.optJSONArray("items")?.let(::parseSoundEffects).orEmpty(),
        )
    }

    private fun parseSoundEffects(json: JSONArray): List<CloudSoundEffect> =
        buildList {
            for (index in 0 until json.length()) {
                val item = json.optJSONObject(index) ?: continue
                val id = item.optString("id")
                val url = item.optString("url")
                if (id.isBlank() || url.isBlank()) {
                    continue
                }
                add(
                    CloudSoundEffect(
                        id = id,
                        nameZh = item.optString("name_zh").ifBlank { item.optString("name_en") },
                        nameEn = item.optString("name_en").ifBlank { item.optString("name_zh") },
                        descriptionZh = item.optString("description_zh").ifBlank { item.optString("description_en") },
                        descriptionEn = item.optString("description_en").ifBlank { item.optString("description_zh") },
                        style = item.optString("style"),
                        bpm = item.optInt("bpm"),
                        durationMs = item.optInt("duration_ms"),
                        url = url,
                    ),
                )
            }
        }

    private fun parseProfile(json: JSONObject): CloudUserProfile =
        CloudUserProfile(
            userId = json.optLong("user_id"),
            serial = json.optString("serial"),
            serialMasked = json.optString("serial_masked"),
            nickname = json.optString("nickname"),
            languageCode = json.optString("language_code"),
            countryCode = normalizedNullableString(json.optString("country_code")),
            avatarColor = normalizedAvatarColor(json.optString("avatar_color")),
            currentTier = json.optInt("current_tier").takeIf { it > 0 } ?: 1,
            highestTier = json.optInt("highest_tier").takeIf { it > 0 } ?: 1,
            bestScoreCached = json.optInt("best_score_cached"),
            best30HitsCached = json.optInt("best_30_hits_cached"),
            best60HitsCached = json.optInt("best_60_hits_cached"),
            bestBurstCached = json.optInt("best_burst_cached"),
            longestStreakCached = json.optInt("longest_streak_cached"),
            activeDaysCached = json.optInt("active_days_cached"),
            createdAt = json.optString("created_at").takeIf { it.isNotBlank() },
            lastSeenAt = json.optString("last_seen_at").takeIf { it.isNotBlank() },
        )

    private fun parseStatistics(json: JSONObject): CloudUserStatistics =
        CloudUserStatistics(
            totalSessions = json.optInt("total_sessions"),
            totalHits = json.optInt("total_hits"),
            best30Hits = json.optInt("best_30_hits"),
            best60Hits = json.optInt("best_60_hits"),
            average30Frequency = json.optDouble("average_30_frequency").toFloat(),
            average60Frequency = json.optDouble("average_60_frequency").toFloat(),
            personalBestHits = json.optInt("personal_best_hits"),
            bestBurstRecord = json.optInt("best_burst_record"),
            bestAverageFrequency = json.optDouble("best_average_frequency").toFloat(),
            activeDays = json.optInt("active_days"),
            currentStreak = json.optInt("current_streak"),
            longestStreak = json.optInt("longest_streak"),
        )

    private fun parseTier(json: JSONObject): CloudTierProgress =
        CloudTierProgress(
            level = json.optInt("level").takeIf { it > 0 } ?: 1,
            key = json.optString("key").ifBlank { "beginner" },
            bestHits = json.optInt("best_hits"),
            nextLevel = json.optInt("next_level").takeIf { it > 0 },
            nextKey = json.optString("next_key").takeIf { it.isNotBlank() },
            nextHits = json.optInt("next_hits").takeIf { it > 0 },
            progressHits = json.optInt("progress_hits"),
            progressTargetHits = json.optInt("progress_target_hits"),
        )

    private fun parseAchievements(json: JSONArray): List<CloudAchievementItem> =
        buildList {
            for (index in 0 until json.length()) {
                val item = json.optJSONObject(index) ?: continue
                add(
                    CloudAchievementItem(
                        key = item.optString("key"),
                        metric = item.optString("metric"),
                        goal = item.optInt("goal"),
                        progress = item.optInt("progress"),
                        unlocked = item.optBoolean("unlocked"),
                        unlockedAt = item.optString("unlocked_at").takeIf { it.isNotBlank() },
                        sortOrder = item.optInt("sort_order"),
                    ),
                )
            }
        }

    private fun parseHistory(json: JSONArray): List<CloudTrainingHistoryItem> =
        buildList {
            for (index in 0 until json.length()) {
                val item = json.optJSONObject(index) ?: continue
                add(
                    CloudTrainingHistoryItem(
                        sessionId = item.optLong("session_id"),
                        modeSeconds = item.optInt("mode_seconds"),
                        totalHits = item.optInt("total_hits"),
                        averageFrequency = item.optDouble("average_frequency").toFloat(),
                        bestBurstCount = item.optInt("best_burst_count"),
                        bestBurstStartSec = item.optDouble("best_burst_start_sec").toFloat(),
                        startedAt = item.optString("started_at").takeIf { it.isNotBlank() },
                        endedAt = item.optString("ended_at").takeIf { it.isNotBlank() },
                    ),
                )
            }
        }

    private fun parseLeaderboardEntries(json: JSONArray): List<CloudLeaderboardEntry> =
        buildList {
            for (index in 0 until json.length()) {
                val item = json.optJSONObject(index) ?: continue
                add(parseLeaderboardEntry(item))
            }
        }

    private fun parseLeaderboardEntry(json: JSONObject): CloudLeaderboardEntry =
        CloudLeaderboardEntry(
            rank = json.optInt("rank"),
            userId = json.optLong("user_id"),
            nickname = json.optString("nickname"),
            serialMasked = json.optString("serial_masked"),
            countryCode = normalizedNullableString(json.optString("country_code")),
            tierLevel = json.optInt("tier_level").takeIf { it > 0 } ?: 1,
            tierKey = json.optString("tier_key").ifBlank { "beginner" },
            bestHits = json.optInt("best_hits"),
            averageFrequency = json.optDouble("average_frequency").toFloat(),
            bestBurstCount = json.optInt("best_burst_count"),
            bestBurstStartSec = json.optDouble("best_burst_start_sec").toFloat(),
            endedAt = json.optString("ended_at").takeIf { it.isNotBlank() },
            isMe = json.optBoolean("is_me"),
        )

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
        private const val UPLOAD_READ_TIMEOUT_MS = 30_000
    }
}
