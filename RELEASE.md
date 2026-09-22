# Preparing and publishing a GitHub release

## Prepared files

Run this from PowerShell in the project folder:

~~~powershell
.\prepare-release.ps1
~~~

The script builds and checks the app, verifies the APK signature, and creates:

- dist/ParkBot-1.2.1-source.zip: source files for the repository, including hidden GitHub configuration.
- dist/ParkBot-1.2.1-debug.apk: locally signed test APK.
- dist/SHA256SUMS.txt: checksums for the source ZIP and APK.
- dist/RELEASE-NOTES.md: text for the GitHub release description.

The version and filenames are read from app/build.gradle.kts. Add matching notes under releases/vVERSION.md when increasing the version. Output under dist/ is ignored by Git.

The source export uses an explicit file list. It excludes local tools, caches, build output, personal machine settings, and signing material. Do not upload the entire unfiltered working directory through the GitHub website: website uploads do not apply .gitignore. Use the extracted source ZIP or a Git client that respects ignore rules.

## Repository and release

1. Create the GitHub repository and add the extracted source files, including .github, .gitignore, and .gitattributes. Upload the extracted files as the repository contents, not the ZIP itself.
2. Review the file list before committing. The repository should contain source and documentation, not the APK or private keys.
3. Add the signing secret described below, then let the Build ParkBot workflow complete. It runs the core checks, builds a debug APK, and runs Android lint.
4. Create a release with tag **v1.2.1** pointing to the uploaded source commit and title **ParkBot 1.2.1**.
5. Paste dist/RELEASE-NOTES.md into the description. Attach the local ParkBot-1.2.1-debug.apk and SHA256SUMS.txt; optionally attach the matching source ZIP. GitHub also provides source archives for the tag.
6. Mark this debug-signed build as a pre-release, review the assets, and publish when ready.

No repository or release is created remotely by the packaging script. See [GitHub releases](https://docs.github.com/en/repositories/releasing-projects-on-github/about-releases).

## GitHub Actions signing

Before running a push or manual build, add the repository secret **PARKBOT_KEYSTORE_BASE64** under **Settings → Secrets and variables → Actions → New repository secret**.

On the Windows computer used to build the existing APK, run:

~~~powershell
[Convert]::ToBase64String(
    [IO.File]::ReadAllBytes("$env:USERPROFILE\.android\debug.keystore")
) | Set-Clipboard
~~~

Paste that clipboard value into the secret. Do not put it in source files, issues, or logs. Keep a private backup of this keystore. The workflow restores it only for push and manual builds, then removes the restored file after the build. A missing secret fails the build rather than silently using a different key.

The current Gradle debug signing configuration already supplies the standard debug alias and passwords; no other secrets are needed for this existing key. Push/manual builds produce **ParkBot-signed-debug**, compatible with the installed local build when the original key is supplied. Pull-request builds do not receive the key and produce **ParkBot-pr-debug** with a temporary signature.

The SDK setup explicitly installs platform-tools, avoiding the obsolete tools package that caused the logged setup failure.


## Signing and future production builds

The current APK is debug-signed and remains compatible with the previously installed local build. The private key is not inside the APK or source archive. Keep its keystore outside the repository and retain a private backup if you need to issue compatible updates.

Push/manual GitHub builds use the original key from the repository secret. Pull-request builds use a different temporary debug key and are only for testing.

For a future production build, use Android Studio **Build → Generate Signed Bundle / APK**, select APK and the release variant, and create or select a private release keystore outside the repository. Retain that key for future releases and keep passwords out of source. Switching signing certificates means the existing debug installation cannot receive an ordinary in-place update; plan that transition before distributing production builds. Production signing is not configured by this preparation.

See [Android app signing](https://developer.android.com/studio/publish/app-signing).

## License

Project source is provided under the [MIT license](LICENSE). Existing third-party files retain their own license headers.


