package com.example.cameraxapp
import android.content.Intent

class IntentDataHandler(private val intent: Intent) {

    // Extract data from intent with default values matching your JSON structure
    fun getServiceId(): String = intent.getStringExtra("service_id") ?: "123456"

    fun getValType(): String = intent.getStringExtra("valType") ?: "kwh"

    fun getValue(): String = intent.getStringExtra("value") ?: "1234.5"

    fun isEdit(): Boolean = intent.getBooleanExtra("isedit", false)

    fun getImagePath(): String = intent.getStringExtra("image_path") ?: "/images/integrity.png"

    // Convenience method to get all values as a map
    fun getAllValues(): Map<String, Any> {
        return mapOf(
            "service_type" to getServiceId(),
            "valType" to getValType(),
            "value" to getValue(),
            "isedit" to isEdit(),
            "image_path" to getImagePath()
        )
    }
}