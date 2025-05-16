package com.example.cameraxapp

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View

/**
 * Enhanced ROI Overlay view that supports both fixed and user-selectable regions of interest
 * Combines features from both implementations to create a versatile overlay
 */
class RoiOverlayView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    // Mode configuration
    enum class Mode {
        FIXED,     // Fixed ROI at predefined position
        SELECTION, // User is selecting an ROI
        SELECTED   // User has selected an ROI
    }

    var mode: Mode = Mode.FIXED
        set(value) {
            field = value
            invalidate()
        }

    // ROI parameters as percentages of view dimensions (for fixed mode)
    var roiX = 0.10f       // X position from left (10% of width)
    var roiY = 0.25f       // Y position from top (25% of height)
    var roiWidth = 0.80f   // Width (80% of view width)
    var roiHeight = 0.20f  // Height (20% of view height)

    // User-selected ROI coordinates
    private var selectionStartX = 0f
    private var selectionStartY = 0f
    private var selectionEndX = 0f
    private var selectionEndY = 0f

    // Currently active ROI rectangle
    private var roiRect = RectF()
    private var userSelectedRoi: RectF? = null

    // Paint objects for drawing
    private val overlayPaint = Paint().apply {
        color = Color.WHITE  // White for areas outside ROI
        alpha = 255        // Semi-transparent
        style = Paint.Style.FILL
    }

    private val borderPaint = Paint().apply {
        color = Color.BLUE
        style = Paint.Style.STROKE
        strokeWidth = 3f
    }

    private val cornerPaint = Paint().apply {
        color = Color.BLUE
        style = Paint.Style.STROKE
        strokeWidth = 5f
    }

    private val selectionPaint = Paint().apply {
        color = Color.GREEN
        style = Paint.Style.STROKE
        strokeWidth = 4f
    }

    // Corner marker size
    private val cornerSize = 40f

    // Flag to show/hide the overlay
    var showOverlay = true
        set(value) {
            field = value
            invalidate()
        }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        if (!showOverlay) return

        when (mode) {
            Mode.FIXED -> drawFixedRoi(canvas)
            Mode.SELECTION -> drawSelectionRoi(canvas)
            Mode.SELECTED -> drawUserSelectedRoi(canvas)
        }
    }

    /**
     * Draw the fixed ROI with the predefined parameters
     */
    private fun drawFixedRoi(canvas: Canvas) {
        // Calculate ROI rectangle
        roiRect.set(
            width * roiX,
            height * roiY,
            width * roiX + width * roiWidth,
            height * roiY + height * roiHeight
        )

        drawRoiWithOverlay(canvas, roiRect, borderPaint)
    }

    /**
     * Draw the ROI that's currently being selected by the user
     */
    private fun drawSelectionRoi(canvas: Canvas) {
        if (selectionStartX == 0f && selectionStartY == 0f) return

        // Create a rectangle from the current selection points
        val currentSelectionRect = RectF(
            minOf(selectionStartX, selectionEndX),
            minOf(selectionStartY, selectionEndY),
            maxOf(selectionStartX, selectionEndX),
            maxOf(selectionStartY, selectionEndY)
        )

        // Just draw the selection rectangle without the overlay
        canvas.drawRect(currentSelectionRect, selectionPaint)
    }

    /**
     * Draw the user's selected ROI with overlay and corner markers
     */
    private fun drawUserSelectedRoi(canvas: Canvas) {
        userSelectedRoi?.let { roi ->
            drawRoiWithOverlay(canvas, roi, selectionPaint)
        }
    }

    /**
     * Common method to draw an ROI with overlay and corner markers
     */
    private fun drawRoiWithOverlay(canvas: Canvas, rect: RectF, rectPaint: Paint) {
        // Create path for white overlay with a cutout for the ROI
        val path = Path().apply {
            addRect(0f, 0f, width.toFloat(), height.toFloat(), Path.Direction.CW)
            addRect(rect, Path.Direction.CCW)
        }

        // Draw white overlay (outside of ROI)
        canvas.drawPath(path, overlayPaint)

        // Draw ROI rectangle border
        canvas.drawRect(rect, rectPaint)

        // Draw corner markers
        // Top-left corner
        canvas.drawLine(
            rect.left, rect.top,
            rect.left + cornerSize, rect.top,
            cornerPaint
        )
        canvas.drawLine(
            rect.left, rect.top,
            rect.left, rect.top + cornerSize,
            cornerPaint
        )

        // Top-right corner
        canvas.drawLine(
            rect.right, rect.top,
            rect.right - cornerSize, rect.top,
            cornerPaint
        )
        canvas.drawLine(
            rect.right, rect.top,
            rect.right, rect.top + cornerSize,
            cornerPaint
        )

        // Bottom-left corner
        canvas.drawLine(
            rect.left, rect.bottom,
            rect.left + cornerSize, rect.bottom,
            cornerPaint
        )
        canvas.drawLine(
            rect.left, rect.bottom,
            rect.left, rect.bottom - cornerSize,
            cornerPaint
        )

        // Bottom-right corner
        canvas.drawLine(
            rect.right, rect.bottom,
            rect.right - cornerSize, rect.bottom,
            cornerPaint
        )
        canvas.drawLine(
            rect.right, rect.bottom,
            rect.right, rect.bottom - cornerSize,
            cornerPaint
        )
    }

    /**
     * Start the ROI selection process
     */
    fun startSelection(x: Float, y: Float) {
        mode = Mode.SELECTION
        selectionStartX = x
        selectionStartY = y
        selectionEndX = x
        selectionEndY = y
        invalidate()
    }

    /**
     * Update the selection as the user drags
     */
    fun updateSelection(x: Float, y: Float) {
        if (mode == Mode.SELECTION) {
            selectionEndX = x
            selectionEndY = y
            invalidate()
        }
    }

    /**
     * Complete the selection process
     */
    fun completeSelection() {
        if (mode == Mode.SELECTION) {
            // Create the final rect from the selection points
            val width = Math.abs(selectionEndX - selectionStartX)
            val height = Math.abs(selectionEndY - selectionStartY)

            // Only accept selections that are big enough
            if (width > 50 && height > 50) {
                userSelectedRoi = RectF(
                    minOf(selectionStartX, selectionEndX),
                    minOf(selectionStartY, selectionEndY),
                    maxOf(selectionStartX, selectionEndX),
                    maxOf(selectionStartY, selectionEndY)
                )
                mode = Mode.SELECTED
            } else {
                // Selection too small, revert to fixed mode
                mode = Mode.FIXED
            }

            // Reset selection points
            selectionStartX = 0f
            selectionStartY = 0f
            selectionEndX = 0f
            selectionEndY = 0f

            invalidate()
        }
    }

    /**
     * Cancel the current selection
     */
    fun cancelSelection() {
        mode = Mode.FIXED
        selectionStartX = 0f
        selectionStartY = 0f
        selectionEndX = 0f
        selectionEndY = 0f
        invalidate()
    }

    /**
     * Get the current active ROI rectangle
     */
    fun getCurrentRoi(): RectF {
        return when (mode) {
            Mode.FIXED -> RectF(
                width * roiX,
                height * roiY,
                width * roiX + width * roiWidth,
                height * roiY + height * roiHeight
            )
            Mode.SELECTED -> userSelectedRoi ?: RectF(
                width * roiX,
                height * roiY,
                width * roiX + width * roiWidth,
                height * roiY + height * roiHeight
            )
            else -> RectF(
                width * roiX,
                height * roiY,
                width * roiX + width * roiWidth,
                height * roiY + height * roiHeight
            )
        }
    }

    /**
     * Reset to fixed ROI mode
     */
    fun resetToFixedMode() {
        mode = Mode.FIXED
        userSelectedRoi = null
        invalidate()
    }

    /**
     * Clear the overlay (for compatibility)
     */
    fun clear() {
        invalidate()
    }
}