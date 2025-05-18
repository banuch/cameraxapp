package com.example.cameraxapp

import android.R
import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.RectF
import android.util.Log
import android.view.ScaleGestureDetector
import android.widget.Toast
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.core.UseCaseGroup
import androidx.camera.core.ZoomState
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.ByteArrayOutputStream
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * CameraManager handles all camera operations for meter detection
 *
 * This class manages camera preview, capture, and ROI-based cropping.
 */
class CameraManager(
    private val context: Context,
    private val lifecycleOwner: LifecycleOwner,
    private val previewView: PreviewView,
    private val roiOverlay: ROIOverlay
) {
    private val tag = "CameraManager"

    // Camera components
    private var cameraProvider: ProcessCameraProvider? = null
    private var camera: Camera? = null
    private var imageCapture: ImageCapture? = null
    private val cameraExecutor: ExecutorService = Executors.newSingleThreadExecutor()

    // Camera settings
    private var lensFacing = CameraSelector.LENS_FACING_BACK
    private var flashEnabled = false

    // Capture result flow
    private val _captureResult = MutableStateFlow<CaptureResult?>(null)
    val captureResult: StateFlow<CaptureResult?> = _captureResult

    // Scale detector for zoom functionality
    private lateinit var scaleGestureDetector: ScaleGestureDetector

    /**
     * Initialize camera and setup the preview
     */
    fun initialize() {
        Log.d(tag, "Initializing camera")

        // Initialize scale gesture detector for zoom
        scaleGestureDetector = ScaleGestureDetector(context, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScale(detector: ScaleGestureDetector): Boolean {
                // Get current zoom ratio
                val currentZoomRatio = camera?.cameraInfo?.zoomState?.value?.zoomRatio ?: 1f
                // Calculate new zoom ratio
                val delta = detector.scaleFactor
                camera?.cameraControl?.setZoomRatio(currentZoomRatio * delta)
                return true
            }
        })

        // Ensure the preview view handles scale gestures
        previewView.setOnTouchListener { _, event ->
            scaleGestureDetector.onTouchEvent(event)
            true
        }

        // Initialize camera provider
        val cameraProviderFuture = ProcessCameraProvider.getInstance(context)
        cameraProviderFuture.addListener({
            try {
                cameraProvider = cameraProviderFuture.get()
                bindCameraUseCases()
            } catch (e: Exception) {
                Log.e(tag, "Camera initialization failed: ${e.message}", e)
                Toast.makeText(context, "Failed to initialize camera: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }, ContextCompat.getMainExecutor(context))
    }

    /**
     * Bind camera use cases (preview and image capture)
     */
    private fun bindCameraUseCases() {
        val cameraProvider = cameraProvider ?: return

        // Build camera selector
        val cameraSelector = CameraSelector.Builder()
            .requireLensFacing(lensFacing)
            .build()

        // Setup preview use case
        val preview = Preview.Builder()
            .build()
            .also {
                it.setSurfaceProvider(previewView.surfaceProvider)
            }

        // Setup image capture use case
        imageCapture = ImageCapture.Builder()
            .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
            .build()

        try {
            // Unbind any existing use cases
            cameraProvider.unbindAll()

            // Get the display rotation
            val rotation = previewView.display.rotation

            // Create a use case group with the display's rotation
            val useCaseGroup = UseCaseGroup.Builder()
                .addUseCase(preview)
                .addUseCase(imageCapture!!)
                //.setTargetRotation(rotation)
                .build()

            // Bind the use cases to the camera
            camera = cameraProvider.bindToLifecycle(
                lifecycleOwner,
                cameraSelector,
                useCaseGroup
            )

            // Initial zoom level
            camera?.cameraControl?.setLinearZoom(0f)
        } catch (e: Exception) {
            Log.e(tag, "Use case binding failed: ${e.message}", e)
            Toast.makeText(context, "Failed to bind camera: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    /**
     * Toggle the flash mode
     *
     * @return Current flash mode state
     */
    fun toggleFlash(): Boolean {
        flashEnabled = !flashEnabled
        imageCapture?.flashMode = if (flashEnabled) {
            ImageCapture.FLASH_MODE_ON
        } else {
            ImageCapture.FLASH_MODE_OFF
        }
        return flashEnabled
    }

    /**
     * Switch between front and back cameras
     */
    fun switchCamera() {
        lensFacing = if (lensFacing == CameraSelector.LENS_FACING_BACK) {
            CameraSelector.LENS_FACING_FRONT
        } else {
            CameraSelector.LENS_FACING_BACK
        }
        bindCameraUseCases()
    }

    /**
     * Set zoom level
     *
     * @param zoomLevel Zoom level (0.0f to 1.0f)
     */
    fun setZoom(zoomLevel: Float) {
        camera?.cameraControl?.setLinearZoom(zoomLevel.coerceIn(0f, 1f))
    }

    /**
     * Get current zoom ratio
     *
     * @return Current zoom ratio or 1.0f if not available
     */
    fun getCurrentZoom(): Float {
        return camera?.cameraInfo?.zoomState?.value?.zoomRatio ?: 1.0f
    }

    /**
     * Get current zoom state
     *
     * @return Current ZoomState or null if not available
     */
    fun getZoomState(): ZoomState? {
        return camera?.cameraInfo?.zoomState?.value
    }

    /**
     * Capture image and process ROI
     */
    fun captureImage() {
        val imageCapture = imageCapture ?: return

        try {
            // Take picture
            imageCapture.takePicture(
                cameraExecutor,
                object : ImageCapture.OnImageCapturedCallback() {
                    @SuppressLint("UnsafeOptInUsageError")
                    override fun onCaptureSuccess(image: ImageProxy) {
                        processImage(image)
                    }

                    override fun onError(exception: ImageCaptureException) {
                        Log.e(tag, "Image capture failed: ${exception.message}", exception)
                        Toast.makeText(context, "Failed to capture image: ${exception.message}", Toast.LENGTH_SHORT).show()
                    }
                }
            )
        } catch (e: Exception) {
            Log.e(tag, "Image capture error: ${e.message}", e)
            Toast.makeText(context, "Failed to capture image: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    /**
     * Process the captured image to extract ROI
     */
    private fun processImage(imageProxy: ImageProxy) {
        try {
            // Get bitmap from image proxy
            val buffer = imageProxy.planes[0].buffer
            val bytes = ByteArray(buffer.remaining())
            buffer.get(bytes)

            // Convert to bitmap
            var bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)

            // Apply rotation if needed
            val rotation = imageProxy.imageInfo.rotationDegrees
            if (rotation != 0) {
                bitmap = ImageCropper.rotateBitmap(bitmap, rotation) ?: bitmap
            }

            // Get ROI from overlay
            val roiRect = roiOverlay.getROIRect()

            // Use ImageCropper to crop the ROI and create a 640x640 bitmap for the model
            val modelBitmap = ImageCropper.cropToROIWithBackground(
                sourceBitmap = bitmap,
                roi = roiRect,
                viewWidth = previewView.width,
                viewHeight = previewView.height,
                targetWidth = 640,
                targetHeight = 640,
                backgroundColor = Color.LTGRAY,
                recycleSource = false
            ) ?: bitmap

            // Crop just the ROI separately to display to the user
            val roiBitmap = ImageCropper.cropToROI(
                sourceBitmap = bitmap,
                roi = roiRect,
                viewWidth = previewView.width,
                viewHeight = previewView.height,
                recycleSource = false
            ) ?: bitmap

            // Generate timestamp for file naming
            val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(System.currentTimeMillis())

            // Create capture result
            val result = CaptureResult(
                originalBitmap = bitmap,
                roiBitmap = roiBitmap,
                modelBitmap = modelBitmap,
                timestamp = timestamp
            )

            // Update capture result flow
            _captureResult.value = result

            // Close the image proxy
            imageProxy.close()
        } catch (e: Exception) {
            Log.e(tag, "Image processing failed: ${e.message}", e)
            imageProxy.close()
        }
    }

    /**
     * Save the captured image and metadata
     */
    fun saveImage(
        result: CaptureResult,
        meterReading: String? = null,
        savedFilename: String? = null,
        isEdited: Boolean = false
    ) {
        try {
            // Convert bitmap to JPEG bytes
            val outputStream = ByteArrayOutputStream()
            result.modelBitmap.compress(Bitmap.CompressFormat.JPEG, 90, outputStream)
            val jpegBytes = outputStream.toByteArray()

            // Generate image file name
            val fileName = "${savedFilename}.jpg"

            // Save to storage
            val (uri, stream) = FileUtils.createOrUpdateImageFile(
                context,
                fileName,
                "npdcl",
                "image/jpeg"
            )

            Log.d(tag, "image URL: $uri")


            if (uri != null && stream != null) {
                // Write JPEG data to file
                stream.write(jpegBytes)
                stream.close()

                // Save metadata with isEdited flag
                val fileManager = FileManager(context)

                fileManager.saveJsonMetadata(uri, result.timestamp, meterReading, savedFilename, isEdited)
                fileManager.notifyGallery(uri)

                Toast.makeText(context, "Image saved successfully", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(context, "Failed to create output file", Toast.LENGTH_SHORT).show()
            }
        } catch (e: Exception) {
            Log.e(tag, "Failed to save image: ${e.message}", e)
            Toast.makeText(context, "Failed to save image: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    /**
     * Release all camera resources
     */
    fun shutdown() {
        cameraExecutor.shutdown()
        cameraProvider?.unbindAll()
    }

    /**
     * Data class to hold capture results
     */
    data class CaptureResult(
        val originalBitmap: Bitmap,
        val roiBitmap: Bitmap,
        val modelBitmap: Bitmap,
        val timestamp: String
    )
}