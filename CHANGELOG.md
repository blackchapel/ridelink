# Changelog

What changed in each release, in plain words for the people using RideLink.
Every pull request that adds, changes or fixes something adds a line under
**Unreleased** (groups: Added, Changed, Fixed, and Project for things around
the app such as the build or the repository). To release, a small PR first
renames "Unreleased" to the version about to be cut (for example
`## 1.1.0 (2026-11-02)`) and starts a fresh "Unreleased" above it; the
**Production Release** workflow publishes that section as the release notes
and refuses to run if it is missing.

## Unreleased

### Added
- **Connected notification with controls:** once the two phones are paired,
  the notification has the buttons that make sense right then -- Call and
  Disconnect, Reject and Accept for an incoming voice chat, and Mute, output
  and End during a call -- so a call can be started, answered and ended
  without opening the app. Before pairing, and after the other phone
  disconnects, it has a Stop button.
- **Status-bar pill and Now Bar (Android 16+, Samsung One UI 9):** the
  notification is shown as a Live Update with a call timer. On Samsung it needs
  Developer options > "Live notifications for all apps" switched on.
- **Microphone prompt from the notification:** Call and Accept ask for the
  microphone first if it has not been allowed yet.
- **Share crash report:** if the app crashed last time, the role picker offers
  to share the report.

### Changed
- **Lower battery and heat:** the phones stay on a Bluetooth connection (no
  Wi-Fi Direct upgrade), stop searching for each other once connected, and
  release the Bluetooth call audio route when a call ends. Release builds
  are smaller and shrunk with R8.
- **Reconnects on its own:** if the connection drops, both phones search
  again with growing pauses and reconnect without reopening the app; the
  notification has a Stop button while it searches.
- Notifications stay quiet while RideLink is open: no "new route" notification
  and no pop-up for pairing or a voice request. They alert only when the app
  is in the background.
- An unanswered voice chat call stops ringing after 45 seconds, and both
  phones tapping Call at the same moment now simply start talking.
- The screen returns to the role picker when the session is ended from the
  notification, and your role is kept when the phone is rotated.

### Fixed
- Several crashes around starting and ending calls from the notification
  (microphone not allowed or busy, microphone service refused in the
  background, ending a session that had already stopped, Google Maps missing).

### Project
- Release notes now come from this file, with GitHub's list of merged pull
  requests added below them.
- Build tooling updated: Android Gradle Plugin 8.9.1, Gradle 8.11.1,
  compileSdk 36.
