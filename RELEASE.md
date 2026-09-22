# Building and publishing ParkBot

## One-time release setup

Create a permanent release keystore in Android Studio using **Build → Generate Signed Bundle / APK → APK → Create new**. Save it outside the repository, choose an alias such as parkbot, and keep a private backup and its passwords.

Under your GitHub repository **Settings → Secrets and variables → Actions**, add:

| Secret | Value |
| --- | --- |
| PARKBOT_RELEASE_KEYSTORE_BASE64 | The release keystore converted to Base64 |
| PARKBOT_RELEASE_STORE_PASSWORD | Keystore password |
| PARKBOT_RELEASE_KEY_ALIAS | Key alias, for example parkbot |
| PARKBOT_RELEASE_KEY_PASSWORD | Key password |

To copy the keystore as Base64 in PowerShell, replace the example path with its actual location:

~~~powershell
[Convert]::ToBase64String(
    [IO.File]::ReadAllBytes("C:\YourPrivateFolder\parkbot-release.jks")
) | Set-Clipboard
~~~

Paste into the GitHub secret. Do not commit the keystore, passwords, or Base64 text. The release workflow deliberately rejects the usual Android debug certificate.

A new release key cannot make an ordinary in-place update to the old debug-signed installation. Uninstalling the old app clears its settings and history. Keep the permanent release key unchanged for subsequent releases.

## Publish directly from GitHub

1. Upload or commit this source, including the hidden .github folder, to the default branch.
2. Add the four secrets above.
3. Open **Actions → Release ParkBot → Run workflow**.
4. Select the branch containing the version you want to publish and click **Run workflow**.

The workflow reads the version from app/build.gradle.kts, builds and signs the release APK, runs checks, verifies the APK is not debuggable, and automatically publishes a GitHub Release with the APK and SHA256SUMS.txt. The first version prepared here is **1.2.2** (version code **8**).

It creates tag **v1.2.2** at the exact commit that was built. A tag already pointing to a different commit or an existing release causes publishing to stop rather than replacing it. Release notes come from releases/v1.2.2.md.

You can alternatively push a matching version tag, such as **v1.2.2**, to start the same workflow. Ordinary branch pushes and pull requests do not publish releases.

The built files also appear under the run's **Artifacts** if publishing fails. If a failed upload leaves a draft release, inspect/delete that incomplete draft before retrying; an existing release is never overwritten automatically. Nothing is published if signing, tests, lint, or APK verification fails.

The built-in GitHub token is used for publishing; you do not need a personal access token. The publish job requests contents: write. Repository/organization policy must permit that permission.

## Publish the next version

Before publishing again:

1. Increase **versionCode** and change **versionName** in app/build.gradle.kts.
2. Add matching notes, for example releases/v1.2.3.md.
3. Commit the changes and run **Release ParkBot** again, or push the matching tag.

Changing only the GitHub tag does not change the app version. The workflow checks that a pushed tag matches the version in the app.

## Debug builds

The separate **Build ParkBot** workflow remains for testing and does not publish releases. Push/manual debug builds use the existing **PARKBOT_KEYSTORE_BASE64** secret, while pull-request builds use a temporary key. That debug secret is separate from the four release secrets and is not required by **Release ParkBot**.

To build a debug APK on Windows:

~~~powershell
.\build.ps1
~~~

This uses an installed JDK/SDK or the ignored project-local tools. On other systems, use JDK 17, Android SDK Platform 35, and Build-Tools 35.0.0, then run:

~~~sh
sh ./gradlew :core:check :app:assembleDebug :app:lintDebug
~~~

The debug APK is written to app/build/outputs/apk/debug/app-debug.apk.

## Local release builds

Set PARKBOT_RELEASE_KEYSTORE_PATH to the private keystore path and set PARKBOT_RELEASE_STORE_PASSWORD, PARKBOT_RELEASE_KEY_ALIAS, and PARKBOT_RELEASE_KEY_PASSWORD in your local environment. Then run:

~~~sh
sh ./gradlew :core:check :app:assembleRelease :app:lintRelease
~~~

On Windows use gradlew.bat instead of sh ./gradlew. The release APK is app/build/outputs/apk/release/app-release.apk. Missing signing credentials cause release builds to fail; they never fall back to debug signing.

## Clean source ZIP

~~~powershell
.\prepare-release.ps1
~~~

This local script creates a clean source ZIP and a **debug test APK**, checksums, and notes under dist/. The source ZIP is suitable for updating the repository; use the **Release ParkBot** workflow to obtain the production APK. The script does not publish anything.

The export excludes local tools, caches, build output, and signing material. Use the extracted source ZIP or a Git client that respects .gitignore; do not upload the entire unfiltered working directory through the GitHub website.

## References

- [Android app signing](https://developer.android.com/studio/publish/app-signing)
- [GitHub release creation](https://cli.github.com/manual/gh_release_create)
- [Device checks](DEVICE-TESTS.md)
- [MIT license](LICENSE)
