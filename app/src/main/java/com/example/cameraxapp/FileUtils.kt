// FileUtils.kt
package com.example.cameraxapp

import android.content.ContentResolver
import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.OutputStream

/**
 * Utility class for file operations
 * Handles storage operations with compatibility across Android versions
 */
object FileUtils {
    private const val TAG = "FileUtils"

    /**
     * Creates a new file for saving image data
     *
     * @param context Application context
     * @param fileName Name of the file to create
     * @param folderName Name of the folder to create file in
     * @param mimeType MIME type of the file
     * @return Pair of URI and OutputStream for the file
     */
    fun createImageFile(
        context: Context,
        fileName: String,
        folderName: String,
        mimeType: String = "image/jpeg"
    ): Pair<Uri?, OutputStream?> {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            createFileMediaStore(context, fileName, folderName, mimeType)
        } else {
            createFileLegacy(context, fileName, folderName, mimeType)
        }
    }

    /**
     * Creates a file for JSON metadata
     *
     * @param context Application context
     * @param fileName Name of the file to create
     * @param folderName Name of the folder to create file in
     * @return Pair of URI and OutputStream for the file
     */
    fun createJsonFile(
        context: Context,
        fileName: String,
        folderName: String
    ): Pair<Uri?, OutputStream?> {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            createFileMediaStore(
                context,
                fileName,
                folderName,
                "application/json",
                Environment.DIRECTORY_DOCUMENTS
            )
        } else {
            createFileLegacy(
                context,
                fileName,
                folderName,
                "application/json",
                Environment.DIRECTORY_DOCUMENTS
            )
        }
    }

    /**
     * Creates a file using MediaStore API (Android 10+)
     */
    private fun createFileMediaStore(
        context: Context,
        fileName: String,
        folderName: String,
        mimeType: String,
        directory: String = Environment.DIRECTORY_PICTURES
    ): Pair<Uri?, OutputStream?> {
        val contentValues = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
            put(MediaStore.MediaColumns.MIME_TYPE, mimeType)
            put(MediaStore.MediaColumns.RELATIVE_PATH, "$directory/$folderName")
        }

        val contentUri = when {
            mimeType.startsWith("image") -> MediaStore.Images.Media.EXTERNAL_CONTENT_URI
            else -> MediaStore.Files.getContentUri("external")
        }

        val contentResolver: ContentResolver = context.contentResolver

        return try {
            val uri = contentResolver.insert(contentUri, contentValues)
            val outputStream = uri?.let { contentResolver.openOutputStream(it) }
            uri to outputStream
        } catch (e: IOException) {
            Log.e(TAG, "Failed to create file in MediaStore: ${e.message}")
            null to null
        }
    }

    /**
     * Creates a file using legacy file system methods (Android 9 and below)
     */
    private fun createFileLegacy(
        context: Context,
        fileName: String,
        folderName: String,
        mimeType: String,
        directory: String = Environment.DIRECTORY_PICTURES
    ): Pair<Uri?, OutputStream?> {
        val storageDir = File(
            Environment.getExternalStoragePublicDirectory(directory),
            folderName
        )

        if (!storageDir.exists()) {
            if (!storageDir.mkdirs()) {
                Log.e(TAG, "Failed to create directory: $storageDir")
                return null to null
            }
        }

        return try {
            val file = File(storageDir, fileName)
            val outputStream = FileOutputStream(file)
            Uri.fromFile(file) to outputStream
        } catch (e: IOException) {
            Log.e(TAG, "Failed to create file: ${e.message}")
            null to null
        }
    }
}