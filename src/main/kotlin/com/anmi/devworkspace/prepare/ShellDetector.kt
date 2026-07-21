package com.anmi.devworkspace.prepare

enum class ShellType { POWERSHELL, CMD, BASH, ZSH, POSIX_SH, DIRECT }

enum class OperatingSystem { WINDOWS, MAC, LINUX }

data class ShellDetectionContext(
    val operatingSystem: OperatingSystem,
    val explicitInterpreter: String?,
    val fileName: String?,
    val firstLine: String?,
    val shellEnvironment: String?,
)

object ShellDetector {
    fun detect(context: ShellDetectionContext): Result<ShellType> = runCatching {
        context.explicitInterpreter?.let { interpreter ->
            return@runCatching shellType(interpreter)
                ?: error("The configured interpreter is not supported")
        }

        context.firstLine
            ?.takeIf { it.startsWith("#!") }
            ?.let(::shebangInterpreter)
            ?.let { interpreter ->
                return@runCatching shellType(interpreter)
                    ?: error("The script shebang interpreter is not supported")
            }

        val fileName = context.fileName?.replace('\\', '/')?.substringAfterLast('/')
        when (fileName?.substringAfterLast('.', missingDelimiterValue = "")?.lowercase()) {
            "ps1" -> return@runCatching ShellType.POWERSHELL
            "bat", "cmd" -> return@runCatching ShellType.CMD
            "sh" -> {
                if (context.operatingSystem == OperatingSystem.WINDOWS) {
                    error("Shell scripts on Windows require an explicit interpreter")
                }
                return@runCatching ShellType.POSIX_SH
            }
        }

        if (fileName != null && '.' !in fileName) return@runCatching ShellType.DIRECT

        context.shellEnvironment?.let(::shellType)?.let { return@runCatching it }
        when (context.operatingSystem) {
            OperatingSystem.WINDOWS -> ShellType.POWERSHELL
            OperatingSystem.MAC, OperatingSystem.LINUX -> ShellType.POSIX_SH
        }
    }

    private fun shebangInterpreter(firstLine: String): String? {
        val parts = firstLine.removePrefix("#!").trim().split(Regex("\\s+")).filter(String::isNotEmpty)
        if (parts.isEmpty()) return null
        val executable = parts.first().fileName()
        return if (executable == "env") parts.drop(1).firstOrNull { !it.startsWith("-") } else parts.first()
    }

    private fun shellType(interpreter: String): ShellType? = when (
        interpreter.trim().trim('"', '\'').fileName().removeSuffix(".exe").lowercase()
    ) {
        "pwsh", "powershell" -> ShellType.POWERSHELL
        "cmd" -> ShellType.CMD
        "bash" -> ShellType.BASH
        "zsh" -> ShellType.ZSH
        "sh", "dash", "ash" -> ShellType.POSIX_SH
        else -> null
    }

    private fun String.fileName(): String = replace('\\', '/').substringAfterLast('/')
}
