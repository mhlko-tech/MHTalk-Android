# MHTalk Android

The standalone Android client for MHTalk voice rooms, private calls, chat,
camera and screen sharing.

The app is visibly marked **Beta** while MHTalk uses zero-budget service
allocations. It reports its supported realtime adapters to the routing broker
and applies hard deadlines to token acquisition and room connection, preventing
an unavailable provider from leaving the UI in an infinite loading state.
Stream, Agora, Tencent, Whereby Embedded, Daily Prebuilt and native LiveKit are
the currently shipped Android adapters; the server selects one compatible
provider before the room opens.

The Android client uses the same production Supabase account system as the
desktop client. It supports username/email password sign-in, registration, mandatory
email verification, password recovery deep links and Google OAuth with PKCE.
Access and refresh tokens are encrypted with an Android Keystore-backed AES-GCM
key and are removed on sign-out.

Build and verify a debug APK with:

```powershell
.\gradlew.bat :app:testDebugUnitTest :app:lintDebug
.\gradlew.bat :app:assembleDebug
```

The source layout and dependency rules are documented in
[`docs/ARCHITECTURE.md`](docs/ARCHITECTURE.md).

The shared account database migration and Cloudflare authentication gateway live
in the desktop repository. Official Android releases are published independently
from the Windows client.
