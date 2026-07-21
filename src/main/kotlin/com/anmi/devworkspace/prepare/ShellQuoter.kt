package com.anmi.devworkspace.prepare

interface ShellQuoter {
    fun quote(argument: String): String
}

object PowerShellQuoter : ShellQuoter {
    override fun quote(argument: String): String = "'${argument.replace("'", "''")}'"
}

object CmdQuoter : ShellQuoter {
    override fun quote(argument: String): String = buildString {
        append('"')
        var backslashes = 0
        argument.forEach { character ->
            when (character) {
                '\\' -> backslashes++
                '"' -> {
                    repeat(backslashes * 2 + 1) { append('\\') }
                    append('"')
                    backslashes = 0
                }

                else -> {
                    repeat(backslashes) { append('\\') }
                    backslashes = 0
                    append(character)
                }
            }
        }
        repeat(backslashes * 2) { append('\\') }
        append('"')
    }
}

object PosixShellQuoter : ShellQuoter {
    override fun quote(argument: String): String = "'${argument.replace("'", "'\"'\"'")}'"
}
