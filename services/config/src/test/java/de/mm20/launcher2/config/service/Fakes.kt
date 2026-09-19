package de.mm20.launcher2.config.service

import de.mm20.launcher2.config.ConfigMutation
import de.mm20.launcher2.config.ConfigState
import de.mm20.launcher2.config.Diagnostic
import java.util.Collections

internal class FakeConfigStore(
    var state: ConfigState = ConfigState(),
    var applyDiagnostics: List<Diagnostic> = emptyList(),
) : ConfigStore {
    val events = Collections.synchronizedList(mutableListOf<String>())
    var applyCount = 0

    override suspend fun readState(): ConfigState {
        events += "read"
        return state
    }

    override suspend fun apply(mutations: List<ConfigMutation>): List<Diagnostic> {
        applyCount++
        events += "apply:${mutations.map { it.section }}"
        return applyDiagnostics
    }
}
