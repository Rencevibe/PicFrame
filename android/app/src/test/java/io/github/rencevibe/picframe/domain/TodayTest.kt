package io.github.rencevibe.picframe.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime

/** Port of tests/test_today.py, plus the TOML subset the parser accepts. */
class TodayTest {
    private val now = LocalDateTime.of(2026, 10, 14, 12, 41)

    private fun appt(title: String, at: LocalTime? = null, day: LocalDate? = null, done: Boolean = false) =
        TodayItem(title, "appointment", at, day, done)

    private fun todo(title: String, at: LocalTime? = null, day: LocalDate? = null, done: Boolean = false) =
        TodayItem(title, "todo", at, day, done)

    private fun titles(items: List<TodayItem>) = items.map { it.title }

    @Test fun hidesPastTimedAndDone() {
        val items = listOf(appt("Standup", LocalTime.of(9, 30)), todo("Done thing", done = true), appt("Dentist", LocalTime.of(13, 30)))
        val (shown, more) = visibleToday(items, now)
        assertEquals(listOf("Dentist"), titles(shown))
        assertEquals(0, more)
    }

    @Test fun untimedTodayItemsAfterTimed() {
        val items = listOf(todo("Plants"), appt("Dinner", LocalTime.of(18, 0)), todo("Mail"), appt("Dentist", LocalTime.of(13, 30)))
        assertEquals(listOf("Dentist", "Dinner", "Plants", "Mail"), titles(visibleToday(items, now, limit = 10).first))
    }

    @Test fun limitAndMoreCount() {
        val (shown, more) = visibleToday((0 until 5).map { todo("t$it") }, now)
        assertEquals(listOf("t0", "t1", "t2"), titles(shown))
        assertEquals(2, more)
    }

    @Test fun otherDaysExcluded() {
        val items = listOf(todo("Later", day = now.toLocalDate().plusDays(1)), todo("Today", day = now.toLocalDate()), todo("Undated"))
        assertEquals(listOf("Today", "Undated"), titles(visibleToday(items, now).first))
    }

    @Test fun loadItemsParsesFile() {
        val text = "[[item]]\ntitle = \"Dentist\"\nkind = \"appointment\"\ntime = \"13:30\"\ndate = \"2026-10-14\"\n\n" +
            "[[item]]\ntitle = \"Water the plants\"\nkind = \"todo\"\n\n" +
            "[[item]]\ntitle = \"Bad time\"\nkind = \"todo\"\ntime = \"25:00\"\n\n" +
            "[[item]]\nkind = \"todo\"\n"
        assertEquals(
            listOf(
                TodayItem("Dentist", "appointment", LocalTime.of(13, 30), LocalDate.of(2026, 10, 14)),
                TodayItem("Water the plants", "todo", null, null),
            ),
            parseTodayItems(text),
        )
    }

    @Test fun badFileReturnsEmpty() {
        assertEquals(emptyList<TodayItem>(), parseTodayItems(null)) // missing file
        val logged = mutableListOf<String>()
        assertEquals(emptyList<TodayItem>(), parseTodayItems("item = [", log = { logged += it }))
        assertEquals(1, logged.size)
    }

    @Test fun missingKindIsSkipped() {
        val text = "[[item]]\ntitle = \"No kind\"\n\n[[item]]\ntitle = \"Ok\"\nkind = \"todo\"\n"
        assertEquals(listOf("Ok"), titles(parseTodayItems(text)))
    }

    @Test fun offsetOrSecondsTimeIsSkipped() {
        val text = "[[item]]\ntitle = \"Offset\"\nkind = \"todo\"\ntime = \"13:30+02:00\"\n\n" +
            "[[item]]\ntitle = \"Seconds\"\nkind = \"todo\"\ntime = \"13:30:00\"\n\n" +
            "[[item]]\ntitle = \"Ok\"\nkind = \"todo\"\ntime = \"13:30\"\n"
        val items = parseTodayItems(text)
        assertEquals(listOf("Ok"), titles(items))
        assertEquals(listOf("Ok"), titles(visibleToday(items, now).first))
    }

    @Test fun awareNowUsesLocalWallTime() {
        val zoned = ZonedDateTime.of(2026, 10, 14, 12, 41, 0, 0, ZoneId.of("America/Los_Angeles"))
        val items = listOf(appt("Past", LocalTime.of(9, 30)), appt("Later", LocalTime.of(13, 30)), todo("Untimed"))
        val (shown, more) = visibleToday(items, zoned)
        assertEquals(listOf("Later", "Untimed"), titles(shown))
        assertEquals(0, more)
    }

    // Kotlin-only: the TOML subset

    @Test fun commentsEscapesLiteralStringsAndDoneFlag() {
        val text = "# my list\n[[item]] # first\ntitle = \"Say \\\"hi\\\" \\u00e9\" # trailing\nkind = 'todo'\ndone = true\n" +
            "[[item]]\n\"title\" = 'C:\\path'\nkind = \"todo\"\ndone = false\n"
        val items = parseTodayItems(text)
        assertEquals(listOf("Say \"hi\" é", "C:\\path"), titles(items))
        assertEquals(listOf(true, false), items.map { it.done })
    }

    @Test fun bareTomlDatesAndTimesAreWrongTypeNotInvalidFile() {
        // tomllib reads these as date/time values; today.py then skips the item, not the file
        val text = "[[item]]\ntitle = \"Bare date\"\nkind = \"todo\"\ndate = 2026-10-14\n\n" +
            "[[item]]\ntitle = \"Bare time\"\nkind = \"todo\"\ntime = 13:30:00\n\n" +
            "[[item]]\ntitle = \"Ok\"\nkind = \"todo\"\n"
        assertEquals(listOf("Ok"), titles(parseTodayItems(text)))
    }

    @Test fun invalidSyntaxRejectsWholeFile() {
        for (bad in listOf(
            "[[item]]\ntitle = \"unterminated\n",
            "[[item]]\ntitle = \"a\"\ntitle = \"b\"\n", // duplicate key
            "[[item]]\ntitle = \"\"\"multi\nline\"\"\"\n",
            "[[item]]\nmeta = { a = 1 }\n",
            "item = \"x\"\n[[item]]\ntitle = \"y\"\nkind = \"todo\"\n",
        )) {
            assertEquals(bad, emptyList<TodayItem>(), parseTodayItems(bad))
        }
    }

    @Test fun nonTableItemOrDoneNotBooleanIsSkipped() {
        assertEquals(emptyList<TodayItem>(), parseTodayItems("item = [1, 2]\n"))
        val text = "[[item]]\ntitle = \"x\"\nkind = \"todo\"\ndone = \"yes\"\n"
        assertTrue(parseTodayItems(text).isEmpty())
    }

    @Test fun arraysAndNumbersParse() {
        val root = TomlSubset.parse("a = [1, 2.5, \"x\", true,]\nb = -7\n")
        assertEquals(listOf(1L, 2.5, "x", true), root["a"])
        assertEquals(-7L, root["b"])
    }
}
