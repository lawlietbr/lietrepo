package com.UltraCine

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addActors
import com.lagradost.cloudstream3.LoadResponse.Companion.addTrailer

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
        "$mainUrl/category/misterio/" to "Mistério",
        "$mainUrl/category/romance/" to "Romance",
        "$mainUrl/category/terror/" to "Terror",
        "$mainUrl/category/thriller/" to "Thriller"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val document = app.get(request.data + if (page > 1) "page/$page/" else "").document
        val home = document.select("div.aa-cn div#movies-a ul.post-lst li").mapNotNull { it.toSearchResult() }
        return newHomePageResponse(request.name, home)
    }

    private fun org.jsoup.nodes.Element.toSearchResult(): SearchResponse? {
        val title = this.selectFirst("header.entry-header h2.entry-title")?.text() ?: return null
        val href = this.selectFirst("a.lnk-blk")?.attr("href") ?: return null
        val posterUrl = this.selectFirst("div.post-thumbnail figure img")?.let { img ->
            val src = img.attr("src").takeIf { it.isNotBlank() } ?: img.attr("data-src")
            src?.replace("/w500/", "/original/")
        }
        val year = this.selectFirst("span.year")?.text()?.toIntOrNull()

        return newMovieSearchResponse(title, href, TvType.Movie) {
            this.posterUrl = posterUrl
            this.year = year
            this.quality = getQualityFromString(this@toSearchResult.selectFirst("span.post-ql")?.text())
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val url = "\( mainUrl/?s= \){query.encodeUrl()}"
        val document = app.get(url).document
        return document.select("div.aa-cn div#movies-a ul.post-lst li").mapNotNull { it.toSearchResult() }
    }

    override suspend fun load(url: String): LoadResponse? {
        val document = app.get(url).document

        val title = document.selectFirst("aside.fg1 header.entry-header h1.entry-title")?.text() ?: return null
        val poster = document.selectFirst("div.bghd img.TPostBg")?.let { img ->
            (img.attr("src").takeIf { it.isNotBlank() } ?: img.attr("data-src"))?.replace("/w1280/", "/original/")
        }
        val year = document.selectFirst("aside.fg1 span.year")?.text()?.toIntOrNull()
        val plot = document.selectFirst("aside.fg1 div.description p")?.text()
        val genres = document.select("aside.fg1 span.genres a").map { it.text() }
        val actors = document.select("aside.fg1 ul.cast-lst a").map { Actor(it.text(), it.attr("href")) }
        val trailer = document.selectFirst("div.mdl-cn div.video iframe")?.attr("src")

        val iframeUrl = document.selectFirst("iframe[src*='assistirseriesonline']")
            ?.let { it.attr("src").takeIf { it.isNotBlank() } ?: it.attr("data-src") }

        val isSerie = url.contains("/serie/")

        return if (isSerie) {
            newTvSeriesLoadResponse(title, url, TvType.TvSeries, iframeUrl?.let { listOf(newEpisode(it)) } ?: emptyList()) {
                this.posterUrl = poster
                this.year = year
                this.plot = plot
                this.tags = genres
                addActors(actors)
                addTrailer(trailer)
            }
        } else {
            newMovieLoadResponse(title, url, TvType.Movie, iframeUrl ?: "") {
                this.posterUrl = poster
                this.year = year
                this.plot = plot
                this.tags = genres
                addActors(actors)
                addTrailer(trailer)
            }
        }
    }

    override suspend fun loadLinks(
    data: String,
    isCasting: Boolean,
    subtitleCallback: (SubtitleFile) -> Unit,
    callback: (ExtractorLink) -> Unit
): Boolean {
    if (data.isBlank()) return false

    val url = if (data.matches(Regex("^\\d+$"))) {
        "https://assistirseriesonline.icu/episodio/$data"
    } else if (data.startsWith("http")) {
        data
    } else return false

    try {
        val doc = app.get(url).document

        doc.selectFirst("button[data-source*='embedplay.upns.pro'], button[data-source*='embedplay.upn.one']")?.let {
            val link = it.attr("data-source")
            if (link.isNotBlank()) {
                loadExtractor(link, url, subtitleCallback, callback)
                return true
            }
        }

        doc.selectFirst("div.play-overlay div#player iframe")?.attr("src")?.takeIf { it.isNotBlank() }?.let {
            loadExtractor(it, url, subtitleCallback, callback)
            return true
        }

        doc.selectFirst("iframe[src*='assistirseriesonline'], iframe[data-src*='assistirseriesonline']")?.let {
            val src = it.attr("src").takeIf { s -> s.isNotBlank() } ?: it.attr("data-src")
            if (src.isNotBlank()) {
                loadExtractor(src, url, subtitleCallback, callback)
                return true
            }
        }
    } catch (e: Exception) {
        // silencioso
    }

    return false
}