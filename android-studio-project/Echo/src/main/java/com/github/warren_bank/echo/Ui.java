package com.github.warren_bank.echo;

import android.app.Activity;
import android.content.SharedPreferences;
import android.os.Build;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.WindowInsets;
import android.widget.LinearLayout;
import android.widget.Switch;
import android.widget.TextView;

// Small view helpers shared by the main and settings screens.
final class Ui {
  interface OnSegmentSelected {
    void onSelected(int index);
  }

  interface OnSwitchChanged {
    void onChanged(boolean checked);
  }

  private Ui() {}

  // Fills a container with equal-width pill buttons; one is selected at a time.
  static void setupSegment(Activity activity, int containerId, int[] labels, int selected, OnSegmentSelected listener) {
    LinearLayout container = activity.findViewById(containerId);
    container.removeAllViews();
    for (int i = 0; i < labels.length; i++) {
      TextView item = new TextView(activity);
      item.setText(labels[i]);
      item.setGravity(Gravity.CENTER);
      item.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
      item.setTextColor(activity.getColorStateList(R.color.seg_text));
      item.setBackgroundResource(R.drawable.seg_item);
      item.setSelected(i == selected);
      item.setClickable(true);
      final int index = i;
      item.setOnClickListener(v -> {
        selectSegment(container, index);
        listener.onSelected(index);
      });
      container.addView(item, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1));
    }
  }

  static void selectSegment(Activity activity, int containerId, int index) {
    selectSegment((LinearLayout) activity.findViewById(containerId), index);
  }

  private static void selectSegment(LinearLayout container, int index) {
    for (int k = 0; k < container.getChildCount(); k++)
      container.getChildAt(k).setSelected(k == index);
  }

  static void bindSwitch(Activity activity, SharedPreferences prefs, int id, String pref, boolean byDefault, OnSwitchChanged listener) {
    Switch view = activity.findViewById(id);
    view.setChecked(prefs.getBoolean(pref, byDefault));
    view.setOnCheckedChangeListener((button, checked) -> {
      prefs.edit().putBoolean(pref, checked).apply();
      AudioEchoService.loadSettings(activity);
      if (listener != null) listener.onChanged(checked);
    });
  }

  // targetSdk 35 draws behind the status and navigation bars: pad the content
  static void applySystemBarInsets(View content) {
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
}
