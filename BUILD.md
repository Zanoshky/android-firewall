# Build & Release Guide - Firewall

## Prerequisites

- Android Studio (latest)
- JDK 17
- Android SDK with API 35

## Debug Build

```bash
cd firewall
./gradlew assembleDebug
```

APK: `app/build/outputs/apk/debug/app-debug.apk`

Install on device:
```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

## Generate Signing Key (one time)

```bash
keytool -genkey -v -keystore firewall-release.jks \
  -keyalg RSA -keysize 2048 -validity 10000 \
  -alias firewall \
  -storepass YOUR_STORE_PASSWORD \
  -keypass YOUR_KEY_PASSWORD \
  -dname "CN=Marko Zanoski, O=Zanoshky, L=City, ST=State, C=XX"
```

## Signed Release Build (APK)

```bash
export KEYSTORE_PATH=../firewall-release.jks
export KEYSTORE_PASSWORD=YOUR_STORE_PASSWORD
export KEY_ALIAS=firewall
export KEY_PASSWORD=YOUR_KEY_PASSWORD

./gradlew assembleRelease
```

APK: `app/build/outputs/apk/release/app-release.apk`

## Signed Release Bundle (AAB for Google Play)

```bash
export KEYSTORE_PATH=../firewall-release.jks
export KEYSTORE_PASSWORD=YOUR_STORE_PASSWORD
export KEY_ALIAS=firewall
export KEY_PASSWORD=YOUR_KEY_PASSWORD

./gradlew bundleRelease
```

AAB: `app/build/outputs/bundle/release/app-release.aab`

## Install Release APK

```bash
adb install -r app/build/outputs/apk/release/app-release.apk
```

## Uninstall

```bash
adb uninstall com.zanoshky.firewall
```

If VPN prevents uninstall:
```bash
adb shell settings put secure always_on_vpn_app ""
adb shell pm clear com.zanoshky.firewall
adb uninstall com.zanoshky.firewall
```

## Google Play Upload

1. Go to [Google Play Console](https://play.google.com/console)
2. Create app → Upload the `.aab` file
3. Fill in store listing from `google_store.md`
4. Host privacy policy from `PRIVACY_POLICY.md` at a public URL
5. Complete content rating questionnaire
6. Complete data safety form
7. Submit for review

## App Details

- Package: `com.zanoshky.firewall`
- Min SDK: 23 (Android 6.0)
- Target SDK: 35 (Android 15)
