# Build validation — ParkBot 1.2.1

- Package: tf.dodoapps.parkbot; versionCode 7, versionName 1.2.1
- Build, core checks, APK assembly, Android lint: successful
- Scheduler suite: 48 checks passed; scheduler code unchanged
- Android lint: 0 errors, 3 advisory warnings
- APK signature verified; certificate matches 1.2.0 for in-place updates
- Build type: debug
- Deliverable: ParkBot.apk

Theme modes are Light, Dark, and Follow System Settings, in that order. OLED Dark and Material 3 are independent saved switches. Standard palettes use neutral white/dark grey surfaces. OLED applies only in standard dark mode. Material 3 has priority and uses its own light/dark backgrounds and colors, with dynamic colors where supported and a standard Material fallback otherwise. The previous OLED preference is retained while its switch is inactive under Material 3.

The previous theme choices migrate to equivalent modes and switches. Applying the theme saves the form before activity recreation; Cancel discards pending changes. Existing scheduling and system inset handling are unchanged.

The owner confirmed that the app and theming are working on their phone. The wider device matrix remains unverified; see DEVICE-TESTS.md for the remaining checks.

APK SHA-256:
20C29E8670E3D1F1F44119FB043D91C1937188078542DD519F12C2968558D9E3

