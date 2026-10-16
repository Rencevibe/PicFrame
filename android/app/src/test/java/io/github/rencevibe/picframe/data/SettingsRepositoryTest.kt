package io.github.rencevibe.picframe.data

import io.github.rencevibe.picframe.domain.Config
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File
import java.io.IOException
import java.time.LocalTime

/**
 * The storage cases of tests/test_config.py (deferred from M2), against DataStore plus the
 * backup file that replaces config.toml.bak.
 */
@RunWith(RobolectricTestRunner::class)
class SettingsRepositoryTest {
    @get:Rule val tmp = TemporaryFolder()
    private val jobs = mutableListOf<Job>()
    private val file by lazy { File(tmp.root, "store/settings.preferences_pb") }
    private val backup by lazy { File(tmp.root, "backup/settings-backup.json").apply { parentFile?.mkdirs() } }

    /** A fresh repository instance (as after an app restart); closes the previous one first. */
    private fun open(writeBackup: (File, String) -> Unit = ::atomicWrite): SettingsRepository = runBlocking {
        jobs.forEach { it.cancelAndJoin() }
        jobs.clear()
        val job = Job()
        jobs += job
        SettingsRepository.create(file, backup, CoroutineScope(Dispatchers.IO + job), writeBackup)
    }

    @After fun close() = runBlocking { jobs.forEach { it.cancelAndJoin() } }

    private fun sample() = Config(
        photoDir = "content://tree/photos", intervalS = 60, fadeMs = 0, lat = 47.6, lon = -122.3, units = "celsius",
        clock24h = true, weekStart = "monday", nightStart = LocalTime.of(22, 30), alarmTime = LocalTime.of(7, 15),
        alarmDays = setOf(5, 6), snoozeMin = 5, alarmSound = "content://media/internal/audio/media/7",
    )

    @Test fun missingFileGivesDefaults() = runBlocking {
        val l = open().load()
        assertEquals(Config(), l.config)
        assertNull(l.banner)
        assertFalse(l.setupDone)
    }

    @Test fun badFileGivesDefaultsAndWarning() = runBlocking { // test_bad_toml_gives_defaults
        file.parentFile!!.mkdirs()
        file.writeText("x = [")
        val l = open().load()
        assertEquals(Config(), l.config)
        assertEquals("Settings were unreadable — using defaults", l.banner)
        assertTrue(l.warnings.any { "defaults" in it })
    }

    @Test fun saveRoundtrip() = runBlocking {
        val repo = open()
        assertNull(repo.save(sample()))
        val l = open().load()
        assertEquals(sample(), l.config)
        assertEquals(emptyList<String>(), l.warnings)
        assertNull(l.banner)
    }

    @Test fun saveFailureKeepsPreviousFile() = runBlocking {
        val repo = open()
        val cfg = sample()
        assertNull(repo.save(cfg))
        val before = file.readBytes()
        val dir = file.parentFile!!
        try {
            dir.setWritable(false)
            val reason = repo.save(cfg.copy(intervalS = 120))
            assertNotNull(reason)
            assertTrue(file.readBytes().contentEquals(before))
        } finally {
            dir.setWritable(true)
        }
        assertEquals(60, open().load().config.intervalS)
        assertTrue(dir.listFiles().orEmpty().none { it.name.endsWith(".tmp") })
    }

    @Test fun invalidMainUsesBackup() = runBlocking {
        val repo = open()
        val cfg = sample()
        assertNull(repo.save(cfg))
        assertNull(repo.save(cfg.copy(snoozeMin = 12))) // the first save becomes the backup
        file.writeText("x = [")
        val l = open().load()
        assertEquals(5, l.config.snoozeMin)
        assertEquals(cfg.photoDir, l.config.photoDir)
        assertEquals("Settings were unreadable — using last saved settings", l.banner)
        assertTrue(l.warnings.any { "last saved" in it })
    }

    @Test fun invalidBytesFallBackToBackupThenDefaults() = runBlocking { // test_invalid_utf8_…
        file.parentFile!!.mkdirs()
        file.writeBytes(byteArrayOf(0x75, 0xFF.toByte(), 0xFE.toByte(), 0x0A))
        assertEquals(Config(), open().load().config) // no backup yet: defaults
        val repo = open()
        assertNull(repo.save(sample()))
        assertNull(repo.save(sample())) // creates a valid backup
        file.writeBytes(byteArrayOf(0x75, 0xFF.toByte(), 0xFE.toByte(), 0x0A))
        val l = open().load()
        assertEquals(sample(), l.config)
        assertTrue(l.warnings.any { "last saved" in it })
    }

    @Test fun backupCopyFailureKeepsPreviousFile() = runBlocking {
        val repo = open { _, _ -> throw IOException("No space left on device") }
        assertNull(repo.save(sample())) // nothing to back up yet
        val before = file.readBytes()
        val reason = repo.save(sample().copy(intervalS = 120))
        assertTrue(reason != null && "No space" in reason)
        assertTrue(file.readBytes().contentEquals(before))
        assertEquals(60, open().load().config.intervalS)
    }

    @Test fun backupIsWrittenAtomically() = runBlocking { // stands in for the Pi's fsync cases
        val repo = open()
        assertNull(repo.save(sample()))
        assertNull(repo.save(sample().copy(intervalS = 120)))
        assertTrue(backup.exists())
        assertFalse(File(backup.parentFile, backup.name + ".tmp").exists())
        assertEquals(60, SettingsRepository.parse(backup.readText())!!["interval_s"].let { (it as Number).toInt() })
    }

    @Test fun invalidValueIsReportedAndCritical() = runBlocking {
        val repo = open()
        assertNull(repo.save(sample()))
        // Simulate a value an older or edited store holds that today's rules reject
        val bad = SettingsRepository.parse(backup.takeIf { it.exists() }?.readText() ?: "{}")!!.toMutableMap()
        bad["alarm_time"] = "25:99"
        atomicWrite(backup, io.github.rencevibe.picframe.domain.MiniJson.write(bad))
        file.writeText("x = [") // force recovery from that backup
        val l = open().load()
        assertEquals(LocalTime.of(6, 30), l.config.alarmTime)
        assertEquals("Settings were unreadable — using last saved settings", l.banner) // recovery outranks
        assertTrue(l.warnings.any { "alarm_time" in it })
    }

    @Test fun unreadableFileUsesBackupWithBanner() = runBlocking { // regression: was silent defaults
        val repo = open()
        assertNull(repo.save(sample()))
        assertNull(repo.save(sample().copy(snoozeMin = 12))) // backup holds snoozeMin = 5
        try {
            file.setReadable(false) // exists but can't be read: not corruption, not "no settings"
            val l = open().load()
            assertEquals(5, l.config.snoozeMin)
            assertEquals("Settings were unreadable — using last saved settings", l.banner)
            assertTrue(l.setupDone) // a read failure must not send the user back to first-run setup
        } finally {
            file.setReadable(true)
        }
    }

    private class FakeGrants(vararg uris: String) : FolderGrants {
        val held = uris.toMutableList()
        val released = mutableListOf<String>()
        override fun held() = held.toList()
        override fun release(uri: String) {
            released += uri
            held -= uri
        }
    }

    private val old = "content://tree/old"
    private val new = "content://tree/new"
    private val abandoned = "content://tree/abandoned"

    @Test fun oldFolderGrantIsKeptUntilNoSavedCopyNamesIt() = runBlocking { // regression: SAF grant leak
        val grants = FakeGrants(old, new, abandoned)
        val repo = open()
        assertNull(repo.saveAndPruneGrants(sample().copy(photoDir = old), grants))
        assertNull(repo.saveAndPruneGrants(sample().copy(photoDir = new), grants))
        // The old folder is now only in the backup, but a corrupt store loads the backup:
        // releasing it here would leave the recovered settings without folder access
        assertEquals(listOf(abandoned), grants.released)
        file.writeText("x = [")
        assertEquals(old, open().load().config.photoDir)
        assertTrue(old in grants.held)

        val again = open()
        assertNull(again.saveAndPruneGrants(sample().copy(photoDir = new), grants)) // backup: old (recovered)
        assertEquals(listOf(abandoned), grants.released)
        assertNull(again.saveAndPruneGrants(sample().copy(photoDir = new, snoozeMin = 12), grants))
        assertEquals(listOf(abandoned, old), grants.released)
        assertEquals(listOf(new), grants.held)
    }

    @Test fun failedSaveReleasesNoGrant() = runBlocking {
        val grants = FakeGrants(old)
        val repo = open { _, _ -> throw IOException("No space left on device") }
        assertNull(repo.saveAndPruneGrants(sample().copy(photoDir = old), grants)) // no backup yet
        grants.held += new // picked in Settings, then the save fails
        assertNotNull(repo.saveAndPruneGrants(sample().copy(photoDir = new), grants))
        assertTrue(grants.released.isEmpty())
        assertEquals(old, open().load().config.photoDir)
    }

    @Test fun unreadableBackupReleasesNoGrant() = runBlocking { // a later load may still read it
        val first = open()
        assertNull(first.save(sample().copy(photoDir = old)))
        assertNull(first.save(sample().copy(photoDir = new))) // backup names old
        val grants = FakeGrants(old, new)
        val repo = open { _, _ -> } // this save leaves the backup file as it is
        try {
            backup.setReadable(false)
            assertNull(repo.foldersInUse())
            assertNull(repo.saveAndPruneGrants(sample().copy(photoDir = new, snoozeMin = 12), grants))
            assertTrue(grants.released.isEmpty())
        } finally {
            backup.setReadable(true)
        }
        assertEquals(setOf(old, new), repo.foldersInUse())
    }

    @Test fun setupDoneIsRemembered() = runBlocking {
        val repo = open()
        repo.setSetupDone()
        assertTrue(open().load().setupDone)
    }
}
