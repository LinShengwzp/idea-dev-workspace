package com.anmi.devworkspace.prepare

import java.nio.file.Path

data class VariableContext(
    val projectDir: Path,
    val userHome: Path,
    val moduleDir: Path?,
    val environment: Map<String, String>,
)

object VariableResolver {
    private val token = Regex("""\$\{([^{}]+)}""")

    fun resolveText(value: String, context: VariableContext): Result<String> = runCatching {
        token.replace(value) { match ->
            when (val key = match.groupValues[1]) {
                "PROJECT_DIR" -> context.projectDir.portableString()
                "USER_HOME" -> context.userHome.portableString()
                "MODULE_DIR" -> context.moduleDir?.portableString()
                    ?: error("MODULE_DIR is unavailable for this task")

                else -> when {
                    key.startsWith("ENV:") -> {
                        val name = key.removePrefix("ENV:")
                        context.environment[name]
                            ?: error("Environment variable '$name' is not defined")
                    }

                    key.startsWith("SECRET:") ->
                        error("Secret references must be resolved by SecretStore")

                    else -> error("Unknown variable '${match.value}'")
                }
            }
        }
    }

    private fun Path.portableString(): String = toString().replace('\\', '/')
}
