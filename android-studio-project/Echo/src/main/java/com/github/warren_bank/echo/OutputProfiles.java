package com.github.warren_bank.echo;

import android.content.SharedPreferences;
import android.media.AudioDeviceInfo;

// Remembers the main sound settings separately for Bluetooth and wired outputs,
// so switching speaker brings back what was used with it last time.
final class OutputProfiles {
  static final String PREF_PER_OUTPUT = "PER_OUTPUT";

  static final String BLUETOOTH = "bt";
  static final String WIRED     = "wired";

  private static final String[] BOOLEAN_KEYS = {
    AudioEchoService.PREF_SYSTEM_NS,
    AudioEchoService.PREF_DENOISE,
    AudioEchoService.PREF_FEEDBACK,
    AudioEchoService.PREF_BACKOFF
  };
  private static final String[] INT_KEYS = {
    AudioEchoService.PREF_EQ
  };

  private OutputProfiles() {}

  // null when the output has no profile (phone speaker, unknown)
  static String categoryOf(AudioDeviceInfo device) {
    if (device == null) return null;
    switch (device.getType()) {
      case AudioDeviceInfo.TYPE_BLUETOOTH_A2DP:
      case AudioDeviceInfo.TYPE_BLUETOOTH_SCO:
      case AudioDeviceInfo.TYPE_BLE_HEADSET:
      case AudioDeviceInfo.TYPE_BLE_SPEAKER:
      case AudioDeviceInfo.TYPE_BLE_BROADCAST:
        return BLUETOOTH;
      case AudioDeviceInfo.TYPE_WIRED_HEADSET:
      case AudioDeviceInfo.TYPE_WIRED_HEADPHONES:
      case AudioDeviceInfo.TYPE_USB_HEADSET:
      case AudioDeviceInfo.TYPE_USB_DEVICE:
      case AudioDeviceInfo.TYPE_USB_ACCESSORY:
      case AudioDeviceInfo.TYPE_LINE_ANALOG:
      case AudioDeviceInfo.TYPE_LINE_DIGITAL:
      case AudioDeviceInfo.TYPE_AUX_LINE:
        return WIRED;
      default:
        return null;
    }
  }

  private static String key(String category, String pref) {
    return "PROFILE_" + category + "_" + pref;
  }

  static void save(SharedPreferences prefs, String category) {
    if (category == null) return;
    SharedPreferences.Editor editor = prefs.edit();
    editor.putFloat(key(category, AudioEchoService.PREF_AUDIO_GAIN), prefs.getFloat(AudioEchoService.PREF_AUDIO_GAIN, 1.0f));
    for (String pref : BOOLEAN_KEYS)
      if (prefs.contains(pref)) editor.putBoolean(key(category, pref), prefs.getBoolean(pref, false));
    for (String pref : INT_KEYS)
      if (prefs.contains(pref)) editor.putInt(key(category, pref), prefs.getInt(pref, 0));
    editor.apply();
  }

  // Returns false when nothing was saved for that output yet
  static boolean load(SharedPreferences prefs, String category) {
    if (category == null) return false;
    String gainKey = key(category, AudioEchoService.PREF_AUDIO_GAIN);
    if (!prefs.contains(gainKey)) return false;

    SharedPreferences.Editor editor = prefs.edit();
    editor.putFloat(AudioEchoService.PREF_AUDIO_GAIN, prefs.getFloat(gainKey, 1.0f));
    for (String pref : BOOLEAN_KEYS) {
      String k = key(category, pref);
      if (prefs.contains(k)) editor.putBoolean(pref, prefs.getBoolean(k, false));
    }
    for (String pref : INT_KEYS) {
      String k = key(category, pref);
      if (prefs.contains(k)) editor.putInt(pref, prefs.getInt(k, 0));
    }
    editor.apply();
    return true;
  }
}
