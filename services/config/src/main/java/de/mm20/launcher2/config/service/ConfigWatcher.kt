package de.mm20.launcher2.config.service

import android.content.Context
import android.os.FileObserver
import android.util.Log
import de.mm20.launcher2.config.ReloadTrigger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException

/**
 * Fork addition (Phase 2, ADR 0003): the convenience reload path for
 * interactive editing (edit → save → launcher updates). Watches the config
 * directory for `launcher.json` `CLOSE_WRITE` and `MOVED_TO` events (the
 * latter covers atomic save-via-rename) and debounces them by
 * [DefaultDebounceMs] so editors that write non-atomically do not trigger
 * partial reloads.
 *
 * On [start] a drift check runs once: if the config file exists and either no
 * [de.mm20.launcher2.config.ReloadReport] exists yet or the file's SHA-256
 * differs from the hash recorded in the last report, one reload is triggered
 * with [ReloadTrigger.StartupCheck]. This catches config pushes that happened
 * while the launcher was not running.
 *
 * Everything funnels into the same [ConfigReloader] as the broadcast
 * receiver; watcher-triggered reloads carry [ReloadTrigger.FileWatcher].
 */
class ConfigWatcher(
    context: Context,
    private val reloader: ConfigReloader,
    private val reportStore: ReloadReportStore,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
    private val debounceMs: Long = DefaultDebounceMs,
) {
    private val appContext = context.applicationContext

    private var observer: FileObserver? = null
    private var startJob: Job? = null
    internal var debounceJob: Job? = null

    /**
     * Starts watching and schedules the startup drift check. The app process
     * can be created by the content provider before external files are
     * available, so the initial lookup is retried instead of silently
     * disabling the watcher forever.
     */
    fun start() {
        if (observer != null || startJob?.isActive == true) return
        startJob = scope.launch {
            val dir = awaitConfigDir()
            if (dir == null) {
                Log.w(TAG, "Config directory unavailable; file watcher disabled")
                return@launch
            }
            try {
                dir.mkdirs()
            } catch (e: Exception) {
                Log.w(TAG, "Could not create config directory", e)
                return@launch
            }
            startObserver(dir)
            startupCheck()
        }
    }

    fun stop() {
        startJob?.cancel()
        startJob = null
        debounceJob?.cancel()
        debounceJob = null
        observer?.stopWatching()
        observer = null
    }

    private suspend fun awaitConfigDir(): File? {
        repeat(StartupRetryCount) {
            ConfigLocation.configDir(appContext)?.let { return it }
            delay(StartupRetryDelayMs)
        }
        return null
    }

    private fun startObserver(dir: File) {
        if (observer != null) return
        @Suppress("DEPRECATION")
        val obs = object : FileObserver(dir.absolutePath, CLOSE_WRITE or MOVED_TO) {
            override fun onEvent(event: Int, path: String?) {
                if (path == ConfigLocation.ConfigFileName) {
                    onConfigFileEvent()
                }
            }
        }
        try {
            obs.startWatching()
            observer = obs
        } catch (e: Exception) {
            Log.w(TAG, "Could not start config file observer", e)
        }
    }

    internal fun onConfigFileEvent() {
        val file = ConfigLocation.configFile(appContext) ?: return
        debounceJob?.cancel()
        debounceJob = scope.launch {
            delay(debounceMs)
            reloader.reload(file, ReloadTrigger.FileWatcher)
        }
    }

    internal fun startupCheck(): Job? {
        val file = ConfigLocation.configFile(appContext) ?: return null
        return scope.launch {
            val hash = try {
                withContext(Dispatchers.IO) {
                    if (!file.exists()) return@withContext null
                    file.readBytes().sha256Hex()
                }
            } catch (e: IOException) {
                null
            } catch (e: SecurityException) {
                null
            }
            if (hash == null && !file.exists()) return@launch
            val report = reportStore.read()
            if (report == null || report.configSha256 != hash) {
                reloader.reload(file, ReloadTrigger.StartupCheck)
            }
        }
    }

    companion object {
        const val DefaultDebounceMs = 300L
        private const val StartupRetryCount = 40
        private const val StartupRetryDelayMs = 250L
        private const val TAG = "ConfigWatcher"
    }
}
