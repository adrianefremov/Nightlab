package com.adrianefremov.nightlab

import android.Manifest
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CameraMetadata
import android.hardware.camera2.CaptureFailure
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.DngCreator
import android.hardware.camera2.TotalCaptureResult
import android.media.Image
import android.graphics.ImageFormat
import android.media.ImageReader
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.provider.MediaStore
import android.view.Gravity
import android.view.Surface
import android.view.TextureView
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.AdapterView
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
import java.util.Locale

class MainActivity : ComponentActivity() {

    private lateinit var preview: TextureView
    private lateinit var status: TextView
    private lateinit var isoSpinner: Spinner
    private lateinit var shutterSpinner: Spinner
    private lateinit var focusBar: SeekBar
    private lateinit var focusLabel: TextView
    private lateinit var rawCheck: CheckBox
    private lateinit var captureButton: Button

    private lateinit var cameraManager: CameraManager
    private var camera: CameraDevice? = null
    private var session: CameraCaptureSession? = null
    private var characteristics: CameraCharacteristics? = null
    private var jpegReader: ImageReader? = null
    private var rawReader: ImageReader? = null

    private var cameraThread: HandlerThread? = null
    private var cameraHandler: Handler? = null
    private var opening = false
    private var capturing = false

    private var isoValues = listOf(100)
    private var shutterValuesNs = listOf(1_000_000_000L)

    private var selectedIso = 100
    private var selectedExposureNs = 1_000_000_000L

    private var minimumFocusDistance = 0f
    private var focusDistance = 0f
    private var rawSupported = false

    private var pendingJpegUri: Uri? = null
    private var pendingDngUri: Uri? = null
    private var pendingRawImage: Image? = null
    private var pendingCaptureResult: TotalCaptureResult? = null

    private val cameraPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) {
                startCameraThread()
                openCamera()
            } else {
                status.text = "KAMERABEHEHÖRIGHET KRÄVS"
                toast("NightLab behöver kamerabehörighet.")
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        buildUi()
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
        if (::preview.isInitialized && preview.isAvailable) {
            openCamera()
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

    private fun buildUi() {
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
            text = "PRO  •  NIGHT  •  CAMERA2  •  WB AUTO"
            textSize = 14f
            setTextColor(0xFFD0D0D0.toInt())
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, 10)
        }

        preview = TextureView(this).apply {
            surfaceTextureListener = surfaceListener
            keepScreenOn = true
        }

        status = TextView(this).apply {
            text = "STARTAR KAMERA…"
            textSize = 13f
            setTextColor(0xFFBDBDBD.toInt())
            gravity = Gravity.CENTER
            setPadding(0, 7, 0, 3)
        }

        val controls = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(16, 3, 16, 8)
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

        focusLabel = TextView(this).apply {
            text = "FOCUS: AUTO"
            textSize = 15f
            setTextColor(0xFFFFFFFF.toInt())
            gravity = Gravity.CENTER
            setPadding(0, 7, 0, 1)
        }

        focusBar = SeekBar(this).apply {
            max = 100
            progress = 0
        }

        controls.addView(focusLabel)
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
            text = "GALLERI"
            setOnClickListener { openGallery() }
        }

        row2.addView(
            rawCheck,
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        )
        row2.addView(galleryButton)
        controls.addView(row2)

        captureButton = Button(this).apply {
            text = "  📷  TA BILD  "
            textSize = 21f
            setAllCaps(false)
            minHeight = 64
            setOnClickListener { capturePhoto() }
        }

        controls.addView(
            captureButton,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        )

        root.addView(title, -1, -2)
        root.addView(mode, -1, -2)
        root.addView(preview, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        root.addView(status, -1, -2)
        root.addView(controls, -1, -2)

        setContentView(root)

        isoSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(
                parent: AdapterView<*>?,
                view: android.view.View?,
                position: Int,
                id: Long
            ) {
                if (isoValues.isNotEmpty()) {
                    selectedIso = isoValues[position.coerceIn(0, isoValues.lastIndex)]
                    updatePreview()
                }
            }

            override fun onNothingSelected(parent: AdapterView<*>?) = Unit
        }

        shutterSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(
                parent: AdapterView<*>?,
                view: android.view.View?,
                position: Int,
                id: Long
            ) {
                if (shutterValuesNs.isNotEmpty()) {
                    selectedExposureNs =
                        shutterValuesNs[position.coerceIn(0, shutterValuesNs.lastIndex)]
                    updatePreview()
                }
            }

            override fun onNothingSelected(parent: AdapterView<*>?) = Unit
        }

        focusBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(
                seekBar: SeekBar,
                progress: Int,
                fromUser: Boolean
            ) {
                if (minimumFocusDistance > 0f) {
                    focusDistance = minimumFocusDistance * (progress / 100f)
                    focusLabel.text =
                        String.format(Locale.US, "FOCUS: %.2f", focusDistance)
                    if (fromUser) updatePreview()
                } else {
                    focusLabel.text = "FOCUS: AUTO"
                }
            }

            override fun onStartTrackingTouch(seekBar: SeekBar) = Unit
            override fun onStopTrackingTouch(seekBar: SeekBar) = Unit
        })
    }

    private fun labeledControl(label: String, spinner: Spinner): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER

            addView(
                TextView(this@MainActivity).apply {
                    text = label
                    textSize = 11f
                    setTextColor(0xFFAAAAAA.toInt())
                    gravity = Gravity.CENTER
                }
            )

            addView(
                spinner,
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                )
            )
        }
    }

    private val surfaceListener = object : TextureView.SurfaceTextureListener {
        override fun onSurfaceTextureAvailable(
            surface: SurfaceTexture,
            width: Int,
            height: Int
        ) {
            openCamera()
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

    private fun openCamera() {
        if (!::preview.isInitialized ||
            !preview.isAvailable ||
            camera != null ||
            opening ||
            ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) !=
            PackageManager.PERMISSION_GRANTED
        ) return

        if (cameraHandler == null) startCameraThread()

        try {
            val cameraId = cameraManager.cameraIdList.firstOrNull { id ->
                cameraManager.getCameraCharacteristics(id).get(
                    CameraCharacteristics.LENS_FACING
                ) == CameraCharacteristics.LENS_FACING_BACK
            } ?: run {
                status.text = "INGEN BAKKAMERA HITTADES"
                return
            }

            characteristics = cameraManager.getCameraCharacteristics(cameraId)
            configureCapabilities()

            opening = true
            cameraManager.openCamera(cameraId, cameraStateCallback, cameraHandler)
        } catch (e: Exception) {
            opening = false
            status.text = "KAMERAÖPPNING MISSLYCKADES"
            toast("Kamerafel: ${e.message ?: "okänt fel"}")
        }
    }

    private fun configureCapabilities() {
        val c = characteristics ?: return

        val isoRange = c.get(CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE)
        val minIso = isoRange?.lower ?: 100
        val maxIso = minOf(isoRange?.upper ?: 6400, 6400)

        isoValues = listOf(50, 100, 200, 400, 800, 1600, 3200, 6400)
            .filter { it in minIso..maxIso }
            .ifEmpty { listOf(minIso) }

        selectedIso = isoValues.firstOrNull { it == 100 } ?: isoValues.first()

        val exposureRange = c.get(CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE)
        val minExposure = exposureRange?.lower ?: 1_000_000L
        val maxExposure = minOf(
            exposureRange?.upper ?: 30_000_000_000L,
            120_000_000_000L
        )

        shutterValuesNs = listOf(
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
        ).filter { it in minExposure..maxExposure }
            .ifEmpty { listOf(maxExposure.coerceAtLeast(minExposure)) }

        selectedExposureNs =
            shutterValuesNs.firstOrNull { it == 1_000_000_000L }
                ?: shutterValuesNs.first()

        minimumFocusDistance =
            c.get(CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE) ?: 0f

        val capabilities =
            c.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES) ?: intArrayOf()

        val map =
            c.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)

        val rawSizes = map?.getOutputSizes(ImageFormat.RAW_SENSOR) ?: emptyArray()

        rawSupported =
            capabilities.contains(
                CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_RAW
            ) && rawSizes.isNotEmpty()

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
            if (!rawSupported) {
                rawCheck.isChecked = false
                rawCheck.text = "RAW / DNG (ej stöds)"
            } else {
                rawCheck.text = "RAW / DNG ✓"
            }

            focusBar.isEnabled = minimumFocusDistance > 0f
            focusLabel.text =
                if (minimumFocusDistance > 0f) "FOCUS: 0.00" else "FOCUS: AUTO"

            status.text =
                "ISO ${isoValues.first()}–${isoValues.last()} • " +
                    "${formatExposure(shutterValuesNs.first())}–" +
                    formatExposure(shutterValuesNs.last()) +
                    if (rawSupported) " • RAW OK" else " • RAW EJ"
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
        override fun onOpened(device: CameraDevice) {
            opening = false
            camera = device
            createReaders()
            createCaptureSession()
        }

        override fun onDisconnected(device: CameraDevice) {
            opening = false
            device.close()
            camera = null
            session = null
            status.text = "KAMERA FRÅNKOPPLAD"
        }

        override fun onError(device: CameraDevice, error: Int) {
            opening = false
            device.close()
            camera = null
            session = null
            status.text = "KAMERAFEL: $error"
        }
    }

    private fun createReaders() {
        jpegReader?.close()
        rawReader?.close()

        val map =
            characteristics?.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)

        val jpegSize =
            map?.getOutputSizes(ImageFormat.JPEG)
                ?.filter { it.width >= 1280 }
                ?.minByOrNull { it.width.toLong() * it.height.toLong() }
                ?: android.util.Size(1920, 1080)

        jpegReader = ImageReader.newInstance(
            jpegSize.width,
            jpegSize.height,
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
            val rawSize =
                map?.getOutputSizes(ImageFormat.RAW_SENSOR)
                    ?.maxByOrNull { it.width.toLong() * it.height.toLong() }

            if (rawSize != null) {
                try {
                    rawReader = ImageReader.newInstance(
                        rawSize.width,
                        rawSize.height,
                        ImageFormat.RAW_SENSOR,
                        2
                    )

                    rawReader?.setOnImageAvailableListener({ reader ->
                        val image = reader.acquireLatestImage()
                            ?: return@setOnImageAvailableListener

                        if (rawCheck.isChecked && pendingDngUri != null) {
                            pendingRawImage?.close()
                            pendingRawImage = image
                            saveDngIfReady()
                        } else {
                            image.close()
                        }
                    }, cameraHandler)
                } catch (_: Exception) {
                    rawReader?.close()
                    rawReader = null
                    rawSupported = false
                }
            }
        }
    }

    private fun createCaptureSession() {
        val device = camera ?: return
        val texture = preview.surfaceTexture ?: return
        val jpegSurface = jpegReader?.surface ?: return

        texture.setDefaultBufferSize(1920, 1080)
        val previewSurface = Surface(texture)

        val surfaces = mutableListOf(previewSurface, jpegSurface)
        rawReader?.surface?.let { surfaces.add(it) }

        try {
            device.createCaptureSession(
                surfaces,
                object : CameraCaptureSession.StateCallback() {
                    override fun onConfigured(s: CameraCaptureSession) {
                        session = s
                        startPreview()
                    }

                    override fun onConfigureFailed(s: CameraCaptureSession) {
                        status.text = "KAMERASESSION MISSLYCKADES"
                    }
                },
                cameraHandler
            )
        } catch (e: Exception) {
            status.text = "SESSIONFEL"
        }
    }

    private fun startPreview() {
        val device = camera ?: return
        val s = session ?: return
        val texture = preview.surfaceTexture ?: return

        texture.setDefaultBufferSize(1920, 1080)
        val previewSurface = Surface(texture)

        try {
            val builder =
                device.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW)

            builder.addTarget(previewSurface)

            builder.set(
                CaptureRequest.CONTROL_MODE,
                CameraMetadata.CONTROL_MODE_AUTO
            )

            // Important: keep automatic white balance so manual ISO/shutter
            // do not produce the strong blue cast seen in the previous build.
            builder.set(
                CaptureRequest.CONTROL_AWB_MODE,
                CaptureRequest.CONTROL_AWB_MODE_AUTO
            )

            builder.set(
                CaptureRequest.COLOR_CORRECTION_MODE,
                CaptureRequest.COLOR_CORRECTION_MODE_HIGH_QUALITY
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
            } else {
                builder.set(
                    CaptureRequest.CONTROL_AF_MODE,
                    CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE
                )
            }

            s.setRepeatingRequest(builder.build(), null, cameraHandler)

            status.text =
                "READY • ISO $selectedIso • ${formatExposure(selectedExposureNs)} • WB AUTO"
        } catch (e: Exception) {
            status.text = "PREVIEWFEL"
        }
    }

    private fun updatePreview() {
        if (camera != null && session != null) {
            startPreview()
        }
    }

    private fun capturePhoto() {
        if (capturing) return

        val device = camera ?: run {
            toast("Kameran är inte redo.")
            return
        }

        val s = session ?: run {
            toast("Kamerasessionen är inte redo.")
            return
        }

        val jpegSurface = jpegReader?.surface ?: run {
            toast("JPEG-utgång saknas.")
            return
        }

        capturing = true
        captureButton.isEnabled = false

        pendingCaptureResult = null
        pendingRawImage?.close()
        pendingRawImage = null

        val stamp = System.currentTimeMillis()

        pendingJpegUri = createMediaUri(
            "NightLab_$stamp.jpg",
            "image/jpeg"
        )

        if (rawCheck.isChecked && (!rawSupported || rawReader == null)) {
            pendingJpegUri = null
            capturing = false
            captureButton.isEnabled = true
            toast("RAW/DNG stöds inte av den valda kameran.")
            return
        }

        pendingDngUri =
            if (rawCheck.isChecked) {
                createMediaUri(
                    "NightLab_$stamp.dng",
                    "image/x-adobe-dng"
                )
            } else {
                null
            }

        if (pendingJpegUri == null || (rawCheck.isChecked && pendingDngUri == null)) {
            capturing = false
            captureButton.isEnabled = true
            toast("Kunde inte skapa bildfil.")
            return
        }

        try {
            val builder =
                device.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE)

            builder.addTarget(jpegSurface)

            if (rawCheck.isChecked && rawSupported) {
                rawReader?.surface?.let { builder.addTarget(it) }
            }

            // Manual exposure, but automatic white balance.
            builder.set(
                CaptureRequest.CONTROL_MODE,
                CameraMetadata.CONTROL_MODE_AUTO
            )

            builder.set(
                CaptureRequest.CONTROL_AE_MODE,
                CaptureRequest.CONTROL_AE_MODE_OFF
            )

            builder.set(
                CaptureRequest.CONTROL_AWB_MODE,
                CaptureRequest.CONTROL_AWB_MODE_AUTO
            )

            builder.set(
                CaptureRequest.COLOR_CORRECTION_MODE,
                CaptureRequest.COLOR_CORRECTION_MODE_HIGH_QUALITY
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
                90
            )

            s.capture(
                builder.build(),
                object : CameraCaptureSession.CaptureCallback() {
                    override fun onCaptureCompleted(
                        session: CameraCaptureSession,
                        request: CaptureRequest,
                        result: TotalCaptureResult
                    ) {
                        pendingCaptureResult = result
                        saveDngIfReady()

                        runOnUiThread {
                            status.text =
                                "FOTOGRAFERAD • ISO $selectedIso • " +
                                    "${formatExposure(selectedExposureNs)} • WB AUTO"
                        }

                        captureButton.postDelayed({
                            capturing = false
                            captureButton.isEnabled = true
                        }, 500)
                    }

                    override fun onCaptureFailed(
                        session: CameraCaptureSession,
                        request: CaptureRequest,
                        failure: CaptureFailure
                    ) {
                        failCapture()
                    }
                },
                cameraHandler
            )
        } catch (e: Exception) {
            failCapture()
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
                output.flush()
            } ?: throw IllegalStateException("OutputStream saknas")

            finishMediaUri(uri)
            pendingJpegUri = null

            runOnUiThread {
                status.text = "SPARAD ✓ • Bilder/NightLab • JPEG"
                toast("Bilden sparades i Galleri → NightLab")
            }
        } catch (e: Exception) {
            failUri(uri)
            pendingJpegUri = null

            runOnUiThread {
                status.text = "SPARFEL"
                toast("Kunde inte spara JPEG: ${e.message ?: "okänt fel"}")
            }
        }
    }

    private fun saveDngIfReady() {
        val uri = pendingDngUri ?: return
        val result = pendingCaptureResult ?: return
        val image = pendingRawImage ?: return
        val c = characteristics ?: return

        try {
            contentResolver.openOutputStream(uri)?.use { output ->
                DngCreator(c, result).use { dng ->
                    dng.writeImage(output, image)
                }
            } ?: throw IllegalStateException("DNG OutputStream saknas")

            finishMediaUri(uri)
            pendingDngUri = null
            pendingRawImage?.close()
            pendingRawImage = null

            runOnUiThread {
                status.text = "RAW/DNG SPARAD ✓ • Pictures/NightLab"
                toast("RAW/DNG sparad i Galleri → NightLab")
            }
        } catch (e: Exception) {
            failUri(uri)
            pendingDngUri = null
            pendingRawImage?.close()
            pendingRawImage = null
        }
    }

    private fun createMediaUri(name: String, mimeType: String): Uri? {
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, name)
            put(MediaStore.Images.Media.MIME_TYPE, mimeType)

            if (android.os.Build.VERSION.SDK_INT >= 29) {
                put(
                    MediaStore.Images.Media.RELATIVE_PATH,
                    "Pictures/NightLab"
                )
                put(
                    MediaStore.Images.Media.IS_PENDING,
                    1
                )
            }
        }

        return contentResolver.insert(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            values
        )
    }

    private fun finishMediaUri(uri: Uri) {
        if (android.os.Build.VERSION.SDK_INT >= 29) {
            val values = ContentValues().apply {
                put(MediaStore.Images.Media.IS_PENDING, 0)
            }
            contentResolver.update(uri, values, null, null)
        } else {
            try {
                val path = uri.path
                if (path != null) {
                    android.media.MediaScannerConnection.scanFile(
                        this,
                        arrayOf(path),
                        arrayOf("image/*"),
                        null
                    )
                }
            } catch (_: Exception) {
            }
        }
    }

    private fun failUri(uri: Uri?) {
        uri ?: return
        try {
            contentResolver.delete(uri, null, null)
        } catch (_: Exception) {
        }
    }

    private fun failCapture() {
        failUri(pendingJpegUri)
        failUri(pendingDngUri)

        pendingJpegUri = null
        pendingDngUri = null
        pendingCaptureResult = null

        pendingRawImage?.close()
        pendingRawImage = null

        runOnUiThread {
            capturing = false
            captureButton.isEnabled = true
            status.text = "CAPTURE FAILED"
            toast("Fotograferingen misslyckades.")
        }
    }

    private fun openGallery() {
        try {
            val intent = Intent(
                Intent.ACTION_VIEW,
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI
            ).apply {
                type = "image/*"
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            startActivity(intent)
        } catch (_: Exception) {
            try {
                startActivity(
                    Intent(Intent.ACTION_MAIN).apply {
                        addCategory(Intent.CATEGORY_APP_GALLERY)
                    }
                )
            } catch (_: Exception) {
                toast("Öppna Galleri manuellt → Bilder → NightLab")
            }
        }
    }

    private fun closeCamera() {
        try {
            session?.close()
        } catch (_: Exception) {
        }
        session = null

        try {
            camera?.close()
        } catch (_: Exception) {
        }
        camera = null

        jpegReader?.close()
        jpegReader = null

        rawReader?.close()
        rawReader = null

        pendingRawImage?.close()
        pendingRawImage = null

        opening = false
        capturing = false
    }

    private fun formatExposure(ns: Long): String {
        return when {
            ns % 1_000_000_000L == 0L ->
                "${ns / 1_000_000_000L}s"

            ns >= 1_000_000_000L ->
                String.format(Locale.US, "%.1fs", ns / 1e9)

            ns >= 1_000_000L ->
                "${ns / 1_000_000L}ms"

            else ->
                "${ns / 1_000L}µs"
        }
    }

    private fun toast(message: String) {
        runOnUiThread {
            Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
        }
    }
}
