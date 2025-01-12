package com.irfanerenciftci.bacak_tespiti_uygulama

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Matrix
import android.os.Bundle
import android.util.Log
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.camera.core.AspectRatio
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import com.irfanerenciftci.bacak_tespiti_uygulama.Adress.modelPath
import com.irfanerenciftci.bacak_tespiti_uygulama.Adress.labelsPath
import com.irfanerenciftci.bacak_tespiti_uygulama.databinding.ActivityMainBinding
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import android.os.Environment
import androidx.activity.result.contract.ActivityResultContracts
import android.provider.MediaStore
import android.content.ContentValues
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException


class MainActivity : AppCompatActivity(), Detections.DetectionResults {
    private lateinit var binding: ActivityMainBinding
    private var preview: Preview? = null
    private var imageAnalyzer: ImageAnalysis? = null
    private var camera: Camera? = null
    private var cameraProvider: ProcessCameraProvider? = null
    private var detector: Detections? = null
    private var imageCapture: ImageCapture? = null

    private lateinit var cameraExecutor: ExecutorService

    //gerekli izinleri kontrol etme
    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted: Boolean ->
        if (!isGranted) {
            Log.e("Permissions", "Storage permission denied.")
        }
    }
    //gerekli izinleri kontrol etme
    private fun checkAndRequestPermissions() {
        if (ContextCompat.checkSelfPermission(
                this, Manifest.permission.WRITE_EXTERNAL_STORAGE
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            ActivityCompat.requestPermissions(
                this,
                arrayOf(Manifest.permission.WRITE_EXTERNAL_STORAGE),
                REQUEST_CODE_PERMISSIONS
            )
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {

        checkAndRequestPermissions()

        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        cameraExecutor = Executors.newSingleThreadExecutor()

        cameraExecutor.execute {
            detector = Detections(baseContext, modelPath, labelsPath, this)
        }

        if (allPermissionsGranted()) {
            openCamera()
        } else {
            requestPermissions(REQUIRED_PERMISSIONS, REQUEST_CODE_PERMISSIONS)
        }

        binding.captureButton.setOnClickListener {
            capturePhoto()
        }

    }
    //resim çekme fonksiyonu
    private fun capturePhoto() {
        val imageCapture = imageCapture ?: return

        val contentValues = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, "photo_${System.currentTimeMillis()}.jpg")
            put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
            put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES)
        }

        val outputOptions = ImageCapture.OutputFileOptions.Builder(
            contentResolver,
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            contentValues
        ).build()

        imageCapture.takePicture(
            outputOptions,
            ContextCompat.getMainExecutor(this),
            object : ImageCapture.OnImageSavedCallback {
                override fun onImageSaved(outputFileResults: ImageCapture.OutputFileResults) {
                    Log.d("CapturePhoto", "Photo saved to: ${outputFileResults.savedUri}")
                }

                override fun onError(exception: ImageCaptureException) {
                    Log.e("CapturePhoto", "Photo capture failed: ${exception.message}", exception)
                }
            }
        )
    }

        private fun openCamera() {
            val cameraProviderFuture = ProcessCameraProvider.getInstance(this)
            cameraProviderFuture.addListener({
                cameraProvider = cameraProviderFuture.get()
                bindCamera()
            }, ContextCompat.getMainExecutor(this))
        }

        private fun bindCamera() {

            val cameraProvider = cameraProvider ?: throw IllegalStateException("Kamera acilamadi.")

            val rotation = binding.viewFinder.display.rotation

            val cameraSelector = CameraSelector
                .Builder()
                .requireLensFacing(CameraSelector.LENS_FACING_BACK)
                .build()

            preview = Preview.Builder()
                .setTargetAspectRatio(AspectRatio.RATIO_4_3)
                .setTargetRotation(rotation)
                .build()

            imageCapture = ImageCapture.Builder()
                .setTargetAspectRatio(AspectRatio.RATIO_4_3)
                .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                .build()

            imageAnalyzer = ImageAnalysis.Builder()
                .setTargetAspectRatio(AspectRatio.RATIO_4_3)
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .setTargetRotation(binding.viewFinder.display.rotation)
                .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
                .build()

            imageAnalyzer?.setAnalyzer(cameraExecutor) { imageProxy ->
                val bitmapBuffer = Bitmap.createBitmap(
                    imageProxy.width,
                    imageProxy.height,
                    Bitmap.Config.ARGB_8888
                )
                imageProxy.use { bitmapBuffer.copyPixelsFromBuffer(imageProxy.planes[0].buffer) }

                val matrix = Matrix().apply {
                    postRotate(imageProxy.imageInfo.rotationDegrees.toFloat())
                }

                val rotatedBitmap = Bitmap.createBitmap(
                    bitmapBuffer, 0, 0, bitmapBuffer.width, bitmapBuffer.height,
                    matrix, true
                )

                detector?.detect(rotatedBitmap)
                imageProxy.close()
            }



            cameraProvider.unbindAll()

            try {
                camera = cameraProvider.bindToLifecycle(
                    this,
                    cameraSelector,
                    preview,
                    imageCapture,
                    imageAnalyzer

                )

                preview?.setSurfaceProvider(binding.viewFinder.surfaceProvider)
            } catch (exc: Exception) {
                Log.e(TAG, "Use case binding failed", exc)
            }
        }

    private fun allPermissionsGranted() = REQUIRED_PERMISSIONS.all {
        ContextCompat.checkSelfPermission(baseContext, it) == PackageManager.PERMISSION_GRANTED
    }

    override fun onResume() {
        super.onResume()
        if (allPermissionsGranted()){
            openCamera()
        } else {
            onDestroy()
        }
    }

        override fun onDestroy() {
            super.onDestroy()
            detector?.close()
            cameraExecutor.shutdown()
        }

        companion object {
            private const val TAG = "Camera"
            private const val REQUEST_CODE_PERMISSIONS = 10
            private val REQUIRED_PERMISSIONS = mutableListOf(
                Manifest.permission.CAMERA
            ).toTypedArray()
        }


    //tahmin varsa çalışacak fonksiyon

        override fun onDetect(boundingBoxes: List<BoundingBox>, inferenceTime: Long) {
            runOnUiThread {
                binding.inferenceTime.text = "${inferenceTime}ms"

                if (boundingBoxes.isNotEmpty()) {
                    val highestConf = boundingBoxes.maxByOrNull { it.score }
                    val confidencePercentage =
                        ((highestConf?.score ?: 0f) * 100).toInt()
                    binding.confidenceText.text = "Conf: $confidencePercentage%"
                } else {
                    binding.confidenceText.text = "No Detection"
                }

                binding.overlay.apply {
                    setResults(boundingBoxes)
                    invalidate()
                }
            }
        }

    //tahmin yoksa
        override fun onEmptyDetect() {
            runOnUiThread {
                binding.overlay.clear()
        }
    }
}
