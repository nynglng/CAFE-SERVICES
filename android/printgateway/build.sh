#!/usr/bin/env bash
# Build and sign the Print Gateway APK without Gradle.
#
# The app needs no AndroidX and no Kotlin, so the SDK's own tools are enough:
# aapt2 compiles and links resources, javac builds against android.jar, d8
# produces the dex, and apksigner signs with a local debug key. That avoids a
# few hundred megabytes of Gradle and AGP download, and rebuilds in seconds.
#
# Usage:  ./build.sh [install]

set -euo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$HERE"

SDK="${ANDROID_SDK:-$LOCALAPPDATA/Android/Sdk}"
BUILD_TOOLS_VERSION="${BUILD_TOOLS_VERSION:-36.1.0}"
PLATFORM="${PLATFORM:-android-36}"
JAVA_HOME_DIR="${JAVA_HOME:-/c/Program Files/Android/Android Studio/jbr}"

# d8 and apksigner are .bat wrappers that look up JAVA_HOME themselves,
# and they need it in Windows form rather than the shell's /c/... form.
export JAVA_HOME="${JAVA_HOME_WIN:-C:/Program Files/Android/Android Studio/jbr}"

BT="$SDK/build-tools/$BUILD_TOOLS_VERSION"
ANDROID_JAR="$SDK/platforms/$PLATFORM/android.jar"
JAVAC="$JAVA_HOME_DIR/bin/javac"
KEYTOOL="$JAVA_HOME_DIR/bin/keytool"

for required in "$BT/aapt2.exe" "$ANDROID_JAR" "$JAVAC"; do
  [ -e "$required" ] || { echo "missing: $required" >&2; exit 1; }
done

OUT="build"
rm -rf "$OUT"
mkdir -p "$OUT/res" "$OUT/gen" "$OUT/classes"

echo "1/6 compiling resources"
"$BT/aapt2.exe" compile --dir res -o "$OUT/res.zip"

echo "2/6 linking resources"
"$BT/aapt2.exe" link \
  -I "$ANDROID_JAR" \
  --manifest AndroidManifest.xml \
  --java "$OUT/gen" \
  --min-sdk-version 24 \
  --target-sdk-version 33 \
  -o "$OUT/base.apk" \
  "$OUT/res.zip"

echo "3/6 compiling java"
find src "$OUT/gen" -name '*.java' > "$OUT/sources.txt"
"$JAVAC" -source 8 -target 8 -nowarn \
  -bootclasspath "$ANDROID_JAR" \
  -classpath "$ANDROID_JAR" \
  -d "$OUT/classes" \
  @"$OUT/sources.txt" 2> "$OUT/javac.log" || { grep -v "bootstrap class path" "$OUT/javac.log" >&2; echo "COMPILE FAILED" >&2; exit 1; }
grep -v "bootstrap class path" "$OUT/javac.log" >&2 || true

echo "4/6 dexing"
"$BT/d8.bat" --min-api 24 --output "$OUT" $(find "$OUT/classes" -name '*.class')

echo "5/6 packaging"
cd "$OUT"
cp base.apk unsigned.apk
# The dex must sit at the archive root, which is where d8 wrote it.
"$JAVA_HOME_DIR/bin/jar" uf unsigned.apk classes.dex
cd ..

if [ ! -f debug.keystore ]; then
  echo "     creating a local debug key"
  "$KEYTOOL" -genkeypair -keystore debug.keystore -alias debug \
    -storepass android -keypass android \
    -keyalg RSA -keysize 2048 -validity 10000 \
    -dname "CN=Print Gateway Debug, OU=cafe-services, O=cafe-services, C=PH" >/dev/null 2>&1
fi

echo "6/6 signing"
"$BT/apksigner.bat" sign \
  --ks debug.keystore --ks-pass pass:android --key-pass pass:android \
  --min-sdk-version 24 \
  --out "$OUT/printgateway.apk" \
  "$OUT/unsigned.apk"

echo
echo "built: $OUT/printgateway.apk"

if [ "${1:-}" = "install" ]; then
  echo "installing..."
  adb install -r "$OUT/printgateway.apk"
  adb shell am start -n com.cafeservices.printgateway/.MainActivity
fi
