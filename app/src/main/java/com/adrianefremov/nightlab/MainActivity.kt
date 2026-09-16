package com.adrianefremov.nightlab

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat

class MainActivity : ComponentActivity() {

    private lateinit var previewView: PreviewView
    private var imageCapture: ImageCapture? = null

    private val cameraPermission =
        registerForActivityResult(
            ActivityResultContracts.RequestPermission()
        ) { granted ->
            if (granted) {
                startCamera()
            } else {
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

        if (ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.CAMERA
            ) == PackageManager.PERMISSION_GRANTED
        ) {
            startCamera()
        } else {
            cameraPermission.launch(Manifest.permission.CAMERA)
        }
    }

    private fun createInterface() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(0xFF050505.toInt())
        }

        val title = TextView(this).apply {
            text = "NIGHTLAB"
            textSize = 22f
            setTextColor(0xFFFFFFFF.toInt())
            gravity = Gravity.CENTER
            setPadding(0, 24, 0, 24)
        }

        previewView = PreviewView(this).apply {
            implementationMode = PreviewView.ImplementationMode.COMPATIBLE
            scaleType = PreviewView.ScaleType.FILL_CENTER
        }

        val mode = TextView(this).apply {
            text = "PRO  •  NIGHT"
            textSize = 16f
            setTextColor(0xFFFFFFFF.toInt())
            gravity = Gravity.CENTER
            setPadding(0, 18, 0, 12)
        }

        val controls = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }

        val iso = Button(this).apply {
            text = "ISO 100"
            setOnClickListener {
                Toast.makeText(
                    this@MainActivity,
                    "ISO-kontroll kommer i nästa kameramodul.",
                    Toast.LENGTH_SHORT
                ).show()
            }
        }

        val shutter = Button(this).apply {
            text = "1s"
            setOnClickListener {
                Toast.makeText(
                    this@MainActivity,
                    "Lång exponering aktiveras när Camera2-modulen är installerad.",
                    Toast.LENGTH_SHORT
                ).show()
            }
        }

        val capture = Button(this).apply {
            text = "●"
            textSize = 24f
            setOnClickListener {
                takePhoto()
            }
        }

        controls.addView(iso)
        controls.addView(shutter)
        controls.addView(capture)

        root.addView(
            title,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        )

        root.addView(
            previewView,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                0,
                1f
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
            controls,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        )

        setContentView(root)
    }

    private fun startCamera() {
        val cameraProviderFuture =
            ProcessCameraProvider.getInstance(this)

        cameraProviderFuture.addListener({

            val cameraProvider = cameraProviderFuture.get()

            val preview = Preview.Builder()
                .build()
                .also {
                    it.surfaceProvider = previewView.surfaceProvider
                }

            imageCapture = ImageCapture.Builder()
                .setCaptureMode(
                    ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY
                )
                .build()

            val cameraSelector =
                CameraSelector.DEFAULT_BACK_CAMERA

            try {
                cameraProvider.unbindAll()

                cameraProvider.bindToLifecycle(
                    this,
                    cameraSelector,
                    preview,
                    imageCapture
                )

            } catch (exception: Exception) {
                Toast.makeText(
                    this,
                    "Kunde inte starta kameran.",
                    Toast.LENGTH_LONG
                ).show()
            }

        }, ContextCompat.getMainExecutor(this))
    }

    private fun takePhoto() {

        val capture = imageCapture ?: return

        val file = java.io.File(
            externalMediaDirs.firstOrNull(),
            "NightLab_${System.currentTimeMillis()}.jpg"
        )

        val outputOptions =
            ImageCapture.OutputFileOptions.Builder(file)
                .build()

        capture.takePicture(
            outputOptions,
            ContextCompat.getMainExecutor(this),
            object : ImageCapture.OnImageSavedCallback {

                override fun onImageSaved(
                    outputFileResults: ImageCapture.OutputFileResults
                ) {
                    Toast.makeText(
                        this@MainActivity,
                        "Bild sparad",
                        Toast.LENGTH_SHORT
                    ).show()
                }

                override fun onError(
                    exception: ImageCaptureException
                ) {
                    Toast.makeText(
                        this@MainActivity,
                        "Fotograferingen misslyckades.",
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
        )
    }
}
