package org.libsdl.app;

/**
 * Minimal SDLInputConnection companion.
 *
 * SDL3's JNI_OnLoad calls RegisterNatives for org/libsdl/app/SDLInputConnection,
 * so the class must exist and declare every native in SDLInputConnection_tab or
 * the registration aborts the process. reNut does not use SDL's soft-keyboard
 * text input, so these are declarations only.
 */
public class SDLInputConnection {
    public static native void nativeCommitText(String a0, int a1);
    public static native void nativeGenerateScancodeForUnichar(char a0);
}
