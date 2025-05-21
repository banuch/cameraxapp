package com.example.cameraxapp

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.util.Log
import org.tensorflow.lite.Interpreter
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.MappedByteBuffer
import java.nio.channels.FileChannel

/**
 * Class to handle YOLOv8 model processing for meter reading detection
 *
 * The model used is YOLOv8 trained on meter reading digits and decimal points.
 *
 * Model specifications:
 * - Input shape: [1, 3, 640, 640] (batch size, channels, height, width)
 * - Input format: RGB normalized to [0,1]
 * - Output shape: [1, 16, 8400] where:
 *   - 16: 4 box coordinates (x, y, w, h) + 12 class probabilities
 *   - 8400: Number of detection predictions (grid cells)
 * - Classes: 12 (decimal point, digits 0-9, and "kwh" label)
 *
 * Inference process:
 * 1. Preprocess image to 640x640 with padding and normalization
 * 2. Run model inference
 * 3. Process output tensors into detection boxes, scores, and classes
 * 4. Apply Non-Maximum Suppression (NMS) to filter redundant detections
 * 5. Sort remaining detections from left to right to form meter reading
 */
class MeterDetector(private val context: Context) {
    private val tag = "MeterDetector"

    // TFLite interpreter
    private var interpreter: Interpreter? = null

    // Available model files in assets folder
    private val availableModels = mutableListOf<ModelInfo>()

    // Currently loaded model
    private var currentModel: ModelInfo? = null

    /**
     * Model parameters for YOLOv8 inference
     *
     * - inputSize: Input dimensions (square) required by the model
     * - numClasses: Number of classes the model can detect
     * - scoreThreshold: Minimum confidence threshold for valid detections
     * - iouThreshold: Intersection over Union threshold for NMS filtering
     * - classNames: Names of the detectable classes in order of class indices
     */
    private val inputSize = 640
    private val numClasses = 12
    private val scoreThreshold = 0.5f
    private val iouThreshold = 0.45f
    private val classNames = arrayOf(".", "0", "1", "2", "3", "4", "5", "6", "7", "8", "9", "kwh")

    /**
     * Data class to hold model information
     *
     * @param fileName Name of the model file in assets folder
     * @param displayName User-friendly name for UI display
     * @param description Optional description of model capabilities
     * @param version Optional version information
     */
    data class ModelInfo(
        val fileName: String,
        val displayName: String,
        val description: String = "",
        val version: String = "1.0"
    )

    init {
        // Scan assets directory for available models
        scanAvailableModels()

        // Load default model (first one found or fallback to weights.tflite)
        if (availableModels.isNotEmpty()) {
            loadModel(availableModels[0])
        } else {
            try {
                val defaultModel = ModelInfo(
                    "weights.tflite",
                    "Default Meter Detection",
                    "Default model for meter reading detection"
                )
                availableModels.add(defaultModel)
                loadModel(defaultModel)
            } catch (e: Exception) {
                Log.e(tag, "Error loading default model: ${e.message}", e)
            }
        }
    }

    /**
     * Scan the assets directory for available TFLite model files
     * Populates the availableModels list with detected models
     */
    private fun scanAvailableModels() {
        try {
            val modelFiles = context.assets.list("")?.filter {
                it.endsWith(".tflite")
            } ?: emptyList()

            availableModels.clear()

            for (fileName in modelFiles) {
                // Create a display name from the file name
                val displayName = fileName
                    .removeSuffix(".tflite")
                    .replace("_", " ")
                    .split(" ")
                    .joinToString(" ") { word ->
                        word.replaceFirstChar {
                            if (it.isLowerCase()) it.titlecase() else it.toString()
                        }
                    }

                // Try to extract version if present (e.g., "model_v2.1.tflite" -> "2.1")
                val versionRegex = """v(\d+(\.\d+)?)""".toRegex()
                val versionMatch = versionRegex.find(fileName)
                val version = versionMatch?.groupValues?.get(1) ?: "1.0"

                // Create description based on file name
                val description = when {
                    fileName.contains("analog") ->
                        "Optimized for analog meter readings"
                    fileName.contains("digital") ->
                        "Optimized for digital meter readings"
                    fileName.contains("tiny") || fileName.contains("small") ->
                        "Lightweight model for faster inference"
                    fileName.contains("accurate") || fileName.contains("precise") ->
                        "High accuracy model (slower inference)"
                    else ->
                        "General meter reading detection model"
                }

                availableModels.add(
                    ModelInfo(
                        fileName = fileName,
                        displayName = displayName,
                        description = description,
                        version = version
                    )
                )

                Log.d(tag, "Found model: $fileName ($displayName), v$version")
            }

            Log.d(tag, "Found ${availableModels.size} model files in assets")
        } catch (e: Exception) {
            Log.e(tag, "Error scanning for models: ${e.message}", e)
        }
    }

    /**
     * Get list of available models
     *
     * @return List of ModelInfo objects for all available models
     */
    fun getAvailableModels(): List<ModelInfo> {
        return availableModels
    }

    /**
     * Get currently loaded model
     *
     * @return ModelInfo of the currently loaded model, or null if no model is loaded
     */
    fun getCurrentModel(): ModelInfo? {
        return currentModel
    }

    /**
     * Load a specific model by ModelInfo
     *
     * @param modelInfo The model info object to load
     * @return True if model loaded successfully, false otherwise
     */
    fun loadModel(modelInfo: ModelInfo): Boolean {
        try {
            Log.d(tag, "Loading model: ${modelInfo.fileName}")

            // Close existing interpreter if any
            interpreter?.close()

            // Load new model
            interpreter = Interpreter(loadModelFile(modelInfo.fileName))

            // Update current model
            currentModel = modelInfo

            Log.d(tag, "Model ${modelInfo.displayName} (${modelInfo.fileName}) loaded successfully")
            return true
        } catch (e: Exception) {
            Log.e(tag, "Error loading model ${modelInfo.fileName}: ${e.message}", e)
            return false
        }
    }

    /**
     * Process image and detect meter readings
     *
     * @param bitmap The input image bitmap
     * @return Pair of detection results and processed bitmap with detections drawn
     *
     * Input processing:
     * - Resize to 640x640 with padding to maintain aspect ratio
     * - Normalize pixel values to [0,1] range
     *
     * Output processing:
     * - Parse YOLOv8 output tensor [16, 8400]
     * - Extract bounding boxes, confidences, and class predictions
     * - Filter by confidence threshold
     * - Apply NMS to remove overlapping detections
     * - Sort digits from left to right to form the meter reading
     */
    fun detectMeterReading(bitmap: Bitmap): Pair<List<DetectionResult>, Bitmap> {
        // Ensure model is loaded
        val interpreter = interpreter ?: return Pair(emptyList(), bitmap)

        // Prepare input: resize to 640x640 and normalize
        val inputBitmap = prepareInputBitmap(bitmap)

        // Convert bitmap to input tensor
        val inputBuffer = ByteBuffer.allocateDirect(inputSize * inputSize * 3 * 4) // 4 bytes per float
        inputBuffer.order(ByteOrder.nativeOrder())

        // Fill input buffer with normalized pixel values
        for (y in 0 until inputSize) {
            for (x in 0 until inputSize) {
                val pixel = inputBitmap.getPixel(x, y)
                // YOLOv8 expects RGB normalized to [0,1]
                inputBuffer.putFloat(Color.red(pixel) / 255.0f)
                inputBuffer.putFloat(Color.green(pixel) / 255.0f)
                inputBuffer.putFloat(Color.blue(pixel) / 255.0f)
            }
        }
        inputBuffer.rewind()

        // Prepare output buffer based on YOLOv8 output shape [1, 16, 8400]
        val outputBuffer = Array(1) { Array(16) { FloatArray(8400) } }

        // Run inference
        interpreter.run(inputBuffer, outputBuffer)

        // Process YOLOv8 output and get detection results
        val detections = processYoloOutput(outputBuffer[0], inputSize, inputSize)

        // Draw detections on a copy of the input image for visualization
        val resultBitmap = drawDetections(inputBitmap, detections)

        return Pair(detections, resultBitmap)
    }

    /**
     * Prepare input bitmap - resize to 640x640 with gray padding if needed
     *
     * @param bitmap Original input bitmap
     * @return Processed bitmap suitable for model input
     */
    private fun prepareInputBitmap(bitmap: Bitmap): Bitmap {
        // Create a 640x640 bitmap for the model input
        val inputBitmap = Bitmap.createBitmap(inputSize, inputSize, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(inputBitmap)

        // Fill with gray background
        canvas.drawColor(Color.LTGRAY)

        // Calculate scaling to fit the original bitmap
        val scale = if (bitmap.width > bitmap.height) {
            inputSize.toFloat() / bitmap.width
        } else {
            inputSize.toFloat() / bitmap.height
        }

        val scaledWidth = (bitmap.width * scale).toInt()
        val scaledHeight = (bitmap.height * scale).toInt()

        // Scale the original bitmap
        val scaledBitmap = Bitmap.createScaledBitmap(bitmap, scaledWidth, scaledHeight, true)

        // Center the scaled bitmap on the input bitmap
        val left = (inputSize - scaledWidth) / 2f
        val top = (inputSize - scaledHeight) / 2f

        canvas.drawBitmap(scaledBitmap, left, top, null)

        // Recycle the scaled bitmap if it's different from the original
        if (scaledBitmap != bitmap) {
            scaledBitmap.recycle()
        }

        return inputBitmap
    }

    /**
     * Process YOLOv8 output and apply post-processing
     *
     * @param output The raw model output array of shape [16, 8400]
     * @param imageWidth Width of the input image
     * @param imageHeight Height of the input image
     * @return List of processed detection results
     *
     * YOLOv8 output format:
     * - output[0][i] to output[3][i]: Box coordinates (x, y, w, h) normalized [0,1]
     * - output[4][i] to output[15][i]: Class probabilities for 12 classes
     * - i ranges from 0 to 8399, representing 8400 potential detections
     *
     * Processing steps:
     * 1. Iterate through all potential detections
     * 2. Find maximum class score for each detection
     * 3. Filter by confidence threshold
     * 4. Convert normalized coordinates to pixel coordinates
     * 5. Apply NMS to remove redundant detections
     */
    private fun processYoloOutput(output: Array<FloatArray>, imageWidth: Int, imageHeight: Int): List<DetectionResult> {
        // YOLOv8 output is [16, 8400] where:
        // - First 4 values are box coordinates (x, y, w, h)
        // - Next 12 values are class probabilities for 12 classes

        val detections = ArrayList<DetectionResult>()

        // Number of detections from YOLO grid
        val numDetections = output[0].size // 8400

        // Process each detection
        for (i in 0 until numDetections) {
            // Get box coordinates (x, y, w, h) - first 4 values
            val x = output[0][i]
            val y = output[1][i]
            val w = output[2][i]
            val h = output[3][i]

            // Find the class with highest confidence
            var maxClassScore = 0f
            var detectedClass = -1

            for (classIdx in 0 until numClasses) {
                val classScore = output[4 + classIdx][i]
                if (classScore > maxClassScore) {
                    maxClassScore = classScore
                    detectedClass = classIdx
                }
            }

            // If confidence is above threshold, add to detections
            if (maxClassScore > scoreThreshold && detectedClass >= 0) {
                // Convert to bounding box coordinates
                val left = (x - w / 2) * imageWidth
                val top = (y - h / 2) * imageHeight
                val right = (x + w / 2) * imageWidth
                val bottom = (y + h / 2) * imageHeight

                detections.add(
                    DetectionResult(
                        classId = detectedClass,
                        className = classNames[detectedClass],
                        confidence = maxClassScore,
                        boundingBox = RectF(left, top, right, bottom)
                    )
                )
            }
        }

        // Apply non-maximum suppression to filter overlapping boxes
        return applyNms(detections)
    }

    /**
     * Apply Non-Maximum Suppression to filter overlapping detections
     *
     * @param detections List of initial detections
     * @return Filtered list of detections after NMS
     */
    private fun applyNms(detections: List<DetectionResult>): List<DetectionResult> {
        // Sort by confidence
        val sortedDetections = detections.sortedByDescending { it.confidence }
        val selectedDetections = ArrayList<DetectionResult>()

        // Boolean array to keep track of detections to keep
        val isIncluded = BooleanArray(sortedDetections.size) { true }

        // Apply NMS
        for (i in sortedDetections.indices) {
            if (!isIncluded[i]) continue

            val boxA = sortedDetections[i].boundingBox
            selectedDetections.add(sortedDetections[i])

            for (j in i + 1 until sortedDetections.size) {
                if (!isIncluded[j]) continue

                val boxB = sortedDetections[j].boundingBox

                // Skip if not the same class (for digit detection we might want different behavior)
                if (sortedDetections[i].classId != sortedDetections[j].classId) continue

                // Calculate IoU
                if (calculateIou(boxA, boxB) > iouThreshold) {
                    isIncluded[j] = false
                }
            }
        }

        return selectedDetections
    }

    /**
     * Calculate Intersection over Union for two bounding boxes
     *
     * @param boxA First bounding box
     * @param boxB Second bounding box
     * @return IoU value between 0 and 1
     */
    private fun calculateIou(boxA: RectF, boxB: RectF): Float {
        val intersectionLeft = maxOf(boxA.left, boxB.left)
        val intersectionTop = maxOf(boxA.top, boxB.top)
        val intersectionRight = minOf(boxA.right, boxB.right)
        val intersectionBottom = minOf(boxA.bottom, boxB.bottom)

        if (intersectionLeft >= intersectionRight || intersectionTop >= intersectionBottom) {
            return 0f
        }

        val intersectionArea = (intersectionRight - intersectionLeft) * (intersectionBottom - intersectionTop)
        val boxAArea = boxA.width() * boxA.height()
        val boxBArea = boxB.width() * boxB.height()

        return intersectionArea / (boxAArea + boxBArea - intersectionArea)
    }

    /**
     * Draw detection results on bitmap
     *
     * @param bitmap Input bitmap to draw on
     * @param detections List of detections to visualize
     * @return New bitmap with detection visualizations
     */
    private fun drawDetections(bitmap: Bitmap, detections: List<DetectionResult>): Bitmap {
        // Create a mutable copy of the bitmap
        val resultBitmap = bitmap.copy(Bitmap.Config.ARGB_8888, true)
        val canvas = Canvas(resultBitmap)

        // Paint for bounding boxes
        val boxPaint = Paint().apply {
            color = Color.GREEN
            style = Paint.Style.STROKE
            strokeWidth = 5f
        }

        // Paint for text
        val textPaint = Paint().apply {
            color = Color.GREEN
            textSize = 40f
            style = Paint.Style.FILL
        }

        // Background paint for text
        val textBackgroundPaint = Paint().apply {
            color = Color.parseColor("#80000000") // Semi-transparent black
            style = Paint.Style.FILL
        }

        // Draw each detection
        for (detection in detections) {
            // Draw bounding box
            canvas.drawRect(detection.boundingBox, boxPaint)

            // Draw label
            val label = "${detection.className} (${String.format("%.2f", detection.confidence)})"
            val textWidth = textPaint.measureText(label)
            val textHeight = 50f

            // Draw text background
            canvas.drawRect(
                detection.boundingBox.left,
                detection.boundingBox.top - textHeight,
                detection.boundingBox.left + textWidth,
                detection.boundingBox.top,
                textBackgroundPaint
            )

            // Draw text
            canvas.drawText(
                label,
                detection.boundingBox.left,
                detection.boundingBox.top - 10f,
                textPaint
            )
        }

        return resultBitmap
    }

    /**
     * Extract meter reading from detections
     *
     * @param detections List of detection results
     * @return String representation of the meter reading
     */
    fun extractMeterReading(detections: List<DetectionResult>): String {
        // Filter for only digit classes and decimal point
        val digitDetections = detections.filter {
            it.classId in 0..10  // Class IDs 0-10 (. and 0-9)
        }

        // Sort from left to right
        val sortedDigits = digitDetections.sortedBy { it.boundingBox.left }

        // Combine into a string
        val reading = sortedDigits.joinToString("") { it.className }

        // Log the extracted reading
        Log.d(tag, "Extracted meter reading: $reading from ${digitDetections.size} digits")

        return reading
    }

    /**
     * Load model file from assets
     *
     * @param fileName Name of the model file in assets directory
     * @return MappedByteBuffer containing the TensorFlow Lite model
     *
     * Expected model compatibility:
     * - TensorFlow Lite format
     * - YOLOv8 architecture
     * - Input shape: [1, 3, 640, 640]
     * - Output shape: [1, 16, 8400]
     * - 12 classes as defined in classNames array
     */
    private fun loadModelFile(fileName: String): MappedByteBuffer {
        val assetManager = context.assets
        val fileDescriptor = assetManager.openFd(fileName)
        val inputStream = FileInputStream(fileDescriptor.fileDescriptor)
        val fileChannel = inputStream.channel
        val startOffset = fileDescriptor.startOffset
        val declaredLength = fileDescriptor.declaredLength

        Log.d(tag, "Loading model file: $fileName, size: ${declaredLength / 1024}KB")

        return fileChannel.map(FileChannel.MapMode.READ_ONLY, startOffset, declaredLength).also {
            inputStream.close()
            fileChannel.close()
        }
    }

    /**
     * Backward compatibility method for loading the default model
     *
     * @return MappedByteBuffer containing the TensorFlow Lite model
     */
    private fun loadModelFile(): MappedByteBuffer {
        return loadModelFile(currentModel?.fileName ?: "weights.tflite")
    }

    /**
     * Data class to hold detection results
     *
     * @param classId ID of the detected class
     * @param className Name of the detected class
     * @param confidence Detection confidence score (0-1)
     * @param boundingBox Bounding box coordinates
     */
    data class DetectionResult(
        val classId: Int,
        val className: String,
        val confidence: Float,
        val boundingBox: RectF
    )

    /**
     * Close the interpreter and release resources when done
     */
    fun close() {
        try {
            interpreter?.close()
            interpreter = null
            Log.d(tag, "MeterDetector resources released")
        } catch (e: Exception) {
            Log.e(tag, "Error closing interpreter: ${e.message}", e)
        }
    }
}
//package com.example.cameraxapp
//
//import android.content.Context
//import android.graphics.Bitmap
//import android.graphics.Canvas
//import android.graphics.Color
//import android.graphics.Paint
//import android.graphics.RectF
//import android.util.Log
//import org.tensorflow.lite.Interpreter
//import org.tensorflow.lite.support.common.FileUtil
//import java.io.File
//import java.io.FileInputStream
//import java.nio.ByteBuffer
//import java.nio.ByteOrder
//import java.nio.MappedByteBuffer
//import java.nio.channels.FileChannel
//
///**
// * Class to handle YOLOv8 model processing for meter reading detection
// */
//class MeterDetector(private val context: Context) {
//    private val tag = "MeterDetector"
//
//    // TFLite interpreter
//    private var interpreter: Interpreter? = null
//
//    // Model parameters
//    private val inputSize = 640
//    private val numClasses = 12
//    private val scoreThreshold = 0.5f
//    private val iouThreshold = 0.45f
//    private val classNames = arrayOf(".", "0", "1", "2", "3", "4", "5", "6", "7", "8", "9", "kwh")
//
//    init {
//        // Load model
//        try {
//            interpreter = Interpreter(loadModelFile())
//            Log.d(tag, "YOLOv8 model loaded successfully")
//        } catch (e: Exception) {
//            Log.e(tag, "Error loading YOLOv8 model: ${e.message}", e)
//        }
//    }
//
//    /**
//     * Process image and detect meter readings
//     *
//     * @param bitmap The input image bitmap
//     * @return List of detection results and processed bitmap with detections drawn
//     */
//    fun detectMeterReading(bitmap: Bitmap): Pair<List<DetectionResult>, Bitmap> {
//        // Ensure model is loaded
//        val interpreter = interpreter ?: return Pair(emptyList(), bitmap)
//
//        // Prepare input: resize to 640x640 and normalize
//        val inputBitmap = prepareInputBitmap(bitmap)
//
//        // Convert bitmap to input tensor
//        val inputBuffer = ByteBuffer.allocateDirect(inputSize * inputSize * 3 * 4) // 4 bytes per float
//        inputBuffer.order(ByteOrder.nativeOrder())
//
//        // Fill input buffer with normalized pixel values
//        for (y in 0 until inputSize) {
//            for (x in 0 until inputSize) {
//                val pixel = inputBitmap.getPixel(x, y)
//                // YOLOv8 expects RGB normalized to [0,1]
//                inputBuffer.putFloat(Color.red(pixel) / 255.0f)
//                inputBuffer.putFloat(Color.green(pixel) / 255.0f)
//                inputBuffer.putFloat(Color.blue(pixel) / 255.0f)
//            }
//        }
//        inputBuffer.rewind()
//
//        // Prepare output buffer based on YOLOv8 output shape [1, 16, 8400]
//        val outputBuffer = Array(1) { Array(16) { FloatArray(8400) } }
//
//        // Run inference
//        interpreter.run(inputBuffer, outputBuffer)
//
//        // Process YOLOv8 output and get detection results
//        val detections = processYoloOutput(outputBuffer[0], inputSize, inputSize)
//
//        // Draw detections on a copy of the input image
//       // val resultBitmap = drawDetections(inputBitmap, detections)
//        var resultBitmap = inputBitmap
//
//        return Pair(detections, resultBitmap)
//    }
//
//    /**
//     * Prepare input bitmap - resize to 640x640 with gray padding if needed
//     */
//    private fun prepareInputBitmap(bitmap: Bitmap): Bitmap {
//        // Create a 640x640 bitmap for the model input
//        val inputBitmap = Bitmap.createBitmap(inputSize, inputSize, Bitmap.Config.ARGB_8888)
//        val canvas = Canvas(inputBitmap)
//
//        // Fill with gray background
//        canvas.drawColor(Color.LTGRAY)
//
//        // Calculate scaling to fit the original bitmap
//        val scale = if (bitmap.width > bitmap.height) {
//            inputSize.toFloat() / bitmap.width
//        } else {
//            inputSize.toFloat() / bitmap.height
//        }
//
//        val scaledWidth = (bitmap.width * scale).toInt()
//        val scaledHeight = (bitmap.height * scale).toInt()
//
//        // Scale the original bitmap
//        val scaledBitmap = Bitmap.createScaledBitmap(bitmap, scaledWidth, scaledHeight, true)
//
//        // Center the scaled bitmap on the input bitmap
//        val left = (inputSize - scaledWidth) / 2f
//        val top = (inputSize - scaledHeight) / 2f
//
//        canvas.drawBitmap(scaledBitmap, left, top, null)
//
//        return inputBitmap
//    }
//
//    /**
//     * Process YOLOv8 output and apply post-processing
//     */
//    private fun processYoloOutput(output: Array<FloatArray>, imageWidth: Int, imageHeight: Int): List<DetectionResult> {
//        // YOLOv8 output is [16, 8400] where:
//        // - First 4 values are box coordinates (x, y, w, h)
//        // - Next 12 values are class probabilities for 12 classes
//
//        val detections = ArrayList<DetectionResult>()
//
//        // Number of detections from YOLO grid
//        val numDetections = output[0].size // 8400
//
//        // Process each detection
//        for (i in 0 until numDetections) {
//            // Get box coordinates (x, y, w, h) - first 4 values
//            val x = output[0][i]
//            val y = output[1][i]
//            val w = output[2][i]
//            val h = output[3][i]
//
//            // Find the class with highest confidence
//            var maxClassScore = 0f
//            var detectedClass = -1
//
//            for (classIdx in 0 until numClasses) {
//                val classScore = output[4 + classIdx][i]
//                if (classScore > maxClassScore) {
//                    maxClassScore = classScore
//                    detectedClass = classIdx
//                }
//            }
//
//            // If confidence is above threshold, add to detections
//            if (maxClassScore > scoreThreshold && detectedClass >= 0) {
//                // Convert to bounding box coordinates
//                val left = (x - w / 2) * imageWidth
//                val top = (y - h / 2) * imageHeight
//                val right = (x + w / 2) * imageWidth
//                val bottom = (y + h / 2) * imageHeight
//
//                detections.add(
//                    DetectionResult(
//                        classId = detectedClass,
//                        className = classNames[detectedClass],
//                        confidence = maxClassScore,
//                        boundingBox = RectF(left, top, right, bottom)
//                    )
//                )
//            }
//        }
//
//        // Apply non-maximum suppression to filter overlapping boxes
//        return applyNms(detections)
//    }
//
//    /**
//     * Apply Non-Maximum Suppression to filter overlapping detections
//     */
//    private fun applyNms(detections: List<DetectionResult>): List<DetectionResult> {
//        // Sort by confidence
//        val sortedDetections = detections.sortedByDescending { it.confidence }
//        val selectedDetections = ArrayList<DetectionResult>()
//
//        // Boolean array to keep track of detections to keep
//        val isIncluded = BooleanArray(sortedDetections.size) { true }
//
//        // Apply NMS
//        for (i in sortedDetections.indices) {
//            if (!isIncluded[i]) continue
//
//            val boxA = sortedDetections[i].boundingBox
//            selectedDetections.add(sortedDetections[i])
//
//            for (j in i + 1 until sortedDetections.size) {
//                if (!isIncluded[j]) continue
//
//                val boxB = sortedDetections[j].boundingBox
//
//                // Skip if not the same class (for digit detection we might want different behavior)
//                if (sortedDetections[i].classId != sortedDetections[j].classId) continue
//
//                // Calculate IoU
//                if (calculateIou(boxA, boxB) > iouThreshold) {
//                    isIncluded[j] = false
//                }
//            }
//        }
//
//        return selectedDetections
//    }
//
//    /**
//     * Calculate Intersection over Union for two bounding boxes
//     */
//    private fun calculateIou(boxA: RectF, boxB: RectF): Float {
//        val intersectionLeft = maxOf(boxA.left, boxB.left)
//        val intersectionTop = maxOf(boxA.top, boxB.top)
//        val intersectionRight = minOf(boxA.right, boxB.right)
//        val intersectionBottom = minOf(boxA.bottom, boxB.bottom)
//
//        if (intersectionLeft >= intersectionRight || intersectionTop >= intersectionBottom) {
//            return 0f
//        }
//
//        val intersectionArea = (intersectionRight - intersectionLeft) * (intersectionBottom - intersectionTop)
//        val boxAArea = boxA.width() * boxA.height()
//        val boxBArea = boxB.width() * boxB.height()
//
//        return intersectionArea / (boxAArea + boxBArea - intersectionArea)
//    }
//
//    /**
//     * Draw detection results on bitmap
//     */
//    private fun drawDetections(bitmap: Bitmap, detections: List<DetectionResult>): Bitmap {
//        // Create a mutable copy of the bitmap
//        val resultBitmap = bitmap.copy(Bitmap.Config.ARGB_8888, true)
//        val canvas = Canvas(resultBitmap)
//
//        // Paint for bounding boxes
//        val boxPaint = Paint().apply {
//            color = Color.GREEN
//            style = Paint.Style.STROKE
//            strokeWidth = 5f
//        }
//
//        // Paint for text
//        val textPaint = Paint().apply {
//            color = Color.GREEN
//            textSize = 40f
//            style = Paint.Style.FILL
//        }
//
//        // Background paint for text
//        val textBackgroundPaint = Paint().apply {
//            color = Color.parseColor("#80000000") // Semi-transparent black
//            style = Paint.Style.FILL
//        }
//
//        // Draw each detection
//        for (detection in detections) {
//            // Draw bounding box
//            canvas.drawRect(detection.boundingBox, boxPaint)
//
//            // Draw label
//            val label = "${detection.className} (${String.format("%.2f", detection.confidence)})"
//            val textWidth = textPaint.measureText(label)
//            val textHeight = 50f
//
//            // Draw text background
//            canvas.drawRect(
//                detection.boundingBox.left,
//                detection.boundingBox.top - textHeight,
//                detection.boundingBox.left + textWidth,
//                detection.boundingBox.top,
//                textBackgroundPaint
//            )
//
//            // Draw text
//            canvas.drawText(
//                label,
//                detection.boundingBox.left,
//                detection.boundingBox.top - 10f,
//                textPaint
//            )
//        }
//
//        return resultBitmap
//    }
//
//    /**
//     * Extract meter reading from detections
//     * This sorts detected digits from left to right and combines them
//     */
//    fun extractMeterReading(detections: List<DetectionResult>): String {
//        // Filter for only digit classes and decimal point
//        val digitDetections = detections.filter {
//            it.classId in 0..10  // Class IDs 0-10 (. and 0-9)
//        }
//
//        // Sort from left to right
//        val sortedDigits = digitDetections.sortedBy { it.boundingBox.left }
//
//        // Combine into a string
//        return sortedDigits.joinToString("") { it.className }
//    }
//
//    /**
//     * Load model file from assets
//     */
//    private fun loadModelFile(): MappedByteBuffer {
//        val assetManager = context.assets
//        val modelPath = "weights.tflite" // Make sure this matches your file name
//
//        val fileDescriptor = assetManager.openFd(modelPath)
//        val inputStream = FileInputStream(fileDescriptor.fileDescriptor)
//        val fileChannel = inputStream.channel
//        val startOffset = fileDescriptor.startOffset
//        val declaredLength = fileDescriptor.declaredLength
//        return fileChannel.map(FileChannel.MapMode.READ_ONLY, startOffset, declaredLength)
//    }
//
//    /**
//     * Data class to hold detection results
//     */
//    data class DetectionResult(
//        val classId: Int,
//        val className: String,
//        val confidence: Float,
//        val boundingBox: RectF
//    )
//
//    /**
//     * Close the interpreter when done
//     */
//    fun close() {
//        interpreter?.close()
//        interpreter = null
//    }
//}