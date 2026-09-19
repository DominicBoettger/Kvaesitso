package de.mm20.launcher2.config.service

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri
import android.os.Binder
import android.os.Handler
import android.os.HandlerThread
import android.os.ParcelFileDescriptor
import android.os.Process
import android.util.Log
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException

/**
 * Fork addition (Phase 2, ADR 0003): write-only ingest for the launcher
 * config file, the provisioning transport that works for **every** Android
 * user:
 *
 * ```
 * adb shell content write --user N \
 *     --uri content://<applicationId>.config-ingest/launcher.json < launcher.json
 * ```
 *
 * Why a provider and not `adb push`: the shell only sees the owner's
 * (user 0) external storage; `/storage/emulated/<N>/...` of a secondary
 * profile is "Permission denied" even as root (measured on the GrapheneOS
 * emulator, 2026-09-19, see ADR 0003). A content provider is resolved in the
 * target user by the system, so `--user N` reaches that user's launcher.
 *
 * This is transport only. The bytes land atomically (temp file, then rename)
 * in [ConfigLocation.configFile]; reloading stays with the two existing
 * triggers, the [ConfigWatcher] (sees the rename) and the explicit
 * [ReloadConfigReceiver] broadcast. One loader, no third code path.
 *
 * Gate: the manifest requires `WRITE_SECURE_SETTINGS` (held by shell and
 * system only) and, belt and braces, [openFile] rejects every calling uid
 * except shell and root. The provider never reads, lists or deletes; it
 * accepts exactly one path and only write modes.
 */
class ConfigIngestProvider : ContentProvider() {

    /** Overridable for tests; production reads the binder identity. */
    internal var callingUid: () -> Int = { Binder.getCallingUid() }

    private val closeHandler: Handler by lazy {
        Handler(HandlerThread("config-ingest").apply { start() }.looper)
    }

    override fun onCreate(): Boolean = true

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
        val uid = callingUid()
        if (uid != Process.SHELL_UID && uid != Process.ROOT_UID) {
            throw SecurityException("Config ingest is restricted to the shell user (caller uid $uid)")
        }
        if (uri.pathSegments != listOf(ConfigLocation.ConfigFileName)) {
            throw FileNotFoundException("Unknown ingest path: $uri")
        }
        if (!mode.startsWith("w")) {
            throw SecurityException("Config ingest is write-only (mode '$mode')")
        }
        val context = context ?: throw IllegalStateException("Provider has no context")
        val target = ConfigLocation.configFile(context)
            ?: throw FileNotFoundException("External files directory unavailable")
        val dir = target.parentFile ?: throw FileNotFoundException("No config directory")
        if (!dir.isDirectory && !dir.mkdirs()) {
            throw FileNotFoundException("Could not create ${dir.absolutePath}")
        }
        val tmp = tempFileFor(target)
        return ParcelFileDescriptor.open(
            tmp,
            ParcelFileDescriptor.MODE_WRITE_ONLY or
                    ParcelFileDescriptor.MODE_CREATE or
                    ParcelFileDescriptor.MODE_TRUNCATE,
            closeHandler,
        ) { error -> commit(tmp, target, error) }
    }

    /**
     * Completes an ingest once the caller closed the descriptor: a clean
     * close renames the temp file onto the config atomically, a failed
     * transfer discards it and leaves the previous config untouched.
     * Returns true when the config was replaced.
     */
    internal fun commit(tmp: File, target: File, error: IOException?): Boolean {
        if (error != null) {
            Log.w(TAG, "Config ingest aborted, discarding partial upload", error)
            tmp.delete()
            return false
        }
        if (!tmp.renameTo(target)) {
            Log.e(TAG, "Config ingest could not rename ${tmp.name} to ${target.name}")
            tmp.delete()
            return false
        }
        return true
    }

    internal fun tempFileFor(target: File): File =
        File(target.parentFile, "${target.name}.$IngestTmpSuffix")

    override fun getType(uri: Uri): String? = null

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?,
    ): Cursor? {
        throw UnsupportedOperationException("Config ingest is write-only: $uri")
    }

    override fun insert(uri: Uri, values: ContentValues?): Uri? {
        throw UnsupportedOperationException("Config ingest accepts streams only (content write): $uri")
    }

    override fun update(
        uri: Uri,
        values: ContentValues?,
        selection: String?,
        selectionArgs: Array<out String>?,
    ): Int {
        throw UnsupportedOperationException("Config ingest accepts streams only (content write): $uri")
    }

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int {
        throw UnsupportedOperationException("Config ingest is write-only: $uri")
    }

    companion object {
        const val AuthoritySuffix = ".config-ingest"
        internal const val IngestTmpSuffix = "ingest"
        private const val TAG = "ConfigIngestProvider"
    }
}
