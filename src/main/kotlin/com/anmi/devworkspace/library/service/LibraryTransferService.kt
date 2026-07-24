package com.anmi.devworkspace.library.service

import com.anmi.devworkspace.library.domain.LibraryScope
import com.anmi.devworkspace.library.storage.MarkdownContentStore
import com.anmi.devworkspace.library.transfer.LibraryExportResult
import com.anmi.devworkspace.library.transfer.LibraryExporter
import com.anmi.devworkspace.library.transfer.LibraryImportResult
import com.anmi.devworkspace.library.transfer.LibraryImporter
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import java.nio.file.Path

/** Project adapter for archive transactions; ZIP and disk work remains inside suspend IO boundaries. */
@Service(Service.Level.PROJECT)
class LibraryTransferService(project: Project) {
    private val library: LibraryService = project.service()

    suspend fun export(scope: LibraryScope, output: Path): LibraryExportResult {
        val repository = library.repository(scope)
        return LibraryExporter(repository, MarkdownContentStore(repository.root)).export(output)
    }

    suspend fun importArchive(archive: Path, scope: LibraryScope): LibraryImportResult {
        val repositories = LibraryScope.entries.associateWith(library::repository)
        val result = LibraryImporter(repositories).importArchive(archive, scope)
        library.reload(scope)
        return result
    }
}
