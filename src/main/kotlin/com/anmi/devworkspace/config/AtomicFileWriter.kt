package com.anmi.devworkspace.config

import java.nio.charset.StandardCharsets
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption

class AtomicFileWriter(
    private val beforeReplace: (Path) -> Unit = {},
) {
    fun write(path: Path, content: String, validate: (String) -> Unit) {
        write(path, content, {}, validate)
    }

    fun write(
        path: Path,
        content: String,
        verifyBeforeReplace: () -> Unit,
        validate: (String) -> Unit,
    ) {
        validate(content)
        Files.createDirectories(path.parent)
        val temporary = Files.createTempFile(path.parent, ".${path.fileName}.", ".tmp")
        try {
            Files.writeString(temporary, content, StandardCharsets.UTF_8)
            beforeReplace(path)
            verifyBeforeReplace()
            try {
                Files.move(
                    temporary,
                    path,
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING,
                )
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(temporary, path, StandardCopyOption.REPLACE_EXISTING)
            }
        } finally {
            Files.deleteIfExists(temporary)
        }
    }
}
