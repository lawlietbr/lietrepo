package com.AniTubeDesenhos

import android.content.Context
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Element
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
        "$mainUrl/categoria/desenhos" to "Desenhos (CN / Disney / Clássicos)",
        "$mainUrl/categoria/animes-dublados" to "Animes Dublados",
        "$mainUrl/categoria/filmes" to "Filmes",
        "$mainUrl/categoria/animes-legendados" to "Animes Legendados",
        "$mainUrl/categoria/tokusatsus" to "Tokusatsus",
        "$mainUrl/" to "Últimos Episódios"
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

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val base = request.data.trimEnd('/')
        val url = if (page > 1) "$base/page/$page" else request.data

        val doc = try {
            app.get(url, headers = defaultHeaders()).document
        } catch (e: Exception) {
            if (page > 1) return newHomePageResponse(request.name, emptyList(), false)
            throw e
        }

        val items = mutableListOf<SearchResponse>()

        // Cards visuais (.aniItem) na home ou páginas com capa
        val cardElements = doc.select(".aniItem, .epiItem")
        for (card in cardElements) {
            card.toCardSearchResult(request.name)?.let { items.add(it) }
        }

        // Lista alfabética (.list_animes li a) usada em /categoria/desenhos, /categoria/filmes, etc.
        if (items.isEmpty()) {
            val listLinks = doc.select(".list_animes li a")
            for (link in listLinks) {
                val href = fixUrl(link.attr("href"))
                if (href.isBlank()) continue
                val title = cleanTitle(link.text().ifBlank { link.attr("title") })
                if (title.isBlank()) continue

                val tvType = when {
                    request.name.contains("Filmes", ignoreCase = true) -> TvType.Movie
                    request.name.contains("Desenhos", ignoreCase = true) -> TvType.Cartoon
                    else -> TvType.Anime
                }

                items.add(
                    newAnimeSearchResponse(title, href, tvType) {
                        this.posterUrl = "$mainUrl/wp-content/themes/anitube_sarthemes/assets/img/logo.png"
                    }
                )
            }
        }

        val hasNext = doc.select("a.next, .pagination a[href*='/page/${page + 1}'], a[href*='/page/${page + 1}']").isNotEmpty()
        return newHomePageResponse(request.name, items.distinctBy { it.url }, hasNext)
    }

    private fun Element.toCardSearchResult(sectionName: String): SearchResponse? {
        val link = selectFirst("a") ?: return null
        val href = fixUrl(link.attr("href"))
        if (href.isBlank()) return null

        val rawTitle = selectFirst(".aniItemNome, .epiItemNome")?.text()?.trim()
            ?.ifBlank { link.attr("title") }
            ?: link.attr("title").ifBlank { link.text() }
        val title = cleanTitle(rawTitle)
        if (title.isBlank()) return null

        val img = selectFirst("img")
        val poster = img?.let {
            val src = it.attr("data-src").ifBlank { it.attr("src") }
            if (src.isNotBlank()) fixUrl(src) else null
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
        val encoded = URLEncoder.encode(query, "UTF-8")
        val doc = app.get("$mainUrl/?s=$encoded", headers = defaultHeaders()).document

        return doc.select(".aniItem, .epiItem")
            .mapNotNull { it.toCardSearchResult("Busca") }
            .distinctBy { it.url }
    }

    override suspend fun load(url: String): LoadResponse? {
        val doc = app.get(url, headers = defaultHeaders()).document

        val episodeLinks = doc.select(".AniPageContainerLista a[href]")
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
            val iframeUrl = if (rawSrc.startsWith("//")) "https:$rawSrc" else rawSrc
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
