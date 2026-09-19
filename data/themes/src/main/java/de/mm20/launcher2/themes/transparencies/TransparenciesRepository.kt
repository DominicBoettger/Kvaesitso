package de.mm20.launcher2.themes.transparencies

import android.content.Context
import androidx.room.withTransaction
import de.mm20.launcher2.database.AppDatabase
import de.mm20.launcher2.themes.DefaultThemeId
import de.mm20.launcher2.themes.R
import de.mm20.launcher2.themes.SemiTransparentId
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import java.util.UUID

class TransparenciesRepository(
    private val context: Context,
    private val database: AppDatabase,
) {
    private val scope = CoroutineScope(Dispatchers.IO + Job())

    fun getAll(): Flow<List<Transparencies>> {
        return database.themeDao().getAllTransparencies().map {
            getBuiltIn() + it.map { Transparencies(it) }
        }
    }

    fun get(id: UUID): Flow<Transparencies?> {
        if (id == DefaultThemeId) return flowOf(default)
        if (id == SemiTransparentId) return flowOf(semiTransparent)
        return database.themeDao().getTransparencies(id).map { it?.let { Transparencies(it) } }
    }

    fun create(transparencies: Transparencies) {
        scope.launch {
            database.themeDao().insertTransparencies(transparencies.toEntity())
        }
    }

    fun update(transparencies: Transparencies) {
        scope.launch {
            database.themeDao().updateTransparencies(transparencies.toEntity())
        }
    }


    fun delete(transparencies: Transparencies) {
        scope.launch {
            database.themeDao().deleteTransparencies(transparencies.id)
        }
    }

    fun getOrDefault(id: UUID?): Flow<Transparencies> {
        if (id == null) return flowOf(default)
        return get(id).map { it ?: default }
    }

    // Fork additions (Phase 2 config reload, ADR 0003): awaited variants for
    // config convergence. Each returns only after the database read/write has
    // completed, so results are visible immediately afterwards.

    /**
     * Awaited variant of [get]: resolves built-in themes by [id], otherwise reads
     * the current database row once and returns it (or null).
     */
    suspend fun getOnce(id: UUID): Transparencies? {
        if (id == DefaultThemeId) return default
        if (id == SemiTransparentId) return semiTransparent
        return database.themeDao().getTransparencies(id).firstOrNull()
            ?.let { Transparencies(it) }
    }

    /**
     * Finds a [Transparencies] by its (localized) [name]. User-created themes
     * take precedence over built-in themes with the same name, so a
     * config-managed scheme derived from a built-in stays resolvable (and the
     * config convergence loop stays idempotent). Returns null if no theme
     * with that name exists.
     */
    suspend fun findByName(name: String): Transparencies? {
        database.themeDao().getAllTransparencies().firstOrNull()
            ?.firstOrNull { it.name == name }
            ?.let { return Transparencies(it) }
        return getBuiltIn().firstOrNull { it.name == name }
    }

    /**
     * Awaited create-or-update: inserts [transparencies] if no row with its id
     * exists, otherwise updates the existing row. Returns after the transaction
     * has committed.
     */
    suspend fun upsert(transparencies: Transparencies) {
        val dao = database.themeDao()
        val entity = transparencies.toEntity()
        database.withTransaction {
            if (dao.getTransparencies(entity.id).firstOrNull() != null) {
                dao.updateTransparencies(entity)
            } else {
                dao.insertTransparencies(entity)
            }
        }
    }

    private fun getBuiltIn(): List<Transparencies> {
        return listOf(
            default,
            semiTransparent,
        )
    }

    private val default: Transparencies
        get() = Transparencies(
            id = DefaultThemeId,
            builtIn = true,
            name = context.getString(R.string.preference_transparencies_default),
            background = 0.85f,
            surface = 1f,
            elevatedSurface = 1f,
        )


    private val semiTransparent: Transparencies
        get() = Transparencies(
            id = SemiTransparentId,
            builtIn = true,
            name = context.getString(R.string.preference_transparencies_semi_transparent),
            background = 0.40f,
            surface = 0.65f,
            elevatedSurface = 0.85f,
        )

}