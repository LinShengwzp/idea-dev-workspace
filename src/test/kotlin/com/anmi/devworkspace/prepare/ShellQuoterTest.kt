package com.anmi.devworkspace.prepare

import kotlin.test.Test
import kotlin.test.assertEquals

class ShellQuoterTest {
    @Test
    fun `PowerShell uses literal single quoted arguments`() {
        assertEquals("'two words'", PowerShellQuoter.quote("two words"))
        assertEquals("'say ''hi'''", PowerShellQuoter.quote("say 'hi'"))
        assertEquals("'a&b'", PowerShellQuoter.quote("a&b"))
        assertEquals("'${'$'}HOME'", PowerShellQuoter.quote("${'$'}HOME"))
        assertEquals("''", PowerShellQuoter.quote(""))
        assertEquals("'C:\\path\\'", PowerShellQuoter.quote("C:\\path\\"))
    }

    @Test
    fun `CMD uses Windows double quote escaping`() {
        assertEquals("\"two words\"", CmdQuoter.quote("two words"))
        assertEquals("\"say \\\"hi\\\"\"", CmdQuoter.quote("say \"hi\""))
        assertEquals("\"a&b\"", CmdQuoter.quote("a&b"))
        assertEquals("\"${'$'}HOME\"", CmdQuoter.quote("${'$'}HOME"))
        assertEquals("\"\"", CmdQuoter.quote(""))
        assertEquals("\"C:\\path\\\\\"", CmdQuoter.quote("C:\\path\\"))
    }

    @Test
    fun `POSIX shell uses single quotes and escapes embedded quote`() {
        assertEquals("'two words'", PosixShellQuoter.quote("two words"))
        assertEquals("'say '\"'\"'hi'", PosixShellQuoter.quote("say 'hi"))
        assertEquals("'a&b'", PosixShellQuoter.quote("a&b"))
        assertEquals("'${'$'}HOME'", PosixShellQuoter.quote("${'$'}HOME"))
        assertEquals("''", PosixShellQuoter.quote(""))
        assertEquals("'path\\'", PosixShellQuoter.quote("path\\"))
    }
}
