package com.anmi.devworkspace.library.actions

import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import kotlin.test.assertNotNull

class LibraryActionLocalizationTest : BasePlatformTestCase() {
    fun testRegisteredLibraryActionsUseChineseBundleLabels() {
        val expected = linkedMapOf(
            "DevWorkspace.OpenLibrary" to "打开资料库",
            "DevWorkspace.QuickAddLibrary" to "快速添加到资料库",
            "DevWorkspace.AddEditorContextToLibrary" to "添加当前文件到资料库",
            "DevWorkspace.AddProjectFilesToLibrary" to "添加到资料库",
        )

        expected.forEach { (actionId, label) ->
            val presentation = assertNotNull(
                ActionManager.getInstance().getAction(actionId),
                "Action $actionId must be registered",
            ).templatePresentation
            assertEquals(label, presentation.text)
            assertFalse(presentation.text.orEmpty().startsWith("%"))
            assertFalse(presentation.description.isNullOrBlank())
        }
    }
}
