# Android 1.6.8

Failed RTC connections now release their allocation and ask the routing service
for another compatible provider. Attempts are limited to three different
providers, with 12-second credential and 20-second connection deadlines. A room
still occupied on its previous provider is allowed up to 90 seconds to complete
the server-coordinated handoff. Authentication, permission and device failures
remain terminal. Existing media capability checks are unchanged.

Leaving cancels pending connection and token requests. Failed attempts send a
zero-duration leaving heartbeat; successful connections immediately announce
presence. Runtime broker rejection for provider availability or a stale room
route uses the same bounded recovery path. Native SDK reconnection is retained
until the SDK reports failure; the previous endless LiveKit-only retry loop is
removed.

Every explicit join has a new client session UUID, retained through credential
refresh and automatic recovery. This lets the broker distinguish devices and
ignore late heartbeats from a departed session.

Automatic runtime recovery permits two cycles in three minutes, preventing
successive provider rejections from creating a continuous reconnect loop. Late
LiveKit events cannot replace another provider's roster. Administrative room
termination and identity conflicts remain terminal.

Unit coverage exercises gateway failure, cancellation, timeout, authorization,
permission errors, duplicate routes, attempt limits and room-lock waiting.
The local full gate passes all 30 unit tests, debug lint and APK assembly.
Automated checks do
not prove two-device microphone, camera, or screen-audio behavior; those remain
unverified until exercised on real Windows and Android devices.
