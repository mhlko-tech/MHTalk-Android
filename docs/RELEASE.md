# Android release process

Official Android releases are built only by
`.github/workflows/release-android.yml`. The workflow tests and lints the app,
builds a Play-safe signed APK and AAB, verifies the signing certificate, writes
SHA-256 checksums, and publishes the verified files to a GitHub release.

## Signing identity

- Alias: `mhtalk`
- Algorithm: RSA 4096 / SHA-256
- Validity: 31 August 2026 through 16 January 2054
- Certificate SHA-256:
  `20:D4:08:63:25:6C:A1:D9:90:2B:4F:91:79:03:CA:FD:C7:18:4E:38:D3:D0:5D:20:95:4C:C1:C2:96:14:FD:D1`

The private key and its passwords are never committed. GitHub Actions reads
them from these repository secrets:

- `MHTALK_ANDROID_KEYSTORE_BASE64`
- `MHTALK_ANDROID_STORE_PASSWORD`
- `MHTALK_ANDROID_KEY_ALIAS`
- `MHTALK_ANDROID_KEY_PASSWORD`

Keep an encrypted offline copy of the keystore in addition to GitHub Secrets.
Do not rotate this identity for normal releases: Android will reject an update
whose certificate does not match the installed application.

## Publish a release

1. Set `versionCode` and `versionName` in `app/build.gradle.kts`.
2. Merge the release commit into `main` and confirm the Android CI workflow is
   green.
3. Run **Publish signed Android release** on `main` and enter the exact
   `versionName`.
4. Confirm that the workflow signature check and the GitHub release both pass.

The release workflow is idempotent: rerunning the same version repairs or
replaces its assets instead of creating a second release.
