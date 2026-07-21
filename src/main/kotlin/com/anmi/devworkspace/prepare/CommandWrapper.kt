package com.anmi.devworkspace.prepare

data class WrappedCommand(
    val text: String,
    val beginMarker: String,
    val exitMarkerPrefix: String,
)

object CommandWrapper {
    fun wrap(
        shellType: ShellType,
        command: String,
        executionId: String,
    ): WrappedCommand {
        require(EXECUTION_ID.matches(executionId)) { "Execution id contains unsupported characters" }
        val beginMarker = "__DEV_TASK_BEGIN__:$executionId"
        val exitMarkerPrefix = "__DEV_TASK_EXIT__:$executionId:"
        val text = when (shellType) {
            ShellType.POWERSHELL -> powerShell(command, beginMarker, exitMarkerPrefix)
            ShellType.CMD -> cmd(command, beginMarker, exitMarkerPrefix)
            ShellType.BASH, ShellType.ZSH, ShellType.POSIX_SH -> posix(command, beginMarker, exitMarkerPrefix)
            ShellType.DIRECT -> throw IllegalArgumentException("Direct execution cannot be marker wrapped")
        }
        return WrappedCommand(text, beginMarker, exitMarkerPrefix)
    }

    private fun powerShell(command: String, beginMarker: String, exitMarkerPrefix: String): String = buildString {
        appendLine("Write-Output '$beginMarker'")
        appendLine(command)
        appendLine("${'$'}__devTaskExitCode = if (${'$'}null -eq ${'$'}LASTEXITCODE) { 0 } else { ${'$'}LASTEXITCODE }")
        append("Write-Output ('$exitMarkerPrefix' + ${'$'}__devTaskExitCode)")
    }

    private fun cmd(command: String, beginMarker: String, exitMarkerPrefix: String): String = listOf(
        "echo $beginMarker",
        command,
        "set \"__DEV_TASK_EXIT_CODE=%ERRORLEVEL%\"",
        "echo $exitMarkerPrefix%__DEV_TASK_EXIT_CODE%",
    ).joinToString("\r\n")

    private fun posix(command: String, beginMarker: String, exitMarkerPrefix: String): String = buildString {
        appendLine("printf '%s\\n' '$beginMarker'")
        appendLine(command)
        appendLine("__dev_task_exit_code=${'$'}?")
        append("printf '%s%s\\n' '$exitMarkerPrefix' \"${'$'}__dev_task_exit_code\"")
    }

    private val EXECUTION_ID = Regex("[A-Za-z0-9._-]+")
}
