# MHTalk Android

The standalone Android client for MHTalk voice rooms, private calls, chat,
camera and screen sharing.

Version 1.3 uses the same production Supabase account system as the desktop
client. It supports username/email password sign-in, registration, mandatory
email verification, password recovery deep links and Google OAuth with PKCE.
Access and refresh tokens are encrypted with an Android Keystore-backed AES-GCM
key and are removed on sign-out.

Build and verify a debug APK with:

```powershell
.\gradlew.bat :app:testDebugUnitTest :app:lintDebug
.\gradlew.bat :app:assembleDebug
```

The shared account database migration and Cloudflare authentication gateway live
in the desktop repository. Official Android releases are published independently
from the Windows client.
