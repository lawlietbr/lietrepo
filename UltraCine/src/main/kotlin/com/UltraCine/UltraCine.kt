package com.UltraCine

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addActors
import com.lagradost.cloudstream3.LoadResponse.Companion.addTrailer
import org.jsoup.nodes.Element
import java.net.URLEncoder
import com.lagradost.cloudstream3.utils.AppUtils.intToScore

class UltraCine : MainAPI() {
    override var mainUrl = "https://ultracine.org"
    override var name = "UltraCine"
    override val hasMainPage = true
    override var lang = "pt"
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
        val url = request.data + if (page > 1) "page/$page/" else ""
        val doc = app.get(url).document
        val items = doc.select("div.aa-cn ul.post-lst li").mapNotNull { it.toSearchResult() }
        return newHomePageResponse(request.name, items)
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val titleEl = selectFirst("h2.entry-title, h3") ?: return null
        val href = selectFirst("a.lnk-blk")?.attr("href") ?: return null
        val poster = selectFirst("img")?.attr("src")?.takeIf { it.isNotBlank() }
            ?: selectFirst("img")?.attr("data-src")

        val year = selectFirst("span.year")?.text()?.toIntOrNull()

        return newMovieSearchResponse(titleEl.text(), href, TvType.Movie) {
            this.posterUrl = poster?.let { fixUrl(it) }
            this.year = year
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val url = "$mainUrl/?s=${java.net.URLEncoder.encode(query, "UTF-8")}"
        val doc = app.get(url).document
        return doc.select("div.aa-cn ul.post-lst li").mapNotNull { it.toSearchResult() }
    }

    override suspend fun load(url: String): LoadResponse? {
        val doc = app.get(url).document

        val title = doc.selectFirst("h1.entry-title")?.text() ?: return null
        val poster = doc.selectFirst("div.bghd img, img.TPostBg")
            ?.attr("src")?.takeIf { it.isNotBlank() }?.let { fixUrl(it) }

        val year = doc.selectFirst("span.year")?.text()
            ?.replace(Regex("\\D"), "")?.toIntOrNull()

        val durationText = doc.selectFirst("span.duration")?.text().orEmpty()
        val duration = parseDuration(durationText)

        // 8.5 → 8500
        val ratingInt = doc.selectFirst("div.vote span.num, .rating span")?.text()
            ?.toDoubleOrNull()?.times(1000)?.toInt()

        val plot = doc.selectFirst("div.description p, .sinopse")?.text()
        val tags = doc.select("span.genres a, .category a").map { it.text() }

        val actors = doc.select("ul.cast-lst a").mapNotNull {
            val name = it.text().trim()
            val img = it.selectFirst("img")?.attr("src")
            if (name.isNotBlank()) Actor(name, img) else null
        }

        val trailer = doc.selectFirst("div.video iframe, iframe[src*=youtube]")?.attr("src")

        val playerIframe = doc.selectFirst("iframe[src*='assistirseriesonline'], iframe[src*='embedplay']")
            ?.attr("src")?.takeIf { it.isNotBlank() }

        val isTvSeries = url.contains("/serie/") || doc.select("div.seasons").isNotEmpty()

        return if (isTvSeries && playerIframe != null) {
            val episodes = mutableListOf<Episode>()
            try {
                val iframeDoc = app.get(playerIframe).document
                iframeDoc.select("li[data-episode-id]").forEach { ep ->
                    val epId = ep.attr("data-episode-id")
                    val name = ep.text().trim()
                    val season = ep.parent()?.attr("data-season-number")?.toIntOrNull()
                    val episodeNum = name.substringBefore(" - ").toIntOrNull() ?: 1

                    if (epId.isNotBlank()) {
                        episodes += newEpisode(epId) {
                            this.name = name.substringAfter(" - ").ifBlank { "Episódio $episodeNum" }
                            this.season = season
                            this.episode = episodeNum
                        }
                    }
                }
            } catch (_: Exception) {}

            newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
                this.posterUrl = poster
                this.year = year
                this.plot = plot
                this.tags = tags
                this.score = ratingInt?.let { intToScore(it) }          // ← CORRETO
                addActors(actors)
                trailer?.let { addTrailer(it) }
            }
        } else {
            newMovieLoadResponse(title, url, TvType.Movie, playerIframe ?: url) {
                this.posterUrl = poster
                this.year = year
                this.plot = plot
                this.tags = tags
                this.duration = duration
                this.score = ratingInt?.let { intToScore(it) }         // ← CORRETO
                addActors(actors)
                trailer?.let { addTrailer(it) }
            }
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val link = if (data.matches(Regex("^\\d+$"))) {
            "https://assistirseriesonline.icu/episodio/$data/"
        } else data

        try {
            val doc = app.get(link, referer = mainUrl).document

            doc.select("button[data-source]").forEach {
                val src = it.attr("data-source")
                if (src.isNotBlank()) {
                    callback(
                        ExtractorLink(
                            source = name,
                            name = "$name 4K",
                            url = src,
                            referer = link,
                            quality = Qualities.Unknown.value,
                            type = ExtractorLinkType.VIDEO
                        )
                    )
                }
            }

            doc.select("iframe").forEach { iframe ->
                val src = iframe.attr("src")
                if (src.isNotBlank() && src.startsWith("http")) {
                    callback(
                        ExtractorLink(
                            source = name,
                            name = name,
                            url = src,
                            referer = link,
                            quality = Qualities.Unknown.value,
                            type = ExtractorLinkType.VIDEO
                        )
                    )
                }
            }
        } catch (_: Exception) {}

        return true
    }

    private fun parseDuration(text: String): Int? {
        if (text.isBlank()) return null
        val h = Regex("(\\d+)h").find(text)?.groupValues?.get(1)?.toIntOrNull() ?: 0
        val m = Regex("(\\d+)m").find(text)?.groupValues?.get(1)?.toIntOrNull() ?: 0
        return if (h > 0 || m > 0) h * 60 + m else null
    }
}
