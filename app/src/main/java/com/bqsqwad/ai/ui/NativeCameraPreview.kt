package com.bqsqwad.ai.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.bqsqwad.ai.core.NativeCameraPreview as CoreNativeCameraPreview

/**
 * UI-facing adapter for AI Live Camera.
 * The actual camera lifecycle/frame pipeline lives in core.NativeCameraPreview.
 */
@Composable
fun NativeCameraPreview(
    modifier: Modifier = Modifier,
    lensFacing: Int = android.hardware.camera2.CameraCharacteristics.LENS_FACING_BACK,
    captureEveryMs: Long = 1800L,
    onFrame: (ByteArray) -> Unit = {},
    onError: (String) -> Unit = {}
) {
    CoreNativeCameraPreview(
        modifier = modifier,
        lensFacing = lensFacing,
        captureEveryMs = captureEveryMs,
        onFrame = onFrame,
        onError = onError
    )
}
