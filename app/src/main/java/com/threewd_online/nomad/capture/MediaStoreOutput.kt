package com.threewd_online.nomad.capture

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import java.io.File

/**
 * Saves captures into the shared gallery under DCIM/NOMAD_ND1 via MediaStore.
 * On Android 10+ this needs no storage permission (scoped storage); on older
 * versions it writes through the legacy DATA path (WRITE_EXTERNAL_STORAGE).
 */
object MediaStoreOutput {
    private const val TAG = "MediaStoreOutput"
    private const val ALBUM = "NOMAD_ND1"

    /** Save a JPEG still. Returns the display name on success, else null. */
    fun saveImage(context: Context, bitmap: Bitmap, name: String): String? {
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, name)
            put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
            applyLocation(this, MediaStore.Images.Media.RELATIVE_PATH, MediaStore.Images.Media.IS_PENDING)
        }
        val resolver = context.contentResolver
        val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values) ?: return null
        return try {
            resolver.openOutputStream(uri)!!.use { out ->
                bitmap.compress(Bitmap.CompressFormat.JPEG, 92, out)
            }
            finishPending(context, uri, values)
            name
        } catch (e: Exception) {
            Log.e(TAG, "saveImage failed", e)
            runCatching { resolver.delete(uri, null, null) }
            null
        }
    }

    /** Copy a finished temp MP4 into the gallery. Returns display name or null. */
    fun saveVideoFile(context: Context, temp: File, name: String): String? {
        val values = ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, name)
            put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
            applyLocation(this, MediaStore.Video.Media.RELATIVE_PATH, MediaStore.Video.Media.IS_PENDING)
        }
        val resolver = context.contentResolver
        val uri = resolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values) ?: return null
        return try {
            resolver.openOutputStream(uri)!!.use { out -> temp.inputStream().use { it.copyTo(out) } }
            finishPending(context, uri, values)
            name
        } catch (e: Exception) {
            Log.e(TAG, "saveVideoFile failed", e)
            runCatching { resolver.delete(uri, null, null) }
            null
        }
    }

    fun timestamp(millis: Long): String {
        // Caller passes System.currentTimeMillis(); format without java.util.Date locale surprises.
        val cal = java.util.Calendar.getInstance().apply { timeInMillis = millis }
        return "%04d%02d%02d_%02d%02d%02d".format(
            cal.get(java.util.Calendar.YEAR),
            cal.get(java.util.Calendar.MONTH) + 1,
            cal.get(java.util.Calendar.DAY_OF_MONTH),
            cal.get(java.util.Calendar.HOUR_OF_DAY),
            cal.get(java.util.Calendar.MINUTE),
            cal.get(java.util.Calendar.SECOND),
        )
    }

    private fun ContentValues.applyLocation(values: ContentValues, relPathKey: String, pendingKey: String) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            values.put(relPathKey, "${Environment.DIRECTORY_DCIM}/$ALBUM")
            values.put(pendingKey, 1)
        } else {
            @Suppress("DEPRECATION")
            val dir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DCIM), ALBUM)
            if (!dir.exists()) dir.mkdirs()
            values.put(MediaStore.MediaColumns.DATA, File(dir, values.getAsString(MediaStore.MediaColumns.DISPLAY_NAME)).absolutePath)
        }
    }

    private fun finishPending(context: Context, uri: android.net.Uri, values: ContentValues) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            values.clear()
            values.put(MediaStore.MediaColumns.IS_PENDING, 0)
            context.contentResolver.update(uri, values, null, null)
        }
    }
}
