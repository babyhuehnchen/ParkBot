# ParkBot

A small native Android app for parking renewals by SMS, with Material 3 controls and light/dark themes.

- App name: **ParkBot**
- Package: **tf.dodoapps.parkbot**
- Android 8.0 (API 26) or newer
- One parking session at a time

## Use

1. Choose **Start now**, or turn it off and select a start time. When checked, the start-time box disappears and the stop-time box is centered. Turning Start now off restores the chosen start time.
2. Select a stop time and interval. There is no preset interval: enter the number of minutes yourself. Your chosen value is remembered for next time. Intervals of 1–1440 minutes are supported.
3. Choose a phone number from contacts, or enter a parking short code directly.
4. Enter the exact SMS text your parking service expects.
5. Tap **Start parking**, grant SMS and Phone permissions, and allow Alarms & reminders when prompted. Choose the sending SIM using the visible **Sending SIM** control below the recipient. Review the confirmation and start.

The first SMS goes at the selected start time (or on the next full minute with Start now). A start time already in the past means **tomorrow**, and a stop time earlier than the start means the following day. The confirmation displays the day as well as the time. These are single sessions, not daily repeating schedules.

Each subsequent renewal ignores seconds and milliseconds in the successful send time: **sent minute + interval**. For example, 14:00:01 or 14:00:59 with a 1-minute interval both schedule the next SMS for 14:01:00; with 3 minutes, both schedule 14:03:00. This counts clock minutes, not a minimum elapsed duration. A successful send is timed from the phone sent callback, once all parts are confirmed. Android alarm dispatch and carrier processing can still delay actual sending; clock alignment does not guarantee second-exact SMS transmission. If Android wakes ParkBot late, it sends **one** SMS before the stop time and shifts the following renewal forward. It never sends a backlog and has no lateness cutoff that silently skips a renewal. It never starts another SMS at or after the stop time.

If sending fails or no sent callback arrives within five minutes, ParkBot stops with an explanation in the app and History. When enabled, notifications also show failures and a Stop action. It does not automatically retry an uncertain SMS. Stop/restart preserves the known cooldown for the same number. A phone-clock change stops the session for review. Persisted state restores alarms after reboot or an app update.

## Phone setup

Use **Setup** for permissions, themes, notifications, and app/battery settings. Choose the SIM on the main page. Notifications are optional but useful when the phone is locked. Some phones need ParkBot battery usage set to **Unrestricted** for unattended operation. Android and mobile networks can still delay sending.

The contact picker grants access only to the chosen number; ParkBot does not request full address-book access. It has no Internet or inbox permissions. Session details, the form, and the last 100 history entries stay on the phone; backup and transfer are disabled.

SEND_SMS is a restricted Android permission: the installer must allow it as well as the user. If the grant stays blocked, check the installer. Parking short codes may also require Premium SMS access in Android settings. ParkBot does not bypass those controls.

A successful SMS callback means the phone reports sending, **not** that the parking provider accepted the purchase. Check the provider's confirmation and the ticket expiry. The app does not read those replies. Turning off or force-stopping the phone/app can interrupt renewals. Messages already submitted cannot be recalled.

## Appearance

Open **Setup → Theme**, choose a mode, adjust the switches, and tap **Apply**:

- **Light** always uses light mode.
- **Dark** always uses dark mode.
- **Follow System Settings** follows the phone light/dark setting.

With both switches off, Light uses white backgrounds and Dark uses neutral dark grey, with neutral controls and surfaces.

**OLED Dark** changes the standard dark background to pure black; it has no effect in light mode. **Material 3** uses the matching light/dark Material palette, with wallpaper-based colors on supported devices and the standard Material palette otherwise. Material 3 takes priority over OLED and controls the background too. The OLED switch is inactive while Material 3 is on, and its previous setting is retained.

Choices are remembered. Upgrading preserves the previous Light, OLED Dark, Follow System Settings, or Material 3 selection as the equivalent mode and switches. Changing themes preserves the form and any running parking session.
Dynamic colors use the [Material DynamicColors API](https://developer.android.com/reference/com/google/android/material/color/DynamicColors).

## Build

Use JDK 17, Android SDK Platform 35, and Build-Tools 35.0.0. Gradle 8.11.1 and Android Gradle Plugin 8.9.1 are pinned.

On Windows:

```powershell
.\build.ps1
```

The script uses a standard installed JDK/SDK or the project-local tools when present. On other systems, set JAVA_HOME and ANDROID_HOME and run:

```sh
sh ./gradlew :core:check :app:assembleDebug :app:lintDebug
```

APK: `app/build/outputs/apk/debug/app-debug.apk`. This is a debug-signed test build; keep the signing key stable for updates. Uninstalling clears local settings/history.

GitHub Actions builds and checks the app on push, pull request, or manual dispatch. Push/manual builds require PARKBOT_KEYSTORE_BASE64 and use your existing signing key; pull-request builds use a temporary key. See [signing setup](RELEASE.md#github-actions-signing). The workflow does not send any SMS.

## Verification

`core:check` runs standalone Java regression checks for late alarms, full cooldowns, restart recovery, duplicate callbacks, multipart sends, stop cutoffs, permissions, and overnight sessions. Android lint checks the platform code. See `DEVICE-TESTS.md` for the remaining real-phone checks.

## References

- [Android contact picker](https://developer.android.com/guide/components/intents-common#PickContactDat)
- [Android alarm scheduling](https://developer.android.com/develop/background-work/services/alarms)
- [SMS sent callbacks](https://developer.android.com/reference/android/telephony/SmsManager)

## Releases

See [release notes](releases/v1.2.1.md) and the [changelog](CHANGELOG.md). The current downloadable package is a debug-signed test build.

Run the PowerShell script prepare-release.ps1 to create the clean source ZIP, APK, checksums, and release notes under dist/. See [RELEASE.md](RELEASE.md) for upload steps and signing details.

## License

[MIT](LICENSE). Third-party components retain their respective licenses.

