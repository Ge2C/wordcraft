#!/usr/bin/env bash
set -e

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
APP_DIR="$SCRIPT_DIR/app"
BUILD_DIR="$SCRIPT_DIR/build_standalone"

# Locate android.jar dynamically across Termux, GitHub Actions, and Linux hosts
if [ -n "$ANDROID_JAR" ] && [ -f "$ANDROID_JAR" ]; then
    : # Keep user-specified ANDROID_JAR
elif [ -f "/data/data/com.termux/files/usr/share/java/android.jar" ]; then
    ANDROID_JAR="/data/data/com.termux/files/usr/share/java/android.jar"
elif [ -n "$ANDROID_SDK_ROOT" ] && [ -d "$ANDROID_SDK_ROOT/platforms" ]; then
    ANDROID_JAR=$(ls -d "$ANDROID_SDK_ROOT"/platforms/android-*/android.jar 2>/dev/null | sort -V | tail -n 1)
elif [ -n "$ANDROID_HOME" ] && [ -d "$ANDROID_HOME/platforms" ]; then
    ANDROID_JAR=$(ls -d "$ANDROID_HOME"/platforms/android-*/android.jar 2>/dev/null | sort -V | tail -n 1)
fi

ANDROID_FRAMEWORK="/system/framework/framework-res.apk"
if [ ! -f "$ANDROID_FRAMEWORK" ]; then
    ANDROID_FRAMEWORK="$ANDROID_JAR"
fi

if [ -z "$ANDROID_JAR" ] || [ ! -f "$ANDROID_JAR" ]; then
    echo "[-] android.jar not found (checked Termux, ANDROID_SDK_ROOT, and ANDROID_HOME)"
    exit 1
fi

echo "[*] Step 1: Cleaning previous build artifacts..."
rm -rf "$BUILD_DIR"
mkdir -p "$BUILD_DIR/gen" "$BUILD_DIR/classes" "$BUILD_DIR/dex" "$BUILD_DIR/apk"

echo "[*] Step 2: Generating R.java resources via AAPT..."
aapt package -f -m \
  --min-sdk-version 26 \
  --target-sdk-version 34 \
  -J "$BUILD_DIR/gen" \
  -S "$APP_DIR/src/main/res" \
  -M "$APP_DIR/src/main/AndroidManifest.xml" \
  -I "$ANDROID_FRAMEWORK"

echo "[*] Step 3: Compiling Java source files..."
javac -source 1.8 -target 1.8 \
  -cp "$ANDROID_JAR" \
  -d "$BUILD_DIR/classes" \
  "$BUILD_DIR/gen/com/storytold/wordcraft/R.java" \
  "$APP_DIR/src/main/java/com/storytold/wordcraft/MainActivity.java"

echo "[*] Step 4: Dexing bytecode with D8..."
d8 --release --min-api 26 \
  --lib "$ANDROID_JAR" \
  --output "$BUILD_DIR/dex" \
  $(find "$BUILD_DIR/classes" -name "*.class")

echo "[*] Step 5: Packaging APK with assets (WebAssembly bundle)..."
aapt package -f \
  --min-sdk-version 26 \
  --target-sdk-version 34 \
  -M "$APP_DIR/src/main/AndroidManifest.xml" \
  -S "$APP_DIR/src/main/res" \
  -A "$APP_DIR/src/main/assets" \
  -I "$ANDROID_FRAMEWORK" \
  -F "$BUILD_DIR/wordcraft-unaligned.apk"

echo "[*] Step 6: Adding classes.dex to APK..."
cd "$BUILD_DIR/dex"
aapt add "$BUILD_DIR/wordcraft-unaligned.apk" classes.dex
cd "$SCRIPT_DIR"

echo "[*] Step 7: Aligning APK with zipalign (4-byte page alignment)..."
zipalign -f -p 4 "$BUILD_DIR/wordcraft-unaligned.apk" "$BUILD_DIR/wordcraft-aligned.apk"

echo "[*] Step 8: Signing APK with apksigner..."
KEYSTORE="$HOME/debug.keystore"
if [ ! -f "$KEYSTORE" ]; then
    echo "[*] Generating debug keystore..."
    keytool -genkey -v -keystore "$KEYSTORE" \
      -alias debugkey -keyalg RSA -keysize 2048 -validity 10000 \
      -dname "CN=Android Debug,O=Android,C=US" \
      -storepass android -keypass android
fi

apksigner sign --ks "$KEYSTORE" \
  --ks-pass pass:android \
  --ks-key-alias debugkey \
  --out "$BUILD_DIR/wordcraft-release.apk" \
  "$BUILD_DIR/wordcraft-aligned.apk"

cp "$BUILD_DIR/wordcraft-release.apk" "$BUILD_DIR/wordcraft-debug.apk"

echo "[*] Step 9: Verifying zipalign & signature..."
zipalign -c 4 "$BUILD_DIR/wordcraft-release.apk" && echo "[+] Zipalign check passed"
apksigner verify "$BUILD_DIR/wordcraft-release.apk" && echo "[+] Signature check passed"

if [ -d "/storage/emulated/0/Download" ]; then
    cp "$BUILD_DIR/wordcraft-release.apk" "/storage/emulated/0/Download/wordcraft-release.apk"
    cp "$BUILD_DIR/wordcraft-debug.apk" "/storage/emulated/0/Download/wordcraft-debug.apk"
    echo "[+] Copied release APK to /storage/emulated/0/Download/wordcraft-release.apk"
fi

echo "[+] Success! Output APKs:"
ls -lh "$BUILD_DIR/wordcraft-release.apk" "$BUILD_DIR/wordcraft-debug.apk"
