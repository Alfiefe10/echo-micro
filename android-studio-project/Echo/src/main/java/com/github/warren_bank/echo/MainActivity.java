package com.github.warren_bank.echo;

import android.Manifest.permission;
import android.app.Activity;
import android.app.AlertDialog;
import android.app.LocaleManager;
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
import android.os.LocaleList;
import android.os.Looper;
import android.preference.PreferenceManager;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowInsets;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.Spinner;
import android.widget.Switch;
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

  private interface OnSegmentSelected {
    void onSelected(int index);
  }

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
  private TextView gainValue;
  private SeekBar gainBar;
  private LinearLayout advancedPanel;
  private ImageView advancedArrow;
  private TextView advancedInfo;
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

    outputCard     = findViewById(R.id.outputCard);
    outputIcon     = findViewById(R.id.outputIcon);
    outputTitle    = findViewById(R.id.outputTitle);
    outputSubtitle = findViewById(R.id.outputSubtitle);
    micButton      = findViewById(R.id.micButton);
    statusLabel    = findViewById(R.id.statusLabel);
    hintLabel      = findViewById(R.id.hintLabel);
    gainValue      = findViewById(R.id.gainValue);
    gainBar        = findViewById(R.id.gainBar);
    advancedPanel  = findViewById(R.id.advancedPanel);
    advancedArrow  = findViewById(R.id.advancedArrow);
    advancedInfo   = findViewById(R.id.advancedInfo);
    sourceSpinner  = findViewById(R.id.sourceSpinner);
    stopButton     = findViewById(R.id.stopButton);

    int secondary = getColor(R.color.text_secondary);
    ((ImageView) findViewById(R.id.volumeIcon)).setImageTintList(ColorStateList.valueOf(secondary));
    advancedArrow.setImageTintList(ColorStateList.valueOf(secondary));

    migrateSettings();
    AudioEchoService.loadSettings(this);
    applySystemBarInsets();

    holdMode = prefs.getBoolean(PREF_HOLD_MODE, false);
    setupSegment(R.id.modeSegment, new int[]{R.string.mode_toggle, R.string.mode_hold},
      holdMode ? 1 : 0, index -> setHoldMode(index == 1));
    setupSegment(R.id.noiseSegment, new int[]{R.string.level_off, R.string.level_light, R.string.level_strong},
      currentNoiseLevel(), this::setNoiseLevel);
    setupSegment(R.id.howlSegment, new int[]{R.string.level_off, R.string.level_normal, R.string.level_strong},
      currentHowlLevel(), this::setHowlLevel);

    setupGain();
    setupSource();
    applyButtonMode();

    bindSwitch(R.id.guardSwitch,    AudioEchoService.PREF_GUARD_SPEAKER, true, false);
    bindSwitch(R.id.highPassSwitch, AudioEchoService.PREF_HIGH_PASS,     true, false);
    bindSwitch(R.id.limiterSwitch,  AudioEchoService.PREF_LIMITER,       true, false);
    bindSwitch(R.id.micDirSwitch,   AudioEchoService.PREF_MIC_DIRECTION, true, true);
    bindSwitch(R.id.shiftSwitch,    AudioEchoService.PREF_SHIFT,         false, false);
    setupLanguage();

    findViewById(R.id.advancedHeader).setOnClickListener(v -> {
      boolean open = advancedPanel.getVisibility() != View.VISIBLE;
      advancedPanel.setVisibility(open ? View.VISIBLE : View.GONE);
      advancedArrow.animate().rotation(open ? 180f : 0f).setDuration(150).start();
    });

    stopButton.setOnClickListener(v -> stopEcho());
    findViewById(R.id.dacButton).setOnClickListener(v -> showDacCheck());

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
  // Segmented controls

  private void setupSegment(int containerId, int[] labels, int selected, OnSegmentSelected listener) {
    LinearLayout container = findViewById(containerId);
    container.removeAllViews();
    for (int i = 0; i < labels.length; i++) {
      TextView item = new TextView(this);
      item.setText(labels[i]);
      item.setGravity(Gravity.CENTER);
      item.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
      item.setTextColor(getColorStateList(R.color.seg_text));
      item.setBackgroundResource(R.drawable.seg_item);
      item.setSelected(i == selected);
      item.setClickable(true);
      final int index = i;
      item.setOnClickListener(v -> {
        for (int k = 0; k < container.getChildCount(); k++)
          container.getChildAt(k).setSelected(k == index);
        listener.onSelected(index);
      });
      container.addView(item, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1));
    }
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
    updateUi();
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

  // targetSdk 35 draws behind the status and navigation bars: pad the content
  private void applySystemBarInsets() {
    View content = findViewById(R.id.content);
    final int left = content.getPaddingLeft(), top = content.getPaddingTop();
    final int right = content.getPaddingRight(), bottom = content.getPaddingBottom();
    content.setOnApplyWindowInsetsListener((v, insets) -> {
      int insetTop, insetBottom;
      if (Build.VERSION.SDK_INT >= 30) {
        android.graphics.Insets bars = insets.getInsets(WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
        insetTop = bars.top;
        insetBottom = bars.bottom;
      }
      else {
        insetTop = insets.getSystemWindowInsetTop();
        insetBottom = insets.getSystemWindowInsetBottom();
      }
      v.setPadding(left, top + insetTop, right, bottom + insetBottom);
      return insets;
    });
    content.requestApplyInsets();
  }

  // Per-app language (Android 13+): the same choice as in the system settings
  private void setupLanguage() {
    View row = findViewById(R.id.languageRow);
    if (Build.VERSION.SDK_INT < 33) {
      row.setVisibility(View.GONE);
      return;
    }
    LocaleManager localeManager = getSystemService(LocaleManager.class);
    String[] tags = {"", "es", "en"};
    LocaleList current = localeManager.getApplicationLocales();
    int selected = 0;
    if (!current.isEmpty()) {
      String language = current.get(0).getLanguage();
      for (int i = 1; i < tags.length; i++)
        if (tags[i].equals(language)) selected = i;
    }
    setupSegment(R.id.languageSegment, new int[]{R.string.language_system, R.string.language_es, R.string.language_en},
      selected, index -> localeManager.setApplicationLocales(
        index == 0 ? LocaleList.getEmptyLocaleList() : LocaleList.forLanguageTags(tags[index])));
  }

  // ---------------------------------------------------------------------------
  // Controls

  private void setupGain() {
    int percent = Math.round(prefs.getFloat(AudioEchoService.PREF_AUDIO_GAIN, 1.0f) * 100.0f);
    gainBar.setMax(Math.round(AudioEchoService.audioGainMax * 100.0f));
    gainBar.setProgress(percent);
    gainValue.setText(percent + " %");

    gainBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
      @Override
      public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
        gainValue.setText(progress + " %");
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
  // Output detection

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

  // Android only lists USB audio devices that have their own DAC; a passive
  // USB-C to jack adapter shows up as an analog headset, or not at all on
  // phones without analog audio over USB-C.
  private static boolean isUsbDac(int type) {
    return type == AudioDeviceInfo.TYPE_USB_HEADSET
        || type == AudioDeviceInfo.TYPE_USB_DEVICE
        || type == AudioDeviceInfo.TYPE_USB_ACCESSORY;
  }

  private void showDacCheck() {
    AudioDeviceInfo usb = null, analog = null;
    for (AudioDeviceInfo device : audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)) {
      int type = device.getType();
      if (isUsbDac(type) && usb == null) usb = device;
      else if (isWired(type) && analog == null) analog = device;
    }

    String message;
    if (usb != null) {
      int[] rates = usb.getSampleRates();
      StringBuilder list = new StringBuilder();
      for (int rate : rates) {
        if (list.length() > 0) list.append(", ");
        list.append(rate / 1000.0).append(" kHz");
      }
      String rateText = (list.length() > 0) ? list.toString() : getString(R.string.dac_unknown_rates);
      message = getString(R.string.dac_usb, deviceName(usb), rateText);
    }
    else if (analog != null) {
      message = getString(R.string.dac_analog, deviceName(analog));
    }
    else {
      message = getString(R.string.dac_none);
    }

    new AlertDialog.Builder(this)
      .setTitle(R.string.dac_title)
      .setMessage(message)
      .setPositiveButton(android.R.string.ok, null)
      .show();
  }

  private String deviceName(AudioDeviceInfo device) {
    CharSequence name = device.getProductName();
    String text = (name == null) ? "" : name.toString().trim();
    return text.isEmpty() ? getString(R.string.output_wired_generic) : text;
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

    updateOutputCard();

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

    String info = getString(R.string.advanced_info);
    if (AudioEchoService.denoise && !AudioEchoService.denoiseUsable)
      info += "\n\n" + getString(R.string.denoise_unavailable);
    advancedInfo.setText(info);

    stopButton.setVisibility(active && holdMode ? View.VISIBLE : View.GONE);
  }
}
