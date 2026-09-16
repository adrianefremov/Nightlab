package com.adrianefremov.nightlab

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.TotalCaptureResult
import android.hardware.camera2.DngCreator
import android.media.Image
import android.graphics.ImageFormat
import android.media.ImageReader
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.view.Gravity
import android.view.Surface
import android.view.TextureView
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
import java.util.Locale

class MainActivity : ComponentActivity() {

    private lateinit var textureView: TextureView
    private lateinit var isoSpinner: Spinner
    private lateinit var shutterSpinner: Spinner
    private lateinit var focusSeekBar: SeekBar
    private lateinit var focusLabel: TextView
    private lateinit var rawCheckBox: CheckBox
    private lateinit var infoLabel: TextView

    private lateinit var cameraManager: CameraManager

    private var cameraDevice: CameraDevice? = null
    private var captureSession: CameraCaptureSession? = null
    private var previewRequestBuilder: CaptureRequest.Builder? = null

    private var jpegReader: ImageReader? = null
    private var rawReader: ImageReader? = null

    private var cameraId: String? = null
    private var characteristics: CameraCharacteristics? = null

    private var sensorExposureMin = 1_000_000L
    private var sensorExposureMax = 30_000_000_000L

    private var isoMin = 100
    private var isoMax = 6400

    private var minimumFocusDistance = 0f
    private var rawSupported = false

    private var selectedExposureNs = 1_000_000_000L
    private var selectedIso = 100

    private var lastCaptureResult: TotalCaptureResult? = null

    private lateinit var cameraThread: HandlerThread
    private lateinit var cameraHandler: Handler

    private val cameraPermission =
        registerForActivityResult(
            ActivityResultContracts.RequestPermission()
        ) { granted ->
            if (granted) {
                openCameraWhenReady()
            } else {
                Toast.makeText(
                    this,
                    "NightLab behöver kamerabehörighet.",
                    Toast.LENGTH_LONG
                ).show()
            }
        }

    private val textureListener = object : TextureView.SurfaceTextureListener {

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
        ) {
        }

        override fun onSurfaceTextureDestroyed(
            surface: SurfaceTexture
        ): Boolean {
            return true
        }

        override fun onSurfaceTextureUpdated(
            surface: SurfaceTexture
        ) {
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        cameraManager =
            getSystemService(Context.CAMERA_SERVICE) as CameraManager

        startCameraThread()
        createInterface()

        if (
            ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.CAMERA
            ) == PackageManager.PERMISSION_GRANTED
        ) {
            openCameraWhenReady()
        } else {
            cameraPermission.launch(Manifest.permission.CAMERA)
        }
    }

    override fun onResume() {
        super.onResume()

        if (::textureView.isInitialized &&
            textureView.isAvailable
        ) {
            openCameraWhenReady()
        }
    }

    override fun onPause() {
        closeCamera()
        super.onPause()
    }

    override fun onDestroy() {
        closeCamera()

        if (::cameraThread.isInitialized) {
            cameraThread.quitSafely()
        }

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
            setPadding(0, 20, 0, 8)
        }

        val mode = TextView(this).apply {
            text = "PRO  •  NIGHT  •  CAMERA2"
            textSize = 13f
            setTextColor(0xFFCCCCCC.toInt())
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, 12)
        }

        textureView = TextureView(this).apply {
            surfaceTextureListener = textureListener
        }

        val controls = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(12, 8, 12, 8)
        }

        val row1 = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }

        val isoLabel = TextView(this).apply {
            text = "ISO"
            textSize = 14f
            setTextColor(0xFFFFFFFF.toInt())
            gravity = Gravity.CENTER_VERTICAL
            setPadding(8, 0, 8, 0)
        }

        isoSpinner = Spinner(this)

        val shutterLabel = TextView(this).apply {
            text = "SHUTTER"
            textSize = 14f
            setTextColor(0xFFFFFFFF.toInt())
            gravity = Gravity.CENTER_VERTICAL
            setPadding(12, 0, 8, 0)
        }

        shutterSpinner = Spinner(this)

        row1.addView(
            isoLabel,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        )

        row1.addView(
            isoSpinner,
            LinearLayout.LayoutParams(
                120,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        )

        row1.addView(
            shutterLabel,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        )

        row1.addView(
            shutterSpinner,
            LinearLayout.LayoutParams(
                150,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        )

        controls.addView(row1)

        focusLabel = TextView(this).apply {
            text = "FOCUS: AUTO"
            textSize = 14f
            setTextColor(0xFFFFFFFF.toInt())
            gravity = Gravity.CENTER
            setPadding(0, 8, 0, 0)
        }

        controls.addView(
            focusLabel,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        )

        focusSeekBar = SeekBar(this).apply {
            max = 1000
            progress = 0
            setOnSeekBarChangeListener(
                object : SeekBar.OnSeekBarChangeListener {

                    override fun onProgressChanged(
                        seekBar: SeekBar?,
                        progress: Int,
                        fromUser: Boolean
                    ) {
                        if (minimumFocusDistance > 0f) {

                            val focus =
                                minimumFocusDistance *
                                    progress.toFloat() /
                                    1000f

                            focusLabel.text =
                                String.format(
                                    Locale.US,
                                    "FOCUS: %.2f",
                                    focus
                                )

                            updatePreview()
                        }
                    }

                    override fun onStartTrackingTouch(
                        seekBar: SeekBar?
                    ) {
                    }

                    override fun onStopTrackingTouch(
                        seekBar: SeekBar?
                    ) {
                    }
                }
            )
        }

        controls.addView(
            focusSeekBar,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        )

        rawCheckBox = CheckBox(this).apply {
            text = "RAW / DNG"
            textSize = 14f
            setTextColor(0xFFFFFFFF.toInt())
            isEnabled = false
        }

        controls.addView(rawCheckBox)

        infoLabel = TextView(this).apply {
            text = "Analyserar kameran..."
            textSize = 12f
            setTextColor(0xFFAAAAAA.toInt())
            gravity = Gravity.CENTER
            setPadding(0, 4, 0, 4)
        }

        controls.addView(infoLabel)

        val captureButton = Button(this).apply {
            text = "●  CAPTURE"
            textSize = 18f

            setOnClickListener {
                capturePhoto()
            }
        }

        controls.addView(
            captureButton,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                64
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
            controls,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        )

        setContentView(root)
    }

    private fun openCameraWhenReady() {

        if (!textureView.isAvailable) {
            return
        }

        if (cameraDevice != null) {
            return
        }

        try {

            findBackCamera()

            val id = cameraId ?: return

            if (
                ContextCompat.checkSelfPermission(
                    this,
                    Manifest.permission.CAMERA
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                return
            }

            cameraManager.openCamera(
                id,
                object : CameraDevice.StateCallback() {

                    override fun onOpened(camera: CameraDevice) {
                        cameraDevice = camera
                        createCameraSession()
                    }

                    override fun onDisconnected(camera: CameraDevice) {
                        camera.close()

                        runOnUiThread {
                            Toast.makeText(
                                this@MainActivity,
                                "Kameran kopplades från.",
                                Toast.LENGTH_SHORT
                            ).show()
                        }

                        cameraDevice = null
                    }

                    override fun onError(
                        camera: CameraDevice,
                        error: Int
                    ) {
                        camera.close()
                        cameraDevice = null

                        runOnUiThread {
                            Toast.makeText(
                                this@MainActivity,
                                "Kamerafel: $error",
                                Toast.LENGTH_LONG
                            ).show()
                        }
                    }

                },
                cameraHandler
            )

        } catch (exception: Exception) {

            runOnUiThread {
                Toast.makeText(
                    this,
                    "Kunde inte öppna kameran: ${exception.message}",
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }

    private fun findBackCamera() {

        for (id in cameraManager.cameraIdList) {

            val chars =
                cameraManager.getCameraCharacteristics(id)

            val facing =
                chars.get(
                    CameraCharacteristics.LENS_FACING
                )

            if (
                facing ==
                CameraCharacteristics.LENS_FACING_BACK
            ) {

                cameraId = id
                characteristics = chars

                val exposureRange =
                    chars.get(
                        CameraCharacteristics
                            .SENSOR_INFO_EXPOSURE_TIME_RANGE
                    )

                if (exposureRange != null) {

                    sensorExposureMin =
                        exposureRange.lower

                    sensorExposureMax =
                        minOf(
                            exposureRange.upper,
                            120_000_000_000L
                        )
                }

                val sensitivityRange =
                    chars.get(
                        CameraCharacteristics
                            .SENSOR_INFO_SENSITIVITY_RANGE
                    )

                if (sensitivityRange != null) {

                    isoMin = sensitivityRange.lower
                    isoMax =
                        minOf(
                            sensitivityRange.upper,
                            6400
                        )
                }

                minimumFocusDistance =
                    chars.get(
                        CameraCharacteristics
                            .LENS_INFO_MINIMUM_FOCUS_DISTANCE
                    ) ?: 0f

                val capabilities =
                    chars.get(
                        CameraCharacteristics
                            .REQUEST_AVAILABLE_CAPABILITIES
                    )

                rawSupported =
                    capabilities?.contains(
                        CameraCharacteristics
                            .REQUEST_AVAILABLE_CAPABILITIES_RAW
                    ) == true

                setupControls()

                return
            }
        }
    }

    private fun setupControls() {

        val isoValues =
            listOf(
                50,
                100,
                200,
                400,
                800,
                1600,
                3200,
                6400
            )
                .filter {
                    it >= isoMin &&
                        it <= isoMax
                }

        val finalIsoValues =
            if (isoValues.isNotEmpty()) {
                isoValues
            } else {
                listOf(isoMin)
            }

        val isoAdapter =
            ArrayAdapter(
                this,
                android.R.layout.simple_spinner_dropdown_item,
                finalIsoValues.map { "ISO $it" }
            )

        isoSpinner.adapter = isoAdapter

        selectedIso =
            finalIsoValues.firstOrNull {
                it >= 100
            } ?: finalIsoValues.first()

        isoSpinner.setSelection(
            finalIsoValues.indexOf(selectedIso)
                .coerceAtLeast(0)
        )

        isoSpinner.setOnItemSelectedListener(
            object :
                android.widget.AdapterView.OnItemSelectedListener {

                override fun onItemSelected(
                    parent:
                    android.widget.AdapterView<*>?,
                    view:
                    android.view.View?,
                    position: Int,
                    id: Long
                ) {
                    selectedIso =
                        finalIsoValues[position]

                    updatePreview()
                }

                override fun onNothingSelected(
                    parent:
                    android.widget.AdapterView<*>?
                ) {
                }
            }
        )

        val shutterOptions =
            listOf(
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
            )
                .filter {
                    it.first >= sensorExposureMin &&
                        it.first <= sensorExposureMax
                }

        val finalShutterOptions =
            if (shutterOptions.isNotEmpty()) {
                shutterOptions
            } else {
                listOf(
                    sensorExposureMin to
                        formatExposure(
                            sensorExposureMin
                        )
                )
            }

        val shutterAdapter =
            ArrayAdapter(
                this,
                android.R.layout.simple_spinner_dropdown_item,
                finalShutterOptions.map {
                    it.second
                }
            )

        shutterSpinner.adapter = shutterAdapter

        selectedExposureNs =
            finalShutterOptions
                .firstOrNull {
                    it.first == 1_000_000_000L
                }?.first
                ?: finalShutterOptions.first().first

        shutterSpinner.setSelection(
            finalShutterOptions.indexOfFirst {
                it.first == selectedExposureNs
            }.coerceAtLeast(0)
        )

        shutterSpinner.setOnItemSelectedListener(
            object :
                android.widget.AdapterView.OnItemSelectedListener {

                override fun onItemSelected(
                    parent:
                    android.widget.AdapterView<*>?,
                    view:
                    android.view.View?,
                    position: Int,
                    id: Long
                ) {

                    selectedExposureNs =
                        finalShutterOptions[position].first

                    updatePreview()
                }

                override fun onNothingSelected(
                    parent:
                    android.widget.AdapterView<*>?
                ) {
                }
            }
        )

        focusSeekBar.isEnabled =
            minimumFocusDistance > 0f

        rawCheckBox.isEnabled =
            rawSupported

        rawCheckBox.isChecked =
            rawSupported

        val focusText =
            if (minimumFocusDistance > 0f) {
                "MANUAL FOCUS"
            } else {
                "FOCUS: AUTO"
            }

        focusLabel.text = focusText

        infoLabel.text =
            "ISO $isoMin–$isoMax  •  " +
            "${formatExposure(sensorExposureMin)}–" +
            "${formatExposure(sensorExposureMax)}" +
            if (rawSupported) {
                "  •  RAW OK"
            } else {
                "  •  JPEG"
            }
    }

    private fun createCameraSession() {

        val camera = cameraDevice ?: return
        val chars = characteristics ?: return

        try {

            val map =
                chars.get(
                    CameraCharacteristics
                        .SCALER_STREAM_CONFIGURATION_MAP
                ) ?: return

            val previewSize =
                map.getOutputSizes(
                    android.graphics.SurfaceTexture::class.java
                )
                    ?.maxByOrNull {
                        it.width * it.height
                    }
                    ?: android.util.Size(1920, 1080)

            textureView.surfaceTexture?.setDefaultBufferSize(
                previewSize.width,
                previewSize.height
            )

            val previewSurface =
                Surface(textureView.surfaceTexture)

            jpegReader =
                ImageReader.newInstance(
                    previewSize.width,
                    previewSize.height,
                    ImageFormat.JPEG,
                    2
                )

            jpegReader?.setOnImageAvailableListener(
                { reader ->

                    val image =
                        reader.acquireLatestImage()
                            ?: return@setOnImageAvailableListener

                    saveJpeg(image)

                },
                cameraHandler
            )

            val surfaces =
                mutableListOf<Surface>()

            surfaces.add(previewSurface)
            surfaces.add(jpegReader!!.surface)

            if (rawSupported) {

                val rawSize =
                    map.getOutputSizes(
                        ImageFormat.RAW_SENSOR
                    )
                        ?.maxByOrNull {
                            it.width * it.height
                        }

                if (rawSize != null) {

                    rawReader =
                        ImageReader.newInstance(
                            rawSize.width,
                            rawSize.height,
                            ImageFormat.RAW_SENSOR,
                            2
                        )

                    rawReader?.setOnImageAvailableListener(
                        { reader ->

                            val image =
                                reader.acquireLatestImage()
                                    ?: return@setOnImageAvailableListener

                            saveRaw(image)

                        },
                        cameraHandler
                    )

                    surfaces.add(rawReader!!.surface)
                }
            }

            camera.createCaptureSession(
                surfaces,
                object : CameraCaptureSession.StateCallback() {

                    override fun onConfigured(
                        session:
                        CameraCaptureSession
                    ) {

                        captureSession = session

                        try {

                            previewRequestBuilder =
                                camera.createCaptureRequest(
                                    CameraDevice
                                        .TEMPLATE_PREVIEW
                                )

                            previewRequestBuilder
                                ?.addTarget(previewSurface)

                            previewRequestBuilder
                                ?.set(
                                    CaptureRequest
                                        .CONTROL_AF_MODE,
                                    CaptureRequest
                                        .CONTROL_AF_MODE_CONTINUOUS_PICTURE
                                )

                            updatePreview()

                        } catch (exception: Exception) {

                            runOnUiThread {
                                Toast.makeText(
                                    this@MainActivity,
                                    "Preview-fel: ${exception.message}",
                                    Toast.LENGTH_LONG
                                ).show()
                            }
                        }
                    }

                    override fun onConfigureFailed(
                        session:
                        CameraCaptureSession
                    ) {

                        runOnUiThread {
                            Toast.makeText(
                                this@MainActivity,
                                "Kunde inte konfigurera kameran.",
                                Toast.LENGTH_LONG
                            ).show()
                        }
                    }
                },
                cameraHandler
            )

        } catch (exception: Exception) {

            runOnUiThread {
                Toast.makeText(
                    this,
                    "Kamerakonfiguration misslyckades.",
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }

    private fun updatePreview() {

        val session = captureSession
            ?: return

        val builder = previewRequestBuilder
            ?: return

        try {

            builder.set(
                CaptureRequest.CONTROL_MODE,
                CaptureRequest.CONTROL_MODE_AUTO
            )

            builder.set(
                CaptureRequest.CONTROL_AE_MODE,
                CaptureRequest.CONTROL_AE_MODE_ON
            )

            if (minimumFocusDistance > 0f) {

                if (focusSeekBar.progress > 0) {

                    val focus =
                        minimumFocusDistance *
                            focusSeekBar.progress.toFloat() /
                            1000f

                    builder.set(
                        CaptureRequest.CONTROL_AF_MODE,
                        CaptureRequest
                            .CONTROL_AF_MODE_OFF
                    )

                    builder.set(
                        CaptureRequest.LENS_FOCUS_DISTANCE,
                        focus
                    )

                } else {

                    builder.set(
                        CaptureRequest.CONTROL_AF_MODE,
                        CaptureRequest
                            .CONTROL_AF_MODE_CONTINUOUS_PICTURE
                    )
                }
            }

            session.setRepeatingRequest(
                builder.build(),
                null,
                cameraHandler
            )

        } catch (_: Exception) {
        }
    }

    private fun capturePhoto() {

        val camera = cameraDevice
            ?: return

        val session = captureSession
            ?: return

        try {

            val builder =
                camera.createCaptureRequest(
                    CameraDevice.TEMPLATE_STILL_CAPTURE
                )

            builder.addTarget(
                jpegReader!!.surface
            )

            if (
                rawSupported &&
                rawCheckBox.isChecked &&
                rawReader != null
            ) {
                builder.addTarget(
                    rawReader!!.surface
                )
            }

            builder.set(
                CaptureRequest.CONTROL_MODE,
                CaptureRequest.CONTROL_MODE_OFF
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

            builder.set(
                CaptureRequest.JPEG_ORIENTATION,
                jpegOrientation()
            )

            if (minimumFocusDistance > 0f) {

                if (focusSeekBar.progress > 0) {

                    val focus =
                        minimumFocusDistance *
                            focusSeekBar.progress.toFloat() /
                            1000f

                    builder.set(
                        CaptureRequest.CONTROL_AF_MODE,
                        CaptureRequest.CONTROL_AF_MODE_OFF
                    )

                    builder.set(
                        CaptureRequest.LENS_FOCUS_DISTANCE,
                        focus
                    )
                }
            }

            session.capture(
                builder.build(),
                object : CameraCaptureSession.CaptureCallback() {

                    override fun onCaptureCompleted(
                        session:
                        CameraCaptureSession,
                        request:
                        CaptureRequest,
                        result:
                        TotalCaptureResult
                    ) {

                        lastCaptureResult = result

                        runOnUiThread {
                            Toast.makeText(
                                this@MainActivity,
                                "Bild taget • ISO $selectedIso • " +
                                    formatExposure(
                                        selectedExposureNs
                                    ),
                                Toast.LENGTH_SHORT
                            ).show()
                        }
                    }
                },
                cameraHandler
            )

        } catch (exception: Exception) {

            Toast.makeText(
                this,
                "Fotograferingen misslyckades: " +
                    exception.message,
                Toast.LENGTH_LONG
            ).show()
        }
    }

    private fun saveJpeg(image: Image) {

        val buffer =
            image.planes[0].buffer

        val bytes =
            ByteArray(buffer.remaining())

        buffer.get(bytes)

        image.close()

        val directory =
            File(
                externalMediaDirs.firstOrNull(),
                "NightLab"
            )

        directory.mkdirs()

        val file =
            File(
                directory,
                "NightLab_${System.currentTimeMillis()}.jpg"
            )

        try {

            FileOutputStream(file).use {
                it.write(bytes)
            }

        } catch (_: Exception) {
        }
    }

    private fun saveRaw(image: Image) {

        val result =
            lastCaptureResult

        val chars =
            characteristics

        if (result == null || chars == null) {
            image.close()
            return
        }

        val directory =
            File(
                externalMediaDirs.firstOrNull(),
                "NightLab"
            )

        directory.mkdirs()

        val file =
            File(
                directory,
                "NightLab_${System.currentTimeMillis()}.dng"
            )

        try {

            FileOutputStream(file).use { output ->

                DngCreator(
                    chars,
                    result
                ).use { creator ->

                    creator.writeImage(
                        output,
                        image
                    )
                }
            }

        } catch (_: Exception) {
        } finally {
            image.close()
        }
    }

    private fun jpegOrientation(): Int {

        val rotation =
            windowManager.defaultDisplay.rotation

        val degrees =
            when (rotation) {
                Surface.ROTATION_0 -> 0
                Surface.ROTATION_90 -> 90
                Surface.ROTATION_180 -> 180
                Surface.ROTATION_270 -> 270
                else -> 0
            }

        val sensorOrientation =
            characteristics?.get(
                CameraCharacteristics
                    .SENSOR_ORIENTATION
            ) ?: 90

        return (sensorOrientation + degrees) % 360
    }

    private fun formatExposure(
        nanoseconds: Long
    ): String {

        val seconds =
            nanoseconds / 1_000_000_000.0

        return when {
            seconds >= 1.0 ->
                if (seconds == seconds.toLong().toDouble()) {
                    "${seconds.toLong()}s"
                } else {
                    String.format(
                        Locale.US,
                        "%.1fs",
                        seconds
                    )
                }

            seconds > 0 ->
                String.format(
                    Locale.US,
                    "1/%.0fs",
                    1.0 / seconds
                )

            else -> "AUTO"
        }
    }

    private fun closeCamera() {

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

        try {
            jpegReader?.close()
        } catch (_: Exception) {
        }

        jpegReader = null

        try {
            rawReader?.close()
        } catch (_: Exception) {
        }

        rawReader = null
    }
}
