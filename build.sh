#!/bin/bash
# Builds out/WaveBuds.apk without Gradle: aapt2 (resources, R.java) -> javac -> d8 -> zipalign -> apksigner.
#   ANDROID_SDK        Android SDK with platforms;android-33 and build-tools;35.0.0
#   WAVEBUDS_KEYSTORE  signing keystore, created on first build (keep it: updates need the same key)
set -e
A=$(cd "$(dirname "$0")" && pwd)
SDK=${ANDROID_SDK:-$HOME/.local/opt/android-sdk}
BT=$SDK/build-tools/35.0.0
JAR=$SDK/platforms/android-33/android.jar
KS=${WAVEBUDS_KEYSTORE:-$HOME/.android/wavebuds.jks}
O=$A/out

rm -rf "$O" && mkdir -p "$O/classes" "$O/dex" "$O/gen"
"$BT/aapt2" compile --dir "$A/res" -o "$O/res.zip"
"$BT/aapt2" link -I "$JAR" --manifest "$A/AndroidManifest.xml" --java "$O/gen" -o "$O/base.apk" "$O/res.zip"
javac -Xlint:-options --release 11 -classpath "$JAR" -d "$O/classes" $(find "$A/src" "$O/gen" -name '*.java')
"$BT/d8" --min-api 33 --lib "$JAR" --output "$O/dex" $(find "$O/classes" -name '*.class')
python3 -I -c "import zipfile,sys; zipfile.ZipFile(sys.argv[1],'a',zipfile.ZIP_DEFLATED).write(sys.argv[2],'classes.dex')" "$O/base.apk" "$O/dex/classes.dex"
"$BT/zipalign" -f 4 "$O/base.apk" "$O/aligned.apk"
if [ ! -f "$KS" ]; then
  mkdir -p "$(dirname "$KS")"
  keytool -genkeypair -keystore "$KS" -storepass wavebuds -keypass wavebuds -alias wb \
      -keyalg RSA -keysize 2048 -validity 36500 -dname "CN=Wave Buds" >/dev/null
fi
"$BT/apksigner" sign --ks "$KS" --ks-pass pass:wavebuds --ks-key-alias wb --out "$O/WaveBuds.apk" "$O/aligned.apk"
ls -la "$O/WaveBuds.apk"
