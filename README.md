# MHTalk Android

The standalone Android client for MHTalk voice rooms, private calls, chat,
camera and screen sharing.

The app is visibly marked **Beta** while MHTalk uses zero-budget service
allocations. It reports its supported realtime adapters to the routing broker
and applies hard deadlines to token acquisition and room connection, preventing
an unavailable provider from leaving the UI in an infinite loading state.
Stream, Agora, Tencent, Cloudflare Realtime, Whereby Embedded, Daily Prebuilt
and native LiveKit are the currently shipped Android adapters. Capability
contract version 2 requires a complete RTC, messaging and file route before the
room opens. Stream events now power the native MHTalk chat, and guarded routes
use private, expiring Supabase Storage attachments.

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

Signed release builds are produced by the manual GitHub Actions workflow. See
[`docs/RELEASE.md`](docs/RELEASE.md) for signing identity, verification, and
publishing details.

Google Play builds set `MHTALK_PLAY_DISTRIBUTION=true` (the default) and do not
show external membership purchase or verification controls. Do not change that
setting for a Play artifact unless Play Billing or an approved alternative
billing program is implemented.
