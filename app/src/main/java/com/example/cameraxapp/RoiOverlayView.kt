package com.example.cameraxapp

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View

/**
 * ROIOverlay draws a square region of interest on top of the camera preview
 *
 * This custom view draws a square rectangle indicating the region of interest
 * that will be captured and processed by a TFLite model requiring 640x640 input.
 */
class ROIOverlay @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    // Rectangle representing the ROI area
    private val roiRect = RectF()

    // Paint for drawing ROI rectangle
    private val roiPaint = Paint().apply {
        color = Color.WHITE
        style = Paint.Style.STROKE
        strokeWidth = 4f
    }

    // Paint for dimming area outside ROI
    private val dimPaint = Paint().apply {
        color = Color.parseColor("#80000000") // Semi-transparent black
        style = Paint.Style.FILL
    }

    // ROI size relative to view width (maintaining square aspect ratio)
    private var roiSizePercent = 0.7f

    // TFLite model target size
    private val tfliteSize = 640 // pixels

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        updateRoiRect()
    }

    /**
     * Updates the ROI rectangle to be a square centered in the view
     */
    private fun updateRoiRect() {
        val viewWidth = width.toFloat()
        val viewHeight = height.toFloat()

        // Calculate ROI size as a square (based on the smaller dimension)
        val smallerDimension = minOf(viewWidth, viewHeight)
        val roiSize = smallerDimension * roiSizePercent

        // Calculate left, top, right, bottom to center the ROI
        val left = (viewWidth - roiSize) / 2
        val top = (viewHeight - roiSize) / 2
        val right = left + roiSize
        val bottom = top + roiSize

        // Set ROI rectangle
        roiRect.set(left, top, right, bottom)

        // Request redraw
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        // Draw semi-transparent overlay outside ROI
        // Top rectangle
        canvas.drawRect(0f, 0f, width.toFloat(), roiRect.top, dimPaint)
        // Left rectangle
        canvas.drawRect(0f, roiRect.top, roiRect.left, roiRect.bottom, dimPaint)
        // Right rectangle
        canvas.drawRect(roiRect.right, roiRect.top, width.toFloat(), roiRect.bottom, dimPaint)
        // Bottom rectangle
        canvas.drawRect(0f, roiRect.bottom, width.toFloat(), height.toFloat(), dimPaint)

        // Draw ROI rectangle border
        canvas.drawRect(roiRect, roiPaint)
    }

    /**
     * Get the current ROI rectangle
     *
     * @return RectF representing the ROI in view coordinates
     */
    fun getRoiRect(): RectF {
        return RectF(roiRect)
    }
}