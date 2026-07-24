package com.anmi.devworkspace.library.ui

import com.intellij.testFramework.fixtures.BasePlatformTestCase

class LibraryPanelPlatformTest : BasePlatformTestCase() {
    fun testPanelInitializationDoesNotThrow() {
        val panel = LibraryPanel(project)
        try {
            assertNotNull(panel.toolbar)
            assertNotNull(panel.content)
        } finally {
            panel.dispose()
        }
    }
}
