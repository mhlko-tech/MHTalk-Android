# Android release process

Official Android releases are built only by
`.github/workflows/release-android.yml`. The workflow tests and lints the app,
builds a signed direct-download APK and a Play-safe AAB, verifies the signing
certificate, writes SHA-256 checksums, and publishes the verified files to a
GitHub release.

## Signing identity

- Alias: `mhtalk`
- Certificate SHA-256:
  `20:2D:85:E6:3B:2A:F1:1A:BA:0D:44:CF:B3:1B:AA:A1:44:60:19:3D:24:64:34:4C:D0:63:F2:E5:3C:B6:FC:5D`

The private key and its passwords are never committed. GitHub Actions reads
them from these repository secrets:

- `MHTALK_ANDROID_KEYSTORE_BASE64`
- `MHTALK_ANDROID_STORE_PASSWORD`
- `MHTALK_ANDROID_KEY_ALIAS`
- `MHTALK_ANDROID_KEY_PASSWORD`

Keep an encrypted offline copy of the keystore in addition to GitHub Secrets.
Do not rotate this identity for normal releases: Android will reject an update
whose certificate does not match the installed application.

The workflow builds the GitHub APK with `MHTALK_PLAY_DISTRIBUTION=false` and
the Google Play AAB with `MHTALK_PLAY_DISTRIBUTION=true`. Never reuse the Play
setting for the direct APK because it hides MHTalk's external membership UI.

## Publish a release

1. Set `versionCode` and `versionName` in `app/build.gradle.kts`.
2. Merge the release commit into `main` and confirm the Android CI workflow is
   green.
3. Run **Publish signed Android release** on `main` and enter the exact
   `versionName`.
4. Confirm that the workflow signature check and the GitHub release both pass.

The release workflow is idempotent: rerunning the same version repairs or
replaces its assets instead of creating a second release.
