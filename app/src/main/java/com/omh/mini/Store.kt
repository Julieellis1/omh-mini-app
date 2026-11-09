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
    val shifts: List<Shift>,
    // From the server: true when this person has an Attendance row for
    // that date with no check-out yet. False when the field is absent
    // (roster downloaded before the server knew the flag).
    val signedIn: Boolean = false,
    val role: String = "STAFF"
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
                    shifts = shifts,
                    signedIn = p.optBoolean("signedIn", false),
                    role = p.optString("role", "STAFF")
                )
            )
        }
        out.add(RosterDay(date = day.optString("date"), staff = staff))
    }
    return out
}

// ---- handover model (matches GET /api/mini/handovers) ----

data class HandoverPatient(val label: String, val note: String, val patientId: String?)

data class Handover(
    val id: String,
    val fromDutyShiftId: String,
    val fromDutyName: String,
    val toDutyShiftId: String,
    val toDutyName: String,
    val ward: String?,
    val date: String,
    val priority: String, // NORMAL | URGENT
    val status: String, // PENDING | ACKNOWLEDGED
    val situation: String,
    val background: String,
    val assessment: String,
    val recommendation: String,
    val patients: List<HandoverPatient>,
    val createdById: String,
    val createdByName: String,
    val acknowledgedByName: String?,
    val acknowledgedAt: String?,
    val createdAt: String,
    val queued: Boolean = false // still on this phone, not on the server yet
)

fun parseHandovers(json: String): List<Handover> {
    val out = ArrayList<Handover>()
    val root = try { JSONObject(json) } catch (_: Exception) { return out }
    val arr = root.optJSONArray("handovers") ?: return out
    for (i in 0 until arr.length()) {
        val h = arr.getJSONObject(i)
        val content = h.optJSONObject("content") ?: JSONObject()
        val pats = ArrayList<HandoverPatient>()
        val parr = content.optJSONArray("patients") ?: JSONArray()
        for (j in 0 until parr.length()) {
            val p = parr.getJSONObject(j)
            pats.add(
                HandoverPatient(
                    label = p.optString("label"),
                    note = p.optString("note"),
                    patientId = if (p.isNull("patientId")) null else p.optString("patientId")
                )
            )
        }
        val createdBy = h.optJSONObject("createdBy")
        val ackBy = h.optJSONObject("acknowledgedBy")
        out.add(
            Handover(
                id = h.optString("id"),
                fromDutyShiftId = h.optString("fromDutyShiftId"),
                fromDutyName = h.optJSONObject("fromDutyShift")?.optString("name") ?: "",
                toDutyShiftId = h.optString("toDutyShiftId"),
                toDutyName = h.optJSONObject("toDutyShift")?.optString("name") ?: "",
                ward = if (h.isNull("ward")) null else h.optString("ward"),
                date = h.optString("date").take(10),
                priority = h.optString("priority", "NORMAL"),
                status = h.optString("status", "PENDING"),
                situation = content.optString("situation"),
                background = content.optString("background"),
                assessment = content.optString("assessment"),
                recommendation = content.optString("recommendation"),
                patients = pats,
                createdById = h.optString("createdById"),
                createdByName = createdBy?.optString("name") ?: "",
                acknowledgedByName = ackBy?.optString("name"),
                acknowledgedAt = if (h.isNull("acknowledgedAt")) null else h.optString("acknowledgedAt"),
                createdAt = h.optString("createdAt")
            )
        )
    }
    return out
}

// ---- one queued attendance record ----

data class QueuedRecord(
    val clientRef: String,
    val userId: String,
    val userName: String,
    val action: String, // "in" / "out" (attendance), or "handover"
    val token: String, // scanned facility QR value, sent back verbatim
    val scannedAt: String, // ISO with offset: the REAL record time
    val selfieFile: String?, // filename in filesDir, null for sign-out
    val status: String, // "pending" or "failed"
    val error: String?,
    val kind: String = "attendance", // "attendance" or "handover"
    val payload: String? = null, // handover POST body JSON (kind handover only)
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
        put("kind", kind)
        if (payload != null) put("payload", payload)
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
            error = if (o.has("error")) o.optString("error") else null,
            kind = o.optString("kind", "attendance"),
            payload = if (o.has("payload")) o.optString("payload") else null
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
        set(v) {
            prefs.edit().putString("roster_json", v).apply()
            // A fresh download is newer server truth: drop the local
            // signed-in overrides (the flag in the roster now carries it).
            clearSignedInOverrides()
        }

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

    /** Duty name for a shift id, from the downloaded roster (any day). */
    fun dutyName(dutyShiftId: String): String {
        for (day in rosterDays()) {
            for (p in day.staff) {
                val s = p.shifts.firstOrNull { it.dutyShiftId == dutyShiftId }
                if (s != null) return s.name
            }
        }
        return ""
    }

    // ---- signed-in state (one button per name, never both) ----

    // State confirmed by a successful sync, kept until the next roster
    // refresh (which carries fresh server truth). Stored as
    // {"date": "YYYY-MM-DD", "states": {"userId": true/false}}.
    private fun signedInOverride(userId: String): Boolean? {
        return try {
            val root = JSONObject(prefs.getString("signed_in_overrides", "") ?: "")
            if (root.optString("date") != lagosToday()) return null
            val states = root.optJSONObject("states") ?: return null
            if (!states.has(userId)) null else states.optBoolean(userId)
        } catch (_: Exception) {
            null
        }
    }

    fun noteSyncedState(userId: String, signedIn: Boolean) {
        val today = lagosToday()
        val root = try {
            JSONObject(prefs.getString("signed_in_overrides", "") ?: "")
        } catch (_: Exception) {
            JSONObject()
        }
        val states = if (root.optString("date") == today) root.optJSONObject("states") ?: JSONObject() else JSONObject()
        states.put(userId, signedIn)
        val out = JSONObject().apply { put("date", today); put("states", states) }
        prefs.edit().putString("signed_in_overrides", out.toString()).apply()
    }

    fun clearSignedInOverrides() {
        prefs.edit().remove("signed_in_overrides").apply()
    }

    /**
     * Is this person signed in right now? The server flag from the
     * downloaded roster first, then any state confirmed by a sync since,
     * then the pending offline queue — the newest truth. A queued sign-in
     * counts as signed in even before it syncs, and a queued sign-out
     * counts as signed out. Failed records never took effect, so they do
     * not change the state.
     */
    fun isSignedIn(userId: String): Boolean {
        var signedIn = todayStaff().firstOrNull { it.id == userId }?.signedIn ?: false
        signedInOverride(userId)?.let { signedIn = it }
        for (r in loadQueue()) {
            if (r.userId == userId && r.status == "pending" && r.kind == "attendance") {
                signedIn = r.action == "in"
            }
        }
        return signedIn
    }

    // ---- handovers (downloaded whenever internet is available) ----

    var handoverJson: String
        get() = prefs.getString("handover_json", "") ?: ""
        set(v) = prefs.edit().putString("handover_json", v).apply()

    var handoverFetchedAt: Long
        get() = prefs.getLong("handover_fetched_at", 0L)
        set(v) = prefs.edit().putLong("handover_fetched_at", v).apply()

    fun handoversFor(date: String): List<Handover> {
        if (handoverJson.isBlank()) return emptyList()
        return try {
            parseHandovers(handoverJson).filter { it.date == date }
        } catch (_: Exception) {
            emptyList()
        }
    }

    /** Handovers written on this phone and still waiting to sync. */
    fun queuedHandovers(): List<Handover> {
        val out = ArrayList<Handover>()
        for (r in loadQueue()) {
            if (r.kind != "handover" || r.status != "pending" || r.payload == null) continue
            try {
                val p = JSONObject(r.payload)
                val pats = ArrayList<HandoverPatient>()
                val parr = p.optJSONArray("patients") ?: JSONArray()
                for (j in 0 until parr.length()) {
                    val x = parr.getJSONObject(j)
                    pats.add(HandoverPatient(x.optString("label"), x.optString("note"), null))
                }
                out.add(
                    Handover(
                        id = "",
                        fromDutyShiftId = p.optString("fromDutyShiftId"),
                        fromDutyName = dutyName(p.optString("fromDutyShiftId")),
                        toDutyShiftId = p.optString("toDutyShiftId"),
                        toDutyName = dutyName(p.optString("toDutyShiftId")),
                        ward = if (p.isNull("ward")) null else p.optString("ward"),
                        date = p.optString("date"),
                        priority = p.optString("priority", "NORMAL"),
                        status = "PENDING",
                        situation = p.optString("situation"),
                        background = p.optString("background"),
                        assessment = p.optString("assessment"),
                        recommendation = p.optString("recommendation"),
                        patients = pats,
                        createdById = r.userId,
                        createdByName = r.userName,
                        acknowledgedByName = null,
                        acknowledgedAt = null,
                        createdAt = r.scannedAt,
                        queued = true
                    )
                )
            } catch (_: Exception) {
            }
        }
        return out
    }

    /**
     * Can this person acknowledge this handover? Same rule as the server
     * (web/src/lib/handover-access.ts): pending, not the creator, and
     * rostered on the handover's to-duty shift for its date — or a super
     * admin. The server enforces it again on acknowledge, so a wrong
     * answer here only ever hides or shows a button, never grants access.
     */
    fun canAcknowledgeHandover(h: Handover, userId: String): Boolean {
        if (h.status != "PENDING" || h.queued) return false
        if (h.createdById == userId) return false
        val person = rosterDays().firstOrNull { it.date == h.date }?.staff?.firstOrNull { it.id == userId }
            ?: return false
        if (person.role == "SUPER_ADMIN") return true
        return person.shifts.any { it.dutyShiftId == h.toDutyShiftId }
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
