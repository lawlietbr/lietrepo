package com.PobreFlix

import com.PobreFlix.extractor.PobreFlixExtractor
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.*
import org.json.JSONObject
import org.jsoup.nodes.Element
import java.net.URLEncoder

class PobreFlix : MainAPI() {
    override var mainUrl = "https://pobreflix.best"
    override var name = "PobreFlix"
    override val hasMainPage = true
    override var lang = "pt-br"
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries, TvType.Anime, TvType.AsianDrama)
    override val usesWebView = false

    companion object {
        private val MAIN_SECTIONS = listOf(
            "" to "Em Alta",
            "/filmes" to "Filmes",
            "/series" to "Séries",
            "/animes" to "Animes",
            "/doramas" to "Doramas",
            "/lancamentos" to "Lançamentos"
        )
    }

    override val mainPage = mainPageOf(
        *MAIN_SECTIONS.map { (path, name) ->
            if (path.isEmpty()) mainUrl to name
            else "$mainUrl$path" to name
        }.toTypedArray()
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        return try {
            var url = request.data
            if (page > 1) {
                url = if (url.contains("?")) "$url&page=$page" else "$url?page=$page"
            }
            val document = app.get(url).document
            val items = document.select("a.gPoster").mapNotNull { it.toSearchResult() }
            val hasNextPage = document
                .select("a[href*='page/'], a[href*='pagina/'], a:contains(Próxima)")
                .isNotEmpty()
            newHomePageResponse(request.name, items.distinctBy { it.url }, hasNext = hasNextPage)
        } catch (e: Exception) {
            newHomePageResponse(request.name, emptyList(), hasNext = false)
        }
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val href = this.attr("href").ifBlank { return null }

        var title = this.selectFirst(".i span")?.text()?.trim()
        if (title.isNullOrBlank()) title = this.attr("title")
        if (title.isNullOrBlank()) return null
        val cleanTitle = title
            .replace(Regex("^Assistir\\s+", RegexOption.IGNORE_CASE), "")
            .replace(Regex("\\s+Online(\\s+Grátis)?$", RegexOption.IGNORE_CASE), "")
            .trim()
            .ifBlank { return null }

        val poster = this.selectFirst("img")?.attr("src")?.ifBlank { null }
        val year = this.selectFirst(".y")?.text()?.trim()?.take(4)?.toIntOrNull()
        val scoreValue = this.selectFirst(".r")?.text()?.trim()?.toFloatOrNull()

        val isAnime = href.contains("/anime")
        val isSerie = href.contains("/series") || href.contains("/doramas")

        return when {
            isAnime -> newAnimeSearchResponse(cleanTitle, href, TvType.Anime) {
                this.posterUrl = poster
                this.year = year
                if (scoreValue != null) this.score = Score.from10(scoreValue)
            }
            isSerie -> newTvSeriesSearchResponse(cleanTitle, href, TvType.TvSeries) {
                this.posterUrl = poster
                this.year = year
                if (scoreValue != null) this.score = Score.from10(scoreValue)
            }
            else -> newMovieSearchResponse(cleanTitle, href, TvType.Movie) {
                this.posterUrl = poster
                this.year = year
                if (scoreValue != null) this.score = Score.from10(scoreValue)
            }
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        if (query.length < 2) return emptyList()
        return try {
            val enc = URLEncoder.encode(query.trim(), "UTF-8")
            val document = app.get("$mainUrl/pesquisar/$enc").document
            document.select("a.gPoster").mapNotNull {
                try {
                    it.toSearchResult()
                } catch (e: Exception) {
                    null
                }
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    // ---------- TMDB (id do conteúdo + temporadas) ----------

    private suspend fun searchTmdbId(title: String, year: Int?, isTv: Boolean): Int? {
        return try {
            val type = if (isTv) "tv" else "movie"
            val yearParam = if (isTv) {
                year?.let { "&first_air_date_year=$it" } ?: ""
            } else {
                year?.let { "&year=$it" } ?: ""
            }
            val enc = URLEncoder.encode(title, "UTF-8")
            val url = "https://api.themoviedb.org/3/search/$type?api_key=${BuildConfig.TMDB_API_KEY}&language=pt-BR&query=$enc$yearParam"
            val json = JSONObject(app.get(url).text)
            val results = json.optJSONArray("results") ?: return null
            if (results.length() == 0) return null
            results.getJSONObject(0).optInt("id").takeIf { it > 0 }
        } catch (e: Exception) {
            null
        }
    }

    private suspend fun tmdbSeasonEpisodes(tmdbTvId: Int): Map<Int, Int> {
        return try {
            val url = "https://api.themoviedb.org/3/tv/$tmdbTvId?api_key=${BuildConfig.TMDB_API_KEY}&language=pt-BR"
            val json = JSONObject(app.get(url).text)
            val seasons = json.optJSONArray("seasons") ?: return emptyMap()
            val out = mutableMapOf<Int, Int>()
            for (i in 0 until seasons.length()) {
                val s = seasons.getJSONObject(i)
                val num = s.optInt("season_number", 0)
                val count = s.optInt("episode_count", 0)
                if (num >= 1 && count > 0) out[num] = count
            }
            out
        } catch (e: Exception) {
            emptyMap()
        }
    }

    // ---------- load ----------

    override suspend fun load(url: String): LoadResponse? {
        return try {
            val document = app.get(url).document

            val h1 = document.selectFirst("h1")?.text()?.trim()
            var title = document.selectFirst("h2.two")?.text()?.trim()
            if (title.isNullOrBlank()) title = h1
            if (title.isNullOrBlank()) return null
            val cleanTitle = title
                .replace(Regex("^Assistir\\s+", RegexOption.IGNORE_CASE), "")
                .replace(Regex("\\s+Online.*$", RegexOption.IGNORE_CASE), "")
                .trim()
                .ifBlank { title }

            val year = document.selectFirst(".infos .year")?.text()?.trim()?.take(4)?.toIntOrNull()

            var poster = document.selectFirst("img.stupidPoster")?.attr("src")?.ifBlank { null }
            var synopsis = document.selectFirst("p.lead")?.text()?.trim()
            if (!synopsis.isNullOrBlank()) {
                synopsis = synopsis
                    .replace(Regex("^<strong>[^<]*</strong>\\s*"), "")
                    .replace(Regex("no Pobreflix\\.?\\s*", RegexOption.IGNORE_CASE), "")
                    .replace(Regex("Ver\\s+.*?\\s+online gr[áa]tis em HD, legendado ou dublado\\.?", RegexOption.IGNORE_CASE), "")
                    .replace(Regex("^Assistir\\s+\\S+(\\s+\\S+)*?\\s+online\\s*", RegexOption.IGNORE_CASE), "")
                    .trim()
                    .ifBlank { null }
            }

            val recommendations = document.select("a.gPoster").mapNotNull {
                try {
                    it.toSearchResult()
                } catch (e: Exception) {
                    null
                }
            }.distinctBy { it.url }

            val isAnime = url.contains("/anime")
            val isSerie = url.contains("/series") || url.contains("/doramas")

            val tmdbId = searchTmdbId(cleanTitle, year, isSerie)

            if (!isSerie) {
                return newMovieLoadResponse(cleanTitle, url, TvType.Movie, "${tmdbId ?: 0}|movie") {
                    this.posterUrl = poster
                    this.year = year
                    this.plot = synopsis
                    this.recommendations = recommendations.takeIf { it.isNotEmpty() }
                }
            }

            val episodes = mutableListOf<Episode>()
            if (tmdbId != null) {
                tmdbSeasonEpisodes(tmdbId).forEach { (seasonNum, count) ->
                    for (epNum in 1..count) {
                        episodes.add(newEpisode("$tmdbId|$seasonNum|$epNum") {
                            this.name = "T$seasonNum E$epNum"
                            this.season = seasonNum
                            this.episode = epNum
                        })
                    }
                }
            }

            val type = if (isAnime) TvType.Anime else TvType.TvSeries
            newTvSeriesLoadResponse(cleanTitle, url, type, episodes) {
                this.posterUrl = poster
                this.year = year
                this.plot = synopsis
                this.recommendations = recommendations.takeIf { it.isNotEmpty() }
            }
        } catch (e: Exception) {
            null
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        return try {
            val parts = data.split("|")
            when {
                parts.size == 2 && parts[1] == "movie" -> {
                    val tmdbId = parts[0].toIntOrNull() ?: return false
                    val streams = PobreFlixExtractor.getStreams(tmdbId, "filme", 1, 1)
                    streams.forEach { callback(it) }
                    streams.isNotEmpty()
                }
                parts.size == 3 -> {
                    val tmdbId = parts[0].toIntOrNull() ?: return false
                    val season = parts[1].toIntOrNull() ?: 1
                    val episode = parts[2].toIntOrNull() ?: 1
                    val streams = PobreFlixExtractor.getStreams(tmdbId, "serie", season, episode)
                    streams.forEach { callback(it) }
                    streams.isNotEmpty()
                }
                else -> false
            }
        } catch (e: Exception) {
            false
        }
    }
}
