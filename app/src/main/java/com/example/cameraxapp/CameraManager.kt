package com.example.cameraxapp

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageFormat
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.YuvImage
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import android.widget.SeekBar
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.core.ZoomState
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.math.roundToInt

/**
 * CameraManager handles all camera-related operations
 *
 * This class encapsulates camera initialization, preview setup,
 * photo capture functionality, and zoom control using CameraX API.
 */
class CameraManager(
    private val activity: AppCompatActivity,
    private val viewFinder: PreviewView,
    private val listener: CameraListener
) {
    private val tag = "CameraManager"
    private var imageCapture: ImageCapture? = null
    private lateinit var cameraExecutor: ExecutorService
    private var outputDirectory: File? = null
    private var camera: Camera? = null

    // Zoom control
    private val _currentZoomRatio = MutableStateFlow(1.0f)
    val currentZoomRatio: Flow<Float> = _currentZoomRatio

    private var minZoomRatio = 1.0f
    private var maxZoomRatio = 5.0f

    // Interface for camera operation callbacks
    interface CameraListener {
        fun onPhotoTaken(savedUri: Uri?, timestamp: String, meterReading: String? = null)
        fun onCameraError(message: String)
    }

    init {
        // Initialize camera executor
        cameraExecutor = Executors.newSingleThreadExecutor()

        // Create output directory for images on older Android versions
        createOutputDirectory()
    }

    /**
     * Creates the output directory for storing images
     */
    private fun createOutputDirectory() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            // For Android 10 and above, we'll use MediaStore
            outputDirectory = null
        } else {
            // For Android 9 and below
            try {
                val dir = File(
                    Environment.getExternalStoragePublicDirectory(
                        Environment.DIRECTORY_PICTURES
                    ),
                    APP_FOLDER_NAME
                )
                if (!dir.exists()) {
                    dir.mkdirs()
                }
                outputDirectory = dir
                Log.d(tag, "Created output directory: ${dir.absolutePath}")
            } catch (e: Exception) {
                Log.e(tag, "Error creating output directory: ${e.message}", e)
                outputDirectory = null
            }
        }
    }

    /**
     * Sets up zoom control with the provided SeekBar
     *
     * @param zoomSeekBar SeekBar for controlling zoom
     */
    fun setupZoomControl(zoomSeekBar: SeekBar) {
        // Configure zoom control
        zoomSeekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) {
                    val zoomRatio = calculateZoomRatio(progress)
                    setZoomRatio(zoomRatio)
                }
            }

            override fun onStartTrackingTouch(seekBar: SeekBar?) {
                // Not needed
            }

            override fun onStopTrackingTouch(seekBar: SeekBar?) {
                // Not needed
            }
        })
    }

    /**
     * Calculates zoom ratio from seekbar progress
     *
     * @param progress SeekBar progress (0-100)
     * @return calculated zoom ratio
     */
    private fun calculateZoomRatio(progress: Int): Float {
        // Convert progress (0-100) to a zoom ratio between min and max zoom
        return minZoomRatio + (progress / 100f) * (maxZoomRatio - minZoomRatio)
    }

    /**
     * Sets the zoom ratio on the camera
     *
     * @param ratio The zoom ratio to set
     */
    fun setZoomRatio(ratio: Float) {
        camera?.cameraControl?.setZoomRatio(ratio)
        _currentZoomRatio.value = ratio
    }

    /**
     * Updates the seekbar position based on the current zoom state
     *
     * @param zoomSeekBar The seekbar to update
     * @param zoomState The current zoom state
     */
    private fun updateZoomSeekBar(zoomSeekBar: SeekBar, zoomState: ZoomState) {
        // Update min and max zoom ratios
        minZoomRatio = zoomState.minZoomRatio
        maxZoomRatio = zoomState.maxZoomRatio

        // Calculate progress based on current zoom ratio
        val currentZoomRatio = zoomState.zoomRatio
        val progress = ((currentZoomRatio - minZoomRatio) / (maxZoomRatio - minZoomRatio) * 100).roundToInt()

        // Update seekbar position
        zoomSeekBar.progress = progress

        // Update current zoom ratio flow
        _currentZoomRatio.value = currentZoomRatio
    }

    /**
     * Initializes and starts the camera
     */
    fun startCamera(zoomSeekBar: SeekBar? = null) {
        Log.d(tag, "Starting camera")
        val cameraProviderFuture = ProcessCameraProvider.getInstance(activity)

        cameraProviderFuture.addListener({
            try {
                // Used to bind the lifecycle of cameras to the lifecycle owner
                val cameraProvider: ProcessCameraProvider = cameraProviderFuture.get()

                // Preview
                val preview = Preview.Builder()
                    .build()
                    .also {
                        it.setSurfaceProvider(viewFinder.surfaceProvider)
                    }

                // Image capture
                imageCapture = ImageCapture.Builder()
                    .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                    .build()

                // Select back camera as a default
                val cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA

                // Unbind use cases before rebinding
                cameraProvider.unbindAll()

                // Bind use cases to camera
                camera = cameraProvider.bindToLifecycle(
                    activity, cameraSelector, preview, imageCapture
                )

                // Set up zoom if seekbar is provided
                zoomSeekBar?.let { seekBar ->
                    // Observe zoom state
                    camera?.cameraInfo?.zoomState?.observe(activity) { zoomState ->
                        updateZoomSeekBar(seekBar, zoomState)
                    }
                }

                // Camera started successfully
                Log.d(tag, "Camera started successfully")

            } catch (exc: Exception) {
                Log.e(tag, "Use case binding failed", exc)
                listener.onCameraError("Failed to start camera: ${exc.message}")
            }
        }, ContextCompat.getMainExecutor(activity))
    }

    /**
     * Takes a photo and saves it to storage
     *
     * @param timestamp Formatted timestamp string for filename
     */
    fun takePhoto(timestamp: String) {
        Log.d(tag, "Taking photo")

        // Get a stable reference of the modifiable image capture use case
        val imageCapture = imageCapture ?: run {
            Log.e(tag, "Image capture use case not initialized")
            listener.onCameraError("Camera not ready")
            return
        }

        val photoFileName = "PHOTO_${timestamp}.jpg"

        // Create output options object which contains file + metadata
        val outputOptions = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            // For Android 10 and above
            val contentValues = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, photoFileName)
                put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
                put(
                    MediaStore.MediaColumns.RELATIVE_PATH,
                    Environment.DIRECTORY_PICTURES + File.separator + APP_FOLDER_NAME
                )
            }

            val contentUri = MediaStore.Images.Media.EXTERNAL_CONTENT_URI
            ImageCapture.OutputFileOptions.Builder(activity.contentResolver, contentUri, contentValues)
                .build()
        } else {
            // For below Android 10
            val photoFile = File(outputDirectory, photoFileName)
            ImageCapture.OutputFileOptions.Builder(photoFile).build()
        }

        // Set up image capture listener
        imageCapture.takePicture(
            outputOptions,
            ContextCompat.getMainExecutor(activity),
            object : ImageCapture.OnImageSavedCallback {
                override fun onError(exc: ImageCaptureException) {
                    Log.e(tag, "Photo capture failed: ${exc.message}", exc)
                    listener.onCameraError("Failed to capture photo: ${exc.message}")
                }

                override fun onImageSaved(output: ImageCapture.OutputFileResults) {
                    val savedUri = output.savedUri
                    Log.d(tag, "Photo capture succeeded: $savedUri")
                    listener.onPhotoTaken(savedUri, timestamp)
                }
            }
        )
    }

    /**
     * Processes the captured image from ROI and runs meter detection
     *
     * @param imageProxy Image from camera frame
     * @param roiRect Region of interest rectangle (in preview coordinates)
     * @param timestamp Timestamp for the filename
     */
    fun processRoiAndDetectMeter(imageProxy: ImageProxy, roiRect: RectF, timestamp: String) {
        Log.d(tag, "Processing ROI and detecting meter")

        try {
            // Convert ImageProxy to Bitmap
            val bitmap = imageProxyToBitmap(imageProxy)

            // Convert ROI rect from view coordinates to bitmap coordinates
            val viewFinderWidth = viewFinder.width.toFloat()
            val viewFinderHeight = viewFinder.height.toFloat()

            val bitmapWidth = bitmap.width.toFloat()
            val bitmapHeight = bitmap.height.toFloat()

            val scaleX = bitmapWidth / viewFinderWidth
            val scaleY = bitmapHeight / viewFinderHeight

            val bitmapRoiLeft = (roiRect.left * scaleX).toInt().coerceIn(0, bitmap.width - 1)
            val bitmapRoiTop = (roiRect.top * scaleY).toInt().coerceIn(0, bitmap.height - 1)
            val bitmapRoiRight = (roiRect.right * scaleX).toInt().coerceIn(0, bitmap.width)
            val bitmapRoiBottom = (roiRect.bottom * scaleY).toInt().coerceIn(0, bitmap.height)

            val roiWidth = bitmapRoiRight - bitmapRoiLeft
            val roiHeight = bitmapRoiBottom - bitmapRoiTop

            // Crop the ROI from the original bitmap
            val croppedBitmap = Bitmap.createBitmap(
                bitmap,
                bitmapRoiLeft,
                bitmapRoiTop,
                roiWidth,
                roiHeight
            )

            // Create detector and run detection
            val meterDetector = MeterDetector(activity)
            val (detections, resultBitmap) = meterDetector.detectMeterReading(croppedBitmap)

            // Extract meter reading
            val meterReading = if (detections.isNotEmpty()) {
                meterDetector.extractMeterReading(detections)
            } else {
                null
            }

            // Save the processed bitmap
            val savedUri = saveProcessedBitmap(resultBitmap, timestamp)

            // Notify listener
            if (savedUri != null) {
                listener.onPhotoTaken(savedUri, timestamp, meterReading)
            } else {
                listener.onCameraError("Failed to save processed image")
            }

            // Clean up
            meterDetector.close()
            bitmap.recycle()
            croppedBitmap.recycle()

        } catch (e: Exception) {
            Log.e(tag, "Error processing ROI and detecting meter: ${e.message}", e)
            listener.onCameraError("Error processing image: ${e.message}")
        }
    }

    /**
     * Convert ImageProxy to Bitmap
     */
    private fun imageProxyToBitmap(imageProxy: ImageProxy): Bitmap {
        val yBuffer = imageProxy.planes[0].buffer
        val uBuffer = imageProxy.planes[1].buffer
        val vBuffer = imageProxy.planes[2].buffer

        val ySize = yBuffer.remaining()
        val uSize = uBuffer.remaining()
        val vSize = vBuffer.remaining()

        val nv21 = ByteArray(ySize + uSize + vSize)

        yBuffer.get(nv21, 0, ySize)
        vBuffer.get(nv21, ySize, vSize)
        uBuffer.get(nv21, ySize + vSize, uSize)

        val yuvImage = YuvImage(nv21, ImageFormat.NV21, imageProxy.width, imageProxy.height, null)
        val out = ByteArrayOutputStream()
        yuvImage.compressToJpeg(Rect(0, 0, imageProxy.width, imageProxy.height), 100, out)
        val imageBytes = out.toByteArray()

        return BitmapFactory.decodeByteArray(imageBytes, 0, imageBytes.size)
    }

    /**
     * Saves a processed bitmap to storage
     *
     * @param bitmap Bitmap to save
     * @param timestamp Timestamp for the filename
     * @return URI of the saved image or null if failed
     */
    /**
     * Saves a processed bitmap to storage
     *
     * @param bitmap Bitmap to save
     * @param timestamp Timestamp for the filename
     * @return URI of the saved image or null if failed
     */
    private fun saveProcessedBitmap(bitmap: Bitmap, timestamp: String): Uri? {
        val photoFileName = "PHOTO_ROI_${timestamp}.jpg"

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                // For Android 10 and above
                val contentValues = ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, photoFileName)
                    put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
                    put(
                        MediaStore.MediaColumns.RELATIVE_PATH,
                        Environment.DIRECTORY_PICTURES + File.separator + APP_FOLDER_NAME
                    )
                }

                val contentUri = MediaStore.Images.Media.EXTERNAL_CONTENT_URI
                val imageUri = activity.contentResolver.insert(contentUri, contentValues)

                imageUri?.let { uri ->
                    activity.contentResolver.openOutputStream(uri)?.use { outputStream ->
                        bitmap.compress(Bitmap.CompressFormat.JPEG, 90, outputStream)
                    }
                    return uri
                }
            } else {
                // For below Android 10
                val photoFile = File(outputDirectory, photoFileName)
                FileOutputStream(photoFile).use { outputStream ->
                    bitmap.compress(Bitmap.CompressFormat.JPEG, 90, outputStream)
                }

                // Create URI from file
                return Uri.fromFile(photoFile)
            }
        } catch (e: Exception) {
            Log.e(tag, "Error saving processed bitmap: ${e.message}", e)
        }

        return null
    }

    /**
     * Shuts down the camera executor service
     */
    fun shutdown() {
        Log.d(tag, "Shutting down camera executor")
        cameraExecutor.shutdown()
    }

    companion object {
        private const val APP_FOLDER_NAME = "CameraXApp"
    }
}