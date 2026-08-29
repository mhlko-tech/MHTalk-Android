# AudioSwitch compatibility artifact

`audioswitch-livekit-stream-compat.aar` is LiveKit's pinned AudioSwitch fork with
one binary-compatible constructor added for Stream Video Android. LiveKit and
Stream otherwise publish different implementations under the same
`com.twilio.audioswitch` package, which cannot coexist in one APK.

The patch source is kept in `tools/audio-switch-compat`. The artifact contains
no MHTalk secrets and does not change microphone capture or audio processing;
it only lets both SDK adapters share LiveKit's maintained routing implementation.

- Upstream fork revision: `039a35aefab7747c557242fa216c9ea11743b604`
- Patched artifact SHA-256: `33AFE8447B7080B3FDD8A80A1D7F8DF8EF77406DA22AD19FD60B91853043BB1A`
