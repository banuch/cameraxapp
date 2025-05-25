package com.example.cameraxapp

import android.content.ContentResolver
import android.content.Context
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.os.Bundle
import android.provider.Settings
import android.util.Log
import android.view.View
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.view.PreviewView
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.bottomsheet.BottomSheetDialog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

class MainActivity : AppCompatActivity(),
    PermissionManager.PermissionListener,
    SettingsBottomSheetDialog.SettingsListener {

    companion object {
        private const val TAG = "MainActivity"

        // Result codes for different meter types
        const val OCR_KWH_RESULT_CODE = 666
        const val OCR_KVAH_RESULT_CODE = 667
        const val OCR_RMD_RESULT_CODE = 668
        const val OCR_LT_RESULT_CODE = 669
        const val OCR_IMG_RESULT_CODE = 770
        const val OCR_SKWH_RESULT_CODE = 771
        const val OCR_SKVAH_RESULT_CODE = 772
        const val OCR_INVALID_RESULT_CODE = 773
    }

    // ============================================================================
    // UI Components
    // ============================================================================

    // Camera preview components
    private lateinit var previewView: PreviewView
    private lateinit var roiOverlay: ROIOverlay
    private lateinit var captureButton: Button
    private lateinit var flashButton: ImageButton
    private lateinit var settingsButton: ImageButton  // Renamed from settingsToggleButton
    private lateinit var modelSelectButton: ImageButton

    // Info display components
    private lateinit var titleTextView: TextView
    private lateinit var serviceIdTextView: TextView
    private lateinit var valueTypeTextView: TextView
    private lateinit var currentModelTextView: TextView

    // Result view components
    private lateinit var resultLayout: LinearLayout
    private lateinit var resultImageView: ImageView
    private lateinit var readingTextView: TextView
    private lateinit var resultServiceIdTextView: TextView
    private lateinit var resultValueTypeTextView: TextView
    private lateinit var saveButton: Button
    private lateinit var retakeButton: Button
    private lateinit var processButton: Button

    // Progress indicator
    private lateinit var progressBar: ProgressBar

    // ============================================================================
    // Managers and Detectors
    // ============================================================================

    private lateinit var permissionManager: PermissionManager
    private lateinit var cameraManager: CameraManager
    private lateinit var meterDetector: MeterDetector

    // ============================================================================
    // State Variables
    // ============================================================================

    private var currentCaptureResult: CameraManager.CaptureResult? = null
    private var currentMeterReading: String? = null
    private var currentModelInfo: MeterDetector.ModelInfo? = null
    private val inputNumber = StringBuilder()

    // Camera control values
    private var currentZoom = 0
    private var currentThreshold = 50
    private var currentExposure = 0

    // Settings dialog
    private var settingsDialog: SettingsBottomSheetDialog? = null

    // App state variables
    private var tapCount = 0
    private var editFlag = false
    private var serviceId = "default_service"
    private var valType = "default"
    private var savedFileName = "default"
    private var meterReading = "null"
    private var imagePath = "path_not_found"

    // ============================================================================
    // Activity Lifecycle
    // ============================================================================

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // Force portrait orientation
        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT

        // Check auto-rotation settings
        checkAutoRotationSettings()

        // Extract data from intent
        extractIntentData()

        // Initialize UI components
        initializeViews()

        // Update UI with service info
        updateServiceInfo()

        // Initialize managers
        initializeManagers()

        // Request permissions
        permissionManager.requestPermissions()

        //showSettingsDialog()
    }

    override fun onDestroy() {
        super.onDestroy()
        cleanupResources()
    }

    // ============================================================================
    // Initialization Methods
    // ============================================================================

    private fun checkAutoRotationSettings() {
        val isAutoRotateEnabled = isAutoRotateOn(contentResolver)
        Log.d(TAG, if (isAutoRotateEnabled) "Auto-rotation is enabled" else "Auto-rotation is disabled")
    }

    private fun extractIntentData() {
        val dataHandler = IntentDataHandler(intent)
        serviceId = dataHandler.getServiceId()
        valType = dataHandler.getValType()
        savedFileName = "${serviceId}_${valType}"

        Log.d(TAG, "ServiceID: $serviceId")
        Log.d(TAG, "ValueType: $valType")
    }

    private fun initializeViews() {
        // Camera preview components
        previewView = findViewById(R.id.viewFinder)
        roiOverlay = findViewById(R.id.roiOverlay)
        captureButton = findViewById(R.id.captureButton)
        flashButton = findViewById(R.id.flashButton)
        settingsButton = findViewById(R.id.settingsButton)  // Updated ID
        modelSelectButton = findViewById(R.id.modelSelectButton)

        // Info display components
        titleTextView = findViewById(R.id.titleTextView)
        serviceIdTextView = findViewById(R.id.serviceIdTextView)
        valueTypeTextView = findViewById(R.id.valueTypeTextView)
        currentModelTextView = findViewById(R.id.currentModelTextView)

        // Result view components
        resultLayout = findViewById(R.id.resultLayout)
        resultImageView = findViewById(R.id.resultImageView)
        readingTextView = findViewById(R.id.readingTextView)
        resultServiceIdTextView = findViewById(R.id.resultServiceIdTextView)
        resultValueTypeTextView = findViewById(R.id.resultValueTypeTextView)
        saveButton = findViewById(R.id.saveButton)
        retakeButton = findViewById(R.id.retakeButton)
        processButton = findViewById(R.id.processButton)

        // Progress indicator
        progressBar = findViewById(R.id.progressBar)

        // Set up click listeners
        setupClickListeners()

        // Update title with version
        titleTextView.text = "Ebilly OCR ${getVersionName()}"
    }

    private fun updateServiceInfo() {
        serviceIdTextView.text = serviceId
        valueTypeTextView.text = valType
        resultServiceIdTextView.text = serviceId
        resultValueTypeTextView.text = valType
    }

    private fun initializeManagers() {
        permissionManager = PermissionManager(this, this)
        meterDetector = MeterDetector(this)
    }

    // ============================================================================
    // UI Setup Methods
    // ============================================================================

    private fun setupClickListeners() {
        // Camera control buttons
        captureButton.setOnClickListener { captureImage(valType) }
        flashButton.setOnClickListener { toggleFlash() }
        settingsButton.setOnClickListener { showSettingsDialog() }  // Updated
        modelSelectButton.setOnClickListener { showModelSelectionDialog() }

        // Result view buttons
        saveButton.setOnClickListener {
            saveCurrentImage()
            sendBackValues()
        }
        retakeButton.setOnClickListener { showCameraView() }
        processButton.setOnClickListener { processCurrentImage() }

        // Reading text view for manual editing
        readingTextView.setOnClickListener { handleReadingTextClick() }
    }

    // ============================================================================
    // Settings Dialog Management
    // ============================================================================

    private fun showSettingsDialog() {
        if (settingsDialog == null) {
            settingsDialog = SettingsBottomSheetDialog.newInstance(
                currentZoom, currentThreshold, currentExposure + 50  // Convert back to 0-100 range
            )
            settingsDialog?.setSettingsListener(this)
        }
        settingsDialog?.show(supportFragmentManager, "SettingsBottomSheet")
    }

    // SettingsListener interface implementations
    override fun onZoomChanged(zoomValue: Int) {
        currentZoom = zoomValue
        applyCameraZoom(zoomValue)
    }

    override fun onThresholdChanged(thresholdValue: Int) {
        currentThreshold = thresholdValue
        applyDetectionThreshold(thresholdValue)
    }

    override fun onExposureChanged(exposureValue: Int) {
        currentExposure = exposureValue
        applyCameraExposure(exposureValue)
    }

    override fun onResetSettings() {
        currentZoom = 0
        currentThreshold = 50
        currentExposure = 0

        // Apply reset values to camera
        applyCameraZoom(0)
        applyDetectionThreshold(50)
        applyCameraExposure(0)

        // Create new dialog instance with reset values
        settingsDialog = null
    }

    // Camera control implementations
    private fun applyCameraZoom(zoomValue: Int) {
        if (::cameraManager.isInitialized) {
            val zoomLevel = zoomValue / 100f
            cameraManager.setZoom(zoomLevel)
        }
    }

    private fun applyDetectionThreshold(thresholdValue: Int) {
        if (::meterDetector.isInitialized) {
            meterDetector.setConfidenceThreshold(thresholdValue / 100f)
        }
    }

    private fun applyCameraExposure(exposureValue: Int) {
        if (::cameraManager.isInitialized) {
            // Uncomment when exposure control is implemented
            // cameraManager.setExposure(exposureValue)
        }
    }

    // ============================================================================
    // Camera Management
    // ============================================================================

    private fun initializeCamera() {
        Log.d(TAG, "Initializing camera")

        cameraManager = CameraManager(this, this, previewView, roiOverlay)

        // Observe camera capture results
        lifecycleScope.launch {
            cameraManager.captureResult.collectLatest { result ->
                result?.let {
                    currentCaptureResult = it
                    showResultView(it)
                }
            }
        }

        // Start camera
        cameraManager.initialize()
    }

    private fun captureImage(valType: String) {
        if (::cameraManager.isInitialized) {
            progressBar.visibility = View.VISIBLE
            captureButton.isEnabled = false
            cameraManager.captureImage(valType)
        }
    }

    private fun toggleFlash() {
        if (::cameraManager.isInitialized) {
            val flashOn = cameraManager.toggleFlash()
            flashButton.setImageResource(
                if (flashOn) R.drawable.ic_flash_on else R.drawable.ic_flash_off
            )
        }
    }

    // ============================================================================
    // Model Management
    // ============================================================================

    private fun updateModelDisplay() {
        currentModelInfo = meterDetector.getCurrentModel()
        currentModelTextView.text = currentModelInfo?.displayName ?: "Default"
    }

    private fun showModelSelectionDialog() {
        val bottomSheetDialog = BottomSheetDialog(this)
        val view = layoutInflater.inflate(R.layout.model_selection_bottom_sheet, null)
        bottomSheetDialog.setContentView(view)

        val recyclerView = view.findViewById<RecyclerView>(R.id.modelRecyclerView)
        recyclerView.layoutManager = LinearLayoutManager(this)

        val availableModels = meterDetector.getAvailableModels()
        currentModelInfo = meterDetector.getCurrentModel()

        val adapter = ModelSelectionAdapter(
            models = availableModels,
            currentModelFileName = currentModelInfo?.fileName ?: "",
            onModelSelected = { selectedModel ->
                loadSelectedModel(selectedModel, bottomSheetDialog)
            }
        )

        recyclerView.adapter = adapter
        bottomSheetDialog.show()
    }

    private fun loadSelectedModel(selectedModel: MeterDetector.ModelInfo, dialog: BottomSheetDialog) {
        val success = meterDetector.loadModel(selectedModel)
        if (success) {
            currentModelInfo = selectedModel
            updateModelDisplay()
            Toast.makeText(this, "Model '${selectedModel.displayName}' loaded", Toast.LENGTH_SHORT).show()
        } else {
            Toast.makeText(this, "Failed to load model", Toast.LENGTH_SHORT).show()
        }
        dialog.dismiss()
    }

    // ============================================================================
    // View Management
    // ============================================================================

    private fun showCameraView() {
        // Show camera components
        resultLayout.visibility = View.GONE
        previewView.visibility = View.VISIBLE
        roiOverlay.visibility = View.VISIBLE
        captureButton.visibility = View.VISIBLE
        flashButton.visibility = View.VISIBLE

        // Clean up previous results
        cleanupCaptureResult()

        // Reset state
        currentCaptureResult = null
        currentMeterReading = null
    }

    private fun showResultView(result: CameraManager.CaptureResult) {
        // Hide progress and enable capture button
        progressBar.visibility = View.GONE
        captureButton.isEnabled = true

        // Update result view
        resultImageView.setImageBitmap(result.roiBitmap)
        resultLayout.visibility = View.VISIBLE

        // Hide camera components
        previewView.visibility = View.GONE
        roiOverlay.visibility = View.GONE
        captureButton.visibility = View.GONE
        flashButton.visibility = View.GONE

        // Set initial reading text based on value type
        readingTextView.text = if (valType == "IMG") "Tap 'Save'" else "Tap 'Process'"
        currentMeterReading = null

        // Update service info in result view
        resultServiceIdTextView.text = serviceId
        resultValueTypeTextView.text = valType
    }

    // ============================================================================
    // Image Processing
    // ============================================================================

    private fun processCurrentImage() {
        val result = currentCaptureResult ?: return

        readingTextView.text = "Processing..."
        progressBar.visibility = View.VISIBLE
        processButton.isEnabled = false

        lifecycleScope.launch {
            try {
                val processingResult = withContext(Dispatchers.IO) {
                    val (detections, resultBitmap) = meterDetector.detectMeterReading(result.modelBitmap)
                    val reading = meterDetector.extractMeterReading(detections)
                    Pair(reading, resultBitmap)
                }

                val (reading, resultBitmap) = processingResult
                currentMeterReading = reading

                resultImageView.setImageBitmap(resultBitmap)
                readingTextView.text = if (reading.isNullOrEmpty()) {
                    "No meter detected"
                } else {
                    reading
                }
            } catch (e: Exception) {
                Log.e(TAG, "Processing failed: ${e.message}", e)
                readingTextView.text = "Processing failed: ${e.message}"
            } finally {
                progressBar.visibility = View.GONE
                processButton.isEnabled = true
            }
        }
    }

    private fun saveCurrentImage() {
        val result = currentCaptureResult ?: return

        progressBar.visibility = View.VISIBLE
        saveButton.isEnabled = false

        lifecycleScope.launch {
            try {
                meterReading = currentMeterReading.toString()
                Log.d(TAG, "Current Reading: $currentMeterReading")

                imagePath = cameraManager.saveImage(result, currentMeterReading, savedFileName, editFlag).toString()
                Log.d(TAG, "Image saved at: $imagePath")

                showCameraView()
            } catch (e: Exception) {
                Log.e(TAG, "Failed to save: ${e.message}", e)
                Toast.makeText(this@MainActivity, "Failed to save image", Toast.LENGTH_SHORT).show()
            } finally {
                progressBar.visibility = View.GONE
                saveButton.isEnabled = true
            }
        }
    }

    // ============================================================================
    // Manual Input Handling
    // ============================================================================

    private fun handleReadingTextClick() {
        if (readingTextView.text.toString() == "No meter detected") {
            editFlag = true
        }

        tapCount++
        if (tapCount == 3) {
            showNumericBottomDialog()
            tapCount = 0
        } else {
            readingTextView.text = "Tap: ${3 - tapCount} times"
        }
    }

    private fun showNumericBottomDialog() {
        val bottomSheetDialog = BottomSheetDialog(this)
        val dialogView = layoutInflater.inflate(R.layout.dialog_numeric_keyboard, null)
        bottomSheetDialog.setContentView(dialogView)

        val displayText = dialogView.findViewById<TextView>(R.id.txtTitle)
        inputNumber.clear()

        // Set up number buttons
        setupNumericKeyboard(dialogView, displayText, bottomSheetDialog)

        bottomSheetDialog.show()
    }

    private fun setupNumericKeyboard(dialogView: View, displayText: TextView, dialog: BottomSheetDialog) {
        val numberButtonListener = View.OnClickListener { view ->
            val button = view as Button
            inputNumber.append(button.text.toString())
            displayText.text = inputNumber.toString()
        }

        // Assign listeners to number buttons
        val buttonIds = arrayOf(
            R.id.button_0, R.id.button_1, R.id.button_2, R.id.button_3, R.id.button_4,
            R.id.button_5, R.id.button_6, R.id.button_7, R.id.button_8, R.id.button_9,
            R.id.button_decimal
        )

        buttonIds.forEach { id ->
            dialogView.findViewById<Button>(id).setOnClickListener(numberButtonListener)
        }

        // Clear button
        dialogView.findViewById<Button>(R.id.button_clear).setOnClickListener {
            inputNumber.setLength(0)
            displayText.text = ""
        }

        // Enter button
        dialogView.findViewById<Button>(R.id.button_enter).setOnClickListener {
            readingTextView.text = inputNumber.toString()
            currentMeterReading = inputNumber.toString()
            editFlag = true
            dialog.dismiss()
        }
    }

    // ============================================================================
    // Result Handling
    // ============================================================================

    private fun sendBackValues() {
        // Create metadata JSON
        val metadata = JSONObject().apply {
            put("meterReading", meterReading)
            put("filename", imagePath)
            put("isEdited", editFlag)
        }

        Log.d(TAG, "Metadata: $metadata")
        Log.d(TAG, "ValType: $valType")

        // Set result code based on value type
        val resultCode = when (valType) {
            "KWH" -> OCR_KWH_RESULT_CODE
            "KVAH" -> OCR_KVAH_RESULT_CODE
            "SKWH" -> OCR_SKWH_RESULT_CODE
            "SKVAH" -> OCR_SKVAH_RESULT_CODE
            "RMD" -> OCR_RMD_RESULT_CODE
            "LT" -> OCR_LT_RESULT_CODE
            "IMG" -> OCR_IMG_RESULT_CODE
            else -> OCR_INVALID_RESULT_CODE
        }

        setResult(resultCode, intent)
        intent.putExtra("metadata", metadata.toString())

        Log.d(TAG, "Result Code Set to: $resultCode")

        // Finish activity with delay
        CoroutineScope(Dispatchers.Main).launch {
            delay(1000)
            finish()
        }
    }

    // ============================================================================
    // Permission Handling
    // ============================================================================

    override fun onPermissionsGranted() {
        Log.d(TAG, "All permissions granted")
        initializeCamera()
        updateModelDisplay()
    }

    override fun onPermissionsDenied() {
        Log.e(TAG, "Permissions denied")
        Toast.makeText(this, "Camera and storage permissions are required", Toast.LENGTH_LONG).show()
        finish()
    }

    // ============================================================================
    // Utility Methods
    // ============================================================================

    private fun isAutoRotateOn(contentResolver: ContentResolver): Boolean {
        return Settings.System.getInt(
            contentResolver,
            Settings.System.ACCELEROMETER_ROTATION,
            0
        ) == 1
    }

    private fun getVersionName(): String {
        return try {
            val packageInfo = packageManager.getPackageInfo(packageName, 0)
            "(Ver:${packageInfo.versionName})"
        } catch (e: PackageManager.NameNotFoundException) {
            Log.e(TAG, "Version not found", e)
            "Version not found"
        }
    }

    private fun cleanupCaptureResult() {
        currentCaptureResult?.let {
            ImageCropper.safeRecycle(it.roiBitmap)
            ImageCropper.safeRecycle(it.modelBitmap)
            if (it.originalBitmap != it.roiBitmap && it.originalBitmap != it.modelBitmap) {
                ImageCropper.safeRecycle(it.originalBitmap)
            }
        }
    }

    private fun cleanupResources() {
        // Clean up bitmaps
        cleanupCaptureResult()

        // Clean up managers
        if (::cameraManager.isInitialized) {
            cameraManager.shutdown()
        }
        if (::meterDetector.isInitialized) {
            meterDetector.close()
        }
    }
}
//package com.example.cameraxapp
//
//import android.content.ContentResolver
//import android.content.Context
//import android.content.pm.ActivityInfo
//import android.content.pm.PackageManager
//import android.os.Bundle
//import android.provider.Settings
//import android.util.Log
//import android.view.View
//import android.widget.*
//import androidx.appcompat.app.AppCompatActivity
//import androidx.camera.view.PreviewView
//import androidx.lifecycle.lifecycleScope
//import androidx.recyclerview.widget.LinearLayoutManager
//import androidx.recyclerview.widget.RecyclerView
//import com.google.android.material.bottomsheet.BottomSheetDialog
//import kotlinx.coroutines.CoroutineScope
//import kotlinx.coroutines.Dispatchers
//import kotlinx.coroutines.delay
//import kotlinx.coroutines.flow.collectLatest
//import kotlinx.coroutines.launch
//import kotlinx.coroutines.withContext
//import org.json.JSONObject
//
//class MainActivity : AppCompatActivity(), PermissionManager.PermissionListener {
//
//    companion object {
//        private const val TAG = "MainActivity"
//
//        // Result codes for different meter types
//        const val OCR_KWH_RESULT_CODE = 666
//        const val OCR_KVAH_RESULT_CODE = 667
//        const val OCR_RMD_RESULT_CODE = 668
//        const val OCR_LT_RESULT_CODE = 669
//        const val OCR_IMG_RESULT_CODE = 770
//        const val OCR_SKWH_RESULT_CODE = 771
//        const val OCR_SKVAH_RESULT_CODE = 772
//        const val OCR_INVALID_RESULT_CODE = 773
//    }
//
//    // ============================================================================
//    // UI Components
//    // ============================================================================
//
//    // Camera preview components
//    private lateinit var previewView: PreviewView
//    private lateinit var roiOverlay: ROIOverlay
//    private lateinit var captureButton: Button
//    private lateinit var flashButton: ImageButton
//    private lateinit var settingsToggleButton: ImageButton
//    private lateinit var modelSelectButton: ImageButton
//
//    // Control components
//    private lateinit var zoomSeekBar: SeekBar
//    private lateinit var exposureSeekBar: SeekBar
//    private lateinit var thresholdSeekBar: SeekBar
//    private lateinit var zoomValueText: TextView
//    private lateinit var exposureValueText: TextView
//    private lateinit var thresholdValueText: TextView
//    private lateinit var controlPanel: ScrollView
//
//    // Info display components
//    private lateinit var titleTextView: TextView
//    private lateinit var serviceIdTextView: TextView
//    private lateinit var valueTypeTextView: TextView
//    private lateinit var currentModelTextView: TextView
//
//    // Result view components
//    private lateinit var resultLayout: LinearLayout
//    private lateinit var resultImageView: ImageView
//    private lateinit var readingTextView: TextView
//    private lateinit var resultServiceIdTextView: TextView
//    private lateinit var resultValueTypeTextView: TextView
//    private lateinit var saveButton: Button
//    private lateinit var retakeButton: Button
//    private lateinit var processButton: Button
//
//    // Progress indicator
//    private lateinit var progressBar: ProgressBar
//
//    // ============================================================================
//    // Managers and Detectors
//    // ============================================================================
//
//    private lateinit var permissionManager: PermissionManager
//    private lateinit var cameraManager: CameraManager
//    private lateinit var meterDetector: MeterDetector
//
//    // ============================================================================
//    // State Variables
//    // ============================================================================
//
//    private var currentCaptureResult: CameraManager.CaptureResult? = null
//    private var currentMeterReading: String? = null
//    private var currentModelInfo: MeterDetector.ModelInfo? = null
//    private val inputNumber = StringBuilder()
//    private var isControlPanelVisible = false
//
//    // App state variables
//    private var tapCount = 0
//    private var editFlag = false
//    private var serviceId = "default_service"
//    private var valType = "default"
//    private var savedFileName = "default"
//    private var meterReading = "null"
//    private var imagePath = "path_not_found"
//
//    // ============================================================================
//    // Activity Lifecycle
//    // ============================================================================
//
//    override fun onCreate(savedInstanceState: Bundle?) {
//        super.onCreate(savedInstanceState)
//        setContentView(R.layout.activity_main)
//
//        // Force portrait orientation
//        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
//
//        // Check auto-rotation settings
//        checkAutoRotationSettings()
//
//        // Extract data from intent
//        extractIntentData()
//
//        // Initialize UI components
//        initializeViews()
//
//        // Update UI with service info
//        updateServiceInfo()
//
//        // Initialize managers
//        initializeManagers()
//
//        // Request permissions
//        permissionManager.requestPermissions()
//    }
//
//    override fun onDestroy() {
//        super.onDestroy()
//        cleanupResources()
//    }
//
//    // ============================================================================
//    // Initialization Methods
//    // ============================================================================
//
//    private fun checkAutoRotationSettings() {
//        val isAutoRotateEnabled = isAutoRotateOn(contentResolver)
//        Log.d(TAG, if (isAutoRotateEnabled) "Auto-rotation is enabled" else "Auto-rotation is disabled")
//    }
//
//    private fun extractIntentData() {
//        val dataHandler = IntentDataHandler(intent)
//        serviceId = dataHandler.getServiceId()
//        valType = dataHandler.getValType()
//        savedFileName = "${serviceId}_${valType}"
//
//        Log.d(TAG, "ServiceID: $serviceId")
//        Log.d(TAG, "ValueType: $valType")
//    }
//
//    private fun initializeViews() {
//        // Camera preview components
//        previewView = findViewById(R.id.viewFinder)
//        roiOverlay = findViewById(R.id.roiOverlay)
//        captureButton = findViewById(R.id.captureButton)
//        flashButton = findViewById(R.id.flashButton)
//        settingsToggleButton = findViewById(R.id.settingsToggleButton)
//        modelSelectButton = findViewById(R.id.modelSelectButton)
//
//        // Control components
//        zoomSeekBar = findViewById(R.id.zoomSeekBar)
//        exposureSeekBar = findViewById(R.id.exposureSeekBar)
//        thresholdSeekBar = findViewById(R.id.thresholdSeekBar)
//        zoomValueText = findViewById(R.id.zoomValueText)
//        exposureValueText = findViewById(R.id.exposureValueText)
//        thresholdValueText = findViewById(R.id.thresholdValueText)
//        controlPanel = findViewById(R.id.controlPanel)
//
//        // Info display components
//        titleTextView = findViewById(R.id.titleTextView)
//        serviceIdTextView = findViewById(R.id.serviceIdTextView)
//        valueTypeTextView = findViewById(R.id.valueTypeTextView)
//        currentModelTextView = findViewById(R.id.currentModelTextView)
//
//        // Result view components
//        resultLayout = findViewById(R.id.resultLayout)
//        resultImageView = findViewById(R.id.resultImageView)
//        readingTextView = findViewById(R.id.readingTextView)
//        resultServiceIdTextView = findViewById(R.id.resultServiceIdTextView)
//        resultValueTypeTextView = findViewById(R.id.resultValueTypeTextView)
//        saveButton = findViewById(R.id.saveButton)
//        retakeButton = findViewById(R.id.retakeButton)
//        processButton = findViewById(R.id.processButton)
//
//        // Progress indicator
//        progressBar = findViewById(R.id.progressBar)
//
//        // Set up click listeners
//        setupClickListeners()
//
//        // Set up seekbar listeners
//        setupSeekBarListeners()
//
//        // Update title with version
//        titleTextView.text = "Ebilly OCR ${getVersionName()}"
//    }
//
//    private fun updateServiceInfo() {
//        serviceIdTextView.text = serviceId
//        valueTypeTextView.text = valType
//        resultServiceIdTextView.text = serviceId
//        resultValueTypeTextView.text = valType
//    }
//
//    private fun initializeManagers() {
//        permissionManager = PermissionManager(this, this)
//        meterDetector = MeterDetector(this)
//    }
//
//    // ============================================================================
//    // UI Setup Methods
//    // ============================================================================
//
//    private fun setupClickListeners() {
//        // Camera control buttons
//        captureButton.setOnClickListener { captureImage(valType) }
//        flashButton.setOnClickListener { toggleFlash() }
//        settingsToggleButton.setOnClickListener { toggleControlPanel() }
//        modelSelectButton.setOnClickListener { showModelSelectionDialog() }
//
//        // Result view buttons
//        saveButton.setOnClickListener {
//            saveCurrentImage()
//            sendBackValues()
//        }
//        retakeButton.setOnClickListener { showCameraView() }
//        processButton.setOnClickListener { processCurrentImage() }
//
//        // Reading text view for manual editing
//        readingTextView.setOnClickListener { handleReadingTextClick() }
//    }
//
//    private fun setupSeekBarListeners() {
//        // Zoom control
//        zoomSeekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
//            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
//                if (fromUser && ::cameraManager.isInitialized) {
//                    val zoomLevel = progress / 100f
//                    cameraManager.setZoom(zoomLevel)
//                    zoomValueText.text = "${progress}%"
//                }
//            }
//            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
//            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
//        })
//
//        // Exposure control
//        exposureSeekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
//            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
//                if (fromUser && ::cameraManager.isInitialized) {
//                    exposureValueText.text = progress.toString()
//                    // Uncomment when exposure control is implemented
//                    // cameraManager.setExposure(progress)
//                }
//            }
//            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
//            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
//        })
//
//        // Threshold control
//        thresholdSeekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
//            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
//                if (fromUser) {
//                    thresholdValueText.text = "${progress}%"
//                    // Update detection threshold in meter detector
//                    if (::meterDetector.isInitialized) {
//                        meterDetector.setConfidenceThreshold(progress / 100f)
//                    }
//                }
//            }
//            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
//            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
//        })
//    }
//
//    // ============================================================================
//    // Camera Management
//    // ============================================================================
//
//    private fun initializeCamera() {
//        Log.d(TAG, "Initializing camera")
//
//        cameraManager = CameraManager(this, this, previewView, roiOverlay)
//
//        // Observe camera capture results
//        lifecycleScope.launch {
//            cameraManager.captureResult.collectLatest { result ->
//                result?.let {
//                    currentCaptureResult = it
//                    showResultView(it)
//                }
//            }
//        }
//
//        // Start camera
//        cameraManager.initialize()
//
//        // Set initial values
//        exposureSeekBar.progress = 50
//        exposureValueText.text = "50"
//        zoomValueText.text = "0%"
//        thresholdValueText.text = "50%"
//    }
//
//    private fun captureImage(valType: String) {
//        if (::cameraManager.isInitialized) {
//            progressBar.visibility = View.VISIBLE
//            captureButton.isEnabled = false
//            cameraManager.captureImage(valType)
//        }
//    }
//
//    private fun toggleFlash() {
//        if (::cameraManager.isInitialized) {
//            val flashOn = cameraManager.toggleFlash()
//            flashButton.setImageResource(
//                if (flashOn) R.drawable.ic_flash_on else R.drawable.ic_flash_off
//            )
//        }
//    }
//
//    // ============================================================================
//    // Control Panel Management
//    // ============================================================================
//
//    private fun toggleControlPanel() {
//        if (isControlPanelVisible) {
//            hideControlPanel()
//        } else {
//            showControlPanel()
//        }
//    }
//
//    private fun showControlPanel() {
//        controlPanel.visibility = View.VISIBLE
//        settingsToggleButton.setImageResource(R.drawable.ic_settings_filled)
//        isControlPanelVisible = true
//    }
//
//    private fun hideControlPanel() {
//        controlPanel.visibility = View.GONE
//        settingsToggleButton.setImageResource(R.drawable.ic_settings)
//        isControlPanelVisible = false
//    }
//
//    // ============================================================================
//    // Model Management
//    // ============================================================================
//
//    private fun updateModelDisplay() {
//        currentModelInfo = meterDetector.getCurrentModel()
//        currentModelTextView.text = currentModelInfo?.displayName ?: "Default"
//    }
//
//    private fun showModelSelectionDialog() {
//        val bottomSheetDialog = BottomSheetDialog(this)
//        val view = layoutInflater.inflate(R.layout.model_selection_bottom_sheet, null)
//        bottomSheetDialog.setContentView(view)
//
//        val recyclerView = view.findViewById<RecyclerView>(R.id.modelRecyclerView)
//        recyclerView.layoutManager = LinearLayoutManager(this)
//
//        val availableModels = meterDetector.getAvailableModels()
//        currentModelInfo = meterDetector.getCurrentModel()
//
//        val adapter = ModelSelectionAdapter(
//            models = availableModels,
//            currentModelFileName = currentModelInfo?.fileName ?: "",
//            onModelSelected = { selectedModel ->
//                loadSelectedModel(selectedModel, bottomSheetDialog)
//            }
//        )
//
//        recyclerView.adapter = adapter
//        bottomSheetDialog.show()
//    }
//
//    private fun loadSelectedModel(selectedModel: MeterDetector.ModelInfo, dialog: BottomSheetDialog) {
//        val success = meterDetector.loadModel(selectedModel)
//        if (success) {
//            currentModelInfo = selectedModel
//            updateModelDisplay()
//            Toast.makeText(this, "Model '${selectedModel.displayName}' loaded", Toast.LENGTH_SHORT).show()
//        } else {
//            Toast.makeText(this, "Failed to load model", Toast.LENGTH_SHORT).show()
//        }
//        dialog.dismiss()
//    }
//
//    // ============================================================================
//    // View Management
//    // ============================================================================
//
//    private fun showCameraView() {
//        // Show camera components
//        resultLayout.visibility = View.GONE
//        previewView.visibility = View.VISIBLE
//        roiOverlay.visibility = View.VISIBLE
//        captureButton.visibility = View.VISIBLE
//        flashButton.visibility = View.VISIBLE
//
//        // Clean up previous results
//        cleanupCaptureResult()
//
//        // Reset state
//        currentCaptureResult = null
//        currentMeterReading = null
//    }
//
//    private fun showResultView(result: CameraManager.CaptureResult) {
//        // Hide progress and enable capture button
//        progressBar.visibility = View.GONE
//        captureButton.isEnabled = true
//
//        // Update result view
//        resultImageView.setImageBitmap(result.roiBitmap)
//        resultLayout.visibility = View.VISIBLE
//
//        // Hide camera components
//        previewView.visibility = View.GONE
//        roiOverlay.visibility = View.GONE
//        captureButton.visibility = View.GONE
//        flashButton.visibility = View.GONE
//
//        // Set initial reading text based on value type
//        readingTextView.text = if (valType == "IMG") "Tap 'Save'" else "Tap 'Process'"
//        currentMeterReading = null
//
//        // Update service info in result view
//        resultServiceIdTextView.text = serviceId
//        resultValueTypeTextView.text = valType
//    }
//
//    // ============================================================================
//    // Image Processing
//    // ============================================================================
//
//    private fun processCurrentImage() {
//        val result = currentCaptureResult ?: return
//
//        readingTextView.text = "Processing..."
//        progressBar.visibility = View.VISIBLE
//        processButton.isEnabled = false
//
//        lifecycleScope.launch {
//            try {
//                val processingResult = withContext(Dispatchers.IO) {
//                    val (detections, resultBitmap) = meterDetector.detectMeterReading(result.modelBitmap)
//                    val reading = meterDetector.extractMeterReading(detections)
//                    Pair(reading, resultBitmap)
//                }
//
//                val (reading, resultBitmap) = processingResult
//                currentMeterReading = reading
//
//                resultImageView.setImageBitmap(resultBitmap)
//                readingTextView.text = if (reading.isNullOrEmpty()) {
//                    "No meter detected"
//                } else {
//                    reading
//                }
//            } catch (e: Exception) {
//                Log.e(TAG, "Processing failed: ${e.message}", e)
//                readingTextView.text = "Processing failed: ${e.message}"
//            } finally {
//                progressBar.visibility = View.GONE
//                processButton.isEnabled = true
//            }
//        }
//    }
//
//    private fun saveCurrentImage() {
//        val result = currentCaptureResult ?: return
//
//        progressBar.visibility = View.VISIBLE
//        saveButton.isEnabled = false
//
//        lifecycleScope.launch {
//            try {
//                meterReading = currentMeterReading.toString()
//                Log.d(TAG, "Current Reading: $currentMeterReading")
//
//                imagePath = cameraManager.saveImage(result, currentMeterReading, savedFileName, editFlag).toString()
//                Log.d(TAG, "Image saved at: $imagePath")
//
//                showCameraView()
//            } catch (e: Exception) {
//                Log.e(TAG, "Failed to save: ${e.message}", e)
//                Toast.makeText(this@MainActivity, "Failed to save image", Toast.LENGTH_SHORT).show()
//            } finally {
//                progressBar.visibility = View.GONE
//                saveButton.isEnabled = true
//            }
//        }
//    }
//
//    // ============================================================================
//    // Manual Input Handling
//    // ============================================================================
//
//    private fun handleReadingTextClick() {
//        if (readingTextView.text.toString() == "No meter detected") {
//            editFlag = true
//        }
//
//        tapCount++
//        if (tapCount == 3) {
//            showNumericBottomDialog()
//            tapCount = 0
//        } else {
//            readingTextView.text = "Tap: ${3 - tapCount} times"
//        }
//    }
//
//    private fun showNumericBottomDialog() {
//        val bottomSheetDialog = BottomSheetDialog(this)
//        val dialogView = layoutInflater.inflate(R.layout.dialog_numeric_keyboard, null)
//        bottomSheetDialog.setContentView(dialogView)
//
//        val displayText = dialogView.findViewById<TextView>(R.id.txtTitle)
//        inputNumber.clear()
//
//        // Set up number buttons
//        setupNumericKeyboard(dialogView, displayText, bottomSheetDialog)
//
//        bottomSheetDialog.show()
//    }
//
//    private fun setupNumericKeyboard(dialogView: View, displayText: TextView, dialog: BottomSheetDialog) {
//        val numberButtonListener = View.OnClickListener { view ->
//            val button = view as Button
//            inputNumber.append(button.text.toString())
//            displayText.text = inputNumber.toString()
//        }
//
//        // Assign listeners to number buttons
//        val buttonIds = arrayOf(
//            R.id.button_0, R.id.button_1, R.id.button_2, R.id.button_3, R.id.button_4,
//            R.id.button_5, R.id.button_6, R.id.button_7, R.id.button_8, R.id.button_9,
//            R.id.button_decimal
//        )
//
//        buttonIds.forEach { id ->
//            dialogView.findViewById<Button>(id).setOnClickListener(numberButtonListener)
//        }
//
//        // Clear button
//        dialogView.findViewById<Button>(R.id.button_clear).setOnClickListener {
//            inputNumber.setLength(0)
//            displayText.text = ""
//        }
//
//        // Enter button
//        dialogView.findViewById<Button>(R.id.button_enter).setOnClickListener {
//            readingTextView.text = inputNumber.toString()
//            currentMeterReading = inputNumber.toString()
//            editFlag = true
//            dialog.dismiss()
//        }
//    }
//
//    // ============================================================================
//    // Result Handling
//    // ============================================================================
//
//    private fun sendBackValues() {
//        // Create metadata JSON
//        val metadata = JSONObject().apply {
//            put("meterReading", meterReading)
//            put("filename", imagePath)
//            put("isEdited", editFlag)
//        }
//
//        Log.d(TAG, "Metadata: $metadata")
//        Log.d(TAG, "ValType: $valType")
//
//        // Set result code based on value type
//        val resultCode = when (valType) {
//            "KWH" -> OCR_KWH_RESULT_CODE
//            "KVAH" -> OCR_KVAH_RESULT_CODE
//            "SKWH" -> OCR_SKWH_RESULT_CODE
//            "SKVAH" -> OCR_SKVAH_RESULT_CODE
//            "RMD" -> OCR_RMD_RESULT_CODE
//            "LT" -> OCR_LT_RESULT_CODE
//            "IMG" -> OCR_IMG_RESULT_CODE
//            else -> OCR_INVALID_RESULT_CODE
//        }
//
//        setResult(resultCode, intent)
//        intent.putExtra("metadata", metadata.toString())
//
//        Log.d(TAG, "Result Code Set to: $resultCode")
//
//        // Finish activity with delay
//        CoroutineScope(Dispatchers.Main).launch {
//            delay(1000)
//            finish()
//        }
//    }
//
//    // ============================================================================
//    // Permission Handling
//    // ============================================================================
//
//    override fun onPermissionsGranted() {
//        Log.d(TAG, "All permissions granted")
//        initializeCamera()
//        updateModelDisplay()
//    }
//
//    override fun onPermissionsDenied() {
//        Log.e(TAG, "Permissions denied")
//        Toast.makeText(this, "Camera and storage permissions are required", Toast.LENGTH_LONG).show()
//        finish()
//    }
//
//    // ============================================================================
//    // Utility Methods
//    // ============================================================================
//
//    private fun isAutoRotateOn(contentResolver: ContentResolver): Boolean {
//        return Settings.System.getInt(
//            contentResolver,
//            Settings.System.ACCELEROMETER_ROTATION,
//            0
//        ) == 1
//    }
//
//    private fun getVersionName(): String {
//        return try {
//            val packageInfo = packageManager.getPackageInfo(packageName, 0)
//            "(Ver:${packageInfo.versionName})"
//        } catch (e: PackageManager.NameNotFoundException) {
//            Log.e(TAG, "Version not found", e)
//            "Version not found"
//        }
//    }
//
//    private fun cleanupCaptureResult() {
//        currentCaptureResult?.let {
//            ImageCropper.safeRecycle(it.roiBitmap)
//            ImageCropper.safeRecycle(it.modelBitmap)
//            if (it.originalBitmap != it.roiBitmap && it.originalBitmap != it.modelBitmap) {
//                ImageCropper.safeRecycle(it.originalBitmap)
//            }
//        }
//    }
//
//    private fun cleanupResources() {
//        // Clean up bitmaps
//        cleanupCaptureResult()
//
//        // Clean up managers
//        if (::cameraManager.isInitialized) {
//            cameraManager.shutdown()
//        }
//        if (::meterDetector.isInitialized) {
//            meterDetector.close()
//        }
//    }
//}

//package com.example.cameraxapp
//import android.content.pm.ActivityInfo
//import android.content.pm.PackageManager
//import android.os.Bundle
//import android.util.Log
//import android.view.View
//import android.widget.Button
//import android.widget.ImageButton
//import android.widget.ImageView
//import android.widget.LinearLayout
//import android.widget.ProgressBar
//import android.widget.SeekBar
//import android.widget.TextView
//import android.widget.Toast
//import androidx.appcompat.app.AppCompatActivity
//import androidx.camera.view.PreviewView
//import androidx.lifecycle.lifecycleScope
//import com.google.android.material.bottomsheet.BottomSheetDialog
//import kotlinx.coroutines.CoroutineScope
//import kotlinx.coroutines.Dispatchers
//import kotlinx.coroutines.delay
//import kotlinx.coroutines.flow.collectLatest
//import kotlinx.coroutines.launch
//import kotlinx.coroutines.withContext
//import org.json.JSONObject
//import android.content.ContentResolver
//import android.content.Context
//import android.provider.Settings
//import android.provider.Settings.System
//import androidx.appcompat.widget.SwitchCompat
//import androidx.recyclerview.widget.LinearLayoutManager
//import androidx.recyclerview.widget.RecyclerView
//import androidx.core.content.edit
//
//class MainActivity : AppCompatActivity(), PermissionManager.PermissionListener {
//    private val tag = "MainActivity"
//
//    // UI components
//    private lateinit var previewView: PreviewView
//    private lateinit var roiOverlay: ROIOverlay
//    private lateinit var captureButton: Button
//    private lateinit var flashButton: ImageButton
//
//    private lateinit var zoomSeekBar: SeekBar
//    private lateinit var exposureSeekBar: SeekBar
//    private lateinit var progressBar: ProgressBar
//    private lateinit var resultLayout: LinearLayout
//    private lateinit var resultImageView: ImageView
//    private lateinit var readingTextView: TextView
//
//    private lateinit var titleTextView: TextView
//    private lateinit var serviceIdTextView: TextView
//    private lateinit var valueTypeTextView: TextView
//    private lateinit var resultServiceIdTextView: TextView
//    private lateinit var resultValueTypeTextView: TextView
//
//    private lateinit var saveButton: Button
//    private lateinit var retakeButton: Button
//    private lateinit var processButton: Button
//
//    // Managers and detectors
//    private lateinit var permissionManager: PermissionManager
//    private lateinit var cameraManager: CameraManager
//    private lateinit var meterDetector: MeterDetector
//
//    // Add this as a class variable
//    private lateinit var currentModelTextView: TextView
//    // Add this to the class variables
//    private lateinit var modelSelectButton: ImageButton
//    private var currentModelInfo: MeterDetector.ModelInfo? = null
//
//    private var visualizeDetections = false
//
//    // State variables
//    private var currentCaptureResult: CameraManager.CaptureResult? = null
//    private var currentMeterReading: String? = null
//    private val inputNumber = StringBuilder()
//
//    var tapCount = 0
//    var editFlag = false
//    var serviceId = "default_service"
//    var valType = "default"
//    var savedFileName="default"
//    var meterReading="null"
//    var imagePath="paht_not_found"
//
//    override fun onCreate(savedInstanceState: Bundle?) {
//        super.onCreate(savedInstanceState)
//        setContentView(R.layout.activity_main)
//
//
//        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
//
//        // Inside your Activity class
//        val isAutoRotateEnabled = isAutoRotateOn(contentResolver)
//
//
//        if (isAutoRotateEnabled) {
//            // Auto-rotation is on
//            Log.d(tag, "Auto-rotation is enabled")
//        } else {
//            // Auto-rotation is off
//            Log.d(tag, "Auto-rotation is disabled")
//        }
//
//        // Create an instance with your intent
//        val dataHandler = IntentDataHandler(intent)
//
//        serviceId = dataHandler.getServiceId()
//        valType = dataHandler.getValType()
//        savedFileName = serviceId + "_" + valType
//
//        // Initialize UI components
//        initializeViews()
//
//        // Update UI with service ID and value type
//        serviceIdTextView.text = serviceId
//        valueTypeTextView.text = valType
//
//        Log.d(tag, "ServiceID: $serviceId")
//        Log.d(tag, "ValueType: $valType")
//
//        resultServiceIdTextView.text = serviceId
//        resultValueTypeTextView.text = valType
//
//        // Initialize permission manager
//        permissionManager = PermissionManager(this, this)
//
//        // Initialize meter detector
//        meterDetector = MeterDetector(this)
//
//        // Request permissions
//        permissionManager.requestPermissions()
//    }
//
//
//    // Method to check if auto-rotation is enabled
//    fun isAutoRotateOn(contentResolver: ContentResolver): Boolean {
//        return Settings.System.getInt(
//            contentResolver,
//            Settings.System.ACCELEROMETER_ROTATION,
//            0
//        ) == 1
//    }
//
//    private fun getVersionName(): String {
//        return try {
//            val packageManager = packageManager
//            val packageInfo = packageManager.getPackageInfo(packageName, 0)
//            "(Ver:${packageInfo.versionName})"
//        } catch (e: PackageManager.NameNotFoundException) {
//            e.printStackTrace()
//            "Version not found"
//        }
//    }
//
//    /**
//     * Initialize UI components and set up listeners
//     */
//    private fun initializeViews() {
//        // Camera preview components
//        previewView = findViewById(R.id.viewFinder)
//        roiOverlay = findViewById(R.id.roiOverlay)
//        captureButton = findViewById(R.id.captureButton)
//        flashButton = findViewById(R.id.flashButton)
//        zoomSeekBar = findViewById(R.id.zoomSeekBar)
//        exposureSeekBar = findViewById(R.id.exposureSeekBar)
//        progressBar = findViewById(R.id.progressBar)
//
//        // Add this to initializeViews() method
//        modelSelectButton = findViewById(R.id.modelSelectButton)
//
//
//        // Service ID and Value Type displays
//        serviceIdTextView = findViewById(R.id.serviceIdTextView)
//        valueTypeTextView = findViewById(R.id.valueTypeTextView)
//
//        // Result view components
//        resultLayout = findViewById(R.id.resultLayout)
//        resultImageView = findViewById(R.id.resultImageView)
//        readingTextView = findViewById(R.id.readingTextView)
//        resultServiceIdTextView = findViewById(R.id.resultServiceIdTextView)
//        resultValueTypeTextView = findViewById(R.id.resultValueTypeTextView)
//        saveButton = findViewById(R.id.saveButton)
//        retakeButton = findViewById(R.id.retakeButton)
//        processButton = findViewById(R.id.processButton)
//        titleTextView = findViewById(R.id.titleTextView)
//
//
//        currentModelTextView = findViewById(R.id.currentModelTextView)
//
//        // Set up button click listeners
//        captureButton.setOnClickListener {
//            captureImage(valType)
//        }
//
//        flashButton.setOnClickListener {
//            toggleFlash()
//        }
//
//
//
//        saveButton.setOnClickListener {
//            saveCurrentImage()
//            sendBackvalues()
//
//        }
//
//        modelSelectButton.setOnClickListener {
//            showModelSelectionDialog()
//        }
//
//
//            // Update the detection info visibility
//
//
//        readingTextView.setOnClickListener {
//            if (readingTextView.text.toString() == "No meter detected") {
//                editFlag = true
//            }
//
//            tapCount = tapCount + 1
//            if (tapCount == 3) {
//                showNumericBottomDialog()
//                tapCount = 0
//            } else {
//                readingTextView.text = buildString {
//                    append("Tap: ")
//                    append(3 - tapCount)
//                    append(" times")
//                }
//            }
//        }
//
//        retakeButton.setOnClickListener {
//            showCameraView()
//        }
//
//        processButton.setOnClickListener {
//            processCurrentImage()
//        }
//
//
//
//        // Set up zoom seekbar
//        zoomSeekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
//            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
//                if (fromUser && ::cameraManager.isInitialized) {
//                    val zoomLevel = progress / 100f
//                    cameraManager.setZoom(zoomLevel)
//                }
//            }
//
//            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
//
//            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
//        })
//
//        // Set up exposure seekbar
//        exposureSeekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
//            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
//                if (fromUser && ::cameraManager.isInitialized) {
//                   // cameraManager.setExposure(progress)
//                }
//            }
//
//            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
//
//            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
//        })
//
//        titleTextView.text = buildString {
//            append("Ebilly OCR ")
//            append(getVersionName())
//        }
//    }
//
//    // Update the UI after loading a model
//    private fun updateModelDisplay() {
//        currentModelInfo = meterDetector.getCurrentModel()
//        currentModelTextView.text = "Model: ${currentModelInfo?.displayName ?: "None"}"
//    }
//    // Add this new method to show the model selection dialog
//    private fun showModelSelectionDialog() {
//        val bottomSheetDialog = BottomSheetDialog(this)
//        val view = layoutInflater.inflate(R.layout.model_selection_bottom_sheet, null)
//        bottomSheetDialog.setContentView(view)
//
//        val recyclerView = view.findViewById<RecyclerView>(R.id.modelRecyclerView)
//        recyclerView.layoutManager = LinearLayoutManager(this)
//
//        val availableModels = meterDetector.getAvailableModels()
//        currentModelInfo = meterDetector.getCurrentModel()
//
//        val adapter = ModelSelectionAdapter(
//            models = availableModels,
//            currentModelFileName = currentModelInfo?.fileName ?: "",
//            onModelSelected = { selectedModel ->
//                // Load the selected model
//                val success = meterDetector.loadModel(selectedModel)
//                if (success) {
//                    currentModelInfo = selectedModel
//                    updateModelDisplay()
//                    Toast.makeText(this, "Model '${selectedModel.displayName}' loaded", Toast.LENGTH_SHORT).show()
//                } else {
//                    Toast.makeText(this, "Failed to load model", Toast.LENGTH_SHORT).show()
//                }
//                bottomSheetDialog.dismiss()
//            }
//        )
//
//        recyclerView.adapter = adapter
//        bottomSheetDialog.show()
//    }
//
//
//    private fun sendBackvalues(){
//        // Create JSON object with metadata
//        val metadata = JSONObject().apply {
//            put("meterReading", meterReading)
//            put("filename", imagePath)
//            put("isEdited", editFlag)
//            // Any other metadata fields you want to include
//        }
//
//        Log.d(tag, "Metadata: $metadata")
//
//        Log.d(tag, "Valtype: $valType")
//
//        // Add value type specific extras and set result code
//        when (valType) {
//            "KWH" -> {
//
//                setResult(OCR_KWH_RESULT_CODE, intent)
//                Log.d(tag, "Result Code Set to : $OCR_KWH_RESULT_CODE")
//            }
//            "KVAH" -> {
//
//                setResult(OCR_KVAH_RESULT_CODE, intent)
//                Log.d(tag, "Result Code Set to : $OCR_KVAH_RESULT_CODE")
//
//            }
//            "SKWH" -> {
//
//                setResult(OCR_SKWH_RESULT_CODE, intent)
//                Log.d(tag, "Result Code Set to : $OCR_SKWH_RESULT_CODE")
//            }
//            "SKVAH" -> {
//
//                setResult(OCR_SKVAH_RESULT_CODE, intent)
//                Log.d(tag, "Result Code Set to : $OCR_SKVAH_RESULT_CODE")
//
//            }
//            "RMD" -> {
//
//                setResult(OCR_RMD_RESULT_CODE, intent)
//                Log.d(tag, "Result Code Set to : ${OCR_RMD_RESULT_CODE}")
//
//            }
//            "LT" -> {
//                setResult(OCR_LT_RESULT_CODE, intent)
//                Log.d(tag, "Result Code Set to : ${OCR_LT_RESULT_CODE}")
//            }
//            "IMG" -> {
//                setResult(OCR_IMG_RESULT_CODE, intent)
//                Log.d(tag, "Result Code Set to : ${OCR_IMG_RESULT_CODE}")
//            }
//            else -> {
//                setResult(OCR_INVALID_RESULT_CODE, intent)
//                Log.d(tag, "Result Code Set to : ${OCR_INVALID_RESULT_CODE}")
//            }
//
//        }
//
//        intent.putExtra("metadata", metadata.toString())
//
//        // Launch a coroutine to delay and then finish the task
//        CoroutineScope(Dispatchers.Main).launch {
//            delay(1000) // 2 seconds delay
//            finish()
//        }
//    }
//
//    /**
//     * Initialize camera after permissions are granted
//     */
//    private fun initializeCamera() {
//        Log.d(tag, "Initializing camera")
//
//        // Initialize camera manager
//        cameraManager = CameraManager(this, this, previewView, roiOverlay)
//
//        // Observe camera capture results
//        lifecycleScope.launch {
//            cameraManager.captureResult.collectLatest { result ->
//                result?.let {
//                    currentCaptureResult = it
//                    showResultView(it)
//                }
//            }
//        }
//
//        // Start camera
//        cameraManager.initialize()
//
//        // Set initial exposure (middle value)
//        if (::cameraManager.isInitialized) {
//            exposureSeekBar.progress = 50
//           // cameraManager.setExposure(50)
//        }
//    }
//
//    /**
//     * Capture button click handler
//     */
//    private fun captureImage(valType1: String) {
//        if (::cameraManager.isInitialized) {
//            // Show progress indicator
//            progressBar.visibility = View.VISIBLE
//            captureButton.isEnabled = false
//
//            // Capture image
//            cameraManager.captureImage(valType1)
//        }
//    }
//
//    /**
//     * Toggle camera flash
//     */
//    private fun toggleFlash() {
//        if (::cameraManager.isInitialized) {
//            val flashOn = cameraManager.toggleFlash()
//            flashButton.setImageResource(
//                if (flashOn) R.drawable.ic_flash_on
//                else R.drawable.ic_flash_off
//            )
//        }
//    }
//
//    /**
//     * Switch between front and back cameras
//     */
//    private fun switchCamera() {
//        if (::cameraManager.isInitialized) {
//            cameraManager.switchCamera()
//        }
//    }
//
//    /**
//     * Show camera preview view
//     */
//    private fun showCameraView() {
//        resultLayout.visibility = View.GONE
//        previewView.visibility = View.VISIBLE
//        roiOverlay.visibility = View.VISIBLE
//        captureButton.visibility = View.VISIBLE
//        flashButton.visibility = View.VISIBLE
//
//        zoomSeekBar.visibility = View.VISIBLE
//        exposureSeekBar.visibility = View.VISIBLE
//
////        findViewById(R.id.exposureLabel).visibility = View.VISIBLE
////        findViewById(R.id.infoPanel).visibility = View.VISIBLE
//
//        // Clean up previous bitmaps to prevent memory leaks
//        currentCaptureResult?.let {
//            ImageCropper.safeRecycle(it.roiBitmap)
//            ImageCropper.safeRecycle(it.modelBitmap)
//            if (it.originalBitmap != it.roiBitmap && it.originalBitmap != it.modelBitmap) {
//                ImageCropper.safeRecycle(it.originalBitmap)
//            }
//        }
//
//        // Reset current capture result
//        currentCaptureResult = null
//        currentMeterReading = null
//    }
//
//    private fun showNumericBottomDialog() {
//        // Inflate the custom layout
//        val inflater = layoutInflater
//        val dialogView = inflater.inflate(R.layout.dialog_numeric_keyboard, null)
//
//        // Initialize BottomSheetDialog with the custom view
//        val bottomSheetDialog = BottomSheetDialog(this)
//        bottomSheetDialog.setContentView(dialogView)
//        bottomSheetDialog.show()
//
//        // Set up display TextView to show entered numbers
//        val displayText = dialogView.findViewById<TextView>(R.id.txtTitle)
//        inputNumber.clear()
//
//        // Numeric buttons logic
//        val numberButtonListener = View.OnClickListener { view ->
//            val button = view as Button
//            inputNumber.append(button.text.toString())
//            displayText.text = inputNumber.toString()
//        }
//
//        // Assign listener to each number button
//        dialogView.findViewById<Button>(R.id.button_0).setOnClickListener(numberButtonListener)
//        dialogView.findViewById<Button>(R.id.button_1).setOnClickListener(numberButtonListener)
//        dialogView.findViewById<Button>(R.id.button_2).setOnClickListener(numberButtonListener)
//        dialogView.findViewById<Button>(R.id.button_3).setOnClickListener(numberButtonListener)
//        dialogView.findViewById<Button>(R.id.button_4).setOnClickListener(numberButtonListener)
//        dialogView.findViewById<Button>(R.id.button_5).setOnClickListener(numberButtonListener)
//        dialogView.findViewById<Button>(R.id.button_6).setOnClickListener(numberButtonListener)
//        dialogView.findViewById<Button>(R.id.button_7).setOnClickListener(numberButtonListener)
//        dialogView.findViewById<Button>(R.id.button_8).setOnClickListener(numberButtonListener)
//        dialogView.findViewById<Button>(R.id.button_9).setOnClickListener(numberButtonListener)
//        dialogView.findViewById<Button>(R.id.button_decimal)
//            .setOnClickListener(numberButtonListener)
//
//        // Clear button logic
//        dialogView.findViewById<Button>(R.id.button_clear).setOnClickListener {
//            inputNumber.setLength(0)
//            displayText.text = ""
//        }
//
//        // Enter button logic
//        dialogView.findViewById<Button>(R.id.button_enter).setOnClickListener {
//            readingTextView.text = inputNumber.toString()
//            currentMeterReading = inputNumber.toString()
//            editFlag = true
//            bottomSheetDialog.dismiss()
//        }
//    }
//
//    /**
//     * Show result view with captured image
//     */
//    private fun showResultView(result: CameraManager.CaptureResult) {
//        // Hide progress indicator
//        progressBar.visibility = View.GONE
//        captureButton.isEnabled = true
//
//        // Update UI
//        resultImageView.setImageBitmap(result.roiBitmap)
//        resultLayout.visibility = View.VISIBLE
//        previewView.visibility = View.GONE
//        roiOverlay.visibility = View.GONE
//        captureButton.visibility = View.GONE
//        flashButton.visibility = View.GONE
//
//        zoomSeekBar.visibility = View.GONE
//        exposureSeekBar.visibility = View.GONE
////        findViewById(R.id.exposureLabel).visibility = View.GONE
////        findViewById(R.id.infoPanel).visibility = View.GONE
//
//        // Clear previous reading
//        if(valType=="IMG")
//            readingTextView.text = "Tap 'Save'"
//        else {
//            readingTextView.text = "Tap 'Process'"
//        }
//
//        currentMeterReading = null
//
//        // Make sure service ID and value type are displayed in the result view
//        resultServiceIdTextView.text = serviceId
//        resultValueTypeTextView.text = valType
//    }
//
//    /**
//     * Process the current captured image
//     */
//    private fun processCurrentImage() {
//        val result = currentCaptureResult ?: return
//
//        // These UI updates are on the main thread
//        readingTextView.text = "Processing..."
//        progressBar.visibility = View.VISIBLE
//        processButton.isEnabled = false
//
//        // Process in background
//        lifecycleScope.launch {
//            try {
//                // Run processing on IO dispatcher
//                val processingResult = withContext(Dispatchers.IO) {
//                    // Detect meter reading
//                    val (detections, resultBitmap) = meterDetector.detectMeterReading(result.modelBitmap)
//
//                    // Extract meter reading
//                    val reading = meterDetector.extractMeterReading(detections)
//
//                    Pair(reading, resultBitmap)
//                }
//
//                // Update UI on Main dispatcher
//                val (reading, resultBitmap) = processingResult
//                currentMeterReading = reading
//
//                resultImageView.setImageBitmap(resultBitmap)
//                readingTextView.text = if (reading.isNullOrEmpty()) {
//                    "No meter detected"
//                } else {
//                    "$reading"
//                }
//            } catch (e: Exception) {
//                Log.e(tag, "Processing failed: ${e.message}", e)
//                readingTextView.text = "Processing failed: ${e.message}"
//            } finally {
//                // Hide progress
//                progressBar.visibility = View.GONE
//                processButton.isEnabled = true
//            }
//        }
//    }
//
//    /**
//     * Save the current image and detected reading
//     */
//    private fun saveCurrentImage() {
//        val result = currentCaptureResult ?: return
//
//        progressBar.visibility = View.VISIBLE
//        saveButton.isEnabled = false
//
//        lifecycleScope.launch {
//            try {
//                meterReading=  currentMeterReading.toString()
//                Log.d(tag, "Current Reading: $currentMeterReading")
//                imagePath=cameraManager.saveImage(result, currentMeterReading, savedFileName, editFlag).toString()
//                Log.d(tag, "Image saved at: $imagePath")
//                showCameraView()
//            } catch (e: Exception) {
//                Log.e(tag, "Failed to save: ${e.message}", e)
//                Toast.makeText(this@MainActivity, "Failed to save image", Toast.LENGTH_SHORT).show()
//            } finally {
//                progressBar.visibility = View.GONE
//                saveButton.isEnabled = true
//            }
//        }
//    }
//
//    /**
//     * Permission granted callback
//     */
//    override fun onPermissionsGranted() {
//        Log.d(tag, "All permissions granted")
//        initializeCamera()
//    }
//
//    /**
//     * Permission denied callback
//     */
//    override fun onPermissionsDenied() {
//        Log.e(tag, "Permissions denied")
//        Toast.makeText(this, "Camera and storage permissions are required", Toast.LENGTH_LONG).show()
//        finish()
//    }
//
//    override fun onDestroy() {
//        super.onDestroy()
//
//        // Clean up bitmaps
//        currentCaptureResult?.let {
//            ImageCropper.safeRecycle(it.roiBitmap)
//            ImageCropper.safeRecycle(it.modelBitmap)
//            ImageCropper.safeRecycle(it.originalBitmap)
//        }
//
//        // Clean up other resources
//        if (::cameraManager.isInitialized) {
//            cameraManager.shutdown()
//        }
//        if (::meterDetector.isInitialized) {
//            meterDetector.close()
//        }
//    }
//    companion object {
//        const val OCR_KWH_RESULT_CODE = 666
//        const val OCR_KVAH_RESULT_CODE = 667
//        const val OCR_RMD_RESULT_CODE = 668
//        const val OCR_LT_RESULT_CODE = 669
//        const val OCR_IMG_RESULT_CODE = 770
//        const val OCR_SKWH_RESULT_CODE = 771
//        const val OCR_SKVAH_RESULT_CODE = 772
//        const val OCR_INVALID_RESULT_CODE = 773
//    }
//}
