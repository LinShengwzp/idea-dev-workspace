package com.anmi.devworkspace.prepare

import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class VariableResolverTest {
    private val context = VariableContext(
        projectDir = Path.of("C:/work/app"),
        userHome = Path.of("C:/Users/me"),
        moduleDir = null,
        environment = mapOf("JAVA_HOME" to "C:/Java"),
    )

    @Test
    fun `resolves project directory and environment variables`() {
        assertEquals(
            "C:/work/app/scripts",
            VariableResolver.resolveText("${'$'}{PROJECT_DIR}/scripts", context).getOrThrow(),
        )
        assertEquals(
            "C:/Java/bin",
            VariableResolver.resolveText("${'$'}{ENV:JAVA_HOME}/bin", context).getOrThrow(),
        )
    }

    @Test
    fun `missing module directory fails`() {
        assertTrue(VariableResolver.resolveText("${'$'}{MODULE_DIR}", context).isFailure)
    }

    @Test
    fun `unknown variable fails`() {
        assertTrue(VariableResolver.resolveText("${'$'}{WHATEVER}", context).isFailure)
    }

    @Test
    fun `secret reference is rejected without including its key in the error`() {
        val result = VariableResolver.resolveText("${'$'}{SECRET:private-token}", context)

        assertTrue(result.isFailure)
        assertTrue("private-token" !in (result.exceptionOrNull()?.message.orEmpty()))
    }
}
