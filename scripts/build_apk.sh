#!/bin/bash
# Build onlinehotspottech.apk (Fridah Tech) with aapt2 + javac + d8 + apksigner
set -e
SDK=/home/z/my-project/work/android
BT=$SDK/android-14
PLATFORM=$SDK/android-34/android.jar
APP=/home/z/my-project/work/app
OUT=/home/z/my-project/work/out
KS=/home/z/my-project/work/keystore/fridah.keystore
FINAL=/home/z/my-project/download/onlinehotspottech.apk

rm -rf $OUT
mkdir -p $OUT/gen $OUT/classes $OUT/dex $OUT/apk

echo "== aapt2 compile =="
$BT/aapt2 compile --dir $APP/res -o $OUT/res.zip

echo "== aapt2 link =="
$BT/aapt2 link -o $OUT/base.apk -I $PLATFORM --manifest $APP/AndroidManifest.xml -R $OUT/res.zip --java $OUT/gen --auto-add-overlay

echo "== ecj (javac replacement) =="
find $APP/src $OUT/gen -name "*.java" > $OUT/sources.txt
java -jar $SDK/ecj.jar -source 1.8 -target 1.8 -encoding UTF-8 -nowarn -proceedOnError \
  -bootclasspath "$PLATFORM:$BT/core-lambda-stubs.jar" \
  -classpath $APP/libs/jsch.jar \
  -d $OUT/classes @$OUT/sources.txt > $OUT/compile.log 2>&1 || true
grep -E "ERROR|error:" $OUT/compile.log | head -40 || true
ERRORS=$(grep -cE "ERROR|error:" $OUT/compile.log || true)
if [ "$ERRORS" -gt 0 ] || [ ! -d $OUT/classes ]; then echo "COMPILE FAILED ($ERRORS errors)"; exit 1; fi

echo "== d8 (app + jsch -> classes.dex) =="
find $OUT/classes -name "*.class" > $OUT/classlist.txt
$BT/d8 --release --lib $PLATFORM --min-api 24 --output $OUT/dex $(cat $OUT/classlist.txt | tr '\n' ' ') $APP/libs/jsch.jar 2>&1 | grep -v "^Warning" | head -20 || true
ls -la $OUT/dex

echo "== package =="
cp $OUT/base.apk $OUT/unsigned.apk
(cd $OUT/dex && zip -q -j ../unsigned.apk classes.dex)
$BT/zipalign -f 4 $OUT/unsigned.apk $OUT/aligned.apk

echo "== sign =="
java -jar $BT/lib/apksigner.jar sign \
  --ks $KS --ks-pass pass:fridah2026key --ks-key-alias fridah --key-pass pass:fridah2026key \
  --out $FINAL $OUT/aligned.apk
java -jar $BT/lib/apksigner.jar verify --print-certs $FINAL | head -6
mkdir -p /home/z/my-project/download
ls -la $FINAL
echo "BUILD OK -> $FINAL"
