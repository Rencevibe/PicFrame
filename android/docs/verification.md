# Verification note (plan §8, items V1–V6)

All checks were made on **2026-10-16** against official sources. Each item is marked:

- **Confirmed**: an official source states it.
- **Provisional**: chosen, but not yet proven by a build or a device test.
- **Open**: not resolved.

## V1: target API and minSdk

| Item | Finding | Source | Status |
|---|---|---|---|
| Play target API | From 2026-08-31, new apps and updates must target **API 36**. An extension to 2026-11-01 can be requested. | developer.android.com/google/play/requirements/target-sdk (page updated 2026-10-01) | Confirmed |
| Sideloaded APKs (D1) | The page sets no rule for apps distributed outside Play. API 36 is used anyway, so a later Play release (follow-up) needs no retarget. | Same page | Confirmed |
| `compileSdk` | **37**. Compose 1.12.1 AAR metadata requires ≥ 37 (CI run 38863305081 failed `checkDebugAarMetadata` at 36). AGP 9.4 supports up to 37. | CI evidence; AGP page | Confirmed by build |
| `targetSdk` | **36** (Play requirement; independent of `compileSdk`) | target-sdk page | Confirmed by build |
| `minSdk` | **26** (native `java.time`). The AndroidX default `minSdk` moved to 23 in activity 1.12.0-alpha06, which is below 26, so no conflict is expected. | developer.android.com/jetpack/androidx/releases/activity | Provisional until D2 |

## V5: toolchain compatibility (pinned in `android/gradle/libs.versions.toml`)

| Component | Pinned | Basis | Source | Status |
|---|---|---|---|---|
| AGP | 9.4.0 | Latest stable (September 2026); supports API levels up to 37 | developer.android.com/build/releases/gradle-plugin (updated 2026-10-08) | Confirmed by docs |
| Gradle (wrapper) | 9.6.0 | AGP 9.4 minimum and default. Gradle 9.8.1 is current, but the AGP default was chosen. | Same page; services.gradle.org/versions/current | Confirmed by docs |
| JDK | 17 | AGP 9.4 minimum and default | Same page | Confirmed by docs |
| Kotlin | Built into AGP 9 (`org.jetbrains.kotlin.android` is **not** applied). The Compose compiler plugin `org.jetbrains.kotlin.plugin.compose` is pinned at **2.4.21**, the newest stable release on the Gradle Plugin Portal (2.5.0 is Beta). | AGP 9 bundles KGP ≥ 2.2.10; the Compose plugin version must match the Kotlin version | developer.android.com/build/releases/agp-9-0-0-release-notes, /develop/ui/compose/compiler; plugins.gradle.org metadata | **Confirmed by build.** CI run 38863455375 compiled with AGP 9.4.0 built-in Kotlin plus the Compose plugin 2.4.21. |
| Compose BOM | 2026.09.00 → ui/foundation 1.12.1 | 1.12.1 is the current stable Compose release | developer.android.com/develop/ui/compose/bom/bom-mapping, /jetpack/androidx/releases/compose | Confirmed by docs |
| activity-compose | 1.13.0 | Latest stable (2026-03-11) | developer.android.com/jetpack/androidx/releases/activity | Confirmed by docs |
| JUnit | 4.13.2 | — | Not re-checked; Maven Central metadata was unreachable from this session | Provisional |
| Screenshot tool | Robolectric **4.17** (native graphics) instead of Roborazzi/Paparazzi (see `m3-m4.md`, deviation 1). Tests run on API **35** because Robolectric's API 36 runtime needs Java 21. | Latest stable; supports up to API 37 | github.com/robolectric/robolectric/releases (checked 2026-10-16) | Confirmed by build (CI run 38873615983) |
| datastore-preferences | **1.2.1** | Latest stable (2026-03-11) | developer.android.com/jetpack/androidx/releases/datastore | Confirmed by build |
| exifinterface | **1.4.2** | Latest stable (2025-12-03) | developer.android.com/jetpack/androidx/releases/exifinterface | Confirmed by build |
| lifecycle (viewmodel-compose, runtime-compose) | **2.11.0** | Latest stable (2026-06-17) | developer.android.com/jetpack/androidx/releases/lifecycle | Confirmed by build |
| kotlinx-coroutines (android, test) | **1.11.0** | Latest stable; built with Kotlin 2.2.20 | github.com/Kotlin/kotlinx.coroutines/releases | Confirmed by build |

**Build evidence:** https://github.com/Rencevibe/PicFrame/actions/runs/38863455375 passed `lintDebug testDebugUnitTest assembleDebug` from a clean checkout on 2026-10-16.

**Gaps in this check:**
- Google Maven and Maven Central were unreachable from the authoring environment (network policy), so nothing was resolved or built locally. The CI run is the build evidence.
- `distributionSha256Sum` is set (2026-10-16) to the official `gradle-9.6.0-bin.zip.sha256` from services.gradle.org. That host is blocked here, so a CI runner fetched it (run 38910007627); CI then builds with the wrapper verifying the download against it.

## V2: exact alarms (checked ahead of M1)

Source: developer.android.com/develop/background-work/services/alarms/schedule (updated 2026-10-01).

- Apps targeting API 31+ need "Alarms & reminders" access (`SCHEDULE_EXACT_ALARM`). Apps targeting API 33+ may instead declare `USE_EXACT_ALARM`.
- `USE_EXACT_ALARM` is "granted automatically", "cannot be revoked by the user", is "subject to an upcoming Google Play policy", and covers only "limited use cases".
- On fresh installs of apps targeting API 33+, `SCHEDULE_EXACT_ALARM` is **not pre-granted**. The app must call `canScheduleExactAlarms()` and send the user to `ACTION_REQUEST_SCHEDULE_EXACT_ALARM`.
- If the permission is revoked, the app is stopped and its future exact alarms are cancelled.
- The page lists `setAlarmClock()` under "Ways to set an exact alarm" but does not explicitly say it needs the permission. **Open; M1 checks it on a device.**
- All alarms are cancelled at shutdown. Re-register them from `ACTION_BOOT_COMPLETED` (needs `RECEIVE_BOOT_COMPLETED`), which only works after the user has launched the app once.
- **D1 = sideload:** the Play policy on `USE_EXACT_ALARM` doesn't gate launch. The spike will declare **`SCHEDULE_EXACT_ALARM` with the request flow**, the option the docs describe for the broader set of use cases. Whether to use `USE_EXACT_ALARM` is left open, since its eligibility rules apply only to Play.

## V3: full-screen intent and foreground service type (checked ahead of M1)

| Item | Finding | Source |
|---|---|---|
| `USE_FULL_SCREEN_INTENT` | For apps targeting API 34+, allowed use is "limited to those that provide calling and alarms only". The **Play Store** revokes it from apps that don't fit. Users can turn it on and off. Check with `NotificationManager.canUseFullScreenIntent()`; send the user to `ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT`. | developer.android.com/about/versions/14/behavior-changes-14 (updated 2026-10-01) |
| Sideload (D1) | The revocation described is done by the Play Store. Whether a sideloaded install has it by default is **not stated: M1 checks it on a device** (pass condition 3/4 evidence). | Same page |
| Foreground service type for alarm playback | `systemExempted` is allowed for "Apps holding `SCHEDULE_EXACT_ALARM` or `USE_EXACT_ALARM` permission"; otherwise the system throws `ForegroundServiceTypeNotAllowedException`. `mediaPlayback` can't be started from `BOOT_COMPLETED` when targeting API 35+. **Spike uses `systemExempted`.** | developer.android.com/develop/background-work/services/fgs/service-types (updated 2026-10-01) |

## V4, V6

- **V4:** not needed (D3 = picked folder).
- **V6** (Open-Meteo terms): open, due before M5. open-meteo.com was unreachable from this session.
