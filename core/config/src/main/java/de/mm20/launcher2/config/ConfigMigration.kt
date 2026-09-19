package de.mm20.launcher2.config

import kotlinx.serialization.json.JsonObject

interface ConfigMigration {
    val fromVersion: Int
    val toVersion: Int
    fun migrate(document: JsonObject): JsonObject
}

object ConfigMigrations {
    private val migrations: List<ConfigMigration> = emptyList()

    val currentSchemaVersion: Int = 1

    fun canMigrate(version: Int): Boolean {
        if (version > currentSchemaVersion) return false
        var v = version
        while (v < currentSchemaVersion) {
            val step = migrations.firstOrNull { it.fromVersion == v } ?: return false
            v = step.toVersion
        }
        return true
    }

    fun migrate(version: Int, document: JsonObject): JsonObject {
        require(canMigrate(version)) { "No migration path from schema version $version" }
        var v = version
        var doc = document
        while (v < currentSchemaVersion) {
            val step = migrations.first { it.fromVersion == v }
            doc = step.migrate(doc)
            v = step.toVersion
        }
        return doc
    }
}
