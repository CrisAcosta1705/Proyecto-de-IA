package com.proyectoia.asistencia

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.util.Size
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.Spinner
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ExperimentalGetImage
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetectorOptions
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

class MainActivity : ComponentActivity() {
    private lateinit var classSpinner: Spinner
    private lateinit var countText: TextView
    private lateinit var statusText: TextView
    private lateinit var historyText: TextView
    private lateinit var previewView: PreviewView
    private lateinit var faceOverlay: FaceBoxOverlay
    private lateinit var cameraExecutor: ExecutorService

    private var currentFaceCount = 0
    private val isAnalyzing = AtomicBoolean(false)
    private val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())

    private val faceDetector by lazy {
        val options = FaceDetectorOptions.Builder()
            .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_FAST)
            .enableTracking()
            .setMinFaceSize(0.08f)
            .build()
        FaceDetection.getClient(options)
    }

    private val requestCameraPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            startCamera()
        } else {
            statusText.text = getString(R.string.camera_permission_denied)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        classSpinner = findViewById(R.id.classSpinner)
        countText = findViewById(R.id.countText)
        statusText = findViewById(R.id.statusText)
        historyText = findViewById(R.id.historyText)
        previewView = findViewById(R.id.previewView)
        faceOverlay = findViewById(R.id.faceOverlay)
        cameraExecutor = Executors.newSingleThreadExecutor()

        val scanButton = findViewById<Button>(R.id.scanButton)
        val saveButton = findViewById<Button>(R.id.saveButton)

        classSpinner.adapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_dropdown_item,
            listOf("Matematica", "Programacion", "Base de datos", "Inteligencia artificial")
        )

        scanButton.setOnClickListener {
            ensureCameraPermission()
        }

        saveButton.setOnClickListener {
            val selectedClass = classSpinner.selectedItem.toString()
            val timestamp = dateFormat.format(Date())
            val record = "$timestamp | $selectedClass | $currentFaceCount estudiantes detectados"
            historyText.text = listOf(record, historyText.text.toString())
                .filter { it.isNotBlank() }
                .joinToString(separator = "\n")
            statusText.text = getString(R.string.saved_record)
        }

        ensureCameraPermission()
    }

    override fun onDestroy() {
        super.onDestroy()
        faceDetector.close()
        cameraExecutor.shutdown()
    }

    private fun ensureCameraPermission() {
        val hasPermission = ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.CAMERA
        ) == PackageManager.PERMISSION_GRANTED

        if (hasPermission) {
            startCamera()
        } else {
            requestCameraPermission.launch(Manifest.permission.CAMERA)
        }
    }

    private fun startCamera() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)

        cameraProviderFuture.addListener({
            val cameraProvider = cameraProviderFuture.get()

            val preview = Preview.Builder().build().also {
                it.setSurfaceProvider(previewView.surfaceProvider)
            }

            val imageAnalysis = ImageAnalysis.Builder()
                .setTargetResolution(Size(640, 480))
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()
                .also {
                    it.setAnalyzer(cameraExecutor, ::analyzeFrame)
                }

            try {
                cameraProvider.unbindAll()
                cameraProvider.bindToLifecycle(
                    this,
                    CameraSelector.DEFAULT_BACK_CAMERA,
                    preview,
                    imageAnalysis
                )
                statusText.text = getString(R.string.scan_ready)
            } catch (error: Exception) {
                statusText.text = error.localizedMessage ?: "No se pudo iniciar la camara."
            }
        }, ContextCompat.getMainExecutor(this))
    }

    @androidx.annotation.OptIn(ExperimentalGetImage::class)
    private fun analyzeFrame(imageProxy: ImageProxy) {
        val mediaImage = imageProxy.image
        if (mediaImage == null || !isAnalyzing.compareAndSet(false, true)) {
            imageProxy.close()
            return
        }

        val image = InputImage.fromMediaImage(
            mediaImage,
            imageProxy.imageInfo.rotationDegrees
        )

        faceDetector.process(image)
            .addOnSuccessListener { faces ->
                currentFaceCount = faces.size
                val boxes = faces.map { it.boundingBox }

                runOnUiThread {
                    countText.text = getString(R.string.people_count, currentFaceCount)
                    faceOverlay.setFaces(
                        boxes = boxes,
                        imageWidth = imageProxy.width,
                        imageHeight = imageProxy.height
                    )
                }
            }
            .addOnFailureListener { error ->
                runOnUiThread {
                    statusText.text = error.localizedMessage ?: "Error detectando rostros."
                }
            }
            .addOnCompleteListener {
                isAnalyzing.set(false)
                imageProxy.close()
            }
    }
}
