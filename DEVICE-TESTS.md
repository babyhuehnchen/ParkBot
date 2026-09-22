# Phone checks before relying on parking renewals

The build and core tests cannot prove carrier delivery or parking-provider acceptance.
Use an authorized test number first; each live SMS can incur a charge.

- Install the APK and open ParkBot. Grant SMS and Phone access, enable Alarms & reminders, and optionally allow notifications.
- Choose a contact with multiple phone numbers and verify the selected number. Cancel the picker and verify the existing number remains.
- Verify manual parking short-code entry and confirm the correct SIM, especially on a dual-SIM phone.
- Use Start now with an 18-minute interval and a stop time in the future. Confirm one SMS is sent and History updates to sent. Verify the provider's reply separately.
- Verify the next planned SMS is the successful send time with seconds removed, plus 18 minutes. Lock the phone during this test.
- Repeat with a selected future start and an overnight stop; review the dates in the confirmation before enabling.
- After a delayed wakeup, verify one SMS is sent and the next time is calculated from its sent minute; there must be no catch-up burst.
- Tap Stop in the app and in the notification. Verify no later SMS is newly submitted. A pending modem message may still leave the phone.
- Test no-service/radio-off failure, denied short-code access, missing callbacks, revoked permissions, removed SIM, and reboot. Check that uncertain sends are never automatically retried.
- Rotate the phone and background/reopen the app while editing. Verify number, text, interval, and chosen times survive.
- Change the phone clock during a session; verify it stops for review.
- Confirm zero new submissions at/after the stop time and no recurrence on the following day.

## Material 3 update

- Check light and dark themes: text, filled/tonal buttons, outlined fields, cards, dialogs, and time pickers should stay readable.
- With Start now checked, the start button must disappear and the stop button must be centered. Uncheck it: both buttons must share the row, the original chosen start time must reappear, and the picker must open.
- Select a SIM directly under the recipient. Verify its slot and name remain visible after returning from permissions and after reopening ParkBot; verify the same SIM appears in the confirmation and active session.
- Choose a one-minute interval. Verify the next planned submission is the sent minute plus one minute; Android idle restrictions can delay short intervals.

## Whole-minute scheduling

- Start partway through a minute: the first scheduled SMS should be at the next HH:mm:00.
- After a sent callback with nonzero seconds, verify the next planned SMS ignores seconds. Example: 14:00:05 + 18 minutes becomes 14:18:00; 14:00:59 + 1 minute becomes 14:01:00.
- Verify no extra send appears at/after the stop time due to rounding.
- Reopen an older active session: a scheduled renewal should be recalculated from the last successful sent minute, removing the extra minute from 1.1.1. A pending SMS must not be resent.
- Android or the carrier can delay actual execution; verify the planned times separately from physical delivery.


## Status bar spacing (1.1.3)

- Check the PARKBOT heading sits below the status bar on first launch and after starting/stopping a session.
- Repeat with three-button and gesture navigation, portrait/landscape, and light/dark themes. No content should overlap status icons, a display cutout, or the navigation controls.
- Open and dismiss the keyboard while editing the number/message. The form remains reachable and top padding does not grow on repeated insets updates.

## Theme modes and switches (1.2.1)

- Verify Setup has no Choose SIM action; the main-page Sending SIM control still works.
- Verify theme modes appear in this order: Light, Dark, Follow System Settings. OLED Dark and Material 3 are separate switches.
- With switches off, Light stays white even when the phone is dark. Dark stays neutral dark grey even when the phone is light. Check cards, dialogs, fields, time pickers, and controls for unwanted green tints.
- Follow System Settings changes between light and dark when the phone setting changes.
- Enable OLED with Material 3 off. Dark mode has a pure-black page background. Light remains white. Follow System Settings uses OLED only when the phone is dark.
- Enable Material 3 in Light, Dark, and Follow System Settings. Check the respective Material palettes, including backgrounds. Test wallpaper colors on supported devices and the standard Material fallback on an older device.
- With both switches stored as on, Material 3 owns the background: OLED must not force it black. The OLED switch is inactive until Material 3 is turned off, then its previous value is restored.
- Apply changes and reopen/rotate the app; choices must persist. Canceling the theme dialog must discard pending mode/switch changes.
- Upgrade from each 1.2.0 theme choice: Light becomes Light; OLED Dark becomes Dark + OLED; Follow System remains Follow System; Material 3 becomes Follow System + Material 3.
- Check status/navigation icons, text, buttons, checkboxes, dialogs, both time pickers, and the theme radio buttons/switches remain readable for every combination.
- Enter form values, change themes, and verify values including an empty interval survive.
- Change theme during an active session: status, selected SIM, history, and next scheduled SMS remain intact.
- Verify Start now still hides the start-time box and centers the stop-time box, and the PARKBOT heading stays below the status bar.
