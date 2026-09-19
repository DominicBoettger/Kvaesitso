package de.mm20.launcher2.config.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import de.mm20.launcher2.config.ReloadTrigger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject

/**
 * Fork addition (Phase 2, ADR 0003): explicit reload trigger for provisioning
 * scripts:
 *
 * ```
 * adb shell am broadcast -n <applicationId>/de.mm20.launcher2.config.service.ReloadConfigReceiver \
 *     -a <applicationId>.action.RELOAD_CONFIG [--user N]
 * ```
 *
 * Exported, because a non-exported receiver is unreachable for the unrooted
 * shell (measured on the GrapheneOS emulator, 2026-09-19: `am broadcast`
 * completes, the receiver never runs; release GrapheneOS has no `adb root`).
 * Gate: the manifest requires the sender to hold `WRITE_SECURE_SETTINGS`
 * (shell and system only). Unlike the ingest provider there is no uid check
 * in code: a receiver only learns the sender uid when the sender opts in
 * (`BroadcastOptions.setShareIdentityEnabled`), which `am broadcast` does
 * not, so `getSentFromUid()` is -1 for exactly the caller we want.
 *
 * Uses [goAsync] so the reload (file read + diff + DataStore writes) runs off
 * the main thread. The pending result is always finished, and a missing or
 * corrupt config file never throws — [ConfigReloader] records a failed
 * [de.mm20.launcher2.config.ReloadReport] instead.
 */
class ReloadConfigReceiver : BroadcastReceiver(), KoinComponent {

    private val reloader: ConfigReloader by inject()

    override fun onReceive(context: Context, intent: Intent) {
        val pendingResult = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                handle(context, intent, reloader)
            } catch (e: Exception) {
                Log.e(TAG, "Config reload failed unexpectedly", e)
            } finally {
                pendingResult.finish()
            }
        }
    }

    internal suspend fun handle(context: Context, intent: Intent, reloader: ConfigReloader) {
        if (intent.action != context.packageName + ActionSuffix) return
        val file = ConfigLocation.configFile(context) ?: return
        reloader.reload(file, ReloadTrigger.Broadcast)
    }

    companion object {
        const val ActionSuffix = ".action.RELOAD_CONFIG"
        private const val TAG = "ReloadConfigReceiver"
    }
}
