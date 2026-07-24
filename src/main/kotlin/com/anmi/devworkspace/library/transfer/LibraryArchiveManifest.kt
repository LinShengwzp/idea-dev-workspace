package com.anmi.devworkspace.library.transfer

import com.anmi.devworkspace.library.domain.LibraryScope
import java.time.Instant
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/** Versioned metadata describing the source of a library archive. */
data class LibraryArchiveManifest(
    val formatVersion: Int,
    val exportedAt: Instant,
    val sourceScope: LibraryScope,
) {
    companion object {
        const val CURRENT_VERSION: Int = 1
    }
}

internal object LibraryArchiveManifestCodec {
    private val json = Json { prettyPrint = true }

    fun encode(manifest: LibraryArchiveManifest): String =
        json.encodeToString(
            kotlinx.serialization.json.JsonElement.serializer(),
            buildJsonObject {
                put("formatVersion", manifest.formatVersion)
                put("exportedAt", manifest.exportedAt.toString())
                put("sourceScope", manifest.sourceScope.name)
            },
        )

    fun decode(content: String): LibraryArchiveManifest {
        val root = try {
            json.parseToJsonElement(content).jsonObject
        } catch (exception: RuntimeException) {
            throw IllegalArgumentException("Invalid library archive manifest", exception)
        }
        val version = root["formatVersion"]?.jsonPrimitive?.int
            ?: throw IllegalArgumentException("Archive manifest is missing formatVersion")
        require(version == LibraryArchiveManifest.CURRENT_VERSION) {
            "Unsupported library archive version: $version"
        }
        val exportedAt = try {
            Instant.parse(root["exportedAt"]?.jsonPrimitive?.content)
        } catch (exception: RuntimeException) {
            throw IllegalArgumentException("Archive manifest has invalid exportedAt", exception)
        }
        val scope = try {
            LibraryScope.valueOf(
                root["sourceScope"]?.jsonPrimitive?.content
                    ?: throw IllegalArgumentException("Archive manifest is missing sourceScope"),
            )
        } catch (exception: RuntimeException) {
            throw IllegalArgumentException("Archive manifest has invalid sourceScope", exception)
        }
        return LibraryArchiveManifest(version, exportedAt, scope)
    }
}
