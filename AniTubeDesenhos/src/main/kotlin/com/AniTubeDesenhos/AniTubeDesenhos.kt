package com.AniTubeDesenhos

import android.content.Context
import android.util.Base64
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Element
import java.net.URLDecoder
import java.net.URLEncoder

@CloudstreamPlugin
class AniTubeDesenhosPlugin : Plugin() {
    override fun load(context: Context) {
        registerMainAPI(AniTubeDesenhos())
    }
}

class AniTubeDesenhos : MainAPI() {
    override var mainUrl = "https://www.anitube.biz"
    override var name = "AniTube Desenhos"
    override val hasMainPage = true
    override var lang = "pt-br"
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(TvType.Cartoon, TvType.Anime, TvType.TvSeries, TvType.Movie)
    override val usesWebView = false

    companion object {
        private const val USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"
    }

    override val mainPage = mainPageOf(
        "$mainUrl/?s=todos+os+episodios+desenho" to "Desenhos",
        "$mainUrl/?s=dublado+todos+os+episodios" to "Dublados",
        "$mainUrl/?s=filme+dublado" to "Filmes Dublados",
        "$mainUrl/" to "Recentes"
    )

    private fun defaultHeaders(referer: String = "$mainUrl/"): Map<String, String> = mapOf(
        "User-Agent" to USER_AGENT,
        "Referer" to referer
    )

    private fun cleanTitle(raw: String): String {
        return raw
            .replace(Regex("""^Anitube\s*-\s*""", RegexOption.IGNORE_CASE), "")
            .replace(Regex("""^Assistir\s+""", RegexOption.IGNORE_CASE), "")
            .replace(Regex("""\s*[-–]\s*Todos os Epis[óo]dios.*$""", RegexOption.IGNORE_CASE), "")
            .replace(Regex("""\s*[-–]\s*Assistir Online.*$""", RegexOption.IGNORE_CASE), "")
            .replace(Regex("""\s+Online em HD$""", RegexOption.IGNORE_CASE), "")
            .replace(Regex("""\s+Online$""", RegexOption.IGNORE_CASE), "")
            .trim()
    }

    private fun cleanPosterUrl(rawSrc: String): String? {
        val trimmed = rawSrc.trim()
        if (trimmed.isBlank()) return null
        val secondHttp = trimmed.indexOf("http", startIndex = 5)
        val cleaned = if (secondHttp > 0) trimmed.substring(secondHttp) else trimmed
        return fixUrl(cleaned)
    }

    private fun isSingleEpisodeTitle(rawTitle: String): Boolean {
        return Regex("""Epis[óo]dio\s*\d+""", RegexOption.IGNORE_CASE).containsMatchIn(rawTitle)
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = if (page > 1) {
            if (request.data.contains("?s=")) {
                val queryPart = request.data.substringAfter("?s=")
                "$mainUrl/page/$page/?s=$queryPart"
            } else {
                "${request.data.trimEnd('/')}/page/$page"
            }
        } else {
            request.data
        }

        val doc = try {
            app.get(url, headers = defaultHeaders()).document
        } catch (e: Exception) {
            if (page > 1) return newHomePageResponse(request.name, emptyList(), false)
            throw e
        }

        val items = doc.select(".aniItem")
            .mapNotNull { it.toCardSearchResult(request.name, filterEpisodes = true) }
            .distinctBy { it.url }

        val hasNext = doc.select("a.next, .pagination a[href*='/page/${page + 1}'], a[href*='/page/${page + 1}']").isNotEmpty()
        return newHomePageResponse(request.name, items, hasNext)
    }

    private fun Element.toCardSearchResult(sectionName: String, filterEpisodes: Boolean = false): SearchResponse? {
        val link = selectFirst("a") ?: return null
        val href = fixUrl(link.attr("href"))
        if (href.isBlank()) return null

        val rawTitle = selectFirst(".aniItemNome, .epiItemNome")?.text()?.trim()
            ?.ifBlank { link.attr("title") }
            ?: link.attr("title").ifBlank { link.text() }
        if (filterEpisodes && isSingleEpisodeTitle(rawTitle)) return null

        val title = cleanTitle(rawTitle)
        if (title.isBlank()) return null

        val img = selectFirst("img")
        val poster = img?.let {
            val src = it.attr("data-src").ifBlank { it.attr("src") }
            cleanPosterUrl(src)
        }

        val tvType = when {
            sectionName.contains("Filmes", ignoreCase = true) -> TvType.Movie
            sectionName.contains("Desenhos", ignoreCase = true) -> TvType.Cartoon
            else -> TvType.Anime
        }

        return newAnimeSearchResponse(title, href, tvType) {
            this.posterUrl = poster
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        if (query.isBlank()) return emptyList()

        // 1. Prioriza páginas agrupadas de séries/desenhos ("Todos os Episódios")
        val seriesEncoded = URLEncoder.encode("$query todos os episodios", "UTF-8")
        val seriesDoc = app.get("$mainUrl/?s=$seriesEncoded", headers = defaultHeaders()).document
        val seriesResults = seriesDoc.select(".aniItem, .epiItem")
            .mapNotNull { it.toCardSearchResult("Busca", filterEpisodes = true) }
            .distinctBy { it.url }

        if (seriesResults.isNotEmpty()) return seriesResults

        // 2. Fallback para filmes ou títulos sem "Todos os Episódios" no nome (ainda filtrando episódios soltos)
        val rawEncoded = URLEncoder.encode(query, "UTF-8")
        val rawDoc = app.get("$mainUrl/?s=$rawEncoded", headers = defaultHeaders()).document
        val filtered = rawDoc.select(".aniItem, .epiItem")
            .mapNotNull { it.toCardSearchResult("Busca", filterEpisodes = true) }
            .distinctBy { it.url }

        return filtered.ifEmpty {
            rawDoc.select(".aniItem, .epiItem")
                .mapNotNull { it.toCardSearchResult("Busca", filterEpisodes = false) }
                .distinctBy { it.url }
        }
    }

    override suspend fun load(url: String): LoadResponse? {
        val doc = app.get(url, headers = defaultHeaders()).document

        val episodeLinks = doc.select(".AniPageContainerLista a[href]")
            .filterNot { it.text().contains("Todos os Epis", ignoreCase = true) }
        val rawTitle = doc.selectFirst(".AniPageContainerTitulo")?.text()
            ?: doc.selectFirst("meta[itemprop=name]")?.attr("content")
            ?: doc.selectFirst("h1")?.text()
            ?: doc.title()
        val title = cleanTitle(rawTitle)

        val poster = doc.selectFirst("#capaAnime img")?.attr("src")?.takeIf { it.isNotBlank() }?.let { fixUrl(it) }
            ?: doc.selectFirst("meta[itemprop=thumbnailUrl]")?.attr("content")?.takeIf { it.isNotBlank() }?.let { fixUrl(it) }
            ?: doc.selectFirst("meta[property=og:image]")?.attr("content")?.takeIf { it.isNotBlank() }?.let { fixUrl(it) }

        val genres = doc.select(".boxAnimeSobreLinha")
            .firstOrNull { it.text().contains("Genero", ignoreCase = true) || it.text().contains("Gênero", ignoreCase = true) }
            ?.text()
            ?.substringAfter(":")
            ?.split(",")
            ?.map { it.trim() }
            ?.filter { it.isNotBlank() }

        val plot = doc.selectFirst("#sinopse2, .sinopse, span[itemprop=description]")?.text()?.trim()

        if (episodeLinks.isNotEmpty()) {
            val episodes = episodeLinks.mapIndexed { idx, a ->
                val epUrl = fixUrl(a.attr("href"))
                val epText = a.text().trim()
                val epNum = Regex("""Epis[óo]dio\s*(\d+)""", RegexOption.IGNORE_CASE)
                    .find(epText)?.groupValues?.get(1)?.toIntOrNull()
                    ?: (idx + 1)
                val seasonNum = Regex("""(\d+)[ªa]\s*Temporada""", RegexOption.IGNORE_CASE)
                    .find(epText)?.groupValues?.get(1)?.toIntOrNull()
                    ?: 1

                newEpisode(epUrl) {
                    this.name = epText.ifBlank { "Episódio $epNum" }
                    this.season = seasonNum
                    this.episode = epNum
                }
            }

            val isCartoon = doc.text().contains("Formato: Desenho", ignoreCase = true) ||
                url.contains("/desenhos", ignoreCase = true)

            return newTvSeriesLoadResponse(
                title,
                url,
                if (isCartoon) TvType.Cartoon else TvType.Anime,
                episodes
            ) {
                this.posterUrl = poster
                this.plot = plot
                this.tags = genres
            }
        }

        // Página de episódio único ou filme (ex.: clicado direto da busca)
        return newMovieLoadResponse(title, url, TvType.Movie, url) {
            this.posterUrl = poster
            this.plot = plot
            this.tags = genres
        }
    }

    private suspend fun extractBloggerStreams(
        bloggerUrl: String,
        referer: String,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        return try {
            val html = app.get(bloggerUrl, headers = defaultHeaders(referer)).text
            val unescaped = html
                .replace("\\u003d", "=")
                .replace("\\u0026", "&")
                .replace("&quot;", "\"")
                .replace("\\/", "/")

            val streamRegex = Regex("""https://[^"'\s<>\\]*googlevideo\.com/videoplayback[^"'\s<>\\]+""")
            val urls = streamRegex.findAll(unescaped).map { it.value }.distinct().toList()
            if (urls.isEmpty()) return false

            urls.forEachIndexed { idx, videoUrl ->
                val quality = when {
                    videoUrl.contains("itag=22") -> Qualities.P720.value
                    videoUrl.contains("itag=37") -> Qualities.P1080.value
                    videoUrl.contains("itag=18") -> Qualities.P360.value
                    else -> Qualities.Unknown.value
                }
                val label = if (urls.size > 1) "$name - Google ${idx + 1}" else "$name - Google"
                callback(
                    newExtractorLink(
                        source = label,
                        name = label,
                        url = videoUrl,
                        type = ExtractorLinkType.VIDEO
                    ) {
                        this.referer = "https://www.blogger.com/"
                        this.quality = quality
                        this.headers = mapOf("User-Agent" to USER_AGENT)
                    }
                )
            }
            true
        } catch (_: Exception) {
            false
        }
    }

    private fun decodeBgsrcToBloggerUrl(rawSrc: String): String? {
        return try {
            val bgParam = rawSrc.substringAfter("bgsrc=", "").substringBefore("&")
            if (bgParam.isBlank()) return null
            val decodedParam = URLDecoder.decode(bgParam, "UTF-8")
            val b64 = Regex("""anitube\.biz/([A-Za-z0-9+/=_-]+)/""").find(decodedParam)?.groupValues?.get(1)
                ?: return null
            val padded = b64 + "=".repeat((4 - b64.length % 4) % 4)
            val token = String(Base64.decode(padded, Base64.DEFAULT), Charsets.UTF_8).trim()
            if (token.startsWith("http")) token else "https://www.blogger.com/video.g?token=$token"
        } catch (_: Exception) {
            null
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val doc = try {
            app.get(data, headers = defaultHeaders()).document
        } catch (_: Exception) {
            return false
        }

        val x2Link = doc.selectFirst("a[href*='x2episodio']")?.attr("href")?.let { fixUrl(it) }
        val playerResp = if (x2Link != null) {
            app.get(x2Link, headers = defaultHeaders(data))
        } else {
            null
        }

        val playerDoc = playerResp?.document ?: doc
        val playerReferer = playerResp?.url ?: data

        var foundAny = false
        val iframes = playerDoc.select("iframe[src]")
            .map { it.attr("src").trim() }
            .filter { it.isNotBlank() }
            .distinct()

        for (rawSrc in iframes) {
            val iframeUrl = when {
                rawSrc.startsWith("//") -> "https:$rawSrc"
                rawSrc.contains("bgsrc=", ignoreCase = true) -> decodeBgsrcToBloggerUrl(rawSrc) ?: rawSrc
                else -> rawSrc
            }
            when {
                iframeUrl.contains("blogger.com/video.g", ignoreCase = true) -> {
                    if (extractBloggerStreams(iframeUrl, playerReferer, callback)) {
                        foundAny = true
                    }
                }
                iframeUrl.startsWith("http") -> {
                    try {
                        if (loadExtractor(iframeUrl, playerReferer, subtitleCallback, callback)) {
                            foundAny = true
                        }
                    } catch (_: Exception) {}
                }
            }
        }

        // Fallback para <video><source src="...">
        if (!foundAny) {
            val videoSrc = playerDoc.selectFirst("video source[src], video[src]")?.attr("src")?.trim()
            if (!videoSrc.isNullOrBlank() && videoSrc.startsWith("http")) {
                val isHls = videoSrc.contains(".m3u8", ignoreCase = true)
                callback(
                    newExtractorLink(
                        source = name,
                        name = name,
                        url = videoSrc,
                        type = if (isHls) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                    ) {
                        this.referer = playerReferer
                        this.headers = mapOf("User-Agent" to USER_AGENT)
                    }
                )
                foundAny = true
            }
        }

        return foundAny
    }
}
