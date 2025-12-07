package com.UltraCine

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addActors
import com.lagradost.cloudstream3.LoadResponse.Companion.addTrailer
import com.lagradost.cloudstream3.network.WebViewResolver
import org.jsoup.nodes.Element
import kotlinx.coroutines.delay  // ← IMPORT QUE FALTAVA

class UltraCine : MainAPI() {
    override var mainUrl = "https://ultracine.org"
    override var name = "UltraCine"
    override val hasMainPage = true
    override var lang = "pt-br"
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)

    private fun getUserAgent(): String =
        "Mozilla/5.0 (Linux; Android 14; SM-G998B) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36"

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
        val posterUrl = selectFirst("div.post-thumbnail figure img")?.attr("src")
            ?.takeIf { it.isNotBlank() }
            ?.let { fixUrl(it).replace("/w500/", "/original/") }
            ?: selectFirst("div.post-thumbnail figure img")?.attr("data-src")
                ?.takeIf { it.isNotBlank() }
                ?.let { fixUrl(it).replace("/w500/", "/original/") }

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
        val poster = document.selectFirst("div.bghd img.TPostBg")?.attr("src")
            ?.takeIf { it.isNotBlank() }
            ?.let { fixUrl(it).replace("/w1280/", "/original/") }
            ?: document.selectFirst("div.bghd img.TPostBg")?.attr("data-src")
                ?.takeIf { it.isNotBlank() }
                ?.let { fixUrl(it).replace("/w1280/", "/original/") }

        val yearText = document.selectFirst("aside.fg1 header.entry-header div.entry-meta span.year")?.ownText()
        val year = yearText?.toIntOrNull()
        val durationText = document.selectFirst("aside.fg1 header.entry-header div.entry-meta span.duration")?.ownText()
        val plot = document.selectFirst("aside.fg1 div.description p")?.text()
        val tags = document.select("aside.fg1 header.entry-header div.entry-meta span.genres a").map { it.text() }
        val actors = document.select("aside.fg1 ul.cast-lst p a").map { Actor(it.text(), it.attr("href")) }
        val trailer = document.selectFirst("div.mdl-cn div.video iframe")?.attr("src")
            ?.takeIf { it.isNotBlank() } ?: document.selectFirst("div.mdl-cn div.video iframe")?.attr("data-src")

        val iframeUrl = document.selectFirst("iframe[src*='assistirseriesonline']")?.attr("src")
            ?.takeIf { it.isNotBlank() } ?: document.selectFirst("iframe[src*='assistirseriesonline']")?.attr("data-src")

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
                val epId = epEl.attr("data-episode-id").takeIf { it.isNotBlank() } ?: return@mapNotNull null
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

        val finalUrl = when {
            data.matches(Regex("^\\d+$")) -> "https://assistirseriesonline.icu/episodio/$data"
            data.contains("ultracine.org/") && data.matches(Regex(".*/\\d+$")) -> {
                val id = data.substringAfterLast("/")
                "https://assistirseriesonline.icu/episodio/$id"
            }
            else -> data
        }

        val isEpisode = data.matches(Regex("^\\d+$"))

        try {
            val res = app.get(finalUrl, referer = mainUrl, headers = mapOf("User-Agent" to getUserAgent()))
            val html = res.text

            if (isEpisode) {
                // WEBVIEW OBRIGATÓRIO PARA EPISÓDIOS
                WebViewResolver(res.text).resolveUsingWebView(res.url) { link ->
                    if (link.isNotBlank() &&
                        (link.contains(".mp4") || link.contains(".m3u8") || link.contains("googlevideo.com")) &&
                        !link.contains("banner") && !link.contains("ads")
                    ) {
                        val quality = when {
                            link.contains("360p") || link.contains("/360/") -> Qualities.P360.value
                            link.contains("480p") || link.contains("/480/") -> Qualities.P480.value
                            link.contains("720p") || link.contains("/720/") -> Qualities.P720.value
                            link.contains("1080p") || link.contains("/1080/") -> Qualities.P1080.value
                            else -> Qualities.Unknown.value
                        }

                        callback.invoke(
                            ExtractorLink(
                                source = name,
                                name = "\( name ( \){quality}p)",
                                url = link,
                                referer = finalUrl,
                                quality = quality,
                                isM3u8 = link.contains(".m3u8")
                            )
                        )
                    }
                }

                // Dá tempo pro WebView pular anúncio e clicar play
                delay(9000)
                return true // CloudStream espera o WebView
            }

            // FILMES: tenta regex rápido (funciona na maioria)
            val jwPattern = Regex("""<video[^>]+class=["'][^"']*jw[^"']*["'][^>]+src=["'](https?://[^"']+\.mp4[^"']*)["']""")
            jwPattern.find(html)?.groupValues?.get(1)?.let { url ->
                if (!url.contains("banner") && !url.contains("ads")) {
                    val quality = when {
                        url.contains("360p") -> Qualities.P360.value
                        url.contains("480p") -> Qualities.P480.value
                        url.contains("720p") -> Qualities.P720.value
                        url.contains("1080p") -> Qualities.P1080.value
                        else -> Qualities.Unknown.value
                    }
                    callback.invoke(
                        ExtractorLink(name, "\( name ( \){quality}p)", url, finalUrl, quality, isM3u8 = false)
                    )
                    return true
                }
            }

            // Fallback genérico MP4
            Regex("""(https?://[^"']+\.mp4[^"']*)""").findAll(html).forEach { match ->
                val url = match.value
                if (url.length > 50 && !url.contains("banner") && !url.contains("ads")) {
                    callback.invoke(
                        ExtractorLink(name, name, url, finalUrl, Qualities.Unknown.value, isM3u8 = false)
                    )
                    return true
                }
            }

            // Iframes antigos (EmbedPlay)
            res.document.select("iframe").forEach { iframe ->
                val src = iframe.attr("src")
                if (src.isNotBlank() && loadExtractor(src, finalUrl, subtitleCallback, callback)) return true
            }

            return false
        } catch (e: Exception) {
            e.printStackTrace()
            return isEpisode // Episódios retornam true mesmo com erro (WebView ainda tenta)
        }
    }
}