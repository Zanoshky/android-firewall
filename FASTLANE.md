# Fastlane Setup Guide - Firewall

## Prerequisites

- Ruby 2.7+ (macOS ships with it)
- Bundler: `gem install bundler`
- A Google Play Developer account with the app already uploaded once manually

## Install

```bash
cd firewall
bundle install
```

## Google Play API Setup

1. Open [Google Play Console](https://play.google.com/console)
2. Go to Setup > API access
3. Link or create a Google Cloud project
4. Create a new service account with "Service Account User" role
5. In Google Cloud Console, create a JSON key for that service account
6. Download the JSON key and save it as `fastlane/play-store-credentials.json`
7. Back in Play Console, grant the service account "Release manager" permissions

The credentials file is gitignored. Never commit it.

## Available Lanes

### Build

```bash
bundle exec fastlane debug           # Debug APK
bundle exec fastlane build_release   # Signed release APK + AAB
```

### Deploy

```bash
bundle exec fastlane internal        # Build and upload to internal testing
bundle exec fastlane beta            # Build and upload to closed testing
bundle exec fastlane release         # Build and upload to production
```

### Metadata

```bash
bundle exec fastlane metadata        # Upload title, descriptions, changelogs
bundle exec fastlane metadata_full   # Upload metadata + screenshots
bundle exec fastlane screenshots     # Upload screenshots only
bundle exec fastlane validate        # Dry run - validate metadata
```

### Promote

```bash
bundle exec fastlane promote_to_beta        # Internal -> Beta
bundle exec fastlane promote_to_production  # Beta -> Production
```

## Metadata Structure

Store listing metadata lives in `fastlane/metadata/android/en-US/`:

```
fastlane/metadata/android/en-US/
  title.txt              # App name (30 chars max)
  short_description.txt  # Short description (80 chars max)
  full_description.txt   # Full description (4000 chars max)
  video.txt              # Promo video URL (optional)
  changelogs/
    5.txt                # Changelog for versionCode 5
    default.txt          # Fallback changelog
  images/
    phoneScreenshots/    # Phone screenshots (1080x1920)
    icon.png             # Hi-res icon (512x512)
    featureGraphic.png   # Feature graphic (1024x500)
```

## Environment Variables

For signing, set these before running build/deploy lanes:

```bash
export KEYSTORE_PASSWORD="your_store_password"
export KEY_PASSWORD="your_key_password"
export KEYSTORE_PATH="../firewall-release.jks"  # optional, defaults to this
export KEY_ALIAS="firewall"                      # optional, defaults to this
```

## Typical Release Workflow

```bash
# 1. Update version in app/build.gradle.kts
# 2. Add changelog in fastlane/metadata/android/en-US/changelogs/<versionCode>.txt
# 3. Deploy to internal testing
bundle exec fastlane internal

# 4. Test on real devices
# 5. Promote to beta
bundle exec fastlane promote_to_beta

# 6. After beta validation, promote to production
bundle exec fastlane promote_to_production
```
