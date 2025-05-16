/**
 * MainActivity.kt
 */
package com.example.cameraxapp

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.widget.SeekBar
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.example.cameraxapp.databinding.ActivityMainBinding
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

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
    private fun setupListeners() {
        // Capture button click listener
        binding.captureButton.setOnClickListener {
            // Show animation feedback
            binding.captureButton.animate().scaleX(0.9f).scaleY(0.9f).setDuration(100).withEndAction {
                binding.captureButton.animate().scaleX(1f).scaleY(1f).setDuration(100)
            }

            // Take photo
            val timestamp = SimpleDateFormat(FILENAME_FORMAT, Locale.US).format(Date())
            cameraManager.takePhoto(timestamp)
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
     * Updates the zoom text value display
     */
    private fun updateZoomTextValue(zoomRatio: Float) {
        val formattedZoom = String.format("%.1fx", zoomRatio)
        binding.zoomValueText.text = formattedZoom

        // Make sure zoom text is visible when zooming
        if (binding.zoomValueText.visibility != android.view.View.VISIBLE) {
            binding.zoomValueText.visibility = android.view.View.VISIBLE
        }

        // Hide zoom text after a delay
        binding.zoomValueText.postDelayed({
            binding.zoomValueText.visibility = android.view.View.GONE
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
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }

    override fun onDestroy() {
        super.onDestroy()
        cameraManager.shutdown()
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

    override fun onPhotoTaken(savedUri: Uri?, timestamp: String) {
        if (savedUri != null) {
            showToast("Photo saved successfully")

            // Save metadata
            fileManager.saveJsonMetadata(savedUri, timestamp)

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