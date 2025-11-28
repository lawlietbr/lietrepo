package com.SuperFlix

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.extractors.*

class SuperFlix : MainAPI() {
    override var name = "SuperFlix"
    override var lang = "pt"
    override val hasMainPage = true
    override val hasDownloadSupport = true
    override val hasChromecastSupport = true
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)

    override val mainUrl = "https://superflix20.lol"
    private val apiUrl = "https://superflix20.lol"

    override val mainPage = mainPageOf(
        "filmes" to "Filmes",
        "series" to "Séries",
        "lancamentos" to "Lançamentos",
        "em-alta" to "Em Alta"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = "\( apiUrl/ \){request.data}"
        val doc = app.get(url).document
        val items = doc.select("div.items article").mapNotNull { it.toSearchResult() }
        return newHomePageResponse(request.name, items)
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val title = this.selectFirst("h2.Title")?.text() ?: return null
        val href = fixUrl(this.selectFirst("a")!!.attr("href"))
        val posterUrl = this.selectFirst("img")?.attr("src")?.let { fixUrl(it) }
        val isMovie = this.selectFirst("span.Year") != null
        return newMovieSearchResponse(title, href, if (isMovie) TvType.Movie else TvType.TvSeries) {
            this.posterUrl = posterUrl
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val url = "\( apiUrl/?s= \){query}"
        val doc = app.get(url).document
        return doc.select("div.Result article").mapNotNull { it.toSearchResult() }
    }

    override suspend fun load(url: String): LoadResponse {
        val doc = app.get(url).document
        val title = doc.selectFirst("h1.Title")!!.text()
        val poster = doc.selectFirst("div.Image img")?.attr("src")?.let { fixUrl(it) }
        val plot = doc.selectFirst("div.Description p")?.text()
        val year = doc.selectFirst("span.Year")?.text()?.toIntOrNull()
        val tags = doc.select("div.Genre a").map { it.text() }

        val isSeries = doc.select("div.Seasons").isNotEmpty()

        return if (isSeries) {
            val episodes = mutableListOf<Episode>()
            doc.select("div.Season").forEach { seasonBlock ->
                val season = seasonBlock.selectFirst("span.Title")!!.text().replace(Regex("[^0-9]"), "").toIntOrNull() ?: 1
                seasonBlock.select("li a").forEach { ep ->
                    val epNum = ep.selectFirst("span.Num")?.text()?.toIntOrNull() ?: 1
                    val epName = ep.selectFirst("h3")?.text() ?: "Episódio $epNum"
                    val epUrl = fixUrl(ep.attr("href"))
                    episodes.add(Episode(epUrl, name = epName, season = season, episode = epNum))
                }
            }
            newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
                this.posterUrl = poster
                this.plot = plot
                this.tags = tags
                this.year = year
            }
        } else {
            newMovieLoadResponse(title, url, TvType.Movie, url) {
                this.posterUrl = poster
                this.plot = plot
                this.tags = tags
                this.year = year
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
        doc.select("div.Player iframe, div.Player source").forEach { element ->
            val src = element.attr("src").takeIf { it.isNotBlank() } ?: return@forEach
            val link = fixUrl(src)

            if (link.contains("m3u8")) {
                callback.invoke(
                    ExtractorLink(
                        source = name,
                        name = "$name - M3U8",
                        url = link,
                        referer = mainUrl,
                        quality = Qualities.Unknown.value,
                        isM3u8 = true
                    )
                )
            }
        }
        return true
    }
}
