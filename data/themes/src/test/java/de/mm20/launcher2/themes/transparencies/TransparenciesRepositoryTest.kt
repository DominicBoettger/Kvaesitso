package de.mm20.launcher2.themes.transparencies

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import de.mm20.launcher2.database.AppDatabase
import de.mm20.launcher2.themes.DefaultThemeId
import de.mm20.launcher2.themes.R
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.util.UUID

/**
 * Characterization tests for the existing fire-and-forget transparencies API
 * plus tests for the fork's awaited getOnce/findByName/upsert.
 */
@RunWith(RobolectricTestRunner::class)
class TransparenciesRepositoryTest {

    private lateinit var context: Context
    private lateinit var database: AppDatabase
    private lateinit var repository: TransparenciesRepository

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        database = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        repository = TransparenciesRepository(context, database)
    }

    @After
    fun tearDown() {
        database.close()
    }

    private suspend fun <T> awaitValue(
        timeoutMs: Long = 5000,
        block: suspend () -> T?,
    ): T {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (true) {
            block()?.let { return it }
            if (System.currentTimeMillis() > deadline) {
                fail("Timed out waiting for expected database state")
            }
            delay(20)
        }
    }

    private fun theme(name: String) = Transparencies(
        id = UUID.randomUUID(),
        name = name,
        background = 0.5f,
        surface = 0.6f,
        elevatedSurface = 0.7f,
    )

    // ----- characterization of the existing async API -----

    @Test
    fun create_isEventuallyVisible() = runBlocking {
        val t = theme("created")
        repository.create(t)
        val entity = awaitValue { database.themeDao().getTransparencies(t.id).firstOrNull() }
        assertEquals(t.name, entity.name)
        assertEquals(t.background, entity.background)
    }

    @Test
    fun update_modifiesExistingRow() = runBlocking {
        val t = theme("updated")
        repository.create(t)
        awaitValue { database.themeDao().getTransparencies(t.id).firstOrNull() }

        repository.update(t.copy(background = 0.1f))
        val entity = awaitValue {
            database.themeDao().getTransparencies(t.id).firstOrNull()
                ?.takeIf { it.background == 0.1f }
        }
        assertEquals(0.1f, entity.background)
    }

    @Test
    fun update_onMissingIdDoesNotInsert() = runBlocking {
        val missing = theme("missing")
        repository.update(missing)
        // Give the fire-and-forget coroutine ample time, then assert no row appeared.
        delay(500)
        assertNull(database.themeDao().getTransparencies(missing.id).firstOrNull())
    }

    @Test
    fun get_resolvesBuiltInDefaultWithoutDatabase() = runBlocking {
        val t = repository.get(DefaultThemeId).first()
        assertNotNull(t)
        assertTrue(t!!.builtIn)
        assertEquals(
            context.getString(R.string.preference_transparencies_default),
            t.name,
        )
    }

    // ----- awaited fork API -----

    @Test
    fun getOnce_returnsNullForUnknownId() = runBlocking {
        assertNull(repository.getOnce(UUID.randomUUID()))
    }

    @Test
    fun getOnce_resolvesBuiltInById() = runBlocking {
        val t = repository.getOnce(DefaultThemeId)
        assertNotNull(t)
        assertTrue(t!!.builtIn)
        assertEquals(DefaultThemeId, t.id)
    }

    @Test
    fun upsert_insertsNewThemeAndIsVisibleImmediately() = runBlocking {
        val t = theme("config theme")
        repository.upsert(t)
        // No polling: the write must have committed when upsert returns.
        assertEquals(t, repository.getOnce(t.id))
        assertEquals(t, repository.findByName("config theme"))
    }

    @Test
    fun upsert_updatesExistingTheme() = runBlocking {
        val t = theme("config theme")
        repository.upsert(t)
        val updated = t.copy(background = 0.2f, surface = 0.3f, elevatedSurface = 0.4f)
        repository.upsert(updated)
        assertEquals(updated, repository.getOnce(t.id))
    }

    @Test
    fun upsert_doesNotDuplicateRows() = runBlocking {
        val t = theme("config theme")
        repository.upsert(t)
        repository.upsert(t.copy(background = 0.2f))
        val all = database.themeDao().getAllTransparencies().first()
        assertEquals(1, all.count { it.id == t.id })
    }

    @Test
    fun findByName_returnsNullForUnknownName() = runBlocking {
        assertNull(repository.findByName("no such theme"))
    }

    @Test
    fun findByName_resolvesBuiltInByLocalizedName() = runBlocking {
        val t = repository.findByName(context.getString(R.string.preference_transparencies_default))
        assertNotNull(t)
        assertEquals(DefaultThemeId, t!!.id)
        assertTrue(t.builtIn)
    }
}
