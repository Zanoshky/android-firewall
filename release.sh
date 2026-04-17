#!/bin/bash
# Bump version, build signed release AAB + APK, copy to release/
set -e

GRADLE_FILE="app/build.gradle.kts"

# Read current version
CURRENT_CODE=$(grep 'versionCode' "$GRADLE_FILE" | head -1 | sed 's/[^0-9]//g')
CURRENT_NAME=$(grep 'versionName' "$GRADLE_FILE" | head -1 | sed 's/.*"\(.*\)".*/\1/')

NEW_CODE=$((CURRENT_CODE + 1))

# Bump minor version: 1.4 -> 1.5
MAJOR=$(echo "$CURRENT_NAME" | cut -d. -f1)
MINOR=$(echo "$CURRENT_NAME" | cut -d. -f2)
NEW_NAME="$MAJOR.$((MINOR + 1))"

echo "Version: $CURRENT_NAME ($CURRENT_CODE) -> $NEW_NAME ($NEW_CODE)"

# Update build.gradle.kts
sed -i '' "s/versionCode = $CURRENT_CODE/versionCode = $NEW_CODE/" "$GRADLE_FILE"
sed -i '' "s/versionName = \"$CURRENT_NAME\"/versionName = \"$NEW_NAME\"/" "$GRADLE_FILE"

# Check signing env vars
if [ -z "$KEYSTORE_PASSWORD" ] || [ -z "$KEY_PASSWORD" ]; then
    echo "Set KEYSTORE_PASSWORD and KEY_PASSWORD env vars"
    exit 1
fi

export KEYSTORE_PATH="${KEYSTORE_PATH:-../firewall-release.jks}"
export KEY_ALIAS="${KEY_ALIAS:-firewall}"

echo "Building release..."
./gradlew clean bundleRelease assembleRelease -q

# Copy outputs
mkdir -p release
cp app/build/outputs/bundle/release/app-release.aab "release/firewall-v${NEW_NAME}.aab"
cp app/build/outputs/apk/release/app-release.apk "release/firewall-v${NEW_NAME}.apk"

echo "Done: release/firewall-v${NEW_NAME}.aab"
echo "Done: release/firewall-v${NEW_NAME}.apk"
