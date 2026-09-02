# Changelog

## 1.6.4 - 2026-09-02

- Unified Full screen and Picture in Picture controls across Stream, LiveKit, Agora, Tencent and Cloudflare video surfaces.
- Made camera and screen-share controls consistently visible without exposing provider changes.

## 1.6.3 - 2026-09-02

- Restored the screen-share action in the native Stream room.
- Routed the Stream leave button through MHTalk session cleanup so it exits immediately.

## 1.6.2 - 2026-09-02

- Updated the native Stream video SDK to restore reliable remote camera and screen-share rendering across Android and desktop peers.

## 1.6.1 - 2026-09-01

- Restored the original Android signing identity used by the 1.5.x releases.
- Restored external membership controls in the direct-download APK.
- Split release builds so only the Google Play AAB enables Play restrictions.

## 1.6.0 - 2026-08-30

- Completed native Stream chat, typing, profile and attachment events.
- Added private, expiring attachments for Stream, Agora, Tencent and Cloudflare routes.
- Added capability contract v2 and strict companion-route validation.
- Added signed RTC usage heartbeats for zero-budget provider safeguards.
- Added Play-distribution billing guards and corrected the privacy-policy URL.
- Added automated tests, lint and debug APK assembly in GitHub Actions.
