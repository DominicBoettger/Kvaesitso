package de.mm20.launcher2.config

import kotlinx.serialization.Serializable

@Serializable
enum class Severity {
    @kotlinx.serialization.SerialName("warning")
    Warning,

    @kotlinx.serialization.SerialName("error")
    Error,
}

@Serializable
data class Diagnostic(
    val severity: Severity,
    val code: String,
    val path: String,
    val message: String,
)

data class ConfigParseResult(
    val config: LauncherConfig?,
    val diagnostics: List<Diagnostic>,
) {
    val isSuccess: Boolean
        get() = config != null && diagnostics.none { it.severity == Severity.Error }
}
