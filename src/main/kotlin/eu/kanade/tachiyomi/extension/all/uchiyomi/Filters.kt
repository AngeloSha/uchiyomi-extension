package eu.kanade.tachiyomi.extension.all.uchiyomi

import eu.kanade.tachiyomi.source.model.Filter
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

// The filter panel, and the JSON it turns into. The condition grammar is the one Uchiyomi's own web app
// sends to POST /api/series/search: `allOf` / `anyOf` of `{ field: { operator, value } }` predicates.

class SortFilter : Filter.Select<String>("Sort", SORT_LABELS)
class StatusFilter : Filter.Select<String>("Status", STATUS_LABELS)
class ReadStateFilter : Filter.Select<String>("Read state", READ_STATE_LABELS)

class GenreCheck(name: String) : Filter.CheckBox(name)
class GenreGroup(genres: List<GenreCheck>) : Filter.Group<GenreCheck>("Genres (all of)", genres)

class LibraryCheck(val id: String, name: String) : Filter.CheckBox(name)
class LibraryGroup(libraries: List<LibraryCheck>) : Filter.Group<LibraryCheck>("Libraries (any of)", libraries)

class NoteFilter(text: String) : Filter.Header(text)

val SORT_LABELS = arrayOf("Recently updated", "Recently added", "Title", "Most unread", "Favourites first")
val SORT_KEYS = arrayOf("updated,desc", "added,desc", "title,asc", "unread,desc", "favorites,desc")

val STATUS_LABELS = arrayOf("Any", "Ongoing", "Completed", "On hiatus", "Cancelled")
val STATUS_KEYS = arrayOf<String?>(null, "ONGOING", "COMPLETED", "HIATUS", "CANCELLED")

val READ_STATE_LABELS = arrayOf("Any", "Unread", "Reading", "Read")
val READ_STATE_KEYS = arrayOf<String?>(null, "UNREAD", "IN_PROGRESS", "READ")

/** What a search asks for, as plain values, so the JSON can be tested without a Filter in sight. */
class Query(
    val query: String = "",
    val sort: String = SORT_KEYS[0],
    val genres: List<String> = emptyList(),
    val libraryIds: List<String> = emptyList(),
    val status: String? = null,
    val readState: String? = null,
)

/**
 * The request body. Genres are ANDed (a series must carry all of them -- the same promise the web app's
 * filter panel makes); libraries are ORed, since a series lives in exactly one. `condition` is omitted
 * entirely when nothing is selected rather than sent as an empty tree.
 */
fun Query.toBody(page: Int, size: Int): JsonObject = buildJsonObject {
    if (query.isNotBlank()) put("query", query.trim())
    put("sort", sort)
    put("page", page)
    put("size", size)
    val predicates = buildJsonArray {
        genres.forEach { g -> add(predicate("genre", g)) }
        if (libraryIds.isNotEmpty()) {
            add(buildJsonObject { put("anyOf", buildJsonArray { libraryIds.forEach { add(predicate("libraryId", it)) } }) })
        }
        status?.let { add(predicate("status", it)) }
        readState?.let { add(predicate("readStatus", it)) }
    }
    if (predicates.isNotEmpty()) putJsonObject("condition") { put("allOf", predicates) }
}

private fun predicate(field: String, value: String): JsonObject = buildJsonObject {
    putJsonObject(field) {
        put("operator", "is")
        put("value", value)
    }
}
