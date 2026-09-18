package de.mm20.launcher2.backup

import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

/**
 * Characterization tests: pin the current backup/restore behavior before the
 * fork touches this code (see AGENTS.md test policy).
 */
@RunWith(RobolectricTestRunner::class)
class BackupManagerTest {

    @get:Rule
    val tmp = TemporaryFolder()

    /**
     * A [Backupable] that writes one file named [name] with [content] during
     * backup and captures whatever it finds in the restore dir.
     */
    private class FakeComponent(
        val name: String,
        val content: String,
    ) : Backupable {
        var restoredContent: String? = null
            private set

        override suspend fun backup(toDir: File) {
            File(toDir, name).writeText(content)
        }

        override suspend fun restore(fromDir: File) {
            restoredContent = File(fromDir, name).takeIf { it.exists() }?.readText()
        }
    }

    private fun newManager(vararg components: Backupable): BackupManager {
        return BackupManager(
            ApplicationProvider.getApplicationContext(),
            components.toList(),
        )
    }

    @Test
    fun `backup then restore round trips all components`() = runTest {
        val settings = FakeComponent("settings", "{\"theme\":\"dark\"}")
        val widgets = FakeComponent("widgets", "weather,music")
        val backupFile = tmp.newFile("backup.zip")

        newManager(settings, widgets).backup(Uri.fromFile(backupFile))
        assertTrue("backup archive was created", backupFile.length() > 0)

        val settingsAfter = FakeComponent("settings", "")
        val widgetsAfter = FakeComponent("widgets", "")
        newManager(settingsAfter, widgetsAfter).restore(Uri.fromFile(backupFile))

        assertEquals("{\"theme\":\"dark\"}", settingsAfter.restoredContent)
        assertEquals("weather,music", widgetsAfter.restoredContent)
    }

    @Test
    fun `readBackupMeta returns the metadata written during backup`() = runTest {
        val backupFile = tmp.newFile("backup.zip")
        newManager().backup(Uri.fromFile(backupFile))

        val meta = newManager().readBackupMeta(Uri.fromFile(backupFile))

        assertNotNull(meta)
        assertEquals(BackupManager.BackupFormat, meta!!.format)
        assertTrue(meta.timestamp > 0)
    }

    @Test
    fun `readBackupMeta returns null for archive without meta entry`() = runTest {
        val notABackup = tmp.newFile("random.zip")
        java.util.zip.ZipOutputStream(notABackup.outputStream()).use {
            it.putNextEntry(java.util.zip.ZipEntry("something-else"))
            it.write("nope".toByteArray())
            it.closeEntry()
        }

        val meta = newManager().readBackupMeta(Uri.fromFile(notABackup))

        assertEquals(null, meta)
    }

    @Test
    fun `checkCompatibility pins format negotiation`() {
        val manager = newManager()
        fun meta(format: String) = BackupMetadata(
            deviceName = "test", timestamp = 0L, appVersionName = "", format = format,
        )

        val cases = listOf(
            "1.9" to BackupCompatibility.Compatible,
            "1.5" to BackupCompatibility.PartiallyCompatible,
            "1.0" to BackupCompatibility.PartiallyCompatible,
            "2.0" to BackupCompatibility.Incompatible,
            "0.9" to BackupCompatibility.Incompatible,
            "1" to BackupCompatibility.Incompatible,
            "garbage" to BackupCompatibility.Incompatible,
            "" to BackupCompatibility.Incompatible,
        )
        for ((format, expected) in cases) {
            assertEquals("format '$format'", expected, manager.checkCompatibility(meta(format)))
        }
    }

    @Test
    fun `current backup format is 1_9`() {
        assertEquals("1.9", BackupManager.BackupFormat)
    }
}
