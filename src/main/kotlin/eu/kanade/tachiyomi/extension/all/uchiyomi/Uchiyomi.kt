package eu.kanade.tachiyomi.extension.all.uchiyomi

import android.app.Application
import android.content.Context
import android.content.SharedPreferences
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.widget.Toast
import androidx.preference.EditTextPreference
import androidx.preference.PreferenceScreen
import androidx.preference.SwitchPreferenceCompat
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.POST
import eu.kanade.tachiyomi.network.await
import eu.kanade.tachiyomi.source.ConfigurableSource
import eu.kanade.tachiyomi.source.model.Filter
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.model.SMangaUpdate
import eu.kanade.tachiyomi.source.online.HttpSource
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import okhttp3.CacheControl
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

/**
 * Uchiyomi as a Mihon source: your own library, read with one API token.
 *
 * The suspend API is the implementation -- it is what a lib-1.6 host calls, and 1.6 deprecates the old
 * request/parse helpers. Those helpers are still overridden, delegating to the same builders and parsers,
 * so a host that reaches a source through the legacy `fetch*` path gets identical behaviour rather than a
 * stub that throws. One set of decisions, two entry points.
 *
 * What it deliberately does not do: write. There is no call to `PUT /api/books/:id/progress`, because no
 * host offers an extension a hook that would make it -- "chapter read" reaches a server only through a
 * tracker compiled into the app itself. A `read`-scoped token is therefore all this ever needs, and the
 * settings screen says so rather than leaving the reader to discover it.
 */
class Uchiyomi : HttpSource(), ConfigurableSource {

    override val name = "Uchiyomi"
    override val lang = "all"
    override val supportsLatest = true

    /**
     * Pinned rather than derived. tachiyomix computes an id from name/lang/versionId, and a library is keyed
     * on it: change the name and every reader's shelf points at a source that no longer exists. The constant
     * lives in build.gradle.kts so the store index and the code cannot disagree; MappingTest asserts it still
     * equals what the formula gives for the original inputs.
     */
    override val id: Long = BuildConfig.SOURCE_ID

    private val preferences: SharedPreferences by lazy {
        Injekt.get<Application>().getSharedPreferences("source_$id", 0x0000)
    }

    /** A getter, not a value: the address is a setting and the source object outlives any one of them. */
    override val baseUrl: String
        get() = preferences.getString(PREF_URL, "").orEmpty().trim().trimEnd('/')

    private val token: String get() = preferences.getString(PREF_TOKEN, "").orEmpty().trim()
    private val skipJunk: Boolean get() = preferences.getBoolean(PREF_SKIP_JUNK, true)
    private val showAdult: Boolean get() = preferences.getBoolean(PREF_ADULT, false)

    override fun headersBuilder(): Headers.Builder = Headers.Builder()
        .add("User-Agent", "Uchiyomi-Mihon/${BuildConfig.VERSION_NAME}")
        .apply { if (token.isNotEmpty()) add("Authorization", "Bearer $token") }

    // ---- requests ------------------------------------------------------------------------------------------

    /** Hosts cache JSON for ten minutes by default; a library listing that lags a fresh chapter by that is a bug report. */
    private val noCache = CacheControl.Builder().noCache().noStore().build()
    private val jsonType = "application/json; charset=utf-8".toMediaType()

    /**
     * Built per request rather than from the inherited `headers`, which the host computes once: a token
     * pasted into settings must work on the next request, not after a restart.
     */
    private fun fresh(): Headers = headersBuilder().build()

    private fun apiUrl(path: String): String {
        val base = baseUrl
        if (base.isEmpty()) throw Exception("Set the server address in the extension settings")
        if (token.isEmpty()) throw Exception("Set an API token in the extension settings (in Uchiyomi: Profile → Account → API tokens → Manage)")
        // `?adult=1` is how the API is asked to include 18+ libraries in listings; absent means hidden.
        val sep = if ('?' in path) '&' else '?'
        return if (showAdult) "$base$path${sep}adult=1" else "$base$path"
    }

    private fun get(path: String): Request = GET(apiUrl(path), fresh(), noCache)

    private fun search(q: Query, page: Int): Request = POST(
        apiUrl("/api/series/search"),
        fresh(),
        Mapping.json.encodeToString(q.toBody(page - 1, PAGE_SIZE)).toRequestBody(jsonType),
        noCache,
    )

    // ---- listings ------------------------------------------------------------------------------------------

    /** "Popular" is a tab every source must have; for a shelf that belongs to one person it means your favourites, then what you are furthest behind on. */
    private fun popularRequest(page: Int): Request = search(Query(sort = "favorites,desc"), page)
    private fun latestRequest(page: Int): Request = search(Query(sort = "updated,desc"), page)
    private fun searchRequest(page: Int, query: String, filters: FilterList): Request = search(queryOf(query, filters), page)

    override suspend fun getPopularManga(page: Int): MangasPage = listingParse(client.newCall(popularRequest(page)).await())
    override suspend fun getLatestUpdates(page: Int): MangasPage = listingParse(client.newCall(latestRequest(page)).await())
    override suspend fun getSearchManga(page: Int, query: String, filters: FilterList): MangasPage =
        listingParse(client.newCall(searchRequest(page, query, filters)).await())

    @Deprecated("legacy hosts only") override fun popularMangaRequest(page: Int): Request = popularRequest(page)
    @Deprecated("legacy hosts only") override fun popularMangaParse(response: Response): MangasPage = listingParse(response)
    @Deprecated("legacy hosts only") override fun latestUpdatesRequest(page: Int): Request = latestRequest(page)
    @Deprecated("legacy hosts only") override fun latestUpdatesParse(response: Response): MangasPage = listingParse(response)
    @Deprecated("legacy hosts only") override fun searchMangaRequest(page: Int, query: String, filters: FilterList): Request = searchRequest(page, query, filters)
    @Deprecated("legacy hosts only") override fun searchMangaParse(response: Response): MangasPage = listingParse(response)

    private fun listingParse(response: Response): MangasPage {
        val page = decode<PageDto<SeriesDto>>(response)
        return MangasPage(page.content.map { toSManga(Mapping.series(it)) }, hasNextPage = !page.last)
    }

    private fun queryOf(query: String, filters: FilterList): Query {
        var sort = SORT_KEYS[0]
        var status: String? = null
        var read: String? = null
        val genres = mutableListOf<String>()
        val libs = mutableListOf<String>()
        filters.forEach { f ->
            when (f) {
                is SortFilter -> sort = SORT_KEYS[f.state]
                is StatusFilter -> status = STATUS_KEYS[f.state]
                is ReadStateFilter -> read = READ_STATE_KEYS[f.state]
                is GenreGroup -> genres += f.state.filter { it.state }.map { it.name }
                is LibraryGroup -> libs += f.state.filter { it.state }.map { it.id }
                else -> Unit
            }
        }
        return Query(query, sort, genres, libs, status, read)
    }

    // ---- one series ----------------------------------------------------------------------------------------

    private fun detailsRequest(manga: SManga): Request = get(manga.url)
    private fun booksRequest(seriesUrl: String, page: Int): Request = get("$seriesUrl/books?size=$BOOK_PAGE&page=$page")
    private fun detailsParse(response: Response): SManga = toSManga(Mapping.series(decode<SeriesDto>(response)))

    /**
     * The 1.6 entry point for both details and chapters. Only what was asked for is fetched, and what was
     * not is handed back untouched: the host may apply anything returned here regardless of the flags.
     */
    override suspend fun getMangaUpdate(
        manga: SManga,
        chapters: List<SChapter>,
        fetchDetails: Boolean,
        fetchChapters: Boolean,
    ): SMangaUpdate {
        val details = if (fetchDetails) detailsParse(client.newCall(detailsRequest(manga)).await()) else manga
        val list = if (fetchChapters) allChapters(manga.url) { client.newCall(it).await() } else chapters
        return SMangaUpdate(details, list)
    }

    /**
     * The server pages chapters; a host wants them all at once. So every page is fetched, in order, and the
     * whole list is reversed once -- reversing page by page would hand back [200..101, 100..1] and the
     * host would show chapter 101 as the newest. `fetch` is how one request becomes a response, because the
     * suspend path awaits it and the legacy path must block.
     */
    private inline fun allChapters(seriesUrl: String, fetch: (Request) -> Response): List<SChapter> {
        val all = mutableListOf<BookDto>()
        var n = 0
        while (n < MAX_BOOK_PAGES) {
            val page = decode<PageDto<BookDto>>(fetch(booksRequest(seriesUrl, n)))
            all += page.content
            if (page.last || page.content.isEmpty()) break
            n++
        }
        return Mapping.chapters(all).map { toSChapter(it) }
    }

    override fun getMangaUrl(manga: SManga): String = "$baseUrl/series/?id=${Mapping.seriesIdOf(manga.url)}"
    override fun getChapterUrl(chapter: SChapter): String = "$baseUrl/reader/?book=${Mapping.bookIdOf(chapter.url)}"

    @Deprecated("legacy hosts only") override fun mangaDetailsRequest(manga: SManga): Request = detailsRequest(manga)
    @Deprecated("legacy hosts only") override fun mangaDetailsParse(response: Response): SManga = detailsParse(response)
    @Deprecated("legacy hosts only") override fun chapterListRequest(manga: SManga): Request = booksRequest(manga.url, 0)

    /** The legacy host hands over page 0; the rest are fetched synchronously, which is the only option on that path. */
    @Deprecated("legacy hosts only")
    override fun chapterListParse(response: Response): List<SChapter> {
        val seriesUrl = "/api/series/" + Mapping.seriesIdOf(response.request.url.encodedPath)
        return allChapters(seriesUrl) { if (it.url == response.request.url) response else client.newCall(it).execute() }
    }

    // ---- pages ---------------------------------------------------------------------------------------------

    private fun pagesRequest(chapter: SChapter): Request = get("${chapter.url}/pages")

    private fun pagesParse(response: Response): List<Page> {
        val bookId = Mapping.bookIdOf(response.request.url.encodedPath)
        val pages = decode<List<PageInfoDto>>(response)
        return Mapping.pageNumbers(pages, skipJunk).mapIndexed { i, n ->
            Page(i, imageUrl = "$baseUrl/img/books/$bookId/page/$n")
        }
    }

    override suspend fun getPageList(chapter: SChapter): List<Page> = pagesParse(client.newCall(pagesRequest(chapter)).await())

    @Deprecated("legacy hosts only") override fun pageListRequest(chapter: SChapter): Request = pagesRequest(chapter)
    @Deprecated("legacy hosts only") override fun pageListParse(response: Response): List<Page> = pagesParse(response)

    @Deprecated("legacy hosts only")
    override fun imageUrlParse(response: Response): String = throw UnsupportedOperationException("image URLs are known up front")

    override fun imageRequest(page: Page): Request = GET(
        page.imageUrl!!,
        fresh().newBuilder().add("Accept", "image/*").build(),
    )

    // ---- filters -------------------------------------------------------------------------------------------

    @Volatile private var genres: List<String> = emptyList()
    @Volatile private var libraries: List<LibraryDto> = emptyList()
    @Volatile private var filtersRequested = false

    /**
     * Genres and libraries come from the server, and hosts call this on the UI thread, so the lists are
     * fetched once in the background: the first panel says so, the next one has them.
     */
    override fun getFilterList(): FilterList {
        if (!filtersRequested && baseUrl.isNotEmpty() && token.isNotEmpty()) {
            filtersRequested = true
            Thread {
                try {
                    genres = decode<PageDto<String>>(client.newCall(get("/api/genres")).execute()).content
                    libraries = decode<List<LibraryDto>>(client.newCall(get("/api/libraries")).execute())
                } catch (_: Exception) {
                    filtersRequested = false // try again next time the panel opens
                }
            }.start()
        }
        val list = mutableListOf<Filter<*>>(SortFilter(), StatusFilter(), ReadStateFilter())
        if (genres.isNotEmpty()) list += GenreGroup(genres.map(::GenreCheck))
        else list += NoteFilter("Genres and libraries load in the background -- reopen this panel")
        if (libraries.size > 1) list += LibraryGroup(libraries.map { LibraryCheck(it.id, it.name) })
        return FilterList(list)
    }

    // ---- into the host's models ----------------------------------------------------------------------------

    private fun toSManga(row: Mapping.SeriesRow): SManga = SManga.create().apply {
        url = row.url
        title = row.title
        author = row.author
        description = row.description
        genre = row.genre
        status = row.status
        thumbnail_url = "$baseUrl${row.thumbnailUrl}"
        initialized = true
    }

    private fun toSChapter(row: Mapping.ChapterRow): SChapter = SChapter.create().apply {
        url = row.url
        name = row.name
        chapter_number = row.chapterNumber
        date_upload = row.dateUpload
    }

    // ---- decoding ------------------------------------------------------------------------------------------

    private inline fun <reified T> decode(response: Response): T {
        val body = response.body.string()
        if (!response.isSuccessful) throw Exception(describe(response.code, body))
        return Mapping.json.decodeFromString(body)
    }

    /** An error a person can act on, built from the `{ error, message }` the server sends. */
    private fun describe(code: Int, body: String): String {
        val server = try {
            Mapping.json.decodeFromString<ErrorDto>(body)
        } catch (_: Exception) {
            null
        }
        val detail = server?.message ?: server?.error
        return when (code) {
            401 -> "Uchiyomi rejected the API token (401). Check it under Profile → Account → API tokens."
            403 -> "Not allowed (403): ${detail ?: "the token lacks a scope this needs"}"
            404 -> "Not found (404)${detail?.let { ": $it" } ?: ""}"
            else -> "Uchiyomi answered $code${detail?.let { ": $it" } ?: ""}"
        }
    }

    // ---- settings ------------------------------------------------------------------------------------------

    /**
     * Two text fields, two switches. There is no separate "test connection" row: each field is tested the
     * moment it is saved, in the background, and the result is a toast -- the address against `/healthz`,
     * the token against `/auth/me`. (The extension-lib stubs offer no way to build a plain clickable row,
     * and constructing one reflectively produced bytecode Suwayomi's JVM verifier refused.)
     */
    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        val ctx = screen.context

        EditTextPreference(ctx).apply {
            key = PREF_URL
            title = "Server address"
            summary = baseUrl.ifEmpty { "e.g. https://manga.example.com" }
            dialogTitle = title
            setOnBindEditTextListener { it.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI }
            setOnPreferenceChangeListener { _, new ->
                val url = (new as String).trim().trimEnd('/')
                if (url.toHttpUrlOrNull() == null) {
                    toast(ctx, "That is not a URL")
                    false
                } else {
                    summary = url
                    checkInBackground(ctx) { probeServer(url) }
                    true
                }
            }
        }.also(screen::addPreference)

        EditTextPreference(ctx).apply {
            key = PREF_TOKEN
            title = "API token"
            summary = "In Uchiyomi: Profile → Account → API tokens → Manage → New token. Leave \"Allow changes\" off: " +
                "this extension never writes, so reading progress stays in this app and is not sent back to Uchiyomi."
            dialogTitle = title
            setOnBindEditTextListener { it.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD }
            setOnPreferenceChangeListener { _, new ->
                val tok = (new as String).trim()
                if (!tok.startsWith("uy_")) {
                    toast(ctx, "An Uchiyomi API token starts with uy_")
                    false
                } else {
                    checkInBackground(ctx) { probeToken(baseUrl, tok) }
                    true
                }
            }
        }.also(screen::addPreference)

        SwitchPreferenceCompat(ctx).apply {
            key = PREF_SKIP_JUNK
            title = "Skip repeated pages"
            summary = "Leave out pages Uchiyomi has seen repeated across chapters -- credit cards, site plates"
            setDefaultValue(true)
        }.also(screen::addPreference)

        SwitchPreferenceCompat(ctx).apply {
            key = PREF_ADULT
            title = "Show 18+ libraries"
            summary = "Include libraries marked adult in listings and search"
            setDefaultValue(false)
        }.also(screen::addPreference)
    }

    /** `GET /healthz` with no credentials: is there an Uchiyomi at this address at all? */
    private fun probeServer(url: String): String {
        val r = client.newCall(GET("$url/healthz", fresh(), noCache)).execute()
        val body = r.body.string()
        return if (r.isSuccessful && body.contains("\"ok\":true")) "Uchiyomi found at $url" else "No Uchiyomi at $url (${r.code})"
    }

    /** `GET /auth/me` with exactly this token, before it is saved, so a typo is caught on the spot. */
    private fun probeToken(url: String, tok: String): String {
        if (url.isEmpty()) return "Set the server address first"
        val headers = Headers.Builder().add("Authorization", "Bearer $tok").build()
        val me = decode<MeDto>(client.newCall(GET("$url/auth/me", headers, noCache)).execute())
        return "Connected as " + (me.displayName?.takeIf { it.isNotBlank() } ?: me.username)
    }

    private fun checkInBackground(ctx: Context, probe: () -> String) {
        Thread {
            val msg = try {
                probe()
            } catch (e: Exception) {
                e.message ?: e.toString()
            }
            try {
                Handler(Looper.getMainLooper()).post { toast(ctx, msg) }
            } catch (_: Throwable) {
                // no main looper on this host: the toast is the only thing lost
            }
        }.start()
    }

    private fun toast(ctx: Context, msg: String) {
        try {
            Toast.makeText(ctx, msg, Toast.LENGTH_LONG).show()
        } catch (_: Throwable) {
            // a host without a UI toolkit (a headless server) simply has nowhere to show it
        }
    }

    companion object {
        const val PREF_URL = "server_url"
        const val PREF_TOKEN = "api_token"
        const val PREF_SKIP_JUNK = "skip_junk"
        const val PREF_ADULT = "show_adult"

        /** Series per listing page; the API caps at 100. */
        const val PAGE_SIZE = 40

        /** Chapters per request while listing a series, and how many such requests before giving up. */
        const val BOOK_PAGE = 500
        const val MAX_BOOK_PAGES = 40
    }
}
