package com.UltraCine

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addActors
import com.lagradost.cloudstream3.LoadResponse.Companion.addTrailer
import org.jsoup.nodes.Element

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

    private fun Element.toSearchResult(): SearchResponse? {
        val title = selectFirst("header.entry-header h2.entry-title")?.text() ?: return null
        val href = selectFirst("a.lnk-blk")?.attr("href") ?: return null
        val posterUrl = selectFirst("div.post-thumbnail figure img")?.let { img ->
            val src = img.attr("src").takeIf { it.isNotBlank() } ?: img.attr("data-src")
            src?.let { if (it.startsWith("//")) "https:$it" else it }?.replace("/w500/", "/original/")
        }
        val year = selectFirst("span.year")?.text()?.toIntOrNull()

        return newMovieSearchResponse(title, href, TvType.Movie) {
            this.posterUrl = posterUrl
            this.year = year
            this.quality = getQualityFromString(selectFirst("span.post-ql")?.text())
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val document = app.get("\( mainUrl/?s= \){query.urlEncode()}").document
        return document.select("div.aa-cn div#movies-a ul.post-lst li").mapNotNull { it.toSearchResult() }
    }

    override suspend fun load(url: String): LoadResponse? {
        val document = app.get(url).document

        val title = document.selectFirst("aside.fg1 header.entry-header h1.entry-title")?.text() ?: return null
        val poster = document.selectFirst("div.bghd img.TPostBg")?.let { img ->
            val src = img.attr("src").takeIf { it.isNotBlank() } ?: img.attr("data-src")
            src?.let { if (it.startsWith("//")) "https:$it" else it }?.replace("/w1280/", "/original/")
        }
        val year = document.selectFirst("span.year")?.text()?.toIntOrNull()
        val plot = document.selectFirst("aside.fg1 div.description p")?.text()
        val tags = document.select("aside.fg1 span.genres a").map { it.text() }
        val actors = document.selectFirst("aside.fg1 ul.cast-lst p")?.select("a")?.map { Actor(it.text(), it.attr("href")) }
        val trailer = document.selectFirst("div.mdl-cn div.video iframe")?.attr("src")

        val isSerie = url.contains("/serie/")

        return if (isSerie) {
            newTvSeriesLoadResponse(title, url, TvType.TvSeries, url) {
                this.posterUrl = poster
                this.year = year
                this.plot = plot
                this.tags = tags
                if (actors != null) addActors(actors)
                addTrailer(trailer)
            }
        } else {
            val iframeUrl = document.selectFirst("iframe[src*='assistirseriesonline'], iframe[data-src*='assistirseriesonline']")
                ?.let { it.attr("src").takeIf { s -> s.isNotBlank() } ?: it.attr("data-src") } ?: ""
            newMovieLoadResponse(title, url, TvType.Movie, iframeUrl) {
                this.posterUrl = poster
                this.year = year
                this.plot = plot
                this.tags = tags
                if (actors != null) addActors(actors)
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

        val url = when {
            data.matches(Regex("^\\d+$")) -> "https://assistirseriesonline.icu/episodio/$data"
            data.startsWith("http") -> data
            else -> return false
        }

        try {
            val res = app.get(url, referer = "https://ultracine.org/", timeout = 30)
            val html = res.text

            // Regex que pega o link direto do embedplay (o que tem 4K/HDR)
            val regex = Regex("""["'](?:file|src|source)["']?\s*:\s*["'](https?://[^"']+embedplay[^"']+)""")
            val match = regex.find(html)

            if (match != null) {
                var videoUrl = match.groupValues[1]

                // Força o player oficial do UltraCine (melhor qualidade + tela cheia)
                videoUrl = if (videoUrl.contains("embedplay.upns.pro") || videoUrl.contains("embedplay.upn.one")) {
                    val id = videoUrl.substringAfterLast("/").substringBefore("?")
                    "https://player.ultracine.org/watch/$id"
                } else videoUrl

                callback.invoke(
                    ExtractorLink(
                        "UltraCine",
                        "UltraCine 4K • Tela Cheia",
                        videoUrl,
                        "https://ultracine.org/",
                        Qualities.Unknown.value,
                        isM3u8 = true
                    )
                )
                return true
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }

        return false
    }

    private fun parseDuration(duration: String?): Int? {
        if (duration == null) return null
        val regex = Regex("(\\d+)h\\s*(\\d+)m")
        val m = regex.find(duration)
        return if (m != null) {
            val h = m.groupValues[1].toIntOrNull() ?: 0
            val min = m.groupValues[2].toIntOrNull() ?: 0
            h * 60 + min
        } else {
            Regex("(\\d+)m").find(duration)?.groupValues?.get(1)?.toIntOrNull()
        }
    }

    private fun getQualityFromString(qualityStr: String?): Int {
        return when {
            qualityStr?.contains("4k", ignoreCase = true) == true -> Qualities.P2160.value
            qualityStr?.contains("1080p", ignoreCase = true) == true -> Qualities.P1080.value
            qualityStr?.contains("720p", ignoreCase = true) == true -> Qualities.P720.value
            qualityStr?.contains("480p", ignoreCase = true) == true -> Qualities.P480.value
            else -> Qualities.Unknown.value
        }
    }
}