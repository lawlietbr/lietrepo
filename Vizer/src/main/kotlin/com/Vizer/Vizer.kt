package com.Vizer

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities

class Vizer : MainAPI() {
    override var mainUrl = "https://vizer.tv.tld"
    override var name = "Vizer"
    override val hasMainPage = true
    override var lang = "pt-br"                    // ← var String (obrigatório agora)
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)

    override val mainPage = mainPageOf(
        "$mainUrl/filmes-online" to "Filmes",
        "$mainUrl/series-online" to "Séries",
        "$mainUrl/lancamentos" to "Lançamentos"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = if (page > 1) "${request.data}/page/$page" else request.data
        val doc = app.get(url).document
        val items = doc.select("div.item").mapNotNull { it.toSearchResponse() }
        return newHomePageResponse(request.name, items)
    }

    private fun org.jsoup.nodes.Element.toSearchResponse(): SearchResponse? {
        val title = selectFirst("h2")?.text() ?: selectFirst(".title")?.text() ?: return null
        val href = selectFirst("a")?.attr("href") ?: return null
        val poster = selectFirst("img")?.attr("src") ?: selectFirst("img")?.attr("data-src")
        val isSeries = href.contains("/serie/") || title.contains("Temporada")

        return if (isSeries) {
            newTvSeriesSearchResponse(title, fixUrl(href), TvType.TvSeries) { posterUrl = poster }
        } else {
            newMovieSearchResponse(title, fixUrl(href), TvType.Movie) { posterUrl = poster }
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val doc = app.get("$mainUrl/?s=$query").document
        return doc.select("div.item").mapNotNull { it.toSearchResponse() }
    }

    override suspend fun load(url: String): LoadResponse {
        val doc = app.get(url).document
        val title = doc.selectFirst("h1")?.text() ?: doc.selectFirst(".title")?.text() ?: ""
        val poster = doc.selectFirst(".poster img")?.attr("src") ?: doc.selectFirst("img")?.attr("data-src")
        val plot = doc.selectFirst(".sinopse")?.text() ?: doc.selectFirst(".description")?.text()

        return if (url.contains("/serie/")) {
            val episodes = doc.select(".episodios a").mapNotNull {
                val epUrl = it.attr("href")
                val epName = it.text()
                newEpisode(fixUrl(epUrl)) { name = epName }
            }
            newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
                this.posterUrl = poster
                this.plot = plot
            }
        } else {
            newMovieLoadResponse(title, url, TvType.Movie, url) {
                this.posterUrl = poster
                this.plot = plot
            }
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val doc = app.get(data).document

        doc.select("iframe").forEach {
            val src = it.attr("src")
            if (src.isNotBlank()) loadExtractor(src, data, subtitleCallback, callback)
        }

        doc.select("source, video source").forEach {
            val src = it.attr("src")
            if (src.isNotBlank()) {
                callback(ExtractorLink(
                    source = name,
                    name = name,
                    url = src,
                    referer = mainUrl,
                    quality = Qualities.Unknown.value,
                    type = ExtractorLinkType.M3U8
                ))
            }
        }

        return true
    }

    private fun fixUrl(url: String): String {
        return if (url.startsWith("/")) "$mainUrl$url" else url
    }
}