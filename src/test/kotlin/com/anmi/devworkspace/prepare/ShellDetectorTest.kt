package com.anmi.devworkspace.prepare

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ShellDetectorTest {
    @Test
    fun `explicit interpreter wins over shebang and extension`() {
        val result = ShellDetector.detect(
            context(
                operatingSystem = OperatingSystem.WINDOWS,
                explicitInterpreter = "pwsh",
                fileName = "task.cmd",
                firstLine = "#!/usr/bin/env bash",
            ),
        )

        assertEquals(ShellType.POWERSHELL, result.getOrThrow())
    }

    @Test
    fun `shebang wins over extension`() {
        val result = ShellDetector.detect(
            context(
                operatingSystem = OperatingSystem.LINUX,
                fileName = "task.cmd",
                firstLine = "#!/usr/bin/env bash",
            ),
        )

        assertEquals(ShellType.BASH, result.getOrThrow())
    }

    @Test
    fun `known extensions map to platform shells`() {
        assertEquals(
            ShellType.POWERSHELL,
            ShellDetector.detect(context(OperatingSystem.WINDOWS, fileName = "task.ps1")).getOrThrow(),
        )
        assertEquals(
            ShellType.CMD,
            ShellDetector.detect(context(OperatingSystem.WINDOWS, fileName = "task.BAT")).getOrThrow(),
        )
    }

    @Test
    fun `shell script on Windows requires explicit interpreter`() {
        assertTrue(
            ShellDetector.detect(context(OperatingSystem.WINDOWS, fileName = "task.sh")).isFailure,
        )
    }

    @Test
    fun `extensionless file without shebang executes directly`() {
        assertEquals(
            ShellType.DIRECT,
            ShellDetector.detect(context(OperatingSystem.LINUX, fileName = "gradlew")).getOrThrow(),
        )
    }

    @Test
    fun `unix inline command uses shell environment then POSIX fallback`() {
        assertEquals(
            ShellType.ZSH,
            ShellDetector.detect(context(OperatingSystem.MAC, shellEnvironment = "/bin/zsh")).getOrThrow(),
        )
        assertEquals(
            ShellType.POSIX_SH,
            ShellDetector.detect(context(OperatingSystem.LINUX)).getOrThrow(),
        )
    }

    private fun context(
        operatingSystem: OperatingSystem,
        explicitInterpreter: String? = null,
        fileName: String? = null,
        firstLine: String? = null,
        shellEnvironment: String? = null,
    ) = ShellDetectionContext(
        operatingSystem = operatingSystem,
        explicitInterpreter = explicitInterpreter,
        fileName = fileName,
        firstLine = firstLine,
        shellEnvironment = shellEnvironment,
    )
}
