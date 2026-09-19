package com.bqsqwad.ai.core

data class DeviceAction(
    val type: String,
    val value: String? = null
)

data class DeviceCommandResponse(
    val providerId: String,
    val modelId: String,
    val message: String,
    val actions: List<DeviceAction>
)

object DeviceCommandPolicy {
    val allowedActions = setOf(
        "torch_on",
        "torch_off",
        "set_brightness",
        "volume_up",
        "volume_down",
        "play_media",
        "pause_media",
        "next_media",
        "previous_media",
        "open_settings",
        "open_wifi_settings",
        "open_bluetooth_settings",
        "open_camera",
        "open_calendar",
        "open_maps"
    )

    fun isAllowed(action: DeviceAction): Boolean = action.type in allowedActions
}
