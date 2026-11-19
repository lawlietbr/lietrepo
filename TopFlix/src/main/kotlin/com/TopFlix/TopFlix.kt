package com.TopFlix

import android.content.Context
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addActors
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
import com.lagradost.cloudstream3.utils.*
import org.json.JSONArray
import org.json.JSONObject
import org.jsoup.nodes.Element
import java.net.URLEncoder

@CloudstreamPlugin
class TopFlixPlugin : Plugin() {
    override fun load(context: Context) {
        registerMainAPI(TopFlix())
    }
}

class TopFlix : MainAPI() {
    override var mainUrl = "https://topflix.actor"
    override var name = "TopFlix"
    override val hasMainPage = true
    override var lang = "pt-br"
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)
    override val usesWebView = false

    @Volatile
    private var cachedCdnUrl: String? = null

    companion object {
        private const val DEFAULT_CDN_URL = "https://topflixfilm.live"
        private const val USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"
    }

    override val mainPage = mainPageOf(
        "$mainUrl/filmes/" to "Filmes",
        "$mainUrl/series/" to "Séries",
        "$mainUrl/genero/acao/" to "Ação",
        "$mainUrl/genero/aventura/" to "Aventura",
        "$mainUrl/genero/comedia/" to "Comédia",
        "$mainUrl/genero/drama/" to "Drama",
        "$mainUrl/genero/ficcao-cientifica/" to "Ficção Científica",
        "$mainUrl/genero/romance/" to "Romance",
        "$mainUrl/genero/suspense/" to "Suspense",
        "$mainUrl/genero/terror/" to "Terror"
    )

    private fun defaultHeaders(): Map<String, String> = mapOf(
        "User-Agent" to USER_AGENT,
        "Referer" to "$mainUrl/"
    )

    private suspend fun getCdnUrl(): String {
        cachedCdnUrl?.let { return it }
        return try {
            val js = app.get("$mainUrl/templates/common/localcdn.js", headers = defaultHeaders()).text
            val match = Regex("""cdnUrl\s*=\s*["']([^"']+)["']""").find(js)
            val resolved = match?.groupValues?.get(1)?.trimEnd('/')?.takeIf { it.startsWith("http") }
                ?: DEFAULT_CDN_URL
            cachedCdnUrl = resolved
            resolved
        } catch (_: Exception) {
            DEFAULT_CDN_URL
        }
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val base = request.data.trimEnd('/')
        val url = if (page > 1) "$base/page/$page/" else "$base/"

        val document = try {
            app.get(url, headers = defaultHeaders()).document
        } catch (e: Exception) {
            if (page > 1) return newHomePageResponse(request.name, emptyList(), false)
            throw e
        }

        val items = document.select("div.poster.grid-item, div.shrtstry")
            .mapNotNull { it.toSearchResult() }
            .distinctBy { it.url }

        val hasNextPage = document.select("a[href*='/page/${page + 1}/']").isNotEmpty()
        return newHomePageResponse(request.name, items, hasNextPage)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        if (query.isBlank()) return emptyList()

        val document = try {
            app.post(
                url = "$mainUrl/",
                data = mapOf(
                    "do" to "search",
                    "subaction" to "search",
                    "story" to query
                ),
                referer = "$mainUrl/",
                headers = mapOf(
                    "User-Agent" to USER_AGENT,
                    "Origin" to mainUrl,
                    "Content-Type" to "application/x-www-form-urlencoded"
                )
            ).document
        } catch (_: Exception) {
            val encoded = URLEncoder.encode(query, "UTF-8")
            app.get("$mainUrl/?do=search&subaction=search&story=$encoded", headers = defaultHeaders()).document
        }

        return document.select("div.poster.grid-item, div.shrtstry")
            .mapNotNull { it.toSearchResult() }
            .distinctBy { it.url }
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val linkEl = selectFirst("h3.poster__title a") ?: selectFirst("a[href*='assistir-online-']") ?: return null
        val href = fixUrl(linkEl.attr("href"))
        if (href.isBlank()) return null

        val rawTitle = linkEl.text().trim()
            .ifBlank { selectFirst(".poster__img img")?.attr("alt")?.trim() ?: "" }
        if (rawTitle.isBlank()) return null

        val poster = selectFirst(".poster__img img")?.let { img ->
            val src = img.attr("src").ifBlank { img.attr("data-src") }
            if (src.isNotBlank()) fixUrl(src) else null
        }

        val year = select(".bslide__meta span")
            .mapNotNull { it.text().trim().takeIf { t -> t.matches(Regex("\\d{4}")) }?.toIntOrNull() }
            .firstOrNull()

        val rating = selectFirst(".bslide__meta .rating")?.text()?.trim()?.replace(",", ".")?.toFloatOrNull()

        val catsText = select(".onslide-cats a").text().lowercase()
        val isSeries = href.contains("/series/") || catsText.contains("séries") || catsText.contains("series")

        return if (isSeries) {
            newTvSeriesSearchResponse(rawTitle, href, TvType.TvSeries) {
                this.posterUrl = poster
                this.year = year
                rating?.let { this.score = Score.from10(it) }
            }
        } else {
            newMovieSearchResponse(rawTitle, href, TvType.Movie) {
                this.posterUrl = poster
                this.year = year
                rating?.let { this.score = Score.from10(it) }
            }
        }
    }

    private fun cleanPageTitle(raw: String): String {
        return raw
            .replace(Regex("""^Assistir\s+""", RegexOption.IGNORE_CASE), "")
            .replace(Regex("""\s+Online\s+Gr[áa]tis$""", RegexOption.IGNORE_CASE), "")
            .replace(Regex("""\s+Online$""", RegexOption.IGNORE_CASE), "")
            .trim()
    }

    private suspend fun fetchPlayerJson(
        cdnUrl: String,
        itemId: String,
        version: Int,
        season: Int,
        episode: Int
    ): JSONObject? {
        return try {
            val apiUrl = "$cdnUrl/json?id=$itemId&version=$version&season=$season&series=$episode&a=false&android=0"
            val text = app.get(apiUrl, headers = defaultHeaders()).text
            if (text.isBlank()) null else JSONObject(text)
        } catch (_: Exception) {
            null
        }
    }

    private fun parseSeasonEpisodePairs(root: JSONObject): List<Pair<Int, Int>> {
        val pairs = linkedSetOf<Pair<Int, Int>>()
        val jsonArr = root.optJSONArray("json") ?: return emptyList()

        for (i in 0 until jsonArr.length()) {
            val block = jsonArr.optJSONObject(i) ?: continue
            val tracks = block.optJSONArray("folder") ?: continue
            for (t in 0 until tracks.length()) {
                val trackObj = tracks.optJSONObject(t) ?: continue
                val seasons = trackObj.optJSONArray("folder") ?: continue
                for (s in 0 until seasons.length()) {
                    val seasonObj = seasons.optJSONObject(s) ?: continue
                    val seasonTitle = seasonObj.optString("title")
                    val seasonNum = Regex("""(\d+)""").find(seasonTitle)?.groupValues?.get(1)?.toIntOrNull() ?: (s + 1)
                    val episodes = seasonObj.optJSONArray("folder") ?: continue
                    for (e in 0 until episodes.length()) {
                        val epObj = episodes.optJSONObject(e) ?: continue
                        val epTitle = epObj.optString("title")
                        val epNum = Regex("""(\d+)""").find(epTitle)?.groupValues?.get(1)?.toIntOrNull() ?: (e + 1)
                        pairs.add(seasonNum to epNum)
                    }
                }
            }
        }
        return pairs.sortedWith(compareBy({ it.first }, { it.second }))
    }

    private fun parseTrackTitles(root: JSONObject): List<String> {
        val titles = mutableListOf<String>()
        val jsonArr = root.optJSONArray("json") ?: return titles
        for (i in 0 until jsonArr.length()) {
            val block = jsonArr.optJSONObject(i) ?: continue
            val tracks = block.optJSONArray("folder") ?: continue
            for (t in 0 until tracks.length()) {
                val title = tracks.optJSONObject(t)?.optString("title")?.trim().orEmpty()
                if (title.isNotBlank()) titles.add(title)
            }
        }
        return titles
    }

    override suspend fun load(url: String): LoadResponse? {
        val response = app.get(url, headers = defaultHeaders())
        val html = response.text
        val doc = response.document

        val itemId = Regex("""itemId\s*=\s*["']?(\d+)["']?""").find(html)?.groupValues?.get(1) ?: return null

        val rawTitle = doc.selectFirst(".movieinfo h1, h1")?.text()?.trim() ?: return null
        val title = cleanPageTitle(rawTitle)

        val poster = doc.selectFirst(".movieposter img")?.attr("src")?.takeIf { it.isNotBlank() }?.let { fixUrl(it) }
        val plot = doc.selectFirst(".movie-description")?.text()?.trim()
        val year = doc.selectFirst(".yearof")?.text()?.let { Regex("""(\d{4})""").find(it)?.groupValues?.get(1)?.toIntOrNull() }
        val duration = doc.selectFirst(".movie__meta .tohr")?.text()?.trim()?.toIntOrNull()
        val rating = doc.selectFirst(".movie__meta .rating")?.text()?.trim()?.replace(",", ".")?.toFloatOrNull()

        val tags = doc.select(".movieinfo .onslide-cats a")
            .map { it.text().trim() }
            .filter { it.isNotBlank() && !it.equals("Filmes", ignoreCase = true) && !it.equals("Séries", ignoreCase = true) }

        val actors = doc.select(".credits .actor_link")
            .mapNotNull { el -> el.text().trim().takeIf { it.isNotBlank() }?.let { Actor(it) } }

        val recommendations = doc.select("div.poster.grid-item, div.shrtstry")
            .mapNotNull { it.toSearchResult() }
            .filter { it.url != url }
            .distinctBy { it.url }
            .take(12)

        val cdnUrl = getCdnUrl()
        val playerJson = fetchPlayerJson(cdnUrl, itemId, version = 0, season = 1, episode = 1)

        val backdrop = playerJson?.optString("background_url")
            ?.takeIf { it.length > 1 }
            ?.let { if (it.startsWith("http")) it else "$cdnUrl$it" }
            ?: poster

        val videoType = playerJson?.optString("video_type").orEmpty()
        val isSeries = videoType.equals("series", ignoreCase = true) || url.contains("/series/")

        return if (isSeries) {
            val episodePairs = playerJson?.let { parseSeasonEpisodePairs(it) }.orEmpty()
            val episodes = if (episodePairs.isNotEmpty()) {
                episodePairs.map { (seasonNum, epNum) ->
                    newEpisode("$itemId|series|$seasonNum|$epNum") {
                        this.name = "Episódio $epNum"
                        this.season = seasonNum
                        this.episode = epNum
                    }
                }
            } else {
                listOf(
                    newEpisode("$itemId|series|1|1") {
                        this.name = "Episódio 1"
                        this.season = 1
                        this.episode = 1
                    }
                )
            }

            newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
                this.posterUrl = poster
                this.backgroundPosterUrl = backdrop
                this.plot = plot
                this.year = year
                this.tags = tags.takeIf { it.isNotEmpty() }
                this.duration = duration
                rating?.let { this.score = Score.from10(it) }
                this.recommendations = recommendations.takeIf { it.isNotEmpty() }
                if (actors.isNotEmpty()) addActors(actors.map { it to null })
            }
        } else {
            newMovieLoadResponse(title, url, TvType.Movie, "$itemId|movie|1|1") {
                this.posterUrl = poster
                this.backgroundPosterUrl = backdrop
                this.plot = plot
                this.year = year
                this.tags = tags.takeIf { it.isNotEmpty() }
                this.duration = duration
                rating?.let { this.score = Score.from10(it) }
                this.recommendations = recommendations.takeIf { it.isNotEmpty() }
                if (actors.isNotEmpty()) addActors(actors.map { it to null })
            }
        }
    }

    private fun inferQualityFromUrl(url: String): Int {
        val lower = url.lowercase()
        return when {
            lower.contains("2160p") || lower.contains("4k") -> Qualities.P2160.value
            lower.contains("1080p") -> Qualities.P1080.value
            lower.contains("720p") -> Qualities.P720.value
            lower.contains("480p") -> Qualities.P480.value
            lower.contains("360p") -> Qualities.P360.value
            else -> Qualities.Unknown.value
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val parts = data.split("|")
        if (parts.size < 4) return false

        val itemId = parts[0]
        val season = parts[2].toIntOrNull() ?: 1
        val episode = parts[3].toIntOrNull() ?: 1

        val cdnUrl = getCdnUrl()
        val firstJson = fetchPlayerJson(cdnUrl, itemId, version = 0, season = season, episode = episode) ?: return false

        val tracks = parseTrackTitles(firstJson).ifEmpty { listOf("Dublado") }
        val emittedUrls = mutableSetOf<String>()
        val emittedSubs = mutableSetOf<String>()
        var foundAny = false

        for (index in tracks.indices) {
            val trackTitle = tracks[index]
            val json = if (index == 0) {
                firstJson
            } else {
                fetchPlayerJson(cdnUrl, itemId, version = index, season = season, episode = episode) ?: continue
            }

            val rawSubs = json.optString("video_subs").trim()
            if (rawSubs.length > 1) {
                val fullSubs = if (rawSubs.startsWith("http")) rawSubs else "$cdnUrl$rawSubs"
                if (emittedSubs.add(fullSubs)) {
                    subtitleCallback(SubtitleFile("Português ($trackTitle)", fullSubs))
                }
            }

            val rawVideo = json.optString("video_url").trim()
            if (rawVideo.isBlank()) continue

            val fullVideoUrl = if (rawVideo.startsWith("http")) rawVideo else "$cdnUrl$rawVideo"
            if (!emittedUrls.add(fullVideoUrl)) continue

            val videoFormat = json.optString("video_format").lowercase()
            val isHls = fullVideoUrl.contains(".m3u8", ignoreCase = true) || videoFormat.contains("mpegurl")
            val sourceLabel = "$name - $trackTitle"
            val streamHeaders = mapOf(
                "Referer" to "$mainUrl/",
                "User-Agent" to USER_AGENT
            )

            if (isHls) {
                try {
                    val m3u8Links = M3u8Helper.generateM3u8(
                        source = sourceLabel,
                        streamUrl = fullVideoUrl,
                        referer = "$mainUrl/",
                        headers = streamHeaders
                    )
                    if (m3u8Links.isNotEmpty()) {
                        m3u8Links.forEach(callback)
                        foundAny = true
                        continue
                    }
                } catch (_: Exception) {
                    // Fallback to direct M3U8 link below
                }

                callback(
                    newExtractorLink(
                        source = sourceLabel,
                        name = sourceLabel,
                        url = fullVideoUrl,
                        type = ExtractorLinkType.M3U8
                    ) {
                        this.referer = "$mainUrl/"
                        this.quality = inferQualityFromUrl(fullVideoUrl)
                        this.headers = streamHeaders
                    }
                )
                foundAny = true
            } else {
                callback(
                    newExtractorLink(
                        source = sourceLabel,
                        name = sourceLabel,
                        url = fullVideoUrl,
                        type = ExtractorLinkType.VIDEO
                    ) {
                        this.referer = "$mainUrl/"
                        this.quality = inferQualityFromUrl(fullVideoUrl)
                        this.headers = streamHeaders
                    }
                )
                foundAny = true
            }
        }

        return foundAny
    }
}
