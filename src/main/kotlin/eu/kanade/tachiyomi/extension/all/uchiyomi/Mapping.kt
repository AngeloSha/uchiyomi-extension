package eu.kanade.tachiyomi.extension.all.uchiyomi

import eu.kanade.tachiyomi.source.model.SManga
import kotlinx.serialization.json.Json
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

/**
 * Everything the extension decides, with no Mihon type in sight.
 *
 * `SManga.create()` and friends are stubs that throw outside a host app, so anything that touches them
 * cannot run in a JVM unit test. The decisions -- which fields become which, what a status means, which
 * pages to keep -- are made here on plain values, tested against captured responses from a live server,
 * and copied into the host's models by [Uchiyomi] in a few lines that could not be wrong on their own.
 */
object Mapping {
    val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        coerceInputValues = true
    }

    /** What a series looks like once mapped. `url` is relative, so a moved server does not orphan a library. */
    class SeriesRow(
        val url: String,
        val title: String,
        val author: String?,
        val description: String?,
        val genre: String?,
        val status: Int,
        val thumbnailUrl: String,
    )

    class ChapterRow(
        val url: String,
        val name: String,
        val chapterNumber: Float,
        val dateUpload: Long,
        val scanlator: String?,
    )

    /**
     * Uchiyomi's status strings to Mihon's constants. The server stores what the source said, so the empty
     * string is common and means "nobody knows", not "cancelled".
     */
    fun status(raw: String?): Int = when (raw?.trim()?.uppercase(Locale.ROOT)) {
        "ONGOING", "RELEASING", "PUBLISHING" -> SManga.ONGOING
        "COMPLETED", "ENDED", "FINISHED" -> SManga.COMPLETED
        "HIATUS", "ON_HIATUS" -> SManga.ON_HIATUS
        "CANCELLED", "CANCELED", "ABANDONED", "DROPPED" -> SManga.CANCELLED
        else -> SManga.UNKNOWN
    }

    fun series(s: SeriesDto): SeriesRow {
        val m = s.metadata
        // The listing carries the description under booksMetadata and the detail page under metadata;
        // whichever is present wins, the edited one first.
        val summary = m.summary?.takeIf { it.isNotBlank() } ?: s.booksMetadata?.summary?.takeIf { it.isNotBlank() }
        val genres = (m.genres.ifEmpty { s.booksMetadata?.genres.orEmpty() } + m.tags).distinct()
        return SeriesRow(
            url = "/api/series/${s.id}",
            title = m.title?.takeIf { it.isNotBlank() } ?: s.name,
            author = m.author?.takeIf { it.isNotBlank() },
            description = summary,
            genre = genres.joinToString(", ").ifBlank { null },
            status = status(m.status),
            thumbnailUrl = "/img/series/${s.id}/thumb",
        )
    }

    /** The series id back out of a stored `SManga.url`, tolerant of a query string a future version may add. */
    fun seriesIdOf(url: String): String = url.substringAfter("/api/series/").substringBefore('/').substringBefore('?')

    fun bookIdOf(url: String): String = url.substringAfter("/api/books/").substringBefore('/').substringBefore('?')

    fun chapter(b: BookDto): ChapterRow = ChapterRow(
        url = "/api/books/${b.id}",
        name = b.metadata.title?.takeIf { it.isNotBlank() } ?: b.name.ifBlank { "Chapter ${b.number}" },
        chapterNumber = b.metadata.numberSort ?: b.number,
        dateUpload = parseDate(b.metadata.releaseDate),
        // Mihon and Tachimanga filter and sort a chapter list by group on their own; that only works when the
        // group travels with the chapter. A blank name is no group, so the host does not offer "" as a filter.
        scanlator = b.scanlator?.takeIf { it.isNotBlank() },
    )

    /**
     * Chapters as Mihon wants them: newest first. The server pages them oldest first, and a list that is
     * merely reversed per page would interleave wrongly, so callers concatenate all pages and reverse once.
     *
     * A chapter the server deleted (`pruned`) is left out: it still has a row, so the server lists it, but
     * it has no pages -- the image server answers 404 per page, which Mihon shows as a broken chapter, and a
     * reader who then re-downloads it in Mihon gets the same 404s again.
     */
    fun chapters(all: List<BookDto>): List<ChapterRow> = all.filter { !it.pruned }.map(::chapter).asReversed()

    /**
     * Which page numbers to fetch for a chapter.
     *
     * A `junk` page is one Uchiyomi has seen repeated across three or more chapters of the same series -- a
     * credits card, a "read on our site" plate. With `skipJunk` those are dropped. But a chapter that is
     * nothing but such pages is a chapter the heuristic has got wrong, and an empty chapter reads as a broken
     * download, so it is returned whole instead (the web reader makes the same call).
     */
    fun pageNumbers(pages: List<PageInfoDto>, skipJunk: Boolean): List<Int> {
        val kept = if (skipJunk) pages.filter { !it.junk } else pages
        return (if (kept.isEmpty()) pages else kept).map { it.number }
    }

    private val iso = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.ROOT).apply { timeZone = TimeZone.getTimeZone("UTC") }

    /** ISO-8601 with any fraction and a `Z`, as the server writes it; anything else is 0 (unknown). */
    fun parseDate(raw: String?): Long {
        if (raw.isNullOrBlank()) return 0L
        val head = raw.substringBefore('.').substringBefore('Z').substringBefore('+')
        return try {
            synchronized(iso) { iso.parse(head)?.time ?: 0L }
        } catch (_: Exception) {
            0L
        }
    }

    /**
     * The source id exactly as tachiyomix derives it: the first eight bytes of `md5("$name/$lang/$versionId")`,
     * big-endian, with the sign bit cleared. Pinned in build.gradle.kts as SOURCE_ID; the test asserts the two
     * agree, because a source whose id changes orphans every library that used it.
     */
    fun sourceId(name: String, lang: String, versionId: Int): Long {
        val key = "${name.lowercase(Locale.ROOT)}/$lang/$versionId"
        val bytes = MessageDigest.getInstance("MD5").digest(key.toByteArray())
        return (0..7).fold(0L) { acc, i -> (acc shl 8) or (bytes[i].toLong() and 0xff) } and Long.MAX_VALUE
    }
}
