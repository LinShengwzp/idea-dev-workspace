package com.anmi.devworkspace.library.open

import com.anmi.devworkspace.library.domain.LibraryItem
import com.anmi.devworkspace.library.domain.LibraryItemType
import com.anmi.devworkspace.library.search.LibraryPathState

enum class LibraryOpenPreference {
    DEFAULT,
    IDEA,
    SYSTEM,
}

enum class LibraryOpenAction {
    BROWSER,
    MARKDOWN_PREVIEW,
    IDEA_EDITOR,
    IMAGE_PREVIEW,
    SYSTEM,
    MISSING,
}

data class LibraryOpenDecision(val action: LibraryOpenAction)

/**
 * Pure routing policy. It decides what should open but performs no browser,
 * desktop, filesystem, or IntelliJ operation.
 */
class LibraryOpenDecisionEngine {
    fun decide(
        item: LibraryItem,
        pathState: LibraryPathState,
        ideaReadable: Boolean = false,
        preference: LibraryOpenPreference = LibraryOpenPreference.DEFAULT,
    ): LibraryOpenDecision {
        if (
            item.type in FILE_TYPES &&
            pathState != LibraryPathState.AVAILABLE
        ) {
            return LibraryOpenDecision(LibraryOpenAction.MISSING)
        }
        val action = when (item.type) {
            LibraryItemType.LINK -> LibraryOpenAction.BROWSER
            LibraryItemType.MARKDOWN -> LibraryOpenAction.MARKDOWN_PREVIEW
            LibraryItemType.FILE -> if (ideaReadable) {
                LibraryOpenAction.IDEA_EDITOR
            } else {
                LibraryOpenAction.SYSTEM
            }
            LibraryItemType.IMAGE -> when (preference) {
                LibraryOpenPreference.DEFAULT -> LibraryOpenAction.IMAGE_PREVIEW
                LibraryOpenPreference.IDEA -> LibraryOpenAction.IDEA_EDITOR
                LibraryOpenPreference.SYSTEM -> LibraryOpenAction.SYSTEM
            }
            LibraryItemType.MEDIA -> LibraryOpenAction.SYSTEM
        }
        return LibraryOpenDecision(action)
    }

    private companion object {
        val FILE_TYPES = setOf(LibraryItemType.FILE, LibraryItemType.IMAGE, LibraryItemType.MEDIA)
    }
}

/** Plugin-owned opening boundary that does not expose IntelliJ platform types. */
interface LibraryItemOpener {
    suspend fun open(
        item: LibraryItem,
        preference: LibraryOpenPreference = LibraryOpenPreference.DEFAULT,
    ): LibraryOpenDecision
}
