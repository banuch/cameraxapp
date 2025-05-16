package com.example.cameraxapp

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.widget.SeekBar
import android.widget.Toast
import androidx.annotation.RequiresApi
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.lifecycle.lifecycleScope
import com.example.cameraxapp.databinding.ActivityMainBinding
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors
import android.os.Handler
import android.os.Looper
/**
 * MainActivity for the Camera Application
 *
 * This activity coordinates between permission handling, camera operations, and file management
 * while providing a user interface for camera preview and photo capture.
 */
class MainActivity : AppCompatActivity(), PermissionManager.PermissionListener,
    CameraManager.CameraListener {

    private lateinit var binding: ActivityMainBinding
    private lateinit var permissionManager: PermissionManager
    private lateinit var cameraManager: CameraManager
    private lateinit var fileManager: FileManager
    private lateinit var cameraExecutor: java.util.concurrent.ExecutorService
    private lateinit var cameraProvider: ProcessCameraProvider

    @RequiresApi(Build.VERSION_CODES.P)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Initialize executor
        cameraExecutor = Executors.newSingleThreadExecutor()

        // Initialize managers
        initializeManagers()

        // Set up UI listeners
        setupListeners()

        // Request permissions and start camera if granted
        permissionManager.requestPermissions()
    }

    /**
     * Initialize all manager classes
     */
    private fun initializeManagers() {
        // Create permission manager
        permissionManager = PermissionManager(this, this)

        // Create file manager
        fileManager = FileManager(this)

        // Create camera manager
        cameraManager = CameraManager(
            this,
            binding.viewFinder,
            this
        )
    }

    /**
     * Sets up UI element listeners such as the capture button
     */
    @RequiresApi(Build.VERSION_CODES.P)
    private fun setupListeners() {
        // Capture button click listener
        binding.captureButton.setOnClickListener {
            // Show animation feedback
            binding.captureButton.animate().scaleX(0.9f).scaleY(0.9f).setDuration(100).withEndAction {
                binding.captureButton.animate().scaleX(1f).scaleY(1f).setDuration(100)
            }

            // Take photo with ROI and detect meter
            takePhotoWithRoiAndDetection()
        }

        // Setup zoom control
        cameraManager.setupZoomControl(binding.zoomSeekBar)

        // Observe zoom changes to update text
        lifecycleScope.launch {
            cameraManager.currentZoomRatio.collectLatest { zoomRatio ->
                updateZoomTextValue(zoomRatio)
            }
        }
    }

    /**
     * Takes a photo with ROI processing and meter detection
     */
    @RequiresApi(Build.VERSION_CODES.P)
    private fun takePhotoWithRoiAndDetection() {
        // Get ROI rectangle from overlay
        val roiRect = binding.roiOverlay.getRoiRect()

        // Show progress indicator
        binding.progressIndicator.visibility = View.VISIBLE

        // Use ImageAnalysis to get a bitmap from the current camera frame
        val imageAnalysis = ImageAnalysis.Builder()
            .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_YUV_420_888)
            .build()

        imageAnalysis.setAnalyzer(cameraExecutor) { imageProxy ->
            // Generate timestamp
            val timestamp = SimpleDateFormat(FILENAME_FORMAT, Locale.US).format(Date())

            // Process ROI and detect meter
            cameraManager.processRoiAndDetectMeter(imageProxy, roiRect, timestamp)

            // Hide progress indicator - already wrapped in runOnUiThread, which is good
            runOnUiThread {
                binding.progressIndicator.visibility = View.GONE
            }

            // Close the image proxy
            imageProxy.close()

            // Remove the analyzer to stop processing
            imageAnalysis.clearAnalyzer()
        }

        // Get camera provider and bind the image analysis use case
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)
        cameraProviderFuture.addListener({
            try {
                cameraProvider = cameraProviderFuture.get()
                // Use a proper CameraSelector
                val cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA
                cameraProvider.bindToLifecycle(this, cameraSelector, imageAnalysis)
            } catch (e: Exception) {
                onCameraError("Failed to bind image analysis: ${e.message}")
            }
        }, mainExecutor)
    }

    /**
     * Updates the zoom text value display
     */
    private fun updateZoomTextValue(zoomRatio: Float) {
        val formattedZoom = String.format("%.1fx", zoomRatio)
        binding.zoomValueText.text = formattedZoom

        // Make sure zoom text is visible when zooming
        if (binding.zoomValueText.visibility != View.VISIBLE) {
            binding.zoomValueText.visibility = View.VISIBLE
        }

        // Hide zoom text after a delay
        binding.zoomValueText.postDelayed({
            binding.zoomValueText.visibility = View.GONE
        }, 2000)
    }

    /**
     * Shows a dialog explaining why permissions are needed
     */
    private fun showPermissionRationaleDialog() {
        AlertDialog.Builder(this)
            .setTitle("Permissions Required")
            .setMessage("This app needs camera and storage permissions to capture and save photos. " +
                    "Please grant these permissions to continue.")
            .setPositiveButton("Settings") { _, _ ->
                // Open app settings
                val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                val uri = Uri.fromParts("package", packageName, null)
                intent.data = uri
                startActivity(intent)
            }
            .setNegativeButton("Cancel") { _, _ ->
                showToast("Permissions are required to use this app")
                finish()
            }
            .setCancelable(false)
            .show()
    }

    /**
     * Shows a toast message
     *
     * @param message Message to display
     */
    private fun showToast(message: String) {
        runOnUiThread {
            Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        cameraManager.shutdown()
        cameraExecutor.shutdown()
    }

    //
    // PermissionManager.PermissionListener implementation
    //

    override fun onPermissionsGranted() {
        cameraManager.startCamera(binding.zoomSeekBar)
    }

    override fun onPermissionsDenied() {
        showPermissionRationaleDialog()
    }

    //
    // CameraManager.CameraListener implementation
    //

    override fun onPhotoTaken(savedUri: Uri?, timestamp: String, meterReading: String?) {
        if (savedUri != null) {
            val message = if (meterReading != null) {
                "Photo saved with meter reading: $meterReading"
            } else {
                "Photo saved successfully"
            }

            showToast(message)

            // Save metadata
            fileManager.saveJsonMetadata(savedUri, timestamp, meterReading)

            // Notify gallery on older Android versions
            fileManager.notifyGallery(savedUri)
        } else {
            showToast("Photo saved but location unknown")
        }
    }

    override fun onCameraError(message: String) {
        showToast("Camera error: $message")
    }

    companion object {
        private const val FILENAME_FORMAT = "yyyyMMdd_HHmmss"
    }
}