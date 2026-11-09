package com.omh.mini

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Matrix
import android.hardware.Camera
import android.os.Bundle
import android.os.Handler
import android.os.Looper
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
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream

/**
 * Phase 3 + 4: attendance and handover on the shared kiosk phone.
 *
 * Home is deliberately minimal: the OMH logo, the facility name, and
 * exactly two big buttons — Attendance and Handing Over. Attendance
 * lists today's roster (downloaded 5 days ahead whenever the phone has
 * internet); picking a name shows exactly ONE action — Sign in, or Sign
 * out when already signed in — plus Back to names. Sign-in takes a
 * front-camera selfie, then the back camera scans the facility QR; the
 * record is queued on the phone with the REAL sign-in time and syncs
 * when internet returns. The startup screen holds about 1.5 seconds so
 * the logo and facility name are seen, then lifts to the home screen.
 * Handovers work the same way: the outgoing team writes one, the
 * incoming team reads and acknowledges it, and only the incoming duty
 * (or a super admin) can acknowledge.
 *
 * Colours are the OMH clinic palette: deep forest green with gold
 * primary buttons and dark forest text, so this reads as the same OMH
 * app. Camera still uses the old android.hardware.Camera API (works API
 * 21 up, no Play services, keeps the build tiny). No new libraries.
 */

// OMH clinic palette (same values as the web app's tailwind config).
private val FOREST_950 = Color.parseColor("#07120e")
private val FOREST_900 = Color.parseColor("#0b1e19")
private val FOREST_800 = Color.parseColor("#0f2a23")
private val GOLD = Color.parseColor("#c9a25e")
private val GOLD_DARK = Color.parseColor("#a5813f")
private val GOLD_LIGHT = Color.parseColor("#e3c88f")
private val SAGE = Color.parseColor("#93a8a1")
private val INK_SOFT = Color.parseColor("#41544d")

class MainActivity : Activity(), SurfaceHolder.Callback, Camera.PreviewCallback {

    private enum class Screen { HOME, NAMES, SETUP, DETAIL, CAMERA, DONE, HANDOVER_LIST, HANDOVER_DETAIL, HANDOVER_FORM }
    private enum class CamMode { SELFIE, QR }

    private lateinit var store: Store

    // home (minimalist: logo, facility name, two big buttons)
    private lateinit var homeGroup: LinearLayout
    private lateinit var attendanceButton: Button
    private lateinit var rosterInfoText: TextView
    private lateinit var queueText: TextView
    private lateinit var syncButton: Button
    private lateinit var refreshButton: Button
    private lateinit var settingsButton: Button
    private lateinit var splashOverlay: LinearLayout

    // names (today's roster, from the Attendance button)
    private lateinit var namesGroup: LinearLayout
    private lateinit var staffList: LinearLayout
    private lateinit var namesBackButton: Button

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

    // handover list
    private lateinit var handoverListGroup: LinearLayout
    private lateinit var handoverListInfo: TextView
    private lateinit var createHandoverButton: Button
    private lateinit var handoverList: LinearLayout
    private lateinit var handoverListBackButton: Button
    private lateinit var handoverInfoText: TextView
    private lateinit var handoversButton: Button

    // handover detail
    private lateinit var handoverDetailGroup: LinearLayout
    private lateinit var handoverDetailTitle: TextView
    private lateinit var handoverDetailMeta: TextView
    private lateinit var handoverDetailContent: TextView
    private lateinit var handoverDetailPatients: TextView
    private lateinit var handoverDetailNote: TextView
    private lateinit var acknowledgeAsButton: Button
    private lateinit var ackList: LinearLayout
    private lateinit var acknowledgeButton: Button
    private lateinit var handoverDetailBackButton: Button

    // handover form
    private lateinit var handoverFormGroup: LinearLayout
    private lateinit var formPickGroup: LinearLayout
    private lateinit var formCreatorList: LinearLayout
    private lateinit var formFieldsGroup: LinearLayout
    private lateinit var formCreatorText: TextView
    private lateinit var formFromText: TextView
    private lateinit var formToList: LinearLayout
    private lateinit var formPriorityNormal: Button
    private lateinit var formPriorityUrgent: Button
    private lateinit var situationEdit: EditText
    private lateinit var backgroundEdit: EditText
    private lateinit var assessmentEdit: EditText
    private lateinit var recommendationEdit: EditText
    private lateinit var formPatientsList: LinearLayout
    private lateinit var addPatientButton: Button
    private lateinit var formSaveButton: Button
    private lateinit var formStatusText: TextView
    private lateinit var formBackButton: Button

    private var screen = Screen.HOME
    private var camMode = CamMode.SELFIE

    // Startup screen: the forest splash overlay stays up about 1.5
    // seconds so the logo and facility name are actually seen, then lifts.
    private val splashHandler = Handler(Looper.getMainLooper())
    private val hideSplashRunnable = Runnable { splashOverlay.visibility = View.GONE }
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
    private var doneReturn = Screen.HOME

    // handover state
    private var selectedHandover: Handover? = null
    private var handoverCreator: StaffMember? = null
    private var handoverToDutyId: String? = null
    private var handoverPriority = "NORMAL"
    private var ackPerson: StaffMember? = null
    private var patientRows = ArrayList<Pair<EditText, EditText>>() // (label, note)

    // ---- OMH styling: gold primary buttons with dark forest text, white
    // secondary buttons with forest text, white name buttons. ----

    private fun stylePrimary(b: Button) {
        b.setBackgroundColor(GOLD)
        b.setTextColor(FOREST_950)
    }

    private fun styleSecondary(b: Button) {
        b.setBackgroundColor(Color.WHITE)
        b.setTextColor(FOREST_900)
    }

    private fun styleNameButton(b: Button) {
        b.setBackgroundColor(Color.WHITE)
        b.setTextColor(FOREST_900)
    }

    private fun styleSelected(b: Button) {
        b.setBackgroundColor(GOLD)
        b.setTextColor(FOREST_950)
    }

    private fun styleUnselected(b: Button) {
        b.setBackgroundColor(Color.WHITE)
        b.setTextColor(FOREST_900)
    }

    private val qrReader = MultiFormatReader().apply {
        setHints(mapOf(DecodeHintType.POSSIBLE_FORMATS to listOf(com.google.zxing.BarcodeFormat.QR_CODE)))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        store = Store(this)

        homeGroup = findViewById(R.id.homeGroup)
        attendanceButton = findViewById(R.id.attendanceButton)
        rosterInfoText = findViewById(R.id.rosterInfoText)
        queueText = findViewById(R.id.queueText)
        syncButton = findViewById(R.id.syncButton)
        refreshButton = findViewById(R.id.refreshButton)
        settingsButton = findViewById(R.id.settingsButton)
        splashOverlay = findViewById(R.id.splashOverlay)
        namesGroup = findViewById(R.id.namesGroup)
        staffList = findViewById(R.id.staffList)
        namesBackButton = findViewById(R.id.namesBackButton)

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

        handoverListGroup = findViewById(R.id.handoverListGroup)
        handoverListInfo = findViewById(R.id.handoverListInfo)
        createHandoverButton = findViewById(R.id.createHandoverButton)
        handoverList = findViewById(R.id.handoverList)
        handoverListBackButton = findViewById(R.id.handoverListBackButton)
        handoverInfoText = findViewById(R.id.handoverInfoText)
        handoversButton = findViewById(R.id.handoversButton)

        handoverDetailGroup = findViewById(R.id.handoverDetailGroup)
        handoverDetailTitle = findViewById(R.id.handoverDetailTitle)
        handoverDetailMeta = findViewById(R.id.handoverDetailMeta)
        handoverDetailContent = findViewById(R.id.handoverDetailContent)
        handoverDetailPatients = findViewById(R.id.handoverDetailPatients)
        handoverDetailNote = findViewById(R.id.handoverDetailNote)
        acknowledgeAsButton = findViewById(R.id.acknowledgeAsButton)
        ackList = findViewById(R.id.ackList)
        acknowledgeButton = findViewById(R.id.acknowledgeButton)
        handoverDetailBackButton = findViewById(R.id.handoverDetailBackButton)

        handoverFormGroup = findViewById(R.id.handoverFormGroup)
        formPickGroup = findViewById(R.id.formPickGroup)
        formCreatorList = findViewById(R.id.formCreatorList)
        formFieldsGroup = findViewById(R.id.formFieldsGroup)
        formCreatorText = findViewById(R.id.formCreatorText)
        formFromText = findViewById(R.id.formFromText)
        formToList = findViewById(R.id.formToList)
        formPriorityNormal = findViewById(R.id.formPriorityNormal)
        formPriorityUrgent = findViewById(R.id.formPriorityUrgent)
        situationEdit = findViewById(R.id.situationEdit)
        backgroundEdit = findViewById(R.id.backgroundEdit)
        assessmentEdit = findViewById(R.id.assessmentEdit)
        recommendationEdit = findViewById(R.id.recommendationEdit)
        formPatientsList = findViewById(R.id.formPatientsList)
        addPatientButton = findViewById(R.id.addPatientButton)
        formSaveButton = findViewById(R.id.formSaveButton)
        formStatusText = findViewById(R.id.formStatusText)
        formBackButton = findViewById(R.id.formBackButton)

        previewView.holder.addCallback(this)

        // OMH colours on the fixed buttons. The two big home buttons are
        // gold; Sync now is small and secondary so they dominate.
        stylePrimary(attendanceButton)
        styleSecondary(syncButton)
        styleSecondary(refreshButton)
        styleSecondary(namesBackButton)
        stylePrimary(handoversButton)
        stylePrimary(saveSetupButton)
        styleSecondary(setupBackButton)
        stylePrimary(signInButton)
        stylePrimary(signOutButton)
        styleSecondary(detailBackButton)
        stylePrimary(captureButton)
        styleSecondary(retakeButton)
        stylePrimary(toQrButton)
        styleSecondary(cameraBackButton)
        stylePrimary(doneButton)
        stylePrimary(createHandoverButton)
        styleSecondary(handoverListBackButton)
        stylePrimary(acknowledgeAsButton)
        stylePrimary(acknowledgeButton)
        styleSecondary(handoverDetailBackButton)
        styleSecondary(addPatientButton)
        stylePrimary(formSaveButton)
        styleSecondary(formBackButton)

        syncButton.setOnClickListener { syncNow() }
        refreshButton.setOnClickListener { refreshRoster(true) }
        attendanceButton.setOnClickListener { showNames() }
        namesBackButton.setOnClickListener { showHome() }
        handoversButton.setOnClickListener { showHandoverList() }
        settingsButton.setOnClickListener { showSetup() }
        saveSetupButton.setOnClickListener { saveSetup() }
        setupBackButton.setOnClickListener { showHome() }
        detailBackButton.setOnClickListener { showNames() }
        signInButton.setOnClickListener { startSignIn() }
        signOutButton.setOnClickListener { startSignOut() }
        captureButton.setOnClickListener { takeSelfie() }
        retakeButton.setOnClickListener { startSelfieMode() }
        toQrButton.setOnClickListener { startQrMode() }
        cameraBackButton.setOnClickListener {
            if (selectedStaff != null) showDetail(selectedStaff!!) else showNames()
        }
        doneButton.setOnClickListener { if (doneReturn == Screen.HANDOVER_LIST) showHandoverList() else showHome() }
        createHandoverButton.setOnClickListener { showHandoverForm() }
        handoverListBackButton.setOnClickListener { showHome() }
        acknowledgeAsButton.setOnClickListener { showAckPicker() }
        acknowledgeButton.setOnClickListener { acknowledgeSelected() }
        handoverDetailBackButton.setOnClickListener { showHandoverList() }
        addPatientButton.setOnClickListener { addPatientRow() }
        formPriorityNormal.setOnClickListener { setHandoverPriority("NORMAL") }
        formPriorityUrgent.setOnClickListener { setHandoverPriority("URGENT") }
        formSaveButton.setOnClickListener { saveHandover() }
        formBackButton.setOnClickListener { showHandoverList() }

        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.CAMERA), 1)
        }

        if (!store.isConfigured) {
            showSetup()
        } else {
            showHome()
            // Whenever the app opens with internet, refresh the roster and
            // handovers and flush anything still queued. All on background
            // threads; the splash overlay is still up for ~1.5 seconds.
            refreshRoster(false)
            refreshHandovers(false)
            Thread { syncQueueQuietly() }.start()
        }

        // Hold the startup screen about 1.5 seconds: the window
        // background shows first, then this overlay carries the same
        // splash until it lifts to reveal the home screen.
        splashHandler.postDelayed(hideSplashRunnable, 1500)
    }

    override fun onDestroy() {
        splashHandler.removeCallbacks(hideSplashRunnable)
        super.onDestroy()
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
            Screen.NAMES -> showHome()
            Screen.DETAIL -> showNames()
            Screen.CAMERA -> if (selectedStaff != null) showDetail(selectedStaff!!) else showNames()
            Screen.HANDOVER_DETAIL, Screen.HANDOVER_FORM -> showHandoverList()
            else -> showHome()
        }
    }

    // ---- screens ----

    private fun showOnly(vararg groups: LinearLayout) {
        for (g in listOf(homeGroup, namesGroup, setupGroup, detailGroup, cameraGroup, doneGroup, handoverListGroup, handoverDetailGroup, handoverFormGroup)) {
            g.visibility = if (groups.contains(g)) View.VISIBLE else View.GONE
        }
    }

    private fun showHome() {
        releaseCamera()
        screen = Screen.HOME
        showOnly(homeGroup)
        refreshHomeText()
    }

    // Attendance button: today's roster only. Tapping a name opens that
    // person's screen with the single Sign in / Sign out button and Back.
    private fun showNames() {
        releaseCamera()
        screen = Screen.NAMES
        showOnly(namesGroup)
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
                setTextColor(INK_SOFT)
            }
            staffList.addView(t)
        } else {
            for (person in today) {
                val duty = person.shifts.joinToString(", ") { "${it.name} ${it.startTime}–${it.endTime}" }
                val signedIn = store.isSignedIn(person.id)
                val b = Button(this).apply {
                    text = "${person.name}\n$duty" + if (signedIn) " • Signed in" else ""
                    textSize = 17f
                    minHeight = 64
                    styleNameButton(this)
                    setOnClickListener { showDetail(person) }
                }
                val lp = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { topMargin = 8 }
                staffList.addView(b, lp)
            }
        }
        // Handovers waiting for the incoming team today.
        val pendingHandovers = store.handoversFor(lagosToday()).count { it.status == "PENDING" } +
            store.queuedHandovers().size
        handoverInfoText.text = when {
            pendingHandovers > 0 -> "$pendingHandovers handover(s) waiting for the incoming team."
            store.handoverJson.isBlank() -> ""
            else -> "No handovers waiting today."
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
        // One action only: Sign in when not signed in, Sign out when
        // signed in — never both buttons at once.
        val signedIn = store.isSignedIn(person.id)
        detailDuty.text = "Duty today:\n$dutyLines\n\n" +
            if (signedIn) "Signed in — tap Sign out to check out." else "Not signed in yet."
        signInButton.visibility = if (signedIn) View.GONE else View.VISIBLE
        signOutButton.visibility = if (signedIn) View.VISIBLE else View.GONE
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
                        if (screen == Screen.HOME || screen == Screen.NAMES) refreshHomeText()
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

    private fun refreshHandovers(showToast: Boolean) {
        if (!store.isConfigured) return
        Thread {
            if (!isOnline(this)) {
                if (showToast) runOnUiThread { Toast.makeText(this, "No internet — using the downloaded handovers.", Toast.LENGTH_SHORT).show() }
                return@Thread
            }
            when (val r = fetchHandovers(store.serverUrl, store.kioskToken, lagosToday())) {
                is ApiResult.Ok -> {
                    store.handoverJson = r.body
                    store.handoverFetchedAt = System.currentTimeMillis()
                    runOnUiThread {
                        if (screen == Screen.HOME || screen == Screen.NAMES) refreshHomeText()
                        if (screen == Screen.HANDOVER_LIST) fillHandoverList()
                        if (showToast) Toast.makeText(this, "Handovers refreshed.", Toast.LENGTH_SHORT).show()
                    }
                }
                is ApiResult.HttpError -> runOnUiThread {
                    if (showToast) Toast.makeText(this, "Handover refresh failed: ${r.message}", Toast.LENGTH_LONG).show()
                }
                is ApiResult.NetworkError -> runOnUiThread {
                    if (showToast) Toast.makeText(this, "No internet — using the downloaded handovers.", Toast.LENGTH_SHORT).show()
                }
            }
        }.start()
    }

    private fun syncNow() {
        syncButton.isEnabled = false
        queueText.text = "Syncing…"
        Thread {
            val summary = syncQueue(store)
            refreshHandovers(false)
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
        refreshHandovers(false)
        if (summary.synced > 0) runOnUiThread { refreshHomeText() }
    }

    // ---- handovers ----

    private fun showHandoverList() {
        releaseCamera()
        screen = Screen.HANDOVER_LIST
        showOnly(handoverListGroup)
        fillHandoverList()
        refreshHandovers(false)
    }

    private fun fillHandoverList() {
        val today = lagosToday()
        val items = (store.handoversFor(today) + store.queuedHandovers())
            .sortedWith(compareByDescending<Handover> { it.priority == "URGENT" }.thenByDescending { it.createdAt })
        val pending = items.count { it.status == "PENDING" }
        handoverListInfo.text = when {
            items.isEmpty() -> if (store.handoverJson.isBlank()) "No handovers downloaded yet. Connect to the internet and open this screen again." else "No handovers for today."
            else -> "$pending waiting for the incoming team, ${items.size - pending} acknowledged."
        }
        handoverList.removeAllViews()
        if (items.isEmpty()) {
            val t = TextView(this).apply {
                text = ""
                textSize = 16f
            }
            handoverList.addView(t)
            return
        }
        for (h in items) {
            val status = when {
                h.queued -> "Waiting to sync"
                h.status == "ACKNOWLEDGED" -> "Acknowledged by ${h.acknowledgedByName ?: "someone"}"
                else -> "Waiting for ${h.toDutyName}"
            }
            val urgent = if (h.priority == "URGENT") "URGENT • " else ""
            val b = Button(this).apply {
                text = "$urgent${h.fromDutyName} → ${h.toDutyName}\nBy ${h.createdByName} • $status"
                textSize = 16f
                minHeight = 64
                if (h.priority == "URGENT" && h.status == "PENDING") stylePrimary(this) else styleNameButton(this)
                setOnClickListener { showHandoverDetail(h) }
            }
            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = 8 }
            handoverList.addView(b, lp)
        }
    }

    private fun showHandoverDetail(h: Handover) {
        selectedHandover = h
        ackPerson = null
        screen = Screen.HANDOVER_DETAIL
        showOnly(handoverDetailGroup)
        handoverDetailTitle.text = "${h.fromDutyName} → ${h.toDutyName}"
        val ward = if (!h.ward.isNullOrBlank()) " • ${h.ward}" else ""
        val at = if (h.createdAt.isNotBlank()) " • ${lagosClock(h.createdAt)}" else ""
        handoverDetailMeta.text = "By ${h.createdByName}$ward$at\n" +
            if (h.status == "ACKNOWLEDGED") "Acknowledged by ${h.acknowledgedByName ?: "someone"}." else "Waiting for the incoming ${h.toDutyName} team."
        val sections = StringBuilder()
        sections.append("Situation:\n${h.situation}")
        if (h.background.isNotBlank()) sections.append("\n\nBackground:\n${h.background}")
        if (h.assessment.isNotBlank()) sections.append("\n\nAssessment:\n${h.assessment}")
        if (h.recommendation.isNotBlank()) sections.append("\n\nRecommendation:\n${h.recommendation}")
        handoverDetailContent.text = sections.toString()
        handoverDetailPatients.text = if (h.patients.isEmpty()) {
            "Patients: none listed."
        } else {
            "Patients:\n" + h.patients.joinToString("\n") { "• ${it.label}" + if (it.note.isNotBlank()) " — ${it.note}" else "" }
        }
        ackList.removeAllViews()
        acknowledgeButton.visibility = View.GONE
        when {
            h.queued -> {
                handoverDetailNote.text = "Still on this phone — it can be acknowledged once it syncs to the server."
                acknowledgeAsButton.visibility = View.GONE
            }
            h.status != "PENDING" -> {
                handoverDetailNote.text = "Already acknowledged. Everyone can read it; only the incoming team could acknowledge it."
                acknowledgeAsButton.visibility = View.GONE
            }
            else -> {
                handoverDetailNote.text = "Only staff on the incoming ${h.toDutyName} duty can acknowledge this."
                acknowledgeAsButton.visibility = View.VISIBLE
            }
        }
    }

    private fun showAckPicker() {
        val h = selectedHandover ?: return
        ackList.removeAllViews()
        acknowledgeButton.visibility = View.GONE
        handoverDetailNote.text = "Who is acknowledging? Pick your name."
        val today = store.todayStaff()
        if (today.isEmpty()) {
            handoverDetailNote.text = "No roster for today — connect to the internet and refresh the roster first."
            return
        }
        for (person in today) {
            val b = Button(this).apply {
                text = person.name
                textSize = 17f
                minHeight = 56
                styleNameButton(this)
                setOnClickListener { pickAcknowledger(person) }
            }
            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = 8 }
            ackList.addView(b, lp)
        }
    }

    private fun pickAcknowledger(person: StaffMember) {
        val h = selectedHandover ?: return
        ackPerson = person
        if (store.canAcknowledgeHandover(h, person.id)) {
            handoverDetailNote.text = "${person.name} is on the incoming ${h.toDutyName} duty."
            acknowledgeButton.visibility = View.VISIBLE
        } else {
            acknowledgeButton.visibility = View.GONE
            handoverDetailNote.text = if (h.createdById == person.id) {
                "You wrote this handover — the incoming team must acknowledge it, not you."
            } else {
                "Only staff on the incoming ${h.toDutyName} duty can acknowledge this handover."
            }
        }
    }

    private fun acknowledgeSelected() {
        val h = selectedHandover ?: return
        val person = ackPerson ?: return
        acknowledgeButton.isEnabled = false
        handoverDetailNote.text = "Sending acknowledgement…"
        Thread {
            when (val r = acknowledgeHandover(store.serverUrl, store.kioskToken, h.id, person.id)) {
                is ApiResult.Ok -> {
                    refreshHandovers(false)
                    runOnUiThread {
                        acknowledgeButton.isEnabled = true
                        Toast.makeText(this, "Acknowledged by ${person.name}.", Toast.LENGTH_SHORT).show()
                        showHandoverList()
                    }
                }
                is ApiResult.HttpError -> runOnUiThread {
                    acknowledgeButton.isEnabled = true
                    handoverDetailNote.text = r.message
                }
                is ApiResult.NetworkError -> runOnUiThread {
                    acknowledgeButton.isEnabled = true
                    handoverDetailNote.text = "No internet — acknowledgement needs a connection. Try again when the phone is online."
                }
            }
        }.start()
    }

    private fun showHandoverForm() {
        handoverCreator = null
        handoverToDutyId = null
        handoverPriority = "NORMAL"
        patientRows.clear()
        formPatientsList.removeAllViews()
        situationEdit.setText("")
        backgroundEdit.setText("")
        assessmentEdit.setText("")
        recommendationEdit.setText("")
        formStatusText.text = ""
        setHandoverPriority("NORMAL")
        screen = Screen.HANDOVER_FORM
        showOnly(handoverFormGroup)
        formPickGroup.visibility = View.VISIBLE
        formFieldsGroup.visibility = View.GONE
        formCreatorList.removeAllViews()
        val today = store.todayStaff()
        if (today.isEmpty()) {
            val t = TextView(this).apply {
                text = "No roster for today — connect to the internet and refresh the roster first."
                textSize = 16f
                setTextColor(INK_SOFT)
            }
            formCreatorList.addView(t)
            return
        }
        for (person in today) {
            val duty = person.shifts.joinToString(", ") { it.name }
            val b = Button(this).apply {
                text = "${person.name}\n$duty"
                textSize = 17f
                minHeight = 64
                styleNameButton(this)
                setOnClickListener { pickFormCreator(person) }
            }
            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = 8 }
            formCreatorList.addView(b, lp)
        }
    }

    private fun pickFormCreator(person: StaffMember) {
        handoverCreator = person
        handoverToDutyId = null
        formPickGroup.visibility = View.GONE
        formFieldsGroup.visibility = View.VISIBLE
        formCreatorText.text = "Handing over as: ${person.name}"
        val first = person.shifts.firstOrNull()
        formFromText.text = if (first != null) {
            "From duty: ${first.name} (${first.startTime}–${first.endTime})"
        } else {
            "From duty: —"
        }
        // To-duty choices: every duty rostered today except the creator's own.
        val myDutyIds = person.shifts.map { it.dutyShiftId }.toSet()
        val duties = store.todayStaff()
            .flatMap { it.shifts }
            .distinctBy { it.dutyShiftId }
            .filter { it.dutyShiftId !in myDutyIds }
        formToList.removeAllViews()
        if (duties.isEmpty()) {
            val t = TextView(this).apply {
                text = "No other duty is rostered today."
                textSize = 16f
                setTextColor(INK_SOFT)
            }
            formToList.addView(t)
            return
        }
        for (d in duties) {
            val b = Button(this).apply {
                text = "${d.name} (${d.startTime}–${d.endTime})"
                textSize = 17f
                minHeight = 56
                styleUnselected(this)
                setOnClickListener {
                    handoverToDutyId = d.dutyShiftId
                    for (i in 0 until formToList.childCount) {
                        val child = formToList.getChildAt(i)
                        if (child is Button) styleUnselected(child)
                    }
                    styleSelected(this)
                }
            }
            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = 8 }
            formToList.addView(b, lp)
        }
    }

    private fun setHandoverPriority(priority: String) {
        handoverPriority = priority
        if (priority == "URGENT") {
            styleSelected(formPriorityUrgent)
            styleUnselected(formPriorityNormal)
        } else {
            styleSelected(formPriorityNormal)
            styleUnselected(formPriorityUrgent)
        }
    }

    private fun addPatientRow() {
        val row = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val labelEdit = EditText(this).apply {
            hint = "Patient name or label"
            textSize = 16f
            minHeight = 52
        }
        val noteEdit = EditText(this).apply {
            hint = "Note for next shift (optional)"
            textSize = 16f
            minHeight = 52
        }
        val remove = Button(this).apply {
            text = "Remove"
            textSize = 14f
            styleSecondary(this)
            setOnClickListener {
                patientRows.remove(labelEdit to noteEdit)
                formPatientsList.removeView(row)
            }
        }
        row.addView(labelEdit)
        row.addView(noteEdit)
        row.addView(remove)
        val lp = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = 12 }
        formPatientsList.addView(row, lp)
        patientRows.add(labelEdit to noteEdit)
    }

    private fun saveHandover() {
        val creator = handoverCreator
        if (creator == null) {
            formStatusText.text = "Pick who is handing over first."
            return
        }
        val fromDuty = creator.shifts.firstOrNull()
        if (fromDuty == null) {
            formStatusText.text = "That person has no duty today."
            return
        }
        val toDutyId = handoverToDutyId
        if (toDutyId == null) {
            formStatusText.text = "Pick which duty this hands over to."
            return
        }
        val situation = situationEdit.text.toString().trim()
        if (situation.isBlank()) {
            formStatusText.text = "Write the situation — the next team needs to know what is happening."
            return
        }
        val patients = JSONArray()
        for ((labelEdit, noteEdit) in patientRows) {
            val label = labelEdit.text.toString().trim()
            if (label.isBlank()) continue
            patients.put(JSONObject().apply {
                put("label", label)
                put("note", noteEdit.text.toString().trim())
            })
        }
        val clientRef = store.newClientRef()
        val createdAt = lagosNowIso()
        val payload = JSONObject().apply {
            put("createdById", creator.id)
            put("fromDutyShiftId", fromDuty.dutyShiftId)
            put("toDutyShiftId", toDutyId)
            put("date", lagosToday())
            put("priority", handoverPriority)
            put("situation", situation)
            put("background", backgroundEdit.text.toString().trim())
            put("assessment", assessmentEdit.text.toString().trim())
            put("recommendation", recommendationEdit.text.toString().trim())
            put("patients", patients)
            put("clientKey", clientRef)
            put("createdAt", createdAt)
        }
        val record = QueuedRecord(
            clientRef = clientRef,
            userId = creator.id,
            userName = creator.name,
            action = "handover",
            token = "",
            scannedAt = createdAt,
            selfieFile = null,
            status = "pending",
            error = null,
            kind = "handover",
            payload = payload.toString()
        )
        store.addRecord(record)
        refreshHomeText()
        showHandoverDone("Handover saved — ${creator.name}", "Saved on this phone. It will sync when internet is available.")
        Thread {
            syncQueue(store)
            refreshHandovers(false)
            runOnUiThread { refreshHomeText() }
        }.start()
    }

    private fun showHandoverDone(title: String, detail: String) {
        doneReturn = Screen.HANDOVER_LIST
        screen = Screen.DONE
        showOnly(doneGroup)
        doneText.text = title
        doneDetailText.text = detail
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
                // Save the ROTATED photo, not the raw camera bytes: the
                // front camera's raw JPEG is sideways, and the selfie in
                // the web admin must show the person upright.
                val rotated = ByteArrayOutputStream()
                photo.compress(Bitmap.CompressFormat.JPEG, 70, rotated)
                selfieBytes = shrinkJpeg(rotated.toByteArray())
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
        doneReturn = Screen.HOME
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
                // A tooSoon checkout did not happen on the server, so it
                // must not flip the local signed-in state.
                if (body?.optBoolean("tooSoon", false) != true) {
                    val action = body?.optString("action") ?: record.action
                    store.noteSyncedState(record.userId, action == "in")
                }
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
