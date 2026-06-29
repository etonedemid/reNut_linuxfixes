
// renut - ReXGlue Recompiled Project
//
// This file is yours to edit. 'rexglue migrate' will NOT overwrite it.

#include "generated/renut_init.h"

#include "renut_app.h"

REX_DEFINE_APP(renut, RenutApp::Create)

#if defined(__ANDROID__)
// Expose the game frame rate (computed by fpsCount_hook) to the Android side
// menu's FPS/stats overlay. Lives here because fpsCount is a renut-app global.
#include <jni.h>

#include "renut_engine/globals.h"

extern "C" JNIEXPORT jint JNICALL
Java_com_rexglue_renut_RenutActivity_nativeGetFps(JNIEnv*, jobject) {
  return static_cast<jint>(fpsCount + 0.5);
}
#endif
