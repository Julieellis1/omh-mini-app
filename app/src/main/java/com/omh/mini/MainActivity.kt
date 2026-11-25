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
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
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
 * exactly three big buttons: Attendance, Handing Over and Drugs.
 * Attendance lists today's roster (downloaded 5 days ahead whenever
 * the phone has internet); picking a name shows exactly ONE action:
 * out when already signed in — plus Back to names. Sign-in takes a
 * front-camera selfie, then the back camera scans the facility QR; the
 * record is queued on the phone with the REAL sign-in time and syncs
 * when internet returns. The startup screen holds about 1.5 seconds so
 * the logo and facility name are seen, then lifts to the home screen.
 * Handovers work the same way: the outgoing team writes one, the
 * incoming team reads and acknowledges it, and only the incoming duty
 * (or a super admin) can acknowledge. A handover also carries a drugs
 * count (record only, never touches stock) and a cash block: handed
 * over, made, used and what it was used for, with handing over
 * computed. Both are downloaded from the server list and queued
 * offline with the handover.
 * The incoming team can report an issue instead of acknowledging: the
 * handover stays PENDING and the issue stays visible as history.
 * Drugs lists the drug catalogue from the downloaded list (searchable,
 * low stock in red), logs usage against stock (treatment, ward use or
 * other, by a named staffer from today's roster) and receives new
 * stock into a batch, all queued offline exactly like handovers and
 * synced through the same queue. A usage log or receive never touches
 * the handover drug count, which stays record only.
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
private val ALERT_RED = Color.parseColor("#b3261e")

class MainActivity : Activity(), SurfaceHolder.Callback, Camera.PreviewCallback {

    private enum class Screen { HOME, NAMES, SETUP, DETAIL, CAMERA, DONE, HANDOVER_LIST, HANDOVER_DETAIL, HANDOVER_FORM, DRUGS, DRUG_DETAIL }
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
    private lateinit var handoverDetailDrugs: TextView
    private lateinit var handoverDetailMoney: TextView
    private lateinit var handoverDetailIssue: TextView
    private lateinit var handoverDetailNote: TextView
    private lateinit var acknowledgeAsButton: Button
    private lateinit var ackList: LinearLayout
    private lateinit var acknowledgeButton: Button
    private lateinit var reportIssueButton: Button
    private lateinit var issueList: LinearLayout
    private lateinit var issueNoteEdit: EditText
    private lateinit var sendIssueButton: Button
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
    private lateinit var formDrugsInfo: TextView
    private lateinit var formDrugsList: LinearLayout
    private lateinit var drugPickerList: LinearLayout
    private lateinit var addDrugButton: Button
    private lateinit var formMoneyHandedOver: EditText
    private lateinit var formMoneyMade: EditText
    private lateinit var formMoneyUsed: EditText
    private lateinit var formMoneyUsedFor: EditText
    private lateinit var formMoneyHandingOver: TextView
    private lateinit var formSaveButton: Button
    private lateinit var formStatusText: TextView
    private lateinit var formBackButton: Button

    // drugs list
    private lateinit var drugsGroup: LinearLayout
    private lateinit var drugsButton: Button
    private lateinit var drugsInfo: TextView
    private lateinit var drugSearchEdit: EditText
    private lateinit var drugList: LinearLayout
    private lateinit var usageInfo: TextView
    private lateinit var usageList: LinearLayout
    private lateinit var drugsBackButton: Button

    // drug detail: log usage + add stock for one drug
    private lateinit var drugDetailGroup: LinearLayout
    private lateinit var drugDetailTitle: TextView
    private lateinit var drugDetailStock: TextView
    private lateinit var usageQtyEdit: EditText
    private lateinit var usagePurposeTreatment: Button
    private lateinit var usagePurposeWard: Button
    private lateinit var usagePurposeOther: Button
    private lateinit var usageByText: TextView
    private lateinit var usageByList: LinearLayout
    private lateinit var usageNotesEdit: EditText
    private lateinit var logUsageButton: Button
    private lateinit var usageStatusText: TextView
    private lateinit var receiveQtyEdit: EditText
    private lateinit var receiveBatchEdit: EditText
    private lateinit var receiveExpiryEdit: EditText
    private lateinit var receiveByText: TextView
    private lateinit var receiveByList: LinearLayout
    private lateinit var addStockButton: Button
    private lateinit var receiveStatusText: TextView
    private lateinit var drugDetailBackButton: Button

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
    private var issuePerson: StaffMember? = null
    private var patientRows = ArrayList<Pair<EditText, EditText>>() // (label, note)
    private var drugRows = ArrayList<DrugRow>()

    // drugs state
    private var selectedDrug: DrugItem? = null
    private var drugSearchQuery = ""
    private var usagePurpose = "TREATMENT"
    private var usagePerson: StaffMember? = null
    private var receivePerson: StaffMember? = null

    // One drug row: the nurse types only how much was used. The leftover
    // (stock - used) is shown live in leftText and never typed or sent;
    // the server computes and stores it.
    private class DrugRow(
        val drug: DrugItem,
        val usedEdit: EditText,
        val leftText: TextView,
        val rowView: LinearLayout
    )

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
        handoverDetailDrugs = findViewById(R.id.handoverDetailDrugs)
        handoverDetailMoney = findViewById(R.id.handoverDetailMoney)
        handoverDetailIssue = findViewById(R.id.handoverDetailIssue)
        handoverDetailNote = findViewById(R.id.handoverDetailNote)
        acknowledgeAsButton = findViewById(R.id.acknowledgeAsButton)
        ackList = findViewById(R.id.ackList)
        acknowledgeButton = findViewById(R.id.acknowledgeButton)
        reportIssueButton = findViewById(R.id.reportIssueButton)
        issueList = findViewById(R.id.issueList)
        issueNoteEdit = findViewById(R.id.issueNoteEdit)
        sendIssueButton = findViewById(R.id.sendIssueButton)
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
        formDrugsInfo = findViewById(R.id.formDrugsInfo)
        formDrugsList = findViewById(R.id.formDrugsList)
        drugPickerList = findViewById(R.id.drugPickerList)
        addDrugButton = findViewById(R.id.addDrugButton)
        formMoneyHandedOver = findViewById(R.id.formMoneyHandedOver)
        formMoneyMade = findViewById(R.id.formMoneyMade)
        formMoneyUsed = findViewById(R.id.formMoneyUsed)
        formMoneyUsedFor = findViewById(R.id.formMoneyUsedFor)
        formMoneyHandingOver = findViewById(R.id.formMoneyHandingOver)
        formSaveButton = findViewById(R.id.formSaveButton)
        formStatusText = findViewById(R.id.formStatusText)
        formBackButton = findViewById(R.id.formBackButton)

        drugsGroup = findViewById(R.id.drugsGroup)
        drugsButton = findViewById(R.id.drugsButton)
        drugsInfo = findViewById(R.id.drugsInfo)
        drugSearchEdit = findViewById(R.id.drugSearchEdit)
        drugList = findViewById(R.id.drugList)
        usageInfo = findViewById(R.id.usageInfo)
        usageList = findViewById(R.id.usageList)
        drugsBackButton = findViewById(R.id.drugsBackButton)

        drugDetailGroup = findViewById(R.id.drugDetailGroup)
        drugDetailTitle = findViewById(R.id.drugDetailTitle)
        drugDetailStock = findViewById(R.id.drugDetailStock)
        usageQtyEdit = findViewById(R.id.usageQtyEdit)
        usagePurposeTreatment = findViewById(R.id.usagePurposeTreatment)
        usagePurposeWard = findViewById(R.id.usagePurposeWard)
        usagePurposeOther = findViewById(R.id.usagePurposeOther)
        usageByText = findViewById(R.id.usageByText)
        usageByList = findViewById(R.id.usageByList)
        usageNotesEdit = findViewById(R.id.usageNotesEdit)
        logUsageButton = findViewById(R.id.logUsageButton)
        usageStatusText = findViewById(R.id.usageStatusText)
        receiveQtyEdit = findViewById(R.id.receiveQtyEdit)
        receiveBatchEdit = findViewById(R.id.receiveBatchEdit)
        receiveExpiryEdit = findViewById(R.id.receiveExpiryEdit)
        receiveByText = findViewById(R.id.receiveByText)
        receiveByList = findViewById(R.id.receiveByList)
        addStockButton = findViewById(R.id.addStockButton)
        receiveStatusText = findViewById(R.id.receiveStatusText)
        drugDetailBackButton = findViewById(R.id.drugDetailBackButton)

        previewView.holder.addCallback(this)

        // OMH colours on the fixed buttons. The three big home buttons are
        // gold; Sync now is small and secondary so they dominate.
        stylePrimary(attendanceButton)
        styleSecondary(syncButton)
        styleSecondary(refreshButton)
        styleSecondary(namesBackButton)
        stylePrimary(handoversButton)
        stylePrimary(drugsButton)
        styleSecondary(drugsBackButton)
        stylePrimary(logUsageButton)
        stylePrimary(addStockButton)
        styleSecondary(drugDetailBackButton)
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
        stylePrimary(reportIssueButton)
        stylePrimary(sendIssueButton)
        styleSecondary(handoverDetailBackButton)
        styleSecondary(addPatientButton)
        styleSecondary(addDrugButton)
        stylePrimary(formSaveButton)
        styleSecondary(formBackButton)

        syncButton.setOnClickListener { syncNow() }
        refreshButton.setOnClickListener { refreshRoster(true) }
        attendanceButton.setOnClickListener { showNames() }
        namesBackButton.setOnClickListener { showHome() }
        handoversButton.setOnClickListener { showHandoverList() }
        drugsButton.setOnClickListener { showDrugs() }
        drugsBackButton.setOnClickListener { showHome() }
        drugDetailBackButton.setOnClickListener { showDrugs() }
        drugSearchEdit.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(s: Editable?) {
                drugSearchQuery = s?.toString()?.trim() ?: ""
                fillDrugList()
            }
        })
        usagePurposeTreatment.setOnClickListener { setUsagePurpose("TREATMENT") }
        usagePurposeWard.setOnClickListener { setUsagePurpose("WARD_USE") }
        usagePurposeOther.setOnClickListener { setUsagePurpose("OTHER") }
        logUsageButton.setOnClickListener { submitUsage() }
        addStockButton.setOnClickListener { submitReceive() }
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
        reportIssueButton.setOnClickListener { showIssuePicker() }
        sendIssueButton.setOnClickListener { sendIssueReport() }
        handoverDetailBackButton.setOnClickListener { showHandoverList() }
        addPatientButton.setOnClickListener { addPatientRow() }
        addDrugButton.setOnClickListener { showDrugPicker() }
        val moneyWatcher = object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(s: Editable?) { updateMoneyHandingOver() }
        }
        formMoneyHandedOver.addTextChangedListener(moneyWatcher)
        formMoneyMade.addTextChangedListener(moneyWatcher)
        formMoneyUsed.addTextChangedListener(moneyWatcher)
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
            // Whenever the app opens with internet, refresh the roster,
            // handovers and drug list, and flush anything still queued.
            // All on background threads; the splash overlay is still up
            // for ~1.5 seconds.
            refreshRoster(false)
            refreshHandovers(false)
            refreshDrugs(false)
            refreshUsage(false)
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
            Screen.DRUGS -> showHome()
            Screen.DRUG_DETAIL -> showDrugs()
            else -> showHome()
        }
    }

    // ---- screens ----

    private fun showOnly(vararg groups: LinearLayout) {
        for (g in listOf(homeGroup, namesGroup, setupGroup, detailGroup, cameraGroup, doneGroup, handoverListGroup, handoverDetailGroup, handoverFormGroup, drugsGroup, drugDetailGroup)) {
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

    /**
     * Download the drug list whenever internet is available and cache it
     * on the phone (like the 5-day roster), so the handover drug count
     * works offline. A 403 means drug management is turned off for the
     * facility — the handover still works, just without a drug section.
     */
    private fun refreshDrugs(showToast: Boolean) {
        if (!store.isConfigured) return
        Thread {
            if (!isOnline(this)) {
                if (showToast) runOnUiThread { Toast.makeText(this, "No internet — using the downloaded drug list.", Toast.LENGTH_SHORT).show() }
                return@Thread
            }
            when (val r = fetchDrugs(store.serverUrl, store.kioskToken)) {
                is ApiResult.Ok -> {
                    store.drugJson = r.body
                    store.drugFetchedAt = System.currentTimeMillis()
                    runOnUiThread {
                        if (screen == Screen.HANDOVER_FORM) refreshFormDrugsInfo()
                        if (screen == Screen.DRUGS) fillDrugList()
                        if (screen == Screen.DRUG_DETAIL) refreshDrugDetailStock()
                        if (showToast) Toast.makeText(this, "Drug list refreshed.", Toast.LENGTH_SHORT).show()
                    }
                }
                is ApiResult.HttpError -> runOnUiThread {
                    if (showToast) Toast.makeText(this, "Drug list refresh failed: ${r.message}", Toast.LENGTH_LONG).show()
                }
                is ApiResult.NetworkError -> runOnUiThread {
                    if (showToast) Toast.makeText(this, "No internet — using the downloaded drug list.", Toast.LENGTH_SHORT).show()
                }
            }
        }.start()
    }

    /**
     * Download the recent usage log whenever internet is available and
     * cache it on the phone (like the drug list), so the log still shows
     * offline. A 403 means drug management is turned off for the
     * facility, the drugs screen still works from its cached list.
     */
    private fun refreshUsage(showToast: Boolean) {
        if (!store.isConfigured) return
        Thread {
            if (!isOnline(this)) {
                if (showToast) runOnUiThread { Toast.makeText(this, "No internet. Using the downloaded usage log.", Toast.LENGTH_SHORT).show() }
                return@Thread
            }
            when (val r = fetchUsage(store.serverUrl, store.kioskToken)) {
                is ApiResult.Ok -> {
                    store.usageJson = r.body
                    store.usageFetchedAt = System.currentTimeMillis()
                    runOnUiThread {
                        if (screen == Screen.DRUGS) fillUsageList()
                        if (showToast) Toast.makeText(this, "Usage log refreshed.", Toast.LENGTH_SHORT).show()
                    }
                }
                is ApiResult.HttpError -> runOnUiThread {
                    if (showToast) Toast.makeText(this, "Usage log refresh failed: ${r.message}", Toast.LENGTH_LONG).show()
                }
                is ApiResult.NetworkError -> runOnUiThread {
                    if (showToast) Toast.makeText(this, "No internet. Using the downloaded usage log.", Toast.LENGTH_SHORT).show()
                }
            }
        }.start()
    }

    // ---- drugs ----

    private fun showDrugs() {
        releaseCamera()
        screen = Screen.DRUGS
        showOnly(drugsGroup)
        fillDrugList()
        fillUsageList()
        refreshDrugs(false)
        refreshUsage(false)
    }

    /** The drug list, filtered by the search box. Works from the cached
     * list when offline; low stock rows are red. */
    private fun fillDrugList() {
        val all = store.drugs()
        val q = drugSearchQuery.lowercase()
        val drugs = if (q.isBlank()) all else all.filter {
            it.name.lowercase().contains(q) ||
                (it.strength ?: "").lowercase().contains(q)
        }
        drugsInfo.text = when {
            store.drugJson.isBlank() -> "No drug list downloaded yet. Connect to the internet and open this screen again."
            all.isEmpty() -> "Drug list is empty."
            drugs.isEmpty() -> "No drug matches \"$drugSearchQuery\"."
            else -> "${drugs.size} drug(s). Tap one to log usage or add stock."
        }
        drugList.removeAllViews()
        for (d in drugs) {
            val low = if (d.lowStock) "LOW STOCK • " else ""
            val b = Button(this).apply {
                text = "$low${d.displayName()}\nStock: ${formatStockQty(d.stock)} ${d.unit}"
                textSize = 16f
                minHeight = 64
                if (d.lowStock) {
                    styleNameButton(this)
                    setTextColor(ALERT_RED)
                } else {
                    styleNameButton(this)
                }
                setOnClickListener { showDrugDetail(d) }
            }
            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = 8 }
            drugList.addView(b, lp)
        }
    }

    /** Recent usage entries, newest first. Shows the last fetched list
     * when offline. */
    private fun fillUsageList() {
        val entries = store.usage()
        usageInfo.text = when {
            store.usageJson.isBlank() -> "No usage log downloaded yet."
            entries.isEmpty() -> "No usage logged yet."
            else -> "Recent usage (newest first):"
        }
        usageList.removeAllViews()
        for (e in entries) {
            val who = e.usedBy ?: "Someone"
            val whenText = if (e.usedAt.isNotBlank() && e.usedAt.contains("T")) {
                "${e.usedAt.take(10)} ${lagosClock(e.usedAt)}"
            } else {
                e.usedAt
            }
            val t = TextView(this).apply {
                text = "• ${e.drugName}${if (!e.strength.isNullOrBlank()) " ${e.strength}" else ""}: ${formatStockQty(e.quantity)} ${e.unit}, $who, ${e.purposeLabel()}, $whenText"
                textSize = 16f
                setTextColor(FOREST_900)
            }
            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = 6 }
            usageList.addView(t, lp)
        }
    }

    private fun showDrugDetail(drug: DrugItem) {
        selectedDrug = drug
        usagePurpose = "TREATMENT"
        usagePerson = null
        receivePerson = null
        usageQtyEdit.setText("")
        usageNotesEdit.setText("")
        usageStatusText.text = ""
        receiveQtyEdit.setText("")
        receiveBatchEdit.setText("")
        receiveExpiryEdit.setText("")
        receiveStatusText.text = ""
        setUsagePurpose("TREATMENT")
        fillStaffPicker(usageByList, ::pickUsagePerson, "No roster for today. Connect to the internet and refresh the roster first.")
        fillStaffPicker(receiveByList, ::pickReceivePerson, "No roster for today. Connect to the internet and refresh the roster first.")
        screen = Screen.DRUG_DETAIL
        showOnly(drugDetailGroup)
        refreshDrugDetailStock()
    }

    /** Drug title and stock line on the detail screen, from the cached
     * list (refreshed after every successful log or receive). */
    private fun refreshDrugDetailStock() {
        val drug = selectedDrug ?: return
        val fresh = store.drugs().firstOrNull { it.id == drug.id } ?: drug
        selectedDrug = fresh
        drugDetailTitle.text = fresh.displayName()
        val low = if (fresh.lowStock) " (LOW STOCK)" else ""
        drugDetailStock.text = "Stock: ${formatStockQty(fresh.stock)} ${fresh.unit}$low"
        drugDetailStock.setTextColor(if (fresh.lowStock) ALERT_RED else FOREST_900)
    }

    /** A name picker from today's roster, like the handover pickers. */
    private fun fillStaffPicker(list: LinearLayout, onPick: (StaffMember) -> Unit, emptyText: String) {
        list.removeAllViews()
        val today = store.todayStaff()
        if (today.isEmpty()) {
            val t = TextView(this).apply {
                text = emptyText
                textSize = 16f
                setTextColor(INK_SOFT)
            }
            list.addView(t)
            return
        }
        for (person in today) {
            val duty = person.shifts.joinToString(", ") { it.name }
            val b = Button(this).apply {
                text = "${person.name}\n$duty"
                textSize = 16f
                minHeight = 56
                styleNameButton(this)
                setOnClickListener { onPick(person) }
            }
            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = 8 }
            list.addView(b, lp)
        }
    }

    private fun pickUsagePerson(person: StaffMember) {
        usagePerson = person
        usageByText.text = "Logged by: ${person.name}"
    }

    private fun pickReceivePerson(person: StaffMember) {
        receivePerson = person
        receiveByText.text = "Received by: ${person.name}"
    }

    private fun setUsagePurpose(purpose: String) {
        usagePurpose = purpose
        styleSelected(usagePurposeTreatment)
        styleUnselected(usagePurposeWard)
        styleUnselected(usagePurposeOther)
        when (purpose) {
            "WARD_USE" -> {
                styleUnselected(usagePurposeTreatment)
                styleSelected(usagePurposeWard)
                styleUnselected(usagePurposeOther)
            }
            "OTHER" -> {
                styleUnselected(usagePurposeTreatment)
                styleUnselected(usagePurposeWard)
                styleSelected(usagePurposeOther)
            }
        }
    }

    /** Quantity > 0, decimals allowed, up to the server cap. Null when
     * the text is not a usable number. */
    private fun parseQty(text: String, max: Double): Double? {
        val t = text.trim()
        if (t.isEmpty()) return null
        val v = t.toDoubleOrNull() ?: return null
        if (v <= 0 || v > max) return null
        return v
    }

    /** YYYY-MM-DD with a real month and day. Blank counts as absent. */
    private fun validDate(text: String): Boolean {
        val t = text.trim()
        if (t.isEmpty()) return true
        val parts = t.split("-")
        if (parts.size != 3 || parts[0].length != 4 || parts[1].length != 2 || parts[2].length != 2) return false
        if (parts[0].toIntOrNull() == null) return false
        val month = parts[1].toIntOrNull() ?: return false
        val day = parts[2].toIntOrNull() ?: return false
        return month in 1..12 && day in 1..31
    }

    /**
     * Log usage for the selected drug. The record is queued first with
     * a fresh clientRef, then sent at once when online, exactly like an
     * attendance record. Offline it stays queued and syncQueue sends it
     * later with the SAME clientRef, so a retry never deducts twice.
     * 400/403 marks it failed (bad body, not enough stock, drug
     * management off); 409 and 5xx keep it queued for a later retry.
     */
    private fun submitUsage() {
        val drug = selectedDrug ?: return
        val person = usagePerson
        if (person == null) {
            usageStatusText.text = "Pick who is logging this usage first."
            return
        }
        val qty = parseQty(usageQtyEdit.text.toString(), 100000.0)
        if (qty == null) {
            usageStatusText.text = "Quantity must be more than 0 and at most 100000."
            return
        }
        val notes = usageNotesEdit.text.toString().trim()
        if (notes.length > 1000) {
            usageStatusText.text = "Notes must be 1000 characters or fewer."
            return
        }
        val clientRef = store.newClientRef()
        val payload = JSONObject().apply {
            put("clientRef", clientRef)
            put("staffId", person.id)
            put("drugId", drug.id)
            put("quantity", qty)
            put("purpose", usagePurpose)
            put("notes", notes)
            put("usedAt", lagosToday())
        }
        val record = QueuedRecord(
            clientRef = clientRef,
            userId = person.id,
            userName = person.name,
            action = "usage",
            token = "",
            scannedAt = lagosNowIso(),
            selfieFile = null,
            status = "pending",
            error = null,
            kind = "usage",
            payload = payload.toString()
        )
        store.addRecord(record)
        logUsageButton.isEnabled = false
        usageStatusText.text = "Logging usage…"
        Thread { submitDrugRecord(record, isReceive = false) }.start()
    }

    /**
     * Receive stock for the selected drug. Same queue-then-send pattern
     * as submitUsage: the record keeps its clientRef across retries, so
     * a retry never adds the stock twice.
     */
    private fun submitReceive() {
        val drug = selectedDrug ?: return
        val person = receivePerson
        if (person == null) {
            receiveStatusText.text = "Pick who received this stock first."
            return
        }
        val qty = parseQty(receiveQtyEdit.text.toString(), 1000000.0)
        if (qty == null) {
            receiveStatusText.text = "Quantity must be more than 0 and at most 1000000."
            return
        }
        val batch = receiveBatchEdit.text.toString().trim()
        if (batch.length > 60) {
            receiveStatusText.text = "Batch number must be 60 characters or fewer."
            return
        }
        val expiry = receiveExpiryEdit.text.toString().trim()
        if (!validDate(expiry)) {
            receiveStatusText.text = "Expiry date must be YYYY-MM-DD, for example 2027-06-30."
            return
        }
        val clientRef = store.newClientRef()
        val payload = JSONObject().apply {
            put("clientRef", clientRef)
            put("staffId", person.id)
            put("drugId", drug.id)
            put("quantity", qty)
            if (batch.isNotBlank()) put("batchNumber", batch)
            if (expiry.isNotBlank()) put("expiryDate", expiry)
            put("receivedAt", lagosNowIso())
        }
        val record = QueuedRecord(
            clientRef = clientRef,
            userId = person.id,
            userName = person.name,
            action = "receive",
            token = "",
            scannedAt = lagosNowIso(),
            selfieFile = null,
            status = "pending",
            error = null,
            kind = "receive",
            payload = payload.toString()
        )
        store.addRecord(record)
        addStockButton.isEnabled = false
        receiveStatusText.text = "Adding stock…"
        Thread { submitDrugRecord(record, isReceive = true) }.start()
    }

    /** Send one queued usage or receive record now. Mirrors
     * submitRecord for attendance: success removes it from the queue
     * and refreshes the drug stock and usage log; 400/403 marks it
     * failed; 409, 5xx and no network keep it queued for a later retry
     * through syncQueue, with the same clientRef. */
    private fun submitDrugRecord(record: QueuedRecord, isReceive: Boolean) {
        val result = if (isReceive) {
            postStockReceive(store.serverUrl, store.kioskToken, record)
        } else {
            postDrugUsage(store.serverUrl, store.kioskToken, record)
        }
        when (result) {
            is ApiResult.Ok -> {
                store.removeRecord(record.clientRef)
                val body = try { JSONObject(result.body) } catch (_: Exception) { null }
                val stock = if (isReceive) body?.optDouble("totalStock", Double.NaN)
                    else body?.optDouble("remainingStock", Double.NaN)
                val stockText = if (stock != null && !stock.isNaN()) {
                    " Stock is now ${formatStockQty(stock)}."
                } else ""
                refreshDrugs(false)
                refreshUsage(false)
                runOnUiThread {
                    if (isReceive) {
                        addStockButton.isEnabled = true
                        receiveStatusText.text = "Stock added.$stockText"
                        receiveQtyEdit.setText("")
                        receiveBatchEdit.setText("")
                        receiveExpiryEdit.setText("")
                    } else {
                        logUsageButton.isEnabled = true
                        usageStatusText.text = "Usage logged.$stockText"
                        usageQtyEdit.setText("")
                        usageNotesEdit.setText("")
                    }
                    refreshHomeText()
                }
            }
            is ApiResult.HttpError -> {
                if (result.status == 400 || result.status == 403) {
                    store.markFailed(record.clientRef, result.message)
                    runOnUiThread {
                        if (isReceive) {
                            addStockButton.isEnabled = true
                            receiveStatusText.text = result.message
                        } else {
                            logUsageButton.isEnabled = true
                            usageStatusText.text = result.message
                        }
                        refreshHomeText()
                    }
                } else {
                    // 409 (already processing) and 5xx: keep queued, retry later.
                    runOnUiThread {
                        if (isReceive) {
                            addStockButton.isEnabled = true
                            receiveStatusText.text = "Saved on this phone. ${result.message} It will sync when the server can take it."
                        } else {
                            logUsageButton.isEnabled = true
                            usageStatusText.text = "Saved on this phone. ${result.message} It will sync when the server can take it."
                        }
                        refreshHomeText()
                    }
                }
            }
            is ApiResult.NetworkError -> {
                runOnUiThread {
                    if (isReceive) {
                        addStockButton.isEnabled = true
                        receiveStatusText.text = "Saved on this phone. No internet, so it will sync when internet returns."
                    } else {
                        logUsageButton.isEnabled = true
                        usageStatusText.text = "Saved on this phone. No internet, so it will sync when internet returns."
                    }
                    refreshHomeText()
                }
            }
        }
    }

    private fun formatNaira(amount: Long): String =
        "₦" + String.format(java.util.Locale.US, "%,d", amount)

    private fun syncNow() {
        syncButton.isEnabled = false
        queueText.text = "Syncing…"
        Thread {
            val summary = syncQueue(store)
            refreshHandovers(false)
            refreshDrugs(false)
            refreshUsage(false)
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
        refreshDrugs(false)
        refreshUsage(false)
        if (summary.synced > 0) runOnUiThread { refreshHomeText() }
    }

    // ---- handovers ----

    private fun showHandoverList() {
        releaseCamera()
        screen = Screen.HANDOVER_LIST
        showOnly(handoverListGroup)
        fillHandoverList()
        refreshHandovers(false)
        refreshDrugs(false)
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
            val issueFlag = if (h.hasIssue) "ISSUE REPORTED • " else ""
            val issueLine = if (h.hasIssue && !h.issueSummary.isNullOrBlank()) "\nIssue: ${h.issueSummary}" else ""
            val moneyLine = if (!h.money.isEmpty()) "\nHanding over ${formatNaira(h.money.handingOverNaira.toLong())}" else ""
            val b = Button(this).apply {
                text = "$urgent$issueFlag${h.fromDutyName} → ${h.toDutyName}\nBy ${h.createdByName} • $status$moneyLine$issueLine"
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
        issuePerson = null
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
        handoverDetailDrugs.text = if (h.drugs.isEmpty()) {
            "Drugs counted: none."
        } else {
            "Drugs counted:\n" + h.drugs.joinToString("\n") {
                "• ${it.name}${if (!it.strength.isNullOrBlank()) " ${it.strength}" else ""} (${it.unit}) — stock ${it.stockAtHandover}, used ${it.used}, left ${it.left}"
            }
        }
        handoverDetailMoney.text = if (h.money.isEmpty()) {
            "Money: none."
        } else {
            val m = h.money
            val lines = StringBuilder("Money:\n")
            lines.append("Handed over: ${formatNaira(m.handedOverNaira.toLong())}\n")
            lines.append("Made: ${formatNaira(m.madeNaira.toLong())}\n")
            lines.append("Used: ${formatNaira(m.usedNaira.toLong())}\n")
            if (m.usedFor.isNotBlank()) lines.append("Used for: ${m.usedFor}\n")
            lines.append("Handing over: ${formatNaira(m.handingOverNaira.toLong())}")
            lines.toString()
        }
        val issue = h.issue
        handoverDetailIssue.text = if (issue != null) {
            val whenText = if (issue.reportedAt.isNotBlank()) " • ${lagosClock(issue.reportedAt)}" else ""
            "Issue reported by ${issue.reportedByName}$whenText:\n${issue.note}"
        } else {
            ""
        }
        ackList.removeAllViews()
        acknowledgeButton.visibility = View.GONE
        issueList.removeAllViews()
        issueNoteEdit.setText("")
        issueNoteEdit.visibility = View.GONE
        sendIssueButton.visibility = View.GONE
        when {
            h.queued -> {
                handoverDetailNote.text = "Still on this phone — it can be acknowledged once it syncs to the server."
                acknowledgeAsButton.visibility = View.GONE
                reportIssueButton.visibility = View.GONE
            }
            h.status != "PENDING" -> {
                handoverDetailNote.text = "Already acknowledged. Everyone can read it; only the incoming team could acknowledge it."
                acknowledgeAsButton.visibility = View.GONE
                reportIssueButton.visibility = View.GONE
            }
            else -> {
                handoverDetailNote.text = "Only staff on the incoming ${h.toDutyName} duty can acknowledge this or report an issue."
                acknowledgeAsButton.visibility = View.VISIBLE
                // One issue per handover: once reported, the button goes
                // away, but the handover can still be acknowledged.
                reportIssueButton.visibility = if (h.hasIssue) View.GONE else View.VISIBLE
            }
        }
    }

    // ---- report an issue on a handover ----

    private fun showIssuePicker() {
        selectedHandover ?: return
        issueList.removeAllViews()
        issueNoteEdit.visibility = View.GONE
        sendIssueButton.visibility = View.GONE
        handoverDetailNote.text = "Who is reporting the issue? Pick your name."
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
                setOnClickListener { pickIssueReporter(person) }
            }
            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = 8 }
            issueList.addView(b, lp)
        }
    }

    private fun pickIssueReporter(person: StaffMember) {
        val h = selectedHandover ?: return
        issuePerson = person
        if (store.canReportIssueHandover(h, person.id)) {
            handoverDetailNote.text = "${person.name} is on the incoming ${h.toDutyName} duty. Say what the issue is — the handover stays waiting until it is acknowledged."
            issueNoteEdit.visibility = View.VISIBLE
            sendIssueButton.visibility = View.VISIBLE
        } else {
            issueNoteEdit.visibility = View.GONE
            sendIssueButton.visibility = View.GONE
            handoverDetailNote.text = if (h.createdById == person.id) {
                "You wrote this handover — the incoming team must report any issue, not you."
            } else if (h.hasIssue) {
                "An issue has already been reported on this handover."
            } else {
                "Only staff on the incoming ${h.toDutyName} duty can report an issue on this handover."
            }
        }
    }

    private fun sendIssueReport() {
        val h = selectedHandover ?: return
        val person = issuePerson ?: return
        val note = issueNoteEdit.text.toString().trim()
        if (note.isBlank()) {
            handoverDetailNote.text = "Say what the issue is before sending."
            return
        }
        sendIssueButton.isEnabled = false
        handoverDetailNote.text = "Sending issue report…"
        Thread {
            when (val r = reportHandoverIssue(store.serverUrl, store.kioskToken, h.id, person.id, note)) {
                is ApiResult.Ok -> {
                    refreshHandovers(false)
                    runOnUiThread {
                        sendIssueButton.isEnabled = true
                        Toast.makeText(this, "Issue reported by ${person.name}. The handover is still waiting.", Toast.LENGTH_LONG).show()
                        showHandoverList()
                    }
                }
                is ApiResult.HttpError -> runOnUiThread {
                    sendIssueButton.isEnabled = true
                    handoverDetailNote.text = r.message
                }
                is ApiResult.NetworkError -> runOnUiThread {
                    sendIssueButton.isEnabled = true
                    handoverDetailNote.text = "No internet — reporting an issue needs a connection. Try again when the phone is online."
                }
            }
        }.start()
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
        drugRows.clear()
        formDrugsList.removeAllViews()
        drugPickerList.removeAllViews()
        drugPickerList.visibility = View.GONE
        formMoneyHandedOver.setText("")
        formMoneyMade.setText("")
        formMoneyUsed.setText("")
        formMoneyUsedFor.setText("")
        updateMoneyHandingOver()
        situationEdit.setText("")
        backgroundEdit.setText("")
        assessmentEdit.setText("")
        recommendationEdit.setText("")
        formStatusText.text = ""
        setHandoverPriority("NORMAL")
        refreshFormDrugsInfo()
        refreshDrugs(false)
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

    private fun refreshFormDrugsInfo() {
        val drugs = store.drugs()
        formDrugsInfo.text = when {
            drugs.isEmpty() && store.drugJson.isBlank() -> "No drug list downloaded yet. Connect to the internet and open this screen again — the count works offline once downloaded."
            drugs.isEmpty() -> "Drug list is empty."
            else -> "${drugs.size} drug(s) on the list. Stock shown is from the last download; type only how much was used and the leftover is worked out for you. Counts are recorded exactly as entered and never change stock."
        }
    }

    /** Pick a drug from the downloaded list (stock shown) and add a count row for it. */
    private fun showDrugPicker() {
        val drugs = store.drugs()
        drugPickerList.removeAllViews()
        if (drugs.isEmpty()) {
            refreshFormDrugsInfo()
            refreshDrugs(true)
            formStatusText.text = "No drug list on this phone yet — connect to the internet and try again."
            return
        }
        val already = drugRows.map { it.drug.id }.toSet()
        val available = drugs.filter { it.id !in already }
        if (available.isEmpty()) {
            formStatusText.text = "Every drug on the list is already counted."
            return
        }
        for (d in available) {
            val b = Button(this).apply {
                text = "${d.displayName()}: stock ${formatStockQty(d.stock)}"
                textSize = 16f
                minHeight = 52
                styleNameButton(this)
                setOnClickListener {
                    drugPickerList.visibility = View.GONE
                    drugPickerList.removeAllViews()
                    addDrugRow(d)
                }
            }
            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = 8 }
            drugPickerList.addView(b, lp)
        }
        drugPickerList.visibility = View.VISIBLE
    }

    private fun addDrugRow(drug: DrugItem) {
        if (drugRows.size >= 50) {
            formStatusText.text = "At most 50 drugs can be counted on one handover."
            return
        }
        val row = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val label = TextView(this).apply {
            text = "${drug.displayName()}: stock ${formatStockQty(drug.stock)}"
            textSize = 16f
            setTextColor(FOREST_900)
        }
        val usedEdit = EditText(this).apply {
            hint = "Used (whole number)"
            textSize = 16f
            minHeight = 52
            inputType = InputType.TYPE_CLASS_NUMBER
        }
        // Leftover is display only: stock - used, recalculated as the
        // nurse types. It is never typed and never sent; the server
        // computes and stores it.
        val leftText = TextView(this).apply {
            text = "Leftover: ${formatStockQty(drug.stock)}"
            textSize = 16f
            setTextColor(FOREST_900)
        }
        val drugRow = DrugRow(drug, usedEdit, leftText, row)
        usedEdit.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(s: Editable?) { updateDrugLeft(drugRow) }
        })
        // A physical count can legitimately differ from stock, so used
        // above stock only warns, it never blocks saving.
        val remove = Button(this).apply {
            text = "Remove"
            textSize = 14f
            styleSecondary(this)
            setOnClickListener {
                drugRows.removeAll { it.rowView == row }
                formDrugsList.removeView(row)
            }
        }
        row.addView(label)
        row.addView(usedEdit)
        row.addView(leftText)
        row.addView(remove)
        val lp = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = 12 }
        formDrugsList.addView(row, lp)
        drugRows.add(drugRow)
    }

    /** Live leftover for one drug row: stock - used, with a visible warning when used exceeds stock. */
    private fun updateDrugLeft(drugRow: DrugRow) {
        val used = parseCount(drugRow.usedEdit.text.toString())
        if (used == null) {
            drugRow.leftText.text = "Leftover: ?"
            drugRow.leftText.setTextColor(FOREST_900)
            return
        }
        val left = drugRow.drug.stock - used
        if (left < 0) {
            drugRow.leftText.text = "Leftover: ${formatStockQty(left)} (used is more than stock!)"
            drugRow.leftText.setTextColor(ALERT_RED)
        } else {
            drugRow.leftText.text = "Leftover: ${formatStockQty(left)}"
            drugRow.leftText.setTextColor(FOREST_900)
        }
    }

    /** Live handing-over figure: handed over + made - used. Never typed, never sent. */
    private fun updateMoneyHandingOver() {
        val handed = parseNaira(formMoneyHandedOver.text.toString()) ?: 0
        val made = parseNaira(formMoneyMade.text.toString()) ?: 0
        val used = parseNaira(formMoneyUsed.text.toString()) ?: 0
        formMoneyHandingOver.text = "Handing over: ${formatNaira((handed + made - used).toLong())}"
    }

    /** Whole naira >= 0 up to the server's 100,000,000 cap; blank counts as 0. Null when not a whole number. */
    private fun parseNaira(text: String): Int? {
        val t = text.trim()
        if (t.isEmpty()) return 0
        val v = t.toLongOrNull() ?: return null
        if (v < 0 || v > 100_000_000) return null
        return v.toInt()
    }

    /** Whole number >= 0; blank counts as 0. Null when the text is not a whole number. */
    private fun parseCount(text: String): Int? {
        val t = text.trim()
        if (t.isEmpty()) return 0
        val v = t.toLongOrNull() ?: return null
        if (v < 0 || v > 1_000_000) return null
        return v.toInt()
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
        // Drugs counted: record only, used only. The leftover is never
        // typed and never sent. The server computes it as stock - used
        // and stores it. Only drugs actually used get a row; a row with
        // nothing used is left out. Used above stock only warns on the
        // row, it never blocks saving, and the server never touches
        // database stock for these numbers.
        val drugs = JSONArray()
        for (row in drugRows) {
            val used = parseCount(row.usedEdit.text.toString())
            if (used == null) {
                formStatusText.text = "Used for ${row.drug.displayName()} must be a whole number (0 or more)."
                return
            }
            if (used == 0) continue
            drugs.put(JSONObject().apply {
                put("drugId", row.drug.id)
                put("name", row.drug.name)
                put("strength", row.drug.strength)
                put("unit", row.drug.unit)
                // Handover counts are whole numbers, so the stock snapshot
                // sent here stays a whole number too (the server expects
                // an int); the catalogue itself keeps decimal stock.
                put("stockAtHandover", row.drug.stock.toInt())
                put("used", used)
            })
        }
        // Money as one object, never a list: handed over + made - used is
        // computed by the server, so handingOverNaira is never sent. The
        // reason is required whenever any money was used.
        val handedOver = parseNaira(formMoneyHandedOver.text.toString())
        val made = parseNaira(formMoneyMade.text.toString())
        val usedNaira = parseNaira(formMoneyUsed.text.toString())
        if (handedOver == null || made == null || usedNaira == null) {
            formStatusText.text = "Handed over, made and used must be whole numbers of naira (0 or more)."
            return
        }
        val usedFor = formMoneyUsedFor.text.toString().trim()
        if (usedNaira > 0 && usedFor.isBlank()) {
            formStatusText.text = "Say what the money was used for."
            return
        }
        if (usedFor.length > 200) {
            formStatusText.text = "What the money was used for must be 200 characters or fewer."
            return
        }
        val money = JSONObject().apply {
            put("handedOverNaira", handedOver)
            put("madeNaira", made)
            put("usedNaira", usedNaira)
            put("usedFor", usedFor)
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
            put("drugs", drugs)
            put("money", money)
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
