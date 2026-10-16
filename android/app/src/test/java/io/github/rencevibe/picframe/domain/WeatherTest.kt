package io.github.rencevibe.picframe.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.URI
import java.net.URLDecoder
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Port of tests/test_weather.py. The fixture is a copy of tests/fixtures/open_meteo.json. */
class WeatherTest {
    private val la: ZoneId = ZoneId.of("America/Los_Angeles")
    private val t0 = ZonedDateTime.of(2026, 10, 14, 12, 30, 0, 0, la)
    private val fixture = javaClass.getResource("/open_meteo.json")!!.readText()

    private class FakeFetch(vararg results: Any) : (String, Int) -> String {
        val results = results.toMutableList()
        val urls = mutableListOf<String>()
        val timeouts = mutableListOf<Int>()
        override fun invoke(url: String, timeoutS: Int): String {
            urls += url
            timeouts += timeoutS
            val r = results.removeAt(0)
            if (r is Exception) throw r
            return r as String
        }
    }

    /** Monotonic clock the tests advance by hand. */
    private class FakeClock : () -> Double {
        var t = 1000.0
        override fun invoke() = t
        fun advance(minutes: Int) {
            t += minutes * 60
        }
    }

    private fun service(fetch: FakeFetch, clock: FakeClock = FakeClock(), units: String = "fahrenheit") =
        WeatherService(Config(lat = 47.6, lon = -122.33, units = units), fetch, spawn = { it() }, clock = clock)

    private fun query(url: String): Map<String, String> = URI(url).rawQuery.split("&").associate {
        val (k, v) = it.split("=", limit = 2)
        k to URLDecoder.decode(v, "UTF-8")
    }

    @Test fun buildUrl() {
        val q = query(buildWeatherUrl(47.6, -122.33, "celsius"))
        assertEquals("api.open-meteo.com", URI(buildWeatherUrl(1.0, 2.0, "fahrenheit")).host)
        assertEquals("47.6", q["latitude"])
        assertEquals("-122.33", q["longitude"])
        assertEquals("temperature_2m,weather_code", q["current"])
        assertEquals("temperature_2m_max,temperature_2m_min", q["daily"])
        assertEquals("auto", q["timezone"])
        assertEquals("1", q["forecast_days"])
        assertEquals("celsius", q["temperature_unit"])
    }

    @Test fun parseFixture() {
        val w = parseWeather(fixture, t0)
        assertEquals(listOf<Any>(64, 2, "partly-cloudy", "Partly cloudy", 72, 55), listOf(w.temp, w.code, w.icon, w.label, w.high, w.low))
        assertEquals(t0, w.fetched)
    }

    @Test fun iconMappingTable() {
        assertEquals("clear", iconFor(0))
        for (c in listOf(1, 2)) assertEquals("partly-cloudy", iconFor(c))
        assertEquals("cloudy", iconFor(3))
        for (c in listOf(45, 48)) assertEquals("fog", iconFor(c))
        for (c in listOf(51, 67, 80, 82)) assertEquals("rain", iconFor(c))
        for (c in listOf(71, 77, 85, 86)) assertEquals("snow", iconFor(c))
        for (c in listOf(95, 99)) assertEquals("storm", iconFor(c))
        for (c in listOf(1234, 4, 46, 68)) assertEquals("unknown", iconFor(c))
    }

    @Test fun fetchUses10sTimeoutAndConfigUnits() {
        val fetch = FakeFetch(fixture)
        service(fetch, units = "celsius").update(t0)
        assertEquals(listOf(10), fetch.timeouts)
        assertTrue("temperature_unit=celsius" in fetch.urls[0])
    }

    @Test fun updatesAtMostEvery30Min() {
        val fetch = FakeFetch(fixture, fixture)
        val clock = FakeClock()
        val svc = service(fetch, clock)
        svc.update(t0)
        clock.advance(29)
        svc.update(t0.plusMinutes(29))
        assertEquals(1, fetch.urls.size)
        clock.advance(1)
        svc.update(t0.plusMinutes(30))
        assertEquals(2, fetch.urls.size)
    }

    @Test fun failureKeepsLastGood() {
        val bad = fixture.replace(Regex("\"current\": \\{[^}]*\\}"), "\"current\": {}")
        val fetch = FakeFetch(fixture, java.io.IOException("network down"), bad)
        val clock = FakeClock()
        val svc = service(fetch, clock)
        for (m in listOf(0L, 30L, 60L)) {
            svc.update(t0.plusMinutes(m))
            clock.advance(30)
        }
        assertEquals(3, fetch.urls.size)
        val w = svc.current(t0.plusMinutes(70))
        assertNotNull(w)
        assertEquals(t0, w!!.fetched)
        assertEquals(64, w.temp)
    }

    @Test fun staleAfter3hReturnsNull() {
        val svc = service(FakeFetch(fixture))
        assertNull(svc.current(t0)) // never fetched
        svc.update(t0)
        assertNotNull(svc.current(t0.plusHours(3)))
        assertNull(svc.current(t0.plusHours(3).plusSeconds(1)))
    }

    @Test fun noLocationNeverFetches() {
        val fetch = FakeFetch()
        val svc = WeatherService(Config(), fetch, spawn = { it() }, clock = FakeClock())
        for (h in 0L until 5L) svc.update(t0.plusHours(h))
        assertEquals(emptyList<String>(), fetch.urls)
        assertNull(svc.current(t0))
    }

    @Test fun runsInBackgroundThread() {
        val started = mutableListOf<() -> Unit>()
        val svc = WeatherService(Config(lat = 1.0, lon = 2.0), FakeFetch(fixture), spawn = { started += it }, clock = FakeClock())
        svc.update(t0)
        assertEquals(1, started.size)
        assertNull(svc.current(t0)) // nothing ran on the caller's thread
        svc.update(t0.plusMinutes(45))
        assertEquals(1, started.size) // still in flight: no second fetch
        started[0]()
        assertNotNull(svc.current(t0))
    }

    @Test fun fixtureMatchesOpenMeteoFormatAndDefaultUnits() {
        // Shape of a real /v1/forecast response for the URL buildWeatherUrl makes
        val root = MiniJson.parse(fixture) as Map<*, *>
        assertEquals(
            setOf("latitude", "longitude", "generationtime_ms", "utc_offset_seconds", "timezone",
                "timezone_abbreviation", "elevation", "current_units", "current", "daily_units", "daily"),
            root.keys,
        )
        val current = root["current"] as Map<*, *>
        val currentUnits = root["current_units"] as Map<*, *>
        val daily = root["daily"] as Map<*, *>
        val dailyUnits = root["daily_units"] as Map<*, *>
        assertEquals(setOf("time", "interval", "temperature_2m", "weather_code"), current.keys)
        assertEquals(current.keys, currentUnits.keys)
        assertEquals(setOf("time", "temperature_2m_max", "temperature_2m_min"), daily.keys)
        assertEquals(daily.keys, dailyUnits.keys)
        assertTrue(daily.values.all { (it as List<*>).size == 1 }) // forecast_days=1
        LocalDateTime.parse(current["time"] as String)
        // Units in the fixture are what Config's default units request
        val symbol = mapOf("fahrenheit" to "°F", "celsius" to "°C")[Config().units]
        assertEquals(symbol, currentUnits["temperature_2m"])
        assertEquals(symbol, dailyUnits["temperature_2m_max"])
        assertEquals(symbol, dailyUnits["temperature_2m_min"])
        assertTrue("temperature_unit=" + Config().units in buildWeatherUrl(1.0, 2.0, Config().units))
    }

    @Test fun spawnFailureDoesNotLeaveServiceBusy() {
        val clock = FakeClock()
        val svc = WeatherService(Config(lat = 1.0, lon = 2.0), FakeFetch(fixture),
            spawn = { throw IllegalStateException("can't start new thread") }, clock = clock)
        svc.update(t0) // must not throw
        assertNull(svc.current(t0))
        svc.spawn = { it() }
        clock.advance(30)
        svc.update(t0.plusMinutes(30))
        assertNotNull(svc.current(t0.plusMinutes(30)))
    }

    @Test fun refreshIntervalIgnoresWallClockJumps() {
        val fetch = FakeFetch(fixture, fixture)
        val clock = FakeClock()
        val svc = service(fetch, clock)
        svc.update(t0)
        clock.advance(31)
        svc.update(t0.minusHours(2)) // NTP set the wall clock back: still refreshes
        assertEquals(2, fetch.urls.size)
        clock.advance(1)
        svc.update(t0.plusDays(1)) // wall clock jumped ahead: no extra fetch
        assertEquals(2, fetch.urls.size)
    }

    @Test fun concurrentUpdatesAndReadsDuringFetch() {
        val release = CountDownLatch(1)
        val entered = CountDownLatch(1)
        val second = fixture.replace("63.7", "50.2")
        val responses = Collections.synchronizedList(mutableListOf(fixture, second))
        val slowFetch: (String, Int) -> String = { _, _ ->
            entered.countDown()
            release.await(5, TimeUnit.SECONDS)
            responses.removeAt(0)
        }
        val clock = FakeClock()
        val svc = WeatherService(Config(lat = 1.0, lon = 2.0), slowFetch, clock = clock) // real threads
        svc.update(t0)
        assertTrue(entered.await(5, TimeUnit.SECONDS))
        repeat(100) { // UI keeps calling while the fetch is in flight
            svc.update(t0)
            assertNull(svc.current(t0))
        }
        release.countDown()

        val seen = Collections.synchronizedList(mutableListOf<Int?>())
        val later = t0.plusMinutes(31)
        val reader = Thread { // reads until it sees the second result (or gives up)
            for (i in 0 until 500_000) {
                val temp = svc.current(later)?.temp
                seen += temp
                if (temp == 50) return@Thread
            }
        }
        for (i in 0 until 500) { // first fetch has completed
            if (svc.current(t0) != null && !svc.isBusy) break
            Thread.sleep(10)
        }
        clock.advance(31)
        reader.start()
        svc.update(later) // second fetch, completing while the reader runs
        reader.join(5000)
        assertEquals(emptyList<String>(), responses.toList()) // exactly two fetches despite 100+ update calls
        assertTrue(seen.toSet().all { it == null || it == 64 || it == 50 }) // every read saw a whole result
        assertEquals(50, seen.last())
    }

    // Kotlin-only: the JSON reader the domain uses instead of org.json

    @Test fun miniJsonParsesAndRejects() {
        assertEquals(
            mapOf("a" to listOf(1L, -2.5, 1e3, "x\"é", true, null), "b" to emptyMap<String, Any?>()),
            MiniJson.parse("""{"a": [1, -2.5, 1e3, "x\"é", true, null], "b": {}}"""),
        )
        for (bad in listOf("", "{", "[1,]", "{\"a\" 1}", "01", "\"unterminated", "{} x", "tru")) {
            try {
                MiniJson.parse(bad)
                throw AssertionError("accepted: $bad")
            } catch (e: IllegalArgumentException) {
                assertFalse(e.message.isNullOrEmpty())
            }
        }
    }

    @Test fun miniJsonWritesWhatItReads() {
        val value = mapOf("s" to "a\"b\\c\n", "n" to listOf(1L, -2.5, true, null), "m" to mapOf("k" to "é"))
        assertEquals(value, MiniJson.parse(MiniJson.write(value)))
        assertEquals("""{"lat":47.6,"days":[0,6]}""", MiniJson.write(linkedMapOf("lat" to 47.6, "days" to listOf(0, 6))))
    }
}
