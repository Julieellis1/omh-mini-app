package com.omh.mini

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.hardware.Camera
import android.os.Bundle
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.MultiFormatReader
import com.google.zxing.PlanarYUVLuminanceSource
import com.google.zxing.common.HybridBinarizer
import org.json.JSONObject
import java.io.ByteArrayOutputStream

/**
 * Phase 3: attendance on the shared kiosk phone.
 *
 * Home shows today's roster only (downloaded 5 days ahead whenever the
 * phone has internet). Pick a name, see your duty and the people on it,
 * take a front-camera selfie (sign-in only), scan the facility QR with
 * the back camera, and the record is queued on the phone with the REAL
 * sign-in time. It syncs when internet returns; each record carries a
 * unique clientRef, so a retry can never create a duplicate on the
 * server. Once the server confirms receipt, the local copy (and the
 * selfie) is deleted to save space.
 *
 * Camera still uses the old android.hardware.Camera API (works API 21
 * up, no Play services, keeps the build tiny). No new libraries.
 */
class MainActivity : Activity(), SurfaceHolder.Callback, Camera.PreviewCallback {

    private enum class Screen { HOME, SETUP, DETAIL, CAMERA, DONE }
    private enum class CamMode { SELFIE, QR }

    private lateinit var store: Store

    // home
    private lateinit var homeGroup: LinearLayout
    private lateinit var rosterInfoText: TextView
    private lateinit var queueText: TextView
    private lateinit var syncButton: Button
    private lateinit var refreshButton: Button
    private lateinit var staffList: LinearLayout
    private lateinit var settingsButton: Button

    // setup
    private lateinit var setupGroup: LinearLayout
    private lateinit var serverUrlEdit: EditText
    private lateinit var tokenEdit: EditText
    private lateinit var saveSetupButton: Button
    private lateinit var setupStatusText: TextView
    private lateinit var setupBackButton: Button

    // detail
    private lateinit var detailGroup: LinearLayout
    private lateinit var detailName: TextView
    private lateinit var detailDuty: TextView
    private lateinit var detailTeam: TextView
    private lateinit var signInButton: Button
    private lateinit var signOutButton: Button
    private lateinit var detailBackButton: Button

    // camera
    private lateinit var cameraGroup: LinearLayout
    private lateinit var previewView: SurfaceView
    private lateinit var photoView: ImageView
    private lateinit var statusText: TextView
    private lateinit var resultText: TextView
    private lateinit var captureButton: Button
    private lateinit var retakeButton: Button
    private lateinit var toQrButton: Button
    private lateinit var cameraBackButton: Button

    // done
    private lateinit var doneGroup: LinearLayout
    private lateinit var doneText: TextView
    private lateinit var doneDetailText: TextView
    private lateinit var doneButton: Button

    private var screen = Screen.HOME
    private var camMode = CamMode.SELFIE
    private var camera: Camera? = null
    private var surfaceReady = false
    private var frameCount = 0
    private var qrFound = false

    private var selectedStaff: StaffMember? = null
    private var flowAction = "in" // "in" or "out" — advisory, the server toggles from the open record
    private var selfieBytes: ByteArray? = null
    private var retakesUsed = 0
    private var lastClientRef: String? = null
    private var lastRecordName = ""

    private val qrReader = MultiFormatReader().apply {
        setHints(mapOf(DecodeHintType.POSSIBLE_FORMATS to listOf(com.google.zxing.BarcodeFormat.QR_CODE)))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        store = Store(this)

        homeGroup = findViewById(R.id.homeGroup)
        rosterInfoText = findViewById(R.id.rosterInfoText)
        queueText = findViewById(R.id.queueText)
        syncButton = findViewById(R.id.syncButton)
        refreshButton = findViewById(R.id.refreshButton)
        staffList = findViewById(R.id.staffList)
        settingsButton = findViewById(R.id.settingsButton)

        setupGroup = findViewById(R.id.setupGroup)
        serverUrlEdit = findViewById(R.id.serverUrlEdit)
        tokenEdit = findViewById(R.id.tokenEdit)
        saveSetupButton = findViewById(R.id.saveSetupButton)
        setupStatusText = findViewById(R.id.setupStatusText)
        setupBackButton = findViewById(R.id.setupBackButton)

        detailGroup = findViewById(R.id.detailGroup)
        detailName = findViewById(R.id.detailName)
        detailDuty = findViewById(R.id.detailDuty)
        detailTeam = findViewById(R.id.detailTeam)
        signInButton = findViewById(R.id.signInButton)
        signOutButton = findViewById(R.id.signOutButton)
        detailBackButton = findViewById(R.id.detailBackButton)

        cameraGroup = findViewById(R.id.cameraGroup)
        previewView = findViewById(R.id.previewView)
        photoView = findViewById(R.id.photoView)
        statusText = findViewById(R.id.statusText)
        resultText = findViewById(R.id.resultText)
        captureButton = findViewById(R.id.captureButton)
        retakeButton = findViewById(R.id.retakeButton)
        toQrButton = findViewById(R.id.toQrButton)
        cameraBackButton = findViewById(R.id.cameraBackButton)

        doneGroup = findViewById(R.id.doneGroup)
        doneText = findViewById(R.id.doneText)
        doneDetailText = findViewById(R.id.doneDetailText)
        doneButton = findViewById(R.id.doneButton)

        previewView.holder.addCallback(this)

        syncButton.setOnClickListener { syncNow() }
        refreshButton.setOnClickListener { refreshRoster(true) }
        settingsButton.setOnClickListener { showSetup() }
        saveSetupButton.setOnClickListener { saveSetup() }
        setupBackButton.setOnClickListener { showHome() }
        detailBackButton.setOnClickListener { showHome() }
        signInButton.setOnClickListener { startSignIn() }
        signOutButton.setOnClickListener { startSignOut() }
        captureButton.setOnClickListener { takeSelfie() }
        retakeButton.setOnClickListener { startSelfieMode() }
        toQrButton.setOnClickListener { startQrMode() }
        cameraBackButton.setOnClickListener { showHome() }
        doneButton.setOnClickListener { showHome() }

        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.CAMERA), 1)
        }

        if (!store.isConfigured) {
            showSetup()
        } else {
            showHome()
            // Whenever the app opens with internet, refresh the roster and
            // flush anything still queued. Both run on background threads.
            refreshRoster(false)
            Thread { syncQueueQuietly() }.start()
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<String>, grantResults: IntArray) {
        if (requestCode == 1 && grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            if (screen == Screen.CAMERA) startCameraForMode()
        } else {
            Toast.makeText(this, "Camera permission is needed for sign-in", Toast.LENGTH_LONG).show()
        }
    }

    override fun onResume() {
        super.onResume()
        if (checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            if (screen == Screen.CAMERA) startCameraForMode()
        }
    }

    override fun onPause() {
        super.onPause()
        releaseCamera()
    }

    override fun onBackPressed() {
        when (screen) {
            Screen.HOME -> super.onBackPressed()
            Screen.SETUP -> if (store.isConfigured) showHome() else super.onBackPressed()
            else -> showHome()
        }
    }

    // ---- screens ----

    private fun showOnly(vararg groups: LinearLayout) {
        for (g in listOf(homeGroup, setupGroup, detailGroup, cameraGroup, doneGroup)) {
            g.visibility = if (groups.contains(g)) View.VISIBLE else View.GONE
        }
    }

    private fun showHome() {
        releaseCamera()
        screen = Screen.HOME
        showOnly(homeGroup)
        refreshHomeText()
    }

    private fun refreshHomeText() {
        val today = store.todayStaff()
        val fetched = store.rosterFetchedAt
        rosterInfoText.text = when {
            store.rosterJson.isBlank() -> "No roster downloaded yet. Connect to the internet and tap Refresh roster."
            today.isEmpty() -> "Roster for today (${lagosToday()}): nobody rostered."
            fetched > 0L -> "Roster for today (${lagosToday()}), ${today.size} on duty."
            else -> "Roster for today (${lagosToday()}), ${today.size} on duty."
        }
        val pending = store.pendingCount()
        val failed = store.failedCount()
        queueText.text = when {
            pending > 0 && failed > 0 -> "$pending record(s) waiting to sync. $failed need attention — tap Sync now to see the reason."
            pending > 0 -> "$pending record(s) waiting to sync."
            failed > 0 -> "$failed record(s) need attention. Tap Sync now to see the reason."
            else -> "No records waiting to sync."
        }
        staffList.removeAllViews()
        if (today.isEmpty()) {
            val t = TextView(this).apply {
                text = if (store.rosterJson.isBlank()) "" else "No names to show for today."
                textSize = 16f
            }
            staffList.addView(t)
        } else {
            for (person in today) {
                val duty = person.shifts.joinToString(", ") { "${it.name} ${it.startTime}–${it.endTime}" }
                val b = Button(this).apply {
                    text = "${person.name}\n$duty"
                    textSize = 17f
                    minHeight = 64
                    setOnClickListener { showDetail(person) }
                }
                val lp = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { topMargin = 8 }
                staffList.addView(b, lp)
            }
        }
    }

    private fun showSetup() {
        releaseCamera()
        screen = Screen.SETUP
        showOnly(setupGroup)
        serverUrlEdit.setText(store.serverUrl)
        tokenEdit.setText(store.kioskToken)
        setupStatusText.text = ""
        setupBackButton.visibility = if (store.isConfigured) View.VISIBLE else View.GONE
    }

    private fun saveSetup() {
        val url = serverUrlEdit.text.toString().trim().trimEnd('/')
        val token = tokenEdit.text.toString().trim()
        if (url.isBlank() || token.isBlank()) {
            setupStatusText.text = "Enter the server address and the kiosk token."
            return
        }
        store.serverUrl = url
        store.kioskToken = token
        saveSetupButton.isEnabled = false
        setupStatusText.text = "Testing connection…"
        Thread {
            when (val r = fetchRoster(store.serverUrl, store.kioskToken, lagosToday(), 5)) {
                is ApiResult.Ok -> {
                    store.rosterJson = r.body
                    store.rosterFetchedAt = System.currentTimeMillis()
                    runOnUiThread {
                        saveSetupButton.isEnabled = true
                        setupStatusText.text = "Connected. Roster downloaded for 5 days."
                        Toast.makeText(this, "Connected", Toast.LENGTH_SHORT).show()
                        showHome()
                    }
                }
                is ApiResult.HttpError -> runOnUiThread {
                    saveSetupButton.isEnabled = true
                    setupStatusText.text = "Server refused the token or address (${r.status}): ${r.message}"
                }
                is ApiResult.NetworkError -> runOnUiThread {
                    saveSetupButton.isEnabled = true
                    setupStatusText.text = "Could not reach the server. Check the address and the internet, then try again."
                }
            }
        }.start()
    }

    private fun showDetail(person: StaffMember) {
        selectedStaff = person
        screen = Screen.DETAIL
        showOnly(detailGroup)
        detailName.text = person.name
        val dutyLines = person.shifts.joinToString("\n") {
            val ward = if (!it.ward.isNullOrBlank()) " — ${it.ward}" else ""
            "${it.name} (${it.code}): ${it.startTime}–${it.endTime}$ward"
        }
        detailDuty.text = "Duty today:\n$dutyLines"
        // Teammates: everyone else rostered today on any of this person's duties.
        val today = store.todayStaff()
        val myDutyIds = person.shifts.map { it.dutyShiftId }.toSet()
        val team = today.filter { it.id != person.id && it.shifts.any { s -> s.dutyShiftId in myDutyIds } }
        detailTeam.text = if (team.isEmpty()) {
            "On duty with you: nobody else on this duty today."
        } else {
            "On duty with you:\n" + team.joinToString("\n") { "• ${it.name}" }
        }
    }

    // ---- roster + sync ----

    private fun refreshRoster(showToast: Boolean) {
        if (!store.isConfigured) return
        // Always on a background thread: this is also called from button taps.
        Thread {
            if (!isOnline(this)) {
                if (showToast) runOnUiThread { Toast.makeText(this, "No internet — using the downloaded roster.", Toast.LENGTH_SHORT).show() }
                return@Thread
            }
            when (val r = fetchRoster(store.serverUrl, store.kioskToken, lagosToday(), 5)) {
                is ApiResult.Ok -> {
                    store.rosterJson = r.body
                    store.rosterFetchedAt = System.currentTimeMillis()
                    runOnUiThread {
                        if (screen == Screen.HOME) refreshHomeText()
                        if (showToast) Toast.makeText(this, "Roster refreshed.", Toast.LENGTH_SHORT).show()
                    }
                }
                is ApiResult.HttpError -> runOnUiThread {
                    if (showToast) Toast.makeText(this, "Roster refresh failed: ${r.message}", Toast.LENGTH_LONG).show()
                }
                is ApiResult.NetworkError -> runOnUiThread {
                    if (showToast) Toast.makeText(this, "No internet — using the downloaded roster.", Toast.LENGTH_SHORT).show()
                }
            }
        }.start()
    }

    private fun syncNow() {
        syncButton.isEnabled = false
        queueText.text = "Syncing…"
        Thread {
            val summary = syncQueue(store)
            runOnUiThread {
                syncButton.isEnabled = true
                refreshHomeText()
                val msg = when {
                    summary.synced > 0 && summary.stopped == null -> "${summary.synced} record(s) synced."
                    summary.synced > 0 -> "${summary.synced} record(s) synced. ${summary.stopped}"
                    summary.stopped != null -> summary.stopped
                    else -> "Nothing to sync."
                }
                Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
                val failed = store.loadQueue().filter { it.status == "failed" }
                if (failed.isNotEmpty()) {
                    queueText.text = "Needs attention: ${failed.first().error ?: "the server refused this record."} (${failed.size} record(s))"
                }
            }
        }.start()
    }

    private fun syncQueueQuietly() {
        if (!isOnline(this)) return
        val summary = syncQueue(store)
        if (summary.synced > 0) runOnUiThread { refreshHomeText() }
    }

    // ---- sign-in / sign-out flow ----

    private fun startSignIn() {
        flowAction = "in"
        retakesUsed = 0
        selfieBytes = null
        startSelfieMode()
    }

    private fun startSignOut() {
        flowAction = "out"
        selfieBytes = null
        startQrMode()
    }

    private fun startSelfieMode() {
        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            Toast.makeText(this, "Camera permission is needed", Toast.LENGTH_LONG).show()
            return
        }
        screen = Screen.CAMERA
        camMode = CamMode.SELFIE
        qrFound = false
        showOnly(cameraGroup)
        photoView.visibility = View.GONE
        previewView.visibility = View.VISIBLE
        captureButton.visibility = View.VISIBLE
        captureButton.text = "Take selfie (front camera)"
        captureButton.isEnabled = true
        retakeButton.visibility = View.GONE
        toQrButton.visibility = View.GONE
        resultText.text = ""
        statusText.text = "Step 1 of 2: front camera. Take your selfie."
        startCameraForMode()
    }

    private fun startQrMode() {
        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            Toast.makeText(this, "Camera permission is needed", Toast.LENGTH_LONG).show()
            return
        }
        screen = Screen.CAMERA
        camMode = CamMode.QR
        qrFound = false
        frameCount = 0
        showOnly(cameraGroup)
        photoView.visibility = View.GONE
        previewView.visibility = View.VISIBLE
        captureButton.visibility = View.GONE
        retakeButton.visibility = View.GONE
        toQrButton.visibility = View.GONE
        resultText.text = "Point the back camera at the facility QR code beside the phone."
        statusText.text = "Step 2 of 2: back camera. Scanning for the QR code…"
        startCameraForMode()
    }

    // ---- camera (old android.hardware.Camera API, as in Phase 1) ----

    private fun findCamera(facing: Int): Int {
        val info = Camera.CameraInfo()
        for (i in 0 until Camera.getNumberOfCameras()) {
            Camera.getCameraInfo(i, info)
            if (info.facing == facing) return i
        }
        return -1
    }

    private fun startCameraForMode() {
        if (!surfaceReady) return
        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) return
        releaseCamera()
        val facing = if (camMode == CamMode.QR) Camera.CameraInfo.CAMERA_FACING_BACK
            else Camera.CameraInfo.CAMERA_FACING_FRONT
        var id = findCamera(facing)
        if (id < 0) id = 0 // device has only one camera: use whatever exists
        try {
            val cam = Camera.open(id)
            cam.setDisplayOrientation(90)
            val params = cam.parameters
            val previewSizes = params.supportedPreviewSizes
            val small = previewSizes?.minByOrNull { it.width * it.height }
            if (small != null) params.setPreviewSize(small.width, small.height)
            val picSizes = params.supportedPictureSizes
            val pic = picSizes?.firstOrNull { it.width == 640 && it.height == 480 }
                ?: picSizes?.minByOrNull { it.width * it.height }
            if (pic != null) params.setPictureSize(pic.width, pic.height)
            params.jpegQuality = 70
            if (params.supportedFocusModes?.contains(Camera.Parameters.FOCUS_MODE_CONTINUOUS_PICTURE) == true) {
                params.focusMode = Camera.Parameters.FOCUS_MODE_CONTINUOUS_PICTURE
            }
            cam.parameters = params
            cam.setPreviewDisplay(previewView.holder)
            if (camMode == CamMode.QR) cam.setPreviewCallback(this)
            cam.startPreview()
            camera = cam
        } catch (e: Exception) {
            statusText.text = "Could not open the camera: ${e.message}"
        }
    }

    private fun releaseCamera() {
        try {
            camera?.setPreviewCallback(null)
            camera?.stopPreview()
            camera?.release()
        } catch (_: Exception) {
        }
        camera = null
    }

    /** Keep the JPEG small enough for the server's 500 KB data-URL limit. */
    private fun shrinkJpeg(bytes: ByteArray): ByteArray {
        if (bytes.size <= 300_000) return bytes
        return try {
            val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return bytes
            var quality = 60
            var out: ByteArray
            do {
                val stream = ByteArrayOutputStream()
                bmp.compress(Bitmap.CompressFormat.JPEG, quality, stream)
                out = stream.toByteArray()
                quality -= 10
            } while (out.size > 300_000 && quality >= 30)
            bmp.recycle()
            out
        } catch (_: Exception) {
            bytes
        }
    }

    private fun takeSelfie() {
        val cam = camera ?: return
        captureButton.isEnabled = false
        try {
            cam.takePicture(null, null) { data, _ ->
                val jpeg = data ?: return@takePicture
                val raw = BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size) ?: return@takePicture
                val matrix = Matrix().apply { postRotate(90f) }
                val photo = Bitmap.createBitmap(raw, 0, 0, raw.width, raw.height, matrix, true)
                if (photo != raw) raw.recycle()
                selfieBytes = shrinkJpeg(jpeg)
                runOnUiThread {
                    releaseCamera()
                    photoView.setImageBitmap(photo)
                    photoView.visibility = View.VISIBLE
                    previewView.visibility = View.GONE
                    captureButton.visibility = View.GONE
                    // One retake only, as planned: a queue at shift change
                    // must keep moving.
                    retakeButton.visibility = if (retakesUsed < 1) View.VISIBLE else View.GONE
                    if (retakesUsed < 1) retakesUsed++
                    toQrButton.visibility = View.VISIBLE
                    statusText.text = "Selfie taken. Retake it once if it is unclear, or continue to the QR scan."
                    captureButton.isEnabled = true
                }
            }
        } catch (e: Exception) {
            captureButton.isEnabled = true
            statusText.text = "Selfie failed: ${e.message}"
        }
    }

    // ---- QR decoding (back camera preview frames) ----

    override fun onPreviewFrame(data: ByteArray?, cam: Camera?) {
        if (screen != Screen.CAMERA || camMode != CamMode.QR || qrFound || data == null || cam == null) return
        frameCount++
        if (frameCount % 4 != 0) return // decode every 4th frame: enough on a slow CPU
        val params = cam.parameters
        val size = params.previewSize ?: return
        try {
            val source = PlanarYUVLuminanceSource(data, size.width, size.height, 0, 0, size.width, size.height, false)
            val result = qrReader.decodeWithState(BinaryBitmap(HybridBinarizer(source)))
            qrFound = true
            runOnUiThread { onQrScanned(result.text) }
        } catch (_: Exception) {
            // No QR code in this frame: keep scanning.
            try { qrReader.reset() } catch (_: Exception) { }
        }
    }

    /**
     * The QR is scanned: this is the moment of the sign-in/out. The
     * record keeps this exact time (scannedAt), not the later sync time.
     * The scanned value itself is never shown on screen and never logged:
     * it is the facility secret.
     */
    private fun onQrScanned(value: String) {
        releaseCamera()
        val person = selectedStaff ?: run { showHome(); return }
        val clientRef = store.newClientRef()
        val scannedAt = lagosNowIso()
        val selfieFile = if (flowAction == "in" && selfieBytes != null) {
            store.saveSelfie(clientRef, selfieBytes!!)
        } else null
        val record = QueuedRecord(
            clientRef = clientRef,
            userId = person.id,
            userName = person.name,
            action = flowAction,
            token = value,
            scannedAt = scannedAt,
            selfieFile = selfieFile,
            status = "pending",
            error = null
        )
        store.addRecord(record)
        lastClientRef = clientRef
        lastRecordName = person.name
        showDoneQueued(record)
        // Try to send it right away; if there is no internet it simply
        // stays queued and the next sync attempt picks it up.
        Thread { submitRecord(record) }.start()
    }

    private fun showDoneQueued(record: QueuedRecord) {
        screen = Screen.DONE
        showOnly(doneGroup)
        val verb = if (record.action == "in") "Signed in" else "Signed out"
        doneText.text = "$verb — ${record.userName}"
        doneDetailText.text = "Time: ${lagosClock(record.scannedAt)}\nSaved on this phone. It will sync when internet is available."
        refreshHomeText()
    }

    /**
     * Send one record now and report what the server decided. The server
     * toggles sign-in/out from the open record, so the confirmation shows
     * the server's action, not the button the user tapped. A tooSoon
     * answer (checkout less than 5 minutes after check-in) is shown as a
     * plain message; the user scans again after 5 minutes.
     */
    private fun submitRecord(record: QueuedRecord) {
        val selfie = store.readSelfieDataUrl(record)
        when (val r = postAttendance(store.serverUrl, store.kioskToken, record, selfie)) {
            is ApiResult.Ok -> {
                store.deleteSelfie(record)
                store.removeRecord(record.clientRef)
                val body = try { JSONObject(r.body) } catch (_: Exception) { null }
                runOnUiThread {
                    if (record.clientRef == lastClientRef && screen == Screen.DONE) {
                        val verb = if (body?.optString("action") == "out") "Signed out" else "Signed in"
                        if (body?.optBoolean("tooSoon", false) == true) {
                            doneText.text = "Not yet — ${record.userName}"
                            doneDetailText.text = body.optString("message", "Checkout opens 5 minutes after check-in. Please scan again then.")
                        } else {
                            val at = body?.optString(if (body.optString("action") == "out") "checkOutAt" else "checkInAt", "")
                            val time = if (!at.isNullOrBlank()) lagosClock(at) else lagosClock(record.scannedAt)
                            doneText.text = "$verb — ${record.userName}"
                            doneDetailText.text = "Time: $time\nSynced to the server."
                        }
                    }
                    refreshHomeText()
                }
                Thread { syncQueueQuietly() }.start()
            }
            is ApiResult.HttpError -> {
                if (r.status == 400 || r.status == 403) {
                    store.markFailed(record.clientRef, r.message)
                    runOnUiThread {
                        if (record.clientRef == lastClientRef && screen == Screen.DONE) {
                            doneText.text = "Could not record — ${record.userName}"
                            doneDetailText.text = r.message
                        }
                        refreshHomeText()
                    }
                }
                // 409 (already processing) and 5xx: keep queued, retry later.
            }
            is ApiResult.NetworkError -> {
                // Offline: stays queued, the confirmation already says so.
            }
        }
    }

    // ---- SurfaceHolder.Callback ----

    override fun surfaceCreated(holder: SurfaceHolder) {
        surfaceReady = true
        if (checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            if (screen == Screen.CAMERA) startCameraForMode()
        }
    }

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) = Unit

    override fun surfaceDestroyed(holder: SurfaceHolder) {
        surfaceReady = false
        releaseCamera()
    }
}
