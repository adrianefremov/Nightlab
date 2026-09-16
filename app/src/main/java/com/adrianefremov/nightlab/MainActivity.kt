package com.adrianefremov.nightlab

import android.Manifest
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureFailure
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.TotalCaptureResult
import android.hardware.camera2.DngCreator
import android.media.Image
import android.media.ImageFormat
import android.media.ImageReader
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.provider.MediaStore
import android.view.Gravity
import android.view.Surface
import android.view.TextureView
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStream
import java.util.Locale
import kotlin.math.roundToInt

class MainActivity : ComponentActivity() {

    private lateinit var textureView: TextureView
    private lateinit var statusText: TextView
    private lateinit var isoSpinner: Spinner
    private lateinit var shutterSpinner: Spinner
    private lateinit var focusBar: SeekBar
    private lateinit var focusText: TextView
    private lateinit var rawCheck: CheckBox
    private lateinit var captureButton: Button

    private lateinit var cameraManager: CameraManager
    private var cameraDevice: CameraDevice? = null
    private var captureSession: CameraCaptureSession? = null
    private var characteristics: CameraCharacteristics? = null
    private var cameraId: String? = null

    private var previewReader: ImageReader? = null
    private var jpegReader: ImageReader? = null
    private var rawReader: ImageReader? = null

    private var cameraThread: HandlerThread? = null
    private var cameraHandler: Handler? = null

    private var sensorWidth = 1920
    private var sensorHeight = 1080
    private var previewWidth = 1920
    private var previewHeight = 1080

    private var isoValues = listOf(100)
    private var shutterValuesNs = listOf(1_000_000_000L)

    private var selectedIso = 100
    private var selectedExposureNs = 1_000_000_000L
    private var focusDistance = 0f

    private var minimumFocusDistance = 0f
    private var rawSupported = false

    private var pendingJpegUri: Uri? = null
    private var pendingDngUri: Uri? = null
    private var pendingCaptureResult: TotalCaptureResult? = null
    private var pendingRawImage: Image? = null

    private var isCapturing = false
    private var cameraOpening = false

    private val cameraPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) {
                startCameraThread()
                openCameraWhenReady()
            } else {
                statusText.text = "KAMERABEHEHÖRIGHET KRÄVS"
                Toast.makeText(
                    this,
                    "NightLab behöver kamerabehörighet.",
                    Toast.LENGTH_LONG
                ).show()
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        createInterface()

        cameraManager = getSystemService(Context.CAMERA_SERVICE) as CameraManager

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) ==
            PackageManager.PERMISSION_GRANTED
        ) {
            startCameraThread()
        } else {
            cameraPermission.launch(Manifest.permission.CAMERA)
        }
    }

    override fun onResume() {
        super.onResume()

        if (::textureView.isInitialized && textureView.isAvailable &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) ==
            PackageManager.PERMISSION_GRANTED
        ) {
            startCameraThread()
            openCameraWhenReady()
        }
    }

    override fun onPause() {
        closeCamera()
        super.onPause()
    }

    override fun onDestroy() {
        closeCamera()
        stopCameraThread()
        super.onDestroy()
    }

    private fun createInterface() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(0xFF050505.toInt())
        }

        val title = TextView(this).apply {
            text = "NIGHTLAB"
            textSize = 27f
            setTextColor(0xFFFFFFFF.toInt())
            gravity = Gravity.CENTER
            setPadding(0, 18, 0, 2)
        }

        val mode = TextView(this).apply {
            text = "PRO  •  NIGHT  •  CAMERA2"
            textSize = 15f
            setTextColor(0xFFD0D0D0.toInt())
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, 12)
        }

        textureView = TextureView(this).apply {
            surfaceTextureListener = surfaceListener
            keepScreenOn = true
        }

        statusText = TextView(this).apply {
            text = "STARTAR KAMERA…"
            textSize = 13f
            setTextColor(0xFFBDBDBD.toInt())
            gravity = Gravity.CENTER
            setPadding(0, 8, 0, 4)
        }

        val controls = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(18, 4, 18, 8)
        }

        val row1 = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }

        isoSpinner = Spinner(this)
        shutterSpinner = Spinner(this)

        row1.addView(
            labeledControl("ISO", isoSpinner),
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        )

        row1.addView(
            labeledControl("SHUTTER", shutterSpinner),
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        )

        controls.addView(row1)

        focusText = TextView(this).apply {
            text = "FOCUS: AUTO"
            textSize = 15f
            setTextColor(0xFFFFFFFF.toInt())
            gravity = Gravity.CENTER
            setPadding(0, 8, 0, 2)
        }

        focusBar = SeekBar(this).apply {
            max = 100
            progress = 0
        }

        controls.addView(focusText)
        controls.addView(focusBar)

        val row2 = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        rawCheck = CheckBox(this).apply {
            text = "RAW / DNG"
            textSize = 15f
            setTextColor(0xFFFFFFFF.toInt())
            isChecked = false
        }

        val galleryButton = Button(this).apply {
            text = "SENASTE BILDER"
            setOnClickListener { openPicturesFolder() }
        }

        row2.addView(
            rawCheck,
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        )
        row2.addView(galleryButton)

        controls.addView(row2)

        captureButton = Button(this).apply {
            text = "  ●  TA BILD  "
            textSize = 21f
            setAllCaps(false)
            setOnClickListener { capturePhoto() }
        }

        controls.addView(
            captureButton,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        )

        root.addView(
            title,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        )
        root.addView(
            mode,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        )
        root.addView(
            textureView,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                0,
                1f
            )
        )
        root.addView(
            statusText,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        )
        root.addView(
            controls,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        )

        setContentView(root)

        isoSpinner.onItemSelectedListener = SimpleItemSelectedListener {
            if (isoValues.isNotEmpty()) {
                selectedIso = isoValues[it.coerceIn(0, isoValues.lastIndex)]
                updatePreview()
            }
        }

        shutterSpinner.onItemSelectedListener = SimpleItemSelectedListener {
            if (shutterValuesNs.isNotEmpty()) {
                selectedExposureNs =
                    shutterValuesNs[it.coerceIn(0, shutterValuesNs.lastIndex)]
                updatePreview()
            }
        }

        focusBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(
                seekBar: SeekBar,
                progress: Int,
                fromUser: Boolean
            ) {
                if (minimumFocusDistance > 0f) {
                    focusDistance =
                        minimumFocusDistance * (progress / 100f)

                    focusText.text =
                        String.format(Locale.US, "FOCUS: %.2f", focusDistance)

                    if (fromUser) updatePreview()
                } else {
                    focusText.text = "FOCUS: AUTO"
                }
            }

            override fun onStartTrackingTouch(seekBar: SeekBar) = Unit
            override fun onStopTrackingTouch(seekBar: SeekBar) = Unit
        })
    }

    private fun labeledControl(label: String, spinner: Spinner): LinearLayout {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
        }

        val title = TextView(this).apply {
            text = label
            textSize = 11f
            setTextColor(0xFFAAAAAA.toInt())
            gravity = Gravity.CENTER
        }

        box.addView(title)
        box.addView(
            spinner,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        )

        return box
    }

    private val surfaceListener = object : TextureView.SurfaceTextureListener {
        override fun onSurfaceTextureAvailable(
            surface: SurfaceTexture,
            width: Int,
            height: Int
        ) {
            openCameraWhenReady()
        }

        override fun onSurfaceTextureSizeChanged(
            surface: SurfaceTexture,
            width: Int,
            height: Int
        ) = Unit

        override fun onSurfaceTextureDestroyed(surface: SurfaceTexture): Boolean {
            closeCamera()
            return true
        }

        override fun onSurfaceTextureUpdated(surface: SurfaceTexture) = Unit
    }

    private fun startCameraThread() {
        if (cameraThread != null) return

        cameraThread = HandlerThread("NightLabCamera").also { it.start() }
        cameraHandler = Handler(cameraThread!!.looper)
    }

    private fun stopCameraThread() {
        cameraThread?.quitSafely()
        try {
            cameraThread?.join()
        } catch (_: InterruptedException) {
        }
        cameraThread = null
        cameraHandler = null
    }

    private fun openCameraWhenReady() {
        if (!::textureView.isInitialized ||
            !textureView.isAvailable ||
            cameraDevice != null ||
            cameraOpening ||
            ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            return
        }

        if (cameraHandler == null) startCameraThread()

        try {
            val id = findBackCamera()
            cameraId = id

            if (id == null) {
                statusText.text = "INGEN BAKKAMERA HITTADES"
                return
            }

            val chars = cameraManager.getCameraCharacteristics(id)
            characteristics = chars

            configureCapabilities(chars)

            cameraOpening = true
            cameraManager.openCamera(id, cameraStateCallback, cameraHandler)
        } catch (e: Exception) {
            cameraOpening = false
            statusText.text = "KAMERAÖPPNING MISSLYCKADES"
            toast("Kunde inte öppna kameran: ${e.message ?: "okänt fel"}")
        }
    }

    private fun findBackCamera(): String? {
        for (id in cameraManager.cameraIdList) {
            val chars = cameraManager.getCameraCharacteristics(id)
            val facing = chars.get(CameraCharacteristics.LENS_FACING)
            if (facing == CameraCharacteristics.LENS_FACING_BACK) {
                return id
            }
        }
        return null
    }

    private fun configureCapabilities(chars: CameraCharacteristics) {
        val isoRange =
            chars.get(CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE)

        val minIso = isoRange?.lower ?: 100
        val maxIso = minOf(isoRange?.upper ?: 6400, 6400)

        isoValues = listOf(50, 100, 200, 400, 800, 1600, 3200, 6400)
            .filter { it in minIso..maxIso }
            .ifEmpty { listOf(minIso) }

        selectedIso = isoValues.firstOrNull { it == 100 } ?: isoValues.first()

        val exposureRange =
            chars.get(CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE)

        val minExposure =
            exposureRange?.lower ?: 1_000_000L

        val maxExposure =
            minOf(
                exposureRange?.upper ?: 30_000_000_000L,
                120_000_000_000L
            )

        val requested = listOf(
            125_000_000L,
            250_000_000L,
            500_000_000L,
            1_000_000_000L,
            2_000_000_000L,
            4_000_000_000L,
            8_000_000_000L,
            15_000_000_000L,
            30_000_000_000L,
            60_000_000_000L,
            120_000_000_000L
        )

        shutterValuesNs = requested
            .filter { it in minExposure..maxExposure }
            .ifEmpty { listOf(maxExposure.coerceAtLeast(minExposure)) }

        selectedExposureNs =
            shutterValuesNs.firstOrNull { it == 1_000_000_000L }
                ?: shutterValuesNs.first()

        minimumFocusDistance =
            chars.get(CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE) ?: 0f

        rawSupported =
            chars.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES)
                ?.contains(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_RAW) == true

        runOnUiThread {
            configureSpinner(
                isoSpinner,
                isoValues.map { "ISO $it" },
                isoValues.indexOf(selectedIso).coerceAtLeast(0)
            )

            configureSpinner(
                shutterSpinner,
                shutterValuesNs.map { formatExposure(it) },
                shutterValuesNs.indexOf(selectedExposureNs).coerceAtLeast(0)
            )

            rawCheck.isEnabled = rawSupported
            if (!rawSupported) rawCheck.isChecked = false

            focusBar.isEnabled = minimumFocusDistance > 0f
            focusText.text =
                if (minimumFocusDistance > 0f) "FOCUS: 0.00" else "FOCUS: AUTO"

            statusText.text =
                "ISO ${isoValues.first()}–${isoValues.last()} • " +
                    "${formatExposure(shutterValuesNs.first())}–${formatExposure(shutterValuesNs.last())}" +
                    if (rawSupported) " • RAW OK" else " • RAW EJ TILLGÄNGLIG"
        }
    }

    private fun configureSpinner(
        spinner: Spinner,
        values: List<String>,
        selected: Int
    ) {
        val adapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_item,
            values
        )
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        spinner.adapter = adapter
        spinner.setSelection(selected, false)
    }

    private val cameraStateCallback = object : CameraDevice.StateCallback() {
        override fun onOpened(camera: CameraDevice) {
            cameraOpening = false
            cameraDevice = camera
            createReaders()
            createCaptureSession()
        }

        override fun onDisconnected(camera: CameraDevice) {
            cameraOpening = false
            camera.close()
            cameraDevice = null
            statusText.text = "KAMERA FRÅNKOPPLAD"
        }

        override fun onError(camera: CameraDevice, error: Int) {
            cameraOpening = false
            camera.close()
            cameraDevice = null
            statusText.text = "KAMERAFEL: $error"
        }
    }

    private fun createReaders() {
        jpegReader?.close()
        rawReader?.close()

        val size = chooseOutputSize(
            characteristics?.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
                ?.getOutputSizes(ImageFormat.JPEG)
        )

        sensorWidth = size.first
        sensorHeight = size.second

        jpegReader = ImageReader.newInstance(
            size.first,
            size.second,
            ImageFormat.JPEG,
            2
        )

        jpegReader?.setOnImageAvailableListener({ reader ->
            val image = reader.acquireLatestImage() ?: return@setOnImageAvailableListener
            try {
                saveJpeg(image)
            } finally {
                image.close()
            }
        }, cameraHandler)

        if (rawSupported) {
            val rawSize = chooseOutputSize(
                characteristics?.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
                    ?.getOutputSizes(ImageFormat.RAW_SENSOR)
            )

            if (rawSize.first > 0 && rawSize.second > 0) {
                rawReader = ImageReader.newInstance(
                    rawSize.first,
                    rawSize.second,
                    ImageFormat.RAW_SENSOR,
                    2
                )

                rawReader?.setOnImageAvailableListener({ reader ->
                    val image = reader.acquireLatestImage() ?: return@setOnImageAvailableListener

                    if (!rawCheck.isChecked || pendingCaptureResult == null) {
                        image.close()
                        return@setOnImageAvailableListener
                    }

                    pendingRawImage?.close()
                    pendingRawImage = image

                    savePendingDngIfReady()
                }, cameraHandler)
            }
        }
    }

    private fun chooseOutputSize(sizes: Array<android.util.Size>?): Pair<Int, Int> {
        if (sizes.isNullOrEmpty()) return Pair(1920, 1080)

        val largest = sizes.maxByOrNull { it.width.toLong() * it.height.toLong() }
            ?: return Pair(1920, 1080)

        return Pair(largest.width, largest.height)
    }

    private fun createCaptureSession() {
        val device = cameraDevice ?: return
        val texture = textureView.surfaceTexture ?: return
        val jpegSurface = jpegReader?.surface ?: return

        previewWidth = textureView.width.coerceAtLeast(1280)
        previewHeight = textureView.height.coerceAtLeast(720)

        texture.setDefaultBufferSize(previewWidth, previewHeight)

        val previewSurface = Surface(texture)

        val surfaces = mutableListOf<Surface>()
        surfaces.add(previewSurface)
        surfaces.add(jpegSurface)

        rawReader?.surface?.let { surfaces.add(it) }

        try {
            device.createCaptureSession(
                surfaces,
                object : CameraCaptureSession.StateCallback() {
                    override fun onConfigured(session: CameraCaptureSession) {
                        captureSession = session
                        startPreview()
                    }

                    override fun onConfigureFailed(session: CameraCaptureSession) {
                        statusText.text = "KAMERASESSION MISSLYCKADES"
                    }
                },
                cameraHandler
            )
        } catch (e: Exception) {
            statusText.text = "SESSIONFEL"
        }
    }

    private fun startPreview() {
        val device = cameraDevice ?: return
        val session = captureSession ?: return
        val texture = textureView.surfaceTexture ?: return

        texture.setDefaultBufferSize(previewWidth, previewHeight)
        val surface = Surface(texture)

        try {
            val builder = device.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW)
            builder.addTarget(surface)

            builder.set(
                CaptureRequest.CONTROL_MODE,
                CameraMetadataCompat.CONTROL_MODE_AUTO
            )

            builder.set(
                CaptureRequest.CONTROL_AF_MODE,
                if (minimumFocusDistance > 0f)
                    CaptureRequest.CONTROL_AF_MODE_OFF
                else
                    CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE
            )

            if (minimumFocusDistance > 0f) {
                builder.set(
                    CaptureRequest.LENS_FOCUS_DISTANCE,
                    focusDistance
                )
            }

            session.setRepeatingRequest(
                builder.build(),
                null,
                cameraHandler
            )

            statusText.text =
                "READY • ISO $selectedIso • ${formatExposure(selectedExposureNs)}"
        } catch (e: Exception) {
            statusText.text = "PREVIEWFEL"
        }
    }

    private fun updatePreview() {
        if (cameraDevice == null || captureSession == null) return
        startPreview()
    }

    private fun capturePhoto() {
        if (isCapturing) return

        val device = cameraDevice ?: run {
            toast("Kameran är inte redo.")
            return
        }

        val session = captureSession ?: run {
            toast("Kamerasessionen är inte redo.")
            return
        }

        val jpegSurface = jpegReader?.surface ?: run {
            toast("JPEG-utgång saknas.")
            return
        }

        isCapturing = true
        captureButton.isEnabled = false

        pendingCaptureResult = null
        pendingRawImage?.close()
        pendingRawImage = null

        pendingJpegUri = createMediaStoreUri(
            displayName = "NightLab_${System.currentTimeMillis()}.jpg",
            mimeType = "image/jpeg"
        )

        pendingDngUri =
            if (rawCheck.isChecked && rawSupported)
                createMediaStoreUri(
                    displayName = "NightLab_${System.currentTimeMillis()}.dng",
                    mimeType = "image/x-adobe-dng"
                )
            else null

        try {
            val builder =
                device.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE)

            builder.addTarget(jpegSurface)

            if (rawCheck.isChecked && rawSupported) {
                rawReader?.surface?.let { builder.addTarget(it) }
            }

            builder.set(
                CaptureRequest.CONTROL_MODE,
                CameraMetadataCompat.CONTROL_MODE_OFF
            )

            builder.set(
                CaptureRequest.CONTROL_AE_MODE,
                CaptureRequest.CONTROL_AE_MODE_OFF
            )

            builder.set(
                CaptureRequest.SENSOR_EXPOSURE_TIME,
                selectedExposureNs
            )

            builder.set(
                CaptureRequest.SENSOR_SENSITIVITY,
                selectedIso
            )

            if (minimumFocusDistance > 0f) {
                builder.set(
                    CaptureRequest.CONTROL_AF_MODE,
                    CaptureRequest.CONTROL_AF_MODE_OFF
                )
                builder.set(
                    CaptureRequest.LENS_FOCUS_DISTANCE,
                    focusDistance
                )
            }

            builder.set(
                CaptureRequest.JPEG_ORIENTATION,
                getJpegOrientation()
            )

            session.capture(
                builder.build(),
                object : CameraCaptureSession.CaptureCallback() {

                    override fun onCaptureCompleted(
                        session: CameraCaptureSession,
                        request: CaptureRequest,
                        result: TotalCaptureResult
                    ) {
                        pendingCaptureResult = result
                        savePendingDngIfReady()

                        runOnUiThread {
                            statusText.text =
                                "CAPTURED • ISO $selectedIso • ${formatExposure(selectedExposureNs)}"
                        }

                        finishCaptureSoon()
                    }

                    override fun onCaptureFailed(
                        session: CameraCaptureSession,
                        request: CaptureRequest,
                        failure: CaptureFailure
                    ) {
                        markMediaStoreFailed(pendingJpegUri)
                        markMediaStoreFailed(pendingDngUri)

                        pendingJpegUri = null
                        pendingDngUri = null
                        pendingCaptureResult = null

                        runOnUiThread {
                            isCapturing = false
                            captureButton.isEnabled = true
                            statusText.text = "CAPTURE FAILED"
                            toast("Fotograferingen misslyckades.")
                        }
                    }
                },
                cameraHandler
            )
        } catch (e: Exception) {
            markMediaStoreFailed(pendingJpegUri)
            markMediaStoreFailed(pendingDngUri)
            pendingJpegUri = null
            pendingDngUri = null
            pendingCaptureResult = null
            isCapturing = false
            captureButton.isEnabled = true
            statusText.text = "CAPTURE ERROR"
            toast("Capture-fel: ${e.message ?: "okänt fel"}")
        }
    }

    private fun saveJpeg(image: Image) {
        val uri = pendingJpegUri ?: return

        try {
            val buffer = image.planes[0].buffer
            contentResolver.openOutputStream(uri)?.use { output ->
                val bytes = ByteArray(buffer.remaining())
                buffer.get(bytes)
                output.write(bytes)
            }

            finishMediaStoreItem(uri)
            pendingJpegUri = null

            runOnUiThread {
                statusText.text =
                    "SPARAD • Galleri/Pictures/NightLab • JPEG"
            }
        } catch (e: Exception) {
            markMediaStoreFailed(uri)
            pendingJpegUri = null
            runOnUiThread {
                toast("Kunde inte spara JPEG.")
            }
        }
    }

    private fun savePendingDngIfReady() {
        val uri = pendingDngUri ?: return
        val result = pendingCaptureResult ?: return
        val image = pendingRawImage ?: return
        val chars = characteristics ?: return

        try {
            contentResolver.openOutputStream(uri)?.use { output ->
                val creator = DngCreator(chars, result)
                creator.use {
                    it.writeImage(output, image)
                }
            }

            finishMediaStoreItem(uri)

            pendingDngUri = null
            pendingRawImage?.close()
            pendingRawImage = null

            runOnUiThread {
                statusText.text =
                    "SPARAD • Galleri/Pictures/NightLab • JPEG + DNG"
            }
        } catch (e: Exception) {
            markMediaStoreFailed(uri)
            pendingDngUri = null
            pendingRawImage?.close()
            pendingRawImage = null

            runOnUiThread {
                toast("DNG kunde inte sparas: ${e.message ?: "okänt fel"}")
            }
        }
    }

    private fun finishCaptureSoon() {
        cameraHandler?.postDelayed({
            if (rawCheck.isChecked && rawSupported) {
                if (pendingDngUri != null) {
                    // Wait briefly for RAW/DNG if the ImageReader is slower.
                    cameraHandler?.postDelayed({
                        savePendingDngIfReady()
                        finishCaptureState()
                    }, 700)
                } else {
                    finishCaptureState()
                }
            } else {
                finishCaptureState()
            }
        }, 250)
    }

    private fun finishCaptureState() {
        runOnUiThread {
            isCapturing = false
            captureButton.isEnabled = true
        }
    }

    private fun createMediaStoreUri(
        displayName: String,
        mimeType: String
    ): Uri? {
        return try {
            val values = ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, displayName)
                put(MediaStore.Images.Media.MIME_TYPE, mimeType)

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    put(
                        MediaStore.Images.Media.RELATIVE_PATH,
                        "Pictures/NightLab"
                    )
                    put(MediaStore.Images.Media.IS_PENDING, 1)
                }
            }

            contentResolver.insert(
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                values
            )
        } catch (_: Exception) {
            null
        }
    }

    private fun finishMediaStoreItem(uri: Uri) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val values = ContentValues().apply {
                put(MediaStore.Images.Media.IS_PENDING, 0)
            }
            contentResolver.update(uri, values, null, null)
        }
    }

    private fun markMediaStoreFailed(uri: Uri?) {
        if (uri == null) return
        try {
            contentResolver.delete(uri, null, null)
        } catch (_: Exception) {
        }
    }

    private fun getJpegOrientation(): Int {
        val rotation = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            display?.rotation ?: Surface.ROTATION_0
        } else {
            @Suppress("DEPRECATION")
            windowManager.defaultDisplay.rotation
        }

        val rotationDegrees = when (rotation) {
            Surface.ROTATION_90 -> 90
            Surface.ROTATION_180 -> 180
            Surface.ROTATION_270 -> 270
            else -> 0
        }

        val sensorOrientation =
            characteristics?.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 90

        val facing =
            characteristics?.get(CameraCharacteristics.LENS_FACING)

        return if (facing == CameraCharacteristics.LENS_FACING_FRONT) {
            (sensorOrientation + rotationDegrees) % 360
        } else {
            (sensorOrientation - rotationDegrees + 360) % 360
        }
    }

    private fun openPicturesFolder() {
        val intent = android.content.Intent(
            android.content.Intent.ACTION_VIEW
        ).apply {
            type = "image/*"
            flags = android.content.Intent.FLAG_ACTIVITY_NEW_TASK
        }

        try {
            startActivity(intent)
        } catch (_: Exception) {
            toast("Öppna Galleri och leta efter Pictures/NightLab.")
        }
    }

    private fun closeCamera() {
        try {
            captureSession?.stopRepeating()
        } catch (_: Exception) {
        }

        try {
            captureSession?.close()
        } catch (_: Exception) {
        }
        captureSession = null

        try {
            cameraDevice?.close()
        } catch (_: Exception) {
        }
        cameraDevice = null

        jpegReader?.close()
        jpegReader = null

        rawReader?.close()
        rawReader = null

        previewReader?.close()
        previewReader = null

        pendingRawImage?.close()
        pendingRawImage = null

        cameraOpening = false
        isCapturing = false
    }

    private fun formatExposure(ns: Long): String {
        if (ns >= 1_000_000_000L) {
            val seconds = ns / 1_000_000_000.0
            return if (seconds >= 1.0 && seconds % 1.0 == 0.0) {
                "${seconds.toInt()}s"
            } else {
                String.format(Locale.US, "%.1fs", seconds)
            }
        }

        val ms = ns / 1_000_000.0
        return if (ms >= 1.0) {
            String.format(Locale.US, "%.0fms", ms)
        } else {
            String.format(Locale.US, "%.2fms", ms)
        }
    }

    private fun toast(message: String) {
        runOnUiThread {
            Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
        }
    }

    private class SimpleItemSelectedListener(
        private val action: (Int) -> Unit
    ) : android.widget.AdapterView.OnItemSelectedListener {

        override fun onItemSelected(
            parent: android.widget.AdapterView<*>?,
            view: View?,
            position: Int,
            id: Long
        ) {
            action(position)
        }

        override fun onNothingSelected(
            parent: android.widget.AdapterView<*>?
        ) = Unit
    }

    private object CameraMetadataCompat {
        const val CONTROL_MODE_AUTO = 1
        const val CONTROL_MODE_OFF = 0
    }
}
