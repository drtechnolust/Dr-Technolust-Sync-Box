# Verification — Dr.Technolust Sync Box 1.0.4

Version 1.0.4 is a security-hardening release on top of the 1.0.3 baseline. The
hardware and emulator checks below were performed on 1.0.3; the 1.0.4 changes
(see the README) are covered by 24 unit/protocol tests and a post-change smoke
test on the NVIDIA Shield with the F62.

Release checks: October 15, 2026. Physical hardware: NVIDIA Shield Android 11
(API 30) and the owner's FancyLEDs F62, product h2nqrmaskyhckcmf, LAN 3.4.
Additional platform: headless Android 16 TV emulator (API 36, x86_64).

## Checks completed

- Eleven app unit tests: color encoding/validation/readback, unknown versus
  requested settings, mode exclusivity, preset format, daily rollover, AM/PM and
  24-hour formatting, shared CEC state, disabled offline controls.
- Eight protocol/discovery tests: simulated session/query/control, authentication,
  frame tampering, discovery and interruption of a stalled handshake.
- Independently signed, non-debuggable release-check APK installed separately
  from the owner's app; production-code difference is the .releasecheck app ID.
- Clean-install checks on Shield and Android 16 TV: empty/invalid setup, typed
  text, remote Down/OK from key to Save, dialog isolation, forget confirmation,
  all settings destinations, time-format switch and About attribution.
- Encrypted configuration round-trip, invalid-replacement preservation, removal
  and rejection of the development-only plaintext import in release builds.
- Preset persistence, recall ordering/failure abort with unchanged HDMI;
  persisted sleep deadline and schedule rearm/cancellation.
- Android 16 TV exact-alarm access explicitly denied and granted: both scheduling
  paths succeed; boot/time/timezone handlers preserve the saved deadline.
- Actual Android 16 TV emulator reboot: boot receiver restored the pending sleep
  alarm before any activity/instrumentation launch, verified in Android's alarm
  service. Persisted deadline and rearming were then checked separately.
  Instrumentation itself force-stops its target and clears existing alarms.
- Provider TCP recovery on Android 16 TV: simulator drops first query, offline
  controls disable, automatic reconnect succeeds, then manual reconnect succeeds
  after another dropped socket. Closing the provider does not block the UI.
- Live F62 command/readback: three primary colors, all 15 sync/scene modes,
  all six sound modes, 1% brightness/diffusion offsets, orientation, HDR auto,
  color enhancement and available sensitivity/black-bar values.
- CEC DP 109 both-value round-trip and shared shortcut/settings state.
- Actual sleep alarm -> receiver -> WorkManager -> lights off; scheduled-on
  worker, startup auto-on and preset recall. Original device settings and
  automation preferences restored in finally blocks.
- All 13 live UI screens opened, including picker/calibration. PixelCopy
  screenshots avoid accessibility takeover. Browsing changed no checked
  device datapoints. HDMI input was not changed in these release tests.

Readback proves acceptance by the box, not visual quality of every animation or
audio/video effect. Tests never print real keys. Launcher/banner and visible
app name are updated to Dr.Technolust Sync Box; application ID remains unchanged.

## Reproduce checks

Use JDK 17 and Android SDK 36. The wrapper pins Gradle 8.14.3 with its official
distribution checksum; the wrapper JAR also matches the official Gradle checksum.

```powershell
.\gradlew.bat :app:testDebugUnitTest :tuya-lan:test :app:assembleDebug
# Release tasks require your private signing configuration; see README.
.\gradlew.bat :app:assembleRelease :app:lintRelease
.\tools\verify_release.ps1
```

For isolated first-run QA:

```powershell
.\gradlew.bat -PreleaseCheck=true :app:assembleRelease :app:assembleReleaseAndroidTest
adb -s <tv-serial> install -r app/build/outputs/apk/release/app-release.apk
adb -s <tv-serial> install -r app/build/outputs/apk/androidTest/release/app-release-androidTest.apk
adb -s <tv-serial> shell am instrument -w -e class com.drtech.ledsync.ReleaseSmokeTest com.drtech.ledsync.releasecheck.test/androidx.test.runner.AndroidJUnitRunner
```

Wake the TV first: Compose cannot inspect a screen that is not drawing.
Never distribute the releasecheck package or instrumentation APK. Live tests
require explicit provisioning and -e hardware true. They temporarily change
LEDs, restore read-before-write values and leave unknown/unrestorable settings
alone. Close other controllers while running. The provisioning helper transfers
setup through token-protected ADB loopback, not shared files or key arguments.

## Release gates

- Permanent owner-controlled RSA signing identity; release tasks fail without
  signing configuration. No debug-key fallback.
- APK gate checks signature, non-debuggable flag, production package/version,
  TV launcher entry, known private credentials/IDs, signing passwords, test
  classes and manufacturer SDK markers.
- Source export is allow-listed, includes wrapper/tests, excludes hardware dumps,
  local properties/signing files and scans known credentials. ZIP includes .gitignore.
- Atomic AES-GCM/Android Keystore writes. Backup/device transfer excluded.
  Raw query responses and local keys are not logged.
- Lint has no errors. Old dependency/latest-target warnings are reviewed
  maintenance items: tested Kotlin/Compose/WorkManager versions and target API
  35 are retained. Timer commit() calls intentionally persist before arming.
  Density-folder lint is inapplicable to vector/nodpi artwork layout.
- App is independent of the vendor SDK/cloud at runtime. Desktop key retrieval
  is separate and requires an account you control.

## Verification limits

- Only this F62 schema has physical coverage. Other boxes, other TV makes and
  protocol 3.5 are not supported promises.
- CEC configuration/readback works. Actual TV standby/wake and HDMI CEC-chain
  behavior still need physical observation; setting a flag is not a guarantee
  that another TV/source implements those signals.
- Physical Shield reboot/long-term standby was not performed. Actual reboot
  recovery is verified on Android 16 TV emulator, and background command execution
  separately on Shield. This is not multi-day endurance certification.
- Android may delay background work. Force-stop suspends automation until the
  app opens again; TV must remain powered and box reachable.
- Ambient level is unavailable because its encoding is unknown. Unreported
  values are Sent · Unconfirmed. Calibration is remembered, not read back.
- HDMI switching can blank the picture. Confirmation/timeout guard is on by
  default; it cannot guarantee recovery after OS force-stop/power/network loss.

## Sharing and key custody

Publish only the clean exported source and final signed release files, never the
working directory, private dumps, signing folder, test APK or debug APKs.
Publish SHA-256 checksums with the APK. Back up the signing key/recovery properties
securely offline: future public updates need the same identity. Existing debug
installs cannot update in place to the independently signed public APK.

This is an unofficial local F62 controller with documented limits, not a
manufacturer certification or completed app-store review.
