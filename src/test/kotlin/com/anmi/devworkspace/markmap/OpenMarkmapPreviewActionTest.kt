package com.anmi.devworkspace.markmap

import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.actionSystem.DataContext
import com.intellij.testFramework.TestActionEvent
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.testFramework.LightVirtualFile

class OpenMarkmapPreviewActionTest : BasePlatformTestCase() {
    fun testMmMdIsVisibleAndEnabled() {
        assertVisible("architecture.mm.md")
    }

    fun testMmMarkdownIsVisibleAndEnabled() {
        assertVisible("architecture.mm.markdown")
    }

    fun testOrdinaryMarkdownIsHiddenAndDisabled() {
        val presentation = updateFor("README.md")

        assertFalse(presentation.isVisible)
        assertFalse(presentation.isEnabled)
    }

    fun testConfiguredCustomSuffixIsVisibleImmediately() {
        var suffixes = listOf("mm.md")
        val action = OpenMarkmapPreviewAction(MarkmapSuffixMatcher { suffixes })

        assertFalse(updateFor("architecture.mind.md", action).isVisible)
        suffixes = listOf("mind.md")
        val presentation = updateFor("architecture.mind.md", action)
        assertTrue(presentation.isVisible)
        assertTrue(presentation.isEnabled)
    }

    fun testUpdateRunsOnBackgroundThread() {
        assertEquals(
            ActionUpdateThread.BGT,
            OpenMarkmapPreviewAction().actionUpdateThread,
        )
    }

    fun testRegisteredActionUsesChineseBundleText() {
        val action = kotlin.test.assertNotNull(
            ActionManager.getInstance().getAction(OpenMarkmapPreviewAction.ACTION_ID),
        )

        assertEquals("思维导图", action.templatePresentation.text)
        assertFalse(action.templatePresentation.text.orEmpty().startsWith("%"))
        assertFalse(action.templatePresentation.description.isNullOrBlank())
    }

    private fun assertVisible(fileName: String) {
        val presentation = updateFor(fileName)

        assertTrue(presentation.isVisible)
        assertTrue(presentation.isEnabled)
    }

    private fun updateFor(
        fileName: String,
        action: OpenMarkmapPreviewAction = OpenMarkmapPreviewAction(
            MarkmapSuffixMatcher { MarkmapSuffixMatcher.DEFAULT_SUFFIXES },
        ),
    ) = TestActionEvent.createTestEvent(
        action,
        DataContext { dataId ->
            if (CommonDataKeys.VIRTUAL_FILE.`is`(dataId)) {
                LightVirtualFile(fileName, "content must not be read")
            } else {
                null
            }
        },
    ).also(action::update).presentation
}
