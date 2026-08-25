# MHTalk Privacy Policy

Effective date: August 26, 2026

MHTalk is a realtime voice, video, screen-sharing and messaging application developed by MHTalk (`mhlko-tech`). This policy explains how the Android and Windows applications handle information.

## Information processed

- Profile information you choose to provide: display name, biography and profile image.
- Account information: account identifier, username and email address. Google provides basic account information only when you choose Google sign-in. Passwords are processed and hashed by Supabase Auth and are never stored by MHTalk.
- Social information needed for cross-device features: friend relationships, blocks and notification device tokens.
- Realtime room content: microphone audio, camera video and screen share while you enable those features.
- Messages and files you choose to send to a room.
- Room access information, including temporary participant identifiers and private invitation codes.
- Safety reports you submit, including the reported participant, message identifier and reported text where applicable.

## How information is used

Information is used only to provide room access, deliver realtime communication, moderate the public Main chat, maintain connection stability, and investigate safety reports. MHTalk does not sell personal information and does not include advertising or behavioral analytics.

## Service providers and transmission

Supabase hosts authentication and account data. Realtime media, messages and file streams are carried by LiveKit Cloud. MHTalk's Cloudflare Worker rate-limits account requests, privately resolves usernames, issues secure room tokens, moderates public text and accepts safety reports. Data is encrypted in transit using HTTPS/WSS and the security provided by WebRTC.

## Storage and retention

Account profiles, friend relationships, blocks and notification tokens remain in Supabase until the account data is deleted. Encrypted session tokens and local preferences remain on your device. Received attachments are stored in the app's temporary cache. Private-room invitation metadata expires after seven days. Safety reports expire after 30 days. Realtime room media is not recorded by MHTalk. Public and private chat history is not maintained as a permanent cloud archive by MHTalk.

## Your choices

Microphone, camera, notification and screen-capture access require Android permission or system confirmation. You can sign out, turn microphone, camera and screen sharing off at any time, leave a room, block a participant, or clear the application's storage to remove local session and cache data. Uninstalling the app also removes its local data. Contact MHTalk to request deletion of hosted account data.

## Safety and children

MHTalk is not directed to children. Users must not distribute illegal, exploitative, harassing, sexually explicit, privacy-violating or infringing content. Public Main messages are filtered, and users can report messages or users and block users in the app.

## Requests and contact

For privacy questions or deletion requests, contact the developer through Instagram at `@m.ed1t` or open a private contact request through the MHTalk GitHub repository maintained by `mhlko-tech`. A request should include the account username or email address and enough information to verify account ownership; MHTalk cannot retrieve chat or media that it does not permanently store.

This policy may be updated when application behavior or legal requirements change. The effective date above identifies the current version.
