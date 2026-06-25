package org.libsdl.app;

import android.app.Activity;
import android.view.Surface;

/**
 * Minimal SDLActivity stub.
 *
 * SDL3's native JNI layer (SDL_android.c) looks up static methods on a class
 * named "org/libsdl/app/SDLActivity" and caches them via nativeSetupJNI().
 * We don't use SDLActivity as our activity, but the native joystick / gamepad
 * subsystem still dereferences the cached class pointer (mActivityClass) when
 * it needs the Android Context (e.g. for internal-storage path lookups).
 *
 * Only the methods actually *called* at runtime need real implementations;
 * everything else can be a no-op / null-return so that GetStaticMethodID
 * succeeds and the jmethodID cache is populated.
 */
public class SDLActivity {

    private static Activity mActivity;

    /** Call once from your real Activity.onCreate() to wire things up. */
    public static void setActivity(Activity activity) {
        mActivity = activity;
    }

    // ── Called by SDL_android.c nativeSetupJNI() ──────────────────────
    public static native void nativeSetupJNI();

    // ── The rest of SDLActivity_tab: SDL's JNI_OnLoad calls RegisterNatives
    //    for ALL of these, so every one must be declared or the registration
    //    aborts the process. We do not call them ourselves (our RenutActivity
    //    drives the engine), they exist only to satisfy RegisterNatives.
    public static native String nativeGetVersion();
    public static native void nativeInitMainThread();
    public static native void nativeCleanupMainThread();
    public static native int nativeRunMain(String a0, String a1, Object a2);
    public static native void onNativeDropFile(String a0);
    public static native void nativeSetScreenResolution(int a0, int a1, int a2, int a3, float a4, float a5);
    public static native void onNativeResize();
    public static native void onNativeSurfaceCreated();
    public static native void onNativeSurfaceChanged();
    public static native void onNativeSurfaceDestroyed();
    public static native void onNativeScreenKeyboardShown();
    public static native void onNativeScreenKeyboardHidden();
    public static native void onNativeKeyDown(int a0);
    public static native void onNativeKeyUp(int a0);
    public static native boolean onNativeSoftReturnKey();
    public static native void onNativeKeyboardFocusLost();
    public static native void onNativeTouch(int a0, int a1, int a2, float a3, float a4, float a5);
    public static native void onNativePinchStart();
    public static native void onNativePinchUpdate(float a0);
    public static native void onNativePinchEnd();
    public static native void onNativeMouse(int a0, int a1, float a2, float a3, boolean a4);
    public static native void onNativePen(int a0, int a1, int a2, int a3, float a4, float a5, float a6);
    public static native void onNativeAccel(float a0, float a1, float a2);
    public static native void onNativeClipboardChanged();
    public static native void nativeLowMemory();
    public static native void onNativeLocaleChanged();
    public static native void onNativeDarkModeChanged(boolean a0);
    public static native void nativeSendQuit();
    public static native void nativeQuit();
    public static native void nativePause();
    public static native void nativeResume();
    public static native void nativeFocusChanged(boolean a0);
    public static native String nativeGetHint(String a0);
    public static native boolean nativeGetHintBoolean(String a0, boolean a1);
    public static native void nativeSetenv(String a0, String a1);
    public static native void nativeSetNaturalOrientation(int a0);
    public static native void onNativeRotationChanged(int a0);
    public static native void onNativeInsetsChanged(int a0, int a1, int a2, int a3);
    public static native void nativeAddTouch(int a0, String a1);
    public static native void nativePermissionResult(int a0, boolean a1);
    public static native boolean nativeAllowRecreateActivity();
    public static native int nativeCheckSDLThreadCounter();
    public static native void onNativeFileDialog(int a0, String[] a1, int a2);

    // ── Methods looked up by GetStaticMethodID ────────────────────────

    public static Activity getContext() {
        return mActivity;
    }

    public static Surface getNativeSurface() {
        return null;            // we manage the surface ourselves
    }

    public static boolean getManifestEnvironmentVariables() {
        return false;
    }

    public static void initTouch() {}

    public static boolean isAndroidTV() { return false; }
    public static boolean isChromebook() { return false; }
    public static boolean isDeXMode() { return false; }
    public static boolean isTablet() { return false; }

    public static String clipboardGetText() { return ""; }
    public static boolean clipboardHasText() { return false; }
    public static void clipboardSetText(String text) {}

    public static int createCustomCursor(int[] colors, int w, int h,
                                          int hotX, int hotY) { return 0; }
    public static void destroyCustomCursor(int id) {}
    public static boolean setCustomCursor(int id) { return false; }
    public static boolean setSystemCursor(int id) { return false; }

    public static void manualBackButton() {}
    public static void minimizeWindow() {}
    public static boolean openURL(String url) { return false; }
    public static void requestPermission(String perm, int reqCode) {}
    public static boolean showToast(String msg, int dur, int grav,
                                     int xOff, int yOff) { return false; }
    public static boolean sendMessage(int cmd, int param) { return false; }
    public static boolean setActivityTitle(String title) { return false; }
    public static void setOrientation(int w, int h, boolean resizable,
                                       String hint) {}
    public static boolean setRelativeMouseEnabled(boolean enabled) { return false; }
    public static void setWindowStyle(boolean fullscreen) {}
    public static boolean shouldMinimizeOnFocusLoss() { return false; }
    public static boolean showTextInput(int x, int y, int w, int h,
                                         int type) { return false; }
    public static boolean supportsRelativeMouse() { return false; }
    public static int openFileDescriptor(String path, String mode) { return -1; }
    public static boolean showFileDialog(String[] filters, boolean allowAll,
                                          boolean multiSelect, int mode) { return false; }
    public static String getPreferredLocales() { return "en"; }
}
