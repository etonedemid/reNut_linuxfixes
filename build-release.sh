#!/bin/bash
# build-release.sh -- build reNut and package release-ready assets.
#
# Builds Linux (x86_64) and Android (arm64-v8a) locally and drops versioned
# archives into out/release/. Windows is opt-in (--windows) and needs the
# llvm-mingw cross toolchain at /opt/llvm-mingw -- it cannot be built otherwise.
#
# Usage:
#   ./build-release.sh                 # linux + android
#   ./build-release.sh --linux         # only linux
#   ./build-release.sh --android       # only android
#   ./build-release.sh --windows       # add windows (needs /opt/llvm-mingw)
#   VERSION=v1.2.3 ./build-release.sh  # override archive version tag
#
# Requires: clang, cmake, ninja. Android also needs the NDK + a JDK.
# The rexglue-sdk must sit at ../rexglue-sdk (or set REXSDK_DIR).

set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
RELEASE_DIR="$ROOT/out/release"

# -- Colours -------------------------------------------------------------------
GREEN='\033[0;32m'; YELLOW='\033[1;33m'; RED='\033[0;31m'; NC='\033[0m'
info()  { echo -e "${GREEN}[release]${NC} $*"; }
warn()  { echo -e "${YELLOW}[release]${NC} $*"; }
error() { echo -e "${RED}[release]${NC} $*" >&2; exit 1; }

# -- Args ----------------------------------------------------------------------
DO_LINUX=0; DO_ANDROID=0; DO_WINDOWS=0
if [ $# -eq 0 ]; then DO_LINUX=1; DO_ANDROID=1; fi
for arg in "$@"; do
    case "$arg" in
        --linux)   DO_LINUX=1 ;;
        --android) DO_ANDROID=1 ;;
        --windows) DO_WINDOWS=1 ;;
        --all)     DO_LINUX=1; DO_ANDROID=1; DO_WINDOWS=1 ;;
        --self-test) ;;
        -h|--help) sed -n '2,20p' "$0"; exit 0 ;;
        *) error "Unknown argument: $arg (try --help)" ;;
    esac
done

ASSET_PATTERN='(^|/)assets/|\.xex($|/)|xuifontcache|\$SystemUpdate|(^|/)Bundle/|(^|/)loctext/'
verify_no_assets() {
    local archive="$1" listing
    case "$archive" in
        *.tar.gz) listing="$(tar -tzf "$archive")" ;;
        *.apk|*.zip) listing="$(unzip -Z1 "$archive" 2>/dev/null || zipinfo -1 "$archive")" ;;
        *) return 0 ;;
    esac
    if echo "$listing" | grep -Eiq "$ASSET_PATTERN"; then
        echo "$listing" | grep -Ei "$ASSET_PATTERN" >&2
        rm -f "$archive"
        error "ASSET LEAK detected in $(basename "$archive"), aborting."
    fi
    info "verified clean (no assets): $(basename "$archive")"
}

# Self-test the guard (run: ./build-release.sh --self-test).
if [ "${1:-}" = "--self-test" ]; then
    t="$(mktemp -d)"; mkdir -p "$t/pkg/assets"
    echo x > "$t/pkg/assets/default.xex"; echo y > "$t/pkg/renut"
    tar -C "$t/pkg" -czf "$t/leak.tar.gz" .
    if ( verify_no_assets "$t/leak.tar.gz" ) 2>/dev/null; then
        rm -rf "$t"; error "self-test FAILED: leak not detected"
    fi
    rm -rf "$t/pkg/assets"
    tar -C "$t/pkg" -czf "$t/clean.tar.gz" .
    verify_no_assets "$t/clean.tar.gz" >/dev/null || { rm -rf "$t"; error "self-test FAILED: clean flagged"; }
    rm -rf "$t"; info "self-test passed: guard rejects assets, accepts clean."; exit 0
fi

# -- Locate the SDK ------------------------------------------------------------
REXSDK_DIR="${REXSDK_DIR:-$ROOT/../rexglue-sdk-achievementslinuxandroid}"
[ -f "$REXSDK_DIR/CMakeLists.txt" ] \
    || error "rexglue-sdk not found at $REXSDK_DIR.
  Clone it side-by-side:  git clone https://github.com/etonedemid/rexglue-sdk.git $ROOT/../rexglue-sdk
  or set REXSDK_DIR to its path."
REXSDK_DIR="$(cd "$REXSDK_DIR" && pwd)"

# Version tag for archive names. Default to git describe.
VERSION="${VERSION:-$(git -C "$ROOT" describe --tags --always --dirty 2>/dev/null || echo dev)}"
mkdir -p "$RELEASE_DIR"
info "Version: $VERSION   SDK: $REXSDK_DIR"

# Copy the recursive shared-library closure of $1 into dir $2 so the build is
# self-contained (extract-and-run, e.g. Steam Deck). Two categories deliberately
# stay on the HOST and are NOT bundled:
#   - glibc core (libc/libm/ld-linux/...): symver-pinned to old versions, so the
#     host's newer glibc is used and the binary still runs on older distros.
#   - GPU/graphics driver stack (libGL*/libvulkan*/libdrm*/libgbm*/...): must
#     match the host's kernel + GPU, so bundling them would break acceleration.
# Each bundled lib gets rpath=$ORIGIN so it finds its siblings.
bundle_linux_libs() {
    local bin="$1" libdir="$2" extra_path="${3:-}"
    mkdir -p "$libdir"
    # ldd resolves the full transitive closure with absolute host paths. Add the
    # SDK output dir so the project's own shared libs (librexruntime.so,
    # libTracyClient.so) resolve and get bundled too.
    LD_LIBRARY_PATH="${extra_path}${LD_LIBRARY_PATH:+:$LD_LIBRARY_PATH}" \
    ldd "$bin" 2>/dev/null | awk '/=>/ && $3 ~ /^\// {print $1 "\t" $3}' \
    | while IFS="$(printf '\t')" read -r soname path; do
        case "$soname" in
            ld-linux*|libc.so*|libm.so*|libdl.so*|librt.so*|libpthread.so*) continue ;;
            libresolv.so*|libutil.so*|libnss_*|libanl.so*|libgcc_s.so*) continue ;;
            libGL.so*|libGLX*|libGLdispatch*|libOpenGL*|libEGL*|libGLU*|libGLESv*) continue ;;
            libvulkan.so*|libdrm*|libgbm*|libVkLayer*|libnvidia*|libcuda*) continue ;;
            libxcb-dri*|libxcb-present*|libxcb-glx*) continue ;;
        esac
        [ -f "$libdir/$soname" ] && continue
        cp -L "$path" "$libdir/$soname" 2>/dev/null || true
    done
    local n=0
    for lib in "$libdir"/*.so*; do
        [ -e "$lib" ] || continue
        chmod u+w "$lib"; patchelf --set-rpath '$ORIGIN' "$lib" 2>/dev/null || true
        n=$((n + 1))
    done
    info "Bundled $n shared lib(s) into $(basename "$libdir")/ (glibc + GPU stack kept on host)"
}

# -- Linux ---------------------------------------------------------------------
build_linux() {
    info "-- Linux x86_64 ------------------------------------------"
    local build_dir="$ROOT/out/build/linux-amd64-release"
    rm -rf "$build_dir"
    cmake -S "$ROOT" -B "$build_dir" -G Ninja \
        -DCMAKE_BUILD_TYPE=Release \
        -DCMAKE_C_COMPILER=clang -DCMAKE_CXX_COMPILER=clang++ \
        -DREXSDK_DIR="$REXSDK_DIR"
    cmake --build "$build_dir" --target renut -j"$(nproc)"
    [ -x "$build_dir/renut" ] || error "Linux build produced no renut binary."

    local stage; stage="$(mktemp -d)"
    cp "$build_dir/renut" "$stage/renut"
    for f in launch.sh extract-xiso renut.toml README.md; do
        [ -e "$build_dir/$f" ] && cp "$build_dir/$f" "$stage/" \
            || { [ -e "$ROOT/$f" ] && cp "$ROOT/$f" "$stage/" || true; }
    done
    chmod +x "$stage/renut" "$stage/launch.sh" 2>/dev/null || true

    # Bundle the shared-lib closure and point renut at it, so the tarball runs on
    # a stock system (Steam Deck etc.) with nothing to install.
    bundle_linux_libs "$build_dir/renut" "$stage/lib" "$REXSDK_DIR/out/linux-amd64"
    chmod u+w "$stage/renut"; patchelf --set-rpath '$ORIGIN/lib' "$stage/renut"
    # Fail loudly if the project's own shared libs didn't make it in.
    for need in librexruntime.so libTracyClient.so; do
        [ -f "$stage/lib/$need" ] || error "portable bundle is missing $need"
    done

    local out="$RELEASE_DIR/renut-${VERSION}-linux-x86_64.tar.gz"
    tar -C "$stage" -czf "$out" .
    rm -rf "$stage"
    verify_no_assets "$out"
    info "-> $out"
}

# -- Android -------------------------------------------------------------------
build_android() {
    info "-- Android arm64-v8a -------------------------------------"

    # Locate the NDK.
    local ndk="${ANDROID_NDK_HOME:-${ANDROID_NDK:-}}"
    if [ -z "$ndk" ]; then
        ndk="$(ls -d "$HOME"/Android/Sdk/ndk/* 2>/dev/null | sort -V | tail -1 || true)"
    fi
    [ -n "$ndk" ] && [ -f "$ndk/build/cmake/android.toolchain.cmake" ] \
        || error "Android NDK not found. Set ANDROID_NDK_HOME or install it under ~/Android/Sdk/ndk/."

    # 1) Cross-compile librenut.so with the NDK toolchain. minSdk 30 = android-30.
    local build_dir="$ROOT/out/build/android-arm64-release"
    rm -rf "$build_dir"
    cmake -S "$ROOT" -B "$build_dir" -G Ninja \
        -DCMAKE_TOOLCHAIN_FILE="$ndk/build/cmake/android.toolchain.cmake" \
        -DANDROID_ABI=arm64-v8a -DANDROID_PLATFORM=android-30 \
        -DCMAKE_BUILD_TYPE=Release \
        -DREXSDK_DIR="$REXSDK_DIR"
    # Also build the libadrenotools hook .so libraries -- they are dlopen'd at
    # runtime from the APK's nativeLibraryDir to load a custom Vulkan driver, so
    # they are NOT link-time deps of renut and must be built/packaged explicitly.
    cmake --build "$build_dir" --target renut \
        main_hook file_redirect_hook gsl_alloc_hook hook_impl -j"$(nproc)"

    local so; so="$(find "$build_dir" -name librenut.so | head -1)"
    [ -n "$so" ] || error "Android build produced no librenut.so."

    # 2) Drop the .so where the release gradle sourceSet expects it. The gradle
    # root project is android/, so its "../build-android" resolves to $ROOT/build-android.
    local jni="$ROOT/build-android/jniLibs-release/arm64-v8a"
    rm -rf "$jni"; mkdir -p "$jni"
    cp "$so" "$jni/librenut.so"

    # librenut.so links librexruntime.so and libTracyClient.so as SHARED libs, and
    # dlopen's the adrenotools hook libs at runtime -- ALL must be in the APK or
    # loading librenut.so fails with UnsatisfiedLinkError before the Activity runs.
    # The SDK's shared outputs all live in $REXSDK_DIR/out/<abi>/; bundle them.
    local sdk_out; sdk_out="$(dirname "$so")"
    [ -d "$REXSDK_DIR/out/android-arm64" ] && sdk_out="$REXSDK_DIR/out/android-arm64"
    local copied=0
    for lib in "$sdk_out"/*.so; do
        [ -e "$lib" ] || continue
        cp "$lib" "$jni/$(basename "$lib")"
        copied=$((copied + 1))
    done
    info "Bundled $copied SDK shared lib(s) into the APK"
    # Sanity: librenut.so's non-system NEEDED deps must now be present.
    for need in librexruntime.so libTracyClient.so; do
        [ -f "$jni/$need" ] || warn "$need missing from jniLibs -- app will crash on load"
    done

    # 3) Point gradle at the SDK, then assemble the signed release APK.
    local sdk="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-$HOME/Android/Sdk}}"
    [ -d "$sdk" ] || error "Android SDK not found. Set ANDROID_HOME."
    [ -f "$ROOT/android/local.properties" ] \
        || echo "sdk.dir=$sdk" > "$ROOT/android/local.properties"

    # AGP/Gradle does not support very new JDKs (e.g. 26). Prefer a 17/21 JDK if
    # one is installed; otherwise fall back to whatever java is on PATH.
    local gradle_java=""
    for cand in /usr/lib/jvm/java-21-openjdk /usr/lib/jvm/java-17-openjdk "${JAVA_HOME:-}"; do
        if [ -n "$cand" ] && [ -x "$cand/bin/java" ]; then gradle_java="$cand"; break; fi
    done

    # The release signingConfig expects a dev keystore; generate a throwaway,
    # self-signed one if it is missing (credentials match android/app/build.gradle.kts).
    local keystore="$ROOT/android/renut-release.jks"
    if [ ! -f "$keystore" ]; then
        info "Generating dev release keystore at $keystore"
        "${gradle_java:+$gradle_java/bin/}keytool" -genkeypair \
            -keystore "$keystore" -storepass renutrelease -keypass renutrelease \
            -alias renut -keyalg RSA -keysize 2048 -validity 10000 \
            -dname "CN=reNut, O=rexglue, C=US"
    fi

    ( cd "$ROOT/android" && JAVA_HOME="${gradle_java:-${JAVA_HOME:-}}" ./gradlew --no-daemon assembleRelease )

    local apk; apk="$(find "$ROOT/android/app/build/outputs/apk/release" -name '*.apk' | head -1)"
    [ -n "$apk" ] || error "gradle produced no APK."
    local out="$RELEASE_DIR/renut-${VERSION}-android-arm64.apk"
    cp "$apk" "$out"
    verify_no_assets "$out"
    info "-> $out"
}

# -- Windows (opt-in cross-build) ----------------------------------------------
build_windows() {
    info "-- Windows x86_64 ----------------------------------------"
    [ -x "/opt/llvm-mingw/bin/x86_64-w64-mingw32-clang" ] \
        || error "llvm-mingw not found at /opt/llvm-mingw -- cannot cross-build Windows.
  Install from https://github.com/mstorsjo/llvm-mingw/releases and extract to /opt/llvm-mingw."
    local build_dir="$ROOT/out/build/win-amd64-release"
    rm -rf "$build_dir"
    cmake -S "$ROOT" -B "$build_dir" -G Ninja \
        -DCMAKE_TOOLCHAIN_FILE="$ROOT/cmake/toolchain-llvm-mingw-x64.cmake" \
        -DCMAKE_BUILD_TYPE=Release \
        -DREXSDK_DIR="$REXSDK_DIR"
    cmake --build "$build_dir" --target renut -j"$(nproc)"
    [ -f "$build_dir/renut.exe" ] || error "Windows build produced no renut.exe."

    local out="$RELEASE_DIR/renut-${VERSION}-windows-x86_64.zip"
    ( cd "$build_dir" && zip -j "$out" renut.exe ./*.dll 2>/dev/null ) || \
        ( cd "$build_dir" && zip -j "$out" renut.exe )
    verify_no_assets "$out"
    info "-> $out"
}

[ "$DO_LINUX"   = 1 ] && build_linux
[ "$DO_ANDROID" = 1 ] && build_android
[ "$DO_WINDOWS" = 1 ] && build_windows

info "Done. Release assets in $RELEASE_DIR:"
ls -lh "$RELEASE_DIR"
