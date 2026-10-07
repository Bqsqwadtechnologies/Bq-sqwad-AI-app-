package com.bqsqwad.ai.core

import android.content.ContentResolver
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

object BusinessTrainingUploader {
    suspend fun upload(
        resolver: ContentResolver,
        uri: Uri,
        type: String,
        title: String,
        purpose: String
    ): JSONObject = withContext(Dispatchers.IO) {
        val size = resolver.openAssetFileDescriptor(uri, "r")?.use { it.length }
            .takeIf { it != null && it >= 0 }
            ?: error("Unable to determine file size")
        val fileName = resolver.query(
            uri,
            arrayOf(android.provider.OpenableColumns.DISPLAY_NAME),
            null,
            null,
            null
        )?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        } ?: "training-asset"
        val contentType = resolver.getType(uri) ?: "application/octet-stream"
        val api = WorkspaceApiClient()
        val created = api.createBusinessTrainingUpload(
            type = type,
            title = title,
            purpose = purpose,
            fileName = fileName,
            contentType = contentType,
            size = size
        )
        val uploadUrl = created.optString("uploadUrl").ifBlank {
            error("Upload URL unavailable")
        }
        val authorizationToken = created.optString("authorizationToken").ifBlank {
            error("Upload authorization unavailable")
        }
        val objectPath = created.optString("objectPath").ifBlank {
            error("Upload object path unavailable")
        }

        val connection = (URL(uploadUrl).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 15000
            readTimeout = 120000
            doInput = true
            doOutput = true
            setRequestProperty("Authorization", authorizationToken)
            setRequestProperty(
                "X-Bz-File-Name",
                URLEncoder.encode(objectPath, "UTF-8").replace("+", "%20")
            )
            setRequestProperty("Content-Type", contentType)
            setRequestProperty("Content-Length", size.toString())
            setRequestProperty("X-Bz-Content-Sha1", "do_not_verify")
        }

        resolver.openInputStream(uri)?.use { input ->
            connection.outputStream.use { output ->
                input.copyTo(output, 1024 * 1024)
            }
        } ?: error("Unable to open selected file")

        val response = connection.responseCode
        val responseBody = (if (response in 200..299) connection.inputStream else connection.errorStream)
            ?.bufferedReader()
            ?.use { it.readText() }
            .orEmpty()
        if (response !in 200..299) {
            val message = runCatching { JSONObject(responseBody).optString("message") }
                .getOrDefault("")
                .ifBlank { responseBody.take(500) }
                .ifBlank { "Training upload failed" }
            error("Training upload failed with HTTP $response: $message")
        }

        api.finalizeBusinessTrainingUpload(created.optString("assetId"))
    }
}
