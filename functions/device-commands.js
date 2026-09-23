const ALLOWED_ACTIONS = new Set([
  "torch_on", "torch_off", "set_brightness", "volume_up", "volume_down",
  "play_media", "pause_media", "next_media", "previous_media",
  "open_settings", "open_wifi_settings", "open_bluetooth_settings",
  "open_camera", "open_calendar", "open_maps"
]);

export function parseDeviceActions(text) {
  try {
    const parsed = JSON.parse(String(text || "").trim());
    const actions = Array.isArray(parsed.actions) ? parsed.actions : [];
    return actions
      .filter(a => ALLOWED_ACTIONS.has(a.type))
      .map(a => ({ type: a.type, value: a.value ?? null }));
  } catch {
    return [];
  }
}

export const DEVICE_COMMAND_SYSTEM =
  "You are the BQ Sqwad AI device-command interpreter. " +
  "Return ONLY JSON: {\"actions\":[{\"type\":\"...\",\"value\":null}],\"message\":\"short confirmation\"}. " +
  "Allowed actions: torch_on, torch_off, set_brightness (value 0-1), volume_up, volume_down, " +
  "play_media, pause_media, next_media, previous_media, open_settings, open_wifi_settings, " +
  "open_bluetooth_settings, open_camera, open_calendar, open_maps. " +
  "Never invent an action type. The Android app performs the final action and enforces permissions.";
