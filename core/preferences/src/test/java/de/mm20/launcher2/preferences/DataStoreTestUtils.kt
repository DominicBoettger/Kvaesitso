package de.mm20.launcher2.preferences

import android.content.Context
import kotlinx.coroutines.runBlocking
import java.io.File

/**
 * Writes [data] directly to the settings.json file that the
 * [androidx.datastore.dataStore] delegate in `BaseSettings` reads.
 *
 * This bypasses DataStore (no active DataStore instance, so no interference
 * with the store under test) and avoids `serializer.defaultValue`, whose
 * Context-based constructor reads R.integer.config_columnCount, which is not
 * resolvable under Robolectric.
 */
internal fun seedSettingsFile(context: Context, data: LauncherSettingsData) {
    val dir = File(context.filesDir, "datastore")
    dir.mkdirs()
    val file = File(dir, "settings.json")
    runBlocking {
        LauncherSettingsDataSerializer(context).writeTo(data, file.outputStream())
    }
}
