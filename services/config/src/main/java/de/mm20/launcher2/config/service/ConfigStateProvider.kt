package de.mm20.launcher2.config.service

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import de.mm20.launcher2.config.ConfigParser
import de.mm20.launcher2.config.LauncherConfig
import de.mm20.launcher2.config.ReloadReport
import de.mm20.launcher2.config.toLauncherConfig
import kotlinx.coroutines.runBlocking
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import org.koin.core.context.GlobalContext

/**
 * Fork addition (Phase 2, ADR 0003): read-only read-back provider, exported
 * behind `WRITE_SECURE_SETTINGS` (shell and system only, like the rest of the
 * config surface) and with `grantUriPermissions=false`:
 *
 * - `content://<applicationId>.state/config` — the current *effective*
 *   launcher state, serialized in the public [LauncherConfig] schema (not the
 *   internal DataStore shape), so a provisioning script can compare it
 *   field-by-field against the document it pushed.
 * - `content://<applicationId>.state/diagnostics` — the latest persisted
 *   [ReloadReport]. Policy when no reload has happened yet: the `json` column
 *   contains the JSON literal `null` (not an empty object, not a missing
 *   row), so callers can distinguish "never reloaded" from a report whose
 *   fields happen to be empty.
 *
 * Both routes return exactly one row with a single `json` column of MIME type
 * `application/json`. [query] is called on binder threads; the underlying
 * stores are suspending, so it blocks with [runBlocking] — binder threads are
 * pooled for exactly this.
 *
 * Dependencies are resolved lazily: providers are created before
 * `Application.onCreate` has started Koin, so injecting in [onCreate] would
 * crash. That is not enough on its own: Android publishes providers before
 * `Application.onCreate` runs, so a query that cold-starts the process (a
 * provisioning script after the launcher was killed under memory pressure,
 * measured in the L4 provisioning run) arrives on a binder thread while Koin
 * is still starting on the main thread. [query] therefore waits until its
 * dependencies resolve, bounded by [koinStartupTimeoutMs].
 */
class ConfigStateProvider : ContentProvider(), KoinComponent {

    private val configStore: ConfigStore by inject()
    private val reportStore: ReloadReportStore by inject()

    /** Overridable for tests. */
    internal var koinStartupTimeoutMs: Long = KoinStartupTimeoutMs

    override fun onCreate(): Boolean = true

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?,
    ): Cursor {
        awaitKoin()
        val payload = when (val path = uri.lastPathSegment) {
            PathConfig -> runBlocking {
                ConfigParser.json.encodeToString(
                    LauncherConfig.serializer(),
                    configStore.readState().toLauncherConfig(),
                )
            }

            PathDiagnostics -> runBlocking {
                val report = reportStore.read()
                if (report == null) {
                    "null"
                } else {
                    ConfigParser.json.encodeToString(ReloadReport.serializer(), report)
                }
            }

            else -> throw IllegalArgumentException("Unknown URI: $uri (path '$path')")
        }
        return MatrixCursor(Columns).apply {
            addRow(arrayOf<Any?>(payload))
        }
    }

    override fun getType(uri: Uri): String {
        return when (uri.lastPathSegment) {
            PathConfig, PathDiagnostics -> JsonMimeType
            else -> throw IllegalArgumentException("Unknown URI: $uri")
        }
    }

    override fun insert(uri: Uri, values: ContentValues?): Uri {
        throw UnsupportedOperationException("Config state is read-only: $uri")
    }

    override fun update(
        uri: Uri,
        values: ContentValues?,
        selection: String?,
        selectionArgs: Array<out String>?,
    ): Int {
        throw UnsupportedOperationException("Config state is read-only: $uri")
    }

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int {
        throw UnsupportedOperationException("Config state is read-only: $uri")
    }

    /**
     * Blocks until the dependencies resolve. `startKoin` registers the global
     * context *before* it loads the modules, so "Koin exists" is not enough;
     * the definitions themselves must be there. Wall-clock on purpose: the
     * binder thread really blocks here.
     */
    private fun awaitKoin() {
        val deadline = System.nanoTime() + koinStartupTimeoutMs * 1_000_000
        while (!dependenciesResolvable()) {
            if (System.nanoTime() >= deadline) {
                throw IllegalStateException(
                    "Launcher did not finish starting within ${koinStartupTimeoutMs}ms; retry"
                )
            }
            Thread.sleep(KoinPollIntervalMs)
        }
    }

    private fun dependenciesResolvable(): Boolean {
        val koin = GlobalContext.getOrNull() ?: return false
        return try {
            koin.getOrNull<ConfigStore>() != null && koin.getOrNull<ReloadReportStore>() != null
        } catch (e: Exception) {
            false
        }
    }

    companion object {
        const val KoinStartupTimeoutMs = 15_000L
        private const val KoinPollIntervalMs = 10L
        const val PathConfig = "config"
        const val PathDiagnostics = "diagnostics"
        const val JsonMimeType = "application/json"
        const val JsonColumn = "json"
        private val Columns = arrayOf(JsonColumn)
    }
}
