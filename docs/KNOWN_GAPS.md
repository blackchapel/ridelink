# RideLink -- known gaps

Open limitations and unresolved risks, stated plainly so nothing gets
silently forgotten. Update this file (add, resolve, or re-open items) as
part of the change that touches them -- see `CLAUDE.md`.

## Voice chat

- **Send-side IPC overhead is unverified.** Each ~20ms audio chunk is
  sent as its own `sendPayload()` call (~50/sec/direction), crossing the
  Play Services Binder IPC boundary far more often than the single
  continuous `Payload.fromStream()` call used before the latency fix. If
  real-device testing shows this itself adds jitter, the fix is a small
  bounded send-side queue mirroring `AudioPlayback`'s (not a redesign) --
  not yet needed as far as testing has shown.
- **BT-SCO + BT-Classic contention hypothesis is untested.** If a rider
  wears a Bluetooth headset (SCO) while the two phones are also paired to
  each other over Bluetooth Classic, one phone runs two simultaneous
  Bluetooth roles on one radio, which can cause scheduling
  contention/jitter. Flagged during design, never specifically tested.
- **No compressed codec.** Deliberately deferred, not an oversight: both
  Bluetooth Classic and Wi-Fi Direct comfortably exceed the current
  ~128kbps raw 8kHz PCM16 bitrate in normal operation, so bitrate was
  never shown to be the bottleneck. Revisit only if real-device testing
  shows the remaining problem is specifically bandwidth-limited.
- **Lossy delivery is a deliberate trade-off, not free.** The
  sequence-number gap-skip receive policy (`VoiceChatSession`) means a
  lost/delayed chunk produces a brief audio glitch/dropout instead of a
  delayed-but-complete stream. Confirmed acceptable in testing so far, but
  worth remembering if audio quality complaints come up again.
- 8kHz mono PCM16 is a real quality ceiling (telephony-band, not music
  quality) -- an intentional, conservative starting point given the
  Bluetooth-at-range throughput unknown, not yet revisited.

## Connected-status notification / dynamic foreground-service type

- **Dynamic foreground-service-type change is unverified on a real
  device.** `RiderForegroundService`/`PillionForegroundService` now add
  the `microphone` type to themselves mid-life (re-invoking
  `startForeground()`) for the duration of a call, instead of running a
  separate `VoiceCallForegroundService` the way this app used to -- a
  deliberate reversal of an earlier decision that avoided this
  specifically for lack of real-device verification. If it doesn't
  behave correctly (the connection destabilizes when a call starts/ends,
  or the OS rejects/ignores the type change on some version/OEM), the
  documented fallback is to reintroduce a second, call-scoped foreground
  service rather than assume the single-service design forward.
- **Channel-switch + `setOnlyAlertOnce` alerting behavior is not
  documented with certainty** across Android versions/OEMs -- needs
  real-device confirmation that "just connected" and "incoming offer"
  actually produce a heads-up alert rather than a silent update.
- **An unexpected mid-ride disconnect stays quiet** (reverts to the
  low-priority pre-connection text) rather than alerting -- a deliberate
  default matching prior behavior, not something the user has confirmed
  either way.
- **No `NotificationCompat.CallStyle`/full-screen-intent** for the
  incoming-offer moment -- deliberately deferred (see
  `docs/SYSTEM_STATE.md`); the plain three-button notification alert may
  feel weaker than a real incoming-call UI in practice.

## Build / process

- **No automated tests** (unit or instrumented) anywhere in the project.
- **This sandbox cannot compile Kotlin/Android locally** -- no local
  Gradle/Android SDK access in the environment these changes are usually
  made from. GitHub Actions (`assembleDebug`/`assembleRelease`) is the
  only real compiler check, which is why diffs need careful manual review
  before every push.
- **`isMinifyEnabled = false`** -- no R8/ProGuard shrinking/obfuscation on
  release builds yet.
- **Per-ride-only pairing** -- no "remembered devices" mode; both sides
  re-discover fresh every session by design (v1 scope), not currently
  planned to change.

## Release pipeline

- **Release secrets are managed manually.** No tool available in this
  environment can set GitHub repo secrets (`KEYSTORE_BASE64`,
  `KEYSTORE_PASSWORD`, `KEY_ALIAS`, `KEY_PASSWORD`,
  `DISCORD_WEBHOOK_URL`) -- adding/rotating them is always a manual step
  for whoever owns the repo.
- **No Play Store track/publishing.** Distribution is signed APK
  artifacts via GitHub Actions + a Discord notification only.
- **The release keystore has a single point of failure**: if
  `ridelink-release.jks` is lost, there is no way to publish an update
  under the same signing identity again. It must be backed up outside
  GitHub (a password manager or offline backup), not just left as a
  GitHub secret.
- **MAJOR/MINOR version classification has no automated enforcement.**
  Whoever authors a PR decides whether it earns a MINOR/MAJOR bump (see
  `CLAUDE.md`) and edits `version.properties` by hand -- there's no
  conventional-commit linting or similar check that would catch a
  PR that should have bumped the version but didn't (or bumped it when it
  shouldn't have). PATCH itself is fully automatic and not at risk here.
- **Release note quality depends on PR labeling discipline.** An
  unlabeled PR just lands in "Other Changes" in the categorized release
  notes (see `docs/SYSTEM_STATE.md`) rather than failing anything --
  there's no enforcement that every PR gets a category label before
  merge.
