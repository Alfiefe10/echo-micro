package com.github.warren_bank.echo;

import android.Manifest.permission;
import android.app.Activity;
import android.app.AlertDialog;
import android.app.LocaleManager;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.res.ColorStateList;
import android.media.AudioDeviceInfo;
import android.media.AudioManager;
import android.media.MediaRecorder;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.LocaleList;
import android.preference.PreferenceManager;
import android.view.View;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.ImageView;
import android.widget.Spinner;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;

// Settings that are changed rarely, grouped by topic.
public class SettingsActivity extends Activity {
  private static final String PROJECT_URL = "https://github.com/Alfiefe10/echo-micro";
  private static final int REQUEST_CODE_MIC = 2;

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
  private LatencyProbe latencyProbe;
  private AlertDialog latencyDialog;

  @Override
  protected void onCreate(Bundle savedInstanceState) {
    super.onCreate(savedInstanceState);
    setContentView(R.layout.activity_settings);
    prefs = PreferenceManager.getDefaultSharedPreferences(this);
    Ui.applySystemBarInsets(findViewById(R.id.content));

    ImageView back = findViewById(R.id.backButton);
    back.setImageTintList(ColorStateList.valueOf(getColor(R.color.text_primary)));
    back.setOnClickListener(v -> finish());

    // Output
    Ui.bindSwitch(this, prefs, R.id.guardSwitch, AudioEchoService.PREF_GUARD_SPEAKER, true, null);
    Ui.bindSwitch(this, prefs, R.id.perOutputSwitch, OutputProfiles.PREF_PER_OUTPUT, true, null);
    findViewById(R.id.latencyRow).setOnClickListener(v -> runLatencyTest());
    findViewById(R.id.dacRow).setOnClickListener(v -> showDacCheck());

    // Microphone (read when the mic opens: restart if running)
    setupSource();
    Ui.bindSwitch(this, prefs, R.id.micDirSwitch, AudioEchoService.PREF_MIC_DIRECTION, true, checked -> restartIfActive());
    Ui.setupSegment(this, R.id.gateSegment, new int[]{R.string.level_off, R.string.level_light, R.string.level_strong},
      prefs.getInt(AudioEchoService.PREF_GATE, 0), index -> {
        prefs.edit().putInt(AudioEchoService.PREF_GATE, index).apply();
        AudioEchoService.loadSettings(this);
      });

    // Processing
    Ui.bindSwitch(this, prefs, R.id.highPassSwitch, AudioEchoService.PREF_HIGH_PASS, true, null);
    Ui.bindSwitch(this, prefs, R.id.limiterSwitch, AudioEchoService.PREF_LIMITER, true, null);

    // Experimental
    Ui.bindSwitch(this, prefs, R.id.agcSwitch, AudioEchoService.PREF_AGC, false, null);
    Ui.bindSwitch(this, prefs, R.id.shiftSwitch, AudioEchoService.PREF_SHIFT, false, null);

    setupLanguage();

    // About
    String version;
    try {
      version = getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
    }
    catch (PackageManager.NameNotFoundException e) {
      version = "";
    }
    ((TextView) findViewById(R.id.versionLabel)).setText(getString(R.string.about_version, version));
    findViewById(R.id.aboutRow).setOnClickListener(v -> {
      try {
        startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(PROJECT_URL)));
      }
      catch (Exception ignore) {}
    });
  }

  @Override
  protected void onDestroy() {
    if (latencyProbe != null) latencyProbe.cancel();
    super.onDestroy();
  }

  private void restartIfActive() {
    if (!AudioEchoService.isActive) return;
    boolean muted = AudioEchoService.isMuted;
    startService(new Intent(this, AudioEchoService.class).setAction(AudioEchoService.ACTION_EXIT));
    AudioEchoService.isActive = false;
    findViewById(R.id.content).postDelayed(() -> {
      AudioEchoService.isMuted = muted;
      startForegroundService(new Intent(this, AudioEchoService.class));
    }, 300);
  }

  private void setupSource() {
    Spinner spinner = findViewById(R.id.sourceSpinner);
    List<String> labels = new ArrayList<>();
    for (int label : SOURCE_LABELS)
      labels.add(getString(label));

    ArrayAdapter<String> adapter = new ArrayAdapter<>(this, android.R.layout.simple_spinner_item, labels);
    adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
    spinner.setAdapter(adapter);

    int current = prefs.getInt(AudioEchoService.PREF_AUDIO_SOURCE, MediaRecorder.AudioSource.MIC);
    for (int i = 0; i < SOURCES.length; i++) {
      if (SOURCES[i] == current) spinner.setSelection(i, false);
    }

    spinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
      @Override
      public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
        int source = SOURCES[position];
        if (source == prefs.getInt(AudioEchoService.PREF_AUDIO_SOURCE, MediaRecorder.AudioSource.MIC)) return;
        prefs.edit().putInt(AudioEchoService.PREF_AUDIO_SOURCE, source).apply();
        restartIfActive();
      }

      @Override public void onNothingSelected(AdapterView<?> parent) {}
    });
  }

  // Per-app language (Android 13+): the same choice as in the system settings
  private void setupLanguage() {
    if (Build.VERSION.SDK_INT < 33) {
      findViewById(R.id.languageSection).setVisibility(View.GONE);
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
    Ui.setupSegment(this, R.id.languageSegment, new int[]{R.string.language_system, R.string.language_es, R.string.language_en},
      selected, index -> localeManager.setApplicationLocales(
        index == 0 ? LocaleList.getEmptyLocaleList() : LocaleList.forLanguageTags(tags[index])));
  }

  // ---------------------------------------------------------------------------
  // Delay and feedback test

  private void runLatencyTest() {
    if (checkSelfPermission(permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
      requestPermissions(new String[]{permission.RECORD_AUDIO}, REQUEST_CODE_MIC);
      return;
    }
    // The test needs the microphone and the speaker for itself
    if (AudioEchoService.isActive) {
      startService(new Intent(this, AudioEchoService.class).setAction(AudioEchoService.ACTION_EXIT));
      AudioEchoService.isActive = false;
    }

    latencyDialog = new AlertDialog.Builder(this)
      .setTitle(R.string.latency_test)
      .setMessage(R.string.latency_running)
      .setNegativeButton(android.R.string.cancel, (d, w) -> {
        if (latencyProbe != null) latencyProbe.cancel();
      })
      .setCancelable(false)
      .show();

    // Give the service a moment to release the microphone
    findViewById(R.id.content).postDelayed(() -> {
      latencyProbe = new LatencyProbe(this);
      latencyProbe.start(new LatencyProbe.Listener() {
        @Override
        public void onResult(LatencyProbe.Result result) {
          showLatencyResult(result);
        }

        @Override
        public void onError(String message) {
          showLatencyMessage(getString(R.string.latency_error, message));
        }
      });
    }, 400);
  }

  private void showLatencyResult(LatencyProbe.Result result) {
    if (!result.reliable) {
      showLatencyMessage(getString(R.string.latency_unreliable));
      return;
    }
    int advice;
    if (result.feedbackDb < -25) advice = R.string.latency_advice_low;
    else if (result.feedbackDb < -12) advice = R.string.latency_advice_mid;
    else advice = R.string.latency_advice_high;

    String text = getString(R.string.latency_result, Math.round(result.delayMs), result.feedbackDb)
      + "\n\n" + getString(advice);
    ((TextView) findViewById(R.id.latencyHint)).setText(text);
    showLatencyMessage(text);
  }

  private void showLatencyMessage(String text) {
    if (latencyDialog != null) latencyDialog.dismiss();
    latencyProbe = null;
    if (isFinishing()) return;
    new AlertDialog.Builder(this)
      .setTitle(R.string.latency_test)
      .setMessage(text)
      .setPositiveButton(android.R.string.ok, null)
      .show();
  }

  @Override
  public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
    super.onRequestPermissionsResult(requestCode, permissions, grantResults);
    if (requestCode == REQUEST_CODE_MIC
        && checkSelfPermission(permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED)
      runLatencyTest();
  }

  // ---------------------------------------------------------------------------
  // DAC check

  private void showDacCheck() {
    AudioManager audioManager = getSystemService(AudioManager.class);
    AudioDeviceInfo usb = null, analog = null;
    for (AudioDeviceInfo device : audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)) {
      int type = device.getType();
      if (MainActivity.isUsbDac(type) && usb == null) usb = device;
      else if (MainActivity.isWired(type) && analog == null) analog = device;
    }

    String message;
    if (usb != null) {
      StringBuilder list = new StringBuilder();
      for (int rate : usb.getSampleRates()) {
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
}
