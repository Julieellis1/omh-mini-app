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
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.MultiFormatReader
import com.google.zxing.PlanarYUVLuminanceSource
import com.google.zxing.common.HybridBinarizer

/**
 * Phase 1 device proof for the OMH mini attendance app.
 * One screen, no login, no network, no backend.
 * Step 1: front camera selfie (modest resolution, retake allowed).
 * Step 2: back camera QR scan via ZXing core (pure Java, offline).
 *
 * Deliberately uses the old android.hardware.Camera API: it works from
 * API 21 up, needs no Play services and no extra libraries, which is
 * what keeps this build tiny enough for a 2 GB Go-edition phone.
 */
class MainActivity : Activity(), SurfaceHolder.Callback, Camera.PreviewCallback {

    private enum class Mode { SELFIE, SELFIE_DONE, QR, QR_DONE }

    private lateinit var previewView: SurfaceView
    private lateinit var photoView: ImageView
    private lateinit var statusText: TextView
    private lateinit var resultText: TextView
    private lateinit var captureButton: Button
    private lateinit var retakeButton: Button
    private lateinit var toQrButton: Button
    private lateinit var scanAgainButton: Button

    private var camera: Camera? = null
    private var mode = Mode.SELFIE
    private var surfaceReady = false
    private var frameCount = 0
    private var qrFound = false

    private val qrReader = MultiFormatReader().apply {
        setHints(mapOf(DecodeHintType.POSSIBLE_FORMATS to listOf(com.google.zxing.BarcodeFormat.QR_CODE)))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        previewView = findViewById(R.id.previewView)
        photoView = findViewById(R.id.photoView)
        statusText = findViewById(R.id.statusText)
        resultText = findViewById(R.id.resultText)
        captureButton = findViewById(R.id.captureButton)
        retakeButton = findViewById(R.id.retakeButton)
        toQrButton = findViewById(R.id.toQrButton)
        scanAgainButton = findViewById(R.id.scanAgainButton)

        previewView.holder.addCallback(this)

        captureButton.setOnClickListener { takeSelfie() }
        retakeButton.setOnClickListener { startSelfieMode() }
        toQrButton.setOnClickListener { startQrMode() }
        scanAgainButton.setOnClickListener { startQrMode() }

        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.CAMERA), 1)
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<String>, grantResults: IntArray) {
        if (requestCode == 1 && grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            startSelfieMode()
        } else {
            statusText.text = "Camera permission is needed for this test. Please allow it and reopen the app."
            Toast.makeText(this, "Camera permission denied", Toast.LENGTH_LONG).show()
        }
    }

    override fun onResume() {
        super.onResume()
        if (checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            if (mode == Mode.SELFIE || mode == Mode.QR) startCameraForMode()
        }
    }

    override fun onPause() {
        super.onPause()
        releaseCamera()
    }

    // ---- modes ----

    private fun startSelfieMode() {
        mode = Mode.SELFIE
        qrFound = false
        photoView.visibility = View.GONE
        previewView.visibility = View.VISIBLE
        captureButton.visibility = View.VISIBLE
        captureButton.text = "Take selfie (front camera)"
        retakeButton.visibility = View.GONE
        toQrButton.visibility = View.GONE
        scanAgainButton.visibility = View.GONE
        statusText.text = "Step 1 of 2: front camera. Take your selfie."
        startCameraForMode()
    }

    private fun startQrMode() {
        mode = Mode.QR
        qrFound = false
        frameCount = 0
        photoView.visibility = View.GONE
        previewView.visibility = View.VISIBLE
        captureButton.visibility = View.GONE
        retakeButton.visibility = View.GONE
        toQrButton.visibility = View.GONE
        scanAgainButton.visibility = View.GONE
        resultText.text = "Point the back camera at a QR code."
        statusText.text = "Step 2 of 2: back camera. Scanning for a QR code…"
        startCameraForMode()
    }

    // ---- camera ----

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
        val facing = if (mode == Mode.QR) Camera.CameraInfo.CAMERA_FACING_BACK
            else Camera.CameraInfo.CAMERA_FACING_FRONT
        var id = findCamera(facing)
        if (id < 0) id = 0 // device has only one camera: use whatever exists
        try {
            val cam = Camera.open(id)
            cam.setDisplayOrientation(90)
            val params = cam.parameters
            // Modest resolution: a proof selfie does not need full size,
            // and small frames keep QR decoding fast on a slow phone.
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
            if (mode == Mode.QR) cam.setPreviewCallback(this)
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

    private fun takeSelfie() {
        val cam = camera ?: return
        captureButton.isEnabled = false
        try {
            cam.takePicture(null, null) { data, _ ->
                val raw = BitmapFactory.decodeByteArray(data, 0, data.size)
                val matrix = Matrix().apply { postRotate(90f) }
                val photo = Bitmap.createBitmap(raw, 0, 0, raw.width, raw.height, matrix, true)
                if (photo != raw) raw.recycle()
                runOnUiThread {
                    releaseCamera()
                    photoView.setImageBitmap(photo)
                    photoView.visibility = View.VISIBLE
                    previewView.visibility = View.GONE
                    mode = Mode.SELFIE_DONE
                    captureButton.visibility = View.GONE
                    retakeButton.visibility = View.VISIBLE
                    toQrButton.visibility = View.VISIBLE
                    statusText.text = "Selfie taken. Retake it if it is unclear, or continue to the QR scan."
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
        if (mode != Mode.QR || qrFound || data == null || cam == null) return
        frameCount++
        if (frameCount % 4 != 0) return // decode every 4th frame: enough on a slow CPU
        val params = cam.parameters
        val size = params.previewSize ?: return
        try {
            val source = PlanarYUVLuminanceSource(data, size.width, size.height, 0, 0, size.width, size.height, false)
            val result = qrReader.decodeWithState(BinaryBitmap(HybridBinarizer(source)))
            qrFound = true
            runOnUiThread {
                releaseCamera()
                mode = Mode.QR_DONE
                scanAgainButton.visibility = View.VISIBLE
                statusText.text = "QR code scanned."
                resultText.text = "Scanned value:\n${result.text}"
            }
        } catch (_: Exception) {
            // No QR code in this frame: keep scanning.
            try { qrReader.reset() } catch (_: Exception) { }
        }
    }

    // ---- SurfaceHolder.Callback ----

    override fun surfaceCreated(holder: SurfaceHolder) {
        surfaceReady = true
        if (checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            startCameraForMode()
        }
    }

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) = Unit

    override fun surfaceDestroyed(holder: SurfaceHolder) {
        surfaceReady = false
        releaseCamera()
    }
}
