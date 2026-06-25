package org.libsdl.app;

/**
 * Minimal HIDDeviceManager companion.
 *
 * SDL3's JNI_OnLoad calls RegisterNatives for org/libsdl/app/HIDDeviceManager
 * unconditionally (even with SDL_HIDAPI=OFF), so the class must exist and declare
 * every native in HIDDeviceManager_tab or the registration aborts the process.
 * reNut does not use SDL HIDAPI, so these are declarations only.
 */
public class HIDDeviceManager {
    public static native void HIDDeviceRegisterCallback();
    public static native void HIDDeviceReleaseCallback();
    public static native void HIDDeviceConnected(int a0, String a1, int a2, int a3, String a4, int a5, String a6, String a7, int a8, int a9, int a10, int a11, boolean a12);
    public static native void HIDDeviceOpenPending(int a0);
    public static native void HIDDeviceOpenResult(int a0, boolean a1);
    public static native void HIDDeviceDisconnected(int a0);
    public static native void HIDDeviceInputReport(int a0, byte[] a1);
    public static native void HIDDeviceReportResponse(int a0, byte[] a1);
}
