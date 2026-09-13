package eu.kanade.tachiyomi.extension.all.uchiyomi

import eu.kanade.tachiyomi.source.Source
import eu.kanade.tachiyomi.source.SourceFactory

/** One server per install for now. A factory rather than a bare source so a second instance can be added without a new package. */
class UchiyomiFactory : SourceFactory {
    override fun createSources(): List<Source> = listOf(Uchiyomi())
}
