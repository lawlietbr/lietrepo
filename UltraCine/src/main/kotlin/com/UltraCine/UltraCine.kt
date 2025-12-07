package com.UltraCine

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addActors
import com.lagradost.cloudstream3.LoadResponse.Companion.addTrailer
import com.lagradost.cloudstream3.network.WebViewResolver
import org.jsoup.nodes.Element
import kotlinx.coroutines.delay

class UltraCine : MainAPI() {
    override var mainUrl = "https://ultracine.org"
    override var name = "UltraCine"
    override val hasMainPage = true
    override var lang = "pt-br"
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)

    override val mainPage = mainPageOf(
        "$mainUrl/category/lancamentos/" to "Lançamentos",
        "$mainUrl/category/acao/" to "Ação",
        "$mainUrl/category/animacao/" to "Animação",
        "$mainUrl/category/comedia/" to "Comédia",
        "$mainUrl/category/crime/" to "Crime",
        "$mainUrl/category/documentario/" to "Documentário",
        "$mainUrl/category/drama/" to "Drama",
        "$mainUrl/category/familia/" to "Família",
        "$mainUrl/category/fantasia/" to "Fantasia",
        "$mainUrl/category/ficcao-cientifica/" to "Ficção Científica",
        "$mainUrl/category/guerra/" to "Guerra",
        "$mainUrl/category/kids/" to "Kids",
        "$mainUrl/category/misterio/" to "Misterio",
        "$mainUrl/category/romance/" to "Romance",
        "$mainUrl/category/terror/" to "Terror",
        "$mainUrl/category/thriller/" to "Thriller"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = request.data + if (page > 1) "page/$page/" else ""
        val document = app.get(url).document
        val home = document.select("div.aa-cn div#movies-a ul.post-lst li").mapNotNull { it.toSearchResult() }
        return newHomePageResponse(request.name, home)
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val title = selectFirst("header.entry-header h2.entry-title")?.text() ?: return null
        val href = selectFirst("a.lnk-blk")?.attr("href") ?: return null
        val posterUrl = selectFirst("div.post-thumbnail figure img")?.attr("src")?.let { src ->
            if (src.length > 0) fixUrl(src).replace("/w500/", "/original/") else null
        } ?: selectFirst("div.post-thumbnail figure img")?.attr("data-src")?.let { dataSrc ->
            if (dataSrc.length > 0) fixUrl(dataSrc).replace("/w500/", "/original/") else null
        }

        val year = selectFirst("span.year")?.text()?.toIntOrNull()

        return newMovieSearchResponse(title, href, TvType.Movie) {
            this.posterUrl = posterUrl
            this.year = year
            this.quality = getQualityFromString(selectFirst("span.post-ql")?.text())
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val document = app.get("$mainUrl/?s=$query").document
        return document.select("div.aa-cn div#movies-a ul.post-lst li").mapNotNull { it.toSearchResult() }
    }

    override suspend fun load(url: String): LoadResponse? {
        val document = app.get(url).document
        val title = document.selectFirst("aside.fg1 header.entry-header h1.entry-title")?.text() ?: return null
        val poster = document.selectFirst("div.bghd img.TPostBg")?.attr("src")?.let { src ->
            if (src.length > 0) fixUrl(src).replace("/w1280/", "/original/") else null
        } ?: document.selectFirst("div.bghd img.TPostBg")?.attr("data-src")?.let { dataSrc ->
            if (dataSrc.length > 0) fixUrl(dataSrc).replace("/w1280/", "/original/") else null
        }

        val year = document.selectFirst("aside.fg1 header.entry-header div.entry-meta span.year")?.ownText()?.toIntOrNull()
        val durationText = document.selectFirst("aside.fg1 header.entry-header div.entry-meta span.duration")?.ownText()
        val plot = document.selectFirst("aside.fg1 div.description p")?.text()
        val tags = document.select("aside.fg1 header.entry-header div.entry-meta span.genres a").map { it.text() }
        val actors = document.select("aside.fg1 ul.cast-lst p a").map { Actor(it.text(), it.attr("href")) }
        val trailer = document.selectFirst("div.mdl-cn div.video iframe")?.attr("src")?.takeIf { it.length > 0 }
            ?: document.selectFirst("div.mdl-cn div.video iframe")?.attr("data-src")

        val iframeUrl = document.selectFirst("iframe[src*='assistirseriesonline']")?.attr("src")?.takeIf { it.length > 0 }
            ?: document.selectFirst("iframe[src*='assistirseriesonline']")?.attr("data-src")

        val isSerie = url.contains("/serie/")

        return if (isSerie) {
            val episodes = if (iframeUrl != null) {
                try { app.get(iframeUrl).document.let { parseSeriesEpisodes(it) } } catch (e: Exception) { emptyList() }
            } else emptyList()

            newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
                this.posterUrl = poster
                this.year = year
                this.plot = plot
                this.tags = tags
                addActors(actors)
                addTrailer(trailer)
            }
        } else {
            newMovieLoadResponse(title, url, TvType.Movie, iframeUrl ?: "") {
                this.posterUrl = poster
                this.year = year
                this.plot = plot
                this.tags = tags
                this.duration = parseDuration(durationText)
                addActors(actors)
                addTrailer(trailer)
            }
        }
    }

    private fun parseSeriesEpisodes(doc: org.jsoup.nodes.Document): List<Episode> {
        val episodes = mutableListOf<Episode>()
        doc.select("header.header ul.header-navigation li").forEach { seasonEl ->
            val seasonNum = seasonEl.attr("data-season-number").toIntOrNull() ?: return@forEach
            val seasonId = seasonEl.attr("data-season-id")
            doc.select("li[data-season-id='$seasonId']").mapNotNull { epEl ->
                val epId = epEl.attr("data-episode-id")
                if (epId.isBlank()) return@mapNotNull null
                val title = epEl.selectFirst("a")?.text() ?: "Episódio"
                val epNum = title.substringBefore(" - ").toIntOrNull() ?: 1
                newEpisode(epId) {
                    this.name = title.substringAfter(" - ").takeIf { it.isNotEmpty() } ?: title
                    this.season = seasonNum
                    this.episode = epNum
                }
            }.also { episodes.addAll(it) }
        }
        return episodes
    }

    private fun parseDuration(duration: String?): Int? {
        if (duration.isNullOrBlank()) return null
        val regex = Regex("""(\d+)h.*?(\d+)m""")
        val match = regex.find(duration)
        return if (match != null) {
            val h = match.groupValues[1].toIntOrNull() ?: 0
            val m = match.groupValues[2].toIntOrNull() ?: 0
            h * 60 + m
        } else {
            Regex("""(\d+)m""").find(duration)?.groupValues?.get(1)?.toIntOrNull()
        }
    }

    override suspend fun loadLinks(
    data: String,
    isCasting: Boolean,
    subtitleCallback: (SubtitleFile) -> Unit,
    callback: (ExtractorLink) -> Unit
): Boolean {
    if (data.isBlank()) return false

    val isEpisode = data.matches(Regex("^\\d+$")) || data.contains("assistirseriesonline.icu")

    val url = if (data.matches(Regex("^\\d+$"))) {
        "https://assistirseriesonline.icu/episodio/$data"
    } else {
        data
    }

    return try {
        val headers = mapOf("User-Agent" to "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36")
        val res = app.get(url, referer = mainUrl, headers = headers)
        val html = res.text

        if (isEpisode) {
            delay(5000) // 5s pra JS inicial carregar o src no JW Player
        }

        // Regex pro JW Player (da tua screenshot: <video class="jw-video jw-reset" src="https://storage.googleapis.com/...")
        val jwPattern = Regex("""<video[^>]*jw-video[^>]*src=["']([^"']+\.mp4[^"']*)["']""", RegexOption.IGNORE_CASE)
        jwPattern.find(html)?.groupValues?.get(1)?.let { videoUrl ->
            if (videoUrl.contains("storage.googleapis.com") && !videoUrl.contains("banner") && !videoUrl.contains("ads")) {
                val quality = when {
                    videoUrl.contains("360p") -> 360
                    videoUrl.contains("480p") -> 480
                    videoUrl.contains("720p") -> 720
                    videoUrl.contains("1080p") -> 1080
                    else -> Qualities.Unknown.value
                }
                newExtractorLink(
                    source = name,
                    name = "\( name ( \){quality}p)",
                    url = videoUrl,
                    referer = url,
                    quality = quality,
                    isM3u8 = false
                ).let { callback(it) }
                return true
            }
        }

        // Fallback MP4 genérico (pra Google Storage direto)
        val mp4Pattern = Regex("""https?://storage\.googleapis\.com/[^"'<>\s]+\.mp4""")
        mp4Pattern.findAll(html).forEach { match ->
            val videoUrl = match.value
            if (videoUrl.length > 50 && !videoUrl.contains("banner") && !videoUrl.contains("ads")) {
                newExtractorLink(
                    source = name,
                    name = name,
                    url = videoUrl,
                    referer = url,
                    quality = Qualities.Unknown.value,
                    isM3u8 = false
                ).let { callback(it) }
                return true
            }
        }

        // Fallback pra loadExtractor (upns.one pra embedone, filmes e alguns episódios)
        loadExtractor(url, mainUrl, subtitleCallback, callback)
    } catch (e: Exception) {
        e.printStackTrace()
        false
    }
  }
}