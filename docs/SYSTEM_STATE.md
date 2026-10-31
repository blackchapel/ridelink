# RideLink -- system state

A technical snapshot of what actually exists in this repo right now.
`README.md` is the user/build-facing doc; this one is for picking up
the codebase quickly. Keep it current alongside every change (see
`CLAUDE.md`).

## Architecture

Two roles, one pairing, no server anywhere:

- **Pillion** (passenger) picks the route in the real Google Maps app and
  shares it into RideLink via the OS share sheet.
- **Rider** (phone mounted on the bike) receives it and RideLink launches
  Google Maps navigation automatically -- no further taps needed, even if
  RideLink is backgrounded.
- The two phones pair over Google's **Nearby Connections API**
  (`play-services-nearby`), `Strategy.P2P_CLUSTER` -- one
  advertiser (Pillion), one discoverer (Rider). The link stays on
  Bluetooth: this strategy does not upgrade to Wi-Fi Direct (the earlier
  `P2P_POINT_TO_POINT` could), which is steadier and cooler on a bike.
  Both phones must run a build with the same strategy or they will not find
  each other. Pairing is per-ride only -- both sides re-discover fresh every
  session, nothing is remembered device-to-device.
- Everything -- route link relay, voice chat signaling, voice audio --
  flows over that same Nearby Connections link. There is no backend, no
  internet/mobile-data dependency, and no third-party server involved at
  any point.

## Module map

`app/src/main/java/com/ridelink/app/`

- `MainActivity.kt` -- role picker, light/dark toggle (persisted via
  `ui/theme/ThemePreferences.kt`), hosts whichever role screen is active.
  Also the landing point for a tapped route notification (see Rider,
  below) -- a notification tap has no background-activity-start
  restriction, so routing through here before relaunching Maps is what
  makes the "opens Maps even from the background" behavior reliable.
- `ShareReceiverActivity.kt` -- the `ACTION_SEND` target that appears in
  Google Maps' share sheet; captures the shared link for the Pillion
  side.
- `RideLinkApplication.kt` -- registers the app's notification channels:
  per-role pre-connection status (LOW), new-route alert (HIGH), and the
  shared connected/voice-chat-controls channel (HIGH, see `notifications/`
  below). Also deletes the old separate call-active channel on startup so
  existing installs don't keep an orphaned entry.
- `nearby/NearbyManager.kt` -- thin wrapper around Nearby Connections.
  Owns the connection lifecycle/state (`NearbyState`), a small tagged
  `ControlMessage` protocol over BYTES payloads (route link,
  voice-chat offer/accept/decline/end), and a separate per-chunk
  sequence-numbered audio-chunk protocol (`AudioChunk`,
  `sendAudioChunk`/`incomingAudioChunks`) -- see Voice chat below for why
  audio doesn't use Nearby's STREAM payload type. Radio lifecycle:
  `startAdvertising`/`startDiscovery` are idempotent; advertising and
  discovery stop once connected (scanning/beaconing all ride is pure
  drain); `onEndpointFound` is guarded against duplicate requests; an
  unexpected disconnect or failed attempt restarts the same role with capped
  exponential backoff (1 s doubling to 30 s, 10 attempts, then
  `Disconnected`).
- `notifications/` -- the single persistent notification shown per role,
  both pre- and post-connection:
  - `ConnectionNotificationBuilder.kt` -- stateless; builds the
    notification for the current `NearbyState` + `VoiceChatState`, per
    role. Quiet/LOW while not yet connected; HIGH once connected, with
    plain `addAction()` buttons (no CallStyle, no custom layout, so it is
    eligible for promotion and looks the same everywhere; posted on the
    silent `connection_quiet` channel (LOW), and on the HIGH
    `connection_active` alert channel only for an update with `alert = true`,
    because a first post on a HIGH channel can heads-up on some phones even
    with `setOnlyAlertOnce`; the not-connected status notification has a
    `[Stop]` button): `[Call]
    [Disconnect]` connected (Call and Accept are activity PendingIntents
    that open `MainActivity`, which asks for `RECORD_AUDIO` and then runs the
    action, while the microphone permission is missing), `[Cancel]` while we are calling, `[Reject] [Accept]`
    incoming, `[Mute/Unmute] [<current output>] [End]` in a call (a
    chronometer shows the call time without re-posting every second).
    It also requests Android 16+ Live Update promotion
    (`setRequestPromotedOngoing`, short pill text `setShortCriticalText`:
    "Linked" / "Call" / "Voice" / "Muted", kept under 7 characters; during
    an unmuted call the pill shows the chronometer instead) and adds a
    clearly marked **experimental** block of Samsung-specific extras
    (`android.ongoingActivityNoti.*`, from unofficial blogs; Samsung may
    ignore them): with `SAMSUNG_PROMOTED_PATH = true` just `chipBgColor` (no `style`,
    and never `automation`, which makes One UI brand it "Gemini Intelligence"),
    otherwise the earlier private-card extras plus the `ongoing_activity` manifest meta-data.
  - `ConnectionNotificationUpdater.kt` -- `launchConnectionNotificationUpdates`
    (shared by both role services) re-posts the notification when the
    connection, call state, mute, audio output or app visibility changes,
    and `AlertPolicy` decides which updates may alert (only "just paired" and
    "incoming request", never while the app is in the foreground; a request
    still ringing when the app is left alerts then).
  - `NotificationActionReceiver.kt` -- manifest-registered
    `BroadcastReceiver` handling every button tap (cancel/accept/
    offer/decline/mute-toggle/cycle-output/end/disconnect), dispatched to the tapped role's
    `RiderSession`/`PillionSession`; any exception is caught and written to
    `CrashLog`. Disconnect goes through `stopConnection()` (not a bare
    `reset()`), which calls `ConnectionNotificationHost.current.stopListening()`
    -- a plain method call, never a start-service intent, because a stop
    intent to a service that is no longer running makes Android kill the app
    ("did not call startForeground").
  - `ConnectionNotificationHost.kt` -- tiny interface + a single
    `current` holder, implemented by whichever role's foreground service
    is running, so `VoiceChatSession` can trigger the microphone
    foreground-service-type change (see Voice chat below) without
    depending on either service class directly. `enterCallType()` returns
    false if Android refuses; `stopListening()` is how Disconnect/End stop
    the service.
- `CrashGuard.kt` -- `crashGuard(tag)` `CoroutineExceptionHandler` used by
  every long-lived scope (sessions, services, voice chat), and `CrashLog`,
  which keeps the last uncaught crash / swallowed background error in
  `filesDir/last_crash.txt`; the role picker offers to share it.
- `AppVisibility.kt` -- whether RideLink is in the foreground (via
  `ProcessLifecycleOwner`, which ignores rotation), used to keep alerting
  notifications quiet while the app is open.
- `permissions/PermissionsGate.kt` -- runtime permission + Bluetooth/
  Wi-Fi-enabled gating shared by both roles before any Nearby call is
  made.
- `pillion/` -- `PillionViewModel`/`PillionScreen`/`PillionSession`/
  `PillionForegroundService`. Mirrors `rider/` below (added so voice chat
  -- and the connected-status notification -- survives Pillion
  backgrounding too, not just Rider's).
- `rider/` -- `RiderViewModel`/`RiderScreen`/`RiderSession`/
  `RiderForegroundService`. `RiderForegroundService`
  (`foregroundServiceType="connectedDevice|microphone"`) is what keeps
  the Rider's process alive and able to keep launching Maps while
  backgrounded; it's the thing that must be running for the app's core
  promise ("rider never has to touch the phone again") to hold. Also
  hosts the connected-status notification (see `notifications/` above)
  and implements `ConnectionNotificationHost` to add/remove its own
  `microphone` type for the duration of a call.
- `voicechat/` -- real-time voice chat, layered on top of an
  already-connected `NearbyManager`:
  - `AudioPipeline.kt` -- `AudioCapture` (AudioRecord, 8kHz mono PCM16,
    hands each ~20ms chunk straight to a callback, no internal
    buffering) and `AudioPlayback` (AudioTrack, fed via a small bounded
    drop-oldest queue, `PLAYBACK_QUEUE_CAPACITY = 4` / ~80ms). Voice is
    always raw PCM (an Opus experiment was tried and removed -- see
    `docs/KNOWN_GAPS.md`). At call end `AudioRoutingController.release()`
    undoes the output choice so a Bluetooth SCO link or forced device does
    not outlive the call.
  - `AudioRoutingController.kt` -- speaker/earpiece/Bluetooth output
    selection (`AudioManager.setCommunicationDevice` on API 31+, legacy
    speakerphone/SCO APIs below that).
  - `VoiceChatSession.kt` -- offer/accept/decline/end state machine
    (`VoiceChatState`), mute, and the sequence-number gap-skip receive
    policy (stale/duplicate chunks dropped, gaps jumped over rather than
    waited for -- see `docs/KNOWN_GAPS.md` for the trade-off this
    implies). The service's `microphone` type is taken when a call is
    *offered or accepted* (while the user's tap still allows it -- Android
    refuses it from the background) and held until the call ends
    (`holdMicType`/`releaseMicType`); `startCall()` rolls everything back and
    shows a Toast if the microphone is missing or busy. Both sides offering
    at once start the call; an unanswered offer times out after 45 s;
    `shutdown()` cancels the session's scope. There's no separate
    call-specific foreground service anymore (see below).
  - `VoiceChatControls.kt` -- shared in-app Compose UI for both roles
    (offer/accept/decline/mute/end), independent of and in addition to
    the notification controls above.
- `ui/theme/` -- `Theme.kt` (Compose theme) and `ThemePreferences.kt`
  (persisted light/dark choice, defaulting to system theme).

**Note on the dynamic foreground-service-type change**: `RiderForegroundService`/
`PillionForegroundService` each declare the superset of types they may use
(`connectedDevice|microphone`) and re-invoke `startForeground()` on the same
notification ID to add/remove the active `microphone` type for the
duration of a call, rather than running a second, separate foreground
service the way this app used to (a deleted `VoiceCallForegroundService`).
This is a deliberate reversal of an earlier, more cautious design that
avoided dynamic type changes specifically for lack of real-device
verification -- see `docs/KNOWN_GAPS.md`.

## Release pipeline

- **Versioning**: `app/version.properties` holds only `VERSION_NAME=
  <major>.<minor>` -- hand-edited (see `CLAUDE.md` for how the bump is
  decided), never a full semver string. PATCH is never edited by hand --
  Production Release computes it each run as `1 + the highest existing
  v<major>.<minor>.*` tag (0 if that line has no tags yet), so the full
  released version is `<major>.<minor>.<patch>`, tagged `v<version>`.
  `versionCode` is separate and still fully automatic in both workflows
  (`git rev-list --count HEAD`).
- `.github/workflows/build-apk.yml` ("Test Release") -- manual
  (`workflow_dispatch`, a `branch` input), **two sequential jobs**:
  1. `build` -- signed `assembleRelease` from any branch using the release
     keystore, `<major.minor>-<branch>.<short-sha>` versionName (no patch
     number -- it's not a tagged release). Uploads the APK as a
     short-lived workflow artifact; version info and the artifact's numeric
     id are exposed as job outputs (not its URL: GitHub drops job outputs
     containing a secret-looking string, and the repo name `ridelink` is
     masked in logs, so `notify` rebuilds the URL from `github.repository`).
  2. `notify` (`needs: build`, `if: always()` -- runs even if `build`
     failed) -- posts a Discord embed using `build`'s outputs.
- `.github/workflows/release.yml` ("Production Release") -- **manual
  only** (`workflow_dispatch`, no inputs -- dispatch it against `main`
  from the branch selector). Does *not* run on merge/push anymore.
  **Three sequential jobs**, each depending on (and only running after)
  the previous:
  1. `build` -- computes the version (including the next patch number, as
     above), builds the signed `assembleRelease`, and hands the APK to
     the next job via a short-lived (`retention-days: 1`) workflow
     artifact -- jobs don't share a filesystem, so this is the only way
     to pass the built file across the job boundary. Refuses to run if
     the computed tag already exists (safety check). Right after the
     version is computed, `.github/scripts/release-notes.sh` extracts the
     `## <version>` section from `CHANGELOG.md`; a missing or empty section
     fails the run before the build.
  2. `release` (`needs: build`) -- downloads that APK, tags the built
     commit `v<version>`, and creates a GitHub Release at that tag via
     `softprops/action-gh-release`. The body is the hand-written
     `CHANGELOG.md` section (`body_path`, written by
     `.github/scripts/release-notes.sh`), and `generate_release_notes: true`
     adds GitHub's "What's Changed" list below it (every PR merged since the
     previous release tag; `.github/release.yml` categorizes it by PR
     label -- "🚀 Features", "🐛 Fixes", "📝 Documentation", "🧰
     Maintenance", "Other Changes" for anything unlabeled) with the APK
     attached directly to the Release (a permanent, versioned asset, not
     a CI artifact). Only this job has `permissions: contents: write`
     (least privilege -- it's the only one that pushes a tag/creates a
     release).
  3. `notify` (`needs: [build, release]`, `if: always()` -- runs even if
     either prior job failed) -- posts a Discord embed with the **GitHub
     Release's own URL** (never the Actions run link; the field is labelled
     "Release notes" because that page shows them), using
     `needs.build.result`/`needs.release.result` to report which stage
     failed, if any. The URL is built in this job from `github.repository`
     and the tag, never passed as a job output (GitHub drops outputs that
     contain the masked repo name). If the build stopped only because
     `CHANGELOG.md` has no section for the version (`build` output
     `notes_missing`), the message says so and spells out the heading to use.
- Both workflows' `build` job reads signing material from GitHub secrets
  (`KEYSTORE_BASE64`/`KEYSTORE_PASSWORD`/`KEY_ALIAS`/`KEY_PASSWORD`,
  decoded to a runner temp file); both `notify` jobs post to a
  `DISCORD_WEBHOOK_URL` secret. `app/build.gradle.kts`'s
  `signingConfigs.release` reads
  `KEYSTORE_PATH`/`KEYSTORE_PASSWORD`/`KEY_ALIAS`/`KEY_PASSWORD` from the
  environment; unset locally, so a local `assembleRelease` fails loudly
  at signing time (expected -- there's no meaningful unsigned fallback
  for a release build). Only `release.yml`'s `release` job carries
  `permissions: contents: write` (to push the tag and create the
  Release) -- everything else stays read-only. This matters since a
  repo's default `GITHUB_TOKEN` permissions are often read-only.
- Repo workflow going forward (see `CLAUDE.md`): multiple PRs merge into
  `main` over time (each: feature branch -> Test Release build -> user
  approval -> PR -> **explicit** user approval -> merge -- merging alone
  triggers nothing); when it's time to ship, the user manually dispatches
  Production Release against `main`.

## Pinned versions (as of this snapshot)

AGP `8.9.1`, Gradle `8.11.1`, Kotlin `2.0.21`, Compose BOM `2024.10.01`,
`play-services-nearby` `19.3.0`, `androidx.core` `1.17.0`,
`lifecycle-process` `2.8.6`, `compileSdk` `36` / `targetSdk` `35`,
`minSdk` `26`, JDK `17`.
