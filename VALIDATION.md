# Build validation — ParkBot 1.3.0

- Package: tf.dodoapps.parkbot; versionCode 11, versionName 1.3.0
- All five app/core classes and the test suite converted from Java to Kotlin
- Kotlin 2.2.21, Android Gradle Plugin 8.10.1, Gradle 8.11.1, JDK 17
- Fresh debug and release APK builds passed after cleaning both modules
- All 56 existing scheduler regression checks retained and passed
- Debug lint: 0 errors, 14 advisory warnings
- Release lint: 0 errors, 14 advisory warnings
- Warnings concern dependency updates, telephony requirements, optional Kotlin extensions, and text localization
- Debug APK signature verified; signing certificate matches the 1.2.4 debug APK
- Release signing tested with a disposable local certificate; temporary key deleted
- Release APK signature, version, and non-debuggable flag verified
- Existing preference/JSON keys and broadcast action strings compared with the Java implementation and unchanged
- Both GitHub workflows validated with actionlint (shellcheck/pyflakes unavailable)
- Release metadata verified: version 1.3.0, tag v1.3.0, version code 11

The existing Material screens, contact card, theme choices, saved data format, whole-minute SMS timing, and one-time end notifications are preserved. No new contacts permission or storage migration is introduced. Session persistence still checks a synchronous commit before submitting SMS.

No phone or emulator test was performed here. Verify an in-place update, permissions, themes, contacts, SIM choice, SMS delivery, and reboot recovery on a device using DEVICE-TESTS.md before publishing.

The local release APK is a validation artifact signed with a disposable key and is not distributed. Use Release ParkBot on GitHub with the existing permanent release key for production builds. Actual GitHub publishing was not run here.

The source ZIP excludes local tools, backups, caches, build output, and signing keys. When applying it to an existing repository, delete the six old tracked .java files; the replacement .kt files live in the same source directories. See RELEASE.md.

Debug APK SHA-256:
B4FAD9CE9566EAEEAEF536EEEBD1ED559DE398C2039D723471D6A8B31F48B54A
