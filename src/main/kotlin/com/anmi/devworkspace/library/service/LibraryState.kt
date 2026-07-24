package com.anmi.devworkspace.library.service

import com.anmi.devworkspace.library.domain.LibraryGroup
import com.anmi.devworkspace.library.domain.LibraryItem
import com.anmi.devworkspace.library.domain.LibraryScope
import com.anmi.devworkspace.library.storage.LibraryLoadError
import com.anmi.devworkspace.library.storage.LibrarySnapshot
import java.util.Collections

/**
 * Unified view of all repository layers.
 *
 * Records are concatenated without ID or title overrides. Groups with
 * normalized matching names remain distinct here; visual aggregation belongs
 * exclusively to presentation code.
 */
data class LibraryState(
    val groups: List<LibraryGroup> = Collections.unmodifiableList(ArrayList()),
    val items: List<LibraryItem> = Collections.unmodifiableList(ArrayList()),
    val errors: Map<LibraryScope, LibraryLoadError> =
        Collections.unmodifiableMap(LinkedHashMap()),
    val snapshots: Map<LibraryScope, LibrarySnapshot> =
        Collections.unmodifiableMap(LinkedHashMap()),
)
