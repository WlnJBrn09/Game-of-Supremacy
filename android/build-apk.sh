#!/usr/bin/env bash
# Builds an installable, debug-signed Android APK of the Rust game.
#
# Requirements: Rust with the Android targets, a JDK (javac, keytool), and an Android SDK with
# platform 35, build-tools 35.0.0 and NDK r27 (any NDK r25 or newer works):
#
#   rustup target add aarch64-linux-android armv7-linux-androideabi x86_64-linux-android
#   sdkmanager "platforms;android-35" "build-tools;35.0.0" "ndk;27.2.12479018"
#
# Set ANDROID_HOME (or ANDROID_SDK_ROOT). ANDROID_NDK_HOME defaults to the newest NDK in the SDK.
# Output: target/android/GameOfSupremacy-debug.apk
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
SDK="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}"
[ -n "$SDK" ] || { echo "Set ANDROID_HOME to your Android SDK." >&2; exit 1; }
NDK="${ANDROID_NDK_HOME:-$(ls -d "$SDK"/ndk/* 2>/dev/null | sort -V | tail -1)}"
[ -d "$NDK" ] || { echo "No Android NDK found; install one with sdkmanager or set ANDROID_NDK_HOME." >&2; exit 1; }
BUILD_TOOLS="$SDK/build-tools/${BUILD_TOOLS_VERSION:-35.0.0}"
PLATFORM="$SDK/platforms/android-${PLATFORM_VERSION:-35}/android.jar"
MIN_SDK=26
TARGET_SDK=35
VERSION_CODE=3
VERSION_NAME="$(sed -n 's/^version = "\(.*\)"/\1/p' "$ROOT/Cargo.toml" | head -1)"
case "$(uname -s)" in Darwin) HOST=darwin-x86_64 ;; *) HOST=linux-x86_64 ;; esac
LLVM="$NDK/toolchains/llvm/prebuilt/$HOST/bin"
OUT="$ROOT/target/android"
STAGE="$OUT/stage"
rm -rf "$STAGE" && mkdir -p "$STAGE/lib" "$STAGE/java" "$STAGE/classes" "$STAGE/dex"

# 1. The game as a shared library for each ABI (Rust target, ABI directory, clang prefix).
for spec in "aarch64-linux-android arm64-v8a aarch64-linux-android" \
            "armv7-linux-androideabi armeabi-v7a armv7a-linux-androideabi" \
            "x86_64-linux-android x86_64 x86_64-linux-android"; do
  set -- $spec
  target=$1 abi=$2 clang="$LLVM/$3$MIN_SDK-clang"
  env_target="$(echo "$target" | tr 'a-z-' 'A-Z_')"
  echo "Building $abi…"
  env "CARGO_TARGET_${env_target}_LINKER=$clang" "CC_${target//-/_}=$clang" "AR_${target//-/_}=$LLVM/llvm-ar" \
    cargo rustc --manifest-path "$ROOT/Cargo.toml" --release --lib --target "$target" --crate-type cdylib
  mkdir -p "$STAGE/lib/$abi"
  cp "$ROOT/target/$target/release/libsupremacy.so" "$STAGE/lib/$abi/"
  "$LLVM/llvm-strip" --strip-unneeded "$STAGE/lib/$abi/libsupremacy.so"
done

# 2. miniquad's Java activity, pointed at this package and library.
MINIQUAD="$(cargo metadata --manifest-path "$ROOT/Cargo.toml" --format-version 1 \
  | python3 -c 'import json,sys; print(next(p["manifest_path"] for p in json.load(sys.stdin)["packages"] if p["name"]=="miniquad").rsplit("/",1)[0])')"
mkdir -p "$STAGE/java/com/supremacy/game" "$STAGE/java/quad_native"
sed -e 's/TARGET_PACKAGE_NAME/com.supremacy.game/' -e 's/LIBRARY_NAME/supremacy/' -e 's#^\(\s*\)//%.*#\1#' \
  "$MINIQUAD/java/MainActivity.java" > "$STAGE/java/com/supremacy/game/MainActivity.java"
cp "$MINIQUAD/java/QuadNative.java" "$STAGE/java/quad_native/"
javac -nowarn --release 8 -Xlint:-options -classpath "$PLATFORM" -d "$STAGE/classes" \
  "$STAGE/java/com/supremacy/game/MainActivity.java" "$STAGE/java/quad_native/QuadNative.java"
"$BUILD_TOOLS/d8" --release --min-api "$MIN_SDK" --lib "$PLATFORM" --output "$STAGE/dex" \
  $(find "$STAGE/classes" -name '*.class')

# 3. Manifest, code and native libraries into an aligned, signed APK.
"$BUILD_TOOLS/aapt2" link -o "$STAGE/unsigned.apk" -I "$PLATFORM" \
  --manifest "$ROOT/android/AndroidManifest.xml" \
  --min-sdk-version "$MIN_SDK" --target-sdk-version "$TARGET_SDK" \
  --version-code "$VERSION_CODE" --version-name "$VERSION_NAME"
(cd "$STAGE/dex" && zip -q "$STAGE/unsigned.apk" classes.dex)
(cd "$STAGE" && zip -qr "$STAGE/unsigned.apk" lib)
"$BUILD_TOOLS/zipalign" -f -p 4 "$STAGE/unsigned.apk" "$STAGE/aligned.apk"

KEYSTORE="${ANDROID_DEBUG_KEYSTORE:-$HOME/.android/debug.keystore}"
if [ ! -f "$KEYSTORE" ]; then
  mkdir -p "$(dirname "$KEYSTORE")"
  keytool -genkeypair -keystore "$KEYSTORE" -storepass android -keypass android -alias androiddebugkey \
    -keyalg RSA -keysize 2048 -validity 10000 -dname "CN=Android Debug,O=Android,C=US" >/dev/null
fi
APK="$OUT/GameOfSupremacy-debug.apk"
"$BUILD_TOOLS/apksigner" sign --ks "$KEYSTORE" --ks-pass pass:android --key-pass pass:android \
  --ks-key-alias androiddebugkey --out "$APK" "$STAGE/aligned.apk"
"$BUILD_TOOLS/apksigner" verify "$APK"
echo "APK: $APK"
