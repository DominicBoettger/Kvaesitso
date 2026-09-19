package de.mm20.launcher2.searchable

import android.content.Context
import android.os.Bundle
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import de.mm20.launcher2.database.AppDatabase
import de.mm20.launcher2.database.entities.SavedSearchableEntity
import de.mm20.launcher2.icons.StaticLauncherIcon
import de.mm20.launcher2.search.SavableSearchable
import de.mm20.launcher2.search.SearchableSerializer
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Characterization tests for the existing fire-and-forget favorites API plus
 * tests for the fork's awaited [SavableSearchableRepository.updateFavoritesAwaited].
 *
 * The repository's deserialization path needs Koin, so read-backs go through the
 * DAO directly; this also asserts on entity columns (pinPosition, launchCount,
 * weight) that the repository's flows do not expose.
 */
@RunWith(RobolectricTestRunner::class)
class SavableSearchableRepositoryTest {

    private lateinit var database: AppDatabase
    private lateinit var repository: SavableSearchableRepositoryImpl

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        repository = SavableSearchableRepositoryImpl(database, null)
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

    private fun pinnedKeys(limit: Int = 10) = database.searchableDao().getKeys(
        manuallySorted = true,
        automaticallySorted = true,
        frequentlyUsed = false,
        unused = false,
        minVisibility = VisibilityLevel.Hidden.value,
        maxVisibility = VisibilityLevel.Default.value,
        limit = limit,
    )

    // ----- characterization of the existing async API -----

    @Test
    fun updateFavorites_assignsDescendingPinPositionsInListOrder() = runBlocking {
        repository.updateFavorites(
            manuallySorted = listOf(TestSearchable("a"), TestSearchable("b")),
            automaticallySorted = listOf(TestSearchable("c")),
        )
        awaitValue { database.searchableDao().getByKey("a").firstOrNull() }
        assertEquals(3, database.searchableDao().getByKey("a").first()!!.pinPosition)
        assertEquals(2, database.searchableDao().getByKey("b").first()!!.pinPosition)
        assertEquals(1, database.searchableDao().getByKey("c").first()!!.pinPosition)
    }

    @Test
    fun updateFavorites_ordersPinnedKeysManuallySortedFirst() = runBlocking {
        repository.updateFavorites(
            manuallySorted = listOf(TestSearchable("a"), TestSearchable("b")),
            automaticallySorted = listOf(TestSearchable("c")),
        )
        val keys = awaitValue {
            pinnedKeys().firstOrNull()?.takeIf { it.size == 3 }
        }
        assertEquals(listOf("a", "b", "c"), keys)
    }

    @Test
    fun updateFavorites_unpinsPreviouslyPinnedItems() = runBlocking {
        repository.updateFavorites(
            manuallySorted = listOf(TestSearchable("a")),
            automaticallySorted = emptyList(),
        )
        awaitValue {
            database.searchableDao().getByKey("a").firstOrNull()
                ?.takeIf { it.pinPosition > 0 }
        }

        repository.updateFavorites(emptyList(), emptyList())
        val entity = awaitValue {
            database.searchableDao().getByKey("a").firstOrNull()
                ?.takeIf { it.pinPosition == 0 }
        }
        assertEquals(0, entity.pinPosition)
    }

    @Test
    fun upsert_preservesExistingValuesWhenParametersAreNull() = runBlocking {
        val item = TestSearchable("a")
        repository.insert(item)
        awaitValue { database.searchableDao().getByKey("a").firstOrNull() }

        repository.upsert(
            item,
            visibility = VisibilityLevel.Hidden,
            pinned = true,
            launchCount = 5,
            weight = 0.5,
        )
        awaitValue {
            database.searchableDao().getByKey("a").firstOrNull()
                ?.takeIf { it.launchCount == 5 }
        }

        item.serialized = "changed"
        repository.upsert(item)
        val entity = awaitValue {
            database.searchableDao().getByKey("a").firstOrNull()
                ?.takeIf { it.serializedSearchable == "changed" }
        }
        assertEquals(VisibilityLevel.Hidden.value, entity.visibility)
        assertEquals(5, entity.launchCount)
        assertEquals(0.5, entity.weight, 0.0)
        assertEquals(1, entity.pinPosition)
    }

    // ----- awaited fork API -----

    @Test
    fun updateFavoritesAwaited_writesAreVisibleImmediatelyAfterReturn() = runBlocking {
        repository.updateFavoritesAwaited(
            manuallySorted = listOf(TestSearchable("a"), TestSearchable("b")),
            automaticallySorted = listOf(TestSearchable("c")),
        )
        // No polling: the transaction must have committed when the call returns.
        assertEquals(listOf("a", "b", "c"), pinnedKeys().first())
    }

    @Test
    fun updateFavoritesAwaited_unpinsItemsMissingFromNewList() = runBlocking {
        repository.updateFavoritesAwaited(
            manuallySorted = listOf(TestSearchable("a")),
            automaticallySorted = emptyList(),
        )
        repository.updateFavoritesAwaited(
            manuallySorted = listOf(TestSearchable("b")),
            automaticallySorted = emptyList(),
        )
        assertEquals(0, database.searchableDao().getByKey("a").first()!!.pinPosition)
        assertEquals(2, database.searchableDao().getByKey("b").first()!!.pinPosition)
    }

    @Test
    fun updateFavoritesAwaited_preservesLaunchCountWeightAndVisibility() = runBlocking {
        database.searchableDao().upsert(
            SavedSearchableEntity(
                key = "a",
                type = "test",
                serializedSearchable = "a",
                launchCount = 7,
                pinPosition = 0,
                visibility = VisibilityLevel.SearchOnly.value,
                weight = 0.9,
            )
        )
        repository.updateFavoritesAwaited(
            manuallySorted = listOf(TestSearchable("a")),
            automaticallySorted = emptyList(),
        )
        val entity = database.searchableDao().getByKey("a").first()!!
        assertEquals(7, entity.launchCount)
        assertEquals(0.9, entity.weight, 0.0)
        assertEquals(VisibilityLevel.SearchOnly.value, entity.visibility)
        assertTrue(entity.pinPosition > 1)
    }

    @Test
    fun updateFavoritesAwaited_withEmptyListsClearsAllPins() = runBlocking {
        repository.updateFavoritesAwaited(
            manuallySorted = listOf(TestSearchable("a")),
            automaticallySorted = listOf(TestSearchable("b")),
        )
        repository.updateFavoritesAwaited(emptyList(), emptyList())
        assertEquals(emptyList<String>(), pinnedKeys().first())
    }

    private class TestSearchable(
        override val key: String,
        var serialized: String = key,
    ) : SavableSearchable {
        override val domain: String = "test"
        override val label: String = key
        override val preferDetailsOverLaunch: Boolean = false

        override fun overrideLabel(label: String): SavableSearchable = this
        override fun launch(context: Context, options: Bundle?): Boolean = false
        override fun getPlaceholderIcon(context: Context): StaticLauncherIcon =
            throw NotImplementedError()

        override fun getSerializer(): SearchableSerializer = object : SearchableSerializer {
            override val typePrefix: String = "test"
            override fun serialize(searchable: SavableSearchable): String =
                (searchable as TestSearchable).serialized
        }
    }
}
