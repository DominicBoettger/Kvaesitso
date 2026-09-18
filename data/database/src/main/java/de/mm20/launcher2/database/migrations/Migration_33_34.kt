package de.mm20.launcher2.database.migrations

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * The `Theme` table (ColorsEntity, added in v28, "shape schemes") was never
 * created by any migration — only by Room's onCreate on fresh installs.
 * Upgrades from <= v33 therefore had no `Theme` table, which breaks the
 * custom color schemes feature at runtime ("no such table: Theme").
 * Found by MigrationTest. `IF NOT EXISTS` keeps this a no-op wherever the
 * table already exists.
 *
 * Also drops the stale `Plugin` table: created by Migration_12_13, superseded
 * by `Plugins` (Migration_25_26), but never dropped.
 */
class Migration_33_34 : Migration(33, 34) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("DROP TABLE IF EXISTS `Plugin`")
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `Theme` (`id` BLOB NOT NULL, `name` TEXT NOT NULL, " +
                    "`corePaletteA1` INTEGER, `corePaletteA2` INTEGER, `corePaletteA3` INTEGER, " +
                    "`corePaletteN1` INTEGER, `corePaletteN2` INTEGER, `corePaletteE` INTEGER, " +
                    "`lightPrimary` TEXT, `lightOnPrimary` TEXT, `lightPrimaryContainer` TEXT, " +
                    "`lightOnPrimaryContainer` TEXT, `lightSecondary` TEXT, `lightOnSecondary` TEXT, " +
                    "`lightSecondaryContainer` TEXT, `lightOnSecondaryContainer` TEXT, " +
                    "`lightTertiary` TEXT, `lightOnTertiary` TEXT, `lightTertiaryContainer` TEXT, " +
                    "`lightOnTertiaryContainer` TEXT, `lightError` TEXT, `lightOnError` TEXT, " +
                    "`lightErrorContainer` TEXT, `lightOnErrorContainer` TEXT, `lightSurface` TEXT, " +
                    "`lightOnSurface` TEXT, `lightOnSurfaceVariant` TEXT, `lightOutline` TEXT, " +
                    "`lightOutlineVariant` TEXT, `lightInverseSurface` TEXT, `lightInverseOnSurface` TEXT, " +
                    "`lightInversePrimary` TEXT, `lightSurfaceDim` TEXT, `lightSurfaceBright` TEXT, " +
                    "`lightSurfaceContainerLowest` TEXT, `lightSurfaceContainerLow` TEXT, " +
                    "`lightSurfaceContainer` TEXT, `lightSurfaceContainerHigh` TEXT, " +
                    "`lightSurfaceContainerHighest` TEXT, `lightBackground` TEXT, " +
                    "`lightOnBackground` TEXT, `lightSurfaceTint` TEXT, `lightScrim` TEXT, " +
                    "`lightSurfaceVariant` TEXT, `darkPrimary` TEXT, `darkOnPrimary` TEXT, " +
                    "`darkPrimaryContainer` TEXT, `darkOnPrimaryContainer` TEXT, `darkSecondary` TEXT, " +
                    "`darkOnSecondary` TEXT, `darkSecondaryContainer` TEXT, " +
                    "`darkOnSecondaryContainer` TEXT, `darkTertiary` TEXT, `darkOnTertiary` TEXT, " +
                    "`darkTertiaryContainer` TEXT, `darkOnTertiaryContainer` TEXT, `darkError` TEXT, " +
                    "`darkOnError` TEXT, `darkErrorContainer` TEXT, `darkOnErrorContainer` TEXT, " +
                    "`darkSurface` TEXT, `darkOnSurface` TEXT, `darkOnSurfaceVariant` TEXT, " +
                    "`darkOutline` TEXT, `darkOutlineVariant` TEXT, `darkInverseSurface` TEXT, " +
                    "`darkInverseOnSurface` TEXT, `darkInversePrimary` TEXT, `darkSurfaceDim` TEXT, " +
                    "`darkSurfaceBright` TEXT, `darkSurfaceContainerLowest` TEXT, " +
                    "`darkSurfaceContainerLow` TEXT, `darkSurfaceContainer` TEXT, " +
                    "`darkSurfaceContainerHigh` TEXT, `darkSurfaceContainerHighest` TEXT, " +
                    "`darkBackground` TEXT, `darkOnBackground` TEXT, `darkSurfaceTint` TEXT, " +
                    "`darkScrim` TEXT, `darkSurfaceVariant` TEXT, PRIMARY KEY(`id`))"
        )
    }
}
