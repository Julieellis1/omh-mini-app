package com.omh.mini

import android.content.Context
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.UUID

/** All dates and times on this phone are Lagos time. */
val LAGOS_TZ: TimeZone = TimeZone.getTimeZone("Africa/Lagos")

fun lagosToday(): String =
    SimpleDateFormat("yyyy-MM-dd", Locale.US).apply { timeZone = LAGOS_TZ }.format(Date())

/** ISO 8601 with offset, e.g. 2026-11-09T07:00:00+01:00 — what the server's scannedAt accepts. */
fun lagosNowIso(): String =
    SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssXXX", Locale.US).apply { timeZone = LAGOS_TZ }.format(Date())

fun lagosClock(iso: String): String {
    val patterns = arrayOf(
        "yyyy-MM-dd'T'HH:mm:ss.SSSXXX",
        "yyyy-MM-dd'T'HH:mm:ssXXX",
        "yyyy-MM-dd'T'HH:mm:ss.SSS'Z'",
        "yyyy-MM-dd'T'HH:mm:ss'Z'"
    )
    for (p in patterns) {
        try {
            val d = SimpleDateFormat(p, Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }.parse(iso)
            if (d != null) return SimpleDateFormat("HH:mm", Locale.US).apply { timeZone = LAGOS_TZ }.format(d)
        } catch (_: Exception) {
        }
    }
    return iso
}

// ---- roster model (matches GET /api/mini/roster) ----

data class Shift(
    val dutyShiftId: String,
    val name: String,
    val code: String,
    val startTime: String,
    val endTime: String,
    val ward: String?,
    val isOnCall: Boolean
)

data class StaffMember(
    val id: String,
    val name: String,
    val department: String?,
    val shifts: List<Shift>
)

data class RosterDay(val date: String, val staff: List<StaffMember>)

fun parseRoster(json: String): List<RosterDay> {
    val out = ArrayList<RosterDay>()
    val root = JSONObject(json)
    val days = root.optJSONArray("roster") ?: return out
    for (i in 0 until days.length()) {
        val day = days.getJSONObject(i)
        val staff = ArrayList<StaffMember>()
        val arr = day.optJSONArray("staff") ?: JSONArray()
        for (j in 0 until arr.length()) {
            val p = arr.getJSONObject(j)
            val shifts = ArrayList<Shift>()
            val sarr = p.optJSONArray("shifts") ?: JSONArray()
            for (k in 0 until sarr.length()) {
                val s = sarr.getJSONObject(k)
                shifts.add(
                    Shift(
                        dutyShiftId = s.optString("dutyShiftId"),
                        name = s.optString("name"),
                        code = s.optString("code"),
                        startTime = s.optString("startTime"),
                        endTime = s.optString("endTime"),
                        ward = if (s.isNull("ward")) null else s.optString("ward"),
                        isOnCall = s.optBoolean("isOnCall", false)
                    )
                )
            }
            staff.add(
                StaffMember(
                    id = p.optString("id"),
                    name = p.optString("name"),
                    department = if (p.isNull("department")) null else p.optString("department"),
                    shifts = shifts
                )
            )
        }
        out.add(RosterDay(date = day.optString("date"), staff = staff))
    }
    return out
}

// ---- one queued attendance record ----

data class QueuedRecord(
    val clientRef: String,
    val userId: String,
    val userName: String,
    val action: String, // "in" or "out" (advisory: the server toggles from the open record)
    val token: String, // scanned facility QR value, sent back verbatim
    val scannedAt: String, // ISO with offset: the REAL sign-in time
    val selfieFile: String?, // filename in filesDir, null for sign-out
    val status: String, // "pending" or "failed"
    val error: String?
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("clientRef", clientRef)
        put("userId", userId)
        put("userName", userName)
        put("action", action)
        put("token", token)
        put("scannedAt", scannedAt)
        if (selfieFile != null) put("selfieFile", selfieFile)
        put("status", status)
        if (error != null) put("error", error)
    }

    companion object {
        fun fromJson(o: JSONObject) = QueuedRecord(
            clientRef = o.optString("clientRef"),
            userId = o.optString("userId"),
            userName = o.optString("userName"),
            action = o.optString("action", "in"),
            token = o.optString("token"),
            scannedAt = o.optString("scannedAt"),
            selfieFile = if (o.has("selfieFile")) o.optString("selfieFile") else null,
            status = o.optString("status", "pending"),
            error = if (o.has("error")) o.optString("error") else null
        )
    }
}

/**
 * All on-phone storage. Settings live in SharedPreferences; the roster is
 * the raw server JSON (5 days, small); the queue is a JSON file in the
 * app's private files dir, with each selfie as a separate JPEG file next
 * to it so a 60 KB photo never sits inside a preferences string.
 */
class Store(private val ctx: Context) {

    private val prefs = ctx.getSharedPreferences("omh_mini", Context.MODE_PRIVATE)
    private val queueFile: File get() = File(ctx.filesDir, "queue.json")

    // ---- settings (super admin, one time) ----

    var serverUrl: String
        get() = prefs.getString("server_url", "https://omh.teta.dpdns.org") ?: "https://omh.teta.dpdns.org"
        set(v) = prefs.edit().putString("server_url", v.trim().trimEnd('/')).apply()

    /** The facility kiosk token. Stored only on this phone, never logged, never in the repo. */
    var kioskToken: String
        get() = prefs.getString("kiosk_token", "") ?: ""
        set(v) = prefs.edit().putString("kiosk_token", v.trim()).apply()

    val isConfigured: Boolean get() = kioskToken.isNotEmpty()

    // ---- roster (5 days, downloaded whenever internet is available) ----

    var rosterJson: String
        get() = prefs.getString("roster_json", "") ?: ""
        set(v) = prefs.edit().putString("roster_json", v).apply()

    var rosterFetchedAt: Long
        get() = prefs.getLong("roster_fetched_at", 0L)
        set(v) = prefs.edit().putLong("roster_fetched_at", v).apply()

    fun rosterDays(): List<RosterDay> =
        if (rosterJson.isBlank()) emptyList()
        else try { parseRoster(rosterJson) } catch (_: Exception) { emptyList() }

    fun todayStaff(): List<StaffMember> {
        val today = lagosToday()
        return rosterDays().firstOrNull { it.date == today }?.staff ?: emptyList()
    }

    // ---- offline queue ----

    fun loadQueue(): MutableList<QueuedRecord> {
        if (!queueFile.exists()) return ArrayList()
        return try {
            val arr = JSONArray(queueFile.readText())
            val out = ArrayList<QueuedRecord>()
            for (i in 0 until arr.length()) out.add(QueuedRecord.fromJson(arr.getJSONObject(i)))
            out
        } catch (_: Exception) {
            ArrayList()
        }
    }

    fun saveQueue(queue: List<QueuedRecord>) {
        val arr = JSONArray()
        for (r in queue) arr.put(r.toJson())
        queueFile.writeText(arr.toString())
    }

    fun pendingCount(): Int = loadQueue().count { it.status == "pending" }
    fun failedCount(): Int = loadQueue().count { it.status == "failed" }

    fun addRecord(record: QueuedRecord) {
        val q = loadQueue()
        q.add(record)
        saveQueue(q)
    }

    fun removeRecord(clientRef: String) {
        saveQueue(loadQueue().filter { it.clientRef != clientRef })
    }

    fun markFailed(clientRef: String, message: String) {
        val q = loadQueue()
        for (i in q.indices) {
            if (q[i].clientRef == clientRef) q[i] = q[i].copy(status = "failed", error = message)
        }
        saveQueue(q)
    }

    fun clearFailed() {
        val q = loadQueue().filter { it.status != "failed" }
        saveQueue(q)
    }

    // ---- selfies (one JPEG file per queued sign-in) ----

    fun saveSelfie(clientRef: String, jpeg: ByteArray): String {
        val name = "selfie_$clientRef.jpg"
        File(ctx.filesDir, name).writeBytes(jpeg)
        return name
    }

    fun readSelfieDataUrl(record: QueuedRecord): String? {
        val name = record.selfieFile ?: return null
        val f = File(ctx.filesDir, name)
        if (!f.exists()) return null
        val bytes = f.readBytes()
        if (bytes.isEmpty()) return null
        return "data:image/jpeg;base64," + Base64.encodeToString(bytes, Base64.NO_WRAP)
    }

    fun deleteSelfie(record: QueuedRecord) {
        val name = record.selfieFile ?: return
        try { File(ctx.filesDir, name).delete() } catch (_: Exception) { }
    }

    fun newClientRef(): String = UUID.randomUUID().toString()
}
