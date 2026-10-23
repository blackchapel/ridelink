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
- **Lossy delivery is a deliberate trade-off, not free.** The
  sequence-number gap-skip receive policy (`AudioPlayback`) means a
  lost/delayed chunk is skipped instead of waited for. With Opus, up to 2
  lost packets are concealed by the decoder; with raw PCM, or a longer
  gap, it is a brief audible glitch.
- 8kHz mono PCM16 is a real quality ceiling (telephony-band, not music
  quality) -- an intentional, conservative starting point given the
  Bluetooth-at-range throughput unknown, not yet revisited.

## Battery / heat / latency work (Settings, radio mode, Opus)

None of this has been verified on a real device yet -- it was written
without being able to compile or run it, and the Test Release A/B in the
README is what turns these into facts.

- **Efficient radio mode (`P2P_CLUSTER`) is unproven for range and
  latency.** It is meant to stay on a Bluetooth link (no Wi-Fi Direct
  upgrade), which should be cooler and cheaper, but real riding-distance
  dropouts or added latency are unknown. Flip to Performance in Settings
  if it is worse. Which medium Nearby actually picks is not visible in the
  stats (the bandwidth-info callback was left out because its API could not
  be verified offline).
- **Mismatched radio modes look like "never connects".** Phones on
  different strategies don't discover each other; the Settings text says
  so, but nothing in the pairing UI detects it.
- **Pure-Java Opus CPU cost is unmeasured on phones.** Concentus encoded
  a 20ms frame in roughly 0.2-0.4 ms on a desktop JVM; phones are slower.
  The stats screen shows the real per-frame encode/decode time, and Raw
  PCM is one switch away. Opus adds no extra buffering over PCM (20ms
  frames, synchronous) but its algorithmic delay is not measured.
- **The 48kHz low-latency playback path is device dependent.** Opening the
  AudioTrack at the device's native rate is what lets Android grant the
  low-latency path, but `USAGE_VOICE_COMMUNICATION` may still be routed to
  a standard path on some devices. The stats show what was granted. Capture
  stays at 8kHz and its path is not reported.
- **Mixed-version / mixed-codec pairs.** The receiver decodes both packet
  tags, so a phone on Raw PCM talks to one on Opus. An older build that
  doesn't know the Opus tag would just not play it.
- **Reconnect policy is a guess.** Capped backoff (1 s doubling to 30 s,
  10 attempts, about 3 minutes), then "Disconnected" until the screen is
  reopened. Both phones search again on any drop, including when the other
  side deliberately ends, so the surviving phone searches for a few
  minutes before giving up.
- **Effects are not de-duplicated.** AEC/NS are still created explicitly
  on top of the `VOICE_COMMUNICATION` source; if the platform already
  applies them this may double-process. The stats show whether each is
  enabled. Removing them risks echo on the loudspeaker.
- **Silence suppression (VAD) was deliberately left out** (wind noise at
  riding speed makes it risky to tune blind). Audio still flows at the
  full packet rate whenever the mic is unmuted.
- **Google Maps probably dominates the rider phone's heat** (screen, GPS);
  this work only reduces RideLink's own share.

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
