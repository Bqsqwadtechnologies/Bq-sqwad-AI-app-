package com.bqsqwad.ai.core

import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.provider.Settings
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager

class DeviceCommandExecutor(private val context: Context) {

    fun execute(action: DeviceAction): Result {
        if (!DeviceCommandPolicy.isAllowed(action)) {
            return Result.failure(IllegalArgumentException("Unsupported device action"))
        }

        return runCatching {
            when (action.type) {
                "torch_on" -> setTorch(true)
                "torch_off" -> setTorch(false)
                "set_brightness" -> setBrightness(action.value)
                "volume_up" -> adjustVolume(AudioManager.ADJUST_RAISE)
                "volume_down" -> adjustVolume(AudioManager.ADJUST_LOWER)
                "play_media" -> sendMediaKey(android.view.KeyEvent.KEYCODE_MEDIA_PLAY)
                "pause_media" -> sendMediaKey(android.view.KeyEvent.KEYCODE_MEDIA_PAUSE)
                "next_media" -> sendMediaKey(android.view.KeyEvent.KEYCODE_MEDIA_NEXT)
                "previous_media" -> sendMediaKey(android.view.KeyEvent.KEYCODE_MEDIA_PREVIOUS)
                "open_settings" -> context.startActivity(Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                "open_wifi_settings" -> context.startActivity(Intent(Settings.ACTION_WIFI_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                "open_bluetooth_settings" -> context.startActivity(Intent(Settings.ACTION_BLUETOOTH_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                "open_camera" -> context.startActivity(Intent("android.media.action.IMAGE_CAPTURE").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                "open_calendar" -> context.startActivity(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_APP_CALENDAR).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                "open_maps" -> context.startActivity(Intent("android.intent.action.VIEW", android.net.Uri.parse("geo:0,0")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }
            Unit
        }
    }

    private fun setTorch(enabled: Boolean) {
        val cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        val cameraId = cameraManager.cameraIdList.firstOrNull { id ->
            cameraManager.getCameraCharacteristics(id)
                .get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
        } ?: throw IllegalStateException("No torch-capable camera")
        cameraManager.setTorchMode(cameraId, enabled)
    }

    private fun setBrightness(value: String?) {
        val brightness = (value?.toFloatOrNull() ?: 0.5f).coerceIn(0f, 1f)
        val resolver = context.contentResolver
        if (!Settings.System.canWrite(context)) {
            context.startActivity(
                Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS)
                    .setData(android.net.Uri.parse("package:" + context.packageName))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
            throw SecurityException("WRITE_SETTINGS permission is required")
        }
        Settings.System.putInt(resolver, Settings.System.SCREEN_BRIGHTNESS, (brightness * 255).toInt())
    }

    private fun adjustVolume(direction: Int) {
        val audio = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        audio.adjustVolume(direction, AudioManager.FLAG_SHOW_UI)
    }

    private fun sendMediaKey(keyCode: Int) {
        val audio = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        audio.dispatchMediaKeyEvent(android.view.KeyEvent(android.view.KeyEvent.ACTION_DOWN, keyCode))
        audio.dispatchMediaKeyEvent(android.view.KeyEvent(android.view.KeyEvent.ACTION_UP, keyCode))
    }
}
