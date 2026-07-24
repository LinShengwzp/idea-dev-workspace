package com.anmi.devworkspace.library.ui

import com.intellij.openapi.components.Service
import java.lang.ref.WeakReference

/** Bridges Action System entry points to the live tool-window UI without owning its lifecycle. */
@Service(Service.Level.PROJECT)
class LibraryUiController {
    private var panelReference = WeakReference<LibraryPanel>(null)

    fun attach(panel: LibraryPanel) {
        panelReference = WeakReference(panel)
    }

    fun detach(panel: LibraryPanel) {
        if (panelReference.get() === panel) panelReference.clear()
    }

    fun focusSearch() {
        panelReference.get()?.focusSearch()
    }
}
