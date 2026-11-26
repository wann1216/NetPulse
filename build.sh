#!/bin/bash
# NetPulse 一键构建：aapt2 链资源 -> javac 编译 -> D8 转 dex -> 签名为可安装 APK
set -e
cd "$(dirname "$0")"

ROOT=".."
AAPT="$ROOT/andro/aapt2x/aapt2.exe"
JAVAC="$ROOT/andro/jdk/jdk-17.0.2/bin/javac.exe"
JAVA="$ROOT/andro/jdk/jdk-17.0.2/bin/java.exe"
ANDROID_JAR="$ROOT/andro/android-api33.jar"
R8="$ROOT/andro/r8.jar"
SIGNER="$ROOT/uber-apk-signer.jar"

echo "[1/5] 清理"
rm -rf build
mkdir -p build/classes build/dex build/signed build/res

echo "[2/5] 编译资源 + 链接清单"
"$AAPT" compile --dir res -o build/res/
"$AAPT" link -o build/base.apk \
  --manifest AndroidManifest.xml \
  -I "$ANDROID_JAR" \
  --min-sdk-version 26 --target-sdk-version 33 \
  build/res/*.flat

echo "[3/5] 编译 Java"
"$JAVAC" -encoding UTF-8 -source 8 -target 8 -nowarn \
  -cp "$ANDROID_JAR" -d build/classes \
  src/com/wann1/netpulse/*.java

echo "[4/5] D8 转 dex"
"$JAVA" -cp "$R8" com.android.tools.r8.D8 --release --min-api 26 \
  --lib "$ANDROID_JAR" --output build/dex \
  $(find build/classes -name "*.class")

echo "[5/5] 打包签名"
cp build/base.apk build/app-unsigned.apk
python -c "
import zipfile
z = zipfile.ZipFile('build/app-unsigned.apk', 'a', zipfile.ZIP_DEFLATED)
z.write('build/dex/classes.dex', 'classes.dex')
z.close()
"
"$JAVA" -jar "$SIGNER" -a build/app-unsigned.apk --out build/signed > build/sign.log 2>&1

OUT="build/signed/app-aligned-debugSigned.apk"
if [ -f "$OUT" ]; then
  echo "OK -> $OUT ($(stat -c%s "$OUT") bytes)"
else
  echo "FAILED"; tail -20 build/sign.log; exit 1
fi
