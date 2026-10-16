# M2: domain port

**Owner:** Android lead.

**Status:** Implemented. All ported tests pass in CI. **Acceptance pending:** the done-when condition is "every listed Python case passing in Kotlin", and some cases are deferred (see [Deferred cases](#deferred-cases-need-acceptance)).

**Code:**
- `android/app/src/main/java/io/github/rencevibe/picframe/domain/`: pure Kotlin, no `android.*` imports.
- Tests in `android/app/src/test/java/io/github/rencevibe/picframe/domain/`.

**CI evidence:** https://github.com/Rencevibe/PicFrame/actions/runs/38866985819, run on 2026-10-16 from a clean checkout of commit `5ce237d`.
- `lintDebug testDebugUnitTest assembleDebug` all succeeded.
- The test annotation reads **"100 run, 0 failed, 0 skipped"**.

**Local checks:** none. Google Maven and Maven Central are unreachable from the authoring environment, and it has no Android SDK, so nothing was compiled or run locally.

## What was ported

| Kotlin file | From | Notes |
|---|---|---|
| `Config.kt` | `frame/config.py` | Field rules, defaults, critical keys, the lat/lon pair rule, `toRaw()` for storage. File I/O is not ported (DataStore, plan §3). `photo_dir` holds the picked folder URI (D3). `todo_path`, `screen_power` and `brightness_cmd` are removed (D3, G4). |
| `Formatters.kt` | `controller.fmt_clock`, `fmt_hour`, `days_label`, `_shift`; `settings_screen.fmt_interval` | |
| `Alarm.kt` | `frame/alarm.py` | `java.time`. A wall time in a DST gap resolves forward (02:30 → 03:30); a repeated hour uses the earlier occurrence. |
| `Night.kt` | `frame/night.py` | |
| `Today.kt`, `TomlSubset.kt` | `frame/today.py` | Parses the text of `todo.toml`. Reading the file is M3/M4 (`TodoSource`). The TOML subset is documented in `TomlSubset.kt`. |
| `Weather.kt`, `MiniJson.kt` | `frame/weather.py` | The HTTP call is injected (`fetch`); `WeatherClient` is M4. |
| `Photos.kt` | `frame/photos.py` | `PhotoQueue` (shuffle rules), the path filter, fill/fit geometry, the decode guard, the cache key, eviction order and the bad-file memo. Folder listing, decoding and cache I/O are M3 (`PhotoSource`, `ImageCache`). |
| `Controller.kt` | `frame/controller.py` | Modes are an enum; button ids stay strings. |

**Change from the plan's recommendation:** plan §4 lists `org.json` for JSON. `domain/` uses `MiniJson` instead, a small strict reader. The reasons:
- `org.json` calls are stubs in JVM unit tests unless a test dependency is added.
- That dependency's version couldn't be verified, because Maven Central is unreachable from here.
- Keeping the parser inside `domain/` means no new dependency at all.

`MiniJson` is covered by `WeatherTest.miniJsonParsesAndRejects`. The M4 `WeatherClient` can still use `HttpURLConnection` and pass the body to `parseWeather`.

## Test mapping (Python → Kotlin)

The Python suites in scope have 91 test functions. The Kotlin suites have 100 tests; the extra ones are marked "Kotlin-only" in the code.

**Ported 1:1** (same name in camelCase):

| Python file | Python tests | Kotlin class | Status |
|---|---|---|---|
| `test_alarm.py` | 13 | `AlarmTest` | All 13 ported, plus 2 Kotlin-only (clock jump marks missed; snooze during a test ring) |
| `test_night.py` | 9 | `NightTest` | All 9 ported |
| `test_today.py` | 9 | `TodayTest` | All 9 ported. `load_items` cases go through `parseTodayItems(text)`; the missing-file case is `parseTodayItems(null)`. Plus 5 Kotlin-only TOML-subset tests. |
| `test_weather.py` | 13 | `WeatherTest` | All 13 ported, including the threaded concurrency test. Plus `miniJsonParsesAndRejects`. |
| `test_controller.py` | 19 (21 with the 3-way parametrize) | `ControllerTest` | All ported; the parametrized test became 3 tests. Plus 2 Kotlin-only. `formattingHelpers` also covers `fmtHour` and `fmtInterval`. |
| `test_photos.py`, library tests | 5 | `PhotosTest` | All 5 ported against `PhotoQueue` and `isPhotoPath` (scan → filter over relative paths). Plus 1 Kotlin-only. |

**Ported in part:**

| Python test | Kotlin test | What's covered now | What's left, and when |
|---|---|---|---|
| `test_config.py::test_missing_file_gives_defaults_and_warning` | `emptyStoreGivesDefaults` | Defaults from empty storage | The "file not found" warning, `photo_dir` folder existence (M3/M4) |
| `test_photos.py::test_cache_output_size_exact` | `cacheOutputSizeExact` | Plan dimensions | Real output files at exact size (M3 `ImageCache`) |
| `…::test_cache_hit_reuses_file_and_source_change_misses` | `cacheHitReusesFileAndSourceChangeMisses` | Key equality and inequality | File reuse on disk (M3) |
| `…::test_exif_rotated_image_upright` | `exifRotatedImageUpright` | Oriented size and fit rectangle | Pixel check on a decoded EXIF-6 JPEG (M3) |
| `…::test_fill_portrait_keeps_top` | `fillPortraitKeepsTop` | Crop starts at the top; the band stays in view | Pixel check (M3) |
| `…::test_fill_landscape_is_centered` | `fillLandscapeIsCentered` | Centered crop | Pixel check (M3) |
| `…::test_fit_backdrop_not_black` | `fitBackdropNotBlack` | Backdrop geometry and constants | Pixel check (M3) |
| `…::test_corrupt_file_returns_none` | `corruptFileReturnsNull` | Reported once per file version | Decoding real corrupt or truncated files, no `.tmp` files left behind (M3) |
| `…::test_eviction_under_limit` | `evictionUnderLimit` | LRU order and the size limit | Deleting real files (M3) |
| `…::test_same_mtime_replacement_misses_cache` | `sameMtimeReplacementMissesCache` | Byte size is part of the key | Real file replacement (M3) |
| `…::test_oversized_image_rejected_before_decoding` | `oversizedImageRejectedBeforeDecoding` | Limit, and JPEG sampling 4000×3000 → 1000×750 | Proof that no full-size decode happens (M3) |

**Ported as validation of stored values:** `test_rejected_value_logged_with_fallback`, `test_bad_alarm_time_is_critical`, `test_save_roundtrip` (as `validateConfig(cfg.toRaw()) == cfg`), `test_partial_location_is_critical`, and `test_nul_in_path_is_rejected` (photo_dir only; `todo_path` no longer exists, per D3). Two Kotlin-only tests cover each parser's type rules.

### Deferred cases (need acceptance)

These seven `test_config.py` cases test the Pi's TOML file persistence. Plan §3 replaces that with DataStore, which M4 builds, so there is no M2 code for them to test:

| Python test | Behavior to keep | Proposed home |
|---|---|---|
| `test_bad_toml_gives_defaults` | Corrupt storage → defaults plus a warning banner | M4 `SettingsRepository` (DataStore corruption handler) |
| `test_save_failure_keeps_previous_file` | Failed save leaves the previous settings | M4: DataStore writes are transactional |
| `test_invalid_main_uses_backup` | Falls back to the last good settings | M4: decide whether a backup is still needed with DataStore |
| `test_invalid_utf8_falls_back_to_backup_then_defaults` | Same as above | M4 |
| `test_backup_copy_failure_keeps_previous_file` | Same as above | M4 |
| `test_directory_is_fsynced_after_replace` | Durable save | M4: covered by DataStore; confirm |
| `test_directory_sync_failure_is_logged_not_fatal` | Same as above | M4 |

**Decision needed (Android lead, then PO sign-off):** do these deferrals, together with the pixel-level parts of the photo cache tests listed above, satisfy the M2 done-when condition? Or should M2 stay open until M3/M4 add those tests? Until that is decided, M2 is recorded as "implemented, acceptance pending", not "done".

## No Python changes

The Python code and tests are unchanged. The baseline's `pytest` suite was not run in this environment, because pytest isn't installed. The Kotlin port only reads the Python sources.
