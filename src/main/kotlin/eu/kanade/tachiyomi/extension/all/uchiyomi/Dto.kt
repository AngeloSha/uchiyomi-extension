package eu.kanade.tachiyomi.extension.all.uchiyomi

import kotlinx.serialization.Serializable

// Mirrors of the Uchiyomi JSON the extension reads, and nothing more. Every field the server may leave out
// is nullable with a default: kotlinx.serialization fails the whole document on one missing non-nullable
// field, and a listing that dies because a single series has no summary is worse than a blank summary.
// The captured fixtures under src/test/resources are real responses from a live server and the tests
// decode every one of them through these classes.

/** `{ content: [...], last, totalElements }` -- the wrapper every list endpoint answers with. */
@Serializable
class PageDto<T>(
    val content: List<T> = emptyList(),
    val last: Boolean = true,
    val totalElements: Long = 0,
)

@Serializable
class SeriesDto(
    val id: String,
    val name: String = "",
    val libraryId: String? = null,
    val booksCount: Int = 0,
    val booksUnreadCount: Int = 0,
    val metadata: SeriesMetadataDto = SeriesMetadataDto(),
    val booksMetadata: BooksMetadataDto? = null,
    val yomi: YomiDto? = null,
)

@Serializable
class SeriesMetadataDto(
    val title: String? = null,
    val status: String? = null,
    val author: String? = null,
    val publisher: String? = null,
    val genres: List<String> = emptyList(),
    val tags: List<String> = emptyList(),
    val summary: String? = null,
    val readingDirection: String? = null,
    val language: String? = null,
)

/** The description lives here on listings; `metadata.summary` is the edited copy on the detail page. */
@Serializable
class BooksMetadataDto(
    val summary: String? = null,
    val genres: List<String> = emptyList(),
    val tags: List<String> = emptyList(),
)

@Serializable
class YomiDto(
    val favorite: Boolean = false,
    val unread: Int = 0,
    val newCount: Int = 0,
)

@Serializable
class BookDto(
    val id: String,
    val seriesId: String? = null,
    val name: String = "",
    val number: Float = 0f,
    val metadata: BookMetadataDto = BookMetadataDto(),
    val media: MediaDto? = null,
)

@Serializable
class BookMetadataDto(
    val title: String? = null,
    val number: String? = null,
    val numberSort: Float? = null,
    val releaseDate: String? = null,
)

@Serializable
class MediaDto(
    val pagesCount: Int = 0,
)

/** One entry of `GET /api/books/:id/pages`. `junk` is present only when true. */
@Serializable
class PageInfoDto(
    val number: Int,
    val junk: Boolean = false,
)

@Serializable
class LibraryDto(
    val id: String,
    val name: String = "",
    val adult: Boolean = false,
)

/** `GET /auth/me`, used by the Test connection button. */
@Serializable
class MeDto(
    val username: String = "",
    val displayName: String? = null,
    val role: String? = null,
)

/** `{ error, message }` -- what every non-2xx answer carries. */
@Serializable
class ErrorDto(
    val error: String = "",
    val message: String? = null,
)
