package eu.kanade.tachiyomi.extension.all.uchiyomi

import eu.kanade.tachiyomi.source.model.SManga
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Every fixture under src/test/resources is a real response captured from a live Uchiyomi (v0.28.1,
 * 2026-09-13), not something typed to fit the DTOs. If the server changes shape, these are what notice.
 */
class MappingTest {

    private fun fixture(name: String): String =
        javaClass.getResourceAsStream("/$name")!!.bufferedReader().readText()

    // ---- the DTOs decode every real response ---------------------------------------------------------------

    @Test fun `a listing decodes and keeps its paging flags`() {
        val page = Mapping.json.decodeFromString<PageDto<SeriesDto>>(fixture("search.json"))
        assertEquals(3, page.content.size)
        assertFalse(page.last)
        assertEquals("Solo Max-Level Newbie", page.content[0].name)
    }

    @Test fun `a favourites listing decodes`() {
        val page = Mapping.json.decodeFromString<PageDto<SeriesDto>>(fixture("search_fav.json"))
        assertEquals(3, page.content.size)
        assertTrue(page.content.all { it.id.startsWith("s_") })
    }

    @Test fun `series detail, chapter list, page list, genres, libraries and me all decode`() {
        Mapping.json.decodeFromString<SeriesDto>(fixture("series.json"))
        Mapping.json.decodeFromString<SeriesDto>(fixture("series_junk.json"))
        val books = Mapping.json.decodeFromString<PageDto<BookDto>>(fixture("books.json"))
        assertEquals(5, books.content.size)
        assertFalse(books.last)
        assertEquals(23, Mapping.json.decodeFromString<List<PageInfoDto>>(fixture("pages.json")).size)
        assertTrue(Mapping.json.decodeFromString<PageDto<String>>(fixture("genres.json")).content.contains("Action"))
        val libs = Mapping.json.decodeFromString<List<LibraryDto>>(fixture("libraries.json"))
        assertEquals(2, libs.size)
        assertTrue(libs.any { it.adult })
        assertEquals("admin", Mapping.json.decodeFromString<MeDto>(fixture("me.json")).username)
    }

    @Test fun `a series with nothing but an id still decodes`() {
        // The server may omit anything; one sparse entry must not sink a whole listing.
        val s = Mapping.json.decodeFromString<SeriesDto>("""{"id":"s_1"}""")
        val row = Mapping.series(s)
        assertEquals("/api/series/s_1", row.url)
        assertEquals("", row.title)
        assertNull(row.author)
        assertEquals(SManga.UNKNOWN, row.status)
    }

    // ---- mapping decisions ---------------------------------------------------------------------------------

    @Test fun `status strings map to Mihon constants and the empty string is unknown`() {
        assertEquals(SManga.ONGOING, Mapping.status("ONGOING"))
        assertEquals(SManga.COMPLETED, Mapping.status("completed"))
        assertEquals(SManga.ON_HIATUS, Mapping.status("HIATUS"))
        assertEquals(SManga.CANCELLED, Mapping.status("CANCELLED"))
        assertEquals(SManga.UNKNOWN, Mapping.status(""))
        assertEquals(SManga.UNKNOWN, Mapping.status(null))
        assertEquals(SManga.UNKNOWN, Mapping.status("something new"))
    }

    @Test fun `a real series maps with a relative url, a relative thumbnail and its genres`() {
        val row = Mapping.series(Mapping.json.decodeFromString<SeriesDto>(fixture("series.json")))
        assertEquals("/api/series/s_43c5e9fa8eaddb1a690f", row.url)
        assertEquals("/img/series/s_43c5e9fa8eaddb1a690f/thumb", row.thumbnailUrl)
        assertEquals("Solo Max-Level Newbie", row.title)
        assertNull("an empty author string is no author", row.author)
        assertTrue(row.genre!!.contains("Manhwa"))
        assertTrue(row.description!!.startsWith("A brief description"))
        assertEquals(SManga.UNKNOWN, row.status) // this series has status ""
    }

    @Test fun `the listing summary is used when the edited one is blank`() {
        val s = Mapping.json.decodeFromString<SeriesDto>(
            """{"id":"s_2","name":"X","metadata":{"summary":""},"booksMetadata":{"summary":"from the books"}}""",
        )
        assertEquals("from the books", Mapping.series(s).description)
    }

    @Test fun `chapters come back newest first with their numbers and dates`() {
        val books = Mapping.json.decodeFromString<PageDto<BookDto>>(fixture("books.json")).content
        val rows = Mapping.chapters(books)
        assertEquals(listOf(4f, 3f, 2f, 1f, 0f), rows.map { it.chapterNumber })
        assertEquals("/api/books/b_33b93062b20490da3b7f", rows.last().url)
        assertEquals("Chapter 0", rows.last().name)
        // 2026-08-29T21:10:40.565Z, to the second
        assertEquals(1788037840000L, rows.last().dateUpload)
    }

    @Test fun `a chapter the server deleted is not listed`() {
        // Reintroduce by dropping the `filter { !it.pruned }` in Mapping.chapters: both books come back and
        // the pruned one opens as a chapter whose every page is a 404.
        val books = Mapping.json.decodeFromString<PageDto<BookDto>>(
            """{"content":[{"id":"b_live","number":1,"metadata":{"numberSort":1}},
                         {"id":"b_gone","number":2,"metadata":{"numberSort":2},"pruned":true}],
                "last":true,"totalElements":2}""",
        ).content
        assertTrue("the field decodes", books[1].pruned)
        val rows = Mapping.chapters(books)
        assertEquals(1, rows.size)
        assertEquals("/api/books/b_live", rows[0].url)
    }

    @Test fun `an unparseable date is zero rather than a crash`() {
        assertEquals(0L, Mapping.parseDate(null))
        assertEquals(0L, Mapping.parseDate(""))
        assertEquals(0L, Mapping.parseDate("yesterday"))
    }

    @Test fun `ids come back out of stored urls`() {
        assertEquals("s_abc", Mapping.seriesIdOf("/api/series/s_abc"))
        assertEquals("s_abc", Mapping.seriesIdOf("/api/series/s_abc?adult=1"))
        assertEquals("b_1", Mapping.bookIdOf("/api/books/b_1/pages"))
    }

    // ---- junk pages ----------------------------------------------------------------------------------------

    @Test fun `repeated pages are skipped when asked and kept when not`() {
        val pages = Mapping.json.decodeFromString<List<PageInfoDto>>(fixture("pages.json"))
        assertEquals(listOf(22, 23), pages.filter { it.junk }.map { it.number })
        val skipped = Mapping.pageNumbers(pages, skipJunk = true)
        assertEquals(21, skipped.size)
        assertFalse(22 in skipped)
        assertFalse(23 in skipped)
        assertEquals((1..23).toList(), Mapping.pageNumbers(pages, skipJunk = false))
    }

    @Test fun `a chapter that is all repeated pages is shown whole, never empty`() {
        // Reintroduce by returning `kept` unconditionally in Mapping.pageNumbers: this gives [] and a reader
        // sees a chapter with no pages, which looks exactly like a broken download.
        val all = listOf(PageInfoDto(1, junk = true), PageInfoDto(2, junk = true))
        assertEquals(listOf(1, 2), Mapping.pageNumbers(all, skipJunk = true))
    }

    // ---- the pinned id -------------------------------------------------------------------------------------

    @Test fun `the pinned source id is what tachiyomix derives from the original name`() {
        // Reintroduce by editing `sourceId` in build.gradle.kts: the constant and the formula part ways, and
        // every library keyed on the old id points at nothing.
        assertEquals(8683375824843625513L, BuildConfig.SOURCE_ID)
        assertEquals(BuildConfig.SOURCE_ID, Mapping.sourceId("Uchiyomi", "all", 1))
    }

    // ---- the search body -----------------------------------------------------------------------------------

    @Test fun `a bare query sends no condition and a zero-based page`() {
        val body = Query(query = " solo ", sort = "updated,desc").toBody(page = 0, size = 40)
        assertEquals("solo", body["query"]!!.jsonPrimitive.content)
        assertEquals("updated,desc", body["sort"]!!.jsonPrimitive.content)
        assertEquals("0", body["page"]!!.jsonPrimitive.content)
        assertNull(body["condition"])
    }

    @Test fun `filters become the predicate tree the web app sends`() {
        val body = Query(
            genres = listOf("Action", "Comedy"),
            libraryIds = listOf("lib", "lib_x"),
            status = "ONGOING",
            readState = "UNREAD",
        ).toBody(page = 2, size = 40)
        val allOf = body["condition"]!!.jsonObject["allOf"]!!.jsonArray
        assertEquals(5, allOf.size)
        assertEquals("Action", allOf[0].jsonObject["genre"]!!.jsonObject["value"]!!.jsonPrimitive.content)
        assertEquals("Comedy", allOf[1].jsonObject["genre"]!!.jsonObject["value"]!!.jsonPrimitive.content)
        val libs = allOf[2].jsonObject["anyOf"]!!.jsonArray
        assertEquals(2, libs.size)
        assertEquals("lib_x", libs[1].jsonObject["libraryId"]!!.jsonObject["value"]!!.jsonPrimitive.content)
        assertEquals("ONGOING", allOf[3].jsonObject["status"]!!.jsonObject["value"]!!.jsonPrimitive.content)
        assertEquals("UNREAD", allOf[4].jsonObject["readStatus"]!!.jsonObject["value"]!!.jsonPrimitive.content)
        assertNull("no query key when the text is empty", body["query"])
    }
}
