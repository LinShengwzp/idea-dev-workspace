package com.anmi.devworkspace.library.files

import com.anmi.devworkspace.library.domain.LibraryItem
import com.anmi.devworkspace.library.domain.LibraryItemType
import com.anmi.devworkspace.library.domain.LibraryScope
import com.anmi.devworkspace.library.search.LibraryPathState
import java.nio.file.Files
import java.nio.file.InvalidPathException
import java.nio.file.Path
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class LibraryItemKey(
    val scope: LibraryScope,
    val id: String,
)

/**
 * Performs current filesystem checks without mutating library entries.
 *
 * Callers use the same operation after loading, on manual refresh, and
 * immediately before opening; stale or missing paths remain persisted.
 */
class LibraryFileStatusService(
    private val resolver: LibraryPathResolver,
    private val exists: (Path) -> Boolean = Files::exists,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    suspend fun check(item: LibraryItem): LibraryPathState = withContext(ioDispatcher) {
        if (item.type == LibraryItemType.LINK || item.type == LibraryItemType.MARKDOWN) {
            return@withContext LibraryPathState.NOT_APPLICABLE
        }
        val target = item.target ?: return@withContext LibraryPathState.MISSING
        try {
            if (exists(resolver.resolve(target))) {
                LibraryPathState.AVAILABLE
            } else {
                LibraryPathState.MISSING
            }
        } catch (_: InvalidPathException) {
            LibraryPathState.MISSING
        } catch (_: IllegalArgumentException) {
            LibraryPathState.MISSING
        }
    }

    suspend fun refresh(items: Collection<LibraryItem>): Map<LibraryItemKey, LibraryPathState> =
        withContext(ioDispatcher) {
            items.associate { item ->
                LibraryItemKey(item.scope, item.id) to check(item)
            }
        }
}
