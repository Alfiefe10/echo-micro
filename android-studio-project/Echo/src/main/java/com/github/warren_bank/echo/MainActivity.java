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
import android.media.MediaRecorder;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.preference.PreferenceManager;
import android.view.MotionEvent;
import android.view.View;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.ProgressBar;
import android.widget.RadioGroup;
import android.widget.SeekBar;
import android.widget.Switch;
import android.widget.Spinner;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;

public class MainActivity extends Activity {
  private static final int REQUEST_CODE_PERMISSIONS = 1;
  private static final String PREF_HOLD_MODE = "HOLD_MODE";
  private static final long UI_TICK_MS = 50;

  private static final int[] SOURCES = {
    MediaRecorder.AudioSource.MIC,
    MediaRecorder.AudioSource.VOICE_PERFORMANCE,
    MediaRecorder.AudioSource.UNPROCESSED,
    MediaRecorder.AudioSource.VOICE_COMMUNICATION
  };

  private static final int[] SOURCE_LABELS = {
    R.string.source_mic,
    R.string.source_performance,
    R.string.source_unprocessed,
    R.string.source_communication
  };

  private SharedPreferences prefs;
  private AudioManager audioManager;
  private Handler handler;

  private TextView outputLabel;
  private TextView warningLabel;
  private TextView statusLabel;
  private TextView hintLabel;
  private TextView gainLabel;
  private FrameLayout micButton;
  private ImageView micIcon;
  private ProgressBar levelBar;
  private RadioGroup modeGroup;
  private SeekBar gainBar;
  private Spinner sourceSpinner;
  private Button stopButton;

  private boolean holdMode;
  private boolean pendingStartMuted;
  private boolean hasPendingStart;
  private String statusOverride;

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

    outputLabel   = findViewById(R.id.outputLabel);
    warningLabel  = findViewById(R.id.warningLabel);
    statusLabel   = findViewById(R.id.statusLabel);
    hintLabel     = findViewById(R.id.hintLabel);
    gainLabel     = findViewById(R.id.gainLabel);
    micButton     = findViewById(R.id.micButton);
    micIcon       = findViewById(R.id.micIcon);
    levelBar      = findViewById(R.id.levelBar);
    modeGroup     = findViewById(R.id.modeGroup);
    gainBar       = findViewById(R.id.gainBar);
    sourceSpinner = findViewById(R.id.sourceSpinner);
    stopButton    = findViewById(R.id.stopButton);

    holdMode = prefs.getBoolean(PREF_HOLD_MODE, false);
    modeGroup.check(holdMode ? R.id.modeHold : R.id.modeToggle);
    modeGroup.setOnCheckedChangeListener((group, checkedId) -> setHoldMode(checkedId == R.id.modeHold));

    setupGain();
    setupSource();
    applyButtonMode();

    stopButton.setOnClickListener(v -> stopEcho());

    AudioEchoService.loadSettings(this);
    bindSwitch(R.id.guardSwitch,     AudioEchoService.PREF_GUARD_SPEAKER, true,  false);
    bindSwitch(R.id.feedbackSwitch,  AudioEchoService.PREF_FEEDBACK,      true,  false);
    bindSwitch(R.id.denoiseSwitch,   AudioEchoService.PREF_DENOISE,       false, false);
    bindSwitch(R.id.highPassSwitch,  AudioEchoService.PREF_HIGH_PASS,     true,  false);
    bindSwitch(R.id.limiterSwitch,   AudioEchoService.PREF_LIMITER,       true,  false);
    bindSwitch(R.id.micDirSwitch,    AudioEchoService.PREF_MIC_DIRECTION, true,  true);
    bindSwitch(R.id.systemNsSwitch,  AudioEchoService.PREF_SYSTEM_NS,     false, true);

    updateUi();
  }

  @Override
  protected void onResume() {
    super.onResume();
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

  // ---------------------------------------------------------------------------
  // Controls

  private void setupGain() {
    int percent = Math.round(prefs.getFloat(AudioEchoService.PREF_AUDIO_GAIN, 1.0f) * 100.0f);
    gainBar.setMax(Math.round(AudioEchoService.audioGainMax * 100.0f));
    gainBar.setProgress(percent);
    gainLabel.setText(getString(R.string.gain, percent));

    gainBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
      @Override
      public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
        gainLabel.setText(getString(R.string.gain, progress));
        if (!fromUser) return;

        float gain = progress / 100.0f;
        if (AudioEchoService.isActive) {
          Intent intent = serviceIntent(AudioEchoService.ACTION_SET_GAIN);
          intent.putExtra(AudioEchoService.EXTRA_GAIN, gain);
          startService(intent);
        }
        else {
          prefs.edit().putFloat(AudioEchoService.PREF_AUDIO_GAIN, gain).apply();
        }
      }

      @Override public void onStartTrackingTouch(SeekBar seekBar) {}
      @Override public void onStopTrackingTouch(SeekBar seekBar) {}
    });
  }

  private void setupSource() {
    List<String> labels = new ArrayList<>();
    for (int label : SOURCE_LABELS)
      labels.add(getString(label));

    ArrayAdapter<String> adapter = new ArrayAdapter<>(this, android.R.layout.simple_spinner_item, labels);
    adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
    sourceSpinner.setAdapter(adapter);

    int current = prefs.getInt(AudioEchoService.PREF_AUDIO_SOURCE, MediaRecorder.AudioSource.MIC);
    for (int i = 0; i < SOURCES.length; i++) {
      if (SOURCES[i] == current) sourceSpinner.setSelection(i, false);
    }

    sourceSpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
      @Override
      public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
        int source = SOURCES[position];
        if (source == prefs.getInt(AudioEchoService.PREF_AUDIO_SOURCE, MediaRecorder.AudioSource.MIC)) return;

        prefs.edit().putInt(AudioEchoService.PREF_AUDIO_SOURCE, source).apply();

        // The source is read when the audio thread starts: restart it
        restartIfActive();
      }

      @Override public void onNothingSelected(AdapterView<?> parent) {}
    });
  }

  private void restartIfActive() {
    if (!AudioEchoService.isActive) return;
    boolean muted = AudioEchoService.isMuted;
    stopEcho();
    handler.postDelayed(() -> startEcho(muted), 300);
  }

  // restart: the setting is only read when the audio thread starts
  private void bindSwitch(int id, String pref, boolean byDefault, boolean restart) {
    Switch view = findViewById(id);
    view.setChecked(prefs.getBoolean(pref, byDefault));
    view.setOnCheckedChangeListener((button, checked) -> {
      prefs.edit().putBoolean(pref, checked).apply();
      AudioEchoService.loadSettings(this);
      if (restart) restartIfActive();
      updateUi();
    });
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
            v.animate().scaleX(1.06f).scaleY(1.06f).setDuration(80).start();
            if (AudioEchoService.isActive) setMuted(false);
            else startEcho(false);
            return true;
          case MotionEvent.ACTION_UP:
          case MotionEvent.ACTION_CANCEL:
            v.animate().scaleX(1f).scaleY(1f).setDuration(120).start();
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

  // ---------------------------------------------------------------------------
  // Service

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
  // UI

  private static boolean isBluetooth(int type) {
    return type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP
        || type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO
        || type == AudioDeviceInfo.TYPE_BLE_HEADSET
        || type == AudioDeviceInfo.TYPE_BLE_SPEAKER
        || type == AudioDeviceInfo.TYPE_BLE_BROADCAST
        || type == AudioDeviceInfo.TYPE_HEARING_AID;
  }

  private static boolean isWired(int type) {
    return type == AudioDeviceInfo.TYPE_WIRED_HEADSET
        || type == AudioDeviceInfo.TYPE_WIRED_HEADPHONES
        || type == AudioDeviceInfo.TYPE_USB_HEADSET
        || type == AudioDeviceInfo.TYPE_USB_DEVICE
        || type == AudioDeviceInfo.TYPE_USB_ACCESSORY
        || type == AudioDeviceInfo.TYPE_LINE_ANALOG
        || type == AudioDeviceInfo.TYPE_LINE_DIGITAL
        || type == AudioDeviceInfo.TYPE_AUX_LINE;
  }

  private static boolean isOnPhoneSpeaker(AudioDeviceInfo device) {
    return (device == null) || AudioEchoService.isPhoneSpeaker(device);
  }

  private void showNoSpeakerDialog() {
    new AlertDialog.Builder(this)
      .setTitle(R.string.no_speaker_title)
      .setMessage(R.string.no_speaker_message)
      .setPositiveButton(android.R.string.ok, null)
      .show();
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

  private void updateOutputLabel() {
    AudioDeviceInfo device = currentOutput();
    boolean warn = false;
    String text;

    if (isOnPhoneSpeaker(device)) {
      text = getString(R.string.output_speaker);
      warn = true;
    }
    else {
      CharSequence name = device.getProductName();
      String label = (name == null) ? "" : name.toString().trim();
      int type = device.getType();

      if (isBluetooth(type))
        text = getString(R.string.output_bluetooth, label);
      else if (isWired(type))
        text = label.isEmpty() ? getString(R.string.output_wired_generic) : getString(R.string.output_wired, label);
      else
        text = getString(R.string.output_other, label);
    }

    outputLabel.setText(text);
    warningLabel.setVisibility(warn ? View.VISIBLE : View.GONE);
  }

  private void updateUi() {
    boolean active = AudioEchoService.isActive;
    boolean live   = active && !AudioEchoService.isMuted;

    updateOutputLabel();

    String status;
    if (active && AudioEchoService.denoise && !AudioEchoService.denoiseUsable)
      status = getString(R.string.status_denoise_unavailable);
    else if (active && AudioEchoService.isBlocked)
      status = getString(R.string.status_blocked);
    else if (AudioEchoService.lastError != null && !active)
      status = getString(R.string.status_error, AudioEchoService.lastError);
    else if (statusOverride != null && !active)
      status = statusOverride;
    else if (live && AudioEchoService.feedbackCuts > 0)
      status = getString(R.string.status_live_cuts, AudioEchoService.feedbackCuts);
    else if (live)
      status = getString(R.string.status_live);
    else if (active)
      status = getString(R.string.status_muted);
    else
      status = getString(R.string.status_idle);
    statusLabel.setText(status);

    live = live && !AudioEchoService.isBlocked;
    int color = getColor(live ? R.color.mic_active : (active ? R.color.mic_muted : R.color.mic_idle));
    micButton.setBackgroundTintList(ColorStateList.valueOf(color));
    micIcon.setImageResource((active && !live) ? R.drawable.ic_mic_off : R.drawable.ic_mic);

    if (holdMode)
      hintLabel.setText(R.string.hint_hold);
    else
      hintLabel.setText(live ? R.string.hint_toggle_stop : R.string.hint_toggle);

    levelBar.setProgress(active ? Math.round(AudioEchoService.inputLevel * 100.0f) : 0);
    stopButton.setVisibility(active && holdMode ? View.VISIBLE : View.GONE);
  }
}
