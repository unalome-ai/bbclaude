#!/bin/sh
# Builds bbclaude.apk for the BB10 Android runtime (Android 4.3, API 18) without Gradle.
set -e
cd "$(dirname "$0")"
SDK=${ANDROID_HOME:-$HOME/bb/android-sdk}
BT=$SDK/build-tools/34.0.0
JAR=$SDK/platforms/android-18/android.jar
CP=libs/conscrypt/classes.jar
export JAVA_HOME=${JAVA_HOME:-$(/usr/libexec/java_home -v 17)}
rm -rf build && mkdir -p build/res build/classes build/dex build/native/lib/armeabi-v7a
"$BT/aapt2" compile --dir res -o build/res.zip
"$BT/aapt2" link -o build/base.apk -I "$JAR" --manifest AndroidManifest.xml \
    --min-sdk-version 18 --target-sdk-version 18 build/res.zip
"$JAVA_HOME/bin/javac" -source 8 -target 8 -bootclasspath "$JAR" -classpath "$JAR:$CP" \
    -Xlint:-options -d build/classes $(find src -name '*.java')
"$BT/d8" --min-api 18 --lib "$JAR" --output build/dex $(find build/classes -name '*.class') $CP 2>&1 \
    | grep -v -E "X509ExtendedTrustManager|^Warning in" || true
cp build/base.apk build/unsigned.apk
(cd build/dex && zip -q -j ../unsigned.apk classes.dex)
cp libs/conscrypt/jni/armeabi-v7a/*.so build/native/lib/armeabi-v7a/
(cd build/native && zip -q -r ../unsigned.apk lib)
"$BT/zipalign" -f 4 build/unsigned.apk build/aligned.apk
# Release builds: KEYSTORE=/path/release.jks KS_PASS=... ./build.sh  (keep that keystore out of git)
if [ -z "$KEYSTORE" ]; then
    KEYSTORE=debug.keystore KS_PASS=android
    [ -f debug.keystore ] || "$JAVA_HOME/bin/keytool" -genkeypair -keystore debug.keystore -storepass android \
        -keypass android -alias debug -keyalg RSA -keysize 2048 -validity 10000 -dname "CN=bbclaude" >/dev/null 2>&1
fi
"$BT/apksigner" sign --ks "$KEYSTORE" --ks-pass "pass:$KS_PASS" --key-pass "pass:$KS_PASS" \
    --min-sdk-version 18 --v1-signing-enabled true --v2-signing-enabled true \
    --out bbclaude.apk build/aligned.apk
"$BT/apksigner" verify --min-sdk-version 18 bbclaude.apk && echo "signed (v1 + v2)"
ls -la bbclaude.apk
