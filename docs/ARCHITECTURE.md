# MHTalk Android architecture

The Android app uses a state-driven Compose UI. Platform entry points are thin;
repositories and the session view model own side effects and expose immutable
state to composables.

## Source layout

- `MainActivity.kt`: Android lifecycle, deep links, and the Compose entry point.
- `auth`: Supabase account state, encrypted token storage, friends, and push
  registration.
- `data`: API models and the small HTTP boundary.
- `call`: the foreground service and LiveKit session owner.
- `ui/MHTalkApp.kt`: top-level navigation, permissions, and room orchestration.
- `ui/auth`: registration, verification, recovery, and OAuth UI.
- `ui/profile`: profile editing and avatar cropping.
- `ui/social`: friend search, requests, presence, and invitations.
- `ui/settings`: device and event preferences.
- `ui/components`: small UI rules shared by features.
- `ui/theme`: colors and Material theme configuration.

## Change rules

1. Composables render state and emit user intent; they do not own network or
   LiveKit clients.
2. `AuthRepository`, `SocialRepository`, and `SessionViewModel` own their
   corresponding state machines and lifecycle cleanup.
3. Avoid non-null assertions. Return a useful error when Android cannot open a
   URI, stream, or system service.
4. Build output, Gradle caches, local properties, credentials, and signing keys
   remain ignored and must never be committed.
5. Run `gradlew.bat check assembleDebug` on Windows before merging.
