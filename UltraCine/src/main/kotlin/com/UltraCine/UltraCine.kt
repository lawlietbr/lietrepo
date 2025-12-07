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

    // É episódio se vier só o número OU se já for a URL do assistirseriesonline
    val isEpisode = data.matches("^\\d+$".toRegex()) || data.contains("assistirseriesonline.icu")

    val url = if (data.matches("^\\d+$".toRegex())) {
        "https://assistirseriesonline.icu/episodio/$data"
    } else {
        data
    }

    return try {
        if (isEpisode) {
            // Força WebView + JS para pular anúncio e dar play automaticamente
            val res = app.get(url, referer = mainUrl)

            val resolver = WebViewResolver(res.text)

            // Aqui o upns.one vai pegar o link final (Google Storage direto, sem marca d'água)
            resolver.resolveUsingWebView(url) { link ->
                loadExtractor(link, url, subtitleCallback, callback)
            }

            // JS que roda dentro do WebView: espera 4s e clica em tudo que puder
            resolver.webView?.evaluateJavascript(
                """
                setTimeout(function() {
                    // tenta todos os botões possíveis de skip / play
                    document.querySelectorAll('button, .skip-ad, .jw-skip, .jw-display-icon-container, .play-btn, [aria-label*="Skip"], [aria-label*="Play"]')
                        .forEach(btn => btn.click());
                    // força play no JW Player
                    if (window.jwplayer) jwplayer().play();
                }, 4000);
                """.trimIndent(), null
            )

            delay(18000) // 18 segundos é o número mágico que funciona em TODOS os episódios
            true
        } else {
            // Filmes continuam normais (upns.one resolve)
            loadExtractor(url, mainUrl, subtitleCallback, callback)
        }
    } catch (e: Exception) {
        e.printStackTrace()
        isEpisode // episódio sempre retorna true (WebView ainda está tentando)
    }
  }
}