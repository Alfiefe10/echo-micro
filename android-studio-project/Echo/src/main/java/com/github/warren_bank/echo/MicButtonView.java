package com.github.warren_bank.echo;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.drawable.Drawable;
import android.util.AttributeSet;
import android.view.View;

// Round microphone button with a ring that grows with the input level.
public class MicButtonView extends View {
  public static final int STATE_IDLE    = 0;
  public static final int STATE_LIVE    = 1;
  public static final int STATE_MUTED   = 2;
  public static final int STATE_BLOCKED = 3;

  private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
  private final Paint ring = new Paint(Paint.ANTI_ALIAS_FLAG);
  private Drawable micIcon;
  private Drawable micOffIcon;
  private int state = STATE_IDLE;
  private float level = 0.0f;
  private float pressScale = 1.0f;

  public MicButtonView(Context context, AttributeSet attrs) {
    super(context, attrs);
    micIcon    = context.getDrawable(R.drawable.ic_mic);
    micOffIcon = context.getDrawable(R.drawable.ic_mic_off);
    fill.setStyle(Paint.Style.FILL);
    ring.setStyle(Paint.Style.FILL);
    setClickable(true);
    setFocusable(true);
  }

  public void setState(int state) {
    if (this.state == state) return;
    this.state = state;
    invalidate();
  }

  public void setLevel(float level) {
    // Smooth the fall so the ring does not flicker
    float target = Math.max(0.0f, Math.min(1.0f, level));
    this.level = (target > this.level) ? target : this.level * 0.8f + target * 0.2f;
    invalidate();
  }

  public void setHeld(boolean pressed) {
    pressScale = pressed ? 1.06f : 1.0f;
    invalidate();
  }

  private int stateColor() {
    switch (state) {
      case STATE_LIVE:    return getContext().getColor(R.color.mic_live);
      case STATE_MUTED:   return getContext().getColor(R.color.mic_muted);
      case STATE_BLOCKED: return getContext().getColor(R.color.mic_blocked);
      default:            return getContext().getColor(R.color.mic_idle);
    }
  }

  @Override
  protected void onDraw(Canvas canvas) {
    float cx = getWidth() / 2.0f, cy = getHeight() / 2.0f;
    float max = Math.min(cx, cy);
    float radius = max * 0.72f * pressScale;
    int color = stateColor();

    // Level ring: only while sound is going out
    if (state == STATE_LIVE) {
      // Perceived loudness: map roughly -50..0 dBFS to 0..1
      float db = (level > 0) ? (float) (20 * Math.log10(level)) : -100f;
      float amount = Math.max(0f, Math.min(1f, (db + 50f) / 50f));
      float ringRadius = radius + (max - radius) * amount;
      ring.setColor(color);
      ring.setAlpha(70);
      canvas.drawCircle(cx, cy, ringRadius, ring);
    }

    fill.setColor(color);
    canvas.drawCircle(cx, cy, radius, fill);

    Drawable icon = (state == STATE_MUTED || state == STATE_BLOCKED) ? micOffIcon : micIcon;
    int half = (int) (radius * 0.45f);
    icon.setBounds((int) cx - half, (int) cy - half, (int) cx + half, (int) cy + half);
    icon.draw(canvas);
  }
}
