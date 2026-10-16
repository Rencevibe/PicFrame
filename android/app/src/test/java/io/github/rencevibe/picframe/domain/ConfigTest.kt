package io.github.rencevibe.picframe.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalTime

/**
 * Port of the validation cases in tests/test_config.py. Storage moves to DataStore (plan §3),
 * so the TOML file, .bak, atomic-write and fsync cases are mapped to M4 in
 * android/docs/m2-domain.md; here, raw stored values go through [validateConfig].
 */
class ConfigTest {
    private fun sample() = Config(
        photoDir = "content://com.android.externalstorage.documents/tree/primary%3APictures",
        intervalS = 60, fadeMs = 0, lat = 47.6, lon = -122.3, units = "celsius", clock24h = true,
        weekStart = "monday", nightStart = LocalTime.of(22, 30), alarmTime = LocalTime.of(7, 15),
        alarmDays = setOf(5, 6), snoozeMin = 5,
    )

    @Test fun emptyStoreGivesDefaults() { // test_missing_file_gives_defaults_and_warning (value part)
        val r = validateConfig(emptyMap())
        assertEquals(Config(), r.config)
        assertEquals(emptyList<String>(), r.warnings)
        assertFalse(r.critical)
    }

    @Test fun rejectedValueLoggedWithFallback() {
        val r = validateConfig(mapOf("interval_s" to -5))
        assertEquals(30, r.config.intervalS)
        assertTrue(r.warnings.any { "interval_s" in it && "30" in it })
        assertFalse(r.critical) // not a critical key
    }

    @Test fun badAlarmTimeIsCritical() {
        val r = validateConfig(mapOf("photo_dir" to "content://x/tree/y", "alarm_time" to "25:99"))
        assertTrue(r.critical)
        assertEquals(LocalTime.of(6, 30), r.config.alarmTime)
        assertTrue(r.warnings.any { "alarm_time" in it })
    }

    @Test fun saveRoundtrip() { // test_save_roundtrip: what is stored validates back unchanged
        val cfg = sample()
        val r = validateConfig(cfg.toRaw())
        assertEquals(cfg, r.config)
        assertEquals(emptyList<String>(), r.warnings)
        assertFalse(r.critical)
    }

    @Test fun partialLocationIsCritical() {
        val r = validateConfig(mapOf("lat" to 47.6))
        assertTrue(r.critical)
        assertTrue(r.warnings.any { "lon" in it })
        assertNull(r.config.lat)
        assertNull(r.config.lon)
    }

    @Test fun nulInPathIsRejected() {
        val r = validateConfig(mapOf("photo_dir" to "/tmp/a\u0000b"))
        assertNull(r.config.photoDir)
        assertTrue(r.critical)
        assertTrue(r.warnings.any { "photo_dir" in it })
    }

    // Kotlin-only: per-type rules from config.py's parsers

    @Test fun typeRulesMatchThePiParsers() {
        val r = validateConfig(
            mapOf(
                "clock_24h" to 1, // not a bool
                "snooze_min" to true, // bool is not a number
                "fade_ms" to 1.5, // not a whole number
                "lon" to 181.0,
                "lat" to 12, // ints are fine for coordinates …
                "units" to "kelvin",
                "alarm_days" to emptyList<Int>(),
                "night_start" to "06:30:00",
                "mystery" to "x",
            ),
        )
        val c = r.config
        assertEquals(Config().clock24h, c.clock24h)
        assertEquals(9, c.snoozeMin)
        assertEquals(1000, c.fadeMs)
        assertNull(c.lon)
        assertNull(c.lat) // … but lon was rejected, so the pair is dropped
        assertEquals("fahrenheit", c.units)
        assertEquals(setOf(0, 1, 2, 3, 4), c.alarmDays)
        assertEquals(LocalTime.of(23, 0), c.nightStart)
        assertTrue(r.critical)
        assertTrue(r.warnings.any { it == "mystery: unknown setting, ignored" })
        assertTrue(r.warnings.any { it.startsWith("alarm_days: [] must be a non-empty list") })
    }

    @Test fun acceptsBoundaryAndLongValues() {
        val r = validateConfig(mapOf("interval_s" to 5L, "wake_s" to 3600, "alarm_days" to listOf(0L, 6L), "lat" to -90, "lon" to 180L))
        assertEquals(5, r.config.intervalS)
        assertEquals(3600, r.config.wakeS)
        assertEquals(setOf(0, 6), r.config.alarmDays)
        assertEquals(-90.0, r.config.lat!!, 0.0)
        assertEquals(emptyList<String>(), r.warnings)
    }
}
