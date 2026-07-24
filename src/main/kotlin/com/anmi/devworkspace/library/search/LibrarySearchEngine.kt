package com.anmi.devworkspace.library.search

import java.util.Locale

/** Pure, stateless filtering and sorting for search-ready library records. */
class LibrarySearchEngine {
    fun search(
        records: Collection<LibrarySearchRecord>,
        query: LibraryQuery,
    ): List<LibrarySearchRecord> {
        val normalizedText = query.text.trim().lowercase(Locale.ROOT)
        val normalizedTags = query.tags.mapTo(hashSetOf()) { it.lowercase(Locale.ROOT) }
        val normalizedGroups = query.groupNames.mapTo(hashSetOf()) {
            it.trim().lowercase(Locale.ROOT)
        }
        return records.asSequence()
            .filter { record -> normalizedText.isEmpty() || record.contains(normalizedText) }
            .filter { record -> query.scopes.isEmpty() || record.item.scope in query.scopes }
            .filter { record -> query.types.isEmpty() || record.item.type in query.types }
            .filter { record ->
                val groupFilterActive =
                    query.groupIds.isNotEmpty() || normalizedGroups.isNotEmpty() || query.includeUngrouped
                !groupFilterActive ||
                    record.item.groupId in query.groupIds ||
                    record.groupName?.trim()?.lowercase(Locale.ROOT) in normalizedGroups ||
                    query.includeUngrouped && record.item.groupId == null
            }
            .filter { record ->
                normalizedTags.isEmpty() ||
                    record.item.tags.any { it.lowercase(Locale.ROOT) in normalizedTags }
            }
            .filter { record -> !query.favoriteOnly || record.item.favorite }
            .filter { record -> query.pathStates.isEmpty() || record.pathState in query.pathStates }
            .sortedWith(comparator(query.sort))
            .toList()
    }

    private fun LibrarySearchRecord.contains(needle: String): Boolean =
        sequenceOf(
            item.title,
            markdownBody,
            item.target,
            item.note,
            groupName,
        ).filterNotNull().any { needle in it.lowercase(Locale.ROOT) } ||
            item.tags.any { needle in it.lowercase(Locale.ROOT) }

    private fun comparator(sort: LibrarySort): Comparator<LibrarySearchRecord> {
        val identity = compareBy<LibrarySearchRecord> { it.item.id }
            .thenBy { it.item.scope.ordinal }
        return when (sort) {
            LibrarySort.DEFAULT -> compareByDescending<LibrarySearchRecord> { it.item.favorite }
                .thenBy { it.groupOrder }
                .thenByDescending { it.item.updatedAt }
                .then(identity)
            LibrarySort.TITLE -> compareBy<LibrarySearchRecord> {
                it.item.title.lowercase(Locale.ROOT)
            }.then(identity)
            LibrarySort.CREATED_AT -> compareByDescending<LibrarySearchRecord> { it.item.createdAt }
                .then(identity)
            LibrarySort.UPDATED_AT -> compareByDescending<LibrarySearchRecord> { it.item.updatedAt }
                .then(identity)
            LibrarySort.TYPE -> compareBy<LibrarySearchRecord> { it.item.type.ordinal }
                .then(identity)
        }
    }
}
