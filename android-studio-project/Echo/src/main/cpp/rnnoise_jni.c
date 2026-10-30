#include <jni.h>
#include <stdint.h>
#include "rnnoise.h"

// Bridge for com.github.warren_bank.echo.dsp.RnNoise.
// RNNoise works on 480-sample frames at 48 kHz, with floats in 16-bit scale.

JNIEXPORT jlong JNICALL
Java_com_github_warren_1bank_echo_dsp_RnNoise_nativeCreate(JNIEnv *env, jclass cls) {
  return (jlong) (intptr_t) rnnoise_create(NULL);
}

JNIEXPORT void JNICALL
Java_com_github_warren_1bank_echo_dsp_RnNoise_nativeDestroy(JNIEnv *env, jclass cls, jlong state) {
  if (state) rnnoise_destroy((DenoiseState *) (intptr_t) state);
}

JNIEXPORT jint JNICALL
Java_com_github_warren_1bank_echo_dsp_RnNoise_nativeFrameSize(JNIEnv *env, jclass cls) {
  return rnnoise_get_frame_size();
}

// In place; returns the voice probability of the frame (0..1).
JNIEXPORT jfloat JNICALL
Java_com_github_warren_1bank_echo_dsp_RnNoise_nativeProcess(JNIEnv *env, jclass cls, jlong state, jfloatArray frame) {
  jfloat *data = (*env)->GetPrimitiveArrayCritical(env, frame, NULL);
  if (data == NULL) return 0.0f;
  float vad = rnnoise_process_frame((DenoiseState *) (intptr_t) state, data, data);
  (*env)->ReleasePrimitiveArrayCritical(env, frame, data, 0);
  return vad;
}
