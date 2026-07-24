package com.anmi.devworkspace.library.storage

import com.anmi.devworkspace.library.domain.LibraryGroup
import com.anmi.devworkspace.library.domain.LibraryItem
import com.anmi.devworkspace.library.domain.LibraryItemSource
import com.anmi.devworkspace.library.domain.LibraryItemType
import com.anmi.devworkspace.library.domain.LibraryScope
import com.anmi.devworkspace.library.domain.LibrarySourceKind
import java.time.Instant
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * Reads legacy v1 and current v2 documents, but always emits v2.
 *
 * Repository scope is intentionally not serialized: a repository is the
 * authority for scope, which prevents imported JSON from claiming another
 * storage layer. The v1-to-v2 migration is in-memory only: item IDs and
 * Markdown body paths remain untouched. Collections and tags are sorted
 * before encoding so equal documents always produce equal text.
 */
class LibraryJsonCodec {
    private val json = Json {
        prettyPrint = true
    }

    fun encode(document: LibraryDocument): String {
        require(document.version == LibraryDocument.LEGACY_VERSION || document.version == LibraryDocument.CURRENT_VERSION) {
            "Unsupported library document version: ${document.version}"
        }
        val normalized = validateAndNormalize(document.copy(version = LibraryDocument.CURRENT_VERSION))
        return json.encodeToString(JsonElement.serializer(), normalized.toJson())
    }

    fun decode(content: String, scope: LibraryScope): LibraryDocument {
        val root = parseObject(content)
        val version = root.required("version").jsonPrimitive.int
        require(version == LibraryDocument.LEGACY_VERSION || version == LibraryDocument.CURRENT_VERSION) {
            "Unsupported library document version: $version"
        }

        val document = LibraryDocument(
            version = LibraryDocument.CURRENT_VERSION,
            groups = root.requiredArray("groups").map { it.jsonObject.toGroup(scope) },
            items = root.requiredArray("items").map { it.jsonObject.toItem(scope, version) },
        )
        return validateAndNormalize(document)
    }

    private fun validateAndNormalize(document: LibraryDocument): LibraryDocument {
        require(document.version == LibraryDocument.CURRENT_VERSION) {
            "Unsupported library document version: ${document.version}"
        }
        requireUniqueIds("group", document.groups.map(LibraryGroup::id))
        requireUniqueIds("item", document.items.map(LibraryItem::id))

        val groups = document.groups
            .onEach(::validateGroup)
            .sortedWith(compareBy(LibraryGroup::order, LibraryGroup::id))
        val items = document.items
            .map(::normalizeItem)
            .sortedBy(LibraryItem::id)
        return document.copy(groups = groups, items = items)
    }

    private fun validateGroup(group: LibraryGroup) {
        require(group.id.isNotBlank()) { "Library group ID must not be blank" }
        require(group.name.isNotBlank()) { "Library group name must not be blank" }
    }

    private fun normalizeItem(item: LibraryItem): LibraryItem {
        require(item.id.isNotBlank()) { "Library item ID must not be blank" }
        require(item.title.isNotBlank()) { "Library item title must not be blank" }
        if (item.type == LibraryItemType.MARKDOWN) {
            require(!item.contentFile.isNullOrBlank()) {
                "Markdown library item '${item.id}' requires contentFile"
            }
        } else {
            require(!item.target.isNullOrBlank()) {
                "${item.type} library item '${item.id}' requires target"
            }
        }

        val tags = item.tags
            .asSequence()
            .map(String::trim)
            .filter(String::isNotEmpty)
            .toSortedSet()
            .toCollection(linkedSetOf())
        return item.copy(tags = tags)
    }

    private fun requireUniqueIds(kind: String, ids: List<String>) {
        val duplicate = ids.groupingBy { it }.eachCount().entries.firstOrNull { it.value > 1 }
        require(duplicate == null) { "Duplicate library $kind ID: ${duplicate?.key}" }
    }

    private fun LibraryDocument.toJson(): JsonObject = buildJsonObject {
        put("version", version)
        put("groups", buildJsonArray { groups.forEach { add(it.toJson()) } })
        put("items", buildJsonArray { items.forEach { add(it.toJson()) } })
    }

    private fun LibraryGroup.toJson(): JsonObject = buildJsonObject {
        put("id", id)
        put("name", name)
        putNullable("description", description)
        put("order", order)
    }

    private fun LibraryItem.toJson(): JsonObject = buildJsonObject {
        put("id", id)
        put("title", title)
        put("type", type.name)
        putNullable("groupId", groupId)
        put("tags", buildJsonArray { tags.forEach { add(JsonPrimitive(it)) } })
        putNullable("note", note)
        put("favorite", favorite)
        putNullable("target", target)
        putNullable("contentFile", contentFile)
        put("createdAt", createdAt.toString())
        put("updatedAt", updatedAt.toString())
        put("source", source?.toJson() ?: JsonNull)
    }

    private fun LibraryItemSource.toJson(): JsonObject = buildJsonObject {
        put("kind", kind.name)
        put("path", path)
        putNullable("startLine", startLine)
        putNullable("endLine", endLine)
    }

    private fun JsonObject.toGroup(scope: LibraryScope): LibraryGroup = LibraryGroup(
        id = requiredString("id"),
        name = requiredString("name"),
        description = optionalString("description"),
        order = required("order").jsonPrimitive.int,
        scope = scope,
    )

    private fun JsonObject.toItem(scope: LibraryScope, version: Int): LibraryItem = LibraryItem(
        id = requiredString("id"),
        title = requiredString("title"),
        type = parseItemType(requiredString("type")),
        scope = scope,
        groupId = optionalString("groupId"),
        tags = requiredArray("tags")
            .map { it.jsonPrimitive.content }
            .toCollection(linkedSetOf()),
        note = optionalString("note"),
        favorite = required("favorite").jsonPrimitive.boolean,
        target = optionalString("target"),
        contentFile = optionalString("contentFile"),
        createdAt = parseInstant(requiredString("createdAt"), "createdAt"),
        updatedAt = parseInstant(requiredString("updatedAt"), "updatedAt"),
        source = if (version == LibraryDocument.CURRENT_VERSION) optionalSource() else null,
    )

    private fun JsonObject.optionalSource(): LibraryItemSource? {
        val value = get("source") ?: return null
        if (value is JsonNull) return null
        val source = value.jsonObject
        return LibraryItemSource(
            kind = parseSourceKind(source.requiredString("kind")),
            path = source.requiredString("path"),
            startLine = source.optionalInt("startLine"),
            endLine = source.optionalInt("endLine"),
        )
    }

    private fun parseObject(content: String): JsonObject =
        try {
            json.parseToJsonElement(content).jsonObject
        } catch (exception: RuntimeException) {
            throw IllegalArgumentException("Invalid library JSON structure", exception)
        }

    private fun parseItemType(value: String): LibraryItemType =
        try {
            LibraryItemType.valueOf(value)
        } catch (exception: IllegalArgumentException) {
            throw IllegalArgumentException("Unknown library item type: $value", exception)
        }

    private fun parseSourceKind(value: String): LibrarySourceKind =
        try {
            LibrarySourceKind.valueOf(value)
        } catch (exception: IllegalArgumentException) {
            throw IllegalArgumentException("Unknown library source kind: $value", exception)
        }

    private fun parseInstant(value: String, field: String): Instant =
        try {
            Instant.parse(value)
        } catch (exception: RuntimeException) {
            throw IllegalArgumentException("Invalid $field timestamp", exception)
        }

    private fun JsonObject.required(key: String): JsonElement =
        get(key) ?: throw IllegalArgumentException("Missing required library field: $key")

    private fun JsonObject.requiredString(key: String): String =
        required(key).jsonPrimitive.content

    private fun JsonObject.requiredArray(key: String): JsonArray =
        required(key).jsonArray

    private fun JsonObject.optionalString(key: String): String? {
        val value = get(key) ?: return null
        return value.jsonPrimitive.contentOrNull
    }

    private fun JsonObject.optionalInt(key: String): Int? {
        val value = get(key) ?: return null
        if (value is JsonNull) return null
        return value.jsonPrimitive.int
    }

    private fun kotlinx.serialization.json.JsonObjectBuilder.putNullable(key: String, value: String?) {
        put(key, value?.let(::JsonPrimitive) ?: JsonNull)
    }

    private fun kotlinx.serialization.json.JsonObjectBuilder.putNullable(key: String, value: Int?) {
        put(key, value?.let(::JsonPrimitive) ?: JsonNull)
    }
}
