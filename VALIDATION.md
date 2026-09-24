# Build validation — ParkBot 1.2.3

- Package: tf.dodoapps.parkbot; versionCode 9, versionName 1.2.3
- Debug APK build passed
- Scheduler suite: 48 checks passed
- Debug lint: 0 errors, 3 advisory warnings
- Debug APK signature and packaged version verified
- Release metadata resolves version 1.2.3, tag v1.2.3, version code 9, and matching notes

The contact picker now reads the selected contact name and phone number. A saved contact appears in a themed card; removing it restores blank number entry while preserving the other form values. Existing phone-only drafts remain editable.

The UI has not been tested on a phone or emulator here. Contact selection, removal, persistence, accessibility, large text, and theme checks are listed in DEVICE-TESTS.md.

The release workflow and scheduler are unchanged. Release signing, release lint, missing-credential rejection, and workflow syntax were validated for 1.2.2; they were not rerun for this UI change. Actual GitHub signing/publishing has not been run here.

The clean source ZIP excludes private signing keys, local tools, caches, and build outputs. The separate local APK is a debug test build. Build the production APK with Release ParkBot on GitHub using the existing permanent signing key.

Debug APK SHA-256:
66DD99C0B522E349409E71152F698D5A730EC4FF9294989660D36121CECED185
