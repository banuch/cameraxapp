package com.example.cameraxapp

import android.app.Dialog
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.SeekBar
import android.widget.TextView
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.bottomsheet.BottomSheetDialogFragment

class SettingsBottomSheetDialog : BottomSheetDialogFragment() {

    companion object {
        private const val ARG_ZOOM = "zoom"
        private const val ARG_THRESHOLD = "threshold"
        private const val ARG_EXPOSURE = "exposure"

        fun newInstance(zoom: Int = 0, threshold: Int = 50, exposure: Int = 50): SettingsBottomSheetDialog {
            return SettingsBottomSheetDialog().apply {
                arguments = Bundle().apply {
                    putInt(ARG_ZOOM, zoom)
                    putInt(ARG_THRESHOLD, threshold)
                    putInt(ARG_EXPOSURE, exposure)
                }
            }
        }
    }

    // Interface for communicating with the parent activity
    interface SettingsListener {
        fun onZoomChanged(zoomValue: Int)
        fun onThresholdChanged(thresholdValue: Int)
        fun onExposureChanged(exposureValue: Int)
        fun onResetSettings()
    }

    private var settingsListener: SettingsListener? = null

    // UI Components
    private lateinit var zoomSeekBar: SeekBar
    private lateinit var thresholdSeekBar: SeekBar
    private lateinit var exposureSeekBar: SeekBar
    private lateinit var zoomValueText: TextView
    private lateinit var thresholdValueText: TextView
    private lateinit var exposureValueText: TextView


    // Current values
    private var currentZoom = 0
    private var currentThreshold = 50
    private var currentExposure = 50

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        arguments?.let {
            currentZoom = it.getInt(ARG_ZOOM, 0)
            currentThreshold = it.getInt(ARG_THRESHOLD, 50)
            currentExposure = it.getInt(ARG_EXPOSURE, 50)
        }
    }

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        return super.onCreateDialog(savedInstanceState) as BottomSheetDialog
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? {
        return inflater.inflate(R.layout.bottom_sheet_controls, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        initViews(view)
        setupSeekBars()
//        setupButtons()
        updateCurrentValues()
    }

    private fun initViews(view: View) {
        zoomSeekBar = view.findViewById(R.id.zoomSeekBar)
        thresholdSeekBar = view.findViewById(R.id.thresholdSeekBar)
        exposureSeekBar = view.findViewById(R.id.exposureSeekBar)
//
//        zoomValueText = view.findViewById(R.id.zoomValueText)
//        thresholdValueText = view.findViewById(R.id.thresholdValueText)
//        exposureValueText = view.findViewById(R.id.exposureValueText)

//        resetButton = view.findViewById(R.id.resetButton)
//        closeButton = view.findViewById(R.id.closeButton)
    }

    private fun setupSeekBars() {
        // Zoom SeekBar
        zoomSeekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) {
                    currentZoom = progress
//                    zoomValueText.text = "$progress%"
                    settingsListener?.onZoomChanged(progress)
                }
            }

            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })

        // Threshold SeekBar
        thresholdSeekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) {
                    currentThreshold = progress
//                    thresholdValueText.text = "$progress%"
                    settingsListener?.onThresholdChanged(progress)
                }
            }

            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })

        // Exposure SeekBar
        exposureSeekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) {
                    currentExposure = progress
                    // Convert to -50 to +50 range for display
                    val exposureValue = progress - 50
//                    exposureValueText.text = exposureValue.toString()
                    settingsListener?.onExposureChanged(exposureValue)
                }
            }

            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })
    }
//
//    private fun setupButtons() {
//        resetButton.setOnClickListener {
//            resetToDefaults()
//            settingsListener?.onResetSettings()
//        }
//
//        closeButton.setOnClickListener {
//            dismiss()
//        }
//    }

    private fun updateCurrentValues() {
        zoomSeekBar.progress = currentZoom
        thresholdSeekBar.progress = currentThreshold
        exposureSeekBar.progress = currentExposure

//        zoomValueText.text = "$currentZoom%"
//        thresholdValueText.text = "$currentThreshold%"
//        exposureValueText.text = "${currentExposure - 50}"
    }

    private fun resetToDefaults() {
        currentZoom = 0
        currentThreshold = 50
        currentExposure = 50
        updateCurrentValues()
    }

    fun setSettingsListener(listener: SettingsListener) {
        this.settingsListener = listener
    }

}