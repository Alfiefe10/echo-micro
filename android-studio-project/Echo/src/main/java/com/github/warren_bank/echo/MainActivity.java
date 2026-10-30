package com.github.warren_bank.echo;

import android.Manifest.permission;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.res.ColorStateList;
import android.media.AudioDeviceCallback;
import android.media.AudioDeviceInfo;
import android.media.AudioManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.preference.PreferenceManager;
import android.view.MotionEvent;
import android.view.View;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.List;

public class MainActivity extends Activity {
  private static final int REQUEST_CODE_PERMISSIONS = 1;
  private static final String PREF_HOLD_MODE = "HOLD_MODE";
  private static final long UI_TICK_MS = 50;

  private SharedPreferences prefs;
  private AudioManager audioManager;
  private Handler handler;

  private LinearLayout outputCard;
  private ImageView outputIcon;
  private TextView outputTitle;
  private TextView outputSubtitle;
  private MicButtonView micButton;
  private TextView statusLabel;
  private TextView hintLabel;
  private LinearLayout recordButton;
  private ImageView recordIcon;
  private TextView recordLabel;
  private TextView gainValue;
  private SeekBar gainBar;
  private Button stopButton;

  private boolean holdMode;
  private boolean pendingStartMuted;
  private boolean hasPendingStart;
  private String statusOverride;
  private String lastCategory;
  private boolean wasRecording;

  private final AudioDeviceCallback deviceCallback = new AudioDeviceCallback() {
    @Override
    public void onAudioDevicesAdded(AudioDeviceInfo[] addedDevices) { updateUi(); }

    @Override
    public void onAudioDevicesRemoved(AudioDeviceInfo[] removedDevices) { updateUi(); }
  };

  private final Runnable uiTick = new Runnable() {
    @Override
    public void run() {
      updateUi();
      handler.postDelayed(this, UI_TICK_MS);
    }
  };

  @Override
  protected void onCreate(Bundle savedInstanceState) {
    super.onCreate(savedInstanceState);
    setContentView(R.layout.activity_main);

    prefs        = PreferenceManager.getDefaultSharedPreferences(this);
    audioManager = (AudioManager) getSystemService(Context.AUDIO_SERVICE);
    handler      = new Handler(Looper.getMainLooper());

    outputCard     = findViewById(R.id.outputCard);
    outputIcon     = findViewById(R.id.outputIcon);
    outputTitle    = findViewById(R.id.outputTitle);
    outputSubtitle = findViewById(R.id.outputSubtitle);
    micButton      = findViewById(R.id.micButton);
    statusLabel    = findViewById(R.id.statusLabel);
    hintLabel      = findViewById(R.id.hintLabel);
    recordButton   = findViewById(R.id.recordButton);
    recordIcon     = findViewById(R.id.recordIcon);
    recordLabel    = findViewById(R.id.recordLabel);
    gainValue      = findViewById(R.id.gainValue);
    gainBar        = findViewById(R.id.gainBar);
    stopButton     = findViewById(R.id.stopButton);

    int secondary = getColor(R.color.text_secondary);
    ((ImageView) findViewById(R.id.volumeIcon)).setImageTintList(ColorStateList.valueOf(secondary));
    ImageView settingsButton = findViewById(R.id.settingsButton);
    settingsButton.setImageTintList(ColorStateList.valueOf(getColor(R.color.text_primary)));
    settingsButton.setOnClickListener(v -> startActivity(new Intent(this, SettingsActivity.class)));

    migrateSettings();
    AudioEchoService.loadSettings(this);
    Ui.applySystemBarInsets(findViewById(R.id.content));

    holdMode = prefs.getBoolean(PREF_HOLD_MODE, false);
    Ui.setupSegment(this, R.id.modeSegment, new int[]{R.string.mode_toggle, R.string.mode_hold},
      holdMode ? 1 : 0, index -> setHoldMode(index == 1));

    setupSoundControls();
    applyButtonMode();

    recordButton.setOnClickListener(v -> toggleRecording());
    stopButton.setOnClickListener(v -> stopEcho());

    lastCategory = OutputProfiles.categoryOf(currentOutput());
    updateUi();
  }

  @Override
  protected void onResume() {
    super.onResume();
    // Settings may have changed while away
    AudioEchoService.loadSettings(this);
    refreshSoundControls();
    audioManager.registerAudioDeviceCallback(deviceCallback, handler);
    handler.post(uiTick);
  }

  @Override
  protected void onPause() {
    super.onPause();
    audioManager.unregisterAudioDeviceCallback(deviceCallback);
    handler.removeCallbacks(uiTick);

    // A finger can't be holding the button once the screen is gone
    if (holdMode && AudioEchoService.isActive)
      setMuted(true);
  }

  // Up to 2.1 "Strong" meant the 5 Hz shift; it now means level back-off,
  // and the shift is a separate (experimental) switch.
  private void migrateSettings() {
    if (prefs.contains(AudioEchoService.PREF_BACKOFF)) return;
    boolean wasStrong = prefs.getBoolean(AudioEchoService.PREF_SHIFT, false);
    prefs.edit()
      .putBoolean(AudioEchoService.PREF_BACKOFF, wasStrong)
      .putBoolean(AudioEchoService.PREF_SHIFT, false)
      .apply();
  }

  // ---------------------------------------------------------------------------
  // Sound controls (volume, voice, noise, howl)

  private void setupSoundControls() {
    gainBar.setMax(Math.round(AudioEchoService.audioGainMax * 100.0f));
    gainBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
      @Override
      public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
        gainValue.setText(progress + " %");
        if (fromUser) applyGain(progress / 100.0f);
      }

      @Override public void onStartTrackingTouch(SeekBar seekBar) {}
      @Override public void onStopTrackingTouch(SeekBar seekBar) {
        saveProfile();
      }
    });

    Ui.setupSegment(this, R.id.eqSegment, new int[]{R.string.eq_natural, R.string.eq_clear, R.string.eq_small},
      currentEq(), this::setEq);
    Ui.setupSegment(this, R.id.noiseSegment, new int[]{R.string.level_off, R.string.level_light, R.string.level_strong},
      currentNoiseLevel(), this::setNoiseLevel);
    Ui.setupSegment(this, R.id.howlSegment, new int[]{R.string.level_off, R.string.level_normal, R.string.level_strong},
      currentHowlLevel(), this::setHowlLevel);
    refreshSoundControls();
  }

  private void refreshSoundControls() {
    int percent = Math.round(prefs.getFloat(AudioEchoService.PREF_AUDIO_GAIN, 1.0f) * 100.0f);
    gainBar.setProgress(percent);
    gainValue.setText(percent + " %");
    Ui.selectSegment(this, R.id.eqSegment, currentEq());
    Ui.selectSegment(this, R.id.noiseSegment, currentNoiseLevel());
    Ui.selectSegment(this, R.id.howlSegment, currentHowlLevel());
  }

  private void applyGain(float gain) {
    if (AudioEchoService.isActive) {
      Intent intent = serviceIntent(AudioEchoService.ACTION_SET_GAIN);
      intent.putExtra(AudioEchoService.EXTRA_GAIN, gain);
      startService(intent);
    }
    // The service saves it too; writing here keeps profiles in sync right away
    prefs.edit().putFloat(AudioEchoService.PREF_AUDIO_GAIN, gain).apply();
  }

  private int currentEq() {
    return prefs.getInt(AudioEchoService.PREF_EQ, 0);
  }

  private void setEq(int preset) {
    prefs.edit().putInt(AudioEchoService.PREF_EQ, preset).apply();
    AudioEchoService.loadSettings(this);
    saveProfile();
  }

  // Background noise: 0 off, 1 the phone's own suppressor, 2 RNNoise
  private int currentNoiseLevel() {
    if (prefs.getBoolean(AudioEchoService.PREF_DENOISE, false)) return 2;
    if (prefs.getBoolean(AudioEchoService.PREF_SYSTEM_NS, false)) return 1;
    return 0;
  }

  private void setNoiseLevel(int level) {
    boolean systemNsBefore = prefs.getBoolean(AudioEchoService.PREF_SYSTEM_NS, false);
    prefs.edit()
      .putBoolean(AudioEchoService.PREF_SYSTEM_NS, level == 1)
      .putBoolean(AudioEchoService.PREF_DENOISE,   level == 2)
      .apply();
    AudioEchoService.loadSettings(this);
    // The phone's suppressor is attached when the microphone opens
    if (systemNsBefore != (level == 1)) restartIfActive();
    saveProfile();
    updateUi();
  }

  // Howl protection: 0 off, 1 automatic notches, 2 notches + level back-off
  private int currentHowlLevel() {
    if (!prefs.getBoolean(AudioEchoService.PREF_FEEDBACK, true)) return 0;
    return prefs.getBoolean(AudioEchoService.PREF_BACKOFF, false) ? 2 : 1;
  }

  private void setHowlLevel(int level) {
    prefs.edit()
      .putBoolean(AudioEchoService.PREF_FEEDBACK, level >= 1)
      .putBoolean(AudioEchoService.PREF_BACKOFF,  level == 2)
      .apply();
    AudioEchoService.loadSettings(this);
    saveProfile();
    updateUi();
  }

  // ---------------------------------------------------------------------------
  // Per-output profiles

  private boolean perOutput() {
    return prefs.getBoolean(OutputProfiles.PREF_PER_OUTPUT, true);
  }

  private void saveProfile() {
    if (perOutput()) OutputProfiles.save(prefs, OutputProfiles.categoryOf(currentOutput()));
  }

  private void onOutputMaybeChanged() {
    String category = OutputProfiles.categoryOf(currentOutput());
    if (category == null || category.equals(lastCategory)) {
      if (category != null) lastCategory = category;
      return;
    }
    lastCategory = category;
    if (!perOutput()) return;

    boolean systemNsBefore = prefs.getBoolean(AudioEchoService.PREF_SYSTEM_NS, false);
    if (OutputProfiles.load(prefs, category)) {
      AudioEchoService.loadSettings(this);
      refreshSoundControls();
      applyGain(prefs.getFloat(AudioEchoService.PREF_AUDIO_GAIN, 1.0f));
      if (systemNsBefore != prefs.getBoolean(AudioEchoService.PREF_SYSTEM_NS, false)) restartIfActive();
    }
    else {
      // First time on this output: start from the current settings
      OutputProfiles.save(prefs, category);
    }
  }

  // ---------------------------------------------------------------------------
  // Recording

  private void toggleRecording() {
    if (AudioEchoService.isRecording) {
      startService(serviceIntent(AudioEchoService.ACTION_RECORD_STOP));
    }
    else if (!AudioEchoService.isActive) {
      Toast.makeText(this, R.string.recording_needs_mic, Toast.LENGTH_SHORT).show();
    }
    else {
      startService(serviceIntent(AudioEchoService.ACTION_RECORD_START));
    }
  }

  private void updateRecordButton() {
    boolean recording = AudioEchoService.isRecording;
    if (recording) {
      long seconds = AudioEchoService.recordingElapsedMs / 1000;
      recordLabel.setText(getString(R.string.recording_elapsed, String.format("%d:%02d", seconds / 60, seconds % 60)));
      recordIcon.setImageResource(R.drawable.ic_stop);
    }
    else {
      recordLabel.setText(R.string.record);
      recordIcon.setImageResource(R.drawable.ic_record);
    }
    recordButton.setBackgroundResource(recording ? R.drawable.pill_recording : R.drawable.pill_outline);
    recordIcon.setImageTintList(ColorStateList.valueOf(getColor(recording ? R.color.on_warning_container : R.color.warning)));
    recordLabel.setTextColor(getColor(recording ? R.color.on_warning_container : R.color.text_primary));
    recordButton.setAlpha(AudioEchoService.isActive || recording ? 1.0f : 0.5f);

    // Tell where the file went once a recording ends
    if (wasRecording && !recording) {
      String error = AudioEchoService.recordingError;
      String saved = AudioEchoService.lastRecording;
      if (error != null)
        Toast.makeText(this, getString(R.string.recording_failed, error), Toast.LENGTH_LONG).show();
      else if (saved != null)
        Toast.makeText(this, getString(R.string.recording_saved, saved), Toast.LENGTH_LONG).show();
    }
    wasRecording = recording;
  }

  // ---------------------------------------------------------------------------
  // Mode and service

  private void restartIfActive() {
    if (!AudioEchoService.isActive) return;
    boolean muted = AudioEchoService.isMuted;
    stopEcho();
    handler.postDelayed(() -> startEcho(muted), 300);
  }

  private void setHoldMode(boolean hold) {
    if (hold == holdMode) return;
    holdMode = hold;
    prefs.edit().putBoolean(PREF_HOLD_MODE, hold).apply();

    if (AudioEchoService.isActive) {
      if (hold) setMuted(true);
      else stopEcho();
    }
    applyButtonMode();
    updateUi();
  }

  private void applyButtonMode() {
    if (holdMode) {
      micButton.setOnClickListener(null);
      micButton.setOnTouchListener((v, ev) -> {
        switch (ev.getActionMasked()) {
          case MotionEvent.ACTION_DOWN:
            v.performClick();
            micButton.setHeld(true);
            if (AudioEchoService.isActive) setMuted(false);
            else startEcho(false);
            return true;
          case MotionEvent.ACTION_UP:
          case MotionEvent.ACTION_CANCEL:
            micButton.setHeld(false);
            hasPendingStart = false;
            setMuted(true);
            return true;
          default:
            return false;
        }
      });
    }
    else {
      micButton.setOnTouchListener(null);
      micButton.setOnClickListener(v -> {
        if (AudioEchoService.isActive) stopEcho();
        else startEcho(false);
      });
    }
  }

  private Intent serviceIntent(String action) {
    Intent intent = new Intent(getApplicationContext(), AudioEchoService.class);
    if (action != null) intent.setAction(action);
    return intent;
  }

  private void startEcho(boolean muted) {
    if (!hasPermissions()) {
      pendingStartMuted = muted;
      hasPendingStart   = true;
      requestNeededPermissions();
      return;
    }

    if (AudioEchoService.guardSpeaker && isOnPhoneSpeaker(currentOutput())) {
      hasPendingStart = false;
      showNoSpeakerDialog();
      return;
    }

    statusOverride = null;
    // The service reads this shared flag, so a release that arrives before
    // the service is up still mutes it
    AudioEchoService.isMuted = muted;
    startForegroundService(serviceIntent(null));
    updateUi();
  }

  private void stopEcho() {
    if (AudioEchoService.isActive)
      startService(serviceIntent(AudioEchoService.ACTION_EXIT));
    AudioEchoService.isActive = false;
    updateUi();
  }

  private void setMuted(boolean muted) {
    AudioEchoService.isMuted = muted;
    updateUi();
  }

  // ---------------------------------------------------------------------------
  // Permissions

  private String[] neededPermissions() {
    List<String> needed = new ArrayList<>();
    needed.add(permission.RECORD_AUDIO);
    if (Build.VERSION.SDK_INT >= 33)
      needed.add(permission.POST_NOTIFICATIONS);
    return needed.toArray(new String[0]);
  }

  private boolean hasPermissions() {
    return checkSelfPermission(permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED;
  }

  private void requestNeededPermissions() {
    requestPermissions(neededPermissions(), REQUEST_CODE_PERMISSIONS);
  }

  @Override
  public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
    super.onRequestPermissionsResult(requestCode, permissions, grantResults);
    if (requestCode != REQUEST_CODE_PERMISSIONS) return;

    if (hasPermissions()) {
      // In hold mode the finger is already up after the dialog: start muted
      if (hasPendingStart) startEcho(holdMode || pendingStartMuted);
    }
    else {
      statusOverride = getString(R.string.status_permission_denied);
    }
    hasPendingStart = false;
    updateUi();
  }

  // ---------------------------------------------------------------------------
  // Output detection

  static boolean isBluetooth(int type) {
    return type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP
        || type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO
        || type == AudioDeviceInfo.TYPE_BLE_HEADSET
        || type == AudioDeviceInfo.TYPE_BLE_SPEAKER
        || type == AudioDeviceInfo.TYPE_BLE_BROADCAST
        || type == AudioDeviceInfo.TYPE_HEARING_AID;
  }

  static boolean isWired(int type) {
    return type == AudioDeviceInfo.TYPE_WIRED_HEADSET
        || type == AudioDeviceInfo.TYPE_WIRED_HEADPHONES
        || type == AudioDeviceInfo.TYPE_USB_HEADSET
        || type == AudioDeviceInfo.TYPE_USB_DEVICE
        || type == AudioDeviceInfo.TYPE_USB_ACCESSORY
        || type == AudioDeviceInfo.TYPE_LINE_ANALOG
        || type == AudioDeviceInfo.TYPE_LINE_DIGITAL
        || type == AudioDeviceInfo.TYPE_AUX_LINE;
  }

  // Android only lists USB audio devices that have their own DAC
  static boolean isUsbDac(int type) {
    return type == AudioDeviceInfo.TYPE_USB_HEADSET
        || type == AudioDeviceInfo.TYPE_USB_DEVICE
        || type == AudioDeviceInfo.TYPE_USB_ACCESSORY;
  }

  private static boolean isOnPhoneSpeaker(AudioDeviceInfo device) {
    return (device == null) || AudioEchoService.isPhoneSpeaker(device);
  }

  // The real route while running; otherwise a guess from the connected outputs.
  private AudioDeviceInfo currentOutput() {
    AudioDeviceInfo routed = AudioEchoService.routedDevice;
    if (AudioEchoService.isActive && routed != null) return routed;

    AudioDeviceInfo bluetooth = null;
    AudioDeviceInfo wired     = null;
    AudioDeviceInfo speaker   = null;
    for (AudioDeviceInfo device : audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)) {
      int type = device.getType();
      if (isWired(type) && wired == null) wired = device;
      else if (isBluetooth(type) && type != AudioDeviceInfo.TYPE_BLUETOOTH_SCO && bluetooth == null) bluetooth = device;
      else if (type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER) speaker = device;
    }
    if (wired != null) return wired;
    if (bluetooth != null) return bluetooth;
    return speaker;
  }

  private void showNoSpeakerDialog() {
    new AlertDialog.Builder(this)
      .setTitle(R.string.no_speaker_title)
      .setMessage(R.string.no_speaker_message)
      .setPositiveButton(android.R.string.ok, null)
      .show();
  }

  // ---------------------------------------------------------------------------
  // UI

  private void updateOutputCard() {
    AudioDeviceInfo device = currentOutput();
    int icon;
    String subtitle;
    boolean warn = false;

    if (isOnPhoneSpeaker(device)) {
      icon = R.drawable.ic_warning;
      outputTitle.setText(R.string.output_none_title);
      subtitle = getString(AudioEchoService.guardSpeaker ? R.string.output_none_subtitle : R.string.output_none_unguarded);
      warn = true;
    }
    else {
      CharSequence productName = device.getProductName();
      String name = (productName == null) ? "" : productName.toString().trim();
      int type = device.getType();

      if (isBluetooth(type)) {
        icon = R.drawable.ic_bluetooth;
        outputTitle.setText(getString(R.string.output_bluetooth_title, name));
        subtitle = getString(R.string.output_bluetooth_subtitle);
      }
      else if (isWired(type)) {
        icon = R.drawable.ic_wired;
        outputTitle.setText(name.isEmpty() ? getString(R.string.output_wired_generic) : getString(R.string.output_wired_title, name));
        subtitle = getString(isUsbDac(type) ? R.string.output_wired_dac_subtitle : R.string.output_wired_subtitle);
      }
      else {
        icon = R.drawable.ic_speaker;
        outputTitle.setText(getString(R.string.output_other_title, name));
        subtitle = "";
      }
    }

    outputSubtitle.setText(subtitle);
    outputSubtitle.setVisibility(subtitle.isEmpty() ? View.GONE : View.VISIBLE);
    outputIcon.setImageResource(icon);

    int textColor = getColor(warn ? R.color.on_warning_container : R.color.text_primary);
    int subColor  = getColor(warn ? R.color.on_warning_container : R.color.text_secondary);
    int iconColor = getColor(warn ? R.color.on_warning_container : R.color.accent);
    outputCard.setBackgroundResource(warn ? R.drawable.card_warn_bg : R.drawable.card_bg);
    outputTitle.setTextColor(textColor);
    outputSubtitle.setTextColor(subColor);
    outputIcon.setImageTintList(ColorStateList.valueOf(iconColor));
  }

  private void updateUi() {
    boolean active  = AudioEchoService.isActive;
    boolean blocked = active && AudioEchoService.isBlocked;
    boolean live    = active && !AudioEchoService.isMuted && !blocked;

    onOutputMaybeChanged();
    updateOutputCard();
    updateRecordButton();

    String idleHint = getString(holdMode ? R.string.hint_hold : R.string.hint_toggle);
    String status, hint;
    int state;
    if (!active && AudioEchoService.lastError != null) {
      status = getString(R.string.status_error, AudioEchoService.lastError);
      hint   = idleHint;
      state  = MicButtonView.STATE_IDLE;
    }
    else if (!active && statusOverride != null) {
      status = statusOverride;
      hint   = idleHint;
      state  = MicButtonView.STATE_IDLE;
    }
    else if (blocked) {
      status = getString(R.string.status_blocked);
      hint   = getString(R.string.hint_blocked);
      state  = MicButtonView.STATE_BLOCKED;
    }
    else if (live) {
      status = getString(R.string.status_live);
      int cuts = AudioEchoService.feedbackCuts;
      int backoff = (int) Math.round(-AudioEchoService.backoffDb);
      if (backoff > 0)
        hint = getString(R.string.hint_backoff, backoff);
      else if (cuts > 0)
        hint = getString(R.string.hint_cuts, cuts);
      else
        hint = getString(holdMode ? R.string.hint_hold : R.string.hint_toggle_stop);
      state  = MicButtonView.STATE_LIVE;
    }
    else if (active) {
      status = getString(R.string.status_muted);
      hint   = getString(R.string.hint_hold);
      state  = MicButtonView.STATE_MUTED;
    }
    else {
      status = getString(R.string.status_idle);
      hint   = idleHint;
      state  = MicButtonView.STATE_IDLE;
    }
    statusLabel.setText(status);
    hintLabel.setText(hint);
    micButton.setState(state);
    micButton.setLevel(live ? AudioEchoService.inputLevel : 0.0f);

    stopButton.setVisibility(active && holdMode ? View.VISIBLE : View.GONE);
  }
}
