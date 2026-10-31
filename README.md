# RideLink

A two-role Android app for a rider (phone mounted on the bike, hands-free
once riding starts) and a pillion (picks the destination). The pillion
builds a route in the real Google Maps app and taps Share; RideLink
captures that share, sends it directly to the rider's phone over a local
Bluetooth/Wi-Fi connection (Google's Nearby Connections API), and the
rider's phone automatically opens Google Maps navigation. **No backend --
the entire handoff, including voice chat, is phone-to-phone.**

For the technical architecture and module-by-module breakdown, see
`docs/SYSTEM_STATE.md`. Known open limitations are tracked in
`docs/KNOWN_GAPS.md`.

## Features

1. Both people open RideLink, grant Bluetooth/nearby-device permissions,
   and pick a role: Rider or Pillion. Pairing is per-ride only -- both
   sides re-discover each other fresh every session, nothing is
   remembered device-to-device.
2. Pillion opens Google Maps, builds the route, taps **Share -> RideLink**.
   RideLink sends the link to the rider's phone the moment both a
   connection and a captured link exist -- and again for every
   subsequent share, not just the first.
3. Rider's phone automatically launches Google Maps navigation, even if
   RideLink itself is backgrounded (a foreground service keeps it
   listening) or the rider's phone is locked. A tapped "new route"
   notification also opens it, as a second path.
4. Once paired, both phones show a high-priority persistent notification
   with the controls that make sense right then -- **Call / Disconnect** when
   just connected, **Reject / Accept** on an incoming voice request, **Mute /
   Output / End** during a call (Output cycles Speaker, Call speaker,
   Bluetooth; Mute becomes Unmute) -- so a call can be started, answered,
   muted, re-routed or ended without opening the app (while you are the one
   calling, the notification shows **Cancel**). If the microphone has not
   been allowed yet, Call / Accept open RideLink to ask first, then carry
   on. The notification is also marked as an Android 16+ "Live
   Update", so phones that support it (Samsung's status-bar pill / Now Bar)
   can promote it; everywhere else it is the same plain notification (its
   buttons show once expanded). Before pairing, and after the other phone
   disconnects, the notification has a **Stop** button to end the session.
   The notification sits on a silent channel; only "just paired" and "voice
   request" use a separate alerting channel, and only when RideLink is in
   the background. While RideLink is open, it does not
   interrupt you with a "new route" notification or a sound for a connect or
   an incoming request -- the app screen already shows them. Audio flows
   over the same Nearby Connections link -- still no internet or mobile
   data.
5. Light/dark theme toggle in the header, persisted across restarts
   (defaults to the system theme on first launch).

## Building

Requires JDK 17 and the Android SDK (platform 36, build-tools matching
AGP 8.9.1). Two ways to build:

- **Locally in Android Studio**: open the project root and let it sync.
  `./gradlew assembleDebug` works with no extra setup.
  `./gradlew assembleRelease` requires the signing environment variables
  below -- without them it fails at the signing step, which is expected.
- **CI (the actual build path used day to day)**: two GitHub Actions
  workflows, both producing a **signed** release APK (see
  `docs/SYSTEM_STATE.md` for full detail):
  - **Test Release** (`build-apk.yml`) -- manually triggered, pick any
    branch. Use this to get a signed build of work-in-progress onto a
    test device.
  - **Production Release** (`release.yml`) -- **manual only** (not
    triggered by merging to `main`). Dispatch it against `main` when
    you've decided it's time to cut a release: it tags the release,
    creates a GitHub Release with the signed APK attached and
    auto-generated, categorized release notes (every PR merged since the
    last release, grouped by label), and posts the Release link to
    Discord.
  - Both need these repository secrets configured: `KEYSTORE_BASE64`,
    `KEYSTORE_PASSWORD`, `KEY_ALIAS`, `KEY_PASSWORD`, and
    `DISCORD_WEBHOOK_URL`. No key material lives in this repo.
  - **Semantic versioning**: `app/version.properties` holds only
    `MAJOR.MINOR`, bumped by hand as part of whichever PR earns it (see
    `CLAUDE.md` for how that's decided). PATCH is always computed
    automatically at release time from existing release tags -- never
    edited by hand. `versionCode` is separate and always derived
    automatically from commit count.

No API keys, accounts, or `local.properties` entries beyond the standard
`sdk.dir` are needed for the app itself -- there's no backend and no
Maps/Places API usage of our own (Google Maps itself handles all of
that, launched via intent).

## Contributing workflow

New work happens on a feature branch, never directly on `main`:
1. Push the feature branch and run the **Test Release** workflow against
   it to get a signed build onto a real device.
2. Once that's tested and approved, open a PR into `main`.
3. Merge only happens on explicit approval. Merging by itself triggers
   nothing further -- multiple PRs land on `main` this way over time, and
   a **Production Release** is a separate, deliberate, manually-dispatched
   step whenever it's time to ship.

See `CLAUDE.md` for the full detail (including keeping `README.md`,
`docs/SYSTEM_STATE.md`, and `docs/KNOWN_GAPS.md` current with every
change).

## Testing

Nearby Connections behavior is unreliable on emulators, so use two
physical Android devices:

1. Install the same signed build on both (a Test Release APK, not two
   separately-signed debug builds -- see Building above for why that
   matters), grant Bluetooth/nearby-device permissions when prompted, and
   enable Bluetooth/Wi-Fi if asked.
2. Device A: "I'm the Pillion". Device B: "I'm the Rider". Both screens
   should show "Connected" within a few seconds, and both devices should now
   show the high-priority connected notification with `[Call] [Disconnect]`. On a
   phone that supports Live Updates (Samsung One UI 8/9: switch on Developer
   options > "Live notifications for all apps" if it is off), the status-bar
   pill ("Linked") and Now Bar card may appear too. The "Live Updates"
   line on the role screen says whether Android allows it.
3. On Device A, open Google Maps, build a route, Share -> RideLink.
   Device A shows "Route sent". Device B should automatically launch
   Google Maps navigation with no further taps -- try this again with
   Device B's screen off/RideLink backgrounded, and again while it's
   already mid-navigation from a previous share.
4. Voice chat: tap **Call** in the notification on one device (it shows
   `[Cancel]` while it waits; with the microphone not yet allowed it opens
   RideLink to ask first; with no answer it stops ringing after 45 s). The other device's notification
   should alert and show `[Reject] [Accept]`. Tap Accept from the
   notification -- both should switch to `[Mute] [<output>] [End]` and
   audio should flow both ways, with a running timer on the notification.
   Try Mute (label flips to Unmute, the status pill says "Muted"), Output
   (cycles the output and the label follows) and End (both return to
   `[Call] [Disconnect]`). Also tap Call on both phones at the same moment --
   they should simply start talking, not both wait. Also confirm Reject on an incoming request reverts the
   caller's notification. Repeat with the other device as the caller.
5. Quiet while open: on a phone that is not a Samsung, connect both phones
   with RideLink open -- no heads-up should appear (connect with RideLink in
   the background -- one should). Stop: from the waiting state, and after the
   other phone disconnects, tap **Stop** on the notification.
   Disconnect, from the notification: tap it while just connected and the
   Nearby connection should tear down and the notification disappear. Do it
   with RideLink open too: the screen should return to the role picker. If
   the app ever crashes, reopen it -- the role picker shows "Share report".
6. App open vs closed: with RideLink open on the rider phone, have the
   pillion send a route and start a voice request -- expect no route
   notification, no heads-up, no sound (Maps still opens). Repeat with the
   app in the background -- the route notification and the request alert
   should appear. Leave the app while a request is still ringing -- it
   should alert then.
7. Also confirm the existing in-app voice chat controls
   (`VoiceChatControls`) still work independently of the notification,
   including the output picker (speaker/earpiece/Bluetooth).
