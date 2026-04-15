#!/bin/bash
# Clean build, install, and launch on connected device/emulator
echo "🔨 Clean building..."
./gradlew clean assembleDebug -q

if [ $? -ne 0 ]; then
    echo "❌ Build failed"
    exit 1
fi

echo "📲 Installing..."
adb install -r app/build/outputs/apk/debug/app-debug.apk

if [ $? -ne 0 ]; then
    echo "⚠️  Install failed, trying force install..."
    adb shell pm clear com.zanoshky.firewall 2>/dev/null
    adb uninstall com.zanoshky.firewall 2>/dev/null
    adb install app/build/outputs/apk/debug/app-debug.apk
fi

echo "🚀 Launching..."
adb shell am start -n com.zanoshky.firewall/com.zanoshky.firewall.MainActivity

echo "✅ Done"
