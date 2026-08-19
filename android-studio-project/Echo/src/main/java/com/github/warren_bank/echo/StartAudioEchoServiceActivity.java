package com.github.warren_bank.echo;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.Manifest.permission;
import android.os.Build;
import android.os.Bundle;

public class StartAudioEchoServiceActivity extends Activity {
  private static int REQUEST_CODE_PERMISSIONS;

  @Override
  protected void onCreate(Bundle savedInstanceState) {
    super.onCreate(savedInstanceState);

    if (Build.VERSION.SDK_INT < 23)
      startAudioEchoService();
    else
      requestPermissions();
  }

  @Override
  public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
    super.onRequestPermissionsResult(requestCode, permissions, grantResults);

    if (requestCode == REQUEST_CODE_PERMISSIONS) {
      if ((grantResults == null) || (grantResults.length == 0) || (grantResults[0] != PackageManager.PERMISSION_GRANTED))
        requestPermissions();
      else
        startAudioEchoService();
    }
  }

  private void requestPermissions() {
    requestPermissions(new String[]{permission.RECORD_AUDIO}, REQUEST_CODE_PERMISSIONS);
  }

  private void startAudioEchoService() {
    Context context = getApplicationContext();

    Intent intent = new Intent(context, AudioEchoService.class);
    if (Build.VERSION.SDK_INT < 26)
      context.startService(intent);
    else
      context.startForegroundService(intent);
    finish();
  }
}
