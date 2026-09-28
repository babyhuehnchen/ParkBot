# Build validation — ParkBot 1.2.4

- Package: tf.dodoapps.parkbot; versionCode 10, versionName 1.2.4
- Debug APK build passed
- Scheduler suite: 56 checks passed, including eight new end-notification regression checks
- Debug lint: 0 errors, 3 advisory warnings
- APK signature verified; certificate matches the 1.2.3 debug APK
- Packaged APK version and release metadata verified

End notifications are emitted only after persisting an active-to-inactive session transition. Alarm recovery refreshes notifications only for active sessions. Duplicate stop requests leave ended sessions and history unchanged.

Regression checks cover idle launches, manual stop, automatic cutoff, last-send completion, failure, active recovery, subsequent sessions, and clock changes. Recovery is simulated by creating a new Engine against saved state. Actual Android notification delivery, dismissal, and reboot behavior have not been tested on a device here; see DEVICE-TESTS.md.

The owner confirmed the 1.2.3 contact-card update works on their phone. That UI is unchanged in 1.2.4.

Release workflows are unchanged. Release signing, release lint, missing-credential rejection, and workflow syntax were validated for 1.2.2; they were not rerun for this update. Actual GitHub signing/publishing has not been run here.

The clean source ZIP excludes private signing keys, local tools, caches, and build outputs. The separate local APK is a debug test build. Build the production APK with Release ParkBot on GitHub using the existing permanent signing key.

Debug APK SHA-256:
43CBAF961825DAD8A52EEA0DE26ADD201DCCC85A7EB63AE85021DF9CF2C1FFEA
