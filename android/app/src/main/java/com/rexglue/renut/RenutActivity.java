package com.rexglue.renut;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.os.ParcelFileDescriptor;
import android.provider.DocumentsContract;
import android.provider.Settings;
import android.view.Choreographer;
import android.view.Gravity;
import android.view.InputDevice;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.Surface;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import org.libsdl.app.SDLControllerManager;

import java.io.File;

/**
 * Main activity for reNut on Android.
 *
 * Shows a main menu where the user selects the game library folder (the
 * unzipped assets directory containing default.xex, Bundle/, Debug/, etc.).
 * Once selected, "PLAY" launches the native engine.
 */
public class RenutActivity extends Activity
        implements SurfaceHolder.Callback, Choreographer.FrameCallback {

    private static final int REQ_PICK_FOLDER   = 1001;
    private static final int REQ_MANAGE_STORAGE = 1002;
    private static final int REQ_PICK_ISO       = 1003;
    private static final int REQ_PICK_DRIVER    = 1004;
    private static final String PREFS_NAME      = "renut_prefs";
    private static final String PREF_GAME_DIR   = "game_directory";
    private static final String PREF_DRIVER_NAME = "gpu_driver_name";
    private static final String PREF_DRIVER_DIR  = "gpu_driver_dir";

    // Colour palette
    private static final int C_BG       = 0xFF0d1117;
    private static final int C_CARD     = 0xFF161b22;
    private static final int C_ACCENT   = 0xFFFFFFFF;
    private static final int C_TEXT     = 0xFFf0f6fc;
    private static final int C_MUTED    = 0xFF8b949e;
    private static final int C_ERR      = 0xFFff6b6b;
    private static final int C_BTN_DIS  = 0xFF21262d;

    static {
        System.loadLibrary("renut");
    }

    // --- Native methods ---
    private native boolean nativeInit(Surface surface, String appId, String gameDir, String filesDir);
    private native boolean nativeExtractIso(String isoPath, String destDir);
    private native void nativeConfigureGpuDriver(String nativeLibDir, String tmpDir,
                                                 String driverDir, String driverName);
    private native void nativeSurfaceChanged(int width, int height);
    private native void nativeSurfaceDestroyed();
    private native void nativePumpEvents();
    private native void nativeShutdown();

    private boolean mInitialised = false;
    private boolean mRunning     = false;
    private String  mGameDir     = null;

    // Views
    private FrameLayout  mRoot;
    private ScrollView   mMenuScroll;
    private SurfaceView  mSurface;
    private TextView     mPathText;
    private Button       mPlayBtn;
    private View         mPermCard;   // shown when MANAGE_EXTERNAL_STORAGE not granted

    // -----------------------------------------------------------------------
    // Activity lifecycle
    // -----------------------------------------------------------------------

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        mGameDir = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
                       .getString(PREF_GAME_DIR, null);

        org.libsdl.app.SDL.setContext(this);
        org.libsdl.app.SDLActivity.setActivity(this);
        org.libsdl.app.SDLActivity.nativeSetupJNI();
        org.libsdl.app.SDLAudioManager.setContext(this);
        org.libsdl.app.SDLAudioManager.initialize();
        org.libsdl.app.SDLAudioManager.nativeSetupJNI();
        SDLControllerManager.nativeSetupJNI();
        SDLControllerManager.initialize();

        buildUI();
        setContentView(mRoot);
        enterImmersive();
    }

    @Override
    protected void onResume() {
        super.onResume();
        enterImmersive();
        refreshPermCard();
        refreshPlayButton();
        if (mInitialised) {
            mRunning = true;
            Choreographer.getInstance().postFrameCallback(this);
        }
    }

    @Override
    protected void onPause() {
        mRunning = false;
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        mRunning = false;
        if (mInitialised) {
            nativeShutdown();
            mInitialised = false;
        }
        super.onDestroy();
    }

    @Override
    public void onBackPressed() {
        if (mSurface.getVisibility() == View.VISIBLE) {
            // Return to menu (keep native state for now — re-launch will re-init)
            mRunning = false;
            showMenu();
        } else {
            super.onBackPressed();
        }
    }

    // -----------------------------------------------------------------------
    // UI construction
    // -----------------------------------------------------------------------

    private void buildUI() {
        mRoot = new FrameLayout(this);
        mRoot.setBackgroundColor(C_BG);

        // Game surface (hidden until "PLAY")
        mSurface = new SurfaceView(this);
        mSurface.getHolder().addCallback(this);
        mSurface.setVisibility(View.GONE);
        mRoot.addView(mSurface, matchParent());

        // Main menu
        mMenuScroll = buildMenu();
        mRoot.addView(mMenuScroll, matchParent());
    }

    private ScrollView buildMenu() {
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);

        // Background image from assets/bg.jpg
        try {
            Bitmap bg = BitmapFactory.decodeStream(getAssets().open("bg.jpg"));
            if (bg != null) {
                BitmapDrawable bgDrawable = new BitmapDrawable(getResources(), bg);
                bgDrawable.setGravity(Gravity.FILL);
                scroll.setBackground(bgDrawable);
            } else {
                scroll.setBackgroundColor(C_BG);
            }
        } catch (Exception e) {
            scroll.setBackgroundColor(C_BG);
        }

        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setGravity(Gravity.CENTER_HORIZONTAL);
        col.setPaddingRelative(dp(24), dp(56), dp(24), dp(40));

        // ── Logo / title area ──────────────────────────────────────────────
        TextView logo = new TextView(this);
        logo.setText("reNut");
        logo.setTextSize(72);
        logo.setTypeface(Typeface.DEFAULT_BOLD);
        logo.setTextColor(C_ACCENT);
        logo.setGravity(Gravity.CENTER);
        col.addView(logo);

        TextView sub = new TextView(this);
        sub.setText("Also try grabbed by the ghoulies!");
        sub.setTextSize(16);
        sub.setTextColor(C_MUTED);
        sub.setGravity(Gravity.CENTER);

        LinearLayout.LayoutParams subLp = wrap();
        subLp.bottomMargin = dp(32);
        sub.setLayoutParams(subLp);
        col.addView(sub);

        // ── Files-access permission card (hidden if already granted) ──────
        mPermCard = buildPermissionCard();
        col.addView(mPermCard);

        // ── Library card ──────────────────────────────────────────────────
        col.addView(buildLibraryCard());

        // ── PLAY button ────────────────────────────────────────────────────
        mPlayBtn = new Button(this);
        mPlayBtn.setText("▶  PLAY");
        mPlayBtn.setTextSize(18);
        mPlayBtn.setTypeface(Typeface.DEFAULT_BOLD);
        mPlayBtn.setAllCaps(false);
        LinearLayout.LayoutParams playLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(58));
        playLp.topMargin = dp(16);
        playLp.bottomMargin = dp(8);
        mPlayBtn.setLayoutParams(playLp);
        mPlayBtn.setOnClickListener(v -> startGame());
        refreshPlayButton();
        col.addView(mPlayBtn);

        // ── Hint ───────────────────────────────────────────────────────────
        TextView hint = new TextView(this);
        hint.setText("Huge thanks to rexglue and renut");
        hint.setTextSize(12);
        hint.setTextColor(C_MUTED);
        hint.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams hintLp = wrap();
        hintLp.topMargin = dp(12);
        hint.setLayoutParams(hintLp);
        col.addView(hint);

        scroll.addView(col, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));
        return scroll;
    }

    private View buildLibraryCard() {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(18), dp(16), dp(18), dp(16));
        card.setBackgroundColor(C_CARD);
        GradientDrawable border = new GradientDrawable();
        border.setColor(C_CARD);
        border.setCornerRadius(dp(8));
        border.setStroke(dp(1), 0xFF30363d);
        card.setBackground(border);
        LinearLayout.LayoutParams cardLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        cardLp.bottomMargin = dp(8);
        card.setLayoutParams(cardLp);

        // Label row
        LinearLayout labelRow = new LinearLayout(this);
        labelRow.setOrientation(LinearLayout.HORIZONTAL);
        labelRow.setGravity(Gravity.CENTER_VERTICAL);

        TextView label = new TextView(this);
        label.setText("Game location");
        label.setTextSize(11);
        label.setTypeface(Typeface.DEFAULT_BOLD);
        label.setTextColor(C_ACCENT);
        label.setLetterSpacing(0.12f);
        LinearLayout.LayoutParams labelLp = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        label.setLayoutParams(labelLp);
        labelRow.addView(label);

        // Browse button (inline, compact)
        Button browse = new Button(this);
        browse.setText("Browse…");
        browse.setTextSize(13);
        browse.setTypeface(Typeface.DEFAULT_BOLD);
        browse.setTextColor(C_ACCENT);
        browse.setBackground(null);
        browse.setPadding(dp(4), dp(2), dp(4), dp(2));
        browse.setMinHeight(0);
        browse.setMinimumHeight(0);
        browse.setOnClickListener(v -> pickFolder());
        labelRow.addView(browse);

        card.addView(labelRow);

        // Divider
        View div = new View(this);
        div.setBackgroundColor(0xFF30363d);
        LinearLayout.LayoutParams divLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(1));
        divLp.topMargin = dp(8);
        divLp.bottomMargin = dp(10);
        div.setLayoutParams(divLp);
        card.addView(div);

        // Path display
        mPathText = new TextView(this);
        mPathText.setTextSize(13);
        mPathText.setSingleLine(false);
        refreshPathText();
        card.addView(mPathText);

        // Install-from-ISO button: pick a single .iso, unpack it into internal
        // app storage, then use that folder as the library.
        Button isoBtn = new Button(this);
        isoBtn.setText("Install from ISO...");
        isoBtn.setTextSize(13);
        isoBtn.setTypeface(Typeface.DEFAULT_BOLD);
        isoBtn.setAllCaps(false);
        GradientDrawable isoBg = new GradientDrawable();
        isoBg.setColor(C_BTN_DIS);
        isoBg.setCornerRadius(dp(6));
        isoBtn.setBackground(isoBg);
        isoBtn.setTextColor(C_TEXT);
        LinearLayout.LayoutParams isoLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        isoLp.topMargin = dp(12);
        isoBtn.setLayoutParams(isoLp);
        isoBtn.setOnClickListener(v -> pickIso());
        card.addView(isoBtn);

        // Optional custom GPU driver (libadrenotools) — pick a Turnip driver
        // .zip or .so. Empty = stock system Vulkan driver.
        Button drvBtn = new Button(this);
        drvBtn.setText("GPU driver (optional)...");
        drvBtn.setTextSize(13);
        drvBtn.setTypeface(Typeface.DEFAULT_BOLD);
        drvBtn.setAllCaps(false);
        GradientDrawable drvBg = new GradientDrawable();
        drvBg.setColor(C_BTN_DIS);
        drvBg.setCornerRadius(dp(6));
        drvBtn.setBackground(drvBg);
        drvBtn.setTextColor(C_TEXT);
        LinearLayout.LayoutParams drvLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        drvLp.topMargin = dp(8);
        drvBtn.setLayoutParams(drvLp);
        drvBtn.setOnClickListener(v -> pickDriver());
        card.addView(drvBtn);

        return card;
    }

    private View buildPermissionCard() {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(16), dp(14), dp(16), dp(14));
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(0xFF2d1a00);  // amber-tinted dark
        bg.setCornerRadius(dp(8));
        bg.setStroke(dp(1), 0xFFf0a500);
        card.setBackground(bg);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = dp(16);
        card.setLayoutParams(lp);

        TextView title = new TextView(this);
        title.setText("⚠  File Access Required");
        title.setTextSize(14);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setTextColor(0xFFf0a500);
        card.addView(title);

        TextView body = new TextView(this);
        body.setText("reNut needs \"All Files Access\" to read the game library from\n"
                + "your storage.  Tap the button below, then enable the toggle\n"
                + "for reNut on the next screen.");
        body.setTextSize(12);
        body.setTextColor(C_TEXT);
        LinearLayout.LayoutParams bodyLp = wrap();
        bodyLp.topMargin = dp(6);
        bodyLp.bottomMargin = dp(10);
        body.setLayoutParams(bodyLp);
        card.addView(body);

        Button grant = new Button(this);
        grant.setText("Grant All Files Access");
        grant.setTextSize(13);
        grant.setTypeface(Typeface.DEFAULT_BOLD);
        grant.setAllCaps(false);
        GradientDrawable btnBg = new GradientDrawable();
        btnBg.setColor(0xFFf0a500);
        btnBg.setCornerRadius(dp(6));
        grant.setBackground(btnBg);
        grant.setTextColor(0xFF0d1117);
        grant.setOnClickListener(v -> requestStoragePermission());
        card.addView(grant);

        // Initially hide the card if permission is already granted
        card.setVisibility(isStoragePermissionGranted() ? View.GONE : View.VISIBLE);
        return card;
    }

    /** Returns true if the app can access arbitrary files on external storage. */
    private boolean isStoragePermissionGranted() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            return Environment.isExternalStorageManager();
        }
        return true;  // API < 30 uses READ_EXTERNAL_STORAGE, declared in manifest
    }

    /** Opens the system Settings screen where the user can grant All Files Access. */
    private void requestStoragePermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            try {
                Intent intent = new Intent(
                        Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                        Uri.parse("package:" + getPackageName()));
                startActivityForResult(intent, REQ_MANAGE_STORAGE);
            } catch (Exception e) {
                // Fallback to general manage-storage screen
                Intent intent = new Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION);
                startActivityForResult(intent, REQ_MANAGE_STORAGE);
            }
        }
    }

    private void refreshPermCard() {
        if (mPermCard != null) {
            mPermCard.setVisibility(isStoragePermissionGranted() ? View.GONE : View.VISIBLE);
        }
    }

    private void refreshPathText() {
        if (mPathText == null) return;
        if (mGameDir != null) {
            mPathText.setText(mGameDir);
            mPathText.setTextColor(C_TEXT);
        } else {
            mPathText.setText("No folder selected  —  tap Browse to choose the game library");
            mPathText.setTextColor(C_ERR);
        }
    }

    private void refreshPlayButton() {
        if (mPlayBtn == null) return;
        boolean ready = mGameDir != null && isStoragePermissionGranted();
        if (ready) {
            mPlayBtn.setEnabled(true);
            GradientDrawable bg = new GradientDrawable();
            bg.setColor(C_ACCENT);
            bg.setCornerRadius(dp(6));
            mPlayBtn.setBackground(bg);
            mPlayBtn.setTextColor(0xFF0d1117);
        } else {
            mPlayBtn.setEnabled(false);
            GradientDrawable bg = new GradientDrawable();
            bg.setColor(C_BTN_DIS);
            bg.setCornerRadius(dp(6));
            mPlayBtn.setBackground(bg);
            mPlayBtn.setTextColor(C_MUTED);
        }
    }

    private void setGameDir(String path) {
        mGameDir = path;
        getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
                .edit()
                .putString(PREF_GAME_DIR, path)
                .apply();
        refreshPathText();
        refreshPlayButton();
    }

    // -----------------------------------------------------------------------
    // Folder picker (SAF)
    // -----------------------------------------------------------------------

    private void pickFolder() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION
                | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
        startActivityForResult(intent, REQ_PICK_FOLDER);
    }

    private void pickIso() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("*/*");   // .iso has no registered MIME type
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        startActivityForResult(intent, REQ_PICK_ISO);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);

        if (requestCode == REQ_MANAGE_STORAGE) {
            // User returned from the All Files Access settings page
            refreshPermCard();
            refreshPlayButton();
            return;
        }

        if (requestCode == REQ_PICK_ISO) {
            if (resultCode != RESULT_OK || data == null || data.getData() == null) return;
            extractIsoAsync(data.getData());
            return;
        }

        if (requestCode == REQ_PICK_DRIVER) {
            if (resultCode != RESULT_OK || data == null || data.getData() == null) return;
            installDriverAsync(data.getData());
            return;
        }

        if (requestCode != REQ_PICK_FOLDER || resultCode != RESULT_OK || data == null) return;

        Uri treeUri = data.getData();
        if (treeUri == null) return;

        // Persist access permission across reboots
        getContentResolver().takePersistableUriPermission(
                treeUri, Intent.FLAG_GRANT_READ_URI_PERMISSION);

        String path = resolveTreeUri(treeUri);
        if (path != null) {
            setGameDir(path);
            Toast.makeText(this, "Library set: " + path, Toast.LENGTH_SHORT).show();
        } else {
            Toast.makeText(this,
                    "Could not resolve path.\nTry a folder on internal storage.",
                    Toast.LENGTH_LONG).show();
        }
    }

    /**
     * Converts a SAF document-tree URI to a real filesystem path.
     *
     * Works for primary (internal) storage URIs of the form:
     *   content://com.android.externalstorage.documents/tree/primary:some/path
     *
     * Returns null for SD-card / cloud URIs — native code can't use those.
     */
    private String resolveTreeUri(Uri treeUri) {
        try {
            String docId = DocumentsContract.getTreeDocumentId(treeUri);
            if (docId != null && docId.contains(":")) {
                String[] parts = docId.split(":", 2);
                String volume   = parts[0];
                String relative = parts.length > 1 ? parts[1] : "";
                if ("primary".equalsIgnoreCase(volume)) {
                    String base = Environment.getExternalStorageDirectory()
                                             .getAbsolutePath();
                    return relative.isEmpty() ? base : base + "/" + relative;
                }
            }
        } catch (Exception ignored) { }
        return null;
    }

    // -----------------------------------------------------------------------
    // ISO extraction (pick .iso -> unpack to internal storage -> use folder)
    // -----------------------------------------------------------------------

    private void extractIsoAsync(final Uri uri) {
        final String destDir = new File(getFilesDir(), "game").getAbsolutePath();
        final AlertDialog dialog = simpleProgress(
                "Extracting ISO to internal storage...\n  This can take several minutes.");
        dialog.show();

        new Thread(() -> {
            // Clear any previous/partial extraction so a failed run can't be reused.
            deleteRecursive(new File(destDir));
            boolean ok = false;
            // Read the ISO through its content URI -- works no matter where the
            // file lives (Downloads, SD card, cloud-backed, ...). We hand native a
            // /proc/self/fd path for the open descriptor instead of resolving a real
            // filesystem path (which only works for primary storage).
            ParcelFileDescriptor pfd = null;
            try {
                pfd = getContentResolver().openFileDescriptor(uri, "r");
                if (pfd != null) {
                    ok = nativeExtractIso("/proc/self/fd/" + pfd.getFd(), destDir);
                }
            } catch (Exception e) {
                ok = false;
            } finally {
                if (pfd != null) try { pfd.close(); } catch (Exception ignored) { }
            }
            final boolean success = ok;
            new Handler(Looper.getMainLooper()).post(() -> {
                dialog.dismiss();
                if (success) {
                    setGameDir(destDir);
                    Toast.makeText(this, "Game installed to internal storage.",
                            Toast.LENGTH_SHORT).show();
                } else {
                    deleteRecursive(new File(destDir));
                    Toast.makeText(this,
                            "Extraction failed. Check it is a valid Nuts & Bolts ISO\n"
                            + "and that there is enough free space.",
                            Toast.LENGTH_LONG).show();
                }
            });
        }, "iso-extract").start();
    }

    private static void deleteRecursive(File f) {
        if (f == null || !f.exists()) return;
        File[] kids = f.listFiles();
        if (kids != null) for (File k : kids) deleteRecursive(k);
        f.delete();
    }

    // -----------------------------------------------------------------------
    // Custom GPU driver (libadrenotools) — optional Turnip/Mesa driver
    // -----------------------------------------------------------------------

    private void configureGpuDriverFromPrefs() {
        SharedPreferences p = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        String name = p.getString(PREF_DRIVER_NAME, "");
        String dir  = p.getString(PREF_DRIVER_DIR, "");
        // Empty name -> native keeps the stock system Vulkan driver.
        nativeConfigureGpuDriver(getApplicationInfo().nativeLibraryDir,
                                 getCacheDir().getAbsolutePath(), dir, name);
    }

    private void pickDriver() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("*/*");   // driver .zip or .so
        startActivityForResult(intent, REQ_PICK_DRIVER);
    }

    private void installDriverAsync(final Uri uri) {
        final AlertDialog dialog = simpleProgress("Installing GPU driver...");
        dialog.show();
        new Thread(() -> {
            String name = null;
            final File driverDir = new File(getFilesDir(), "drivers");
            try {
                deleteRecursive(driverDir);
                driverDir.mkdirs();
                String fileName = queryDisplayName(uri);
                if (fileName != null && fileName.toLowerCase().endsWith(".zip")) {
                    name = installDriverZip(uri, driverDir);
                } else {
                    String soName = (fileName != null && fileName.endsWith(".so"))
                            ? fileName : "libvulkan_freedreno.so";
                    try (java.io.InputStream in = getContentResolver().openInputStream(uri)) {
                        copyStreamToFile(in, new File(driverDir, soName));
                    }
                    name = soName;
                }
            } catch (Exception e) {
                name = null;
            }
            final String driverName = name;
            new Handler(Looper.getMainLooper()).post(() -> {
                dialog.dismiss();
                if (driverName != null) {
                    getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit()
                            .putString(PREF_DRIVER_NAME, driverName)
                            .putString(PREF_DRIVER_DIR, driverDir.getAbsolutePath())
                            .apply();
                    Toast.makeText(this, "GPU driver set: " + driverName
                            + "\nUsed next time you press PLAY.", Toast.LENGTH_LONG).show();
                } else {
                    deleteRecursive(driverDir);
                    Toast.makeText(this, "Could not install driver (no .so found).",
                            Toast.LENGTH_LONG).show();
                }
            });
        }, "driver-install").start();
    }

    /** Extracts a driver .zip; returns the .so to load (from meta.json if present). */
    private String installDriverZip(Uri uri, File destDir) throws Exception {
        byte[] buf = new byte[65536];
        try (java.util.zip.ZipInputStream zis =
                     new java.util.zip.ZipInputStream(getContentResolver().openInputStream(uri))) {
            java.util.zip.ZipEntry e;
            while ((e = zis.getNextEntry()) != null) {
                if (e.isDirectory()) continue;
                String n = new File(e.getName()).getName();   // flatten subdirs
                if (n.isEmpty()) continue;
                try (java.io.FileOutputStream fos = new java.io.FileOutputStream(new File(destDir, n))) {
                    int r; while ((r = zis.read(buf)) > 0) fos.write(buf, 0, r);
                }
            }
        }
        String libraryName = null;
        File meta = new File(destDir, "meta.json");
        if (meta.exists()) {
            try {
                String json = new String(java.nio.file.Files.readAllBytes(meta.toPath()));
                libraryName = new org.json.JSONObject(json).optString("libraryName", null);
                if (libraryName != null && libraryName.isEmpty()) libraryName = null;
            } catch (Exception ignored) { }
        }
        if (libraryName == null) {
            File[] sos = destDir.listFiles((d, nm) -> nm.endsWith(".so"));
            if (sos != null && sos.length > 0) {
                libraryName = sos[0].getName();
                for (File s : sos) if (s.getName().contains("vulkan")) { libraryName = s.getName(); break; }
            }
        }
        return libraryName;
    }

    private static void copyStreamToFile(java.io.InputStream in, File out) throws Exception {
        try (java.io.OutputStream os = new java.io.FileOutputStream(out)) {
            byte[] buf = new byte[65536];
            int r; while ((r = in.read(buf)) > 0) os.write(buf, 0, r);
        }
    }

    private String queryDisplayName(Uri uri) {
        try (android.database.Cursor c = getContentResolver().query(uri, null, null, null, null)) {
            if (c != null && c.moveToFirst()) {
                int idx = c.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME);
                if (idx >= 0) return c.getString(idx);
            }
        } catch (Exception ignored) { }
        return null;
    }

    private AlertDialog simpleProgress(String message) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.HORIZONTAL);
        box.setGravity(Gravity.CENTER_VERTICAL);
        box.setPadding(dp(24), dp(24), dp(24), dp(24));
        box.addView(new ProgressBar(this));
        TextView msg = new TextView(this);
        msg.setText("  " + message);
        msg.setTextColor(C_TEXT);
        box.addView(msg);
        return new AlertDialog.Builder(this).setView(box).setCancelable(false).create();
    }

    // -----------------------------------------------------------------------
    // Game launch
    // -----------------------------------------------------------------------

    private void startGame() {
        if (mGameDir == null) return;
        showGame();   // SurfaceView becomes visible → surfaceCreated fires
    }

    private void showMenu() {
        mMenuScroll.setVisibility(View.VISIBLE);
        mSurface.setVisibility(View.GONE);
    }

    private void showGame() {
        mMenuScroll.setVisibility(View.GONE);
        mSurface.setVisibility(View.VISIBLE);
    }

    // -----------------------------------------------------------------------
    // SurfaceHolder.Callback
    // -----------------------------------------------------------------------

    @Override
    public void surfaceCreated(SurfaceHolder holder) {
        if (!mInitialised && mGameDir != null) {
            configureGpuDriverFromPrefs();
            mInitialised = nativeInit(holder.getSurface(), "renut", mGameDir, getFilesDir().getAbsolutePath());
            if (!mInitialised) {
                showMenu();
                Toast.makeText(this, "Failed to initialise engine", Toast.LENGTH_LONG).show();
                return;
            }
        }
        if (mInitialised) {
            mRunning = true;
            Choreographer.getInstance().postFrameCallback(this);
        }
    }

    @Override
    public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) {
        if (mInitialised) nativeSurfaceChanged(width, height);
    }

    @Override
    public void surfaceDestroyed(SurfaceHolder holder) {
        mRunning = false;
        if (mInitialised) nativeSurfaceDestroyed();
    }

    // -----------------------------------------------------------------------
    // Choreographer.FrameCallback
    // -----------------------------------------------------------------------

    @Override
    public void doFrame(long frameTimeNanos) {
        if (mRunning && mInitialised) {
            nativePumpEvents();
            Choreographer.getInstance().postFrameCallback(this);
        }
    }

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    private void enterImmersive() {
        if (android.os.Build.VERSION.SDK_INT >= 30) {
            WindowInsetsController wic = getWindow().getInsetsController();
            if (wic != null) {
                wic.hide(WindowInsets.Type.statusBars() | WindowInsets.Type.navigationBars());
                wic.setSystemBarsBehavior(
                        WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
            }
        } else {
            getWindow().getDecorView().setSystemUiVisibility(
                    View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                    | View.SYSTEM_UI_FLAG_FULLSCREEN
                    | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                    | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                    | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                    | View.SYSTEM_UI_FLAG_LAYOUT_STABLE);
        }
    }

    private int dp(int dp) {
        return Math.round(dp * getResources().getDisplayMetrics().density);
    }

    private FrameLayout.LayoutParams matchParent() {
        return new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT);
    }

    private LinearLayout.LayoutParams wrap() {
        return new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
    }

    // -----------------------------------------------------------------------
    // Gamepad / input forwarding via SDL3 controller manager
    // -----------------------------------------------------------------------

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        if ((event.getSource() & InputDevice.SOURCE_GAMEPAD) == InputDevice.SOURCE_GAMEPAD
                || (event.getSource() & InputDevice.SOURCE_JOYSTICK) == InputDevice.SOURCE_JOYSTICK
                || (event.getSource() & InputDevice.SOURCE_DPAD) == InputDevice.SOURCE_DPAD) {
            int deviceId = event.getDeviceId();
            int keyCode  = event.getKeyCode();
            if (event.getAction() == KeyEvent.ACTION_DOWN) {
                if (SDLControllerManager.onNativePadDown(deviceId, keyCode)) {
                    return true;
                }
            } else if (event.getAction() == KeyEvent.ACTION_UP) {
                if (SDLControllerManager.onNativePadUp(deviceId, keyCode)) {
                    return true;
                }
            }
        }
        return super.dispatchKeyEvent(event);
    }

    @Override
    public boolean dispatchGenericMotionEvent(MotionEvent event) {
        if ((event.getSource() & InputDevice.SOURCE_JOYSTICK) == InputDevice.SOURCE_JOYSTICK
                && event.getAction() == MotionEvent.ACTION_MOVE) {
            SDLControllerManager.handleJoystickMotionEvent(event);
            return true;
        }
        return super.dispatchGenericMotionEvent(event);
    }

}

