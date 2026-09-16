package com.adrianefremov.nightlab

import android.Manifest
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.ImageFormat
import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice

import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.DngCreator
import android.hardware.camera2.TotalCaptureResult
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.provider.MediaStore
import android.view.Gravity
import android.view.MotionEvent
import android.view.Surface
import android.view.TextureView
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.CheckBox
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import android.media.Image
import android.media.ImageReader
import java.io.FileOutputStream
import java.util.Locale
import kotlin.math.abs

class MainActivity : ComponentActivity() {

    private lateinit var textureView: TextureView
    private lateinit var isoSpinner: Spinner
    private lateinit var shutterSpinner: Spinner
    private lateinit var focusSeekBar: SeekBar
    private lateinit var focusLabel: TextView
    private lateinit var rawCheckBox: CheckBox
    private lateinit var infoLabel: TextView
    private lateinit var statusLabel: TextView
    val shutterArea = FrameLayout(this)
    private lateinit var cameraManager: CameraManager
    private var cameraDevice: CameraDevice? = null
    private var captureSession: CameraCaptureSession? = null
    private var previewBuilder: CaptureRequest.Builder? = null

    private var jpegReader: ImageReader? = null
    private var rawReader: ImageReader? = null
    private var cameraId: String? = null
    private var characteristics: CameraCharacteristics? = null

    private var exposureMin = 1_000_000L
    private var exposureMax = 30_000_000_000L
    private var isoMin = 100
    private var isoMax = 6400
    private var minFocusDistance = 0f
    private var rawSupported = false

    private var selectedIso = 100
    private var selectedExposureNs = 1_000_000_000L
    private var lastCaptureResult: TotalCaptureResult? = null

    private lateinit var cameraThread: HandlerThread
    private lateinit var cameraHandler: Handler

    private var isCapturing = false

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) openCameraWhenReady()
            else toast("NightLab behöver kamerabehörighet.")
        }

    private val textureListener = object : TextureView.SurfaceTextureListener {
        override fun onSurfaceTextureAvailable(surface: SurfaceTexture, width: Int, height: Int) {
            openCameraWhenReady()
        }
        override fun onSurfaceTextureSizeChanged(surface: SurfaceTexture, width: Int, height: Int) {}
        override fun onSurfaceTextureDestroyed(surface: SurfaceTexture): Boolean = true
        override fun onSurfaceTextureUpdated(surface: SurfaceTexture) {}
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        cameraManager = getSystemService(Context.CAMERA_SERVICE) as CameraManager
        startCameraThread()
        createInterface()

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
            == PackageManager.PERMISSION_GRANTED) {
            openCameraWhenReady()
        } else {
            permissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    override fun onPause() {
        closeCamera()
        super.onPause()
    }

    override fun onDestroy() {
        closeCamera()
        cameraThread.quitSafely()
        super.onDestroy()
    }

    private fun startCameraThread() {
        cameraThread = HandlerThread("NightLabCamera")
        cameraThread.start()
        cameraHandler = Handler(cameraThread.looper)
    }

    private fun createInterface() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(0xFF050505.toInt())
        }

        val title = TextView(this).apply {
            text = "NIGHTLAB"
            textSize = 24f
            setTextColor(0xFFFFFFFF.toInt())
            gravity = Gravity.CENTER
            setPadding(0, 18, 0, 2)
        }

        val mode = TextView(this).apply {
            text = "PRO  •  NIGHT  •  CAMERA2"
            textSize = 13f
            setTextColor(0xFFCCCCCC.toInt())
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, 8)
        }

        textureView = TextureView(this).apply {
            surfaceTextureListener = textureListener
            setOnTouchListener { _, event ->
                if (event.action == MotionEvent.ACTION_UP) {
                    focusAtTouch(event.x, event.y)
                }
                true
            }
        }

        root.addView(title, lpWrap())
        root.addView(mode, lpWrap())
        root.addView(textureView, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f
        ))

        val controls = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(12, 6, 12, 4)
        }

        val exposureRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }

        val isoTitle = label("ISO")
        isoSpinner = Spinner(this)
        val shutterTitle = label("SHUTTER")
        shutterSpinner = Spinner(this)

        exposureRow.addView(isoTitle, lpWrap())
        exposureRow.addView(isoSpinner, LinearLayout.LayoutParams(120, 48))
        exposureRow.addView(shutterTitle, lpWrap())
        exposureRow.addView(shutterSpinner, LinearLayout.LayoutParams(150, 48))
        controls.addView(exposureRow)

        focusLabel = label("FOCUS: AUTO").apply {
            gravity = Gravity.CENTER
            setPadding(0, 4, 0, 0)
        }
        controls.addView(focusLabel, lpWrap())

        focusSeekBar = SeekBar(this).apply {
            max = 1000
            progress = 0
        }
        controls.addView(focusSeekBar, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, 38
        ))

        val quickRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }

        val infinity = Button(this).apply {
            text = "∞"
            setOnClickListener { setInfinityFocus() }
        }
        val autoFocus = Button(this).apply {
            text = "AF"
            setOnClickListener { setAutoFocus() }
        }
        val night30 = Button(this).apply {
            text = "30s"
            setOnClickListener { selectShutter(30_000_000_000L) }
        }
        val iso800 = Button(this).apply {
            text = "ISO 800"
            setOnClickListener { selectIso(800) }
        }

        quickRow.addView(infinity, LinearLayout.LayoutParams(0, 44, 1f))
        quickRow.addView(autoFocus, LinearLayout.LayoutParams(0, 44, 1f))
        quickRow.addView(night30, LinearLayout.LayoutParams(0, 44, 1f))
        quickRow.addView(iso800, LinearLayout.LayoutParams(0, 44, 1f))
        controls.addView(quickRow)

        rawCheckBox = CheckBox(this).apply {
            text = "RAW / DNG"
            textSize = 14f
            setTextColor(0xFFFFFFFF.toInt())
            isEnabled = false
        }
        controls.addView(rawCheckBox, lpWrap())

        infoLabel = label("Analyserar kameran...").apply {
            textSize = 12f
            setTextColor(0xFFAAAAAA.toInt())
            gravity = Gravity.CENTER
        }
        controls.addView(infoLabel, lpWrap())

        statusLabel = label("READY").apply {
            textSize = 12f
            setTextColor(0xFFBBBBBB.toInt())
            gravity = Gravity.CENTER
        }
        controls.addView(statusLabel, lpWrap())

        val shutterArea = FrameLayout(this)

        val captureButton = TextView(this).apply {
            text = "●"
            textSize = 38f
            setTextColor(0xFF000000.toInt())
            gravity = Gravity.CENTER
            background = android.graphics.drawable.GradientDrawable().apply {
                shape = android.graphics.drawable.GradientDrawable.OVAL
                setColor(0xFFFFFFFF.toInt())
                setStroke(7, 0xFFAAAAAA.toInt())
            }
            isClickable = true
            setOnClickListener { capturePhoto() }
            setOnTouchListener { view, event ->
                when (event.action) {
                    MotionEvent.ACTION_DOWN -> {
                        view.scaleX = 0.90f
                        view.scaleY = 0.90f
                    }
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                        view.scaleX = 1f
                        view.scaleY = 1f
                    }
                }
                false
            }
        }

        shutterArea.addView(captureButton, FrameLayout.LayoutParams(82, 82).apply {
            gravity = Gravity.CENTER
        })

        controls.addView(shutterArea, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, 90
        ))

        val captureText = TextView(this).apply {
            text = "CAPTURE"
            textSize = 12f
            setTextColor(0xFFFFFFFF.toInt())
            gravity = Gravity.CENTER
        }
        controls.addView(captureText, lpWrap())

        root.addView(controls, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ))

        setContentView(root)

        focusSeekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(bar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (minFocusDistance > 0f && fromUser) {
                    val focus = minFocusDistance * progress / 1000f
                    focusLabel.text = String.format(Locale.US, "FOCUS: %.2f", focus)
                    updatePreview()
                }
            }
            override fun onStartTrackingTouch(bar: SeekBar?) {}
            override fun onStopTrackingTouch(bar: SeekBar?) {}
        })
    }

    private fun label(textValue: String) = TextView(this).apply {
        text = textValue
        textSize = 14f
        setTextColor(0xFFFFFFFF.toInt())
        gravity = Gravity.CENTER_VERTICAL
        setPadding(6, 0, 6, 0)
    }

    private fun lpWrap() = LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT,
        ViewGroup.LayoutParams.WRAP_CONTENT
    )

    private fun openCameraWhenReady() {
        if (!textureView.isAvailable || cameraDevice != null) return

        try {
            findBackCamera()
            val id = cameraId ?: return

            if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
                != PackageManager.PERMISSION_GRANTED) return

            cameraManager.openCamera(id, object : CameraDevice.StateCallback() {
                override fun onOpened(camera: CameraDevice) {
                    cameraDevice = camera
                    createCameraSession()
                }
                override fun onDisconnected(camera: CameraDevice) {
                    camera.close()
                    cameraDevice = null
                }
                override fun onError(camera: CameraDevice, error: Int) {
                    camera.close()
                    cameraDevice = null
                    toast("Kamerafel: $error")
                }
            }, cameraHandler)
        } catch (e: Exception) {
            toast("Kunde inte öppna kameran: ${e.message}")
        }
    }

    private fun findBackCamera() {
        for (id in cameraManager.cameraIdList) {
            val c = cameraManager.getCameraCharacteristics(id)
            if (c.get(CameraCharacteristics.LENS_FACING)
                == CameraCharacteristics.LENS_FACING_BACK) {

                cameraId = id
                characteristics = c

                c.get(CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE)?.let {
                    exposureMin = it.lower
                    exposureMax = minOf(it.upper, 120_000_000_000L)
                }

                c.get(CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE)?.let {
                    isoMin = it.lower
                    isoMax = minOf(it.upper, 6400)
                }

                minFocusDistance =
                    c.get(CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE) ?: 0f

                rawSupported = c.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES)
                    ?.contains(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_RAW) == true

                runOnUiThread { setupControls() }
                return
            }
        }
    }

    private fun setupControls() {
        val isoValues = listOf(50, 100, 200, 400, 800, 1600, 3200, 6400)
            .filter { it in isoMin..isoMax }
            .ifEmpty { listOf(isoMin) }

        isoSpinner.adapter = ArrayAdapter(
            this, android.R.layout.simple_spinner_dropdown_item,
            isoValues.map { "ISO $it" }
        )

        selectedIso = isoValues.firstOrNull { it >= 100 } ?: isoValues.first()
        isoSpinner.setSelection(isoValues.indexOf(selectedIso).coerceAtLeast(0))
        isoSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) {
                selectedIso = isoValues[pos]
                updatePreview()
            }
            override fun onNothingSelected(p: AdapterView<*>?) {}
        }

        val shutterValues = listOf(
            125_000_000L to "1/8s",
            250_000_000L to "1/4s",
            500_000_000L to "1/2s",
            1_000_000_000L to "1s",
            2_000_000_000L to "2s",
            4_000_000_000L to "4s",
            8_000_000_000L to "8s",
            15_000_000_000L to "15s",
            30_000_000_000L to "30s",
            60_000_000_000L to "60s",
            120_000_000_000L to "120s"
        ).filter { it.first in exposureMin..exposureMax }
            .ifEmpty { listOf(exposureMin to formatExposure(exposureMin)) }

        shutterSpinner.adapter = ArrayAdapter(
            this, android.R.layout.simple_spinner_dropdown_item,
            shutterValues.map { it.second }
        )

        selectedExposureNs = shutterValues.firstOrNull { it.first == 1_000_000_000L }?.first
            ?: shutterValues.first().first

        shutterSpinner.setSelection(
            shutterValues.indexOfFirst { it.first == selectedExposureNs }.coerceAtLeast(0)
        )
        shutterSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) {
                selectedExposureNs = shutterValues[pos].first
                updatePreview()
            }
            override fun onNothingSelected(p: AdapterView<*>?) {}
        }

        focusSeekBar.isEnabled = minFocusDistance > 0f
        rawCheckBox.isEnabled = rawSupported
        rawCheckBox.isChecked = rawSupported

        infoLabel.text = "ISO $isoMin–$isoMax  •  ${formatExposure(exposureMin)}–${formatExposure(exposureMax)}" +
            if (rawSupported) "  •  RAW OK" else "  •  JPEG"
    }

    private fun createCameraSession() {
        val camera = cameraDevice ?: return
        val chars = characteristics ?: return
        val map = chars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP) ?: return

        try {
            val jpegSizes = map.getOutputSizes(ImageFormat.JPEG)
            val jpegSize = jpegSizes
                ?.minByOrNull { abs((it.width * it.height) - 12_000_000) }
                ?: android.util.Size(1920, 1080)

            val previewSize = map.getOutputSizes(SurfaceTexture::class.java)
                ?.minByOrNull { abs((it.width * it.height) - 2_000_000) }
                ?: android.util.Size(1920, 1080)

            textureView.surfaceTexture?.setDefaultBufferSize(
                previewSize.width, previewSize.height
            )

            val previewSurface = Surface(textureView.surfaceTexture)

            jpegReader = ImageReader.newInstance(
                jpegSize.width, jpegSize.height, ImageFormat.JPEG, 2
            ).also { reader ->
                reader.setOnImageAvailableListener({ r ->
                    r.acquireLatestImage()?.let { saveJpeg(it) }
                }, cameraHandler)
            }

            val surfaces = mutableListOf(previewSurface, jpegReader!!.surface)

            if (rawSupported) {
                map.getOutputSizes(ImageFormat.RAW_SENSOR)
                    ?.maxByOrNull { it.width * it.height }
                    ?.let { rawSize ->
                        rawReader = ImageReader.newInstance(
                            rawSize.width, rawSize.height, ImageFormat.RAW_SENSOR, 2
                        ).also { reader ->
                            reader.setOnImageAvailableListener({ r ->
                                r.acquireLatestImage()?.let { saveDng(it) }
                            }, cameraHandler)
                        }
                        surfaces.add(rawReader!!.surface)
                    }
            }

            camera.createCaptureSession(
                surfaces,
                object : CameraCaptureSession.StateCallback() {
                    override fun onConfigured(session: CameraCaptureSession) {
                        captureSession = session
                        previewBuilder = camera.createCaptureRequest(
                            CameraDevice.TEMPLATE_PREVIEW
                        ).apply {
                            addTarget(previewSurface)
                        }
                        setAutoFocus()
                    }

                    override fun onConfigureFailed(session: CameraCaptureSession) {
                        toast("Kunde inte konfigurera kameran.")
                    }
                },
                cameraHandler
            )
        } catch (e: Exception) {
            toast("Kamerakonfiguration misslyckades: ${e.message}")
        }
    }

    private fun updatePreview() {
        val session = captureSession ?: return
        val builder = previewBuilder ?: return

        try {
            builder.set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_AUTO)
            builder.set(
                CaptureRequest.CONTROL_AF_MODE,
                if (focusSeekBar.progress == 0)
                    CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE
                else CaptureRequest.CONTROL_AF_MODE_OFF
            )

            if (minFocusDistance > 0f && focusSeekBar.progress > 0) {
                builder.set(
                    CaptureRequest.LENS_FOCUS_DISTANCE,
                    minFocusDistance * focusSeekBar.progress / 1000f
                )
            }

            session.setRepeatingRequest(builder.build(), null, cameraHandler)
        } catch (_: Exception) {}
    }

    private fun focusAtTouch(x: Float, y: Float) {
        if (minFocusDistance <= 0f) return

        // Manual focus is represented by the seekbar; touching the preview
        // switches to a useful near/mid focus point instead of leaving AUTO.
        val progress = ((y / textureView.height.coerceAtLeast(1)) * 1000f)
            .toInt().coerceIn(1, 1000)

        focusSeekBar.progress = progress
        focusLabel.text = "FOCUS: MANUAL"
        updatePreview()
    }

    private fun setInfinityFocus() {
        if (minFocusDistance <= 0f) return
        focusSeekBar.progress = 1
        focusLabel.text = "FOCUS: ∞"
        updatePreview()
    }

    private fun setAutoFocus() {
        focusSeekBar.progress = 0
        focusLabel.text = "FOCUS: AUTO"
        updatePreview()
    }

    private fun selectIso(value: Int) {
        val min = isoMin
        val max = isoMax
        if (value !in min..max) return
        selectedIso = value
        val adapter = isoSpinner.adapter ?: return
        for (i in 0 until adapter.count) {
            if (adapter.getItem(i).toString() == "ISO $value") {
                isoSpinner.setSelection(i)
                break
            }
        }
    }

    private fun selectShutter(value: Long) {
        if (value !in exposureMin..exposureMax) return
        selectedExposureNs = value
        val adapter = shutterSpinner.adapter ?: return
        val wanted = formatExposure(value)
        for (i in 0 until adapter.count) {
            if (adapter.getItem(i).toString() == wanted) {
                shutterSpinner.setSelection(i)
                break
            }
        }
    }

    private fun capturePhoto() {
        val camera = cameraDevice ?: return
        val session = captureSession ?: return
        if (isCapturing) return

        isCapturing = true
        statusLabel.text = "EXPOSING • ${formatExposure(selectedExposureNs)}"

        try {
            val builder = camera.createCaptureRequest(
                CameraDevice.TEMPLATE_STILL_CAPTURE
            )

            builder.addTarget(jpegReader!!.surface)

            if (rawSupported && rawCheckBox.isChecked && rawReader != null) {
                builder.addTarget(rawReader!!.surface)
            }

            builder.set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_OFF)
            builder.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_OFF)
            builder.set(CaptureRequest.SENSOR_EXPOSURE_TIME, selectedExposureNs)
            builder.set(CaptureRequest.SENSOR_SENSITIVITY, selectedIso)
            builder.set(CaptureRequest.JPEG_ORIENTATION, jpegOrientation())

            if (minFocusDistance > 0f && focusSeekBar.progress > 0) {
                builder.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_OFF)
                builder.set(
                    CaptureRequest.LENS_FOCUS_DISTANCE,
                    minFocusDistance * focusSeekBar.progress / 1000f
                )
            }

            session.capture(builder.build(), object : CameraCaptureSession.CaptureCallback() {
                override fun onCaptureCompleted(
                    session: CameraCaptureSession,
                    request: CaptureRequest,
                    result: TotalCaptureResult
                ) {
                    lastCaptureResult = result
                    runOnUiThread {
                        statusLabel.text = "SAVED • ISO $selectedIso • ${formatExposure(selectedExposureNs)}"
                        isCapturing = false
                    }
                }

              override fun onCaptureFailed(
    session: CameraCaptureSession,
    request: CaptureRequest,
    failure: CameraCaptureSession.CaptureFailure
) {
    runOnUiThread {
        statusLabel.text = "CAPTURE FAILED"
        isCapturing = false
    }
}
            }, cameraHandler)
        } catch (e: Exception) {
            isCapturing = false
            statusLabel.text = "READY"
            toast("Fotograferingen misslyckades: ${e.message}")
        }
    }

    private fun saveJpeg(image: Image) {
        val buffer = image.planes[0].buffer
        val bytes = ByteArray(buffer.remaining())
        buffer.get(bytes)
        image.close()

        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME,
                "NightLab_${System.currentTimeMillis()}.jpg")
            put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/NightLab")
                put(MediaStore.Images.Media.IS_PENDING, 1)
            }
        }

        val resolver = contentResolver
        val uri = resolver.insert(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values
        ) ?: return

        try {
            resolver.openOutputStream(uri)?.use { it.write(bytes) }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                values.clear()
                values.put(MediaStore.Images.Media.IS_PENDING, 0)
                resolver.update(uri, values, null, null)
            }
            runOnUiThread { statusLabel.text = "SAVED • JPEG + DNG" }
        } catch (e: Exception) {
            resolver.delete(uri, null, null)
        }
    }

    private fun saveDng(image: Image) {
        val result = lastCaptureResult ?: run {
            image.close()
            return
        }
        val chars = characteristics ?: run {
            image.close()
            return
        }

        val values = ContentValues().apply {
            put(MediaStore.Files.FileColumns.DISPLAY_NAME,
                "NightLab_${System.currentTimeMillis()}.dng")
            put(MediaStore.Files.FileColumns.MIME_TYPE, "image/x-adobe-dng")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.Files.FileColumns.RELATIVE_PATH, "Pictures/NightLab")
                put(MediaStore.Files.FileColumns.IS_PENDING, 1)
            }
        }

        val collection = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        } else {
            MediaStore.Files.getContentUri("external")
        }

        val uri: Uri? = contentResolver.insert(collection, values)

        if (uri == null) {
            image.close()
            return
        }

        try {
            contentResolver.openOutputStream(uri)?.use { output ->
                DngCreator(chars, result).use { creator ->
                    creator.writeImage(output, image)
                }
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                values.clear()
                values.put(MediaStore.Files.FileColumns.IS_PENDING, 0)
                contentResolver.update(uri, values, null, null)
            }
        } catch (_: Exception) {
            contentResolver.delete(uri, null, null)
        } finally {
            image.close()
        }
    }

    private fun jpegOrientation(): Int {
        val rotation = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            display?.rotation ?: Surface.ROTATION_0
        } else {
            @Suppress("DEPRECATION")
            windowManager.defaultDisplay.rotation
        }

        val degrees = when (rotation) {
            Surface.ROTATION_0 -> 0
            Surface.ROTATION_90 -> 90
            Surface.ROTATION_180 -> 180
            Surface.ROTATION_270 -> 270
            else -> 0
        }

        val sensor = characteristics?.get(
            CameraCharacteristics.SENSOR_ORIENTATION
        ) ?: 90

        return (sensor + degrees) % 360
    }

    private fun formatExposure(ns: Long): String {
        val seconds = ns / 1_000_000_000.0
        return when {
            seconds >= 1.0 -> if (seconds == seconds.toLong().toDouble())
                "${seconds.toLong()}s"
            else String.format(Locale.US, "%.1fs", seconds)
            seconds > 0 -> String.format(Locale.US, "1/%.0fs", 1.0 / seconds)
            else -> "AUTO"
        }
    }

    private fun closeCamera() {
        try { captureSession?.close() } catch (_: Exception) {}
        captureSession = null
        try { cameraDevice?.close() } catch (_: Exception) {}
        cameraDevice = null
        try { jpegReader?.close() } catch (_: Exception) {}
        jpegReader = null
        try { rawReader?.close() } catch (_: Exception) {}
        rawReader = null
    }

    private fun toast(message: String) {
        runOnUiThread {
            Toast.makeText(this, message, Toast.LENGTH_LONG).show()
        }
    }
}
