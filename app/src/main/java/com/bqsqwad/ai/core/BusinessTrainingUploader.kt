package com.bqsqwad.ai.core

import android.content.ContentResolver
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL

object BusinessTrainingUploader {
    suspend fun upload(
        resolver: ContentResolver,
        uri: Uri,
        type: String,
        title: String,
        purpose: String
    ): org.json.JSONObject = withContext(Dispatchers.IO) {
        val size = resolver.openAssetFileDescriptor(uri, "r")?.use { it.length }.takeIf { it != null && it >= 0 } ?: error("Unable to determine file size")
        val fileName = resolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        } ?: "training-asset"
        val contentType = resolver.getType(uri) ?: "application/octet-stream"
        val api = WorkspaceApiClient()
        val created = api.createBusinessTrainingUpload(type, title, purpose, fileName, contentType, size)
        val uploadUrl = created.optString("uploadUrl").ifBlank { error("Upload URL unavailable") }
        val connection = (URL(uploadUrl).openConnection() as HttpURLConnection).apply {
            requestMethod = "PUT"
            connectTimeout = 15000
            readTimeout = 120000
            doOutput = true
            setRequestProperty("Content-Type", contentType)
            setRequestProperty("Content-Length", size.toString())
        }
        resolver.openInputStream(uri)?.use { input ->
            connection.outputStream.use { output -> input.copyTo(output, 1024 * 1024) }
        } ?: error("Unable to open selected file")
        val response = connection.responseCode
        if (response !in 200..299) error("Training upload failed with HTTP $response")
        api.finalizeBusinessTrainingUpload(created.optString("assetId"))
    }
}
