package com.omh.mini

import android.content.Context
import android.net.ConnectivityManager
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL

/**
 * Plain HttpURLConnection calls to the clinic server — no new libraries,
 * so the APK stays tiny. All calls run off the main thread (callers use
 * plain background threads). The kiosk token is sent as a Bearer header
 * and is never written to a log.
 */

fun isOnline(ctx: Context): Boolean {
    return try {
        @Suppress("DEPRECATION")
        val cm = ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        @Suppress("DEPRECATION")
        val info = cm.activeNetworkInfo
        info != null && info.isConnected
    } catch (_: Exception) {
        false
    }
}

sealed class ApiResult {
    data class Ok(val body: String) : ApiResult()
    data class HttpError(val status: Int, val message: String) : ApiResult()
    data class NetworkError(val message: String) : ApiResult()
}

private fun readBody(conn: HttpURLConnection): String {
    return try {
        val stream = if (conn.responseCode in 200..299) conn.inputStream else conn.errorStream
        if (stream == null) "" else BufferedReader(InputStreamReader(stream)).use { it.readText() }
    } catch (_: Exception) {
        ""
    }
}

private fun serverMessage(body: String, fallback: String): String {
    return try {
        JSONObject(body).optString("error", fallback).ifBlank { fallback }
    } catch (_: Exception) {
        fallback
    }
}

fun fetchRoster(serverUrl: String, token: String, from: String, days: Int = 5): ApiResult {
    return try {
        val url = URL("$serverUrl/api/mini/roster?from=$from&days=$days")
        val conn = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            setRequestProperty("Authorization", "Bearer $token")
            setRequestProperty("Accept", "application/json")
            connectTimeout = 15000
            readTimeout = 20000
        }
        val body = readBody(conn)
        conn.disconnect()
        if (conn.responseCode == 200) ApiResult.Ok(body)
        else ApiResult.HttpError(conn.responseCode, serverMessage(body, "Roster download failed (${conn.responseCode})."))
    } catch (e: Exception) {
        ApiResult.NetworkError("No internet connection: ${e.message ?: "could not reach the server"}")
    }
}

/**
 * POST one queued record. Returns Ok with the server JSON on success
 * (including the tooSoon case, which is a 200 with tooSoon=true).
 * 409 means the server is already processing this clientRef: keep the
 * record queued and try again later. 400/403 means the record will
 * never succeed: the caller marks it failed so it stops blocking the
 * queue. 5xx / no network: keep queued, retry later.
 */
fun postAttendance(serverUrl: String, token: String, record: QueuedRecord, selfieDataUrl: String?): ApiResult {
    return try {
        val url = URL("$serverUrl/api/mini/attendance")
        val payload = JSONObject().apply {
            put("clientRef", record.clientRef)
            put("userId", record.userId)
            put("action", record.action)
            put("token", record.token)
            put("scannedAt", record.scannedAt)
            if (selfieDataUrl != null) put("selfieDataUrl", selfieDataUrl)
        }
        val bytes = payload.toString().toByteArray(Charsets.UTF_8)
        val conn = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            doOutput = true
            setRequestProperty("Authorization", "Bearer $token")
            setRequestProperty("Content-Type", "application/json")
            setRequestProperty("Accept", "application/json")
            connectTimeout = 15000
            readTimeout = 30000
            outputStream.use { it.write(bytes) }
        }
        val body = readBody(conn)
        val code = conn.responseCode
        conn.disconnect()
        when {
            code in 200..299 -> ApiResult.Ok(body)
            code == 409 -> ApiResult.HttpError(409, "Already being processed — retry in a moment.")
            code == 400 || code == 403 -> ApiResult.HttpError(code, serverMessage(body, "The server refused this record ($code)."))
            else -> ApiResult.HttpError(code, serverMessage(body, "Server error ($code). Will retry."))
        }
    } catch (e: Exception) {
        ApiResult.NetworkError("No internet connection: ${e.message ?: "could not reach the server"}")
    }
}

fun fetchHandovers(serverUrl: String, token: String, date: String): ApiResult {
    return try {
        val url = URL("$serverUrl/api/mini/handovers?date=$date")
        val conn = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            setRequestProperty("Authorization", "Bearer $token")
            setRequestProperty("Accept", "application/json")
            connectTimeout = 15000
            readTimeout = 20000
        }
        val body = readBody(conn)
        conn.disconnect()
        if (conn.responseCode == 200) ApiResult.Ok(body)
        else ApiResult.HttpError(conn.responseCode, serverMessage(body, "Handover download failed (${conn.responseCode})."))
    } catch (e: Exception) {
        ApiResult.NetworkError("No internet connection: ${e.message ?: "could not reach the server"}")
    }
}

/**
 * POST one queued handover. The record's payload is the full POST body
 * (including clientKey, the record's clientRef, so a retry returns the
 * original handover instead of duplicating). 200/201 is success —
 * including already:true, which means this exact handover already exists.
 */
fun postHandover(serverUrl: String, token: String, record: QueuedRecord): ApiResult {
    val payload = record.payload
        ?: return ApiResult.HttpError(400, "Handover record is missing its details.")
    return try {
        val url = URL("$serverUrl/api/mini/handovers")
        val bytes = payload.toByteArray(Charsets.UTF_8)
        val conn = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            doOutput = true
            setRequestProperty("Authorization", "Bearer $token")
            setRequestProperty("Content-Type", "application/json")
            setRequestProperty("Accept", "application/json")
            connectTimeout = 15000
            readTimeout = 30000
            outputStream.use { it.write(bytes) }
        }
        val body = readBody(conn)
        val code = conn.responseCode
        conn.disconnect()
        when {
            code in 200..299 -> ApiResult.Ok(body)
            code == 409 -> ApiResult.HttpError(409, "Already being processed — retry in a moment.")
            code == 400 || code == 403 -> ApiResult.HttpError(code, serverMessage(body, "The server refused this handover ($code)."))
            else -> ApiResult.HttpError(code, serverMessage(body, "Server error ($code). Will retry."))
        }
    } catch (e: Exception) {
        ApiResult.NetworkError("No internet connection: ${e.message ?: "could not reach the server"}")
    }
}

/** PATCH /api/mini/handovers/{id}/acknowledge for one staffer. */
fun acknowledgeHandover(serverUrl: String, token: String, handoverId: String, userId: String): ApiResult {
    return try {
        val url = URL("$serverUrl/api/mini/handovers/$handoverId/acknowledge")
        val bytes = JSONObject().apply { put("userId", userId) }.toString().toByteArray(Charsets.UTF_8)
        val conn = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = "PATCH"
            doOutput = true
            setRequestProperty("Authorization", "Bearer $token")
            setRequestProperty("Content-Type", "application/json")
            setRequestProperty("Accept", "application/json")
            connectTimeout = 15000
            readTimeout = 30000
            outputStream.use { it.write(bytes) }
        }
        val body = readBody(conn)
        val code = conn.responseCode
        conn.disconnect()
        when {
            code in 200..299 -> ApiResult.Ok(body)
            else -> ApiResult.HttpError(code, serverMessage(body, "Could not acknowledge ($code)."))
        }
    } catch (e: Exception) {
        ApiResult.NetworkError("No internet connection: ${e.message ?: "could not reach the server"}")
    }
}

private fun bodyFlag(body: String, key: String): Boolean {
    return try { JSONObject(body).optBoolean(key, false) } catch (_: Exception) { false }
}

private fun bodyString(body: String, key: String): String? {
    return try { JSONObject(body).optString(key).ifBlank { null } } catch (_: Exception) { null }
}

/**
 * Sync the queue in order. Stops at the first record the network or the
 * server cannot take right now; records the server refuses for good are
 * marked failed (with the server's reason) so they stop blocking the
 * queue, and syncing continues past them.
 */
data class SyncSummary(val synced: Int, val failed: Int, val stopped: String?)

fun syncQueue(store: Store): SyncSummary {
    var synced = 0
    var failed = 0
    var stopped: String? = null
    // Snapshot the pending list in order; re-read after each success so a
    // record added mid-sync is not lost.
    while (true) {
        val next = store.loadQueue().firstOrNull { it.status == "pending" } ?: break
        val result = if (next.kind == "handover") {
            postHandover(store.serverUrl, store.kioskToken, next)
        } else {
            postAttendance(store.serverUrl, store.kioskToken, next, store.readSelfieDataUrl(next))
        }
        when (result) {
            is ApiResult.Ok -> {
                if (next.kind == "attendance") {
                    store.deleteSelfie(next)
                    // A tooSoon checkout did not happen on the server, so
                    // it must not flip the local signed-in state.
                    if (!bodyFlag(result.body, "tooSoon")) {
                        val action = bodyString(result.body, "action") ?: next.action
                        store.noteSyncedState(next.userId, action == "in")
                    }
                }
                store.removeRecord(next.clientRef)
                synced++
            }
            is ApiResult.HttpError -> {
                if (result.status == 400 || result.status == 403) {
                    store.markFailed(next.clientRef, result.message)
                    failed++
                    continue // do not let one bad record block the rest
                }
                if (result.status == 409) {
                    stopped = result.message
                    break
                }
                stopped = result.message
                break
            }
            is ApiResult.NetworkError -> {
                stopped = result.message
                break
            }
        }
    }
    return SyncSummary(synced, failed, stopped)
}
