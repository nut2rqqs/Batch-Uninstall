# Batch Uninstall

An Android utility that inventories installed apps, shows whether each app has a launcher activity, and lets the user queue selected apps for uninstall.

## Current implementation

- Lists installed packages visible to the app.
- Shows app label, package name, and whether Android exposes a launch intent.
- Search by app name or package name.
- Optionally includes system apps.
- Selects multiple apps and sends uninstall requests sequentially.
- Android displays its own confirmation UI for each uninstall request.

## Important limitations

- **A launcher-intent check is not a full app health check.** It only indicates whether PackageManager currently exposes an activity that can launch the app. It does not prove that the app can sign in, connect to a server, or work correctly.
- **Uninstall is not silent.** A normal third-party app cannot remove other apps without the Android package installer/user confirmation. This app opens the system uninstall screen one package at a time.
- **Package visibility:** `QUERY_ALL_PACKAGES` is declared to inventory installed apps on Android 11+. Google Play restricts this permission and requires an eligible core use case and declaration. Review the policy before publishing on Google Play. Sideloaded/private distribution has a different review path but still follows Android platform rules.
- Device-owner / managed-device privileges are not implemented.

## Build

Open this repository in Android Studio with Android SDK 35 and JDK 17, then sync the Gradle project and build the `app` module.

This repository does not currently include the Gradle wrapper. Android Studio can import the Gradle project using a locally installed compatible Gradle distribution.

## Project configuration

- Package: `com.nut2rqqs.batchuninstall`
- Minimum Android: 8.0 (API 26)
- Target Android: API 35
- Kotlin / Android Gradle Plugin versions are declared in the root `build.gradle.kts`.
