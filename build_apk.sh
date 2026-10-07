#!/bin/bash
set -e
cd /root/pocket-ai-edge
rm -rf bin gen
mkdir -p bin gen assets/web

echo "=== 🚀 BUILDING POCKET AI EDGE v1.6.0 (NEURAL DIALOGUE RUNTIME) ==="

echo "Step 1: Generating R.java..."
aapt package -f -m -J gen -M AndroidManifest.xml -S res -A assets -I /usr/lib/android-sdk/platforms/android-23/android.jar

echo "Step 2: Compiling Java classes..."
javac -source 1.8 -target 1.8 -bootclasspath /usr/lib/android-sdk/platforms/android-23/android.jar -cp /usr/lib/android-sdk/platforms/android-23/android.jar -d bin gen/com/johntika/pocketai/R.java src/com/johntika/pocketai/*.java

echo "Step 3: Creating DEX bytecode..."
/usr/lib/android-sdk/build-tools/debian/dx --dex --output=bin/classes.dex bin

echo "Step 4: Packaging APK resources..."
aapt package -f -M AndroidManifest.xml -S res -A assets -I /usr/lib/android-sdk/platforms/android-23/android.jar -F bin/pocket_ai_edge_unaligned.apk

echo "Step 5: Adding classes.dex and lib/arm64-v8a shared libraries..."
cd bin
aapt add pocket_ai_edge_unaligned.apk classes.dex
cd /root/pocket-ai-edge

# Add native libraries to APK
if [ -d "lib" ]; then
    zip -u bin/pocket_ai_edge_unaligned.apk lib/arm64-v8a/* 2>/dev/null || true
fi

echo "Step 6: Signing with Noorma M Hidayat Master Keystore..."
/root/tanda-tangan-digital/sign_apk.sh bin/pocket_ai_edge_unaligned.apk bin/Pocket_AI_Edge_v1.6.0_Signed.apk

echo "Step 7: Copying deliverables..."
cp -f bin/Pocket_AI_Edge_v1.6.0_Signed.apk /sdcard/Download/Pocket_AI_Edge_v1.6.0.apk
cp -f bin/Pocket_AI_Edge_v1.6.0_Signed.apk /root/jarvis-angel/public/Pocket_AI_Edge_v1.6.0_Signed.apk

echo "Step 8: Installing to phone via pm install..."
/system/bin/pm install -r bin/Pocket_AI_Edge_v1.6.0_Signed.apk || true

echo "✅ BUILD AND INSTALL v1.6.0 COMPLETE!"
