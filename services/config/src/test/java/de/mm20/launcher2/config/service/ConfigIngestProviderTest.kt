package de.mm20.launcher2.config.service

import android.content.Context
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.os.Process
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException

@RunWith(RobolectricTestRunner::class)
class ConfigIngestProviderTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    private lateinit var provider: ConfigIngestProvider
    private lateinit var target: File

    private fun uri(path: String): Uri =
        Uri.parse("content://${context.packageName}${ConfigIngestProvider.AuthoritySuffix}/$path")

    @Before
    fun setup() {
        provider = Robolectric
            .buildContentProvider(ConfigIngestProvider::class.java)
            .create("${context.packageName}${ConfigIngestProvider.AuthoritySuffix}")
            .get()
        provider.callingUid = { Process.SHELL_UID }
        target = ConfigLocation.configFile(context)!!
        target.parentFile!!.mkdirs()
        target.delete()
        uploads().forEach { it.delete() }
    }

    private fun uploads(): List<File> =
        target.parentFile!!.listFiles { f -> f.name.endsWith(".ingest") }!!.toList()

    private fun awaitCommit() {
        val deadline = System.currentTimeMillis() + 5_000
        while (!target.exists() && System.currentTimeMillis() < deadline) Thread.sleep(20)
    }

    @Test
    fun `shell may stream the config and the close commits it`() {
        val pfd = provider.openFile(uri("launcher.json"), "w")
        ParcelFileDescriptor.AutoCloseOutputStream(pfd).use { it.write("{}".toByteArray()) }

        // The close listener runs on the provider's handler thread.
        awaitCommit()
        assertEquals("{}", target.readText())
        assertEquals(emptyList<File>(), uploads())
    }

    @Test
    fun `concurrent uploads get distinct temp files`() {
        val first = provider.openFile(uri("launcher.json"), "w")
        val second = provider.openFile(uri("launcher.json"), "w")

        assertEquals(2, uploads().size)
        first.close()
        second.close()
        awaitCommit()
    }

    @Test
    fun `root may open the config path for writing`() {
        provider.callingUid = { Process.ROOT_UID }

        provider.openFile(uri("launcher.json"), "w").close()

        awaitCommit()
        assertTrue(target.exists())
    }

    @Test(expected = SecurityException::class)
    fun `any other uid is rejected`() {
        provider.callingUid = { 10123 }

        provider.openFile(uri("launcher.json"), "w")
    }

    @Test(expected = FileNotFoundException::class)
    fun `unknown path is rejected`() {
        provider.openFile(uri("other.json"), "w")
    }

    @Test(expected = FileNotFoundException::class)
    fun `nested path is rejected`() {
        provider.openFile(uri("config/launcher.json"), "w")
    }

    @Test(expected = SecurityException::class)
    fun `read mode is rejected`() {
        provider.openFile(uri("launcher.json"), "r")
    }

    @Test
    fun `clean close commits the upload atomically onto the config file`() {
        target.writeText("old")
        val tmp = provider.newTempFile(target.parentFile!!).apply { writeText("new") }

        assertTrue(provider.commit(tmp, target, null))

        assertEquals("new", target.readText())
        assertFalse(tmp.exists())
    }

    @Test
    fun `failed transfer discards the upload and keeps the previous config`() {
        target.writeText("old")
        val tmp = provider.newTempFile(target.parentFile!!).apply { writeText("partial") }

        assertFalse(provider.commit(tmp, target, IOException("client died")))

        assertEquals("old", target.readText())
        assertFalse(tmp.exists())
    }

    @Test
    fun `temp file lives next to the config so the rename is atomic`() {
        val tmp = provider.newTempFile(target.parentFile!!)
        assertEquals(target.parentFile, tmp.parentFile)
        assertTrue(tmp.name.startsWith("launcher.json."))
        assertTrue(tmp.name.endsWith(".ingest"))
    }

    @Test
    fun `stale uploads are swept, fresh ones are kept`() {
        val dir = target.parentFile!!
        val stale = provider.newTempFile(dir)
        val fresh = provider.newTempFile(dir)
        val now = System.currentTimeMillis()
        stale.setLastModified(now - ConfigIngestProvider.StaleUploadMs - 1_000)
        fresh.setLastModified(now)

        provider.sweepStaleUploads(dir, now)

        assertFalse(stale.exists())
        assertTrue(fresh.exists())
    }

    @Test(expected = UnsupportedOperationException::class)
    fun `query is rejected`() {
        provider.query(uri("launcher.json"), null, null, null, null)
    }

    @Test(expected = UnsupportedOperationException::class)
    fun `insert is rejected`() {
        provider.insert(uri("launcher.json"), null)
    }

    @Test(expected = UnsupportedOperationException::class)
    fun `update is rejected`() {
        provider.update(uri("launcher.json"), null, null, null)
    }

    @Test(expected = UnsupportedOperationException::class)
    fun `delete is rejected`() {
        provider.delete(uri("launcher.json"), null, null)
    }
}
